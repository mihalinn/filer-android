package local.filemanager

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/** 2D 図面(DXF・JW_CAD)の描画データ。色は 0xAARRGGBB で、0 は「自動色」(背景に合わせて白か黒にする)。 */
class CadLayer(val name: String, var visible: Boolean) {
    /** この層にある図形の数 */
    var count: Int = 0
}

/** 同じ層・同じ色の線の束。xy は x y の並びで、lens[i] は i 番目の折れ線の点の数。 */
class CadStrokes(val layer: Int, val color: Int, val xy: FloatArray, val lens: IntArray, val closed: BooleanArray)

/** 塗りつぶし。loops が複数あるときは、偶奇規則(穴あき)で塗る。 */
class CadFill(val layer: Int, val color: Int, val xy: FloatArray, val lens: IntArray)

/**
 * 文字。(x, y) は hAlign / vAlign で決まる基準点。hAlign: 0 左 / 1 中央 / 2 右。vAlign: 0 ベースライン / 1 下 / 2 中央 / 3 上。
 * text に改行があれば複数行。
 */
class CadText(
    val layer: Int, val color: Int, val x: Float, val y: Float, val height: Float, val rotation: Float,
    val text: String, val hAlign: Int, val vAlign: Int, val widthFactor: Float,
)

class CadDrawing(
    val layers: List<CadLayer>,
    val strokes: List<CadStrokes>,
    val fills: List<CadFill>,
    val texts: List<CadText>,
    /** 図形の範囲(原点からの相対) */
    val minX: Float, val minY: Float, val maxX: Float, val maxY: Float,
    /** 「全体表示」で合わせる範囲。遠くに離れた図形があっても、本体が見えるようにする */
    val fitMinX: Float, val fitMinY: Float, val fitMaxX: Float, val fitMaxY: Float,
    val shapeCount: Int,
    val note: String,
)

/** 座標をためて CadDrawing にする。座標は Double で受け取り、最後に範囲の中心を原点にして Float にする。 */
class CadBuilder(private val maxShapes: Int = MAX_SHAPES) {
    val layers = ArrayList<CadLayer>()
    private val layerIdx = HashMap<String, Int>()

    private class IntBuf {
        var a = IntArray(16)
        var n = 0
        fun add(v: Int) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
        fun toArray() = a.copyOf(n)
    }

    private class Buf {
        var xy = DoubleArray(64)
        var n = 0
        val lens = IntBuf()
        val closed = ArrayList<Boolean>()
        fun add(x: Double, y: Double) {
            if (n + 2 > xy.size) xy = xy.copyOf(xy.size * 2)
            xy[n++] = x; xy[n++] = y
        }
    }

    private val strokeBufs = LinkedHashMap<Long, Buf>()
    private class FillRaw(val layer: Int, val color: Int, val xy: DoubleArray, val lens: IntArray)
    private val fillList = ArrayList<FillRaw>()
    private class TextRaw(
        val layer: Int, val color: Int, val x: Double, val y: Double, val height: Double, val rotation: Double,
        val text: String, val hAlign: Int, val vAlign: Int, val widthFactor: Double,
    )
    private val textList = ArrayList<TextRaw>()
    var shapes = 0
        private set

    fun layer(name: String, visible: Boolean = true): Int =
        layerIdx.getOrPut(name) { layers.add(CadLayer(name, visible)); layers.size - 1 }

    private fun count(layer: Int) {
        if (++shapes > maxShapes) throw OpException(Txt(R.string.cad_001))
        layers[layer].count++
    }

    private fun key(layer: Int, color: Int) = (layer.toLong() shl 32) or (color.toLong() and 0xFFFFFFFFL)

    fun line(layer: Int, color: Int, x1: Double, y1: Double, x2: Double, y2: Double) {
        if (!(x1.isFinite() && y1.isFinite() && x2.isFinite() && y2.isFinite())) return
        count(layer)
        val b = strokeBufs.getOrPut(key(layer, color)) { Buf() }
        b.add(x1, y1); b.add(x2, y2)
        b.lens.add(2); b.closed.add(false)
    }

