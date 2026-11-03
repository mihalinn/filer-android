package local.filemanager

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** DXF(AutoCAD の交換形式。文字で書かれた版)を CadDrawing にする。Android に依存しない。 */
object Dxf {
    const val MAX_BYTES = 64L * 1024 * 1024

    // ---------------------------------------------------------------- 読み込みの準備

    fun parse(bytes: ByteArray): CadDrawing {
        if (bytes.size >= 22 && String(bytes, 0, 22, Charsets.ISO_8859_1).startsWith("AutoCAD Binary DXF")) {
            throw OpException("バイナリ形式の DXF には対応していません")
        }
        val text = decode(bytes)
        val ents = readEntities(text)
        return Converter(ents).convert()
    }

    /** 文字コード: R2007 以降(AC1021〜)は UTF-8、それ以前は $DWGCODEPAGE(日本語なら ANSI_932 = Shift_JIS) */
    internal fun decode(bytes: ByteArray): String {
        val head = String(bytes, 0, minOf(bytes.size, 64 * 1024), Charsets.ISO_8859_1)
        fun headerValue(name: String): String? {
            val i = head.indexOf(name)
            if (i < 0) return null
            val lines = head.substring(i + name.length).split('\n')
            // 名前の次の行が「グループコード」、その次が値
            return lines.getOrNull(2)?.trim() ?: lines.getOrNull(1)?.trim()
        }
        val ver = headerValue("\$ACADVER")?.takeIf { it.startsWith("AC") }?.substring(2)?.toIntOrNull() ?: 0
        val cp = headerValue("\$DWGCODEPAGE")?.uppercase()
        bom(bytes)?.let { (cs, skip) -> return String(bytes, skip, bytes.size - skip, cs) }
        if (ver >= 1021) {
            strict(bytes, Charsets.UTF_8)?.let { return it }
        }
        val cs = codepage(cp)
        if (cs != null) strict(bytes, cs)?.let { return it }
        return TextDecode.decode(bytes).text
    }

    private fun bom(b: ByteArray): Pair<Charset, Int>? = when {
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> Charsets.UTF_8 to 3
        else -> null
    }

    private fun codepage(cp: String?): Charset? {
        val name = when {
            cp == null -> return null
            cp == "ANSI_932" -> "windows-31j"
            cp == "ANSI_936" -> "GBK"
            cp == "ANSI_949" -> "x-windows-949"
            cp == "ANSI_950" -> "Big5"
            cp == "ANSI_874" -> "windows-874"
            cp.startsWith("ANSI_125") -> "windows-" + cp.removePrefix("ANSI_")
            cp == "UTF-8" || cp == "UTF8" -> "UTF-8"
            else -> return null
        }
        return try { Charset.forName(name) } catch (e: Exception) { null }
    }

    private fun strict(bytes: ByteArray, cs: Charset): String? = try {
        cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        null
    }

    // ---------------------------------------------------------------- グループコードの列

    /** 1 つの要素(LINE や LAYER など)のグループコードと値 */
    internal class Ent(val type: String) {
        var codes = IntArray(16)
        val values = ArrayList<String>()
        val size: Int get() = values.size

        fun add(code: Int, value: String) {
            if (values.size == codes.size) codes = codes.copyOf(codes.size * 2)
            codes[values.size] = code
            values.add(value)
        }

        fun str(code: Int): String? {
            for (i in 0 until size) if (codes[i] == code) return values[i]
            return null
        }

