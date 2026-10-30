package edu.hust.looma.prototype

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.core.view.doOnLayout
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LocalEditor(note: Note, style: Style, probe: Boolean, onDraft: (String) -> Unit, onEvidence: (String) -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val draftCallback by rememberUpdatedState(onDraft)
    val evidenceCallback by rememberUpdatedState(onEvidence)
    fun hex(color: androidx.compose.ui.graphics.Color) = "#%06X".format(color.toArgb() and 0xFFFFFF)
    val appearance = JSONObject().put("style", style.name).put("background", hex(colors.surface))
        .put("foreground", hex(colors.onSurface)).put("muted", hex(colors.onSurfaceVariant))
        .put("accent", hex(colors.primary)).put("container", hex(colors.surfaceContainer))
    val editor = remember(note.id) {
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = (context as? android.app.Activity)?.intent?.getBooleanExtra("strictDom", false) != true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.blockNetworkLoads = true
            isFocusableInTouchMode = true
            setBackgroundColor(colors.surface.toArgb())
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    if (request.url.scheme == "https" && request.url.host == "appassets.androidplatform.net" && request.url.path?.startsWith("/assets/") == true) {
                        loader.shouldInterceptRequest(request.url)?.let { return it }
                    }
                    synchronized(context) { File(context.filesDir, "blocked-requests.log").appendText(request.url.toString() + "\n") }
                    return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun onPageFinished(view: WebView, url: String) {
                    val jsHash = MessageDigest.getInstance("SHA-256").digest(context.assets.open("editor/vditor/dist/index.min.js").use { it.readBytes() })
                        .joinToString("") { "%02x".format(it) }
                    val locked = JSONObject(context.assets.open("editor/assets-lock.json").bufferedReader().use { it.readText() }).getString("vditorJsSha256")
                    check(jsHash.equals(locked, ignoreCase = true)) { "Pinned Vditor asset hash mismatch" }
                    val config = JSONObject().put("body", note.markdown).put("probe", probe).put("appearance", appearance)
                        .put("viewportHeight", view.height / context.resources.displayMetrics.density)
                        .put("bundleSha256", jsHash).put("webView", WebView.getCurrentWebViewPackage()?.versionName)
                        .put("engine", (context as? android.app.Activity)?.intent?.getStringExtra("engine") ?: "vditor")
                        .put("domStorageEnabled", settings.domStorageEnabled)
                    view.evaluateJavascript("window.launch($config)", null)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    synchronized(context) { File(context.filesDir, "editor-console.log").appendText("${message.messageLevel()} ${message.message()}\n") }
                    return true
                }
            }
            addOnLayoutChangeListener { view, _, top, _, bottom, _, _, _, _ ->
                if (bottom > top) evaluateJavascript("window.setViewport && window.setViewport(${(bottom - top) / context.resources.displayMetrics.density})", null)
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                WebViewCompat.addWebMessageListener(this, "ResearchBridge", setOf("https://appassets.androidplatform.net")) { _, message, origin, mainFrame, _ ->
                    if (mainFrame && origin.scheme == "https" && origin.host == "appassets.androidplatform.net" && (message.data?.length ?: 0) <= 1_000_000) {
                        runCatching {
                            val json = JSONObject(message.data ?: return@addWebMessageListener)
                            when (json.optString("kind")) {
                                "draft" -> draftCallback(json.getString("body"))
                                "evidence" -> evidenceCallback(json.getJSONObject("result").toString(2))
                                "diagnostic" -> File(context.filesDir, "editor-layout.json").writeText(json.getJSONObject("result").toString(2))
                            }
                        }.onFailure { File(context.filesDir, "editor-console.log").appendText("BRIDGE ${it.message}\n") }
                    }
                }
            }
        }
    }
    AndroidView(factory = { editor.apply {
        doOnLayout { loadUrl("https://appassets.androidplatform.net/assets/editor/index.html") }
    } }, modifier = Modifier.fillMaxSize(), update = {
        it.setBackgroundColor(colors.surface.toArgb())
        it.evaluateJavascript("window.setAppearance && window.setAppearance($appearance)", null)
    })
    DisposableEffect(editor) { onDispose { editor.destroy() } }
}
