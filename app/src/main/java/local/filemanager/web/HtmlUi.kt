package local.filemanager

import android.annotation.SuppressLint
import android.graphics.Color as AColor
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

private const val HOST = HtmlRules.HOST
private const val HTML_MAX_BYTES = 8 * 1024 * 1024

private sealed interface HtmlLoad {
    data object Busy : HtmlLoad
    data class Ok(val html: String) : HtmlLoad
    data class Fail(val message: String) : HtmlLoad
}

private fun readHtml(fs: Fs, item: FileItem): HtmlLoad = try {
    val (bytes, truncated) = fs.open(item.path).use { TextDecode.readUpTo(it, HTML_MAX_BYTES) }
    HtmlLoad.Ok(TextDecode.decode(bytes, truncated).text)
} catch (e: Exception) {
    HtmlLoad.Fail(e.message ?: "読み込めません")
} catch (e: OutOfMemoryError) {
    HtmlLoad.Fail("大きすぎて読み込めません")
}

private fun empty(status: Int, reason: String) =
    WebResourceResponse("text/plain", "UTF-8", status, reason, emptyMap(), ByteArrayInputStream(ByteArray(0)))

/** ページから読まれたファイルを、Fs から返す。読んでよい場所は HtmlRules が決める。 */
private fun serve(fs: Fs, htmlPath: String, requestPath: String, dark: Boolean): WebResourceResponse {
    val p = HtmlRules.normalize(requestPath)
    if (!HtmlRules.isAllowed(htmlPath, p)) return empty(403, "Forbidden")
    val ext = FileOps.extensionOf(p)
    val mime = HtmlRules.mimeFor(ext)
    return try {
        if (HtmlRules.isTextual(ext)) {
            val (bytes, truncated) = fs.open(p).use { TextDecode.readUpTo(it, HTML_MAX_BYTES) }
            val raw = TextDecode.decode(bytes, truncated).text
            // 同じ場所の別の HTML ページも、暗くして見せる
            val text = if (dark && mime == "text/html") HtmlDark.inject(raw) else raw
            WebResourceResponse(mime, "UTF-8", ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        } else {
            WebResourceResponse(mime, null, fs.open(p))
        }
    } catch (e: Exception) {
        empty(404, "Not Found")
    }
}

/**
 * HTML をページとして表示する。JavaScript と外部への通信は、既定では使わない(js / net で許可する)。
 * 同じフォルダの画像・CSS・別の HTML は、端末内でも NAS でも表示できる。
 */
@Composable
internal fun HtmlPane(fs: Fs, item: FileItem, js: Boolean, net: Boolean, dark: Boolean = false) {
    val load = produceState<HtmlLoad>(HtmlLoad.Busy, item.path) {
        value = withContext(Dispatchers.IO) { readHtml(fs, item) }
    }.value
    when (load) {
        HtmlLoad.Busy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Pv.text) }
        is HtmlLoad.Fail -> Box(Modifier.fillMaxSize()) {
            Text(load.message, color = Pv.text, modifier = Modifier.align(Alignment.Center).padding(32.dp))
        }
        is HtmlLoad.Ok -> HtmlWeb(fs, item, load.html, js, net, dark)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HtmlWeb(fs: Fs, item: FileItem, source: String, js: Boolean, net: Boolean, dark: Boolean) {
    val html = remember(source, dark) { if (dark) HtmlDark.inject(source) else source }
    val uri = LocalUriHandler.current
    var pending by remember { mutableStateOf<String?>(null) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var canBack by remember { mutableStateOf(false) }
    val base = remember(item.path) { Uri.Builder().scheme("https").authority(HOST).path(item.path).build().toString() }

    // ページ内でリンクをたどったあとの「戻る」は、ページを戻す(最初のページなら、ビューアを閉じる)
    BackHandler(enabled = canBack) { web?.goBack() }

    // 設定を変えたら、WebView を作り直して読み込み直す
    key(js, net, dark) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(if (dark) AColor.BLACK else AColor.WHITE)
                    settings.apply {
                        javaScriptEnabled = js
                        domStorageEnabled = js
                        allowFileAccess = false
                        allowContentAccess = false
                        builtInZoomControls = true
                        displayZoomControls = false
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        mediaPlaybackRequiresUserGesture = true
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        setSupportMultipleWindows(false)
                        setGeolocationEnabled(false)
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val u = request.url
                            if (u.host == HOST) return serve(fs, item.path, u.path ?: "/", dark)
                            // 外のサーバーへの通信だけ、許可していなければここで止める(ページ本体の data: などは止めない)
                            return if (HtmlRules.shouldBlock(u.scheme, u.host, net)) empty(403, "Blocked") else null
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val u = request.url
                            if (u.host == HOST) {
                                val ext = FileOps.extensionOf(u.path ?: "")
                                // 同じ場所の HTML(や、同じページ内の移動)だけ、ページとして開く
                                return !(ext == "html" || ext == "htm" || ext == "" )
                            }
                            if (HtmlRules.isExternalLink(u.toString())) pending = u.toString()
                            return true
                        }

                        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                            canBack = view.canGoBack()
                        }
                    }
                    loadDataWithBaseURL(base, html, "text/html", "UTF-8", base)
                    web = this
                }
            },
            modifier = Modifier.fillMaxSize().belowBars(),
            onRelease = {
                it.stopLoading()
                it.destroy()
                if (web === it) web = null
            },
        )
    }

    pending?.let { url ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("リンクを開く?") },
            text = { Text(url, fontSize = 13.sp) },
            confirmButton = { TextButton(onClick = { runCatching { uri.openUri(url) }; pending = null }) { Text("開く") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("キャンセル") } },
        )
    }
}
