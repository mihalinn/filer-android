package local.filemanager

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** 図形の形。prst は「rect」「ellipse」などの名前、adj は調整値。cust は自由な形(custGeom)。 */
internal class Geom(val prst: String, val adj: Map<String, Float> = emptyMap(), val cust: List<CustPath>? = null)

internal sealed interface CustCmd {
    class Move(val x: Float, val y: Float) : CustCmd
    class Line(val x: Float, val y: Float) : CustCmd
    class Cubic(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : CustCmd
    class Quad(val x1: Float, val y1: Float, val x: Float, val y: Float) : CustCmd
    class Arc(val wR: Float, val hR: Float, val stDeg: Float, val swDeg: Float) : CustCmd
    data object Close : CustCmd
}

/** 自由な形の 1 本の経路。w・h は、座標の枠(0 ならそのまま図形の大きさ)。 */
internal class CustPath(val w: Float, val h: Float, val cmds: List<CustCmd>, val fill: Boolean, val stroke: Boolean)

/** 折れ線にした 1 つの輪郭。pts は x0,y0,x1,y1… (pt)。 */
internal class Contour(val pts: FloatArray, val closed: Boolean, val fill: Boolean = true, val stroke: Boolean = true)

internal object SlideGeom {
    private class Poly {
        val pts = ArrayList<Float>()
        fun p(x: Float, y: Float): Poly { pts.add(x); pts.add(y); return this }

        /** 中心 (cx, cy)・半径 (rx, ry) の弧を、角度 a0〜a1(度。右が 0、下が 90)で足す。 */
        fun arc(cx: Float, cy: Float, rx: Float, ry: Float, a0: Float, a1: Float, steps: Int = 8): Poly {
            for (i in 0..steps) {
                val a = (a0 + (a1 - a0) * i / steps) * PI.toFloat() / 180f
                p(cx + rx * cos(a), cy + ry * sin(a))
            }
            return this
        }

        fun toContour(closed: Boolean = true, fill: Boolean = true, stroke: Boolean = true) = Contour(pts.toFloatArray(), closed, fill, stroke)
    }

    private fun poly(vararg xy: Float): Poly {
        val p = Poly()
        var i = 0
        while (i + 1 < xy.size) { p.p(xy[i], xy[i + 1]); i += 2 }
        return p
    }

    fun contours(g: Geom, w: Float, h: Float, flipH: Boolean, flipV: Boolean): List<Contour> {
        val raw = if (g.cust != null) custom(g.cust, w, h) else preset(g, w, h)
        if (!flipH && !flipV) return raw
        return raw.map { c ->
            val a = c.pts.copyOf()
            var i = 0
            while (i + 1 < a.size) {
                if (flipH) a[i] = w - a[i]
                if (flipV) a[i + 1] = h - a[i + 1]
                i += 2
            }
            Contour(a, c.closed, c.fill, c.stroke)
        }
    }

    private fun adj(g: Geom, name: String, def: Float) = (g.adj[name] ?: def)

    private fun roundRect(w: Float, h: Float, r0: Float): Contour {
        val r = r0.coerceIn(0f, min(w, h) / 2f)
        if (r <= 0.01f) return poly(0f, 0f, w, 0f, w, h, 0f, h).toContour()
        val p = Poly()
        p.arc(r, r, r, r, 180f, 270f, 6)
        p.arc(w - r, r, r, r, 270f, 360f, 6)
        p.arc(w - r, h - r, r, r, 0f, 90f, 6)
        p.arc(r, h - r, r, r, 90f, 180f, 6)
        return p.toContour()
    }

    private fun star(w: Float, h: Float, n: Int, inner: Float): Contour {
        val p = Poly()
        val cx = w / 2f
        val cy = h / 2f
        for (i in 0 until n * 2) {
            val a = (-90f + 180f * i / n) * PI.toFloat() / 180f
            val r = if (i % 2 == 0) 1f else inner
            p.p(cx + w / 2f * r * cos(a), cy + h / 2f * r * sin(a))
        }
        return p.toContour()
    }

