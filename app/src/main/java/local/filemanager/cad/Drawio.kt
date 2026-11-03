package local.filemanager

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.util.Base64
import java.util.zip.Inflater
import javax.xml.parsers.DocumentBuilderFactory

/**
 * draw.io(diagrams.net)の図面(.drawio)を、描くだけの形(図形と線)に直す。Android に依存しない。
 * 圧縮された図面にも対応する。draw.io 本体と同じ見た目にはならない(よく使う図形と線だけを描く)。
 */
object Drawio {
    data class Pt(val x: Float, val y: Float)

    sealed interface Item

    data class Shape(
        /** rect / ellipse / rhombus / triangle / hexagon / cylinder / cloud / swimlane / text / image / stencil など */
        val kind: String,
        val x: Float, val y: Float, val w: Float, val h: Float,
        val fill: Int?, val stroke: Int?, val strokeWidth: Float, val dashed: Boolean, val rounded: Boolean, val opacity: Float,
        val label: String, val fontColor: Int, val fontSize: Float, val bold: Boolean, val italic: Boolean, val underline: Boolean,
        /** -1 左 / 0 中央 / 1 右 */
        val align: Int,
        /** -1 上 / 0 中央 / 1 下 */
        val valign: Int,
        val wrap: Boolean,
        /** swimlane の見出しの太さ(それ以外は 0) */
        val header: Float,
        val headerVertical: Boolean,
        /** 画像や特殊な図形の、代わりに出す短い説明 */
        val note: String?,
    ) : Item

    data class Line(
        val points: List<Pt>,
        val stroke: Int, val strokeWidth: Float, val dashed: Boolean,
        val startArrow: String, val endArrow: String, val startFill: Boolean, val endFill: Boolean,
    ) : Item

    /** 線の上の文字(中心の位置)。 */
    data class Label(
        val text: String, val x: Float, val y: Float,
        val fontColor: Int, val fontSize: Float, val bold: Boolean, val italic: Boolean,
    ) : Item

