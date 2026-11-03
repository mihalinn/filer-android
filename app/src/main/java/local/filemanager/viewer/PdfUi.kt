package local.filemanager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

// ---------------------------------------------------------------- PDF

private sealed interface PdfLoad {
    data object Busy : PdfLoad
    data class Ok(val doc: PdfDoc) : PdfLoad
    data class Fail(val message: String) : PdfLoad
}

private const val PDF_REMOTE_LIMIT = 300L * 1024 * 1024
private const val PDF_RENDER_MAX_PX = 2400

private object PdfCache {
    private val cache = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun get(key: String): Bitmap? = synchronized(cache) { cache.get(key) }
    fun put(key: String, b: Bitmap) = synchronized(cache) { cache.put(key, b) }
}

private class PdfDoc(val file: File) : Closeable {
    private val pfd: ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private lateinit var renderer: PdfRenderer
    private var closed = false
    var pageCount = 0
        private set
    var sizes: List<Pair<Int, Int>> = emptyList()
        private set

    init {
        try {
            renderer = PdfRenderer(pfd)
            pageCount = renderer.pageCount
            val list = ArrayList<Pair<Int, Int>>(pageCount)
            for (i in 0 until pageCount) {
                val p = renderer.openPage(i)
                try {
                    list.add(p.width.coerceAtLeast(1) to p.height.coerceAtLeast(1))
                } finally {
                    p.close()
                }
            }
            sizes = list
        } catch (e: Exception) {
            runCatching { pfd.close() }
            throw e
        }
    }

    /** 幅 widthPx の画像にして返す。PdfRenderer は同時に 1 ページしか開けないので、順番に処理する。 */
    fun render(index: Int, widthPx: Int): Bitmap? = synchronized(renderer) {
        if (closed) return null
        val page = renderer.openPage(index)
        try {
            val h = (widthPx.toLong() * page.height / page.width).toInt().coerceIn(1, 8000)
            val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(AColor.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        } finally {
            page.close()
        }
    }

    override fun close() {
        synchronized(renderer) {
            if (closed) return
            closed = true
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
    }
}

/** 端末内のファイルはそのまま、NAS のファイルは端末の一時領域に取り込んでから開く。 */
private fun openPdf(ctx: Context, fs: Fs, item: FileItem): PdfLoad {
    return try {
        val file: File = if (fs.isLocal) {
            File(item.path)
        } else {
            if (item.size > PDF_REMOTE_LIMIT) return PdfLoad.Fail("大きすぎて表示できません(${PDF_REMOTE_LIMIT / 1024 / 1024} MB まで)。メニューの「他のアプリで開く」を使ってください")
            val dir = File(ctx.cacheDir, "pdfview").also { it.mkdirs() }
            val name = "pdf_" + (fs.id + "|" + item.path + "|" + item.size + "|" + item.modified).hashCode().toUInt().toString(16) + ".pdf"
            val dest = File(dir, name)
            if (!dest.exists() || dest.length() != item.size) {
                val tmp = File(dir, "$name.part")
                fs.open(item.path).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                if (!tmp.renameTo(dest)) throw java.io.IOException("一時ファイルを作れません")
            }
            dest.setLastModified(System.currentTimeMillis())
            trimPdfCache(dir, dest)
            dest
        }
        PdfLoad.Ok(PdfDoc(file))
    } catch (e: SecurityException) {
        PdfLoad.Fail("パスワード付きのPDFは表示できません。メニューの「他のアプリで開く」を使ってください")
    } catch (e: Exception) {
        PdfLoad.Fail("PDFを開けません(${e.message ?: e.javaClass.simpleName})")
    }
}

/** 取り込んだ PDF が溜まりすぎないよう、古いものから消す(合計 500MB まで、1 日以上前のものは消す)。 */
private fun trimPdfCache(dir: File, keep: File) {
    val files = dir.listFiles()?.filter { it != keep }?.sortedBy { it.lastModified() } ?: return
    var total = files.sumOf { it.length() } + keep.length()
    val now = System.currentTimeMillis()
    for (f in files) {
        if (total > 500L * 1024 * 1024 || now - f.lastModified() > 24L * 3600 * 1000) {
            total -= f.length()
            f.delete()
        }
    }
}

@Composable
internal fun PdfPane(fs: Fs, item: FileItem, bars: Boolean, gotoDialog: Boolean, onGotoDismiss: () -> Unit, onTap: () -> Unit, dark: Boolean = false) {
    val ctx = LocalContext.current
    val load = produceState<PdfLoad>(PdfLoad.Busy, item.path) {
        value = withContext(Dispatchers.IO) { openPdf(ctx, fs, item) }
    }.value
    when (load) {
        PdfLoad.Busy -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Pv.text)
            if (!fs.isLocal) Text("読み込み中…", color = Pv.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
        }
        is PdfLoad.Fail -> Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }) {
            Text(load.message, color = Pv.text, modifier = Modifier.align(Alignment.Center).padding(32.dp))
        }
        is PdfLoad.Ok -> {
            DisposableEffect(load.doc) { onDispose { load.doc.close() } }
            PdfPages(load.doc, bars, gotoDialog, onGotoDismiss, onTap, dark)
        }
    }
}

