package com.example.looma

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.looma.data.settings.AppSettings
import com.example.looma.feature.vault.VaultSetupViewModel
import com.example.looma.navigation.LoomaNavGraph
import com.example.looma.ui.theme.LoomaTheme

class MainActivity : AppCompatActivity() {
    private val container get() = application as LoomaApp

    private val vaultSetupViewModel: VaultSetupViewModel by viewModels {
        VaultSetupViewModel.factory(container.vaultRepository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by container.settingsRepository.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())
            LoomaTheme(
                dynamicColor = settings.dynamicColor,
                preset = settings.themePreset,
            ) {
                LoomaNavGraph(
                    vaultRepository = container.vaultRepository,
                    noteRepository = container.noteRepository,
                    folderRepository = container.folderRepository,
                    searchHistoryRepository = container.searchHistoryRepository,
                    settingsRepository = container.settingsRepository,
                    imageUploader = container.imageUploader,
                    vaultSetupViewModel = vaultSetupViewModel,
                )
            }
        }
    }
}
