package edu.hust.looma.prototype

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private var darkPrototype = false
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkPrototype
            isAppearanceLightNavigationBars = !darkPrototype
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialStyle = Style.entries.getOrElse(intent.getIntExtra("style", 2)) { Style.Expressive }
        val probe = intent.getBooleanExtra("probe", false)
        val session = ResearchSession(initialStyle, intent.getStringExtra("note"))
        if (probe) lifecycleScope.launch(Dispatchers.IO) {
            val result = runNativeProbes(this@MainActivity)
            File(filesDir, "native-probes.json").writeText(result)
        }
        setContent {
            SideEffect {
                darkPrototype = session.style == Style.Obsidian
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = session.style != Style.Obsidian
                    isAppearanceLightNavigationBars = session.style != Style.Obsidian
                }
            }
            val palette = when (session.style) {
                Style.Obsidian -> darkColorScheme()
                Style.Paper -> paperColors
                Style.Expressive -> expressiveLightColorScheme()
            }
            MaterialExpressiveTheme(colorScheme = palette, typography = researchTypography(), motionScheme = MotionScheme.expressive()) {
                Surface(Modifier.fillMaxSize()) {
                    if (probe) {
                        LocalEditor(fixtureNotes.first(), session.style, true, {}, { json ->
                            lifecycleScope.launch(Dispatchers.IO) { File(filesDir, "editor-probes.json").writeText(json) }
                        })
                    } else ResearchApp(session)
                }
            }
        }
    }
}

@Composable
private fun researchTypography(): Typography {
    fun voice(size: Int, weight: Int = 400, width: Float = 100f) = FontFamily(Font(
        R.font.google_sans_flex, FontWeight(weight), variationSettings = FontVariation.Settings(
            FontVariation.weight(weight), FontVariation.width(width), FontVariation.opticalSizing(size.sp)
        )
    ))
    fun TextStyle.flex(size: Int, weight: Int = 400, width: Float = 100f) = copy(fontFamily = voice(size, weight, width))
    val base = Typography()
    return base.copy(
        displayLarge = base.displayLarge.flex(57, 800, 85f), displayMedium = base.displayMedium.flex(45, 700, 85f),
        displaySmall = base.displaySmall.flex(36, 700, 85f), headlineLarge = base.headlineLarge.flex(32, 700, 85f),
        headlineMedium = base.headlineMedium.flex(28, 600), headlineSmall = base.headlineSmall.flex(24, 600),
        titleLarge = base.titleLarge.flex(22, 600), titleMedium = base.titleMedium.flex(16, 600),
        titleSmall = base.titleSmall.flex(14, 600), bodyLarge = base.bodyLarge.flex(16),
        bodyMedium = base.bodyMedium.flex(14), bodySmall = base.bodySmall.flex(12),
        labelLarge = base.labelLarge.flex(14, 600), labelMedium = base.labelMedium.flex(12, 500),
        labelSmall = base.labelSmall.flex(11, 500), headlineLargeEmphasized = base.headlineLargeEmphasized.flex(32, 800, 85f),
        titleMediumEmphasized = base.titleMediumEmphasized.flex(16, 700)
    )
}