@Composable
private fun PdfPages(doc: PdfDoc, bars: Boolean, gotoDialog: Boolean, onGotoDismiss: () -> Unit, onTap: () -> Unit, dark: Boolean) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 拡大は「画面の上での拡大」にする(ページの並びは変えず、見た目だけ拡大・移動する)。
    // 画面上の位置 = scale × 中身の位置 + tx(縦は、一覧のスクロールで合わせる)
    var scale by remember { mutableFloatStateOf(1f) }
    var tx by remember { mutableFloatStateOf(0f) }
    val panned = remember { BooleanArray(1) } // この操作で横に動かした(離したときのタップ扱いを避ける)
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 8.dp
    val single = doc.pageCount == 1

    fun clampTx(t: Float, s: Float, viewW: Float) = t.coerceIn(viewW * (1f - s), 0f)

    /** (cx, cy) の位置にある中身が動かないように、倍率を newScale に変える。 */
    fun zoomAt(newScale: Float, cx: Float, cy: Float, viewW: Float) {
        val s0 = scale
        var s1 = newScale.coerceIn(1f, 3f)
        if (s1 < 1.03f) s1 = 1f
        if (s1 == s0) return
        val xLocal = (cx - tx) / s0
        tx = if (s1 == 1f) 0f else clampTx(cx - s1 * xLocal, s1, viewW)
        val dy = cy / s0 - cy / s1
        scale = s1
        scope.launch { list.scrollBy(dy) }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Pv.pdfBackdrop).clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (!panned[0]) onTap() },
                    onDoubleTap = { p -> zoomAt(if (scale > 1.05f) 1f else 2f, p.x, p.y, size.width.toFloat()) },
                )
            }
            .pointerInput(Unit) {
                // 2 本指のピンチ(拡大・縮小と移動)と、拡大中の横の移動。縦のスクロールは一覧に任せる
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    panned[0] = false
                    var moved = 0f
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val viewW = size.width.toFloat()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2) {
                            val z = event.calculateZoom()
                            val pan = event.calculatePan()
                            val c = event.calculateCentroid()
                            if (z != 1f || pan != Offset.Zero) {
                                zoomAt(scale * z, c.x, c.y, viewW)
                                tx = clampTx(tx + pan.x, scale, viewW)
                                if (pan.y != 0f) scope.launch { list.scrollBy(-pan.y / scale) }
                                panned[0] = true
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } else if (fingers == 1 && scale > 1f) {
                            val dx = event.changes.firstOrNull { it.pressed }?.positionChange()?.x ?: 0f
                            if (dx != 0f) {
                                tx = clampTx(tx + dx, scale, viewW)
                                moved += kotlin.math.abs(dx)
                                if (moved > 16f) panned[0] = true
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        val viewWpx = constraints.maxWidth
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxWidth().height(maxHeight / scale).graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = tx
                transformOrigin = TransformOrigin(0f, 0f)
            },
            // 1ページだけのPDFは、画面の中央に置く(上下の余白をそろえて、中央に寄せる)
            contentPadding = if (single) PaddingValues(vertical = maxOf(top, bottom)) else PaddingValues(top = top, bottom = bottom),
            verticalArrangement = if (single) Arrangement.Center else Arrangement.spacedBy(8.dp),
        ) {
            items(doc.pageCount) { i -> PdfPage(doc, i, (viewWpx * scale).toInt(), dark) }
        }
        if (bars) {
            Text(
                "${list.firstVisibleItemIndex + 1} / ${doc.pageCount}" + if (scale > 1f) "  ×${"%.1f".format(scale)}" else "",
                color = Color.White, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 10.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }

    if (gotoDialog) {
        var text by remember { mutableStateOf("${list.firstVisibleItemIndex + 1}") }
        val n = text.toIntOrNull()
        AlertDialog(
            onDismissRequest = onGotoDismiss,
            title = { Text("ページ移動") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.filter(Char::isDigit).take(6) }, singleLine = true,
                    label = { Text("1 〜 ${doc.pageCount}") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = n != null && n in 1..doc.pageCount,
                    onClick = {
                        scope.launch { list.scrollToItem((n ?: 1) - 1) }
                        onGotoDismiss()
                    },
                ) { Text("移動") }
            },
            dismissButton = { TextButton(onClick = onGotoDismiss) { Text("キャンセル") } },
        )
    }
}

/** targetPx: 画面に出る見かけの幅(ピクセル。拡大中は大きくなる)。この幅に合わせて描く。 */
@Composable
private fun PdfPage(doc: PdfDoc, index: Int, targetPx: Int, dark: Boolean) {
    val (pw, ph) = doc.sizes[index]
    // 描く大きさは 256px 単位にそろえる(ズーム中に何度も描き直さない)
    val renderPx = (((minOf(targetPx, PDF_RENDER_MAX_PX) + 255) / 256) * 256).coerceIn(512, PDF_RENDER_MAX_PX)
    val key = "${doc.file.path}#$index@$renderPx"
    var shown by remember(index) { mutableStateOf(PdfCache.get(key)) }
    var failed by remember(index) { mutableStateOf(false) }
    LaunchedEffect(key) {
        val cached = PdfCache.get(key)
        if (cached != null) {
            shown = cached
            return@LaunchedEffect
        }
        val b = withContext(Dispatchers.IO) {
            try {
                doc.render(index, renderPx)
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
        }
        if (b != null) {
            PdfCache.put(key, b)
            shown = b
        } else if (shown == null) {
            failed = true
        }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(pw.toFloat() / ph).background(if (dark) Color(0xFF1A1A1A) else Color.White), contentAlignment = Alignment.Center) {
        val b = shown
        if (b != null) {
            Image(
                b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.Medium,
                colorFilter = if (dark) ColorFilter.colorMatrix(ColorMatrix(DarkFilter.MATRIX)) else null,
            )
        } else if (failed) {
            Text("このページを表示できません", color = Color.Gray, fontSize = 13.sp)
        } else {
            CircularProgressIndicator(Modifier.height(28.dp), color = Color.LightGray, strokeWidth = 2.dp)
        }
    }
}
