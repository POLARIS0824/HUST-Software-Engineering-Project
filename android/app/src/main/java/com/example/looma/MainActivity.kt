package com.example.looma

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.looma.data.folder.FolderRepository
import com.example.looma.data.index.NoteIndexDatabase
import com.example.looma.data.index.RoomNoteIndex
import com.example.looma.data.note.NoteRepository
import com.example.looma.data.search.SearchHistoryRepository
import com.example.looma.data.settings.AppSettings
import com.example.looma.data.settings.SettingsRepository
import com.example.looma.data.vault.VaultRepository
import com.example.looma.feature.vault.VaultSetupViewModel
import com.example.looma.navigation.LoomaNavGraph
import com.example.looma.ui.theme.LoomaTheme

class MainActivity : AppCompatActivity() {
    private val vaultRepository by lazy { VaultRepository(applicationContext) }
    private val indexDatabase by lazy { NoteIndexDatabase.get(applicationContext) }
    private val noteRepository by lazy { NoteRepository(vaultRepository, RoomNoteIndex(indexDatabase)) }
    private val searchHistoryRepository by lazy {
        SearchHistoryRepository(vaultRepository, indexDatabase.searchHistoryDao())
    }
    private val folderRepository by lazy { FolderRepository(vaultRepository) }
    private val settingsRepository by lazy { SettingsRepository(applicationContext) }

    private val vaultSetupViewModel: VaultSetupViewModel by viewModels {
        VaultSetupViewModel.factory(vaultRepository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by settingsRepository.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())
            LoomaTheme(
                dynamicColor = settings.dynamicColor,
                preset = settings.themePreset,
            ) {
                LoomaNavGraph(
                    vaultRepository = vaultRepository,
                    noteRepository = noteRepository,
                    folderRepository = folderRepository,
                    searchHistoryRepository = searchHistoryRepository,
                    settingsRepository = settingsRepository,
                    vaultSetupViewModel = vaultSetupViewModel,
                )
            }
        }
    }
}
