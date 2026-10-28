package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewLightDark
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Check
import mihon.icons.materialsymbols.rounded.Folder
import tachiyomi.presentation.core.components.Badge

@Composable
internal fun DownloadsBadge(count: Int) {
    if (count > 0) {
        Badge(
            text = "$count",
            color = MaterialTheme.colorScheme.tertiary,
            textColor = MaterialTheme.colorScheme.onTertiary,
        )
    }
}

/** Capítulos nuevos sin leer: círculo rojo con el número. */
@Composable
internal fun UnreadBadge(count: Long) {
    if (count > 0) {
        Badge(
            text = if (count > 99) "99+" else "$count",
            color = MaterialTheme.colorScheme.error,
            textColor = MaterialTheme.colorScheme.onError,
            shape = CircleShape,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/** Porcentaje leído; con todo leído muestra un check. */
@Composable
internal fun ReadProgressBadge(readCount: Long, totalCount: Long) {
    if (totalCount <= 0 || readCount <= 0) return
    val percent = (readCount * 100 / totalCount).toInt()
    if (readCount >= totalCount) {
        Badge(
            imageVector = MaterialSymbols.Rounded.Check,
            color = Color.Black.copy(alpha = 0.55f),
            iconColor = Color.White,
            shape = CircleShape,
        )
    } else {
        Badge(
            text = "${percent.coerceIn(1, 99)}%",
            color = Color.Black.copy(alpha = 0.55f),
            textColor = Color.White,
            shape = RoundedCornerShape(50),
        )
    }
}

@Composable
internal fun LanguageBadge(
    isLocal: Boolean,
    sourceLanguage: String,
) {
    if (isLocal) {
        Badge(
            imageVector = MaterialSymbols.Rounded.Folder,
            color = MaterialTheme.colorScheme.tertiary,
            iconColor = MaterialTheme.colorScheme.onTertiary,
        )
    } else if (sourceLanguage.isNotEmpty()) {
        Badge(
            text = sourceLanguage.uppercase(),
            color = MaterialTheme.colorScheme.tertiary,
            textColor = MaterialTheme.colorScheme.onTertiary,
        )
    }
}

@PreviewLightDark
@Composable
private fun BadgePreview() {
    TachiyomiPreviewTheme {
        Column {
            DownloadsBadge(count = 10)
            UnreadBadge(count = 10)
            LanguageBadge(isLocal = true, sourceLanguage = "EN")
            LanguageBadge(isLocal = false, sourceLanguage = "EN")
        }
    }
}