    /** pts: x y の並び。count: 点の数 */
    fun poly(layer: Int, color: Int, pts: DoubleArray, count: Int, closed: Boolean) {
        if (count < 2) return
        for (i in 0 until count * 2) if (!pts[i].isFinite()) return
        count(layer)
        val b = strokeBufs.getOrPut(key(layer, color)) { Buf() }
        for (i in 0 until count) b.add(pts[i * 2], pts[i * 2 + 1])
        b.lens.add(count); b.closed.add(closed)
    }

    /** loops の各要素は x y の並び */
    fun fill(layer: Int, color: Int, loops: List<DoubleArray>) {
        val ok = loops.filter { it.size >= 6 && it.all { v -> v.isFinite() } }
        if (ok.isEmpty()) return
        count(layer)
        val all = DoubleArray(ok.sumOf { it.size })
        var o = 0
        for (l in ok) { System.arraycopy(l, 0, all, o, l.size); o += l.size }
        fillList.add(FillRaw(layer, color, all, IntArray(ok.size) { ok[it].size / 2 }))
    }

    private class PointRaw(val layer: Int, val color: Int, val x: Double, val y: Double)
    private val pointList = ArrayList<PointRaw>()

    /** 点。小さな十字で示す(大きさは図面全体の大きさに合わせる) */
    fun point(layer: Int, color: Int, x: Double, y: Double) {
        if (!(x.isFinite() && y.isFinite())) return
        count(layer)
        pointList.add(PointRaw(layer, color, x, y))
    }

    fun text(layer: Int, color: Int, x: Double, y: Double, height: Double, rotation: Double, text: String, hAlign: Int = 0, vAlign: Int = 0, widthFactor: Double = 1.0) {
        if (text.isEmpty() || !(x.isFinite() && y.isFinite() && height.isFinite()) || height <= 0.0) return
        if (textList.size >= MAX_TEXTS) return
        count(layer)
        textList.add(TextRaw(layer, color, x, y, height, if (rotation.isFinite()) rotation else 0.0, text, hAlign, vAlign, if (widthFactor.isFinite() && widthFactor > 0.0) widthFactor else 1.0))
    }