    /** 右向きの矢印(長さ方向が x)。len×across の枠。 */
    private fun rightArrow(len: Float, across: Float, a1: Float, a2: Float): Poly {
        val ss = min(len, across)
        val head = (ss * a2 / 100000f).coerceAtMost(len)
        val y1 = across * (50000f - a1 / 2f) / 100000f
        val y2 = across - y1
        val x1 = len - head
        return poly(0f, y1, x1, y1, x1, 0f, len, across / 2f, x1, across, x1, y2, 0f, y2)
    }

    private fun mapPoly(p: Poly, f: (Float, Float) -> Pair<Float, Float>): Poly {
        val q = Poly()
        var i = 0
        while (i + 1 < p.pts.size) {
            val (x, y) = f(p.pts[i], p.pts[i + 1])
            q.p(x, y)
            i += 2
        }
        return q
    }

    private fun preset(g: Geom, w: Float, h: Float): List<Contour> {
        val ss = min(w, h)
        return when (g.prst) {
            "line", "straightConnector1" -> listOf(poly(0f, 0f, w, h).toContour(closed = false, fill = false))
            "bentConnector2" -> listOf(poly(0f, 0f, w, 0f, w, h).toContour(closed = false, fill = false))
            "bentConnector3", "bentConnector4", "bentConnector5" -> {
                val x1 = w * adj(g, "adj1", 50000f) / 100000f
                listOf(poly(0f, 0f, x1, 0f, x1, h, w, h).toContour(closed = false, fill = false))
            }
            "curvedConnector2", "curvedConnector3", "curvedConnector4", "curvedConnector5" -> {
                val p = Poly()
                for (i in 0..16) {
                    val t = i / 16f
                    val u = 1f - t
                    val x = u * u * u * 0f + 3f * u * u * t * (w / 2f) + 3f * u * t * t * (w / 2f) + t * t * t * w
                    val y = 3f * u * t * t * h + t * t * t * h
                    p.p(x, y)
                }
                listOf(p.toContour(closed = false, fill = false))
            }
            "ellipse", "flowChartConnector", "flowChartSummingJunction", "flowChartOr", "cloud", "smileyFace", "sun", "moon", "noSmoking", "chord", "pie", "blockArc", "arc", "circularArrow" ->
                listOf(Poly().arc(w / 2f, h / 2f, w / 2f, h / 2f, 0f, 360f, 48).toContour())
            "roundRect", "round1Rect", "round2SameRect", "round2DiagRect", "wedgeRoundRectCallout", "snipRoundRect" ->
                listOf(roundRect(w, h, ss * adj(g, "adj", adj(g, "adj1", 16667f)) / 100000f))
            "flowChartAlternateProcess" -> listOf(roundRect(w, h, ss * 0.16667f))
            "flowChartTerminator" -> listOf(roundRect(w, h, ss / 2f))
            "triangle" -> listOf(poly(w * adj(g, "adj", 50000f) / 100000f, 0f, w, h, 0f, h).toContour())
            "rtTriangle" -> listOf(poly(0f, 0f, w, h, 0f, h).toContour())
            "diamond", "flowChartDecision", "flowChartPreparation" -> listOf(poly(w / 2f, 0f, w, h / 2f, w / 2f, h, 0f, h / 2f).toContour())
            "parallelogram", "flowChartData" -> {
                val x2 = (ss * adj(g, "adj", 25000f) / 100000f).coerceAtMost(w)
                listOf(poly(x2, 0f, w, 0f, w - x2, h, 0f, h).toContour())
            }
            "trapezoid", "flowChartManualOperation" -> {
                val x2 = (ss * adj(g, "adj", 25000f) / 100000f).coerceAtMost(w / 2f)
                listOf(poly(0f, h, x2, 0f, w - x2, 0f, w, h).toContour())
            }
            "pentagon" -> {
                val p = Poly()
                for (i in 0 until 5) {
                    val a = (-90f + 72f * i) * PI.toFloat() / 180f
                    p.p(w / 2f + w / 2f * cos(a), h / 2f + h / 2f * sin(a))
                }
                listOf(p.toContour())
            }
            "hexagon" -> {
                val x1 = (ss * adj(g, "adj", 25000f) / 100000f).coerceAtMost(w / 2f)
                listOf(poly(0f, h / 2f, x1, 0f, w - x1, 0f, w, h / 2f, w - x1, h, x1, h).toContour())
            }
            "octagon" -> {
                val x1 = (ss * adj(g, "adj", 29289f) / 100000f).coerceAtMost(w / 2f)
                listOf(poly(0f, x1, x1, 0f, w - x1, 0f, w, x1, w, h - x1, w - x1, h, x1, h, 0f, h - x1).toContour())
            }
            "star4" -> listOf(star(w, h, 4, 0.38f))
            "star5" -> listOf(star(w, h, 5, 0.382f))
            "star6" -> listOf(star(w, h, 6, 0.58f))
            "star8" -> listOf(star(w, h, 8, 0.76f))
            "star10", "star12", "star16", "star24", "star32" -> listOf(star(w, h, g.prst.removePrefix("star").toInt(), 0.8f))
            "rightArrow", "notchedRightArrow", "stripedRightArrow", "bentArrow", "uturnArrow", "curvedRightArrow" ->
                listOf(rightArrow(w, h, adj(g, "adj1", 50000f), adj(g, "adj2", 50000f)).toContour())
            "leftArrow" -> listOf(mapPoly(rightArrow(w, h, adj(g, "adj1", 50000f), adj(g, "adj2", 50000f))) { x, y -> (w - x) to y }.toContour())
            "upArrow" -> listOf(mapPoly(rightArrow(h, w, adj(g, "adj1", 50000f), adj(g, "adj2", 50000f))) { x, y -> y to (h - x) }.toContour())
            "downArrow" -> listOf(mapPoly(rightArrow(h, w, adj(g, "adj1", 50000f), adj(g, "adj2", 50000f))) { x, y -> y to x }.toContour())
            "leftRightArrow" -> listOf(leftRight(w, h, adj(g, "adj1", 50000f), adj(g, "adj2", 50000f)).toContour())
            "upDownArrow" -> listOf(mapPoly(leftRight(h, w, adj(g, "adj1", 50000f), adj(g, "adj2", 50000f))) { x, y -> y to x }.toContour())
            "chevron" -> {
                val x1 = (ss * adj(g, "adj", 50000f) / 100000f).coerceAtMost(w)
                listOf(poly(0f, 0f, w - x1, 0f, w, h / 2f, w - x1, h, 0f, h, x1, h / 2f).toContour())
            }
            "homePlate" -> {
                val x1 = w - (ss * adj(g, "adj", 50000f) / 100000f).coerceAtMost(w)
                listOf(poly(0f, 0f, x1, 0f, w, h / 2f, x1, h, 0f, h).toContour())
            }
            "plus", "mathPlus" -> {
                val x1 = (ss * adj(g, "adj", 25000f) / 100000f).coerceAtMost(min(w, h) / 2f)
                listOf(poly(x1, 0f, w - x1, 0f, w - x1, x1, w, x1, w, h - x1, w - x1, h - x1, w - x1, h, x1, h, x1, h - x1, 0f, h - x1, 0f, x1, x1, x1).toContour())
            }
            "donut" -> {
                val t = ss * adj(g, "adj", 25000f) / 100000f
                listOf(
                    Poly().arc(w / 2f, h / 2f, w / 2f, h / 2f, 0f, 360f, 48).toContour(),
                    Poly().arc(w / 2f, h / 2f, max(w / 2f - t, 0.1f), max(h / 2f - t, 0.1f), 0f, 360f, 48).toContour(),
                )
            }
            "heart" -> {
                val p = Poly()
                for (i in 0 until 64) {
                    val t = (2 * PI * i / 64).toFloat()
                    val x = 16f * sin(t) * sin(t) * sin(t)
                    val y = 13f * cos(t) - 5f * cos(2 * t) - 2f * cos(3 * t) - cos(4 * t)
                    p.p(w / 2f + x / 17f * w / 2f, h * (0.45f - y / 17f * 0.5f + 0.05f))
                }
                listOf(p.toContour())
            }
            else -> listOf(poly(0f, 0f, w, 0f, w, h, 0f, h).toContour())
        }
    }

