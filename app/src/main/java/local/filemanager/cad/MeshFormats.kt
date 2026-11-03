package local.filemanager

import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.StringTokenizer
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/** 色が無い頂点の目印 */
internal const val NO_COLOR = 0

/** 三角形(または点)を集めて Mesh にする。座標は Z が上。Y が上の形式(OBJ)は yUp = true で起こし直す。 */
internal class MeshBuilder(private val yUp: Boolean = false, private val maxVertices: Int = MAX_VERTICES) {
    private var pos = FloatArray(3 * 3 * 4096)
    private var col = FloatArray(0)
    private var n = 0
    private var colored = false
    private val min = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
    private val max = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)

    val vertexCount: Int get() = n

    /** color: 0xAARRGGBB(NO_COLOR = 色なし) */
    fun vertex(x0: Float, y0: Float, z0: Float, color: Int = NO_COLOR) {
        if (x0.isNaN() || y0.isNaN() || z0.isNaN() || x0.isInfinite() || y0.isInfinite() || z0.isInfinite()) throw BadVertex
        if (n >= maxVertices) throw OpException("大きすぎて表示できません(頂点が多すぎます)")
        val x = x0
        val y = if (yUp) -z0 else y0
        val z = if (yUp) y0 else z0
        if (n * 3 + 3 > pos.size) pos = pos.copyOf(pos.size * 2)
        pos[n * 3] = x; pos[n * 3 + 1] = y; pos[n * 3 + 2] = z
        if (x < min[0]) min[0] = x
        if (y < min[1]) min[1] = y
        if (z < min[2]) min[2] = z
        if (x > max[0]) max[0] = x
        if (y > max[1]) max[1] = y
        if (z > max[2]) max[2] = z
        if (color != NO_COLOR) {
            if (!colored) {
                colored = true
                col = FloatArray(maxOf(pos.size, 3 * (n + 1))) { UNSET }
            }
        }
        if (colored) {
            if (n * 3 + 3 > col.size) col = col.copyOf(maxOf(pos.size, col.size * 2)).also { java.util.Arrays.fill(it, col.size, it.size, UNSET) }
            if (color != NO_COLOR) {
                col[n * 3] = ((color shr 16) and 0xFF) / 255f
                col[n * 3 + 1] = ((color shr 8) and 0xFF) / 255f
                col[n * 3 + 2] = (color and 0xFF) / 255f
            }
        }
        n++
    }

    /** 途中まで入れた三角形を取り消す(mark: 入れ始める前の vertexCount) */
    fun rollbackTo(mark: Int) {
        if (mark in 0..n) n = mark
    }

    /** NaN や無限大の座標。その三角形は捨てる */
    object BadVertex : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    fun build(points: Boolean = false): Mesh {
        val count = if (points) n else n - n % 3
        if (count == 0) throw OpException(if (points) "点がありません" else "三角形がありません")
        val buf = ByteBuffer.allocateDirect(count * 12).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(pos, 0, count * 3)
        buf.flip()
        var cbuf: java.nio.FloatBuffer? = null
        if (colored) {
            cbuf = ByteBuffer.allocateDirect(count * 12).order(ByteOrder.nativeOrder()).asFloatBuffer()
            for (i in 0 until count * 3) cbuf.put(if (i < col.size && col[i] != UNSET) col[i] else 0.8f)
            cbuf.flip()
        }
        return Mesh(buf, count, min, max, cbuf, points)
    }

    companion object {
        const val MAX_VERTICES = 6_000_000
        private const val UNSET = -1f
    }
}

