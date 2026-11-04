package local.filemanager

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ---------------------------------------------------------------- PDF

private sealed interface PdfLoad {
    data object Busy : PdfLoad
    data class Ok(val doc: PdfiumDoc) : PdfLoad
    data class Fail(val message: String) : PdfLoad
}

private const val PDF_REMOTE_LIMIT = 300L * 1024 * 1024
private const val PDF_RENDER_MAX_PX = 2400
/** 拡大したとき、見えている範囲だけを細かく描く(画面のピクセルの数に合わせる)。この倍率を超えたら描く */
private const val PDF_DETAIL_FROM = 1.15f
private const val PDF_DETAIL_MAX_PIXELS = 7_000_000f

private object PdfCache {
    private val cache = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun get(key: String): Bitmap? = synchronized(cache) { cache.get(key) }
    fun put(key: String, b: Bitmap) = synchronized(cache) { cache.put(key, b) }
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
        PdfLoad.Ok(PdfiumDoc(ctx, file))
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
internal fun PdfPane(fs: Fs, item: FileItem, bars: Boolean, gotoDialog: Boolean, onGotoDismiss: () -> Unit, onTap: () -> Unit, dark: Boolean = false, copy: CopyHolder? = null) {
    val ctx = LocalContext.current
    val copyScope = rememberCoroutineScope()
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
            DisposableEffect(load.doc, copy) {
                copy?.setAction("全ページの文字をコピー") { pdfCopy(ctx, copyScope, load.doc, null) }
                onDispose { copy?.action = null }
            }
            PdfPages(load.doc, bars, gotoDialog, onGotoDismiss, onTap, dark) { page -> pdfCopy(ctx, copyScope, load.doc, page) }
        }
    }
}

