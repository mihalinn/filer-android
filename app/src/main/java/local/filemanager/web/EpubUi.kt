package local.filemanager

import android.annotation.SuppressLint
import android.graphics.Color as AColor
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

private const val HOST = HtmlRules.HOST
private const val EPUB_PREFIX = "/epub/"
private const val EPUB_ENTRY_MAX = 48L * 1024 * 1024

private class EpubSource(val handle: ArchiveHandle, val book: EpubBook) {
    private val byName = handle.source.entries.associateBy { ZipTree.clean(it.name) }

    /** 縦書きの本か(CSS に縦書きの指定がある) */
    val vertical: Boolean by lazy {
        book.cssPaths.any { p -> read(p)?.let { Epub.isVerticalCss(TextDecode.decode(it, false).text) } == true }
    }

    fun read(path: String): ByteArray? {
        val e = byName[ZipTree.clean(path)] ?: return null
        if (e.size > EPUB_ENTRY_MAX) return null
        // 書庫は同時に 2 か所を読めないので、順番に読む
        return synchronized(handle) { runCatching { handle.source.open(e).use { it.readBytes() } }.getOrNull() }
    }
}

private sealed interface EpubLoad {
    data object Busy : EpubLoad
    data class Ok(val src: EpubSource) : EpubLoad
    data class Fail(val message: String) : EpubLoad
}

private fun openEpub(ctx: android.content.Context, fs: Fs, item: FileItem): EpubLoad {
    val handle = try {
        ArchiveOpener.open(fs, item, ctx.cacheDir)
    } catch (e: Exception) {
        return EpubLoad.Fail(e.message ?: Txt(R.string.epub_ui_001))
    }
    return try {
        val byName = handle.source.entries.associateBy { ZipTree.clean(it.name) }
        val book = Epub.parse { p ->
            byName[ZipTree.clean(p)]?.takeIf { it.size <= EPUB_ENTRY_MAX }
                ?.let { e -> runCatching { handle.source.open(e).use { it.readBytes() } }.getOrNull() }
        }
        if (book.spine.isEmpty()) {
            handle.close()
            EpubLoad.Fail(Txt(R.string.epub_ui_002))
        } else {
            EpubLoad.Ok(EpubSource(handle, book))
        }
    } catch (e: OpException) {
        handle.close()
        EpubLoad.Fail(e.message ?: Txt(R.string.epub_ui_003))
    } catch (e: Exception) {
        handle.close()
        EpubLoad.Fail(Txt(R.string.epub_ui_004, e.message ?: e.javaClass.simpleName))
    } catch (e: OutOfMemoryError) {
        handle.close()
        EpubLoad.Fail(Txt(R.string.epub_ui_005))
    }
}

private fun urlFor(path: String, anchor: String?): String =
    Uri.Builder().scheme("https").authority(HOST).path(EPUB_PREFIX + path).apply { if (anchor != null) fragment(anchor) }.build().toString()

private fun emptyResponse(status: Int, reason: String) =
    WebResourceResponse("text/plain", "UTF-8", status, reason, emptyMap(), ByteArrayInputStream(ByteArray(0)))

private fun serveEpub(src: EpubSource, path: String, dark: Boolean, cssHeight: Int): WebResourceResponse {
    val bytes = src.read(path) ?: return emptyResponse(404, "Not Found")
    val mime = Epub.mimeFor(path)
    if (mime != "text/html") return WebResourceResponse(mime, null, ByteArrayInputStream(bytes))
    // 章の HTML。文字コードをそろえ、暗い配色のときはスタイルを差し込む
    var html = TextDecode.decode(bytes, false).text
    if (dark) html = Epub.injectHead(html, Epub.DARK_CSS)
    // 縦書きは、列の長さが「画面の高さ」で決まる。高さがまだ決まっていない間に組まれると、1 文字ごとに折り返してしまうので、高さを教える
    if (cssHeight > 0 && src.vertical) html = Epub.injectHead(html, Epub.verticalCss(cssHeight))
    return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
}

/**
 * EPUB(電子書籍)を読む。縦書きの日本語や、本の中の画像・CSS・フォントもそのまま表示する(WebView)。
 * 章ごとに前後へ移れて、目次から飛べる。JavaScript と外への通信は使わない。
 */