    private fun leftRight(w: Float, h: Float, a1: Float, a2: Float): Poly {
        val ss = min(w, h)
        val head = (ss * a2 / 100000f).coerceAtMost(w / 2f)
        val y1 = h * (50000f - a1 / 2f) / 100000f
        val y2 = h - y1
        return poly(0f, h / 2f, head, 0f, head, y1, w - head, y1, w - head, 0f, w, h / 2f, w - head, h, w - head, y2, head, y2, head, h)
    }

    private fun custom(paths: List<CustPath>, w: Float, h: Float): List<Contour> {
        val out = ArrayList<Contour>()
        for (cp in paths) {
            val sx = if (cp.w > 0f) w / cp.w else 1f
            val sy = if (cp.h > 0f) h / cp.h else 1f
            var cur = Poly()
            var curX = 0f
            var curY = 0f
            var startX = 0f
            var startY = 0f
            fun flush(closed: Boolean) {
                if (cur.pts.size >= 4 || (cur.pts.size >= 2 && closed)) out.add(cur.toContour(closed, cp.fill, cp.stroke))
                cur = Poly()
            }
            for (cmd in cp.cmds) when (cmd) {
                is CustCmd.Move -> {
                    flush(false)
                    curX = cmd.x * sx; curY = cmd.y * sy; startX = curX; startY = curY
                    cur.p(curX, curY)
                }
                is CustCmd.Line -> {
                    if (cur.pts.isEmpty()) cur.p(curX, curY)
                    curX = cmd.x * sx; curY = cmd.y * sy
                    cur.p(curX, curY)
                }
                is CustCmd.Cubic -> {
                    if (cur.pts.isEmpty()) cur.p(curX, curY)
                    val x0 = curX; val y0 = curY
                    val x1 = cmd.x1 * sx; val y1 = cmd.y1 * sy
                    val x2 = cmd.x2 * sx; val y2 = cmd.y2 * sy
                    val x3 = cmd.x * sx; val y3 = cmd.y * sy
                    for (i in 1..14) {
                        val t = i / 14f
                        val u = 1f - t
                        cur.p(
                            u * u * u * x0 + 3f * u * u * t * x1 + 3f * u * t * t * x2 + t * t * t * x3,
                            u * u * u * y0 + 3f * u * u * t * y1 + 3f * u * t * t * y2 + t * t * t * y3,
                        )
                    }
                    curX = x3; curY = y3
                }
                is CustCmd.Quad -> {
                    if (cur.pts.isEmpty()) cur.p(curX, curY)
                    val x0 = curX; val y0 = curY
                    val x1 = cmd.x1 * sx; val y1 = cmd.y1 * sy
                    val x2 = cmd.x * sx; val y2 = cmd.y * sy
                    for (i in 1..10) {
                        val t = i / 10f
                        val u = 1f - t
                        cur.p(u * u * x0 + 2f * u * t * x1 + t * t * x2, u * u * y0 + 2f * u * t * y1 + t * t * y2)
                    }
                    curX = x2; curY = y2
                }
                is CustCmd.Arc -> {
                    if (cur.pts.isEmpty()) cur.p(curX, curY)
                    val wr = cmd.wR * sx
                    val hr = cmd.hR * sy
                    if (wr > 0f && hr > 0f) {
                        val th0 = cmd.stDeg * PI.toFloat() / 180f
                        val t0 = atan2(wr * sin(th0), hr * cos(th0))
                        val th1 = (cmd.stDeg + cmd.swDeg) * PI.toFloat() / 180f
                        val t1raw = atan2(wr * sin(th1), hr * cos(th1))
                        val sw = cmd.swDeg * PI.toFloat() / 180f
                        val twoPi = (2 * PI).toFloat()
                        val t1 = t1raw + twoPi * ((t0 + sw - t1raw) / twoPi).roundToInt()
                        val cx = curX - wr * cos(t0)
                        val cy = curY - hr * sin(t0)
                        val steps = max(4, (kotlin.math.abs(cmd.swDeg) / 10f).toInt())
                        for (i in 1..steps) {
                            val t = t0 + (t1 - t0) * i / steps
                            cur.p(cx + wr * cos(t), cy + hr * sin(t))
                        }
                        curX = cx + wr * cos(t1); curY = cy + hr * sin(t1)
                    }
                }
                CustCmd.Close -> {
                    flush(true)
                    curX = startX; curY = startY
                }
            }
            flush(false)
        }
        return out
    }
}
