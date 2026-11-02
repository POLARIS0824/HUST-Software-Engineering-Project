package com.example.looma

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.looma.data.folder.FolderRepository
import com.example.looma.data.note.NoteRepository
import com.example.looma.data.settings.AppSettings
import com.example.looma.data.settings.SettingsRepository
import com.example.looma.data.vault.VaultRepository
import com.example.looma.feature.vault.VaultSetupViewModel
import com.example.looma.navigation.LoomaNavGraph
import com.example.looma.ui.theme.LoomaTheme

class MainActivity : AppCompatActivity() {
    private val vaultRepository by lazy { VaultRepository(applicationContext) }
    private val noteRepository by lazy { NoteRepository(vaultRepository) }
    private val folderRepository by lazy { FolderRepository(vaultRepository) }
    private val settingsRepository by lazy { SettingsRepository(applicationContext) }

    // 笔记库状态与设置页共用一个 ViewModel：Activity 级作用域，切换目的地不会丢状态
    private val vaultSetupViewModel: VaultSetupViewModel by viewModels {
        VaultSetupViewModel.factory(vaultRepository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // 主题偏好是 Activity 级的：改完立刻换色，不需要重启
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
                    settingsRepository = settingsRepository,
                    vaultSetupViewModel = vaultSetupViewModel,
                )
            }
        }
    }
}