@Composable
fun Symbol(resource: Int, label: String? = null, modifier: Modifier = Modifier) {
    Icon(painterResource(resource), label, modifier.size(24.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResearchApp(session: ResearchSession) {
    var menu by remember { mutableStateOf(false) }
    var backlinkSheet by remember { mutableStateOf(false) }
    val note = session.selected
    val c = MaterialTheme.colorScheme
    BackHandler(note != null) { session.noteId = null }
    Scaffold(
        containerColor = c.surface,
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (note != null) IconButton(onClick = { session.noteId = null }) { Symbol(R.drawable.sym_arrow_back, "返回笔记列表") }
                    else Symbol(if (session.style == Style.Obsidian) R.drawable.sym_folder else R.drawable.sym_description, modifier = Modifier.padding(end = 10.dp))
                    Text(if (note == null) "Looma" else note.folder,
                        style = if (note == null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f))
                    if (note != null) IconButton(onClick = { backlinkSheet = true }) { Symbol(R.drawable.sym_hub, "查看双向链接") }
                    Box {
                        IconButton(onClick = { menu = true }) { Symbol(R.drawable.sym_tune, "切换设计方案") }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            Style.entries.forEach { variant ->
                                DropdownMenuItem(text = { Text(variant.label) }, onClick = { session.style = variant; menu = false },
                                    leadingIcon = { if (session.style == variant) Symbol(R.drawable.sym_check) })
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (note == null) {
                ShortNavigationBar {
                    listOf("笔记" to R.drawable.sym_description, "标签" to R.drawable.sym_sell, "链接" to R.drawable.sym_hub).forEachIndexed { index, item ->
                        ShortNavigationBarItem(selected = session.destination == index,
                            onClick = { session.destination = index }, icon = { Symbol(item.second) }, label = { Text(item.first) })
                    }
                }
            } else {
                Column(Modifier.navigationBarsPadding().imePadding().fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("仅本次会话", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                        Text("Markdown · ${note.tags.size} 个标签", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                    }
                }
            }
        },
        floatingActionButton = {
            if (note == null && session.destination == 0) {
                ExtendedFloatingActionButton(onClick = { session.newNote() }, icon = { Symbol(R.drawable.sym_edit_square) }, text = { Text("记一笔") })
            }
        }
    ) { inset ->
        if (note != null) {
            Column(Modifier.padding(inset).fillMaxSize()) {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(note.updated, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                    Text("${note.folder} / ${note.title}.md", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                key(note.id) {
                    LocalEditor(note.copy(markdown = session.drafts[note.id] ?: note.markdown), session.style, false,
                        { session.updateDraft(note.id, it) }, {})
                }
            }
        } else {
            when (session.destination) {
                0 -> Library(session, Modifier.padding(inset))
                1 -> TagDirectory(session, Modifier.padding(inset))
                else -> LinkDirectory(session, Modifier.padding(inset))
            }
        }
    }
    if (backlinkSheet && note != null) ModalBottomSheet(onDismissRequest = { backlinkSheet = false }) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("连接到这篇笔记", style = MaterialTheme.typography.headlineSmall)
            val incoming = session.notes.filter { it.markdown.contains("[[${note.title}]]") }
            if (incoming.isEmpty()) Text("还没有笔记引用它", color = c.onSurfaceVariant)
            incoming.forEach { linked ->
                ListItem(headlineContent = { Text(linked.title) }, supportingContent = { Text(linked.folder) },
                    leadingContent = { Symbol(R.drawable.sym_link) }, modifier = Modifier.clickable { session.noteId = linked.id; backlinkSheet = false })
            }
        }
    }
}

@Composable
private fun Library(session: ResearchSession, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    val paper = session.style == Style.Paper
    val expressive = session.style == Style.Expressive
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 108.dp), verticalArrangement = Arrangement.spacedBy(if (expressive) 12.dp else 4.dp)) {
        item {
            Column(Modifier.padding(top = 18.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (expressive) Text("给想法，一个位置。", style = MaterialTheme.typography.labelLarge, color = c.primary)
                Text(if (expressive) "灵感手册" else if (paper) "笔记" else "我的知识库",
                    style = if (paper) MaterialTheme.typography.displayMedium else MaterialTheme.typography.headlineLargeEmphasized)
                Text("${session.notes.size} 篇笔记 · 随时回到你的思考", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            }
        }
        item {
            OutlinedTextField(value = session.query, onValueChange = { session.query = it },
                label = { Text("搜索笔记内容") }, leadingIcon = { Symbol(R.drawable.sym_search) },
                trailingIcon = { if (session.query.isNotEmpty()) IconButton(onClick = { session.query = "" }) { Symbol(R.drawable.sym_close, "清除搜索") } },
                shape = RoundedCornerShape(if (paper) 12.dp else 28.dp), singleLine = true,
                modifier = Modifier.fillMaxWidth())
        }
        item {
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!session.onlyPinned, { session.onlyPinned = false }, label = { Text("全部笔记") })
                FilterChip(session.onlyPinned, { session.onlyPinned = true }, label = { Text("收藏") })
                Spacer(Modifier.weight(1f))
                Text("${session.visible.size} 篇", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
            }
        }
        if (session.visible.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Symbol(R.drawable.sym_search, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(16.dp))
                Text("没有找到相关笔记", style = MaterialTheme.typography.titleMedium)
                Text("试试标题、正文或标签", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            }
        }
        session.visible.groupBy { if (it.pinned) "置顶" else "最近编辑" }.forEach { (section, notes) ->
            item { Text(section, Modifier.padding(top = 6.dp, bottom = 8.dp), style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant) }
            items(notes, key = { it.id }) { note ->
                val fill = when {
                    expressive && note.pinned -> c.surfaceContainerHigh
                    paper -> c.surfaceContainerLowest
                    !paper && note.pinned -> c.surfaceContainer
                    else -> c.surface
                }
                Surface(onClick = { session.noteId = note.id }, color = fill,
                    shape = RoundedCornerShape(if (expressive) 20.dp else if (paper) 12.dp else 8.dp)) {
                    Row(Modifier.fillMaxWidth().padding(if (expressive || paper || note.pinned) 18.dp else 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (!paper) Surface(color = if (note.pinned) c.secondaryContainer else c.surfaceContainerHigh,
                            shape = RoundedCornerShape(if (expressive) 16.dp else 8.dp), modifier = Modifier.size(44.dp)) {
                            Box(contentAlignment = Alignment.Center) { Symbol(if (note.id == "inbox") R.drawable.sym_edit_square else R.drawable.sym_description) }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(note.title, Modifier.weight(1f), style = if (expressive && note.pinned) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium, maxLines = 2)
                                if (paper) Text(note.updated, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                            }
                            Text(note.excerpt, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, maxLines = if (note.pinned) 2 else 1, overflow = TextOverflow.Ellipsis)
                            if (note.pinned || paper) Text(if (paper) note.folder else note.tags.joinToString("   ") { "#$it" },
                                style = MaterialTheme.typography.labelMedium, color = if (paper) c.onSurfaceVariant else c.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TagDirectory(session: ResearchSession, modifier: Modifier) {
    val tags = session.notes.flatMap { it.tags }.distinct()
    LazyColumn(modifier, contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("从一个标签开始", style = MaterialTheme.typography.headlineLarge) }
        items(tags) { tag ->
            Surface(onClick = { session.query = "#$tag"; session.destination = 0; session.onlyPinned = false },
                color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("#$tag", style = MaterialTheme.typography.titleMedium)
                    Text("${session.notes.count { tag in it.tags }} 篇")
                }
            }
        }
    }
}

@Composable
private fun LinkDirectory(session: ResearchSession, modifier: Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("笔记之间的联系", style = MaterialTheme.typography.headlineLarge) }
        items(session.notes.filter { it.markdown.contains("[[") }) { note ->
            ListItem(headlineContent = { Text(note.title) }, supportingContent = { Text("查看文中的知识连接") },
                leadingContent = { Symbol(R.drawable.sym_hub) }, modifier = Modifier.clickable { session.noteId = note.id })
        }
    }
}