    class Page(val name: String, val items: List<Item>, val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

    // ---------------------------------------------------------------- 読み込み

    /** 図面全体(複数ページ)。読めなければ null。 */
    fun parse(text: String): List<Page>? {
        return try {
            val root = documentOf(text.removePrefix("﻿")) ?: return null
            val models = ArrayList<Pair<String, Element>>()
            when (root.tagName) {
                "mxfile" -> {
                    val diagrams = children(root, "diagram")
                    for ((i, d) in diagrams.withIndex()) {
                        val name = d.getAttribute("name").ifBlank { "ページ-${i + 1}" }
                        val model = children(d, "mxGraphModel").firstOrNull()
                            ?: decompress(d.textContent)?.let { documentOf(it) }?.takeIf { it.tagName == "mxGraphModel" }
                        if (model != null) models.add(name to model)
                    }
                }
                "mxGraphModel" -> models.add("ページ-1" to root)
                else -> return null
            }
            if (models.isEmpty()) return null
            models.map { (name, model) -> build(name, model) }
        } catch (e: Exception) {
            null
        }
    }

    private fun documentOf(text: String): Element? {
        val f = DocumentBuilderFactory.newInstance()
        // 外部の読み込みは使わない(悪意のある図面を開いても外へ取りに行かない)
        for ((k, v) in listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
        )) {
            runCatching { f.setFeature(k, v) }
        }
        runCatching { f.isXIncludeAware = false }
        runCatching { f.isExpandEntityReferences = false }
        val b = f.newDocumentBuilder()
        return b.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))).documentElement
    }

    /** draw.io の圧縮形式: base64 → deflate(zlib ヘッダーなし)→ URL エンコードされた XML */
    fun decompress(data: String): String? = try {
        val raw = Base64.getMimeDecoder().decode(data.trim())
        val inf = Inflater(true)
        inf.setInput(raw)
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, n)
            if (out.size() > 64 * 1024 * 1024) break
        }
        inf.end()
        URLDecoder.decode(out.toString("UTF-8"), "UTF-8")
    } catch (e: Exception) {
        null
    }

    private fun children(e: Element, name: String): List<Element> {
        val out = ArrayList<Element>()
        var n: Node? = e.firstChild
        while (n != null) {
            if (n is Element && n.tagName == name) out.add(n)
            n = n.nextSibling
        }
        return out
    }

    // ---------------------------------------------------------------- セル

    private class Geo(
        val x: Float, val y: Float, val w: Float, val h: Float, val relative: Boolean,
        val points: List<Pt>, val sourcePoint: Pt?, val targetPoint: Pt?, val offset: Pt?,
    )

    private class Cell(
        val id: String, val parent: String, val value: String, val style: Map<String, String>,
        val vertex: Boolean, val edge: Boolean, val source: String?, val target: String?, val geo: Geo?, val visible: Boolean,
    )

    private fun f(e: Element, name: String, def: Float = 0f): Float = e.getAttribute(name).toFloatOrNull() ?: def

    private fun pt(e: Element) = Pt(f(e, "x"), f(e, "y"))

    private fun readCell(cellEl: Element, id: String, value: String): Cell {
        val geoEl = children(cellEl, "mxGeometry").firstOrNull()
        val geo = geoEl?.let { g ->
            var sp: Pt? = null
            var tp: Pt? = null
            var off: Pt? = null
            val pts = ArrayList<Pt>()
            for (p in children(g, "mxPoint")) when (p.getAttribute("as")) {
                "sourcePoint" -> sp = pt(p)
                "targetPoint" -> tp = pt(p)
                "offset" -> off = pt(p)
            }
            for (arr in children(g, "Array")) if (arr.getAttribute("as") == "points") for (p in children(arr, "mxPoint")) pts.add(pt(p))
            Geo(f(g, "x"), f(g, "y"), f(g, "width"), f(g, "height"), g.getAttribute("relative") == "1", pts, sp, tp, off)
        }
        return Cell(
            id, cellEl.getAttribute("parent"), value, parseStyle(cellEl.getAttribute("style")),
            cellEl.getAttribute("vertex") == "1", cellEl.getAttribute("edge") == "1",
            cellEl.getAttribute("source").ifEmpty { null }, cellEl.getAttribute("target").ifEmpty { null }, geo,
            cellEl.getAttribute("visible") != "0",
        )
    }

    internal fun parseStyle(s: String): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for ((i, part) in s.split(';').withIndex()) {
            if (part.isBlank()) continue
            val eq = part.indexOf('=')
            if (eq < 0) {
                if (i == 0) m["shape"] = part.trim() // 先頭の「ellipse;」のような書き方
            } else {
                m[part.substring(0, eq).trim()] = part.substring(eq + 1).trim()
            }
        }
        return m
    }

    // ---------------------------------------------------------------- 色・文字

    private val named = mapOf(
        "white" to 0xFFFFFFFF.toInt(), "black" to 0xFF000000.toInt(), "red" to 0xFFFF0000.toInt(), "green" to 0xFF008000.toInt(),
        "blue" to 0xFF0000FF.toInt(), "yellow" to 0xFFFFFF00.toInt(), "gray" to 0xFF808080.toInt(), "grey" to 0xFF808080.toInt(),
        "orange" to 0xFFFFA500.toInt(),
    )

    /** スタイルの色。無いときは def、none のときは null。 */
    internal fun colorOf(style: Map<String, String>, key: String, def: Int?): Int? {
        var v = style[key] ?: return def
        if (v.startsWith("light-dark(")) v = v.removePrefix("light-dark(").substringBefore(',').trim()
        if (v == "none") return null
        if (v == "default" || v == "auto" || v.isEmpty()) return def
        if (v.startsWith("#")) {
            val h = v.substring(1)
            val n = when (h.length) {
                3 -> h.map { "$it$it" }.joinToString("").toLongOrNull(16)?.let { it or 0xFF000000 }
                6 -> h.toLongOrNull(16)?.let { it or 0xFF000000 }
                8 -> h.toLongOrNull(16)?.let { ((it and 0xFF) shl 24) or (it shr 8) } // #RRGGBBAA
                else -> null
            }
            return n?.toInt() ?: def
        }
        return named[v.lowercase()] ?: def
    }

    private val entities = mapOf("lt" to "<", "gt" to ">", "amp" to "&", "quot" to "\"", "apos" to "'", "nbsp" to " ")

    /** HTML の文字(<br> や <div> を含む)を、普通の文字にする。 */
    internal fun htmlToText(html: String): String {
        var s = html
        if (!s.contains('<') && !s.contains('&')) return s
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</(div|p|li|tr|h[1-6])>"), "\n")
        s = s.replace(Regex("<[^>]*>"), "")
        s = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);").replace(s) { m ->
            val g = m.groupValues[1]
            when {
                g.startsWith("#x") || g.startsWith("#X") -> g.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                g.startsWith("#") -> g.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
                else -> entities[g] ?: m.value
            }
        }
        return s.trim('\n', ' ')
    }

    // ---------------------------------------------------------------- 組み立て

    private fun build(name: String, model: Element): Page {
        val rootEl = children(model, "root").firstOrNull() ?: return Page(name, emptyList(), 0f, 0f, 1f, 1f)
        val cells = ArrayList<Cell>()
        for (el in rootEl.childNodes.let { nl -> (0 until nl.length).mapNotNull { nl.item(it) as? Element } }) {
            when (el.tagName) {
                "mxCell" -> cells.add(readCell(el, el.getAttribute("id"), el.getAttribute("value")))
                "object", "UserObject" -> {
                    val inner = children(el, "mxCell").firstOrNull() ?: continue
                    cells.add(readCell(inner, el.getAttribute("id"), el.getAttribute("label")))
                }
            }
        }
        val byId = HashMap<String, Cell>()
        for (c in cells) byId[c.id] = c

        // 図形の左上の位置(親の図形の中に入っているものは、親の位置からの相対)
        val originCache = HashMap<String, Pt>()
        fun originOf(c: Cell, depth: Int = 0): Pt {
            originCache[c.id]?.let { return it }
            val g = c.geo
            val p = byId[c.parent]
            val base = if (p != null && p.vertex && depth < 50) originOf(p, depth + 1) else Pt(0f, 0f)
            val r = if (g == null || !c.vertex) base else Pt(base.x + g.x, base.y + g.y)
            originCache[c.id] = r
            return r
        }
        fun rectOf(c: Cell): FloatArray? {
            val g = c.geo ?: return null
            if (!c.vertex) return null
            val o = originOf(c)
            return floatArrayOf(o.x, o.y, g.w, g.h)
        }

        val items = ArrayList<Item>()
        val edgeMid = HashMap<String, Pt>()
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        fun grow(x: Float, y: Float) {
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }

        // 先に線の通り道を決める(線の上に載る文字の位置に要る)
        val routes = HashMap<String, List<Pt>>()
        for (c in cells) {
            if (!c.edge || !c.visible) continue
            routes[c.id] = routeOf(c, byId, ::rectOf, ::originOf)
        }
        for ((id, pts) in routes) edgeMid[id] = midpoint(pts)

        for (c in cells) {
            if (!c.visible) continue
            if (c.edge) {
                val pts = routes[c.id] ?: continue
                if (pts.size < 2) continue
                val st = c.style
                val stroke = colorOf(st, "strokeColor", 0xFF000000.toInt()) ?: continue
                val endArrow = st["endArrow"] ?: "classic"
                val startArrow = st["startArrow"] ?: "none"
                items.add(
                    Line(
                        pts, stroke, st["strokeWidth"]?.toFloatOrNull() ?: 1f, st["dashed"] == "1",
                        startArrow, endArrow, st["startFill"] != "0", st["endFill"] != "0",
                    ),
                )
                pts.forEach { grow(it.x, it.y) }
                val text = labelText(c)
                if (text.isNotEmpty()) {
                    val m = edgeMid[c.id] ?: continue
                    val off = c.geo?.offset
                    items.add(
                        Label(
                            text, m.x + (off?.x ?: 0f), m.y + (off?.y ?: 0f), colorOf(st, "fontColor", 0xFF000000.toInt()) ?: 0xFF000000.toInt(),
                            st["fontSize"]?.toFloatOrNull() ?: 11f, fontBit(st, 1), fontBit(st, 2),
                        ),
                    )
                }
                continue
            }
            if (!c.vertex) continue
            val parent = byId[c.parent]
            if (parent != null && parent.edge) {
                // 線にぶら下がった文字
                val m = edgeMid[parent.id] ?: continue
                val text = labelText(c)
                if (text.isNotEmpty()) {
                    val off = c.geo?.let { Pt(it.x, it.y) }
                    items.add(
                        Label(
                            text, m.x + (off?.x ?: 0f), m.y + (off?.y ?: 0f), colorOf(c.style, "fontColor", 0xFF000000.toInt()) ?: 0xFF000000.toInt(),
                            c.style["fontSize"]?.toFloatOrNull() ?: 11f, fontBit(c.style, 1), fontBit(c.style, 2),
                        ),
                    )
                }
                continue
            }
            val r = rectOf(c) ?: continue
            if (r[2] <= 0f && r[3] <= 0f) continue
            val shape = shapeOf(c, r)
            items.add(shape)
            grow(r[0], r[1])
            grow(r[0] + r[2], r[1] + r[3])
        }
        if (minX > maxX) {
            minX = 0f
            minY = 0f
            maxX = 1f
            maxY = 1f
        }
        return Page(name, items, minX, minY, maxX, maxY)
    }

    private fun fontBit(st: Map<String, String>, bit: Int) = ((st["fontStyle"]?.toIntOrNull() ?: 0) and bit) != 0

    private fun labelText(c: Cell): String {
        val v = c.value
        return if (c.style["html"] == "1" || v.contains('<')) htmlToText(v) else v
    }

    private fun shapeOf(c: Cell, r: FloatArray): Shape {
        val st = c.style
        val raw = st["shape"] ?: "rect"
        var note: String? = null
        val kind = when {
            st.containsKey("ellipse") || raw == "ellipse" || raw == "doubleEllipse" -> "ellipse"
            raw == "rhombus" -> "rhombus"
            raw == "triangle" -> "triangle"
            raw == "hexagon" -> "hexagon"
            raw == "cylinder" || raw == "cylinder3" -> "cylinder"
            raw == "cloud" -> "cloud"
            raw == "swimlane" -> "swimlane"
            raw == "text" -> "text"
            raw == "image" || st.containsKey("image") -> {
                note = "画像"
                "image"
            }
            raw.startsWith("mxgraph.") -> {
                note = raw.substringAfterLast('.').replace('_', ' ')
                "stencil"
            }
            else -> "rect"
        }
        val isText = kind == "text"
        val defFill = if (isText || kind == "image") null else 0xFFFFFFFF.toInt()
        val defStroke = if (isText) null else 0xFF000000.toInt()
        val header = if (kind == "swimlane") (st["startSize"]?.toFloatOrNull() ?: 23f) else 0f
        return Shape(
            kind, r[0], r[1], r[2], r[3],
            colorOf(st, "fillColor", defFill), colorOf(st, "strokeColor", defStroke), st["strokeWidth"]?.toFloatOrNull() ?: 1f,
            st["dashed"] == "1", st["rounded"] == "1" || st["arcSize"] != null && kind == "rect",
            ((st["opacity"]?.toFloatOrNull() ?: 100f) / 100f).coerceIn(0f, 1f),
            labelText(c), colorOf(st, "fontColor", 0xFF000000.toInt()) ?: 0xFF000000.toInt(), st["fontSize"]?.toFloatOrNull() ?: 12f,
            fontBit(st, 1), fontBit(st, 2), fontBit(st, 4),
            when (st["align"]) { "left" -> -1; "right" -> 1; else -> 0 },
            when (st["verticalAlign"]) { "top" -> -1; "bottom" -> 1; else -> 0 },
            st["whiteSpace"] == "wrap",
            header, st["horizontal"] == "0", note,
        )
    }

    // ---------------------------------------------------------------- 線の通り道

    private enum class Side { LEFT, RIGHT, TOP, BOTTOM }

    private fun sideOfConstraint(x: Float?, y: Float?): Side? = when {
        x == null || y == null -> null
        x <= 0.01f -> Side.LEFT
        x >= 0.99f -> Side.RIGHT
        y <= 0.01f -> Side.TOP
        y >= 0.99f -> Side.BOTTOM
        else -> null
    }

    private fun sidePoint(r: FloatArray, s: Side) = when (s) {
        Side.LEFT -> Pt(r[0], r[1] + r[3] / 2)
        Side.RIGHT -> Pt(r[0] + r[2], r[1] + r[3] / 2)
        Side.TOP -> Pt(r[0] + r[2] / 2, r[1])
        Side.BOTTOM -> Pt(r[0] + r[2] / 2, r[1] + r[3])
    }

    /** 図形の中心から target に向かう線が、図形のふちと交わる点。 */
    private fun perimeter(r: FloatArray, kind: String, t: Pt): Pt {
        val cx = r[0] + r[2] / 2
        val cy = r[1] + r[3] / 2
        val dx = t.x - cx
        val dy = t.y - cy
        if (dx == 0f && dy == 0f) return Pt(cx, cy)
        val rx = r[2] / 2
        val ry = r[3] / 2
        val k = when (kind) {
            "ellipse", "cloud" -> 1f / kotlin.math.sqrt((dx / rx) * (dx / rx) + (dy / ry) * (dy / ry))
            "rhombus" -> 1f / (kotlin.math.abs(dx) / rx + kotlin.math.abs(dy) / ry)
            else -> minOf(if (dx == 0f) Float.MAX_VALUE else rx / kotlin.math.abs(dx), if (dy == 0f) Float.MAX_VALUE else ry / kotlin.math.abs(dy))
        }
        return if (k >= 1f) Pt(cx, cy) else Pt(cx + dx * k, cy + dy * k)
    }

    private fun kindOfCell(c: Cell): String = when {
        c.style.containsKey("ellipse") || c.style["shape"] == "ellipse" -> "ellipse"
        c.style["shape"] == "rhombus" -> "rhombus"
        else -> "rect"
    }

    private fun routeOf(
        c: Cell, byId: Map<String, Cell>, rectOf: (Cell) -> FloatArray?, originOf: (Cell, Int) -> Pt,
    ): List<Pt> {
        val st = c.style
        val g = c.geo
        val src = c.source?.let { byId[it] }
        val dst = c.target?.let { byId[it] }
        val sr = src?.let { rectOf(it) }
        val tr = dst?.let { rectOf(it) }
        val base = byId[c.parent]?.takeIf { it.vertex }?.let { originOf(it, 0) } ?: Pt(0f, 0f)
        val way = (g?.points ?: emptyList()).map { Pt(it.x + base.x, it.y + base.y) }
        val sp = g?.sourcePoint?.let { Pt(it.x + base.x, it.y + base.y) }
        val tp = g?.targetPoint?.let { Pt(it.x + base.x, it.y + base.y) }

        val exitX = st["exitX"]?.toFloatOrNull()
        val exitY = st["exitY"]?.toFloatOrNull()
        val entryX = st["entryX"]?.toFloatOrNull()
        val entryY = st["entryY"]?.toFloatOrNull()
        val orth = st["edgeStyle"] == "orthogonalEdgeStyle" || st["edgeStyle"] == "elbowEdgeStyle" || st["edgeStyle"] == "isometricEdgeStyle"

        fun constrained(r: FloatArray, x: Float, y: Float, dx: Float, dy: Float) = Pt(r[0] + r[2] * x + dx, r[1] + r[3] * y + dy)
        val exitDx = st["exitDx"]?.toFloatOrNull() ?: 0f
        val exitDy = st["exitDy"]?.toFloatOrNull() ?: 0f
        val entryDx = st["entryDx"]?.toFloatOrNull() ?: 0f
        val entryDy = st["entryDy"]?.toFloatOrNull() ?: 0f

        val sCenter = sr?.let { Pt(it[0] + it[2] / 2, it[1] + it[3] / 2) }
        val tCenter = tr?.let { Pt(it[0] + it[2] / 2, it[1] + it[3] / 2) }
        // 端の仮の位置(図形がなければ、線の端の点)
        val sRef = sCenter ?: sp ?: way.firstOrNull() ?: tCenter ?: tp ?: return emptyList()
        val tRef = tCenter ?: tp ?: way.lastOrNull() ?: sRef

        var sSide = sideOfConstraint(exitX, exitY)
        var tSide = sideOfConstraint(entryX, entryY)
        if (orth && way.isEmpty()) {
            val dx = tRef.x - sRef.x
            val dy = tRef.y - sRef.y
            if (sSide == null && sr != null) sSide = if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) (if (dx >= 0) Side.RIGHT else Side.LEFT) else (if (dy >= 0) Side.BOTTOM else Side.TOP)
            if (tSide == null && tr != null) tSide = if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) (if (dx >= 0) Side.LEFT else Side.RIGHT) else (if (dy >= 0) Side.TOP else Side.BOTTOM)
        }

        val start: Pt = when {
            sr != null && exitX != null && exitY != null -> constrained(sr, exitX, exitY, exitDx, exitDy)
            sr != null && orth && way.isEmpty() && sSide != null -> sidePoint(sr, sSide)
            sr != null -> perimeter(sr, kindOfCell(src), way.firstOrNull() ?: tRef)
            else -> sp ?: sRef
        }
        val end: Pt = when {
            tr != null && entryX != null && entryY != null -> constrained(tr, entryX, entryY, entryDx, entryDy)
            tr != null && orth && way.isEmpty() && tSide != null -> sidePoint(tr, tSide)
            tr != null -> perimeter(tr, kindOfCell(dst), way.lastOrNull() ?: start)
            else -> tp ?: tRef
        }

        val pts = ArrayList<Pt>()
        pts.add(start)
        if (orth && way.isEmpty()) {
            pts.addAll(elbows(start, end, sSide, tSide))
        } else if (orth) {
            // 途中の点がある: 点と点の間を、縦と横だけでつなぐ
            var prev = start
            for (w in way + end) {
                if (prev.x != w.x && prev.y != w.y) pts.add(Pt(w.x, prev.y))
                pts.add(w)
                prev = w
            }
            return dedupe(pts)
        } else {
            pts.addAll(way)
        }
        pts.add(end)
        return dedupe(pts)
    }

    /** start と end の間の折れ曲がりの点(縦と横だけの線)。 */
    private fun elbows(s: Pt, e: Pt, ss: Side?, ts: Side?): List<Pt> {
        val sh = ss == Side.LEFT || ss == Side.RIGHT
        val th = ts == Side.LEFT || ts == Side.RIGHT
        val sv = ss == Side.TOP || ss == Side.BOTTOM
        val tv = ts == Side.TOP || ts == Side.BOTTOM
        return when {
            sh && th -> {
                val facing = (ss == Side.RIGHT && ts == Side.LEFT && e.x > s.x) || (ss == Side.LEFT && ts == Side.RIGHT && e.x < s.x)
                val m = if (facing) (s.x + e.x) / 2 else if (ss == Side.RIGHT) maxOf(s.x, e.x) + 20 else minOf(s.x, e.x) - 20
                listOf(Pt(m, s.y), Pt(m, e.y))
            }
            sv && tv -> {
                val facing = (ss == Side.BOTTOM && ts == Side.TOP && e.y > s.y) || (ss == Side.TOP && ts == Side.BOTTOM && e.y < s.y)
                val m = if (facing) (s.y + e.y) / 2 else if (ss == Side.BOTTOM) maxOf(s.y, e.y) + 20 else minOf(s.y, e.y) - 20
                listOf(Pt(s.x, m), Pt(e.x, m))
            }
            sh && tv -> listOf(Pt(e.x, s.y))
            sv && th -> listOf(Pt(s.x, e.y))
            else -> if (s.x != e.x && s.y != e.y) listOf(Pt(e.x, s.y)) else emptyList()
        }
    }

    private fun dedupe(p: List<Pt>): List<Pt> {
        val out = ArrayList<Pt>(p.size)
        for (q in p) if (out.isEmpty() || out.last() != q) out.add(q)
        return out
    }

    /** 線の長さの真ん中の点。 */
    internal fun midpoint(p: List<Pt>): Pt {
        if (p.isEmpty()) return Pt(0f, 0f)
        if (p.size == 1) return p[0]
        var total = 0f
        for (i in 1 until p.size) total += dist(p[i - 1], p[i])
        var left = total / 2
        for (i in 1 until p.size) {
            val d = dist(p[i - 1], p[i])
            if (left <= d && d > 0f) {
                val t = left / d
                return Pt(p[i - 1].x + (p[i].x - p[i - 1].x) * t, p[i - 1].y + (p[i].y - p[i - 1].y) * t)
            }
            left -= d
        }
        return p.last()
    }

    private fun dist(a: Pt, b: Pt) = kotlin.math.sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y))
}
