package eu.kanade.presentation.manga.components

import android.content.Context
import eu.kanade.tachiyomi.source.CatalogueSource
import mihon.domain.manga.model.toDomainManga
import tachiyomi.domain.manga.model.Manga
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
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
 * Recomendaciones dentro de la ficha: mangas parecidos (según AniList) buscados en la misma
 * extensión del manga, para abrirlos directamente. Si no hay, se muestran los populares de la extensión.
 */
@Composable
fun MangaRecommendations(
    manga: Manga,
    onClickManga: (Manga) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val result by produceState<Pair<String, List<Manga>>?>(initialValue = null, manga.id) {
        value = ExtensionRecommendations.get(context, manga)
    }
    val (sourceName, mangas) = result ?: return
    if (mangas.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(MR.strings.manga_recommendations_in, sourceName),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(mangas, key = { it.id }) { item ->
                Column(modifier = Modifier.width(110.dp)) {
                    MangaCover.Book(
                        data = item,
                        contentDescription = item.title,
                        onClick = { onClickManga(item) },
                        modifier = Modifier.fillMaxWidth(),
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

private object ExtensionRecommendations {
    private const val MAX_TITLES = 8
    private const val MAX_POPULAR = 15
    private val cache = ConcurrentHashMap<Long, Pair<String, List<Manga>>>()

    suspend fun get(context: Context, manga: Manga): Pair<String, List<Manga>>? {
        cache[manga.id]?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val graph = context.appGraph
                val source = graph.sourceManager.get(manga.source) as? CatalogueSource
                    ?: return@withContext null
                val titles = AnilistDiscovery.recommendations(graph.networkHelper.client, manga.title)
                    .take(MAX_TITLES)
                    .map { it.title }
                // Busca cada título recomendado en la extensión y se queda con el primer resultado
                val found = titles.mapNotNull { title ->
                    try {
                        source.getSearchManga(1, title, source.getFilterList()).mangas.firstOrNull()
                    } catch (e: Exception) {
                        null
                    }
                }
                val candidates = found.ifEmpty { source.getPopularManga(1).mangas.take(MAX_POPULAR) }
                val mangas = candidates
                    .map { it.toDomainManga(source.id) }
                    .filter { it.url != manga.url }
                    .distinctBy { it.url }
                    .let { graph.networkToLocalManga(it) }
                (source.name to mangas).also { cache[manga.id] = it }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Could not load extension recommendations for ${manga.title}" }
                null
            }
        }
    }
}

/** Fila horizontal de portadas con título. */
@Composable
fun CoverCarousel(
    title: String,
    items: List<MangaRecommendation>,
    onClick: (title: String) -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            trailing()
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.title }) { item ->
                Column(
                    modifier = Modifier
                        .width(110.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onClick(item.title) },
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

internal object AnilistDiscovery {
    private val cache = ConcurrentHashMap<String, List<MangaRecommendation>>()
    private var trendingCache: List<MangaRecommendation>? = null

    private const val TRENDING_QUERY = "{ Page(perPage: 20) { media(type: MANGA, sort: TRENDING_DESC, isAdult: false) { " +
        "title { romaji english } coverImage { large } } } }"

    /** Mangas en tendencia en AniList. */
    suspend fun trending(client: OkHttpClient): List<MangaRecommendation> {
        trendingCache?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val text = post(client, buildJsonObject { put("query", TRENDING_QUERY) }.toString())
                val media = (Json.parseToJsonElement(text) as? JsonObject)
                    ?.obj("data")
                    ?.obj("Page")
                    ?.get("media") as? JsonArray
                    ?: return@withContext emptyList()
                media.mapNotNull { (it as? JsonObject)?.toRecommendation() }
                    .distinctBy { it.title }
                    .also { trendingCache = it }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Could not load trending manga" }
                emptyList()
            }
        }
    }

    private suspend fun post(client: OkHttpClient, json: String): String {
        val request = Request.Builder()
            .url("https://graphql.anilist.co")
            .header("Accept", "application/json")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(request).awaitSuccess().use { it.body.string() }
    }

    private fun JsonObject.toRecommendation(): MangaRecommendation? {
        val titles = obj("title")
        val name = titles?.string("english") ?: titles?.string("romaji") ?: return null
        return MangaRecommendation(title = name, coverUrl = obj("coverImage")?.string("large"))
    }

    private const val QUERY = "query(\$search: String) { Media(search: \$search, type: MANGA) { " +
        "recommendations(sort: RATING_DESC, perPage: 20) { nodes { mediaRecommendation { " +
        "title { romaji english } coverImage { large } } } } } }"

    suspend fun recommendations(client: OkHttpClient, title: String): List<MangaRecommendation> {
        if (title.isBlank()) return emptyList()
        cache[title]?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val body = buildJsonObject {
                    put("query", QUERY)
                    putJsonObject("variables") { put("search", title) }
                }.toString()
                parse(post(client, body)).also { cache[title] = it }
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
            (node as? JsonObject)?.obj("mediaRecommendation")?.toRecommendation()
        }
            .distinctBy { it.title }
    }

    private fun JsonObject.obj(key: String) = get(key) as? JsonObject

    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.takeIf { it.isNotBlank() }
}
