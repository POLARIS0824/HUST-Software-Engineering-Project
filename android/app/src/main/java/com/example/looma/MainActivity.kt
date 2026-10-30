package com.example.looma

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.looma.feature.vault.VaultSetupScreen
import com.example.looma.ui.theme.LoomaTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LoomaTheme {
                Scaffold(Modifier.fillMaxSize()) { innerPadding ->
                    VaultSetupDemo(Modifier.padding(innerPadding))
                }
            }
        }
    }
}

/**
 * 交互演示：用模拟的目录名走通 Empty → Pending → Current
 * 这里没有做真实的文件权限申请和数据保存
 * 在接上 VaultRepository 后，由 ViewModel 替换这些演示状态即可
 */
@Composable
private fun VaultSetupDemo(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var selectionCount by rememberSaveable { mutableIntStateOf(0) }
    var selectedFolderName by rememberSaveable { mutableStateOf<String?>(null) }
    var currentFolderName by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(busy) {
        if (busy) {
            // 故意用较长延时演示 LoadingIndicator 和状态切换动画
            delay(1_200L)
            selectedFolderName?.let { currentFolderName = it }
            selectedFolderName = null
            busy = false
        }
    }

    VaultSetupScreen(
        selectedFolderName = selectedFolderName,
        currentFolderName = currentFolderName,
        isBusy = busy,
        onChooseFolderClick = {
            // TODO: 后续替换为真正的系统文件夹选择或你决定的路径选择流程
            selectedFolderName = if (selectionCount % 2 == 0) "Looma Notes" else "Course Notes"
            selectionCount++
        },
        onConfirmClick = {
            if (selectedFolderName != null) {
                busy = true
            } else {
                Toast.makeText(context, "当前为 UI 演示，真实笔记库尚未接入", Toast.LENGTH_SHORT).show()
            }
        },
        modifier = modifier,
    )
}
