package eu.kanade.presentation.library.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.category.visualName
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.KeyboardArrowLeft
import mihon.icons.materialsymbols.rounded.KeyboardArrowRight
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.glass.GlassBackdrop
import tachiyomi.presentation.core.components.glass.GlassSurface
import tachiyomi.presentation.core.i18n.stringResource

val LibraryCategoryPillHeight = 48.dp

/**
 * Selector de categoría flotante (liquid glass): anterior | nombre | siguiente.
 * Al tocar el nombre se abre la lista de categorías.
 */
@Composable
internal fun LibraryCategoryPill(
    categories: List<Category>,
    pagerState: PagerState,
    backdrop: GlassBackdrop,
    getItemCountForCategory: (Category) -> Int?,
    onSelectPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentIndex = pagerState.currentPage.coerceIn(0, categories.lastIndex)
    val current = categories[currentIndex]
    var showMenu by remember { mutableStateOf(false) }

    GlassSurface(
        backdrop = backdrop,
        shape = CircleShape,
        modifier = modifier
            .height(LibraryCategoryPillHeight)
            .widthIn(min = 200.dp, max = 320.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onSelectPage(currentIndex - 1) },
                enabled = currentIndex > 0,
            ) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.KeyboardArrowLeft,
                    contentDescription = stringResource(MR.strings.action_previous_category),
                )
            }
            VerticalDivider(Modifier.padding(vertical = 12.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { showMenu = true },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = categoryLabel(current, getItemCountForCategory),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    categories.forEachIndexed { index, category ->
                        DropdownMenuItem(
                            text = { Text(categoryLabel(category, getItemCountForCategory)) },
                            onClick = {
                                showMenu = false
                                onSelectPage(index)
                            },
                        )
                    }
                }
            }
            VerticalDivider(Modifier.padding(vertical = 12.dp))
            IconButton(
                onClick = { onSelectPage(currentIndex + 1) },
                enabled = currentIndex < categories.lastIndex,
            ) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.KeyboardArrowRight,
                    contentDescription = stringResource(MR.strings.action_next_category),
                )
            }
        }
    }
}

@Composable
private fun categoryLabel(category: Category, getItemCountForCategory: (Category) -> Int?): String {
    val count = getItemCountForCategory(category)
    return if (count != null) "${category.visualName} ($count)" else category.visualName
}
