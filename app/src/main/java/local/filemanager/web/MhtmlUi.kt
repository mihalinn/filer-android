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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

private const val HOST = HtmlRules.HOST
private const val CID_PATH = "/mht-cid/"
private const val MHT_LIMIT = 96L * 1024 * 1024

private sealed interface MhtLoad {
    data object Busy : MhtLoad
    data class Ok(val doc: MhtDoc, val html: String, val base: String) : MhtLoad
    data class Fail(val message: String) : MhtLoad
}

private fun loadMht(fs: Fs, item: FileItem, web: Boolean): MhtLoad {
    if (item.size > MHT_LIMIT) return MhtLoad.Fail("大きすぎて開けません(${MHT_LIMIT / 1024 / 1024} MB まで)。メニューで「ソース」に切り替えてください")
    return try {
        val bytes = fs.open(item.path).use { it.readBytes() }
        val doc = (if (web) Plist.webArchive(bytes) else Mhtml.parse(bytes)) ?: return MhtLoad.Fail(if (web) "ウェブアーカイブ(Safari の保存形式)として読めません" else "MHTML(ウェブページの保存形式)として読めません")
        val main = doc.main ?: return MhtLoad.Fail("本文の HTML が見つかりません。メニューで「ソース」に切り替えると中身を見られます")
        val html = Mhtml.rewriteCid(main.text(), "https://$HOST$CID_PATH")
        MhtLoad.Ok(doc, html, main.location ?: doc.pageUrl ?: "https://$HOST/mht/index.html")
    } catch (e: Exception) {
        MhtLoad.Fail(e.message ?: "読み込めません")
    } catch (e: OutOfMemoryError) {
        MhtLoad.Fail("大きすぎて読み込めません")
    }
}

private fun empty(status: Int, reason: String) =
    WebResourceResponse("text/plain", "UTF-8", status, reason, emptyMap(), ByteArrayInputStream(ByteArray(0)))

/** ページから読まれたものを、保存してある中身から返す。中に無いものは null(外への通信は、呼び出し側で止める)。 */
private fun serveMht(doc: MhtDoc, u: Uri, dark: Boolean): WebResourceResponse? {
    val part = if (u.host == HOST && (u.path ?: "").startsWith(CID_PATH)) doc.findCid((u.path ?: "").removePrefix(CID_PATH)) else doc.findUrl(u.toString())
    part ?: return null
    val textual = part.isHtml || part.isCss || part.contentType.startsWith("text/") || part.contentType == "application/javascript"
    val headers = mapOf("Access-Control-Allow-Origin" to "*")
    return if (textual) {
        val rewritten = Mhtml.rewriteCid(part.text(), "https://$HOST$CID_PATH")
        val text = if (dark && part.isHtml) HtmlDark.inject(rewritten) else rewritten
        WebResourceResponse(part.contentType, "UTF-8", 200, "OK", headers, ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
    } else {
        WebResourceResponse(part.contentType, null, 200, "OK", headers, ByteArrayInputStream(part.body))
    }
}

/**
 * MHTML(.mht / .mhtml。ブラウザの「ページを保存」で作られる、画像や CSS ごとまとめたウェブページ)を、ページとして表示する。
 * 中に保存されたものだけを使って表示し、外への通信はしない。JavaScript は、既定では使わない。
 */
@Composable
internal fun MhtmlPane(fs: Fs, item: FileItem, js: Boolean, wide: Boolean, web: Boolean = false, dark: Boolean = false) {
    val load = produceState<MhtLoad>(MhtLoad.Busy, item.path) {
        value = withContext(Dispatchers.IO) { loadMht(fs, item, web) }
    }.value
    when (load) {
        MhtLoad.Busy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Pv.text) }
        is MhtLoad.Fail -> Box(Modifier.fillMaxSize()) {
            Text(load.message, color = Pv.text, modifier = Modifier.align(Alignment.Center).padding(32.dp))
        }
        is MhtLoad.Ok -> MhtWeb(load, js, wide, dark)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MhtWeb(load: MhtLoad.Ok, js: Boolean, wide: Boolean, dark: Boolean) {
    val uri = LocalUriHandler.current
    var pending by remember { mutableStateOf<String?>(null) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var canBack by remember { mutableStateOf(false) }
    val doc = load.doc
    val base = Mhtml.normalizeUrl(load.base)

    BackHandler(enabled = canBack) { web?.goBack() }

    // パソコン向けの幅: ページの viewport 指定を外し、広い画面(約 980px)で組んでから、画面の幅に縮めて見せる(横にも動かせる・拡大できる)
    val html = remember(load, wide, dark) {
        val h = if (wide) Mhtml.stripViewportMeta(load.html) else load.html
        if (dark) HtmlDark.inject(h) else h
    }
    key(js, wide, dark) {
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
                        setSupportZoom(true)
                        mediaPlaybackRequiresUserGesture = true
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        setSupportMultipleWindows(false)
                        setGeolocationEnabled(false)
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val u = request.url
                            serveMht(doc, u, dark)?.let { return it }
                            // 保存されていない外のサーバーへの通信は、止める
                            return if (u.host == HOST || HtmlRules.shouldBlock(u.scheme, u.host, false)) empty(404, "Not in archive") else null
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val u = request.url.toString()
                            val n = Mhtml.normalizeUrl(u)
                            if (n == base) return false // 同じページの中(目次などへのリンク)
                            doc.findUrl(u)?.let { if (it.isHtml) return false }
                            if (HtmlRules.isExternalLink(u)) pending = u
                            return true
                        }

                        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                            canBack = view.canGoBack()
                        }
                    }
                    loadDataWithBaseURL(load.base, html, "text/html", "UTF-8", load.base)
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
