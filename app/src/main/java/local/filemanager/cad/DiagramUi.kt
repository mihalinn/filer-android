package local.filemanager

import android.graphics.Canvas as AndroidCanvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private sealed interface DrawioLoad {
    data object Busy : DrawioLoad
    data class Ok(val pages: List<Drawio.Page>) : DrawioLoad
    data class Fail(val message: String) : DrawioLoad
}

private const val DRAWIO_MAX_BYTES = 16 * 1024 * 1024

private fun loadDrawio(fs: Fs, item: FileItem): DrawioLoad = try {
    if (item.size > DRAWIO_MAX_BYTES) {
        DrawioLoad.Fail(Txt(R.string.diagram_ui_001, DRAWIO_MAX_BYTES / 1024 / 1024))
    } else {
        val bytes = fs.open(item.path).use { it.readBytes() }
        val text = TextDecode.decode(bytes).text
        val pages = Drawio.parse(text)
        if (pages == null) DrawioLoad.Fail(Txt(R.string.diagram_ui_002)) else DrawioLoad.Ok(pages)
    }
} catch (e: Exception) {
    DrawioLoad.Fail(e.message ?: Txt(R.string.diagram_ui_003))
} catch (e: OutOfMemoryError) {
    DrawioLoad.Fail(Txt(R.string.diagram_ui_004))
}

