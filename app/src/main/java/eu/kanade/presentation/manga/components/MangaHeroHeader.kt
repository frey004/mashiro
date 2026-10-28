package eu.kanade.presentation.manga.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.system.copyToClipboard
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Book
import mihon.icons.materialsymbols.rounded.Check
import mihon.icons.materialsymbols.rounded.Favorite
import mihon.icons.materialsymbols.rounded.HourglassEmpty
import mihon.icons.materialsymbols.rounded.Public
import mihon.icons.materialsymbols.rounded.Sync
import mihon.icons.materialsymbols.roundedfilled.Favorite
import mihon.icons.materialsymbols.roundedfilled.Warning
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.clickableNoIndication
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Instant

private val CardShape = RoundedCornerShape(24.dp)

/** Cabecera de la ficha: portada centrada, título, autor y etiquetas de estado y fuente. */
@Composable
fun MangaHeroHeader(
    appBarPadding: Dp,
    manga: Manga,
    source: Source,
    isStubSource: Boolean,
    onCoverClick: () -> Unit,
    doSearch: (query: String, global: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Box(modifier = modifier.fillMaxWidth()) {
        val backdropGradient = listOf(Color.Transparent, MaterialTheme.colorScheme.background)
        AsyncImage(
            model = ImageRequest.Builder(context).data(manga).crossfade(true).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .matchParentSize()
                .drawWithContent {
                    drawContent()
                    drawRect(brush = Brush.verticalGradient(colors = backdropGradient))
                }
                .blur(24.dp)
                .alpha(0.45f),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, top = appBarPadding + 16.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MangaCover.Book(
                modifier = Modifier.width(170.dp),
                data = ImageRequest.Builder(context).data(manga).crossfade(true).build(),
                contentDescription = stringResource(MR.strings.manga_cover),
                onClick = onCoverClick,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = manga.title.ifBlank { stringResource(MR.strings.unknown_title) },
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.clickableNoIndication(
                    onLongClick = { if (manga.title.isNotBlank()) context.copyToClipboard(manga.title, manga.title) },
                    onClick = { if (manga.title.isNotBlank()) doSearch(manga.title, true) },
                ),
            )
            val author = manga.author?.takeIf { it.isNotBlank() }
            Text(
                text = author ?: stringResource(MR.strings.unknown_author),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickableNoIndication(
                        onLongClick = { if (author != null) context.copyToClipboard(author, author) },
                        onClick = { if (author != null) doSearch(author, true) },
                    ),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoChip(text = statusText(manga.status))
                InfoChip(
                    text = source.getNameForMangaInfo(),
                    icon = if (isStubSource) MaterialSymbols.RoundedFilled.Warning else null,
                    iconTint = MaterialTheme.colorScheme.error,
                    onClick = { doSearch(source.searchQuery(), false) },
                )
            }
        }
    }
}

/** Botón grande de biblioteca y accesos a intervalo, seguimiento y WebView. */
@Composable
fun MangaPrimaryActions(
    favorite: Boolean,
    trackingCount: Int,
    nextUpdate: Instant?,
    isUserIntervalMode: Boolean,
    onAddToLibraryClicked: () -> Unit,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
    onTrackingClicked: () -> Unit,
    onEditIntervalClicked: (() -> Unit)?,
    onEditCategory: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PillButton(
            text = stringResource(if (favorite) MR.strings.in_library else MR.strings.add_to_library),
            icon = if (favorite) MaterialSymbols.RoundedFilled.Favorite else MaterialSymbols.Rounded.Favorite,
            containerColor = if (favorite) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            contentColor = if (favorite) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
            onClick = onAddToLibraryClicked,
            onLongClick = onEditCategory,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        )

        val nextUpdateDays = remember(nextUpdate) {
            nextUpdate?.let {
                Clock.System.now().daysUntil(it, TimeZone.currentSystemDefault()).coerceAtLeast(0)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryAction(
                text = when (nextUpdateDays) {
                    null -> stringResource(MR.strings.not_applicable)
                    0 -> stringResource(MR.strings.manga_interval_expected_update_soon)
                    else -> pluralStringResource(MR.plurals.day, count = nextUpdateDays, nextUpdateDays)
                },
                icon = MaterialSymbols.Rounded.HourglassEmpty,
                highlighted = isUserIntervalMode,
                onClick = { onEditIntervalClicked?.invoke() },
            )
            SecondaryAction(
                text = if (trackingCount == 0) {
                    stringResource(MR.strings.manga_tracking_tab)
                } else {
                    pluralStringResource(MR.plurals.num_trackers, count = trackingCount, trackingCount)
                },
                icon = if (trackingCount == 0) MaterialSymbols.Rounded.Sync else MaterialSymbols.Rounded.Check,
                highlighted = trackingCount > 0,
                onClick = onTrackingClicked,
            )
            if (onWebViewClicked != null) {
                SecondaryAction(
                    text = stringResource(MR.strings.action_web_view),
                    icon = MaterialSymbols.Rounded.Public,
                    highlighted = false,
                    onClick = onWebViewClicked,
                    onLongClick = onWebViewLongClicked,
                )
            }
        }
    }
}

/** Tarjeta con el progreso de lectura. */
@Composable
fun MangaProgressCard(
    readCount: Int,
    totalCount: Int,
    modifier: Modifier = Modifier,
) {
    if (totalCount <= 0) return
    val progress = readCount.toFloat() / totalCount
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.Book,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(MR.strings.manga_read_progress, readCount, totalCount),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                )
                Text(
                    text = "${(progress * 100).roundToInt()}%",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { progress },
                strokeCap = StrokeCap.Round,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
            )
        }
    }
}

/** Envuelve la descripción en una tarjeta redondeada. */
@Composable
fun MangaDescriptionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp), content = content)
    }
}

@Composable
private fun InfoChip(
    text: String,
    icon: ImageVector? = null,
    iconTint: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .then(if (onClick != null) Modifier.clickableNoIndication(onClick = onClick) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(16.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PillButton(
    text: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = icon, contentDescription = null)
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.SecondaryAction(
    text: String,
    icon: ImageVector,
    highlighted: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (highlighted) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun statusText(status: Long): String = when (status) {
    SManga.ONGOING.toLong() -> stringResource(MR.strings.ongoing)
    SManga.COMPLETED.toLong() -> stringResource(MR.strings.completed)
    SManga.LICENSED.toLong() -> stringResource(MR.strings.licensed)
    SManga.PUBLISHING_FINISHED.toLong() -> stringResource(MR.strings.publishing_finished)
    SManga.CANCELLED.toLong() -> stringResource(MR.strings.cancelled)
    SManga.ON_HIATUS.toLong() -> stringResource(MR.strings.on_hiatus)
    else -> stringResource(MR.strings.unknown)
}

private fun Source.searchQuery(): String =
    """src:"$name" lang:$lang""".takeIf { name.isNotBlank() } ?: "srcid:$id"