        fun d(code: Int, def: Double = 0.0): Double = str(code)?.trim()?.toDoubleOrNull() ?: def
        fun i(code: Int, def: Int = 0): Int = str(code)?.trim()?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() } ?: def
        fun has(code: Int): Boolean = (0 until size).any { codes[it] == code }
        fun all(code: Int): List<String> = (0 until size).filter { codes[it] == code }.map { values[it] }
        fun allD(code: Int): DoubleArray = all(code).map { it.trim().toDoubleOrNull() ?: 0.0 }.toDoubleArray()
    }

    internal class Doc(
        val layers: List<Ent>,
        val blocks: Map<String, Pair<Ent, List<Ent>>>,
        val entities: List<Ent>,
    )

    internal fun readEntities(text: String): Doc {
        val layers = ArrayList<Ent>()
        val blocks = LinkedHashMap<String, Pair<Ent, List<Ent>>>()
        val entities = ArrayList<Ent>()
        var section = ""
        var cur: Ent? = null
        var block: Ent? = null
        var blockEnts: ArrayList<Ent>? = null

        fun flush() {
            val e = cur ?: return
            cur = null
            when (section) {
                "TABLES" -> if (e.type == "LAYER") layers.add(e)
                "BLOCKS" -> when (e.type) {
                    "BLOCK" -> { block = e; blockEnts = ArrayList() }
                    "ENDBLK" -> {
                        val b = block
                        if (b != null) blocks[b.str(2) ?: b.str(3) ?: ""] = b to (blockEnts ?: ArrayList())
                        block = null; blockEnts = null
                    }
                    else -> blockEnts?.add(e)
                }
                "ENTITIES" -> entities.add(e)
            }
        }

        var pos = 0
        val n = text.length
        var pendingSection = false
        while (pos < n) {
            // 1 行目: グループコード
            var eol = text.indexOf('\n', pos)
            if (eol < 0) break
            val code = text.substring(pos, eol).trim().toIntOrNull()
            pos = eol + 1
            if (pos > n) break
            eol = text.indexOf('\n', pos)
            if (eol < 0) eol = n
            var value = text.substring(pos, eol)
            if (value.endsWith("\r")) value = value.substring(0, value.length - 1)
            pos = eol + 1
            if (code == null) continue
            if (code == 0) {
                val v = value.trim()
                flush()
                when (v) {
                    "SECTION" -> pendingSection = true
                    "ENDSEC" -> section = ""
                    "EOF" -> { pos = n }
                    else -> if (section == "TABLES" || section == "BLOCKS" || section == "ENTITIES") cur = Ent(v)
                }
            } else if (pendingSection && code == 2) {
                section = value.trim().uppercase()
                pendingSection = false
            } else {
                cur?.add(code, value)
            }
        }
        flush()
        return Doc(layers, blocks, entities)
    }

    // ---------------------------------------------------------------- 変換

    private class Ctx(val xf: Xf, val layer: String?, val color: Int, val depth: Int)

    private class Converter(val doc: Doc) {
        val b = CadBuilder()
        val layerColors = HashMap<String, Int>()
        var skipped = 0

        fun convert(): CadDrawing {
            for (l in doc.layers) {
                val name = l.str(2) ?: continue
                val c = l.i(62, 7)
                val tc = if (l.has(420)) 0xFF000000.toInt() or (l.i(420) and 0xFFFFFF) else 0
                layerColors[name] = if (tc != 0) tc else Aci.rgb(abs(c))
                val off = c < 0 || (l.i(70) and 1) != 0
                b.layer(name, !off)
            }
            run(doc.entities, Ctx(Xf.ID, null, 0, 0))
            if (b.shapes == 0 && skipped > 0) throw OpException("このファイルの図形には対応していません(${skipped} 個)")
            val note = if (skipped > 0) "対応していない図形が ${skipped} 個あります" else ""
            return b.build(note)
        }

        fun layerId(name: String): Int = b.layer(name, true)

        fun color(e: Ent, layerName: String, ctx: Ctx): Int {
            if (e.has(420)) return 0xFF000000.toInt() or (e.i(420) and 0xFFFFFF)
            val aci = e.i(62, 256)
            return when {
                aci == 256 -> layerColors[layerName] ?: 0
                aci == 0 -> if (ctx.depth > 0) ctx.color else (layerColors[layerName] ?: 0)
                else -> Aci.rgb(abs(aci))
            }
        }

        /** 押し出し方向が下向き(0,0,-1)の図形は、x が逆向き */
        fun mirror(e: Ent): Boolean = e.has(230) && e.d(230) < 0 && abs(e.d(210)) < 1e-9 && abs(e.d(220)) < 1e-9

        fun run(list: List<Ent>, ctx: Ctx) {
            var i = 0
            while (i < list.size) {
                val e = list[i]
                i++
                try {
                    when (e.type) {
                        "POLYLINE" -> {
                            val verts = ArrayList<Ent>()
                            while (i < list.size && list[i].type != "SEQEND") {
                                if (list[i].type == "VERTEX") verts.add(list[i])
                                i++
                            }
                            if (i < list.size) i++ // SEQEND
                            polyline(e, verts, ctx)
                        }
                        "INSERT" -> {
                            val attribs = ArrayList<Ent>()
                            if (e.i(66) == 1) {
                                while (i < list.size && list[i].type != "SEQEND") {
                                    if (list[i].type == "ATTRIB") attribs.add(list[i])
                                    i++
                                }
                                if (i < list.size) i++
                            }
                            insert(e, ctx)
                            for (a in attribs) text(a, ctx)
                        }
                        else -> one(e, ctx)
                    }
                } catch (ex: OpException) {
                    throw ex
                } catch (ex: Exception) {
                    skipped++
                }
            }
        }

        fun layerOf(e: Ent, ctx: Ctx): String {
            val n = e.str(8) ?: "0"
            return if (n == "0" && ctx.layer != null) ctx.layer else n
        }

        fun one(e: Ent, ctx: Ctx) {
            if (e.i(67) == 1) return // 用紙空間
            if (e.type == "VERTEX" || e.type == "SEQEND" || e.type == "ATTDEF") return
            val ln = layerOf(e, ctx)
            val xf = ctx.xf
            when (e.type) {
                "LINE" -> {
                    val l = layerId(ln)
                    b.line(l, color(e, ln, ctx), xf.x(e.d(10), e.d(20)), xf.y(e.d(10), e.d(20)), xf.x(e.d(11), e.d(21)), xf.y(e.d(11), e.d(21)))
                }
                "CIRCLE" -> {
                    val m = mirror(e)
                    val pts = CadGeo.arc(if (m) -e.d(10) else e.d(10), e.d(20), e.d(40), 0.0, Math.PI * 2)
                    emitPoly(e, ln, ctx, pts, pts.size / 2, true)
                }
                "ARC" -> {
                    val m = mirror(e)
                    val a0 = Math.toRadians(e.d(50))
                    var sweep = Math.toRadians(e.d(51) - e.d(50))
                    if (sweep <= 0) sweep += Math.PI * 2
                    var pts = CadGeo.arc(e.d(10), e.d(20), e.d(40), a0, sweep)
                    if (m) pts = mirrorX(pts)
                    emitPoly(e, ln, ctx, pts, pts.size / 2, false)
                }
                "ELLIPSE" -> {
                    val mx = e.d(11); val my = e.d(21)
                    val rx = hypot(mx, my)
                    val ry = rx * e.d(40, 1.0)
                    val t0 = e.d(41, 0.0)
                    var t1 = e.d(42, Math.PI * 2)
                    var sweep = t1 - t0
                    if (sweep <= 0) sweep += Math.PI * 2
                    val pts = CadGeo.ellipse(e.d(10), e.d(20), rx, ry, atan2(my, mx), t0, sweep)
                    emitPoly(e, ln, ctx, pts, pts.size / 2, false)
                }
                "LWPOLYLINE" -> lwpolyline(e, ln, ctx)
                "SPLINE" -> spline(e, ln, ctx)
                "TEXT", "MTEXT", "ATTRIB" -> text(e, ctx)
                "SOLID", "TRACE" -> {
                    val p = arrayOf(0, 1, 2, 3).map { doubleArrayOf(e.d(10 + it), e.d(20 + it)) }
                    // 4 点目が無ければ 3 点目と同じ(三角形)。点の並びは 1・2・4・3 の順で囲む
                    val order = if (e.has(13)) listOf(0, 1, 3, 2) else listOf(0, 1, 2)
                    val loop = DoubleArray(order.size * 2)
                    order.forEachIndexed { k, idx -> loop[k * 2] = xf.x(p[idx][0], p[idx][1]); loop[k * 2 + 1] = xf.y(p[idx][0], p[idx][1]) }
                    b.fill(layerId(ln), color(e, ln, ctx), listOf(loop))
                }
                "3DFACE" -> {
                    val ids = if (e.has(13) && (e.d(13) != e.d(12) || e.d(23) != e.d(22))) 4 else 3
                    val pts = DoubleArray(ids * 2)
                    for (k in 0 until ids) { pts[k * 2] = xf.x(e.d(10 + k), e.d(20 + k)); pts[k * 2 + 1] = xf.y(e.d(10 + k), e.d(20 + k)) }
                    b.poly(layerId(ln), color(e, ln, ctx), pts, ids, true)
                }
                "HATCH" -> hatch(e, ln, ctx)
                "LEADER" -> {
                    val xs = e.allD(10); val ys = e.allD(20)
                    val n = minOf(xs.size, ys.size)
                    val pts = DoubleArray(n * 2)
                    for (k in 0 until n) { pts[k * 2] = xf.x(xs[k], ys[k]); pts[k * 2 + 1] = xf.y(xs[k], ys[k]) }
                    b.poly(layerId(ln), color(e, ln, ctx), pts, n, false)
                }
                "POINT" -> b.point(layerId(ln), color(e, ln, ctx), xf.x(e.d(10), e.d(20)), xf.y(e.d(10), e.d(20)))
                // 寸法線と表は、図形の集まり(無名のブロック)として保存されている
                "DIMENSION", "ACAD_TABLE" -> {
                    val name = e.str(2)
                    val blk = if (name != null) doc.blocks[name] else null
                    if (blk != null && ctx.depth < MAX_DEPTH) {
                        val shift = Xf(1.0, 0.0, 0.0, 1.0, -blk.first.d(10), -blk.first.d(20))
                        run(blk.second, Ctx(shift.then(xf), ln.takeIf { it != "0" } ?: ctx.layer, color(e, ln, ctx), ctx.depth + 1))
                    } else skipped++
                }
                "XLINE", "RAY", "IMAGE", "VIEWPORT", "WIPEOUT", "OLE2FRAME", "MLINE", "MESH", "3DSOLID", "BODY", "REGION", "SURFACE", "LIGHT", "UNDERLAY", "TOLERANCE", "SEQEND" -> {}
                else -> skipped++
            }
        }

        fun mirrorX(p: DoubleArray): DoubleArray {
            val o = p.copyOf()
            var i = 0
            while (i < o.size) { o[i] = -o[i]; i += 2 }
            return o
        }

        /** 図形ごとの座標(図面の座標)を変換して、線として出す */
        fun emitPoly(e: Ent, ln: String, ctx: Ctx, pts: DoubleArray, count: Int, closed: Boolean) {
            val out = DoubleArray(count * 2)
            for (k in 0 until count) { out[k * 2] = ctx.xf.x(pts[k * 2], pts[k * 2 + 1]); out[k * 2 + 1] = ctx.xf.y(pts[k * 2], pts[k * 2 + 1]) }
            b.poly(layerId(ln), color(e, ln, ctx), out, count, closed)
        }

        fun lwpolyline(e: Ent, ln: String, ctx: Ctx) {
            val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val bu = ArrayList<Double>()
            for (k in 0 until e.size) {
                when (e.codes[k]) {
                    10 -> { xs.add(e.values[k].trim().toDoubleOrNull() ?: 0.0); ys.add(0.0); bu.add(0.0) }
                    20 -> if (ys.isNotEmpty()) ys[ys.size - 1] = e.values[k].trim().toDoubleOrNull() ?: 0.0
                    42 -> if (bu.isNotEmpty()) bu[bu.size - 1] = e.values[k].trim().toDoubleOrNull() ?: 0.0
                }
            }
            val closed = (e.i(70) and 1) != 0
            polyPoints(e, ln, ctx, xs, ys, bu, closed, mirror(e))
        }

        fun polyPoints(e: Ent, ln: String, ctx: Ctx, xs: List<Double>, ys: List<Double>, bu: List<Double>, closed: Boolean, mir: Boolean) {
            val n = xs.size
            if (n < 2) return
            val out = ArrayList<Double>(n * 2)
            val segs = if (closed) n else n - 1
            for (k in 0 until segs) {
                val x1 = xs[k]; val y1 = ys[k]
                val x2 = xs[(k + 1) % n]; val y2 = ys[(k + 1) % n]
                val bg = bu[k]
                if (abs(bg) > 1e-9) {
                    val arc = CadGeo.bulge(x1, y1, x2, y2, bg)
                    for (v in arc) out.add(v)
                } else {
                    out.add(x1); out.add(y1)
                }
            }
            if (!closed) { out.add(xs[n - 1]); out.add(ys[n - 1]) }
            var arr = out.toDoubleArray()
            if (mir) arr = mirrorX(arr)
            emitPoly(e, ln, ctx, arr, arr.size / 2, closed)
        }

        fun polyline(h: Ent, verts: List<Ent>, ctx: Ctx) {
            if (h.i(67) == 1) return
            val ln = layerOf(h, ctx)
            val flags = h.i(70)
            if ((flags and 64) != 0) {
                // ポリフェース メッシュ: 面の辺を線にする
                val pts = ArrayList<DoubleArray>()
                val faces = ArrayList<IntArray>()
                for (v in verts) {
                    val f = v.i(70)
                    if ((f and 128) != 0 && (f and 64) == 0) faces.add(intArrayOf(v.i(71), v.i(72), v.i(73), v.i(74)))
                    else pts.add(doubleArrayOf(v.d(10), v.d(20)))
                }
                for (face in faces) {
                    val idx = face.filter { it != 0 }.map { abs(it) - 1 }.filter { it in pts.indices }
                    if (idx.size < 2) continue
                    val arr = DoubleArray(idx.size * 2)
                    idx.forEachIndexed { k, id -> arr[k * 2] = pts[id][0]; arr[k * 2 + 1] = pts[id][1] }
                    emitPoly(h, ln, ctx, arr, idx.size, true)
                }
                return
            }
            if ((flags and 16) != 0) return // 多角形メッシュは対応しない
            val vs = verts.filter { (it.i(70) and 16) == 0 }
            polyPoints(
                h, ln, ctx, vs.map { it.d(10) }, vs.map { it.d(20) }, vs.map { it.d(42) },
                (flags and 1) != 0, mirror(h),
            )
        }

        fun spline(e: Ent, ln: String, ctx: Ctx) {
            val deg = e.i(71, 3)
            val knots = e.allD(40)
            val cx = ArrayList<Double>(); val cy = ArrayList<Double>(); val cw = e.allD(41)
            val fx = ArrayList<Double>(); val fy = ArrayList<Double>()
            for (k in 0 until e.size) {
                when (e.codes[k]) {
                    10 -> { cx.add(e.values[k].trim().toDoubleOrNull() ?: 0.0); cy.add(0.0) }
                    20 -> if (cy.isNotEmpty() && cy.size == cx.size && fx.size == 0) cy[cy.size - 1] = e.values[k].trim().toDoubleOrNull() ?: 0.0
                    11 -> { fx.add(e.values[k].trim().toDoubleOrNull() ?: 0.0); fy.add(0.0) }
                    21 -> if (fy.isNotEmpty()) fy[fy.size - 1] = e.values[k].trim().toDoubleOrNull() ?: 0.0
                }
            }
            val pts: DoubleArray = if (cx.size > deg && knots.size == cx.size + deg + 1) {
                CadSpline.eval(deg, knots, cx.toDoubleArray(), cy.toDoubleArray(), if (cw.size == cx.size) cw else null)
            } else if (fx.size >= 2) {
                DoubleArray(fx.size * 2) { if (it % 2 == 0) fx[it / 2] else fy[it / 2] }
            } else {
                DoubleArray(cx.size * 2) { if (it % 2 == 0) cx[it / 2] else cy[it / 2] }
            }
            emitPoly(e, ln, ctx, pts, pts.size / 2, (e.i(70) and 1) != 0)
        }

        fun text(e: Ent, ctx: Ctx) {
            if (e.i(67) == 1) return
            if (e.type == "ATTRIB" && (e.i(70) and 1) != 0) return // 非表示の属性
            val ln = layerOf(e, ctx)
            val xf = ctx.xf
            val color = color(e, ln, ctx)
            if (e.type == "MTEXT") {
                val raw = (e.all(3).joinToString("") + (e.str(1) ?: ""))
                val txt = DxfText.mtext(raw)
                var rot = e.d(50)
                if (e.has(11) || e.has(21)) rot = Math.toDegrees(atan2(e.d(21), e.d(11)))
                val ap = e.i(71, 1).coerceIn(1, 9)
                val h = (ap - 1) % 3
                val v = when ((ap - 1) / 3) { 0 -> 3; 1 -> 2; else -> 1 }
                val x = e.d(10); val y = e.d(20)
                b.text(layerId(ln), color, xf.x(x, y), xf.y(x, y), e.d(40, 2.5) * xf.scale, Math.toDegrees(xf.rotation) + rot, txt, h, v)
                return
            }
            val txt = DxfText.clean(e.str(1) ?: "")
            if (txt.isEmpty()) return
            val ha = e.i(72); val va = e.i(73)
            val useAlign = (ha != 0 || va != 0) && e.has(11) && ha != 3 && ha != 5
            val px = if (useAlign) e.d(11) else e.d(10)
            val py = if (useAlign) e.d(21) else e.d(20)
            val h = when (ha) { 1, 4 -> 1; 2 -> 2; else -> 0 }
            val v = when (va) { 1 -> 1; 2 -> 2; 3 -> 3; else -> 0 }
            val vAlign = if (ha == 4) 2 else v
            val m = mirror(e)
            val x = if (m) -px else px
            b.text(layerId(ln), color, xf.x(x, py), xf.y(x, py), e.d(40, 2.5) * xf.scale, Math.toDegrees(xf.rotation) + (if (m) 180 - e.d(50) else e.d(50)), txt, h, vAlign, e.d(41, 1.0))
        }

        fun insert(e: Ent, ctx: Ctx) {
            if (e.i(67) == 1) return
            if (ctx.depth >= MAX_DEPTH) return
            val name = e.str(2) ?: return
            val blk = doc.blocks[name] ?: return
            val ln = layerOf(e, ctx)
            val sx = e.d(41, 1.0).let { if (it == 0.0) 1.0 else it }
            val sy = e.d(42, 1.0).let { if (it == 0.0) 1.0 else it }
            val rot = Math.toRadians(e.d(50))
            val cols = e.i(70, 1).coerceAtLeast(1)
            val rows = e.i(71, 1).coerceAtLeast(1)
            if (cols.toLong() * rows > 10_000) return
            val dc = e.d(44); val dr = e.d(45)
            val bx = blk.first.d(10); val by = blk.first.d(20)
            var ix = e.d(10); var iy = e.d(20)
            if (mirror(e)) ix = -ix
            val cr = cos(rot); val sr = sin(rot)
            val c = color(e, ln, ctx)
            for (r in 0 until rows) {
                for (cc in 0 until cols) {
                    // ブロックの基点を原点に → 拡大 → 回転 → 挿入点 + 配列の間隔(回転後)
                    val ox = ix + (cc * dc) * cr - (r * dr) * sr
                    val oy = iy + (cc * dc) * sr + (r * dr) * cr
                    val local = Xf(sx * cr, sx * sr, -sy * sr, sy * cr, ox, oy)
                    val shift = Xf(1.0, 0.0, 0.0, 1.0, -bx, -by)
                    run(blk.second, Ctx(shift.then(local).then(ctx.xf), ln.takeIf { it != "0" } ?: ctx.layer, c, ctx.depth + 1))
                }
            }
        }

        fun hatch(e: Ent, ln: String, ctx: Ctx) {
            val solid = e.i(70) == 1
            var k = 0
            while (k < e.size && e.codes[k] != 91) k++
            if (k >= e.size) return
            val pathCount = e.values[k].trim().toIntOrNull() ?: return
            k++
            val loops = ArrayList<DoubleArray>()
            fun num(code: Int): Double? {
                // 次に現れる code の値を読み、その次の位置へ進む(間に別のコードがあれば読み飛ばさず失敗)
                if (k < e.size && e.codes[k] == code) { val v = e.values[k].trim().toDoubleOrNull(); k++; return v }
                return null
            }
            for (p in 0 until pathCount) {
                while (k < e.size && e.codes[k] != 92) {
                    if (e.codes[k] == 75 || e.codes[k] == 76) { finishHatch(e, ln, ctx, loops, solid); return }
                    k++
                }
                if (k >= e.size) break
                val flags = e.values[k].trim().toIntOrNull() ?: 0
                k++
                val pts = ArrayList<Double>()
                if ((flags and 2) != 0) {
                    val hasBulge = (num(72) ?: 0.0).toInt() != 0
                    val closed = (num(73) ?: 1.0).toInt() != 0
                    val nv = (num(93) ?: 0.0).toInt()
                    val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val bu = ArrayList<Double>()
                    for (v in 0 until nv) {
                        val x = num(10) ?: break
                        val y = num(20) ?: break
                        val bg = if (hasBulge) (num(42) ?: 0.0) else 0.0
                        xs.add(x); ys.add(y); bu.add(bg)
                    }
                    for (q in xs.indices) {
                        val nx = xs.getOrNull(q + 1) ?: if (closed) xs[0] else null
                        val ny = ys.getOrNull(q + 1) ?: if (closed) ys[0] else null
                        if (nx != null && ny != null && abs(bu[q]) > 1e-9) for (v in CadGeo.bulge(xs[q], ys[q], nx, ny, bu[q])) pts.add(v)
                        else { pts.add(xs[q]); pts.add(ys[q]) }
                    }
                } else {
                    val ne = (num(93) ?: 0.0).toInt()
                    for (q in 0 until ne) {
                        when ((num(72) ?: break).toInt()) {
                            1 -> {
                                val x1 = num(10) ?: break; val y1 = num(20) ?: break; val x2 = num(11) ?: break; val y2 = num(21) ?: break
                                pts.add(x1); pts.add(y1); pts.add(x2); pts.add(y2)
                            }
                            2 -> {
                                val cx = num(10) ?: break; val cy = num(20) ?: break; val r = num(40) ?: break
                                val a0 = num(50) ?: break; val a1 = num(51) ?: break; val ccw = (num(73) ?: 1.0).toInt() != 0
                                val s = Math.toRadians(a0)
                                var sweep = Math.toRadians(a1 - a0)
                                if (ccw) { if (sweep <= 0) sweep += Math.PI * 2 } else { if (sweep >= 0) sweep -= Math.PI * 2 }
                                for (v in CadGeo.arc(cx, cy, r, s, sweep)) pts.add(v)
                            }
                            3 -> {
                                val cx = num(10) ?: break; val cy = num(20) ?: break; val mx = num(11) ?: break; val my = num(21) ?: break
                                val ratio = num(40) ?: break; val a0 = num(50) ?: break; val a1 = num(51) ?: break; val ccw = (num(73) ?: 1.0).toInt() != 0
                                val rx = hypot(mx, my)
                                var sweep = Math.toRadians(a1 - a0)
                                if (ccw) { if (sweep <= 0) sweep += Math.PI * 2 } else { if (sweep >= 0) sweep -= Math.PI * 2 }
                                for (v in CadGeo.ellipse(cx, cy, rx, rx * ratio, atan2(my, mx), Math.toRadians(a0), sweep)) pts.add(v)
                            }
                            4 -> {
                                val deg = (num(94) ?: break).toInt()
                                val rational = (num(73) ?: 0.0).toInt() != 0
                                num(74)
                                val nk = (num(95) ?: break).toInt()
                                val nc = (num(96) ?: break).toInt()
                                val kn = DoubleArray(nk) { num(40) ?: 0.0 }
                                val cx = DoubleArray(nc); val cy = DoubleArray(nc); val w = DoubleArray(nc) { 1.0 }
                                for (c in 0 until nc) {
                                    cx[c] = num(10) ?: 0.0; cy[c] = num(20) ?: 0.0
                                    if (rational) w[c] = num(42) ?: 1.0
                                }
                                val ev = if (nc > deg && nk == nc + deg + 1) CadSpline.eval(deg, kn, cx, cy, if (rational) w else null)
                                else DoubleArray(nc * 2) { if (it % 2 == 0) cx[it / 2] else cy[it / 2] }
                                for (v in ev) pts.add(v)
                                // 近似曲線の点(fit)が続くことがある
                                val nf = (num(97) ?: 0.0).toInt()
                                for (f in 0 until nf) { num(11); num(21) }
                            }
                            else -> break
                        }
                    }
                }
                if (pts.size >= 6) loops.add(pts.toDoubleArray())
                // 境界オブジェクトの数(97)と参照(330)は読み飛ばす
                while (k < e.size && e.codes[k] != 92 && e.codes[k] != 75) k++
            }
            finishHatch(e, ln, ctx, loops, solid)
        }

        fun finishHatch(e: Ent, ln: String, ctx: Ctx, loops: List<DoubleArray>, solid: Boolean) {
            if (loops.isEmpty()) return
            val xf = ctx.xf
            val tr = loops.map { l -> DoubleArray(l.size) { if (it % 2 == 0) xf.x(l[it], l[it + 1]) else xf.y(l[it - 1], l[it]) } }
            val color = color(e, ln, ctx)
            val layer = layerId(ln)
            if (solid) b.fill(layer, color, tr)
            else for (l in tr) b.poly(layer, color, l, l.size / 2, true)
        }
    }

    private const val MAX_DEPTH = 12
}

