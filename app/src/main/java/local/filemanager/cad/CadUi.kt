package local.filemanager

import android.graphics.Paint
import android.graphics.Path
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

private sealed interface CadLoad {
    data object Busy : CadLoad
    data class Ok(val drawing: CadDrawing) : CadLoad
    data class Fail(val message: String) : CadLoad
}

private fun loadCad(fs: Fs, item: FileItem): CadLoad = try {
    val ext = FileOps.extensionOf(item.name)
    if (ext == "dwg") {
        val ver = fs.open(item.path).use { s -> val b = ByteArray(6); val n = s.read(b); if (n == 6) String(b, Charsets.ISO_8859_1) else "" }
        throw OpException(Txt(R.string.cad_ui_001) + (dwgVersion(ver)?.let { Txt(R.string.cad_ui_002, it) } ?: "") + Txt(R.string.cad_ui_003))
    }
    val limit = if (ext == "dxf") Dxf.MAX_BYTES else Jww.MAX_BYTES
    if (item.size > limit) throw OpException(Txt(R.string.cad_ui_004, limit / 1024 / 1024))
    val bytes = fs.open(item.path).use { it.readBytes() }
    val d = when (ext) {
        "dxf" -> Dxf.parse(bytes)
        "jww" -> Jww.parse(bytes)
        "jwc" -> Jwc.parse(bytes)
        else -> throw OpException(Txt(R.string.cad_ui_005))
    }
    CadLoad.Ok(d)
} catch (e: OpException) {
    CadLoad.Fail(e.message ?: Txt(R.string.cad_ui_006))
} catch (e: Exception) {
    CadLoad.Fail(e.message ?: Txt(R.string.cad_ui_007))
} catch (e: OutOfMemoryError) {
    CadLoad.Fail(Txt(R.string.cad_ui_008))
}

/** DWG の先頭 6 文字(AC10xx)から、AutoCAD の版を示す */
internal fun dwgVersion(code: String): String? = when (code) {
    "AC1006" -> "R10"
    "AC1009" -> Txt(R.string.cad_ui_009)
    "AC1012" -> "R13"
    "AC1014" -> "R14"
    "AC1015" -> "AutoCAD 2000"
    "AC1018" -> "AutoCAD 2004"
    "AC1021" -> "AutoCAD 2007"
    "AC1024" -> "AutoCAD 2010"
    "AC1027" -> "AutoCAD 2013"
    "AC1032" -> "AutoCAD 2018"
    else -> if (code.startsWith("AC")) code else null
}

/**
 * 2D 図面(DXF・JW_CAD)の表示。ピンチで拡大・縮小、ドラッグで移動、ダブルタップで全体表示。
 * layersDialog が true のあいだ、レイヤー(層)の表示切り替えを出す。
 */
@Composable
internal fun CadPane(fs: Fs, item: FileItem, bars: Boolean, layersDialog: Boolean, onLayersClose: () -> Unit, onTap: () -> Unit) {
    val load = produceState<CadLoad>(CadLoad.Busy, item.path) {
        value = withContext(Dispatchers.IO) { loadCad(fs, item) }
    }.value
    when (load) {
        CadLoad.Busy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Pv.text) }
        is CadLoad.Fail -> Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }) {
            Text(load.message, color = Pv.text, modifier = Modifier.align(Alignment.Center).padding(32.dp))
        }
        is CadLoad.Ok -> CadView(load.drawing, bars, layersDialog, onLayersClose, onTap)
    }
}