@Composable
internal fun EpubPane(fs: Fs, item: FileItem, bars: Boolean, fontSp: Int, onTap: () -> Unit) {
    val ctx = LocalContext.current
    val load = produceState<EpubLoad>(EpubLoad.Busy, item.path) {
        value = withContext(Dispatchers.IO) { openEpub(ctx, fs, item) }
    }.value
    when (load) {
        EpubLoad.Busy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Pv.text) }
        is EpubLoad.Fail -> Box(Modifier.fillMaxSize()) {
            Text(load.message, color = Pv.text, modifier = Modifier.align(Alignment.Center).padding(32.dp))
        }
        is EpubLoad.Ok -> {
            DisposableEffect(load.src) { onDispose { load.src.handle.close() } }
            EpubReader(load.src, item, bars, fontSp)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EpubReader(src: EpubSource, item: FileItem, bars: Boolean, fontSp: Int) {
    val book = src.book
    val uri = LocalUriHandler.current
    var index by remember { mutableIntStateOf(0) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var pending by remember { mutableStateOf<String?>(null) }
    var tocOpen by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }
    val dark = Pv.dark
    val spineIndex = remember(book) { book.spine.withIndex().associate { it.value to it.index } }
    val cssHeight = remember { IntArray(1) }

    fun go(i: Int, anchor: String? = null) {
        val n = i.coerceIn(0, book.spine.lastIndex)
        index = n
        web?.loadUrl(urlFor(book.spine[n], anchor))
    }

    // 本文(WebView)と、その下の操作バーは、重ならないように縦に並べる
    Column(Modifier.fillMaxSize().belowBars()) {
    key(dark) {
        AndroidView(
            factory = { c ->
                WebView(c).apply {
                    setBackgroundColor(if (dark) 0xFF121212.toInt() else AColor.WHITE)
                    settings.apply {
                        javaScriptEnabled = false
                        domStorageEnabled = false
                        allowFileAccess = false
                        allowContentAccess = false
                        builtInZoomControls = false
                        displayZoomControls = false
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        setSupportMultipleWindows(false)
                        setGeolocationEnabled(false)
                        textZoom = (fontSp * 100 / 14).coerceIn(50, 300)
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val u = request.url
                            if (u.host == HOST) {
                                val p = u.path ?: return emptyResponse(404, "Not Found")
                                if (!p.startsWith(EPUB_PREFIX)) return emptyResponse(403, "Forbidden")
                                return serveEpub(src, p.removePrefix(EPUB_PREFIX), dark, cssHeight[0])
                            }
                            return if (HtmlRules.shouldBlock(u.scheme, u.host, false)) emptyResponse(403, "Blocked") else null
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val u = request.url
                            if (u.host == HOST) {
                                val p = (u.path ?: "").removePrefix(EPUB_PREFIX)
                                spineIndex[p]?.let { index = it }
                                return false
                            }
                            if (HtmlRules.isExternalLink(u.toString())) pending = u.toString()
                            return true
                        }
                    }
                    // 画面の大きさが決まってから読み込む(縦書きは、高さが決まっていないと正しく組めない)
                    val started = java.util.concurrent.atomic.AtomicBoolean(false)
                    addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
                        val h = bottom - top
                        if (h > 0) {
                            cssHeight[0] = (h / v.resources.displayMetrics.density).toInt()
                            if (started.compareAndSet(false, true)) (v as WebView).loadUrl(urlFor(book.spine[index], null))
                        }
                    }
                    web = this
                }
            },
            update = { it.settings.textZoom = (fontSp * 100 / 14).coerceIn(50, 300) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            onRelease = {
                it.stopLoading()
                it.destroy()
                if (web === it) web = null
            },
        )
    }

    if (bars) {
        val chapterTitle = book.toc.firstOrNull { it.spineIndex == index }?.title
            Column(Modifier.fillMaxWidth().background(Pv.bar).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text(
                    (chapterTitle?.let { "$it  " } ?: "") + "${index + 1} / ${book.spine.size}",
                    color = Pv.barSub, fontSize = 12.sp, maxLines = 1, modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly) {
                    TextButton(onClick = { go(index - 1) }, enabled = index > 0) { Text(Txt(R.string.epub_ui_006)) }
                    TextButton(onClick = { tocOpen = true }, enabled = book.toc.isNotEmpty()) { Text(Txt(R.string.epub_ui_007)) }
                    TextButton(onClick = { infoOpen = true }) { Text(Txt(R.string.epub_ui_008)) }
                    TextButton(onClick = { go(index + 1) }, enabled = index < book.spine.lastIndex) { Text(Txt(R.string.epub_ui_009)) }
                }
            }
    }
    }

    if (tocOpen) {
        AlertDialog(
            onDismissRequest = { tocOpen = false },
            title = { Text(Txt(R.string.epub_ui_010)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    items(book.toc.size) { i ->
                        val t = book.toc[i]
                        Text(
                            t.title, fontSize = 15.sp,
                            color = if (t.spineIndex == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth().clickable { tocOpen = false; go(t.spineIndex, t.anchor) }
                                .padding(start = (t.depth * 16).dp, top = 9.dp, bottom = 9.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { tocOpen = false }) { Text(Txt(R.string.epub_ui_011)) } },
        )
    }
    if (infoOpen) {
        val rows = listOfNotNull(
            book.title?.let { Txt(R.string.epub_ui_012) to it },
            book.creators.takeIf { it.isNotEmpty() }?.let { Txt(R.string.epub_ui_013) to it.joinToString(Txt(R.string.epub_ui_014)) },
            book.publisher?.let { Txt(R.string.epub_ui_015) to it },
            book.language?.let { Txt(R.string.epub_ui_016) to it },
            book.date?.let { Txt(R.string.epub_ui_017) to it },
            book.identifier?.let { Txt(R.string.epub_ui_018) to it },
            Txt(R.string.epub_ui_019) to "${book.spine.size}",
            Txt(R.string.epub_ui_020) to if (book.rtl) Txt(R.string.epub_ui_021) else Txt(R.string.epub_ui_022),
            Txt(R.string.epub_ui_023) to item.name,
            Txt(R.string.epub_ui_024) to FileOps.formatSize(item.size),
        )
        AlertDialog(
            onDismissRequest = { infoOpen = false },
            title = { Text(Txt(R.string.epub_ui_025)) },
            text = {
                SelectionContainer {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        for ((label, value) in rows) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.width(96.dp))
                                Text(value, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { infoOpen = false }) { Text(Txt(R.string.epub_ui_026)) } },
        )
    }
    pending?.let { url ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(Txt(R.string.epub_ui_027)) },
            text = { Text(url, fontSize = 13.sp) },
            confirmButton = { TextButton(onClick = { runCatching { uri.openUri(url) }; pending = null }) { Text(Txt(R.string.epub_ui_028)) } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(Txt(R.string.epub_ui_029)) } },
        )
    }
}
