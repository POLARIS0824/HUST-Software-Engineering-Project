package com.example.looma.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.example.looma.R
import com.example.looma.data.folder.FolderRepository
import com.example.looma.data.note.NoteRepository
import com.example.looma.data.search.SearchHistoryRepository
import com.example.looma.data.settings.SettingsRepository
import com.example.looma.data.vault.VaultRepository
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.VaultId
import com.example.looma.feature.home.HomeRoute
import com.example.looma.feature.home.HomeViewModel
import com.example.looma.feature.note.NoteRoute
import com.example.looma.feature.note.NoteViewModel
import com.example.looma.feature.settings.SettingsRoute
import com.example.looma.feature.settings.SettingsViewModel
import com.example.looma.feature.trash.TrashRoute
import com.example.looma.feature.trash.TrashViewModel
import com.example.looma.feature.vault.VaultSetupRoute
import com.example.looma.feature.vault.VaultSetupViewModel
import com.example.looma.feature.vault.VaultState
import com.example.looma.ui.components.LoomaLoadingIndicator
import kotlinx.coroutines.launch

@Composable
fun LoomaNavGraph(
    vaultRepository: VaultRepository,
    noteRepository: NoteRepository,
    folderRepository: FolderRepository,
    searchHistoryRepository: SearchHistoryRepository,
    settingsRepository: SettingsRepository,
    vaultSetupViewModel: VaultSetupViewModel,
    modifier: Modifier = Modifier,
) {
    val setupState by vaultSetupViewModel.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vaultSetupViewModel.refreshVaultStatus() }
    val status = setupState.vaultState

    if (status is VaultState.Checking) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoomaLoadingIndicator()
        }
        return
    }

    val start: NavKey = if (status is VaultState.Ready) LoomaRoute.Home else LoomaRoute.VaultSetup
    val backStack = rememberNavBackStack(start)

    LaunchedEffect(setupState.selectionSaved, status) {
        if (setupState.selectionSaved && status is VaultState.Ready) {
            backStack.clear()
            backStack.add(LoomaRoute.Home)
            vaultSetupViewModel.onNavigationHandled()
        } else if (status !is VaultState.Ready && backStack.lastOrNull() != LoomaRoute.VaultSetup) {
            backStack.clear()
            backStack.add(LoomaRoute.VaultSetup)
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val current = backStack.lastOrNull()
    val destination = current.toDestination()
    val vault by vaultRepository.currentVaultFlow.collectAsStateWithLifecycle(initialValue = null)

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    LoomaDrawer(
        drawerState = drawerState,
        current = destination,
        vaultName = vault?.name.orEmpty(),
        gesturesEnabled = destination != null,
        onSelect = { target ->
            scope.launch { drawerState.close() }
            when (target) {
                LoomaDestination.Notes -> {
                    backStack.clear()
                    backStack.add(LoomaRoute.Home)
                }
                LoomaDestination.Trash ->
                    if (backStack.lastOrNull() != LoomaRoute.Trash) backStack.add(LoomaRoute.Trash)
                LoomaDestination.Settings ->
                    if (backStack.lastOrNull() != LoomaRoute.Settings) backStack.add(LoomaRoute.Settings)
            }
        },
        modifier = modifier.fillMaxSize(),
    ) {
        NavDisplay(
            modifier = Modifier.fillMaxSize(),

            backStack = backStack,
            onBack = {
                if (backStack.size > 1) {
                    backStack.removeLastOrNull()
                }
            },

            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),

            entryProvider = entryProvider {

                entry<LoomaRoute.VaultSetup> {
                    VaultSetupRoute(
                        viewModel = vaultSetupViewModel,
                        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                    )
                }

                entry<LoomaRoute.Home> {
                    val homeViewModel: HomeViewModel = viewModel(
                        factory = HomeViewModel.factory(
                            vaultRepository = vaultRepository,
                            noteRepository = noteRepository,
                            folderRepository = folderRepository,
                            searchHistoryRepository = searchHistoryRepository,
                        ),
                    )
                    HomeRoute(
                        viewModel = homeViewModel,
                        onOpenNote = { noteId -> backStack.add(LoomaRoute.Note.from(noteId)) },
                        onCreatedNote = { noteId ->
                            backStack.add(LoomaRoute.Note.from(noteId, isNew = true))
                        },
                        onMenuClick = { scope.launch { drawerState.open() } },
                        onSettingsClick = {
                            if (backStack.lastOrNull() != LoomaRoute.Settings) {
                                backStack.add(LoomaRoute.Settings)
                            }
                        },
                    )
                }

                entry<LoomaRoute.Trash> {
                    val trashViewModel: TrashViewModel = viewModel(
                        factory = TrashViewModel.factory(noteRepository),
                    )
                    TrashRoute(
                        viewModel = trashViewModel,
                        onNavigateUp = { if (backStack.size > 1) backStack.removeLastOrNull() },
                    )
                }

                entry<LoomaRoute.Settings> {
                    val settingsViewModel: SettingsViewModel = viewModel(
                        factory = SettingsViewModel.factory(settingsRepository),
                    )
                    SettingsRoute(
                        vaultViewModel = vaultSetupViewModel,
                        settingsViewModel = settingsViewModel,
                        onChangeVaultClick = {
                            if (backStack.lastOrNull() != LoomaRoute.VaultSetup) {
                                backStack.add(LoomaRoute.VaultSetup)
                            }
                        },
                        onNavigateUp = { if (backStack.size > 1) backStack.removeLastOrNull() },
                    )
                }

                entry<LoomaRoute.Note> { route ->
                    val noteId = NoteId(
                        vaultId = VaultId(route.vaultId),
                        path = NotePath(route.relativePath),
                    )
                    val noteViewModel: NoteViewModel = viewModel(
                        factory = NoteViewModel.factory(
                            repository = noteRepository,
                            settingsRepository = settingsRepository,
                            noteId = noteId,
                            isNewNote = route.isNew,
                        ),
                    )
                    NoteRoute(
                        viewModel = noteViewModel,
                        fallbackTitle = if (route.isNew) {
                            stringResource(R.string.home_untitled_note)
                        } else {
                            route.relativePath.substringAfterLast('/').substringBeforeLast('.')
                        },
                        isNew = route.isNew,
                        onNavigateUp = { if (backStack.size > 1) backStack.removeLastOrNull() },
                        onOpenNote = { noteId -> backStack.add(LoomaRoute.Note.from(noteId)) },
                    )
                }
            },
        )
    }
}

private fun NavKey?.toDestination(): LoomaDestination? = when (this) {
    LoomaRoute.Home -> LoomaDestination.Notes
    LoomaRoute.Trash -> LoomaDestination.Trash
    LoomaRoute.Settings -> LoomaDestination.Settings
    else -> null
}
