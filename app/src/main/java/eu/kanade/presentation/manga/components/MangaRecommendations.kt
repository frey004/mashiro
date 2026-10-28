package eu.kanade.presentation.manga.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import logcat.LogPriority
import mihon.app.di.appGraph
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import java.util.concurrent.ConcurrentHashMap

@Immutable
data class MangaRecommendation(
    val title: String,
    val coverUrl: String?,
)

/**
 * Recomendaciones de mangas parecidos (datos públicos de AniList, sin iniciar sesión).
 * Al tocar una se busca ese título en todas las extensiones instaladas.
 */
@Composable
fun MangaRecommendations(
    title: String,
    onRecommendationClick: (title: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val client = remember { context.appGraph.networkHelper.client }
    val recommendations by produceState(initialValue = emptyList<MangaRecommendation>(), title) {
        value = RecommendationsRepository.get(client, title)
    }
    if (recommendations.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(MR.strings.manga_recommendations),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(recommendations, key = { it.title }) { item ->
                Column(
                    modifier = Modifier
                        .width(110.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onRecommendationClick(item.title) },
                ) {
                    AsyncImage(
                        model = item.coverUrl,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(MangaCover.Book.ratio)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                    )
                }
            }
        }
    }
}

private object RecommendationsRepository {
    private val cache = ConcurrentHashMap<String, List<MangaRecommendation>>()

    private const val QUERY = "query(\$search: String) { Media(search: \$search, type: MANGA) { " +
        "recommendations(sort: RATING_DESC, perPage: 20) { nodes { mediaRecommendation { " +
        "title { romaji english } coverImage { large } } } } } }"

    suspend fun get(client: OkHttpClient, title: String): List<MangaRecommendation> {
        if (title.isBlank()) return emptyList()
        cache[title]?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val body = buildJsonObject {
                    put("query", QUERY)
                    putJsonObject("variables") { put("search", title) }
                }.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("https://graphql.anilist.co")
                    .header("Accept", "application/json")
                    .post(body)
                    .build()
                val text = client.newCall(request).awaitSuccess().use { it.body.string() }
                parse(text).also { cache[title] = it }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Could not load recommendations for $title" }
                emptyList()
            }
        }
    }

    private fun parse(text: String): List<MangaRecommendation> {
        val media = (Json.parseToJsonElement(text) as? JsonObject)
            ?.obj("data")
            ?.obj("Media")
            ?: return emptyList()
        val nodes = media.obj("recommendations")?.get("nodes") as? JsonArray ?: return emptyList()
        return nodes.mapNotNull { node ->
            val rec = (node as? JsonObject)?.obj("mediaRecommendation") ?: return@mapNotNull null
            val titles = rec.obj("title")
            val name = titles?.string("english") ?: titles?.string("romaji") ?: return@mapNotNull null
            MangaRecommendation(title = name, coverUrl = rec.obj("coverImage")?.string("large"))
        }
            .distinctBy { it.title }
    }

    private fun JsonObject.obj(key: String) = get(key) as? JsonObject

    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.takeIf { it.isNotBlank() }
}
