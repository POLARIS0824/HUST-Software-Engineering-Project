package com.example.looma.navigation

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.example.looma.data.note.NoteRepository
import com.example.looma.data.vault.VaultRepository
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.VaultId
import com.example.looma.feature.editor.EditorRoute
import com.example.looma.feature.editor.EditorViewModel
import com.example.looma.feature.home.HomeRoute
import com.example.looma.feature.home.HomeViewModel
import com.example.looma.feature.vault.VaultSetupViewModel
import com.example.looma.feature.vault.VaultState

/**
 * App-level navigation only. Features never receive or modify the NavBackStack.
 * Vault setup is a prerequisite; a successfully selected vault replaces the root.
 */
@Composable
fun LoomaNavGraph(
    vaultRepository: VaultRepository,
    noteRepository: NoteRepository,
    vaultSetupViewModel: VaultSetupViewModel,
    vaultSetupContent: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val setupState by vaultSetupViewModel.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vaultSetupViewModel.refreshVaultStatus() }
    val status = setupState.vaultState

    // Avoid creating a saved back stack before DataStore's first value arrives.
    if (status is VaultState.Checking) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val context = LocalContext.current
    val start: NavKey = if (status is VaultState.Ready) {
        LoomaRoute.Home
    } else {
        LoomaRoute.VaultSetup
    }
    val backStack = rememberNavBackStack(start)

    // A successful selection is a root change, not a push: Back must not reopen setup.
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

    NavDisplay(
        modifier = modifier.fillMaxSize(),
        backStack = backStack,
        onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<LoomaRoute.VaultSetup> {
                vaultSetupContent(Modifier.fillMaxSize().safeDrawingPadding())
            }
            entry<LoomaRoute.Home> {
                val homeViewModel: HomeViewModel = viewModel(
                    factory = HomeViewModel.factory(vaultRepository, noteRepository)
                )
                HomeRoute(
                    viewModel = homeViewModel,
                    onOpenNote = { noteId ->
                        backStack.add(LoomaRoute.Editor.from(noteId))
                    },
                    onMenuClick = {
                        if (backStack.lastOrNull() != LoomaRoute.VaultSetup) {
                            backStack.add(LoomaRoute.VaultSetup)
                        }
                    },
                    onAssistantClick = {
                        Toast.makeText(context, "辅助功能尚未开放", Toast.LENGTH_SHORT).show()
                    },
                    onAvatarClick = {
                        Toast.makeText(context, "当前笔记库设置请从左上角菜单进入", Toast.LENGTH_SHORT).show()
                    },
                )
            }
            entry<LoomaRoute.Editor> { route ->
                val noteId = NoteId(
                    vaultId = VaultId(route.vaultId),
                    path = NotePath(route.relativePath),
                )
                val editorViewModel: EditorViewModel = viewModel(
                    factory = EditorViewModel.factory(noteRepository, noteId)
                )
                EditorRoute(
                    viewModel = editorViewModel,
                    onNavigateUp = { if (backStack.size > 1) backStack.removeLastOrNull() },
                )
            }
        },
    )
}
