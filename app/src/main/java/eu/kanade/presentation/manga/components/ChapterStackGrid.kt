package eu.kanade.presentation.manga.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.ui.manga.ChapterList
import eu.kanade.tachiyomi.ui.manga.ChapterPagesScreen
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.roundedfilled.Bookmark
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

private const val COLUMNS = 3

/**
 * Capítulos como montones de hojas, tres por fila. Al tocar uno se ven sus hojas para elegir desde
 * cuál empezar; mantener pulsado selecciona, como en la lista.
 */
internal fun LazyListScope.chapterStackGrid(
    manga: Manga,
    chapters: List<ChapterList>,
    isAnyChapterSelected: Boolean,
    onChapterSelected: (ChapterList.Item, Boolean, Boolean) -> Unit,
) {
    val rows = chapters.filterIsInstance<ChapterList.Item>().chunked(COLUMNS)
    items(
        items = rows,
        key = { row -> "chapter-row-${row.first().id}" },
        contentType = { "chapter-stack-row" },
    ) { row ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
        ) {
            row.forEach { item ->
                ChapterStackTile(
                    manga = manga,
                    item = item,
                    isAnyChapterSelected = isAnyChapterSelected,
                    onChapterSelected = onChapterSelected,
                    modifier = Modifier.weight(1f),
                )
            }
            repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterStackTile(
    manga: Manga,
    item: ChapterList.Item,
    isAnyChapterSelected: Boolean,
    onChapterSelected: (ChapterList.Item, Boolean, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalNavigator.currentOrThrow
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val chapter = item.chapter
    val shape = RoundedCornerShape(8.dp)
    val paper = MaterialTheme.colorScheme.surfaceContainerHighest
    val edge = MaterialTheme.colorScheme.outlineVariant

    Box(
        modifier = modifier
            .padding(horizontal = 8.dp, vertical = 12.dp)
            .aspectRatio(0.7f)
            .alpha(if (chapter.read && !item.selected) 0.55f else 1f)
            .combinedClickable(
                onClick = {
                    when {
                        item.selected -> onChapterSelected(item, false, false)
                        isAnyChapterSelected -> onChapterSelected(item, true, false)
                        else -> navigator.push(ChapterPagesScreen(manga.id, chapter.id))
                    }
                },
                onLongClick = {
                    onChapterSelected(item, !item.selected, true)
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
            ),
    ) {
        // Hojas de debajo, un poco giradas como un montón de papel
        listOf(-5f to 7.dp, 3f to 4.dp).forEach { (angle, shift) ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(x = shift, y = -shift / 2)
                    .rotate(angle)
                    .clip(shape)
                    .background(paper)
                    .border(1.dp, edge, shape),
            )
        }
        // Hoja de arriba
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    width = if (item.selected) 2.dp else 1.dp,
                    color = if (item.selected) MaterialTheme.colorScheme.primary else edge,
                    shape = shape,
                ),
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(manga).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.28f),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
                        ),
                    ),
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
            ) {
                Text(
                    text = stringResource(MR.strings.chapter_short),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (chapter.chapterNumber >= 0) formatChapterNumber(chapter.chapterNumber) else "?",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                if (manga.displayMode != Manga.CHAPTER_DISPLAY_NUMBER) {
                    Text(
                        text = chapter.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val progress = chapter.lastPageRead.takeIf { !chapter.read && it > 0L }
            if (progress != null) {
                Text(
                    text = stringResource(MR.strings.chapter_progress, progress + 1),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp),
                )
            }
            if (chapter.bookmark) {
                Icon(
                    imageVector = MaterialSymbols.RoundedFilled.Bookmark,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                )
            }
        }
    }
}
