package eu.kanade.presentation.updates

import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.util.fastAll
import mihon.icons.materialsymbols.rounded.KeyboardArrowDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadIndicator
import eu.kanade.presentation.manga.components.DotSeparatorText
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.updates.UpdatesItem
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.roundedfilled.Bookmark
import mihon.icons.materialsymbols.roundedfilled.Circle
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.selectedBackground

internal fun LazyListScope.updatesLastUpdatedItem(
    lastUpdated: Long,
) {
    item(key = "updates-lastUpdated") {
        UpdatesLastUpdatedHeader(
            lastUpdated = lastUpdated,
            modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
        )
    }
}

@Composable
internal fun UpdatesLastUpdatedHeader(
    lastUpdated: Long,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
    ) {
        Text(
            text = stringResource(MR.strings.updates_last_update_info, relativeTimeSpanString(lastUpdated)),
            fontStyle = FontStyle.Italic,
        )
    }
}

internal fun LazyListScope.updatesUiItems(
    uiModels: List<UpdatesUiModel>,
    expandedGroups: List<String>,
    onToggleGroup: (String) -> Unit,
    selectionMode: Boolean,
    onUpdateSelected: (UpdatesItem, Boolean, Boolean) -> Unit,
    onClickCover: (UpdatesItem) -> Unit,
    onClickUpdate: (UpdatesItem) -> Unit,
    onDownloadChapter: (List<UpdatesItem>, ChapterDownloadAction) -> Unit,
) {
    // Los grupos abiertos muestran sus capítulos justo debajo, con sangría
    val rows = uiModels.flatMap { model ->
        if (model is UpdatesUiModel.Group && model.key in expandedGroups) {
            listOf(model) + model.items.map { UpdatesRow.Child(it) }
        } else {
            listOf(model)
        }
    }
    items(
        items = rows,
        contentType = {
            when (it) {
                is UpdatesUiModel.Header -> "header"
                is UpdatesUiModel.Item, is UpdatesRow.Child -> "item"
                is UpdatesUiModel.Group -> "group"
                else -> null
            }
        },
        key = {
            when (it) {
                is UpdatesUiModel.Header -> "updatesHeader-${it.hashCode()}"
                is UpdatesUiModel.Item -> "updates-${it.item.update.mangaId}-${it.item.update.chapterId}"
                is UpdatesRow.Child -> "updates-${it.item.update.mangaId}-${it.item.update.chapterId}"
                is UpdatesUiModel.Group -> it.key
                else -> it.hashCode()
            }
        },
    ) { row ->
        when (row) {
            is UpdatesUiModel.Header -> {
                ListGroupHeader(
                    modifier = Modifier.animateItem(),
                    text = relativeDateText(row.date),
                )
            }
            is UpdatesUiModel.Group -> {
                val items = row.items
                UpdatesGroupItem(
                    modifier = Modifier.animateItem(),
                    items = items,
                    expanded = row.key in expandedGroups,
                    onClick = {
                        if (selectionMode) {
                            val select = !items.fastAll { it.selected }
                            items.forEach { onUpdateSelected(it, select, false) }
                        } else {
                            onToggleGroup(row.key)
                        }
                    },
                    onLongClick = {
                        val select = !items.fastAll { it.selected }
                        items.forEach { onUpdateSelected(it, select, false) }
                    },
                    onClickCover = { onClickCover(items.first()) }.takeIf { !selectionMode },
                    onDownloadChapter = { action: ChapterDownloadAction ->
                        when (action) {
                            ChapterDownloadAction.START -> onDownloadChapter(
                                items.filter { it.downloadStateProvider() != Download.State.DOWNLOADED },
                                action,
                            )
                            ChapterDownloadAction.DELETE -> onDownloadChapter(
                                items.filter { it.downloadStateProvider() == Download.State.DOWNLOADED },
                                action,
                            )
                            // Cancelar y "descargar ya" van capítulo a capítulo
                            ChapterDownloadAction.CANCEL, ChapterDownloadAction.START_NOW -> items
                                .filter {
                                    it.downloadStateProvider() == Download.State.QUEUE ||
                                        it.downloadStateProvider() == Download.State.DOWNLOADING
                                }
                                .forEach { onDownloadChapter(listOf(it), action) }
                        }
                    }.takeIf { !selectionMode },
                )
            }
            is UpdatesUiModel.Item, is UpdatesRow.Child -> {
                val nested = row is UpdatesRow.Child
                val updatesItem = if (row is UpdatesRow.Child) row.item else (row as UpdatesUiModel.Item).item
                UpdatesUiItem(
                    modifier = Modifier.animateItem(),
                    nested = nested,
                    update = updatesItem.update,
                    selected = updatesItem.selected,
                    readProgress = updatesItem.update.lastPageRead
                        .takeIf { !updatesItem.update.read && it > 0L }
                        ?.let {
                            stringResource(
                                MR.strings.chapter_progress,
                                it + 1,
                            )
                        },
                    onLongClick = {
                        onUpdateSelected(updatesItem, !updatesItem.selected, true)
                    },
                    onClick = {
                        when {
                            selectionMode -> onUpdateSelected(updatesItem, !updatesItem.selected, false)
                            else -> onClickUpdate(updatesItem)
                        }
                    },
                    onClickCover = { onClickCover(updatesItem) }.takeIf { !selectionMode },
                    onDownloadChapter = { action: ChapterDownloadAction ->
                        onDownloadChapter(listOf(updatesItem), action)
                    }.takeIf { !selectionMode },
                    downloadStateProvider = updatesItem.downloadStateProvider,
                    downloadProgressProvider = updatesItem.downloadProgressProvider,
                )
            }
        }
    }
}