/** B スプライン(有理も可)を折れ線にする */
internal object CadSpline {
    fun eval(degree: Int, knots: DoubleArray, cx: DoubleArray, cy: DoubleArray, w: DoubleArray?): DoubleArray {
        val n = cx.size - 1
        val p = degree
        val t0 = knots[p]; val t1 = knots[n + 1]
        if (!(t1 > t0)) return DoubleArray(cx.size * 2) { if (it % 2 == 0) cx[it / 2] else cy[it / 2] }
        val samples = (cx.size * 10).coerceIn(32, 4000)
        val out = DoubleArray((samples + 1) * 2)
        val dx = DoubleArray(p + 1); val dy = DoubleArray(p + 1); val dw = DoubleArray(p + 1)
        for (s in 0..samples) {
            var t = t0 + (t1 - t0) * s / samples
            if (s == samples) t = t1
            var k = p
            while (k < n && t >= knots[k + 1]) k++
            for (j in 0..p) {
                val idx = k - p + j
                val wt = w?.get(idx) ?: 1.0
                dx[j] = cx[idx] * wt; dy[j] = cy[idx] * wt; dw[j] = wt
            }
            for (r in 1..p) {
                for (j in p downTo r) {
                    val den = knots[j + 1 + k - r] - knots[j + k - p]
                    val alpha = if (abs(den) < 1e-12) 0.0 else (t - knots[j + k - p]) / den
                    dx[j] = (1 - alpha) * dx[j - 1] + alpha * dx[j]
                    dy[j] = (1 - alpha) * dy[j - 1] + alpha * dy[j]
                    dw[j] = (1 - alpha) * dw[j - 1] + alpha * dw[j]
                }
            }
            val ww = if (abs(dw[p]) < 1e-12) 1.0 else dw[p]
            out[s * 2] = dx[p] / ww
            out[s * 2 + 1] = dy[p] / ww
        }
        return out
    }
}