/** draw.io の図面の表示。ピンチで拡大・縮小、ドラッグで移動、ダブルタップで全体表示。ページが複数あれば下で切り替える。 */
@Composable
internal fun DrawioPane(fs: Fs, item: FileItem, bars: Boolean, onTap: () -> Unit, dark: Boolean = false) {
    val load = produceState<DrawioLoad>(DrawioLoad.Busy, item.path) {
        value = withContext(Dispatchers.IO) { loadDrawio(fs, item) }
    }.value
    when (load) {
        DrawioLoad.Busy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Pv.text) }
        is DrawioLoad.Fail -> Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }) {
            Text(load.message, color = Pv.text, modifier = Modifier.align(Alignment.Center).padding(32.dp))
        }
        is DrawioLoad.Ok -> {
            var page by remember { mutableIntStateOf(0) }
            val p = load.pages[page.coerceIn(0, load.pages.lastIndex)]
            Box(Modifier.fillMaxSize()) {
                DiagramCanvas(p, onTap, dark)
                if (bars && load.pages.size > 1) {
                    Row(
                        Modifier.align(Alignment.BottomStart).navigationBarsPadding().background(Pv.bar).horizontalScroll(rememberScrollState()),
                    ) {
                        load.pages.forEachIndexed { i, pg ->
                            TextButton(onClick = { page = i }) {
                                Text(pg.name, color = if (i == page) Pv.accent else Pv.barText, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagramCanvas(page: Drawio.Page, onTap: () -> Unit, dark: Boolean) {
    // 暗いときは、描いた絵を「反転 + 色相を半回転」で暗くする(白 → 暗い灰色、黒 → 明るい灰色)
    val darkPaint = remember { android.graphics.Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(DarkFilter.MATRIX)) } }
    var scale by remember(page) { mutableFloatStateOf(1f) }
    var ox by remember(page) { mutableFloatStateOf(0f) }
    var oy by remember(page) { mutableFloatStateOf(0f) }
    val painter = remember(page) { DiagramPainter(page) }
    var viewW by remember { mutableFloatStateOf(0f) }
    var viewH by remember { mutableFloatStateOf(0f) }

    fun fit() {
        if (viewW <= 0f || viewH <= 0f) return
        val pw = max(page.maxX - page.minX, 1f)
        val ph = max(page.maxY - page.minY, 1f)
        val s = min((viewW - 48f) / pw, (viewH - 140f) / ph).coerceIn(0.02f, 2f)
        scale = s
        ox = (viewW - pw * s) / 2f - page.minX * s
        oy = (viewH - ph * s) / 2f - page.minY * s + 20f
    }

    Box(
        Modifier.fillMaxSize().background(if (dark) Color(0xFF1A1A1A) else Color.White).clipToBounds()
            .onSizeChanged { viewW = it.width.toFloat(); viewH = it.height.toFloat() }
            .pointerInput(page) {
                detectTransformGestures { c, pan, zoom, _ ->
                    val ns = (scale * zoom).coerceIn(0.02f, 10f)
                    ox = c.x + (ox - c.x) * (ns / scale) + pan.x
                    oy = c.y + (oy - c.y) * (ns / scale) + pan.y
                    scale = ns
                }
            }
            .pointerInput(page) {
                detectTapGestures(onTap = { onTap() }, onDoubleTap = { fit() })
            },
    ) {
        // 開いたとき・向きが変わったときに、全体が入る大きさにする
        LaunchedEffect(page, viewW, viewH) { fit() }
        Canvas(Modifier.fillMaxSize()) {
            val s = scale
            val x0 = ox
            val y0 = oy
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                if (dark) {
                    nc.saveLayer(null, darkPaint)
                    painter.draw(nc, s, x0, y0, size.width, size.height)
                    nc.restore()
                } else {
                    painter.draw(nc, s, x0, y0, size.width, size.height)
                }
            }
        }
    }
}

/** 図面を Android の Canvas に描く。文字の折り返しは図形ごとに 1 度だけ計算して覚える。 */
private class DiagramPainter(val page: Drawio.Page) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val bgPaint = Paint().apply { style = Paint.Style.FILL; color = 0xDDFFFFFF.toInt() }
    private val path = Path()
    private val rect = RectF()
    private val layouts = HashMap<Int, StaticLayout?>()
    private val bounds: Array<FloatArray> = Array(page.items.size) { i -> boundsOf(page.items[i]) }

    private fun boundsOf(it: Drawio.Item): FloatArray = when (it) {
        is Drawio.Shape -> floatArrayOf(it.x - 4, it.y - 4, it.x + it.w + 4, it.y + it.h + 4)
        is Drawio.Line -> {
            var a = Float.MAX_VALUE; var b = Float.MAX_VALUE; var c = -Float.MAX_VALUE; var d = -Float.MAX_VALUE
            for (p in it.points) { a = min(a, p.x); b = min(b, p.y); c = max(c, p.x); d = max(d, p.y) }
            floatArrayOf(a - 12, b - 12, c + 12, d + 12)
        }
        is Drawio.Label -> floatArrayOf(it.x - 300, it.y - 100, it.x + 300, it.y + 100)
    }

    fun draw(nc: AndroidCanvas, scale: Float, ox: Float, oy: Float, w: Float, h: Float) {
        nc.save()
        nc.translate(ox, oy)
        nc.scale(scale, scale)
        val vx0 = -ox / scale
        val vy0 = -oy / scale
        val vx1 = (w - ox) / scale
        val vy1 = (h - oy) / scale
        for ((i, item) in page.items.withIndex()) {
            val b = bounds[i]
            if (b[2] < vx0 || b[0] > vx1 || b[3] < vy0 || b[1] > vy1) continue
            when (item) {
                is Drawio.Shape -> drawShape(nc, i, item)
                is Drawio.Line -> drawLine(nc, item)
                is Drawio.Label -> drawLabel(nc, i, item)
            }
        }
        nc.restore()
    }

    private fun withAlpha(color: Int, a: Float): Int {
        val alpha = ((color ushr 24) * a).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (color and 0xFFFFFF)
    }

    private fun drawShape(nc: AndroidCanvas, index: Int, s: Drawio.Shape) {
        val x = s.x
        val y = s.y
        val w = s.w
        val h = s.h
        rect.set(x, y, x + w, y + h)
        var fill = s.fill
        var stroke = s.stroke
        if (s.kind == "image" || s.kind == "stencil") {
            fill = fill ?: 0xFFF2F4F5.toInt()
            stroke = stroke ?: 0xFF9AA5AB.toInt()
        }
        strokePaint.strokeWidth = s.strokeWidth
        strokePaint.pathEffect = if (s.dashed || s.kind == "image" || s.kind == "stencil") DashPathEffect(floatArrayOf(6f * s.strokeWidth.coerceAtLeast(1f), 3f * s.strokeWidth.coerceAtLeast(1f)), 0f) else null
        path.reset()
        when (s.kind) {
            "ellipse", "cloud" -> path.addOval(rect, Path.Direction.CW)
            "rhombus" -> {
                path.moveTo(x + w / 2, y); path.lineTo(x + w, y + h / 2); path.lineTo(x + w / 2, y + h); path.lineTo(x, y + h / 2); path.close()
            }
            "triangle" -> {
                path.moveTo(x, y); path.lineTo(x + w, y + h / 2); path.lineTo(x, y + h); path.close()
            }
            "hexagon" -> {
                val d = w * 0.25f
                path.moveTo(x + d, y); path.lineTo(x + w - d, y); path.lineTo(x + w, y + h / 2)
                path.lineTo(x + w - d, y + h); path.lineTo(x + d, y + h); path.lineTo(x, y + h / 2); path.close()
            }
            "cylinder" -> {
                val e = min(h * 0.18f, w * 0.5f)
                rect.set(x, y, x + w, y + 2 * e)
                path.addArc(rect, 180f, 180f)
                path.lineTo(x + w, y + h - e)
                rect.set(x, y + h - 2 * e, x + w, y + h)
                path.arcTo(rect, 0f, 180f)
                path.close()
            }
            else -> {
                if (s.rounded || s.kind == "image" || s.kind == "stencil") {
                    val r = min(w, h) * 0.15f
                    path.addRoundRect(rect, r, r, Path.Direction.CW)
                } else {
                    path.addRect(rect, Path.Direction.CW)
                }
            }
        }
        if (fill != null) {
            fillPaint.color = withAlpha(fill, s.opacity)
            nc.drawPath(path, fillPaint)
        }
        if (s.kind == "swimlane" && s.header > 0f) {
            // 見出し部分
            fillPaint.color = withAlpha(s.fill ?: 0xFFFFFFFF.toInt(), s.opacity)
            if (s.headerVertical) nc.drawRect(x, y, x + min(s.header, w), y + h, fillPaint) else nc.drawRect(x, y, x + w, y + min(s.header, h), fillPaint)
        }
        if (stroke != null) {
            strokePaint.color = withAlpha(stroke, s.opacity)
            nc.drawPath(path, strokePaint)
            if (s.kind == "swimlane" && s.header > 0f) {
                if (s.headerVertical) nc.drawLine(x + s.header, y, x + s.header, y + h, strokePaint)
                else nc.drawLine(x, y + s.header, x + w, y + s.header, strokePaint)
            }
        }
        strokePaint.pathEffect = null

        var text = s.label
        var color = s.fontColor
        var italic = s.italic
        if (text.isEmpty() && s.note != null) {
            text = s.note
            color = 0xFF7A868C.toInt()
            italic = true
        }
        if (text.isEmpty()) return
        // 文字を置く枠(swimlane は見出しの中)
        var bx = x
        var by = y
        var bw = w
        var bh = h
        if (s.kind == "swimlane" && s.header > 0f) {
            if (s.headerVertical) bw = min(s.header, w) else bh = min(s.header, h)
        }
        val layout = layouts.getOrPut(index) { makeLayout(text, s, color, italic, if (s.headerVertical && s.kind == "swimlane") bh else bw) } ?: return
        nc.save()
        if (s.kind == "swimlane" && s.headerVertical) {
            // 縦の見出し: 左端の帯の中で、文字を 90 度回して置く
            nc.translate(bx + bw / 2, by + bh / 2)
            nc.rotate(-90f)
            nc.translate(-layout.width / 2f, -layout.height / 2f)
        } else {
            val tx = when (s.align) {
                -1 -> bx + 4
                1 -> bx + bw - layout.width - 4
                else -> bx + (bw - layout.width) / 2
            }
            val ty = when (s.valign) {
                -1 -> by + 2
                1 -> by + bh - layout.height - 2
                else -> by + (bh - layout.height) / 2
            }
            nc.translate(tx, ty)
        }
        layout.draw(nc)
        nc.restore()
    }

    private fun makeLayout(text: String, s: Drawio.Shape, color: Int, italic: Boolean, boxWidth: Float): StaticLayout? {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = s.fontSize
            typeface = Typeface.create(Typeface.DEFAULT, if (s.bold && italic) Typeface.BOLD_ITALIC else if (s.bold) Typeface.BOLD else if (italic) Typeface.ITALIC else Typeface.NORMAL)
            isUnderlineText = s.underline
        }
        val natural = text.split('\n').maxOfOrNull { Layout.getDesiredWidth(it, tp) } ?: 0f
        val width = if (s.wrap) max(boxWidth - 8f, 8f) else natural + 2f
        val align = when (s.align) {
            -1 -> Layout.Alignment.ALIGN_NORMAL
            1 -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_CENTER
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, tp, width.toInt().coerceAtLeast(1)).setAlignment(align).build()
    }

    private fun drawLine(nc: AndroidCanvas, l: Drawio.Line) {
        val pts = l.points
        if (pts.size < 2) return
        strokePaint.strokeWidth = l.strokeWidth
        strokePaint.color = l.stroke
        val sw = l.strokeWidth.coerceAtLeast(1f)
        strokePaint.pathEffect = if (l.dashed) DashPathEffect(floatArrayOf(6f * sw, 3f * sw), 0f) else null
        path.reset()
        path.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
        nc.drawPath(path, strokePaint)
        strokePaint.pathEffect = null
        arrow(nc, l.endArrow, l.endFill, pts[pts.size - 1], pts[pts.size - 2], l)
        arrow(nc, l.startArrow, l.startFill, pts[0], pts[1], l)
    }

    /** tip: 矢印の先、from: 線のもう一方(向きを決める)。 */
    private fun arrow(nc: AndroidCanvas, type: String, filled: Boolean, tip: Drawio.Pt, from: Drawio.Pt, l: Drawio.Line) {
        if (type == "none") return
        val sw = l.strokeWidth.coerceAtLeast(1f)
        val len = 8f + 2f * sw
        val half = 4f + 1f * sw
        val ang = atan2(from.y - tip.y, from.x - tip.x)
        val bx = tip.x + cos(ang) * len
        val by = tip.y + sin(ang) * len
        val nx = -sin(ang) * half
        val ny = cos(ang) * half
        fillPaint.color = if (filled) l.stroke else 0xFFFFFFFF.toInt()
        path.reset()
        when {
            type.startsWith("oval") -> {
                val cx = tip.x + cos(ang) * len / 2
                val cy = tip.y + sin(ang) * len / 2
                rect.set(cx - len / 2, cy - len / 2, cx + len / 2, cy + len / 2)
                path.addOval(rect, Path.Direction.CW)
            }
            type.startsWith("diamond") -> {
                path.moveTo(tip.x, tip.y); path.lineTo((tip.x + bx) / 2 + nx, (tip.y + by) / 2 + ny)
                path.lineTo(tip.x + cos(ang) * len * 1.4f, tip.y + sin(ang) * len * 1.4f); path.lineTo((tip.x + bx) / 2 - nx, (tip.y + by) / 2 - ny); path.close()
            }
            type.startsWith("open") -> {
                path.moveTo(bx + nx, by + ny); path.lineTo(tip.x, tip.y); path.lineTo(bx - nx, by - ny)
                strokePaint.color = l.stroke
                nc.drawPath(path, strokePaint)
                return
            }
            else -> {
                path.moveTo(tip.x, tip.y); path.lineTo(bx + nx, by + ny); path.lineTo(bx - nx, by - ny); path.close()
            }
        }
        nc.drawPath(path, fillPaint)
        strokePaint.color = l.stroke
        nc.drawPath(path, strokePaint)
    }

    private fun drawLabel(nc: AndroidCanvas, index: Int, l: Drawio.Label) {
        val layout = layouts.getOrPut(index) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = l.fontColor
                textSize = l.fontSize
                typeface = Typeface.create(Typeface.DEFAULT, if (l.bold && l.italic) Typeface.BOLD_ITALIC else if (l.bold) Typeface.BOLD else if (l.italic) Typeface.ITALIC else Typeface.NORMAL)
            }
            val width = (l.text.split('\n').maxOfOrNull { Layout.getDesiredWidth(it, tp) } ?: 0f) + 2f
            StaticLayout.Builder.obtain(l.text, 0, l.text.length, tp, width.toInt().coerceAtLeast(1)).setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        } ?: return
        val x = l.x - layout.width / 2f
        val y = l.y - layout.height / 2f
        nc.drawRect(x - 2, y - 1, x + layout.width + 2, y + layout.height + 1, bgPaint)
        nc.save()
        nc.translate(x, y)
        layout.draw(nc)
        nc.restore()
    }
}
