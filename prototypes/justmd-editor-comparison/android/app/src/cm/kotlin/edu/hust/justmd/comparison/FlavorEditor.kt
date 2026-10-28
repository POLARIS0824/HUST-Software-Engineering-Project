package edu.hust.justmd.comparison

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

fun createFlavorEditorPort(context: Context): EditorPort = CodeMirrorPort(context)

@Composable
fun FlavorEditor(port: EditorPort, colors: ColorScheme, modifier: Modifier = Modifier, visible: Boolean = true) {
    val host = port as CodeMirrorPort
    AndroidView(factory = { host.webView }, modifier = modifier, update = {
        fun hex(color: androidx.compose.ui.graphics.Color) = "#%06X".format(color.toArgb() and 0xffffff)
        val roles = JSONObject().put("surface", hex(colors.surface)).put("onSurface", hex(colors.onSurface))
            .put("primary", hex(colors.primary)).put("muted", hex(colors.onSurfaceVariant))
            .put("outline", hex(colors.outline)).put("container", hex(colors.surfaceContainer))
            .put("selection", hex(colors.secondaryContainer))
        it.setBackgroundColor(colors.surface.toArgb())
        it.visibility = if (visible) android.view.View.VISIBLE else android.view.View.INVISIBLE
        host.appearance = roles
        it.evaluateJavascript("window.setAppearance && window.setAppearance($roles)", null)
        if (visible) it.post {
            it.evaluateJavascript("window.setViewport && window.setViewport(${it.width / it.resources.displayMetrics.density},${it.height / it.resources.displayMetrics.density})", null)
        }
    })
}

@SuppressLint("SetJavaScriptEnabled")
private class CodeMirrorPort(context: Context) : EditorPort {
    private val observed = MutableStateFlow<DraftSnapshot?>(null)
    private val available = MutableStateFlow(false)
    override val active: StateFlow<DraftSnapshot?> = observed.asStateFlow()
    override val ready: StateFlow<Boolean> = available.asStateFlow()
    private val session = UUID.randomUUID().toString()
    private val operations = Mutex()
    private var generation = 0L
    private var sequence = 0L
    private var commandId = 0L
    private var noteId: String? = null
    private var pending: Pair<Long, CompletableDeferred<DraftSnapshot>>? = null
    private var closed = false
    var appearance = JSONObject()
    val webView: WebView

    init {
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
        webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            isFocusableInTouchMode = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val uri = request.url
                    if (uri.scheme == "https" && uri.host == "appassets.androidplatform.net" && uri.port == -1 && uri.path?.startsWith("/assets/editor/") == true) {
                        loader.shouldInterceptRequest(uri)?.let { return it }
                    }
                    return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                }
            }
            addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                val density = context.resources.displayMetrics.density
                evaluateJavascript("window.setViewport && window.setViewport(${(right-left)/density},${(bottom-top)/density})", null)
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, "EditorBridge", setOf("https://appassets.androidplatform.net")) { _, message, origin, mainFrame, _ ->
                if (mainFrame && origin.scheme == "https" && origin.host == "appassets.androidplatform.net" && origin.port == -1) {
                    val data = message.data
                    if (data != null && data.length <= 2_500_000) runCatching { receive(JSONObject(data)) }
                        .onFailure { pending?.second?.completeExceptionally(it) }
                }
            }
            webView.loadUrl("https://appassets.androidplatform.net/assets/editor/index.html")
        } else webView.loadData("<html><body>CodeMirror unavailable. Secure WebMessageListener is unsupported.</body></html>", "text/html", "UTF-8")
    }

    private fun receive(json: JSONObject) {
        if (closed) return
        if (json.getString("kind") == "ready") {
            available.value = true
            webView.evaluateJavascript("window.setAppearance($appearance);window.setViewport(${webView.width / webView.resources.displayMetrics.density},${webView.height / webView.resources.displayMetrics.density})", null)
            return
        }
        if (json.getString("session") != session || json.getString("noteId") != noteId || json.getLong("generation") != generation) return
        val nextSequence = json.getLong("sequence")
        if (nextSequence <= sequence) return
        val text = json.getString("text")
        require(text.length <= 400_000) { "Source too large" }
        val selection = Utf16Selection(json.getInt("anchor"), json.getInt("head"))
        requireSelection(text, selection)
        val revision = json.getLong("revision")
        require(revision >= 0)
        val snapshot = DraftSnapshot(json.getString("noteId"), text, selection, revision, json.getBoolean("canUndo"), json.getBoolean("canRedo"))
        sequence = nextSequence
        observed.value = snapshot
        val waiting = pending
        if (!json.isNull("commandId") && waiting?.first == json.getLong("commandId")) {
            if (json.isNull("error")) waiting.second.complete(snapshot)
            else waiting.second.completeExceptionally(IllegalStateException(json.getString("error")))
        }
    }

    private suspend fun send(message: JSONObject): DraftSnapshot {
        check(!closed) { "Editor closed" }
        withTimeout(4000) { available.first { it } }
        val id = ++commandId
        val result = CompletableDeferred<DraftSnapshot>()
        pending = id to result
        message.put("session", session).put("noteId", noteId).put("generation", generation).put("commandId", id)
        return try {
            webView.evaluateJavascript("window.receive($message)", null)
            withTimeout(4000) { result.await() }
        } finally { pending = null }
    }

    override suspend fun selectNote(note: NoteDocument): DraftSnapshot = withContext(Dispatchers.Main.immediate) {
        operations.withLock {
            require(note.id.isNotEmpty() && note.id.length <= 200 && note.markdown.length <= 400_000)
            if (noteId != null) send(JSONObject().put("kind", "flush"))
            noteId = note.id
            generation++
            send(JSONObject().put("kind", "select").put("text", normalizedSource(note.markdown)))
        }
    }

    override suspend fun command(command: EditorCommand): DraftSnapshot = withContext(Dispatchers.Main.immediate) {
        operations.withLock {
            check(noteId != null) { "No active note" }
            val message = JSONObject().put("kind", "command")
            val operation = when (command) {
                EditorCommand.Bold -> "bold"
                EditorCommand.Heading -> "heading"
                EditorCommand.Link -> "link"
                EditorCommand.Undo -> "undo"
                EditorCommand.Redo -> "redo"
                is EditorCommand.Select -> { message.put("anchor", command.anchor).put("head", command.head); "select" }
                is EditorCommand.Insert -> { require(command.text.length <= 400_000); message.put("text", normalizedSource(command.text)); "insert" }
            }
            send(message.put("operation", operation))
        }
    }

    override suspend fun snapshotForRead(): DraftSnapshot = withContext(Dispatchers.Main.immediate) {
        operations.withLock { check(noteId != null); send(JSONObject().put("kind", "flush")) }
    }

    override fun close() {
        closed = true
        available.value = false
        pending?.second?.completeExceptionally(IllegalStateException("Editor closed"))
        webView.destroy()
    }
}