private sealed interface UpdatesRow {
    /** Capítulo de un grupo desplegado. */
    data class Child(val item: UpdatesItem) : UpdatesRow
}

@Composable
private fun UpdatesGroupItem(
    items: List<UpdatesItem>,
    expanded: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onClickCover: (() -> Unit)?,
    onDownloadChapter: ((ChapterDownloadAction) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val update = items.first().update
    val unreadCount = items.count { !it.update.read }
    val textAlpha = if (unreadCount == 0) DISABLED_ALPHA else 1f
    val arrowRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "groupArrow")

    Row(
        modifier = modifier
            .selectedBackground(items.fastAll { it.selected })
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    onLongClick()
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
            )
            .height(56.dp)
            .padding(horizontal = MaterialTheme.padding.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover.Square(
            modifier = Modifier
                .padding(vertical = 6.dp)
                .fillMaxHeight(),
            data = update.coverData,
            onClick = onClickCover,
        )

        Column(
            modifier = Modifier
                .padding(horizontal = MaterialTheme.padding.medium)
                .weight(1f),
        ) {
            Text(
                text = update.mangaTitle,
                maxLines = 1,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current.copy(alpha = textAlpha),
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (unreadCount > 0) {
                    Icon(
                        imageVector = MaterialSymbols.RoundedFilled.Circle,
                        contentDescription = stringResource(MR.strings.unread),
                        modifier = Modifier
                            .height(8.dp)
                            .padding(end = 4.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = stringResource(MR.strings.updates_group_chapters, items.size),
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = textAlpha),
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = MaterialSymbols.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .size(18.dp)
                        .rotate(arrowRotation),
                    tint = LocalContentColor.current.copy(alpha = textAlpha),
                )
            }
        }

        ChapterDownloadIndicator(
            enabled = onDownloadChapter != null,
            modifier = Modifier.padding(start = 4.dp),
            downloadStateProvider = { groupDownloadState(items) },
            downloadProgressProvider = {
                items.filter { it.downloadStateProvider() == Download.State.DOWNLOADING }
                    .map { it.downloadProgressProvider() }
                    .takeIf { it.isNotEmpty() }
                    ?.average()
                    ?.toInt()
                    ?: 0
            },
            onClick = { onDownloadChapter?.invoke(it) },
        )
    }
}

private fun groupDownloadState(items: List<UpdatesItem>): Download.State {
    val states = items.map { it.downloadStateProvider() }
    return when {
        states.all { it == Download.State.DOWNLOADED } -> Download.State.DOWNLOADED
        Download.State.DOWNLOADING in states -> Download.State.DOWNLOADING
        Download.State.QUEUE in states -> Download.State.QUEUE
        Download.State.ERROR in states -> Download.State.ERROR
        else -> Download.State.NOT_DOWNLOADED
    }
}

@Composable
private fun UpdatesUiItem(
    update: UpdatesWithRelations,
    nested: Boolean,
    selected: Boolean,
    readProgress: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onClickCover: (() -> Unit)?,
    onDownloadChapter: ((ChapterDownloadAction) -> Unit)?,
    // Download Indicator
    downloadStateProvider: () -> Download.State,
    downloadProgressProvider: () -> Int,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val textAlpha = if (update.read) DISABLED_ALPHA else 1f

    Row(
        modifier = modifier
            .selectedBackground(selected)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    onLongClick()
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
            )
            .height(if (nested) 44.dp else 56.dp)
            .padding(horizontal = MaterialTheme.padding.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (nested) {
            // Alineado bajo el título del grupo, sin repetir la portada
            Spacer(modifier = Modifier.width(44.dp))
        } else {
            MangaCover.Square(
                modifier = Modifier
                    .padding(vertical = 6.dp)
                    .fillMaxHeight(),
                data = update.coverData,
                onClick = onClickCover,
            )
        }

        Column(
            modifier = Modifier
                .padding(horizontal = MaterialTheme.padding.medium)
                .weight(1f),
        ) {
            if (!nested) {
                Text(
                    text = update.mangaTitle,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalContentColor.current.copy(alpha = textAlpha),
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                var textHeight by remember { mutableIntStateOf(0) }
                if (!update.read) {
                    Icon(
                        imageVector = MaterialSymbols.RoundedFilled.Circle,
                        contentDescription = stringResource(MR.strings.unread),
                        modifier = Modifier
                            .height(8.dp)
                            .padding(end = 4.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                if (update.bookmark) {
                    Icon(
                        imageVector = MaterialSymbols.RoundedFilled.Bookmark,
                        contentDescription = stringResource(MR.strings.action_filter_bookmarked),
                        modifier = Modifier
                            .sizeIn(maxHeight = with(LocalDensity.current) { textHeight.toDp() - 2.dp }),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                }
                Text(
                    text = update.chapterName,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = textAlpha),
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { textHeight = it.size.height },
                    modifier = Modifier
                        .weight(weight = 1f, fill = false),
                )
                if (readProgress != null) {
                    DotSeparatorText()
                    Text(
                        text = readProgress,
                        maxLines = 1,
                        color = LocalContentColor.current.copy(alpha = DISABLED_ALPHA),
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        ChapterDownloadIndicator(
            enabled = onDownloadChapter != null,
            modifier = Modifier.padding(start = 4.dp),
            downloadStateProvider = downloadStateProvider,
            downloadProgressProvider = downloadProgressProvider,
            onClick = { onDownloadChapter?.invoke(it) },
        )
    }
}
