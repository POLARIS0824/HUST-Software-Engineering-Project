@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package edu.hust.justmd.comparison

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var editor: EditorPort
    private var darkBars = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        editor = createFlavorEditorPort(this)
        setContent {
            var darkOverride by remember { mutableStateOf<Boolean?>(null) }
            val dark = darkOverride ?: isSystemInDarkTheme()
            val colors = comparisonColors(dark)
            SideEffect {
                darkBars = dark
                applyBars()
            }
            MaterialExpressiveTheme(colorScheme = colors, typography = comparisonTypography(), motionScheme = MotionScheme.expressive()) {
                ComparisonApp(editor, intent.getStringExtra("note"), intent.getBooleanExtra("probe", false),
                    dark, { darkOverride = !dark })
            }
        }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyBars()
    }
    private fun applyBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkBars
            isAppearanceLightNavigationBars = !darkBars
        }
    }
    override fun onDestroy() { editor.close(); super.onDestroy() }
}

@Composable
fun Symbol(id: Int, label: String? = null) = Icon(painterResource(id), label, Modifier.size(24.dp))

@Composable
private fun ComparisonApp(port: EditorPort, initialNote: String?, runProbe: Boolean, dark: Boolean, toggleDark: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val mutex = remember { Mutex() }
    val ready by port.ready.collectAsState()
    val snapshot by port.active.collectAsState()
    var selected by remember { mutableStateOf<ResearchNote?>(null) }
    var reading by remember { mutableStateOf(false) }
    var readSnapshot by remember { mutableStateOf<DraftSnapshot?>(null) }
    var pending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var onlyPinned by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf(fixtureNotes) }

    fun perform(action: suspend () -> Unit) {
        if (pending) return
        scope.launch {
            pending = true
            try { mutex.withLock { action() }; error = null }
            catch (failure: Exception) { error = failure.message ?: failure.javaClass.simpleName }
            finally { pending = false }
        }
    }
    fun open(note: ResearchNote) = perform {
        focus.clearFocus(); keyboard?.hide()
        if (selected != null) port.snapshotForRead()
        port.selectNote(note.document())
        selected = note; reading = false
    }
    fun leave() = perform {
        focus.clearFocus(); keyboard?.hide()
        port.snapshotForRead(); selected = null; reading = false
    }
    fun setReading(value: Boolean) = perform {
        focus.clearFocus(); keyboard?.hide()
        if (value) readSnapshot = port.snapshotForRead()
        reading = value
    }
    BackHandler(selected != null && !pending) { if (reading) setReading(false) else leave() }

    LaunchedEffect(ready) {
        if (ready) {
            if (runProbe) perform {
                val result = runEditorProbes(port)
                withContext(Dispatchers.IO) { File(context.filesDir, "editor-probes.json").writeText(result) }
                port.selectNote(fixtureNotes.first().document())
                selected = fixtureNotes.first()
            }
            else initialNote?.let { id -> notes.find { it.id == id }?.let { open(it) } }
        }
    }
    LaunchedEffect(snapshot) {
        snapshot?.let { value -> withContext(Dispatchers.IO) {
            File(context.filesDir, "active-draft.json").writeText(value.json().toString(2))
        } }
    }
    LaunchedEffect(c, dark) {
        val palette = JSONObject().put("engine", BuildConfig.EDITOR_KIND).put("dark", dark)
            .put("dynamic", Build.VERSION.SDK_INT >= 31).put("primary", colorHex(c.primary.toArgb()))
            .put("surface", colorHex(c.surface.toArgb())).put("onSurface", colorHex(c.onSurface.toArgb()))
            .put("secondaryContainer", colorHex(c.secondaryContainer.toArgb()))
        if (Build.VERSION.SDK_INT >= 31) palette.put("systemAccent1_500", colorHex(context.getColor(android.R.color.system_accent1_500)))
        withContext(Dispatchers.IO) { File(context.filesDir, "palette.json").writeText(palette.toString(2)) }
    }

    Scaffold(containerColor = c.surface, modifier = Modifier.imePadding(),
        topBar = {
            Column {
                TopAppBar(title = { Text(selected?.folder ?: "JustMD") },
                    navigationIcon = { if (selected != null) IconButton(onClick = { leave() }, enabled = !pending) { Symbol(R.drawable.sym_arrow_back, "返回笔记列表") } },
                    actions = {
                        if (pending) LoadingIndicator(Modifier.size(24.dp))
                        Box {
                            FilledTonalIconButton(onClick = { menu = true }) { Symbol(R.drawable.sym_tune, "显示与研究工具") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text(if (dark) "浅色模式" else "深色模式") }, onClick = { toggleDark(); menu = false })
                                DropdownMenuItem(text = { Text("运行源码检查") }, onClick = { menu = false; perform {
                                    val previous = selected
                                    val result = runEditorProbes(port)
                                    withContext(Dispatchers.IO) { File(context.filesDir, "editor-probes.json").writeText(result) }
                                    if (previous != null) port.selectNote(previous.document())
                                } })
                            }
                        }
                    })
                if (selected != null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                        ToggleButton(checked = !reading, onCheckedChange = { if (it) setReading(false) }, enabled = !pending,
                            shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(), modifier = Modifier.weight(1f)) { Text("编辑") }
                        ToggleButton(checked = reading, onCheckedChange = { if (it) setReading(true) }, enabled = !pending && ready,
                            shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(), modifier = Modifier.weight(1f)) { Text("阅读") }
                    }
                    Text(selected!!.title + " · Markdown", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                }
                error?.let { Text(it, Modifier.fillMaxWidth().background(c.errorContainer).padding(12.dp), color = c.onErrorContainer, style = MaterialTheme.typography.bodySmall) }
            }
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (selected != null && !reading) HorizontalFloatingToolbar(expanded = true, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val commands = listOf(Triple(EditorCommand.Undo, R.drawable.sym_undo, "撤销"), Triple(EditorCommand.Redo, R.drawable.sym_redo, "重做"),
                        Triple(EditorCommand.Bold, R.drawable.sym_format_bold, "加粗"), Triple(EditorCommand.Heading, R.drawable.sym_title, "插入标题"), Triple(EditorCommand.Link, R.drawable.sym_link, "插入链接"))
                    commands.forEach { (command, icon, label) ->
                        val allowed = !pending && ready && when (command) { EditorCommand.Undo -> snapshot?.canUndo == true; EditorCommand.Redo -> snapshot?.canRedo == true; else -> true }
                        IconButton(onClick = { perform { port.command(command) } }, enabled = allowed) { Symbol(icon, label) }
                    }
                }
                Text("${BuildConfig.EDITOR_KIND} · 本次会话", Modifier.padding(bottom = 10.dp, top = 4.dp), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
            }
        },
        floatingActionButton = {
            if (selected == null) ExtendedFloatingActionButton(onClick = {
                val note = ResearchNote("draft-${System.nanoTime()}", "未命名笔记", "随手记", "", "# 未命名笔记\n\n", "现在")
                notes = listOf(note) + notes; open(note)
            }, icon = { Symbol(R.drawable.sym_edit_square) }, text = { Text("记一笔") })
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val visibleEditor = selected != null && !reading
            FlavorEditor(port, c, Modifier.fillMaxSize()
                .then(if (visibleEditor) Modifier else Modifier.clearAndSetSemantics { hideFromAccessibility() }), visibleEditor)
            if (!visibleEditor) Surface(Modifier.fillMaxSize(), color = c.surface) {
                if (selected != null && reading) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp)) {
                        val type = MaterialTheme.typography
                        Markdown(content = readSnapshot?.text.orEmpty(), typography = markdownTypography(
                            h1 = type.headlineLarge, h2 = type.headlineSmall, h3 = type.titleLarge,
                            h4 = type.titleMedium, h5 = type.titleSmall, h6 = type.labelLarge))
                    }
                } else {
                    val filtered = notes.filter { (!onlyPinned || it.pinned) && (query.isBlank() || (it.title + it.markdown).contains(query, true)) }
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item {
                            Text("笔记", Modifier.padding(top = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.displayMedium)
                            Text("${notes.size} 篇笔记 · 随时回到你的思考", Modifier.padding(bottom = 20.dp), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                        }
                        item { OutlinedTextField(query, { query = it }, label = { Text("搜索笔记内容") }, leadingIcon = { Symbol(R.drawable.sym_search) }, singleLine = true, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) }
                        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(!onlyPinned, { onlyPinned = false }, { Text("全部笔记") }); FilterChip(onlyPinned, { onlyPinned = true }, { Text("收藏") })
                        } }
                        if (filtered.isEmpty()) item { Text("没有找到相关笔记", Modifier.padding(vertical = 40.dp), style = MaterialTheme.typography.titleMedium) }
                        filtered.groupBy { if (it.pinned) "置顶" else "最近编辑" }.forEach { (section, group) ->
                            item { Text(section, Modifier.padding(top = 10.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant) }
                            items(group, key = { it.id }) { note ->
                                Surface(onClick = { open(note) }, color = c.surfaceContainerLowest, shape = RoundedCornerShape(16.dp)) {
                                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row { Text(note.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); Text(note.updated, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant) }
                                        Text(note.excerpt, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(note.folder, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun colorHex(argb: Int) = "#%08X".format(argb)
fun DraftSnapshot.json() = JSONObject().put("noteId", noteId).put("text", text).put("anchor", selection.anchor)
    .put("head", selection.head).put("revision", revision).put("canUndo", canUndo).put("canRedo", canRedo)
