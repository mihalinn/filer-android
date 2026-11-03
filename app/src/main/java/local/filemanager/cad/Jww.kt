package local.filemanager

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * JW_CAD(.jww)の読み込み。MFC の CArchive で書かれた図形の並びを読む。
 * ファイル構造は、公開されている ezjww(MIT ライセンス、https://github.com/monozukuri-ai/ezjww)の解析を参考にした。
 * Android に依存しない。
 */
object Jww {
    const val MAX_BYTES = 64L * 1024 * 1024

    fun isJww(b: ByteArray): Boolean = b.size >= 12 && String(b, 0, 8, Charsets.ISO_8859_1) == "JwwData."

    // ---------------------------------------------------------------- 読み取り

    private class R(val d: ByteArray, var p: Int = 0) {
        private val bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val remaining: Int get() = d.size - p

        private fun need(n: Int) {
            if (n < 0 || p + n > d.size) throw OpException("JW_CAD のデータが途中で終わっています")
        }

        fun skip(n: Int) { need(n); p += n }
        fun u8(): Int { need(1); return d[p++].toInt() and 0xFF }
        fun u16(): Int { need(2); val v = bb.getShort(p).toInt() and 0xFFFF; p += 2; return v }
        fun u32(): Long { need(4); val v = bb.getInt(p).toLong() and 0xFFFFFFFFL; p += 4; return v }
        fun f64(): Double { need(8); val v = bb.getDouble(p); p += 8; return v }
        fun bytes(n: Int): ByteArray { need(n); val o = d.copyOfRange(p, p + n); p += n; return o }

        /** MFC の CString。長さは 1 バイト(0xFF なら 2 バイト、さらに 0xFFFF なら 4 バイト)。0xFFFE が付くと UTF-16。 */
        fun cstring(): String {
            val first = u8()
            var unicode = false
            val len: Int
            if (first < 0xFF) {
                len = first
            } else {
                val w = u16()
                if (w == 0xFFFE) {
                    unicode = true
                    val inner = u8()
                    len = if (inner < 0xFF) inner else {
                        val w2 = u16()
                        if (w2 == 0xFFFF) u32().toInt() else w2
                    }
                } else if (w == 0xFFFF) {
                    len = u32().toInt()
                } else {
                    len = w
                }
            }
            if (len < 0 || len > 1 shl 24) throw OpException("JW_CAD の文字列が読めません")
            return if (unicode) String(bytes(len * 2), Charsets.UTF_16LE) else decode(bytes(len))
        }
    }

    private val cp932: Charset by lazy { Charset.forName("windows-31j") }

    /** ふつうは Shift_JIS(CP932)。Wine で作られたファイルは UTF-8 のことがあるので、UTF-8 として正しく読める日本語が 2 文字以上あればそちらにする。 */
    internal fun decode(b: ByteArray): String {
        if (b.all { it >= 0 }) return String(b, Charsets.US_ASCII)
        try {
            val s = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b)).toString()
            if (s.count { it.code > 0x7F } >= 2) return s
        } catch (e: CharacterCodingException) {
            // CP932 として読む
        }
        return String(b, cp932)
    }

    // ---------------------------------------------------------------- 図形

    private class Base(val penStyle: Int, val penColor: Int, val layer: Int, val group: Int, val flag: Int)

    private sealed class E(val base: Base)
    private class ELine(b: Base, val x1: Double, val y1: Double, val x2: Double, val y2: Double) : E(b)
    private class EArc(
        b: Base, val cx: Double, val cy: Double, val r: Double, val start: Double, val sweep: Double,
        val tilt: Double, val flat: Double, val full: Boolean,
    ) : E(b)
    private class EPoint(b: Base) : E(b)
    private class EText(
        b: Base, val x1: Double, val y1: Double, val x2: Double, val y2: Double, val sizeX: Double, val sizeY: Double,
        val angle: Double, val content: String,
    ) : E(b)
    private class ESolid(b: Base, val pts: DoubleArray, val color: Long?) : E(b)
    private class ECircleSolid(
        b: Base, val cx: Double, val cy: Double, val r: Double, val flat: Double, val tilt: Double,
        val start: Double, val sweep: Double, val mode: Double, val color: Long?,
    ) : E(b)
    private class EBlock(b: Base, val x: Double, val y: Double, val sx: Double, val sy: Double, val rot: Double, val def: Long) : E(b)
    private class EDim(b: Base, val line: ELine, val text: EText, val aux: List<ELine>) : E(b)
    private class BlockDef(val number: Long, val entities: List<E>)

    private class Table {
        val names = HashMap<Long, String>()
        var next = 1L
        fun registerClass(name: String) { names[next++] = name }
    }

    private sealed class Tag {
        object New : Tag()
        object Null : Tag()
        class ClassRef(val pid: Long) : Tag()
        class ObjectRef(val tag: Long) : Tag()
    }

    private fun readTag(r: R): Tag {
        val w = r.u16()
        if (w == 0xFFFF) return Tag.New
        val tag: Long = if (w == 0x7FFF) r.u32() else ((w.toLong() and 0x8000L) shl 16) or (w.toLong() and 0x7FFFL)
        if (tag and 0x80000000L != 0L) {
            val pid = tag and 0x7FFFFFFFL
            return if (pid == 0L) Tag.Null else Tag.ClassRef(pid)
        }
        return if (tag == 0L) Tag.Null else Tag.ObjectRef(tag)
    }

    private fun readCount(r: R): Int {
        val w = r.u16()
        return if (w != 0xFFFF) w else r.u32().toInt()
    }

    private fun readBase(r: R, version: Int): Base {
        r.u32() // 曲線の属性(グループ)
        val style = r.u8()
        val color = r.u16()
        if (version >= 351) r.u16() // 線の太さ
        val layer = r.u16()
        val group = r.u16()
        val flag = r.u16()
        return Base(style, color, layer, group, flag)
    }

    private fun readLine(r: R, version: Int): ELine {
        val b = readBase(r, version)
        return ELine(b, r.f64(), r.f64(), r.f64(), r.f64())
    }

    private fun readText(r: R, version: Int): EText {
        val b = readBase(r, version)
        val x1 = r.f64(); val y1 = r.f64(); val x2 = r.f64(); val y2 = r.f64()
        r.u32() // 文字の種類
        val sx = r.f64(); val sy = r.f64()
        r.f64() // 文字の間隔
        val angle = r.f64()
        r.cstring() // フォント名
        val content = r.cstring()
        return EText(b, x1, y1, x2, y2, sx, sy, angle, content)
    }

    private fun readEntity(r: R, version: Int, t: Table): E? {
        val name = when (val tag = readTag(r)) {
            Tag.New -> {
                r.u16() // スキーマ番号
                val n = r.u16()
                if (n > 64) throw OpException("JW_CAD の図形の種類が読めません")
                String(r.bytes(n), Charsets.ISO_8859_1).also { t.registerClass(it) }
            }
            Tag.Null -> return null
            is Tag.ClassRef -> t.names[tag.pid] ?: throw OpException("JW_CAD の図形の種類が読めません")
            is Tag.ObjectRef -> throw OpException("JW_CAD の図形の参照が読めません")
        }
        val e: E = when (name) {
            "CDataSen" -> readLine(r, version)
            "CDataEnko" -> {
                val b = readBase(r, version)
                EArc(b, r.f64(), r.f64(), r.f64(), r.f64(), r.f64(), r.f64(), r.f64(), r.u32() != 0L)
            }
            "CDataTen" -> {
                val b = readBase(r, version)
                r.f64(); r.f64(); r.u32()
                if (b.penStyle == 100) { r.u32(); r.f64(); r.f64() }
                EPoint(b)
            }
            "CDataMoji" -> readText(r, version)
            "CDataSolid" -> {
                val b = readBase(r, version)
                val sx = r.f64(); val sy = r.f64(); val ex = r.f64(); val ey = r.f64()
                val d2x = r.f64(); val d2y = r.f64(); val d3x = r.f64(); val d3y = r.f64()
                val color = if (b.penColor == 10) r.u32() else null
                if (b.penStyle >= 101) ECircleSolid(b, sx, sy, ex, ey, d2x, d2y, d3x, d3y, color)
                else ESolid(b, doubleArrayOf(sx, sy, ex, ey, d2x, d2y, d3x, d3y), color)
            }
            "CDataBlock" -> {
                val b = readBase(r, version)
                EBlock(b, r.f64(), r.f64(), r.f64(), r.f64(), r.f64(), r.u32())
            }
            "CDataSunpou" -> {
                val b = readBase(r, version)
                val line = readLine(r, version)
                val text = readText(r, version)
                val aux = ArrayList<ELine>()
                if (version >= 420) {
                    r.u16()
                    aux.add(readLine(r, version)); aux.add(readLine(r, version))
                    // 補助の点 4 つ(種類の目印は付かず、中身だけが続く)
                    for (i in 0 until 4) {
                        val pb = readBase(r, version)
                        r.f64(); r.f64(); r.u32()
                        if (pb.penStyle == 100) { r.u32(); r.f64(); r.f64() }
                    }
                }
                EDim(b, line, text, aux)
            }
            else -> throw OpException("JW_CAD の図形(${name})に対応していません")
        }
        t.next++
        return e
    }

    /** 図形の並びを読む。途中で壊れていたら、読めた所までを返す(truncated = true) */
    private class ListResult(val entities: List<E>, val truncated: Boolean)

    private fun readEntityList(r: R, version: Int, t: Table): ListResult {
        val count = try { readCount(r) } catch (e: OpException) { return ListResult(emptyList(), true) }
        val out = ArrayList<E>(minOf(count, 1 shl 16))
        for (i in 0 until count) {
            try {
                readEntity(r, version, t)?.let { out.add(it) }
            } catch (e: OpException) {
                return ListResult(out, true)
            }
        }
        return ListResult(out, false)
    }

    private fun readBlockDefs(r: R, version: Int, t: Table): List<BlockDef> {
        val out = ArrayList<BlockDef>()
        val count = try { readCount(r) } catch (e: OpException) { return out }
        if (count > 10_000) return out
        // 古い書き方(個数が 4 バイト)で、2 バイトの 0 が余分に付いている場合
        if (count > 0 && r.remaining >= 6) {
            val d = r.d; val p = r.p
            if (d[p].toInt() == 0 && d[p + 1].toInt() == 0 && (d[p + 2].toInt() and 0xFF) == 0xFF && (d[p + 3].toInt() and 0xFF) == 0xFF) r.skip(2)
        }
        for (i in 0 until count) {
            try {
                when (readTag(r)) {
                    Tag.New -> { r.u16(); val n = r.u16(); if (n > 64) return out; t.registerClass(String(r.bytes(n), Charsets.ISO_8859_1)) }
                    Tag.Null -> continue
                    is Tag.ClassRef -> {}
                    is Tag.ObjectRef -> return out
                }
                t.next++
                readBase(r, version)
                val number = r.u32()
                r.u32()
                r.skip(4) // 作った日時
                r.cstring() // ブロック名
                val list = readEntityList(r, version, t)
                out.add(BlockDef(number, list.entities))
                if (list.truncated) return out
            } catch (e: OpException) {
                return out
            }
        }
        return out
    }

    // ---------------------------------------------------------------- ヘッダー

    private class Header(
        val version: Int,
        /** 16 グループ × 16 レイヤーの表示(true = 表示) */
        val visible: BooleanArray,
        val layerNames: Array<String>,
        val groupNames: Array<String>,
        /** 画面の色 0〜9(0xFFRRGGBB) */
        val pens: IntArray,
        /** 色番号 100〜356 */
        val extended: IntArray?,
    )

    private fun colorRef(v: Long): Int {
        val r = (v and 0xFF).toInt(); val g = ((v shr 8) and 0xFF).toInt(); val b = ((v shr 16) and 0xFF).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun readHeader(data: ByteArray): Header {
        val r = R(data)
        r.skip(8)
        val version = r.u32().toInt()
        r.cstring() // メモ
        r.u32() // 用紙
        r.u32() // 書き込みグループ
        val visible = BooleanArray(256)
        for (g in 0 until 16) {
            val gs = r.u32()
            r.u32(); r.f64(); r.u32()
            for (l in 0 until 16) {
                val ls = r.u32()
                r.u32()
                visible[g * 16 + l] = gs != 0L && ls != 0L
            }
        }
        val layerNames = Array(256) { "" }
        val groupNames = Array(16) { "" }
        val pens = IntArray(10)
        var extended: IntArray? = null
        if (version >= 300) {
            try {
                r.skip((14 + 5 + 1) * 4)
                r.u32() // 線の太さの上限
                r.skip(16 + 8 + 4 + 4 + 8 + 16 + 16)
                for (g in 0 until 16) for (l in 0 until 16) layerNames[g * 16 + l] = r.cstring()
                for (g in 0 until 16) groupNames[g] = r.cstring()
                r.skip(464) // 日影・天空率・ズーム・並行線などの設定
                for (i in 0 until 10) { pens[i] = colorRef(r.u32()); r.u32() }
                if (version >= 420) {
                    r.skip(10 * 16) // プリンター用の色
                    r.skip(8 * 16 + 5 * 20 + 4 * 16) // 線種
                    r.skip(11 * 4 + 20 + 40 + 32 + 8)
                    val ext = IntArray(257)
                    for (i in 0 until 257) { ext[i] = colorRef(r.u32()); r.u32() }
                    extended = ext
                }
            } catch (e: OpException) {
                // 名前・色が読めなくても、図形は読める
            }
        }
        return Header(version, visible, layerNames, groupNames, pens, extended)
    }

    /** 最初の図形の種類(CData…)の目印を探して、その前にある個数の位置を返す */
    internal fun findEntityList(d: ByteArray, version: Int): Int? {
        if (d.size < 128) return null
        var fallback: Int? = null
        for (i in 100 until d.size - 20) {
            if (d[i] != 0xFF.toByte() || d[i + 1] != 0xFF.toByte()) continue
            val schema = (d[i + 2].toInt() and 0xFF) or ((d[i + 3].toInt() and 0xFF) shl 8)
            val nameLen = (d[i + 4].toInt() and 0xFF) or ((d[i + 5].toInt() and 0xFF) shl 8)
            if (nameLen !in 8..32 || i + 6 + nameLen > d.size) continue
            val name = String(d, i + 6, nameLen, Charsets.ISO_8859_1)
            if (!name.startsWith("CData")) continue
            val offset = countOffset(d, i)
            if (schema == (version and 0xFFFF)) return offset
            if (fallback == null) fallback = offset
        }
        return fallback
    }

    /** 個数が 65535 以上のときは、0xFFFF の後に 4 バイトの個数が付く */
    private fun countOffset(d: ByteArray, tag: Int): Int {
        if (tag >= 6 && d[tag - 6] == 0xFF.toByte() && d[tag - 5] == 0xFF.toByte()) {
            val v = (d[tag - 4].toLong() and 0xFF) or ((d[tag - 3].toLong() and 0xFF) shl 8) or
                ((d[tag - 2].toLong() and 0xFF) shl 16) or ((d[tag - 1].toLong() and 0xFF) shl 24)
            if (v >= 0xFFFF && v * 20 <= d.size) return tag - 6
        }
        return tag - 2
    }

    // ---------------------------------------------------------------- 変換

    fun parse(data: ByteArray): CadDrawing {
        if (!isJww(data)) throw OpException("JW_CAD(.jww)として読めません")
        val header = try { readHeader(data) } catch (e: OpException) { throw OpException("JW_CAD のヘッダーが読めません") }
        val start = findEntityList(data, header.version) ?: throw OpException("JW_CAD の図形が見つかりません")
        val r = R(data, start)
        val table = Table()
        val list = readEntityList(r, header.version, table)
        val defs = if (list.truncated) emptyList() else readBlockDefs(r, header.version, table)
        if (list.entities.isEmpty()) throw OpException("図形がありません")
        val conv = Converter(header, defs.associateBy { it.number })
        conv.run(list.entities, Xf.ID, null, 0)
        val note = buildString {
            if (list.truncated) append("ファイルの途中までしか読めませんでした")
        }
        return conv.b.build(note)
    }

    private class Converter(val h: Header, val defs: Map<Long, BlockDef>) {
        val b = CadBuilder()
        private val layerIds = HashMap<Int, Int>()

        fun layerOf(base: Base): Int {
            val key = (base.group.coerceIn(0, 15)) * 16 + base.layer.coerceIn(0, 15)
            return layerIds.getOrPut(key) {
                val g = key / 16; val l = key % 16
                val ln = h.layerNames[key]
                val gn = h.groupNames[g]
                val def = "%X-%X".format(g, l)
                val name = buildString {
                    append(def)
                    if (ln.isNotBlank() && ln != def) append(' ').append(ln)
                    if (gn.isNotBlank() && gn != "Group%X".format(g)) { insert(0, "[$gn] ") }
                }
                b.layer(name, h.visible[key])
            }
        }

        fun color(base: Base, solidRef: Long?): Int {
            if (solidRef != null && base.penColor == 10) return colorRef(solidRef)
            return when (base.penColor) {
                in 1..9 -> h.pens[base.penColor].takeIf { it != 0 } ?: 0
                in 100..356 -> h.extended?.get(base.penColor - 100) ?: 0
                else -> 0
            }
        }

        fun run(list: List<E>, xf: Xf, layerFrom: Layer?, depth: Int) {
            for (e in list) {
                try {
                    one(e, xf, layerFrom, depth)
                } catch (ex: OpException) {
                    throw ex
                } catch (ex: Exception) {
                    // 壊れた図形は飛ばす
                }
            }
        }

        /** ブロックの中の図形は、ブロックを置いた層の図形として扱う */
        class Layer(val id: Int, val color: Int)

        private fun layer(e: E, from: Layer?) = from?.id ?: layerOf(e.base)

        private fun pts(xf: Xf, p: DoubleArray): DoubleArray {
            val o = DoubleArray(p.size)
            var i = 0
            while (i < p.size) { o[i] = xf.x(p[i], p[i + 1]); o[i + 1] = xf.y(p[i], p[i + 1]); i += 2 }
            return o
        }

        fun one(e: E, xf: Xf, from: Layer?, depth: Int) {
            when (e) {
                is ELine -> b.line(layer(e, from), color(e.base, null), xf.x(e.x1, e.y1), xf.y(e.x1, e.y1), xf.x(e.x2, e.y2), xf.y(e.x2, e.y2))
                is EArc -> arc(e, xf, from)
                is EText -> text(e, xf, from)
                is ESolid -> {
                    var p = e.pts // 並びは 始点・終点・点2・点3(この順に囲む)
                    if (crosses(p)) p = byAngle(p)
                    b.fill(layer(e, from), color(e.base, e.color), listOf(pts(xf, p)))
                }
                is ECircleSolid -> circleSolid(e, xf, from)
                is EBlock -> {
                    val def = defs[e.def] ?: return
                    if (depth >= 12) return
                    val cr = cos(e.rot); val sr = sin(e.rot)
                    val place = Xf(e.sx * cr, e.sx * sr, -e.sy * sr, e.sy * cr, e.x, e.y)
                    val l = from ?: Layer(layerOf(e.base), 0)
                    run(def.entities, place.then(xf), l, depth + 1)
                }
                is EDim -> {
                    one(e.line, xf, from, depth)
                    one(e.text, xf, from, depth)
                    for (a in e.aux) one(a, xf, from, depth)
                }
                is EPoint -> {}
            }
        }

        fun arc(e: EArc, xf: Xf, from: Layer?) {
            if (e.r <= 0.0) return
            val flat = if (abs(e.flat) < 1e-12) 1.0 else e.flat
            val lo = if (e.sweep < 0) e.start + e.sweep else e.start
            val sweep = if (e.full) Math.PI * 2 else abs(e.sweep)
            val raw = if (flat == 1.0) CadGeo.arc(e.cx, e.cy, e.r, if (e.full) 0.0 else lo, sweep)
            else CadGeo.ellipse(e.cx, e.cy, e.r, e.r * flat, e.tilt, if (e.full) 0.0 else lo, sweep)
            val p = pts(xf, raw)
            b.poly(layer(e, from), color(e.base, null), p, p.size / 2, e.full)
        }

        fun circleSolid(e: ECircleSolid, xf: Xf, from: Layer?) {
            if (abs(e.r) < 1e-12) return
            val flat = if (abs(e.flat) < 1e-12) 1.0 else abs(e.flat)
            val full = (abs(e.sweep) - Math.PI * 2).let { abs(it) < 1e-6 } || e.mode.toInt() == 100
            val lo = if (e.sweep < 0) e.start + e.sweep else e.start
            val sw = if (full) Math.PI * 2 else abs(e.sweep)
            fun edge(radius: Double) = CadGeo.ellipse(e.cx, e.cy, radius, radius * flat, e.tilt, if (full) 0.0 else lo, sw)
            val lay = layer(e, from)
            val c = color(e.base, e.color)
            if (e.base.penStyle in 105..106) {
                // 輪(外側の半径 r、内側の半径が mode)
                val inner = abs(e.mode)
                val outer = abs(e.r)
                if (inner > 1e-12 && inner < outer) {
                    if (full) b.fill(lay, c, listOf(pts(xf, edge(outer)), pts(xf, edge(inner))))
                    else {
                        val o = edge(outer); val i = edge(inner)
                        val loop = DoubleArray(o.size + i.size)
                        System.arraycopy(o, 0, loop, 0, o.size)
                        var k = 0
                        var j = i.size - 2
                        while (j >= 0) { loop[o.size + k] = i[j]; loop[o.size + k + 1] = i[j + 1]; k += 2; j -= 2 }
                        b.fill(lay, c, listOf(pts(xf, loop)))
                    }
                    return
                }
            }
            val boundary = edge(abs(e.r))
            val loop = if (full || e.mode.toInt() == -1 || e.mode.toInt() == 5) boundary
            else DoubleArray(boundary.size + 2).also { it[0] = e.cx; it[1] = e.cy; System.arraycopy(boundary, 0, it, 2, boundary.size) }
            b.fill(lay, c, listOf(pts(xf, loop)))
        }

        fun text(e: EText, xf: Xf, from: Layer?) {
            val c = e.content
            if (c.isEmpty() || c.startsWith("^@BM")) return
            if (abs(e.x1) < 1e-9 && abs(e.y1 + 1000.0) < 1e-9 && abs(e.x2) < 1e-9 && abs(e.y2 + 1000.0) < 1e-9) return // 内部の設定
            val size = if (e.sizeY > 0) e.sizeY else if (e.sizeX > 0) e.sizeX else 2.5
            val cells = c.sumOf { ch -> if (ch.code in 0x20..0x80 || ch.code in 0xFF61..0xFF9F) 0.5 else 1.0 }
            val span = hypot(e.x2 - e.x1, e.y2 - e.y1)
            val factor = if (cells > 0 && span > 0) (span / (size * cells)).coerceIn(0.05, 20.0) else 1.0
            b.text(
                layer(e, from), color(e.base, null), xf.x(e.x1, e.y1), xf.y(e.x1, e.y1), size * xf.scale,
                Math.toDegrees(xf.rotation) + e.angle, c, 0, 1, factor,
            )
        }

        private fun crosses(p: DoubleArray): Boolean {
            fun o(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double) = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
            fun seg(a: Int, b2: Int, c: Int, d: Int): Boolean {
                val abc = o(p[a * 2], p[a * 2 + 1], p[b2 * 2], p[b2 * 2 + 1], p[c * 2], p[c * 2 + 1])
                val abd = o(p[a * 2], p[a * 2 + 1], p[b2 * 2], p[b2 * 2 + 1], p[d * 2], p[d * 2 + 1])
                val cda = o(p[c * 2], p[c * 2 + 1], p[d * 2], p[d * 2 + 1], p[a * 2], p[a * 2 + 1])
                val cdb = o(p[c * 2], p[c * 2 + 1], p[d * 2], p[d * 2 + 1], p[b2 * 2], p[b2 * 2 + 1])
                return abc * abd < 0 && cda * cdb < 0
            }
            return seg(0, 1, 2, 3) || seg(1, 2, 3, 0)
        }

        /** 辺が交差する並びは、重心からの角度の順に並べ直す(始点から始める) */
        private fun byAngle(p: DoubleArray): DoubleArray {
            val cx = (p[0] + p[2] + p[4] + p[6]) / 4
            val cy = (p[1] + p[3] + p[5] + p[7]) / 4
            val idx = (0 until 4).sortedBy { atan2(p[it * 2 + 1] - cy, p[it * 2] - cx) }
            val first = idx.indexOf(0)
            val rot = idx.drop(first) + idx.take(first)
            return DoubleArray(8) { p[rot[it / 2] * 2 + (it % 2)] }
        }
    }
}