/** DXF の文字の整え(特殊記号・MTEXT の書式コード) */
internal object DxfText {
    private val unicodeEscape = Regex("\\\\U\\+([0-9A-Fa-f]{4})")

    /** TEXT: %%c → ⌀、%%d → °、%%p → ±、%%u / %%o は無視、\U+XXXX */
    fun clean(s: String): String {
        var t = s
        t = t.replace(Regex("%%[cC]"), "⌀").replace(Regex("%%[dD]"), "°").replace(Regex("%%[pP]"), "±")
        t = t.replace(Regex("%%[uUoO]"), "").replace(Regex("%%%"), "%")
        t = unicodeEscape.replace(t) { it.groupValues[1].toInt(16).toChar().toString() }
        return t.replace("^I", "    ").replace("\r", "")
    }

    /** MTEXT: \P は改行、{\fArial;...} や \C1; などの書式は取り除く */
    fun mtext(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val n = s[i + 1]
                when (n) {
                    'P' -> { sb.append('\n'); i += 2 }
                    'p' -> { // 段落の書式(\pxqc; など)
                        val end = s.indexOf(';', i)
                        i = if (end < 0) i + 2 else end + 1
                    }
                    '~' -> { sb.append(' '); i += 2 }
                    '\\', '{', '}' -> { sb.append(n); i += 2 }
                    'U' -> {
                        val m = unicodeEscape.matchAt(s, i)
                        if (m != null) { sb.append(m.groupValues[1].toInt(16).toChar()); i += m.value.length } else i += 2
                    }
                    'S' -> {
                        val end = s.indexOf(';', i)
                        if (end < 0) { i += 2 } else {
                            sb.append(s.substring(i + 2, end).replace('^', '/').replace('#', '/'))
                            i = end + 1
                        }
                    }
                    'f', 'F', 'C', 'c', 'H', 'h', 'T', 't', 'Q', 'q', 'W', 'w', 'A', 'a', 'X', 'x' -> {
                        val end = s.indexOf(';', i)
                        i = if (end < 0) i + 2 else end + 1
                    }
                    'L', 'l', 'O', 'o', 'K', 'k', 'N', 'n' -> i += 2
                    else -> { sb.append(n); i += 2 }
                }
            } else if (c == '{' || c == '}') {
                i++
            } else {
                sb.append(c)
                i++
            }
        }
        return clean(sb.toString())
    }
}