@Composable
private fun PdfPages(doc: PdfiumDoc, bars: Boolean, gotoDialog: Boolean, onGotoDismiss: () -> Unit, onTap: () -> Unit, dark: Boolean, onCopy: (Int?) -> Unit) {
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
    // 文字を選んでいるページ(選択は、同時に 1 ページだけ)。ほかの所を短くタップすると外れる
    var activePage by remember { mutableStateOf<Int?>(null) }
    val activeNow = rememberUpdatedState(activePage)
    var selText by remember { mutableStateOf<String?>(null) }
    val selDrag = remember { BooleanArray(1) } // 選択のつまみを動かしている間は、ページを横に動かさない
    var viewport by remember { mutableStateOf(Rect.Zero) }
    val ctx = LocalContext.current

    /** ページから選択の変化を受ける。text が null なら、そのページの選択が無くなった。 */
    fun setSel(page: Int, text: String?) {
        if (text != null) {
            activePage = page
            selText = text
        } else if (activeNow.value == page) {
            activePage = null
            selText = null
        }
    }

    fun clampTx(t: Float, s: Float, viewW: Float) = t.coerceIn(viewW * (1f - s), 0f)

    /** (cx, cy) の位置にある中身が動かないように、倍率を newScale に変える。 */
    fun zoomAt(newScale: Float, cx: Float, cy: Float, viewW: Float) {
        val s0 = scale
        var s1 = newScale.coerceIn(1f, 5f)
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
            .onGloballyPositioned { viewport = Rect(it.positionInRoot(), it.size.toSize()) }
            .pointerInput(Unit) {
                detectTapGestures(
                    // 長押し・移動のあとに指を離したときもタップとして届くので、そのときは何もしない(選んだ直後に外れないように)
                    onTap = {
                        if (!panned[0]) {
                            if (activeNow.value != null) { activePage = null; selText = null } else onTap()
                        }
                    },
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
                        } else if (fingers == 1 && scale > 1f && !selDrag[0]) {
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
            items(doc.pageCount) { i -> PdfPage(doc, i, viewWpx, scale, viewport, dark, panned, selDrag, onCopy, activePage, ::setSel) }
        }
        val st = selText
        if (st != null) {
            // 選んだ文字のコピーと解除(拡大しても大きさが変わらないよう、拡大の外に置く)
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 10.dp)
                    .background(Color(0xEE202020), RoundedCornerShape(8.dp)),
            ) {
                Text(
                    "コピー", color = Color.White, fontSize = 15.sp,
                    modifier = Modifier.clickable { copyToClipboard(ctx, st); activePage = null; selText = null }.padding(horizontal = 20.dp, vertical = 10.dp),
                )
                Text(
                    "解除", color = Color(0xFFBBBBBB), fontSize = 15.sp,
                    modifier = Modifier.clickable { activePage = null; selText = null }.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        } else if (bars) {
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

/** 拡大したときに細かく描く範囲(ページの 0〜1 の割合)と、ページ全体を何ピクセルの幅にしたときの細かさか。 */
private data class PdfDetailReq(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val fullW: Int)

/** 画面に見えている範囲(少し余分を付ける)を、PdfDetailReq にする。見えていなければ null。 */
private fun pdfDetailFor(c: LayoutCoordinates, vp: Rect, scale: Float): PdfDetailReq? {
    val p = c.positionInRoot()
    val w = c.size.width * scale
    val h = c.size.height * scale
    if (w < 1f || h < 1f || vp.width < 1f) return null
    val mx = vp.width * 0.2f
    val my = vp.height * 0.2f
    val ix0 = max(p.x, vp.left - mx)
    val iy0 = max(p.y, vp.top - my)
    val ix1 = min(p.x + w, vp.right + mx)
    val iy1 = min(p.y + h, vp.bottom + my)
    if (ix1 <= ix0 || iy1 <= iy0) return null
    // 動くたびに描き直さないよう、範囲は 1/32 単位にそろえる
    val x0 = (floor((ix0 - p.x) / w * 32f) / 32f).coerceIn(0f, 1f)
    val y0 = (floor((iy0 - p.y) / h * 32f) / 32f).coerceIn(0f, 1f)
    val x1 = (ceil((ix1 - p.x) / w * 32f) / 32f).coerceIn(0f, 1f)
    val y1 = (ceil((iy1 - p.y) / h * 32f) / 32f).coerceIn(0f, 1f)
    var fullW = ((w / 64f).roundToInt() * 64).coerceAtLeast(64)
    val px = (x1 - x0) * fullW * ((y1 - y0) * fullW * h / w)
    if (px > PDF_DETAIL_MAX_PIXELS) fullW = ((fullW * sqrt(PDF_DETAIL_MAX_PIXELS / px) / 64f).toInt() * 64).coerceAtLeast(64)
    return PdfDetailReq(x0, y0, x1, y1, fullW)
}

/**
 * 1 ページ分。viewWpx: 画面の幅(ピクセル、拡大前)。scale: いまの拡大。
 * 全体は画面の幅で描き、拡大したときは、見えている範囲だけを画面のピクセルに合わせて細かく描き足す。
 */
@Composable
private fun PdfPage(
    doc: PdfiumDoc, index: Int, viewWpx: Int, scale: Float, viewport: Rect, dark: Boolean, panned: BooleanArray, selDrag: BooleanArray,
    onCopy: (Int?) -> Unit, activePage: Int?, onSel: (Int, String?) -> Unit,
) {
    // 文字の上を長押しすると、その単語が選ばれる(つまみで範囲を変えて、「コピー」)。文字の無い所を長押しすると、ページ・全ページの文字をコピーするメニュー。
    // 触れた操作は消費しないので、拡大・移動は今まで通り
    var menu by remember(index) { mutableStateOf(false) }
    val pageScope = rememberCoroutineScope()
    var lay by remember(index) { mutableStateOf<PdfPageText?>(null) }
    var sel by remember(index) { mutableStateOf<IntRange?>(null) }
    var boxW by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(activePage) { if (activePage != index) sel = null }
    LaunchedEffect(sel, lay) {
        val l = lay
        val r = sel
        onSel(index, if (l != null && r != null && !r.isEmpty()) l.textOf(r) else null)
    }
    DisposableEffect(index) { onDispose { onSel(index, null) } }
    val (pw, ph) = doc.sizes[index]
    // 描く大きさは 256px 単位にそろえる。縦に長すぎるページは、絵が大きくなりすぎないよう幅を縮める
    val maxW = (8000L * pw / ph).toInt().coerceAtLeast(64)
    val renderPx = minOf((((minOf(viewWpx, PDF_RENDER_MAX_PX) + 255) / 256) * 256).coerceIn(512, PDF_RENDER_MAX_PX), maxW)
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
    // 拡大中は、見えている範囲を細かく描く。拡大や移動は描画だけで行うので、位置は一定の間隔で見に行く
    val coordsRef = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val scaleNow by rememberUpdatedState(scale)
    val viewportNow by rememberUpdatedState(viewport)
    var detailReq by remember(index) { mutableStateOf<PdfDetailReq?>(null) }
    var detail by remember(index) { mutableStateOf<PdfRegion?>(null) }
    LaunchedEffect(index, scale > PDF_DETAIL_FROM) {
        if (scale <= PDF_DETAIL_FROM) {
            detailReq = null
            return@LaunchedEffect
        }
        while (true) {
            val c = coordsRef[0]
            detailReq = if (c != null && c.isAttached) pdfDetailFor(c, viewportNow, scaleNow) else null
            delay(150)
        }
    }
    LaunchedEffect(detailReq) {
        val r = detailReq
        if (r == null) {
            detail = null
            return@LaunchedEffect
        }
        delay(100)
        val reg = withContext(Dispatchers.IO) {
            try {
                doc.renderRegion(index, r.fullW, r.x0, r.y0, r.x1, r.y1)
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
        }
        if (reg != null) detail = reg
    }
    Box(
        Modifier.fillMaxWidth().aspectRatio(pw.toFloat() / ph).background(if (dark) Color(0xFF1A1A1A) else Color.White)
            .onSizeChanged { boxW = it.width.toFloat() }
            .onGloballyPositioned { coordsRef[0] = it }
            .onLongPressAt { pos ->
                panned[0] = true  // 離したときに、上下のバーが出入りしないように
                pageScope.launch {
                    val lt = lay ?: withContext(Dispatchers.IO) { runCatching { doc.layout(index) }.getOrNull() }?.also { lay = it }
                    val k = boxW / pw
                    val i = lt?.hit(pos.x / k, pos.y / k) ?: -1
                    val cur = sel
                    if (lt != null && i >= 0) {
                        if (cur == null || i !in cur) sel = lt.wordAround(i)
                    } else {
                        menu = true
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (menu) {
            ListMenu(
                expanded = true, dark = Pv.dark, onDismiss = { menu = false },
                items = listOf(
                    VMenuItem.Action(Icons.Default.ContentCopy, "このページの文字をコピー") { onCopy(index) },
                    VMenuItem.Action(Icons.Default.ContentCopy, "全ページの文字をコピー") { onCopy(null) },
                ),
            )
        }
        val b = shown
        if (b != null) {
            val filter = if (dark) ColorFilter.colorMatrix(ColorMatrix(DarkFilter.MATRIX)) else null
            Image(
                b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.Medium,
                colorFilter = filter,
            )
            val d = detail
            if (d != null && scale > PDF_DETAIL_FROM) {
                val img = remember(d) { d.bmp.asImageBitmap() }
                Canvas(Modifier.fillMaxSize()) {
                    val dx = (d.x0 * size.width).roundToInt()
                    val dy = (d.y0 * size.height).roundToInt()
                    drawImage(
                        img, IntOffset.Zero, IntSize(img.width, img.height),
                        IntOffset(dx, dy), IntSize((d.x1 * size.width).roundToInt() - dx, (d.y1 * size.height).roundToInt() - dy),
                        filterQuality = FilterQuality.Medium, colorFilter = filter,
                    )
                }
            }
        } else if (failed) {
            Text("このページを表示できません", color = Color.Gray, fontSize = 13.sp)
        } else {
            CircularProgressIndicator(Modifier.height(28.dp), color = Color.LightGray, strokeWidth = 2.dp)
        }
        val lt = lay
        val range = sel
        if (lt != null && range != null && !range.isEmpty() && boxW > 1f) {
            PdfSelectionOverlay(lt, range, boxW / pw, scale, selDrag, onRange = { sel = it })
        }
    }
}

/** PDF の文字をコピーする。page は 0 から数えたページ(null なら全ページ)。取り出しは裏で行う。 */
private fun pdfCopy(ctx: Context, scope: kotlinx.coroutines.CoroutineScope, doc: PdfiumDoc, page: Int?) {
    android.widget.Toast.makeText(ctx, "文字を取り出しています", android.widget.Toast.LENGTH_SHORT).show()
    scope.launch {
        // 上限より 1 文字多く取り出して、超えたときは copyToClipboard が「先頭だけコピーした」と知らせる
        val r = withContext(Dispatchers.IO) { runCatching { doc.extractText(page?.let { it..it }, CopyText.MAX_CHARS + 1) } }
        r.onSuccess { res ->
            if (res.hasText) copyToClipboard(ctx, res.text)
            else android.widget.Toast.makeText(ctx, "文字がありません(スキャンした画像の PDF など)", android.widget.Toast.LENGTH_LONG).show()
        }.onFailure { e ->
            android.widget.Toast.makeText(ctx, e.message ?: "文字を取り出せませんでした", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}