internal fun rgb(r: Float, g: Float, b: Float): Int {
    fun c(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    return (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
}

/** OBJ・PLY・OFF・3MF の読み込み。Android に依存しない。 */
object MeshFormats {
    const val MAX_TEXT_BYTES = 60L * 1024 * 1024
    const val MAX_BINARY_BYTES = 120L * 1024 * 1024

    private val names = setOf("obj", "ply", "off", "3mf", "stl")

    fun supports(ext: String): Boolean = ext in names

    /** 拡張子で読み分ける(STL も含む)。 */
    fun parse(ext: String, input: InputStream, size: Long): Mesh = when (ext) {
        "stl" -> Stl.parse(input, size)
        "obj" -> { limit(size, MAX_TEXT_BYTES); parseObj(input) }
        "ply" -> { limit(size, MAX_BINARY_BYTES); parsePly(input) }
        "off" -> { limit(size, MAX_TEXT_BYTES); parseOff(input) }
        "3mf" -> { limit(size, MAX_TEXT_BYTES); parse3mf(input) }
        else -> throw OpException("3D モデルとして読めません")
    }

    private fun limit(size: Long, max: Long) {
        if (size > max) throw OpException("大きすぎて表示できません(${max / 1024 / 1024} MB まで)")
    }

    // ---------------------------------------------------------------- OBJ

    /** 頂点(v)と面(f)だけを読む。頂点の後ろに r g b が付く拡張があれば色にする。材質(.mtl)は読まない。 */
    fun parseObj(input: InputStream): Mesh {
        var xs = FloatArray(3 * 4096)
        var cs = IntArray(4096)
        var vn = 0
        var anyColor = false
        val b = MeshBuilder(yUp = true)
        val reader = BufferedReader(InputStreamReader(input, Charsets.ISO_8859_1), 1 shl 16)
        val idx = IntArray(64)
        var faces = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.length < 3) continue
            val c0 = line[0]
            if (c0 == 'v' && line[1] == ' ') {
                val st = StringTokenizer(line, " \t", false)
                st.nextToken()
                if (st.countTokens() < 3) continue
                val x = st.nextToken().toFloatOrNull() ?: Float.NaN
                val y = st.nextToken().toFloatOrNull() ?: Float.NaN
                val z = st.nextToken().toFloatOrNull() ?: Float.NaN
                var color = NO_COLOR
                if (st.countTokens() >= 3) {
                    val r = st.nextToken().toFloatOrNull()
                    val g = st.nextToken().toFloatOrNull()
                    val bl = st.nextToken().toFloatOrNull()
                    if (r != null && g != null && bl != null) {
                        color = if (r > 1f || g > 1f || bl > 1f) rgb(r / 255f, g / 255f, bl / 255f) else rgb(r, g, bl)
                        anyColor = true
                    }
                }
                if (vn * 3 + 3 > xs.size) xs = xs.copyOf(xs.size * 2)
                if (vn >= cs.size) cs = cs.copyOf(cs.size * 2)
                xs[vn * 3] = x; xs[vn * 3 + 1] = y; xs[vn * 3 + 2] = z
                cs[vn] = color
                vn++
            } else if (c0 == 'f' && (line[1] == ' ' || line[1] == '\t')) {
                val st = StringTokenizer(line, " \t", false)
                st.nextToken()
                var k = 0
                var bad = false
                while (st.hasMoreTokens() && k < idx.size) {
                    val t = st.nextToken()
                    val slash = t.indexOf('/')
                    val v = (if (slash < 0) t else t.substring(0, slash)).toIntOrNull()
                    if (v == null || v == 0) { bad = true; break }
                    val i = if (v > 0) v - 1 else vn + v
                    if (i < 0 || i >= vn) { bad = true; break }
                    idx[k++] = i
                }
                if (bad || k < 3) continue
                // 多角形は、最初の頂点から扇形に三角形へ分ける
                for (t in 1 until k - 1) {
                    val ia = idx[0]; val ib = idx[t]; val ic = idx[t + 1]
                    val mark = b.vertexCount
                    try {
                        b.vertex(xs[ia * 3], xs[ia * 3 + 1], xs[ia * 3 + 2], if (anyColor) cs[ia] else NO_COLOR)
                        b.vertex(xs[ib * 3], xs[ib * 3 + 1], xs[ib * 3 + 2], if (anyColor) cs[ib] else NO_COLOR)
                        b.vertex(xs[ic * 3], xs[ic * 3 + 1], xs[ic * 3 + 2], if (anyColor) cs[ic] else NO_COLOR)
                        faces++
                    } catch (e: MeshBuilder.BadVertex) {
                        // 座標が読めない三角形は飛ばす(builder 側で途中まで入った頂点を戻せないので、3 つの検査を先に済ませる)
                        b.rollbackTo(mark)
                    }
                }
            }
        }
        if (faces == 0) throw OpException("OBJ に面がありません")
        return b.build()
    }

    // ---------------------------------------------------------------- OFF

    fun parseOff(input: InputStream): Mesh {
        val reader = BufferedReader(InputStreamReader(input, Charsets.ISO_8859_1), 1 shl 16)
        fun next(): String? {
            while (true) {
                val l = reader.readLine() ?: return null
                val h = l.indexOf('#')
                val s = (if (h >= 0) l.substring(0, h) else l).trim()
                if (s.isNotEmpty()) return s
            }
        }
        val head = next() ?: throw OpException("OFF として読めません")
        if (!head.uppercase().contains("OFF")) throw OpException("OFF として読めません")
        val hasColor = head.uppercase().startsWith("C")
        var counts = head.substring(head.uppercase().indexOf("OFF") + 3).trim()
        if (counts.isEmpty()) counts = next() ?: throw OpException("OFF として読めません")
        val ct = StringTokenizer(counts)
        if (ct.countTokens() < 2) throw OpException("OFF として読めません")
        val nv = ct.nextToken().toIntOrNull() ?: throw OpException("OFF として読めません")
        val nf = ct.nextToken().toIntOrNull() ?: throw OpException("OFF として読めません")
        if (nv <= 0 || nv > MeshBuilder.MAX_VERTICES) throw OpException("OFF として読めません")
        val xs = FloatArray(nv * 3)
        val cs = IntArray(nv)
        var anyColor = false
        for (i in 0 until nv) {
            val l = next() ?: throw OpException("OFF の頂点が足りません")
            val st = StringTokenizer(l)
            if (st.countTokens() < 3) throw OpException("OFF の頂点が読めません")
            xs[i * 3] = st.nextToken().toFloatOrNull() ?: Float.NaN
            xs[i * 3 + 1] = st.nextToken().toFloatOrNull() ?: Float.NaN
            xs[i * 3 + 2] = st.nextToken().toFloatOrNull() ?: Float.NaN
            if (hasColor && st.countTokens() >= 3) {
                cs[i] = offColor(st)
                anyColor = true
            }
        }
        val b = MeshBuilder()
        val idx = IntArray(64)
        var faces = 0
        for (f in 0 until nf) {
            val l = next() ?: break
            val st = StringTokenizer(l)
            val k = st.nextToken().toIntOrNull() ?: continue
            if (k < 3 || k > idx.size || st.countTokens() < k) continue
            var bad = false
            for (j in 0 until k) {
                val v = st.nextToken().toIntOrNull()
                if (v == null || v < 0 || v >= nv) { bad = true; break }
                idx[j] = v
            }
            if (bad) continue
            val fc = if (st.countTokens() >= 3) offColor(st) else NO_COLOR
            for (t in 1 until k - 1) {
                val tri = intArrayOf(idx[0], idx[t], idx[t + 1])
                val mark = b.vertexCount
                try {
                    for (v in tri) b.vertex(xs[v * 3], xs[v * 3 + 1], xs[v * 3 + 2], if (fc != NO_COLOR) fc else if (anyColor) cs[v] else NO_COLOR)
                    faces++
                } catch (e: MeshBuilder.BadVertex) {
                    b.rollbackTo(mark)
                }
            }
        }
        if (faces == 0) throw OpException("OFF に面がありません")
        return b.build()
    }

    /** 3 つ(または 4 つ)の数を色にする。すべて 1 以下の小数なら 0〜1、そうでなければ 0〜255 とみなす。 */
    private fun offColor(st: StringTokenizer): Int {
        val v = FloatArray(3) { st.nextToken().toFloatOrNull() ?: 0f }
        val unit = v.all { it <= 1f } && v.any { it.toString().contains('.') }
        return if (unit) rgb(v[0], v[1], v[2]) else rgb(v[0] / 255f, v[1] / 255f, v[2] / 255f)
    }

    // ---------------------------------------------------------------- PLY

    private class PlyProp(val name: String, val type: Int, val countType: Int, val isList: Boolean)
    private class PlyElement(val name: String, val count: Long, val props: ArrayList<PlyProp> = ArrayList())

    private val plyTypes = mapOf(
        "char" to 0, "int8" to 0, "uchar" to 1, "uint8" to 1, "short" to 2, "int16" to 2, "ushort" to 3, "uint16" to 3,
        "int" to 4, "int32" to 4, "uint" to 5, "uint32" to 5, "float" to 6, "float32" to 6, "double" to 7, "float64" to 7,
    )
    private val plySize = intArrayOf(1, 1, 2, 2, 4, 4, 4, 8)

    /** 値を 1 つずつ返す入力。ascii は空白区切り、binary は型の大きさで読む。 */
    private class PlyIn(private val input: InputStream, val ascii: Boolean, order: ByteOrder) {
        private val bb = ByteBuffer.allocate(8).order(order)
        private val sb = StringBuilder()

        fun value(type: Int): Double {
            if (ascii) {
                sb.setLength(0)
                var c = input.read()
                while (c >= 0 && c <= 32) c = input.read()
                if (c < 0) throw OpException("PLY のデータが途中で終わっています")
                while (c > 32) { sb.append(c.toChar()); c = input.read() }
                return sb.toString().toDoubleOrNull() ?: Double.NaN
            }
            val n = plySize[type]
            bb.clear()
            var got = 0
            while (got < n) {
                val r = input.read(bb.array(), got, n - got)
                if (r < 0) throw OpException("PLY のデータが途中で終わっています")
                got += r
            }
            return when (type) {
                0 -> bb.get(0).toDouble()
                1 -> (bb.get(0).toInt() and 0xFF).toDouble()
                2 -> bb.getShort(0).toDouble()
                3 -> (bb.getShort(0).toInt() and 0xFFFF).toDouble()
                4 -> bb.getInt(0).toDouble()
                5 -> (bb.getInt(0).toLong() and 0xFFFFFFFFL).toDouble()
                6 -> bb.getFloat(0).toDouble()
                else -> bb.getDouble(0)
            }
        }
    }

    fun parsePly(input0: InputStream): Mesh {
        val input = BufferedInputStream(input0, 1 shl 16)
        fun headerLine(): String? {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) return sb.toString().trimEnd('\r')
                if (sb.length > 4096) throw OpException("PLY として読めません")
                sb.append(c.toChar())
            }
        }
        if (headerLine()?.trim() != "ply") throw OpException("PLY として読めません")
        var format = ""
        val elements = ArrayList<PlyElement>()
        while (true) {
            val l = headerLine() ?: throw OpException("PLY のヘッダーが途中で終わっています")
            val t = l.trim().split(Regex("\\s+"))
            when (t[0]) {
                "format" -> format = t.getOrElse(1) { "" }
                "element" -> elements.add(PlyElement(t.getOrElse(1) { "" }, t.getOrElse(2) { "0" }.toLongOrNull() ?: 0L))
                "property" -> {
                    val el = elements.lastOrNull() ?: continue
                    if (t.getOrNull(1) == "list") {
                        val ct = plyTypes[t.getOrNull(2)] ?: throw OpException("PLY の型が読めません")
                        val it = plyTypes[t.getOrNull(3)] ?: throw OpException("PLY の型が読めません")
                        el.props.add(PlyProp(t.getOrElse(4) { "" }, it, ct, true))
                    } else {
                        val ty = plyTypes[t.getOrNull(1)] ?: throw OpException("PLY の型が読めません")
                        el.props.add(PlyProp(t.getOrElse(2) { "" }, ty, 0, false))
                    }
                }
                "end_header" -> break
            }
        }
        val rd = when (format) {
            "ascii" -> PlyIn(input, true, ByteOrder.LITTLE_ENDIAN)
            "binary_little_endian" -> PlyIn(input, false, ByteOrder.LITTLE_ENDIAN)
            "binary_big_endian" -> PlyIn(input, false, ByteOrder.BIG_ENDIAN)
            else -> throw OpException("PLY の形式($format)に対応していません")
        }
        var xs = FloatArray(0)
        var cs = IntArray(0)
        var vn = 0
        var anyColor = false
        val b = MeshBuilder()
        var faces = 0
        for (el in elements) {
            if (el.count < 0 || el.count > MeshBuilder.MAX_VERTICES.toLong() * 2) throw OpException("大きすぎて表示できません")
            val isVertex = el.name == "vertex"
            val isFace = el.name == "face"
            if (isVertex) {
                xs = FloatArray((el.count * 3).toInt())
                cs = IntArray(el.count.toInt())
            }
            val ix = el.props.indexOfFirst { !it.isList && it.name == "x" }
            val iy = el.props.indexOfFirst { !it.isList && it.name == "y" }
            val iz = el.props.indexOfFirst { !it.isList && it.name == "z" }
            fun colorIndex(vararg n: String) = el.props.indexOfFirst { !it.isList && it.name in n }
            val ir = colorIndex("red", "r", "diffuse_red")
            val ig = colorIndex("green", "g", "diffuse_green")
            val ib = colorIndex("blue", "b", "diffuse_blue")
            val il = el.props.indexOfFirst { it.isList && (it.name == "vertex_indices" || it.name == "vertex_index") }
            val vals = DoubleArray(el.props.size)
            var list = IntArray(256)
            val isStrip = el.name == "tristrips"
            for (r in 0 until el.count.toInt()) {
                var listLen = 0
                for ((pi, p) in el.props.withIndex()) {
                    if (p.isList) {
                        val cnt = rd.value(p.countType).toInt()
                        if (cnt < 0 || cnt > 20_000_000) throw OpException("PLY のデータが読めません")
                        if (pi == il && cnt > list.size) list = IntArray(cnt)
                        for (j in 0 until cnt) {
                            val v = rd.value(p.type)
                            if (pi == il) list[j] = v.toInt()
                        }
                        if (pi == il) listLen = cnt
                    } else {
                        vals[pi] = rd.value(p.type)
                    }
                }
                fun channel(i: Int): Float {
                    val v = vals[i]
                    val ty = el.props[i].type
                    return (if (ty >= 6) v else v / 255.0).toFloat()
                }
                val color = if (ir >= 0 && ig >= 0 && ib >= 0) rgb(channel(ir), channel(ig), channel(ib)) else NO_COLOR
                if (isVertex) {
                    if (ix < 0 || iy < 0 || iz < 0) throw OpException("PLY に座標がありません")
                    xs[r * 3] = vals[ix].toFloat(); xs[r * 3 + 1] = vals[iy].toFloat(); xs[r * 3 + 2] = vals[iz].toFloat()
                    cs[r] = color
                    if (color != NO_COLOR) anyColor = true
                    vn = r + 1
                } else if (isStrip && il >= 0 && listLen >= 3) {
                    // 三角形の帯(-1 で区切られる): 1 つずつずらして、向きを交互に反転する
                    var start = 0
                    for (k in 0..listLen) {
                        if (k < listLen && list[k] >= 0) continue
                        for (t in start + 2 until k) {
                            val a = list[t - 2]; val bb = list[t - 1]; val c = list[t]
                            val flip = (t - start) % 2 == 1
                            val tri = if (flip) intArrayOf(bb, a, c) else intArrayOf(a, bb, c)
                            if (tri.any { it !in 0 until vn } || a == bb || bb == c || a == c) continue
                            val mark = b.vertexCount
                            try {
                                for (v in tri) b.vertex(xs[v * 3], xs[v * 3 + 1], xs[v * 3 + 2], if (anyColor) cs[v] else NO_COLOR)
                                faces++
                            } catch (e: MeshBuilder.BadVertex) {
                                b.rollbackTo(mark)
                            }
                        }
                        start = k + 1
                    }
                } else if (isFace && il >= 0 && listLen >= 3) {
                    var ok = true
                    for (j in 0 until listLen) if (list[j] < 0 || list[j] >= vn) ok = false
                    if (!ok) continue
                    for (t in 1 until listLen - 1) {
                        val tri = intArrayOf(list[0], list[t], list[t + 1])
                        val mark = b.vertexCount
                        try {
                            for (v in tri) b.vertex(xs[v * 3], xs[v * 3 + 1], xs[v * 3 + 2], if (color != NO_COLOR) color else if (anyColor) cs[v] else NO_COLOR)
                            faces++
                        } catch (e: MeshBuilder.BadVertex) {
                            b.rollbackTo(mark)
                        }
                    }
                }
            }
        }
        if (vn == 0) throw OpException("PLY に頂点がありません")
        if (faces > 0) return b.build()
        // 面が無ければ点群として見せる
        val p = MeshBuilder(maxVertices = MeshBuilder.MAX_VERTICES)
        for (i in 0 until vn) {
            try { p.vertex(xs[i * 3], xs[i * 3 + 1], xs[i * 3 + 2], if (anyColor) cs[i] else NO_COLOR) } catch (e: MeshBuilder.BadVertex) { /* 飛ばす */ }
        }
        return p.build(points = true)
    }

    // ---------------------------------------------------------------- 3MF

    private class Affine3(val m: DoubleArray) {
        fun x(x: Double, y: Double, z: Double) = x * m[0] + y * m[3] + z * m[6] + m[9]
        fun y(x: Double, y: Double, z: Double) = x * m[1] + y * m[4] + z * m[7] + m[10]
        fun z(x: Double, y: Double, z: Double) = x * m[2] + y * m[5] + z * m[8] + m[11]

        /** this を先に、o を後に当てる合成 */
        fun then(o: Affine3): Affine3 {
            val r = DoubleArray(12)
            for (c in 0 until 3) {
                for (row in 0 until 3) {
                    r[row * 3 + c] = m[row * 3] * o.m[c] + m[row * 3 + 1] * o.m[3 + c] + m[row * 3 + 2] * o.m[6 + c]
                }
                r[9 + c] = m[9] * o.m[c] + m[10] * o.m[3 + c] + m[11] * o.m[6 + c] + o.m[9 + c]
            }
            return Affine3(r)
        }

        companion object {
            val IDENTITY get() = Affine3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0))
            fun parse(s: String?): Affine3 {
                if (s == null) return IDENTITY
                val v = s.trim().split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
                return if (v.size == 12) Affine3(v.toDoubleArray()) else IDENTITY
            }
        }
    }

    private class Obj3(val id: String) {
        var xyz = FloatArray(0)
        var nv = 0
        var tris = IntArray(0)
        var tcol = IntArray(0) // 三角形ごとの色 3 つ(頂点ごと)
        var nt = 0
        val comps = ArrayList<Pair<String, Affine3>>()
        var pid: String? = null
        var pindex = 0
    }

    private fun parseHex(s: String?): Int {
        if (s == null) return NO_COLOR
        val h = s.trim().removePrefix("#")
        if (h.length < 6) return NO_COLOR
        val v = h.substring(0, 6).toIntOrNull(16) ?: return NO_COLOR
        return (0xFF shl 24) or v
    }

    fun parse3mf(input: InputStream): Mesh {
        val zip = ZipInputStream(BufferedInputStream(input, 1 shl 16))
        var model: ByteArray? = null
        while (true) {
            val e = zip.nextEntry ?: break
            val n = e.name.lowercase()
            if (!e.isDirectory && n.endsWith(".model") && (model == null || n == "3d/3dmodel.model")) {
                model = zip.readBytes()
                if (n == "3d/3dmodel.model") break
            }
        }
        val bytes = model ?: throw OpException("3MF にモデルがありません")
        val objs = HashMap<String, Obj3>()
        val palettes = HashMap<String, IntArray>() // basematerials / colorgroup の id → 色
        val build = ArrayList<Pair<String, Affine3>>()
        val h = object : DefaultHandler() {
            var cur: Obj3? = null
            var pal: ArrayList<Int>? = null
            var palId: String? = null
            var inBuild = false

            override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                val name = qName.substringAfter(':')
                when (name) {
                    "object" -> cur = Obj3(a.getValue("id") ?: "").also {
                        it.pid = a.getValue("pid"); it.pindex = a.getValue("pindex")?.toIntOrNull() ?: 0
                        objs[it.id] = it
                    }
                    "vertex" -> cur?.let { o ->
                        if (o.nv * 3 + 3 > o.xyz.size) o.xyz = o.xyz.copyOf(maxOf(o.xyz.size * 2, 3 * 1024))
                        o.xyz[o.nv * 3] = a.getValue("x")?.toFloatOrNull() ?: 0f
                        o.xyz[o.nv * 3 + 1] = a.getValue("y")?.toFloatOrNull() ?: 0f
                        o.xyz[o.nv * 3 + 2] = a.getValue("z")?.toFloatOrNull() ?: 0f
                        o.nv++
                    }
                    "triangle" -> cur?.let { o ->
                        if (o.nt * 3 + 3 > o.tris.size) {
                            o.tris = o.tris.copyOf(maxOf(o.tris.size * 2, 3 * 1024))
                            o.tcol = o.tcol.copyOf(o.tris.size)
                        }
                        val k = o.nt * 3
                        o.tris[k] = a.getValue("v1")?.toIntOrNull() ?: -1
                        o.tris[k + 1] = a.getValue("v2")?.toIntOrNull() ?: -1
                        o.tris[k + 2] = a.getValue("v3")?.toIntOrNull() ?: -1
                        val pid = a.getValue("pid") ?: o.pid
                        val p = palettes[pid]
                        if (p != null) {
                            val p1 = a.getValue("p1")?.toIntOrNull() ?: o.pindex
                            val p2 = a.getValue("p2")?.toIntOrNull() ?: p1
                            val p3 = a.getValue("p3")?.toIntOrNull() ?: p1
                            o.tcol[k] = p.getOrElse(p1) { NO_COLOR }
                            o.tcol[k + 1] = p.getOrElse(p2) { NO_COLOR }
                            o.tcol[k + 2] = p.getOrElse(p3) { NO_COLOR }
                        } else {
                            o.tcol[k] = NO_COLOR; o.tcol[k + 1] = NO_COLOR; o.tcol[k + 2] = NO_COLOR
                        }
                        o.nt++
                    }
                    "component" -> cur?.comps?.add((a.getValue("objectid") ?: "") to Affine3.parse(a.getValue("transform")))
                    "basematerials", "colorgroup" -> { pal = ArrayList(); palId = a.getValue("id") }
                    "base" -> pal?.add(parseHex(a.getValue("displaycolor")))
                    "color" -> pal?.add(parseHex(a.getValue("color")))
                    "build" -> inBuild = true
                    "item" -> if (inBuild) build.add((a.getValue("objectid") ?: "") to Affine3.parse(a.getValue("transform")))
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String) {
                when (qName.substringAfter(':')) {
                    "object" -> cur = null
                    "basematerials", "colorgroup" -> { palId?.let { palettes[it] = pal!!.toIntArray() }; pal = null; palId = null }
                    "build" -> inBuild = false
                }
            }
        }
        try {
            val f = SAXParserFactory.newInstance()
            f.isNamespaceAware = false
            f.newSAXParser().parse(ByteArrayInputStream(bytes), h)
        } catch (e: OpException) {
            throw e
        } catch (e: Exception) {
            throw OpException("3MF として読めません")
        }
        val b = MeshBuilder()
        var tris = 0
        fun emit(o: Obj3, t: Affine3, depth: Int) {
            for (i in 0 until o.nt) {
                val v = intArrayOf(o.tris[i * 3], o.tris[i * 3 + 1], o.tris[i * 3 + 2])
                if (v.any { it < 0 || it >= o.nv }) continue
                val mark = b.vertexCount
                try {
                    for (k in 0 until 3) {
                        val x = o.xyz[v[k] * 3].toDouble(); val y = o.xyz[v[k] * 3 + 1].toDouble(); val z = o.xyz[v[k] * 3 + 2].toDouble()
                        b.vertex(t.x(x, y, z).toFloat(), t.y(x, y, z).toFloat(), t.z(x, y, z).toFloat(), o.tcol[i * 3 + k])
                    }
                    tris++
                } catch (e: MeshBuilder.BadVertex) {
                    b.rollbackTo(mark)
                }
            }
            if (depth < 8) for ((id, ct) in o.comps) objs[id]?.let { emit(it, ct.then(t), depth + 1) }
        }
        if (build.isEmpty()) objs.values.forEach { emit(it, Affine3.IDENTITY, 0) }
        else for ((id, t) in build) objs[id]?.let { emit(it, t, 0) }
        if (tris == 0) throw OpException("3MF に三角形がありません")
        return b.build()
    }
}
