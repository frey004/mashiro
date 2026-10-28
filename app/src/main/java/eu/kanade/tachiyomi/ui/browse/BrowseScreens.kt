package eu.kanade.tachiyomi.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.TabbedScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsViewModel
import eu.kanade.tachiyomi.ui.browse.extension.extensionsTab
import eu.kanade.tachiyomi.ui.browse.migration.sources.migrateSourceTab
import tachiyomi.i18n.MR

/** Instalar, actualizar, ocultar y quitar extensiones. Se abre desde "Gestionar" en Explorar. */
object ManageExtensionsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<ExtensionsViewModel>()
        val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()

        TabbedScreen(
            titleRes = MR.strings.label_extensions,
            tabs = listOf(extensionsTab(viewModel)),
            searchQuery = searchQuery,
            onChangeSearchQuery = viewModel::search,
            navigateUp = navigator::pop,
        )
    }
}

/** Migrar mangas entre fuentes. Se abre desde el menú de Explorar. */
object MigrateSourcesScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        TabbedScreen(
            titleRes = MR.strings.label_migration,
            tabs = listOf(migrateSourceTab()),
            navigateUp = navigator::pop,
        )
    }
}