@Composable
private fun CadView(d: CadDrawing, bars: Boolean, layersDialog: Boolean, onLayersClose: () -> Unit, onTap: () -> Unit) {
    val dark = Pv.dark
    val visible = remember(d) { mutableStateListOf<Boolean>().also { l -> d.layers.forEach { l.add(it.visible) } } }
    val painter = remember(d, dark) { CadPainter(d, dark) }
    var scale by remember(d) { mutableFloatStateOf(1f) }
    var ox by remember(d) { mutableFloatStateOf(0f) }
    var oy by remember(d) { mutableFloatStateOf(0f) }
    var viewW by remember { mutableFloatStateOf(0f) }
    var viewH by remember { mutableFloatStateOf(0f) }
    var fitScale by remember(d) { mutableFloatStateOf(1f) }
    val density = LocalDensity.current.density

    fun fit() {
        if (viewW <= 0f || viewH <= 0f) return
        val w = max(d.fitMaxX - d.fitMinX, 1e-6f)
        val h = max(d.fitMaxY - d.fitMinY, 1e-6f)
        val s = min((viewW - 48f) / w, (viewH - 160f) / h).coerceAtLeast(1e-9f)
        scale = s
        fitScale = s
        val cx = (d.fitMinX + d.fitMaxX) / 2
        val cy = (d.fitMinY + d.fitMaxY) / 2
        ox = viewW / 2 - s * cx
        oy = viewH / 2 + s * cy
    }

    Box(
        Modifier.fillMaxSize().background(if (dark) Color(0xFF14181C) else Color(0xFFFAFAFA)).clipToBounds()
            .onSizeChanged { viewW = it.width.toFloat(); viewH = it.height.toFloat() }
            .pointerInput(d) {
                detectTransformGestures { c, pan, zoom, _ ->
                    val ns = (scale * zoom).coerceIn(fitScale * 0.2f, fitScale * 2000f)
                    ox = c.x + (ox - c.x) * (ns / scale) + pan.x
                    oy = c.y + (oy - c.y) * (ns / scale) + pan.y
                    scale = ns
                }
            }
            .pointerInput(d) { detectTapGestures(onTap = { onTap() }, onDoubleTap = { fit() }) },
    ) {
        LaunchedEffect(d, viewW, viewH) { fit() }
        Canvas(Modifier.fillMaxSize()) {
            val s = scale
            val x0 = ox
            val y0 = oy
            val vis = visible.toBooleanArray()
            drawIntoCanvas { c -> painter.draw(c.nativeCanvas, s, x0, y0, size.width, size.height, vis, density) }
        }
        if (bars) {
            val shown = visible.count { it }
            val used = d.layers.count { it.count > 0 }
            Text(
                Txt(R.string.cad_ui_010, d.shapeCount, used) + (if (shown < visible.size) Txt(R.string.cad_ui_011, d.layers.indices.count { visible[it] && d.layers[it].count > 0 }) else "") +
                    (if (d.note.isNotEmpty()) Txt(R.string.cad_ui_012, d.note) else ""),
                color = Color(0xFFCCCCCC), fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
    if (layersDialog) {
        val shown = d.layers.indices.filter { d.layers[it].count > 0 }.sortedBy { d.layers[it].name }
        AlertDialog(
            onDismissRequest = onLayersClose,
            title = { Text(Txt(R.string.cad_ui_013, shown.size)) },
            text = {
                LazyColumn {
                    items(shown) { i ->
                        Row(Modifier.fillMaxWidth().clickable { visible[i] = !visible[i] }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = visible[i], onCheckedChange = { visible[i] = it })
                            Text("${d.layers[i].name}  (${d.layers[i].count})", fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onLayersClose) { Text(Txt(R.string.cad_ui_014)) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { shown.forEach { visible[it] = true } }) { Text(Txt(R.string.cad_ui_015)) }
                    TextButton(onClick = { shown.forEach { visible[it] = false } }) { Text(Txt(R.string.cad_ui_016)) }
                }
            },
        )
    }
}

/** 図面を Android の Canvas に描く。線の束ごとの Path は最初に 1 度だけ作る。 */
private class CadPainter(val d: CadDrawing, val dark: Boolean) {
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaths: Array<Path> by lazy {
        Array(d.strokes.size) { k ->
            val g = d.strokes[k]
            val p = Path()
            var o = 0
            for (i in g.lens.indices) {
                val n = g.lens[i]
                p.moveTo(g.xy[o * 2], g.xy[o * 2 + 1])
                for (j in 1 until n) p.lineTo(g.xy[(o + j) * 2], g.xy[(o + j) * 2 + 1])
                if (g.closed[i]) p.close()
                o += n
            }
            p
        }
    }
    private val fillPaths: Array<Path> by lazy {
        Array(d.fills.size) { k ->
            val f = d.fills[k]
            val p = Path().apply { fillType = Path.FillType.EVEN_ODD }
            var o = 0
            for (n in f.lens) {
                p.moveTo(f.xy[o * 2], f.xy[o * 2 + 1])
                for (j in 1 until n) p.lineTo(f.xy[(o + j) * 2], f.xy[(o + j) * 2 + 1])
                p.close()
                o += n
            }
            p
        }
    }
    private val colors = HashMap<Int, Int>()
    private fun col(c: Int) = colors.getOrPut(c) { CadColor.adapt(c, dark) }

    fun draw(nc: android.graphics.Canvas, scale: Float, ox: Float, oy: Float, w: Float, h: Float, vis: BooleanArray, density: Float) {
        nc.save()
        nc.translate(ox, oy)
        nc.scale(scale, -scale)
        fillPaint.style = Paint.Style.FILL
        for (k in d.fills.indices) {
            val f = d.fills[k]
            if (!vis.getOrElse(f.layer) { true }) continue
            fillPaint.color = col(f.color)
            nc.drawPath(fillPaths[k], fillPaint)
        }
        strokePaint.strokeWidth = 1.2f * density / scale
        for (k in d.strokes.indices) {
            val g = d.strokes[k]
            if (!vis.getOrElse(g.layer) { true }) continue
            strokePaint.color = col(g.color)
            nc.drawPath(strokePaths[k], strokePaint)
        }
        nc.restore()
        // 文字は、拡大率に合わせた大きさで、画面の座標に直接描く(小さすぎるものは省く)
        for (t in d.texts) {
            if (!vis.getOrElse(t.layer) { true }) continue
            val px = t.height * scale
            if (px < 4f || px > 2000f) continue
            val sx = ox + scale * t.x
            val sy = oy - scale * t.y
            if (sx < -2000f || sy < -2000f || sx > w + 2000f || sy > h + 2000f) continue
            textPaint.color = col(t.color)
            textPaint.textSize = px
            textPaint.textScaleX = t.widthFactor.coerceIn(0.2f, 5f)
            textPaint.textAlign = when (t.hAlign) { 1 -> Paint.Align.CENTER; 2 -> Paint.Align.RIGHT; else -> Paint.Align.LEFT }
            val lines = t.text.split('\n')
            val lh = px * 1.25f
            val first = when (t.vAlign) {
                1 -> -0.2f * px - (lines.size - 1) * lh
                2 -> -lines.size * lh / 2 + 0.85f * px
                3 -> 0.85f * px
                else -> 0f
            }
            nc.save()
            nc.translate(sx, sy)
            nc.rotate(-t.rotation)
            for ((i, line) in lines.withIndex()) nc.drawText(line, 0f, first + i * lh, textPaint)
            nc.restore()
        }
    }
}