    fun build(note: String = ""): CadDrawing {
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        fun grow(x: Double, y: Double) {
            if (x < x0) x0 = x; if (x > x1) x1 = x
            if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        val xs = ArrayList<Double>() // 外れ値を除くための標本
        val ys = ArrayList<Double>()
        var seen = 0L
        fun sample(x: Double, y: Double) {
            seen++
            if (xs.size < SAMPLE_MAX) { xs.add(x); ys.add(y) }
            else { val r = (seen * 2654435761L and 0x7FFFFFFF) % seen; if (r < SAMPLE_MAX) { xs[r.toInt()] = x; ys[r.toInt()] = y } }
        }
        for (b in strokeBufs.values) { var i = 0; while (i < b.n) { grow(b.xy[i], b.xy[i + 1]); sample(b.xy[i], b.xy[i + 1]); i += 2 } }
        for (f in fillList) { var i = 0; while (i < f.xy.size) { grow(f.xy[i], f.xy[i + 1]); sample(f.xy[i], f.xy[i + 1]); i += 2 } }
        for (t in textList) { grow(t.x, t.y); sample(t.x, t.y) }
        for (p in pointList) { grow(p.x, p.y); sample(p.x, p.y) }
        if (x0 > x1) throw OpException(Txt(R.string.cad_002))
        if (pointList.isNotEmpty()) {
            val ext = max(x1 - x0, y1 - y0)
            val arm = if (ext > 0) ext * 0.004 else 1.0
            for (p in pointList) {
                val b = strokeBufs.getOrPut(key(p.layer, p.color)) { Buf() }
                b.add(p.x - arm, p.y); b.add(p.x + arm, p.y); b.lens.add(2); b.closed.add(false)
                b.add(p.x, p.y - arm); b.add(p.x, p.y + arm); b.lens.add(2); b.closed.add(false)
            }
        }
        val ox = (x0 + x1) / 2
        val oy = (y0 + y1) / 2
        // 1% 〜 99% の範囲が全体よりずっと小さければ、本体が小さく見えないよう、そちらに合わせる
        var fx0 = x0; var fy0 = y0; var fx1 = x1; var fy1 = y1
        if (xs.size >= 50) {
            val sx = xs.sorted(); val sy = ys.sorted()
            val lo = (sx.size * 0.01).toInt(); val hi = (sx.size * 0.99).toInt().coerceAtMost(sx.size - 1)
            val px0 = sx[lo]; val px1 = sx[hi]; val py0 = sy[lo]; val py1 = sy[hi]
            val full = max(x1 - x0, 1e-9) * max(y1 - y0, 1e-9)
            val core = max(px1 - px0, 1e-9) * max(py1 - py0, 1e-9)
            if (full > core * 16) {
                val mx = (px1 - px0) * 0.1; val my = (py1 - py0) * 0.1
                fx0 = px0 - mx; fx1 = px1 + mx; fy0 = py0 - my; fy1 = py1 + my
            }
        }
        fun rel(v: Double, o: Double) = (v - o).toFloat()
        val strokes = strokeBufs.map { (k, b) ->
            val f = FloatArray(b.n)
            var i = 0
            while (i < b.n) { f[i] = rel(b.xy[i], ox); f[i + 1] = rel(b.xy[i + 1], oy); i += 2 }
            CadStrokes((k ushr 32).toInt(), (k and 0xFFFFFFFFL).toInt(), f, b.lens.toArray(), b.closed.toBooleanArray())
        }
        val fills = fillList.map { r ->
            val f = FloatArray(r.xy.size)
            var i = 0
            while (i < f.size) { f[i] = rel(r.xy[i], ox); f[i + 1] = rel(r.xy[i + 1], oy); i += 2 }
            CadFill(r.layer, r.color, f, r.lens)
        }
        val texts = textList.map { CadText(it.layer, it.color, rel(it.x, ox), rel(it.y, oy), it.height.toFloat(), it.rotation.toFloat(), it.text, it.hAlign, it.vAlign, it.widthFactor.toFloat()) }
        return CadDrawing(
            layers, strokes, fills, texts,
            rel(x0, ox), rel(y0, oy), rel(x1, ox), rel(y1, oy),
            rel(fx0, ox), rel(fy0, oy), rel(fx1, ox), rel(fy1, oy),
            shapes, note,
        )
    }

    companion object {
        const val MAX_SHAPES = 4_000_000
        const val MAX_TEXTS = 60_000
        private const val SAMPLE_MAX = 100_000
    }
}

/** 円弧・膨らみ・色の共通の計算 */
internal object CadGeo {
    /** 円弧を折れ線にする。角度はラジアン。反時計回りに sweep だけ進む(負なら時計回り)。中心 (cx, cy)、半径 r。 */
    fun arc(cx: Double, cy: Double, r: Double, start: Double, sweep: Double, stepDeg: Double = 2.0): DoubleArray {
        val n = max(2, ceil(abs(sweep) / Math.toRadians(stepDeg)).toInt()).coerceAtMost(720)
        val out = DoubleArray((n + 1) * 2)
        for (i in 0..n) {
            val a = start + sweep * i / n
            out[i * 2] = cx + r * cos(a)
            out[i * 2 + 1] = cy + r * sin(a)
        }
        return out
    }

    /** 楕円(長軸の向き tilt、長軸 rx、短軸 ry)のパラメータ t0 から sweep だけの折れ線 */
    fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double, tilt: Double, t0: Double, sweep: Double): DoubleArray {
        val n = max(4, ceil(abs(sweep) / Math.toRadians(2.0)).toInt()).coerceAtMost(720)
        val out = DoubleArray((n + 1) * 2)
        val ct = cos(tilt); val st = sin(tilt)
        for (i in 0..n) {
            val t = t0 + sweep * i / n
            val lx = rx * cos(t); val ly = ry * sin(t)
            out[i * 2] = cx + lx * ct - ly * st
            out[i * 2 + 1] = cy + lx * st + ly * ct
        }
        return out
    }

    /** (x1,y1) から (x2,y2) へ、膨らみ bulge の弧。始点は含み、終点は含まない。 */
    fun bulge(x1: Double, y1: Double, x2: Double, y2: Double, bulge: Double): DoubleArray {
        val dx = x2 - x1; val dy = y2 - y1
        val len = hypot(dx, dy)
        if (len < 1e-12 || abs(bulge) < 1e-9) return doubleArrayOf(x1, y1)
        val theta = 4 * atan(bulge)
        val h = (len / 2) / tan(theta / 2)
        val cx = (x1 + x2) / 2 - dy / len * h
        val cy = (y1 + y2) / 2 + dx / len * h
        val r = hypot(x1 - cx, y1 - cy)
        val a = atan2(y1 - cy, x1 - cx)
        val full = arc(cx, cy, r, a, theta)
        return full.copyOf(full.size - 2)
    }
}

