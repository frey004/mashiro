package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.core.metro.AppCoroutineScope
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import kotlin.math.max

/**
 * Todas las hojas de un capítulo en cuadrícula, numeradas; al tocar una se empieza a leer desde ahí.
 */
class ChapterPagesScreen(
    private val mangaId: Long,
    private val chapterId: Long,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val viewModel = assistedMetroViewModel<Model, Model.Factory> { create(mangaId, chapterId) }
        val state by viewModel.state.collectAsState()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = state.title,
                    subtitle = state.pages?.size?.let { stringResource(MR.strings.chapter_pages_count, it) },
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            val pages = state.pages
            when {
                state.error != null -> EmptyScreen(
                    message = state.error!!,
                    modifier = Modifier.padding(contentPadding),
                )
                pages == null -> LoadingScreen(Modifier.padding(contentPadding))
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 92.dp),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = contentPadding.calculateTopPadding() + 8.dp,
                        bottom = contentPadding.calculateBottomPadding() + 96.dp,
                    ),
                ) {
                    itemsIndexed(pages, key = { index, _ -> index }) { index, page ->
                        PageThumbnail(
                            page = page,
                            number = index + 1,
                            load = viewModel::loadPage,
                            onClick = {
                                context.startActivity(
                                    ReaderActivity.newIntent(context, mangaId, chapterId, page = index),
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun PageThumbnail(
        page: ReaderPage,
        number: Int,
        load: suspend (ReaderPage) -> Unit,
        onClick: () -> Unit,
    ) {
        val context = LocalContext.current
        // La carga se cancela sola al salir la hoja de la pantalla
        val bitmap by produceState<Bitmap?>(initialValue = null, page) {
            launch { load(page) }
            page.statusFlow.first { it == Page.State.Ready }
            value = withContext(Dispatchers.IO) { decodeThumbnail(context, page) }
        }
        val shape = RoundedCornerShape(10.dp)
        Box(
            modifier = Modifier
                .padding(5.dp)
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onClick),
        ) {
            val image = bitmap
            if (image != null) {
                Image(
                    bitmap = remember(image) { image.asImageBitmap() },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .size(22.dp)
                        .align(Alignment.Center),
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            ) {
                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }

    @AssistedInject
    class Model(
        @Assisted("mangaId") private val mangaId: Long,
        @Assisted("chapterId") private val chapterId: Long,
        @AppCoroutineScope private val appScope: CoroutineScope,
        private val context: Context,
        private val getManga: GetManga,
        private val getChapter: GetChapter,
        private val sourceManager: SourceManager,
        private val downloadManager: DownloadManager,
        private val downloadProvider: DownloadProvider,
        private val chapterCache: ChapterCache,
    ) : ViewModel() {

        val state: StateFlow<State>
            field = MutableStateFlow(State())

        private var readerChapter: ReaderChapter? = null

        init {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val manga = getManga.await(mangaId) ?: error("Manga not found")
                    val chapter = getChapter.await(chapterId) ?: error("Chapter not found")
                    state.update { it.copy(title = chapter.name) }
                    val loader = ChapterLoader(
                        context,
                        downloadManager,
                        downloadProvider,
                        chapterCache,
                        appScope,
                        { false },
                        manga,
                        sourceManager.getOrStub(manga.source),
                    )
                    val reader = ReaderChapter(chapter)
                    loader.loadChapter(reader)
                    readerChapter = reader
                    state.update { it.copy(pages = reader.pages.orEmpty()) }
                } catch (e: Throwable) {
                    state.update { it.copy(error = e.message ?: e.toString()) }
                }
            }
        }

        suspend fun loadPage(page: ReaderPage) {
            readerChapter?.pageLoader?.loadPage(page)
        }

        override fun onCleared() {
            readerChapter?.pageLoader?.recycle()
        }

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(@Assisted("mangaId") mangaId: Long, @Assisted("chapterId") chapterId: Long): Model
        }
    }

    @Immutable
    data class State(
        val title: String = "",
        val pages: List<ReaderPage>? = null,
        val error: String? = null,
    )

    private companion object {
        const val THUMB_WIDTH = 320

        fun decodeThumbnail(context: Context, page: ReaderPage): Bitmap? {
            val stream = page.stream ?: return null
            return runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                stream().use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth > 0 && bounds.outWidth / (sample * 2) >= THUMB_WIDTH) sample *= 2
                stream().use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = max(1, sample) })
                } ?: stream().use { input ->
                    // Formatos que BitmapFactory no lee (AVIF, JXL...)
                    tachiyomi.decoder.ImageDecoder.newInstance(input)?.let { decoder ->
                        decoder.decode().also { decoder.recycle() }
                    }
                }
            }.getOrNull()
        }
    }
}