/** AutoCAD の色番号(ACI)→ 0xFFRRGGBB。7 は背景に合わせる色なので 0(自動色)。 */
internal object Aci {
    private val table: IntArray by lazy {
        val t = IntArray(256)
        val base = intArrayOf(
            0x000000, 0xFF0000, 0xFFFF00, 0x00FF00, 0x00FFFF, 0x0000FF, 0xFF00FF, 0xFFFFFF, 0x808080, 0xC0C0C0,
        )
        for (i in 0..9) t[i] = 0xFF000000.toInt() or base[i]
        val vf = floatArrayOf(1f, 0.8f, 0.6f, 0.5f, 0.3f)
        for (i in 10..249) {
            val hue = (i - 10) / 10
            val sub = (i - 10) % 10
            val v = (255f * vf[sub / 2] + 0.001f).toInt()
            val minC = if (sub % 2 == 0) 0 else (v * 0.5f + 0.001f).toInt()
            val h6 = hue * 15f / 60f
            val seg = h6.toInt() % 6
            val f = h6 - h6.toInt()
            fun mix(frac: Float) = (minC + (v - minC) * frac + 0.001f).toInt()
            val (r, g, b) = when (seg) {
                0 -> Triple(v, mix(f), minC)
                1 -> Triple(mix(1 - f), v, minC)
                2 -> Triple(minC, v, mix(f))
                3 -> Triple(minC, mix(1 - f), v)
                4 -> Triple(mix(f), minC, v)
                else -> Triple(v, minC, mix(1 - f))
            }
            t[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val grays = intArrayOf(51, 91, 132, 173, 214, 255)
        for (i in 250..255) t[i] = (0xFF shl 24) or (grays[i - 250] shl 16) or (grays[i - 250] shl 8) or grays[i - 250]
        t
    }

    fun rgb(index: Int): Int = if (index == 7 || index == 0) 0 else table[index.coerceIn(0, 255)]
}

/** 図面の色を、背景(暗い / 明るい)で見やすい色にする */
internal object CadColor {
    fun adapt(argb: Int, dark: Boolean): Int {
        if (argb == 0) return if (dark) 0xFFE6E6E6.toInt() else 0xFF111111.toInt()
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        val lum = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
        val neutral = max(r, max(g, b)) - min(r, min(g, b)) < 12
        // 黒・白に近い無彩色は「自動色」と同じ扱い(背景と同じ色で見えなくなるのを防ぐ)
        if (neutral && (lum > 0.92f || lum < 0.08f)) return adapt(0, dark)
        return if (dark) {
            if (lum < 0.30f) mixWhite(argb, 0.55f - lum) else argb
        } else {
            if (lum > 0.62f) scale(argb, 0.62f / lum * 0.9f) else argb
        }
    }

    private fun mixWhite(argb: Int, t: Float): Int {
        fun c(v: Int) = (v + (255 - v) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (c((argb shr 16) and 0xFF) shl 16) or (c((argb shr 8) and 0xFF) shl 8) or c(argb and 0xFF)
    }

    private fun scale(argb: Int, k: Float): Int {
        fun c(v: Int) = (v * k).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (c((argb shr 16) and 0xFF) shl 16) or (c((argb shr 8) and 0xFF) shl 8) or c(argb and 0xFF)
    }
}

/** 2D の変換: x' = a x + c y + e、y' = b x + d y + f */
internal class Xf(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
    fun x(x: Double, y: Double) = a * x + c * y + e
    fun y(x: Double, y: Double) = b * x + d * y + f

    /** this を先に、o を後に当てる */
    fun then(o: Xf) = Xf(
        o.a * a + o.c * b, o.b * a + o.d * b,
        o.a * c + o.c * d, o.b * c + o.d * d,
        o.a * e + o.c * f + o.e, o.b * e + o.d * f + o.f,
    )

    val scale: Double get() = kotlin.math.sqrt(abs(a * d - b * c))
    val rotation: Double get() = atan2(b, a)

    companion object {
        val ID = Xf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
    }
}
