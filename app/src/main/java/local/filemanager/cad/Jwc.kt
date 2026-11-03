package local.filemanager

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * JW_CAD の旧形式(.jwc。ファイルの先頭が「jw_cad(c)data」)の読み込み。
 * 固定長の記録が並ぶ形式で、構造は公開されている ezjww(MIT ライセンス、https://github.com/monozukuri-ai/ezjww)の解析を参考にした。
 * 座標は用紙上の位置(0〜518)で書かれているので、用紙の大きさと縮尺から実寸(mm)に直す。Android に依存しない。
 */
object Jwc {
    const val MAX_BYTES = 64L * 1024 * 1024
    private const val SCALES = 1797
    private const val TEXT_PRESETS = 1709
    private const val MAX_ENTITIES = 100_000

    fun isJwc(b: ByteArray): Boolean = b.size >= 40 && String(b, 0, 13, Charsets.ISO_8859_1) == "jw_cad(c)data"

    /** 用紙 A0〜A4 の大きさ(mm) */
    private val paper = arrayOf(1189.0 to 841.0, 841.0 to 594.0, 594.0 to 420.0, 420.0 to 297.0, 297.0 to 210.0)

    /** 画面の色 1〜9(Jw_cad の標準の並び)。0 は自動色 */
    private val pens = intArrayOf(
        0, 0, 0xFFFF0000.toInt(), 0xFF00A000.toInt(), 0xFF0000FF.toInt(), 0xFF00A0A0.toInt(), 0xFFA000A0.toInt(), 0xFFA0A000.toInt(), 0xFF808080.toInt(), 0xFF808080.toInt(),
    )

    private fun csv(d: ByteArray, start: Int): List<String> {
        if (start + 199 > d.size) throw OpException("JW_CAD(旧形式)のヘッダーが短すぎます")
        var end = start
        while (end < start + 199 && d[end] != 0.toByte()) end++
        return String(d, start, end - start, Charsets.ISO_8859_1).split(',').map { it.trim() }
    }

    private fun far(s: String): Long? {
        val p = s.split(':')
        if (p.size != 2) return null
        val a = p[0].toLongOrNull(16) ?: return null
        val b = p[1].toLongOrNull(16) ?: return null
        return (a shl 16) or b
    }

    fun parse(d: ByteArray): CadDrawing {
        if (!isJwc(d)) throw OpException("JW_CAD(旧形式 .jwc)として読めません")
        val u16Scales = d[22] == '.'.code.toByte()
        val header = if (u16Scales) 2389 else 2421
        if (d.size < header) throw OpException("JW_CAD(旧形式)のヘッダーが短すぎます")
        val bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        fun u16(o: Int) = bb.getShort(o).toInt() and 0xFFFF
        fun u32(o: Int) = bb.getInt(o).toLong() and 0xFFFFFFFFL
        fun f32(o: Int) = bb.getFloat(o).toDouble()

        val f = csv(d, 200)
        val st = csv(d, 600)
        if (f.size < 12 || st.size < 2) throw OpException("JW_CAD(旧形式)のヘッダーが読めません")
        val counts = IntArray(5) { f[it].toIntOrNull() ?: throw OpException("JW_CAD(旧形式)の図形の数が読めません") }
        if (counts.any { it < 0 } || counts.sum() > MAX_ENTITIES) throw OpException("JW_CAD(旧形式)の図形が多すぎます")
        val (pw, ph) = paper.getOrNull(f[11].toIntOrNull() ?: 3) ?: paper[3]
        val extent = f.getOrNull(30)?.toDoubleOrNull()?.takeIf { it > 0 } ?: 518.0
        val poolStart = far(st[0]) ?: throw OpException("JW_CAD(旧形式)の文字の領域が読めません")
        val poolLen = ((far(st[1]) ?: throw OpException("JW_CAD(旧形式)の文字の領域が読めません")) - poolStart).toInt()
        if (poolLen < 0 || poolLen > 0xFFFF) throw OpException("JW_CAD(旧形式)の文字の領域が読めません")

        val lines = header
        val arcs = lines + counts[0] * 22
        val texts = arcs + counts[1] * 32
        val pool = texts + counts[2] * 24
        val points = pool + poolLen
        val names = points + counts[3] * 12
        if (d.size < names) throw OpException("JW_CAD(旧形式)のデータが途中で終わっています")

        val editOff = SCALES + if (u16Scales) 32 else 64
        val visOff = editOff + 272
        fun scaleOf(g: Int): Double {
            val s = if (u16Scales) u16(SCALES + 2 * g).toDouble() else f32(SCALES + 4 * g)
            return if (s > 0 && s.isFinite()) s else 1.0
        }
        fun visible(index: Int) = (d[visOff + index].toInt() and 1) != 0

        val b = CadBuilder()
        val layerIds = HashMap<Int, Int>()
        fun layerOf(packed: Int): Int {
            val g = (packed shr 4) and 15; val l = packed and 15
            return layerIds.getOrPut(packed and 0xFF) {
                val def = "%X-%X".format(g, l)
                val nameOff = if (names + 8 * (16 * g + l) + 8 <= d.size) names + 8 * (16 * g + l) else -1
                val nm = if (nameOff >= 0) cstr(d, nameOff, 8) else ""
                val gOff = names + 2048 + 16 * g
                val gn = if (gOff + 16 <= d.size) cstr(d, gOff, 16) else ""
                val name = buildString {
                    append(def)
                    if (nm.isNotBlank() && nm != def) append(' ').append(nm)
                    if (gn.isNotBlank() && gn != "Group%X".format(g)) insert(0, "[$gn] ")
                }
                b.layer(name, visible(g) && visible(16 + 16 * g + l))
            }
        }
        fun color(c: Int): Int = pens.getOrElse(c) { 0 }
        // 用紙上の位置 → 実寸(mm)
        fun px(x: Double, scale: Double) = (x * pw / extent - pw / 2) * scale
        fun py(y: Double, scale: Double) = (y * pw / extent - ph / 2) * scale

        for (i in 0 until counts[0]) {
            val o = lines + i * 22
            val packed = d[o + 18].toInt() and 0xFF
            val s = scaleOf(packed shr 4)
            b.line(layerOf(packed), color(d[o + 17].toInt() and 0xFF), px(f32(o), s), py(f32(o + 4), s), px(f32(o + 8), s), py(f32(o + 12), s))
        }
        for (i in 0 until counts[1]) {
            val o = arcs + i * 32
            val packed = d[o + 28].toInt() and 0xFF
            val s = scaleOf(packed shr 4)
            val r = f32(o + 8) * pw / extent * s
            val flat = u16(o + 12) / 10000.0
            val a0 = u32(o + 14).toInt() / 65536.0
            val a1 = u32(o + 18).toInt() / 65536.0
            val tilt = Math.toRadians(u32(o + 22).toInt() / 65536.0)
            val cx = px(f32(o), s); val cy = py(f32(o + 4), s)
            if (r <= 0 || flat <= 0) continue
            val full = a0 == a1
            var sweep = Math.toRadians(a1 - a0)
            if (sweep <= 0) sweep += Math.PI * 2
            val t0 = if (full) 0.0 else Math.toRadians(a0)
            val sw = if (full) Math.PI * 2 else sweep
            val p = if (flat == 1.0) CadGeo.arc(cx, cy, r, t0, sw) else CadGeo.ellipse(cx, cy, r, r * flat, tilt, t0, sw)
            b.poly(layerOf(packed), color(d[o + 27].toInt() and 0xFF), p, p.size / 2, full)
        }
        for (i in 0 until counts[2]) {
            val o = texts + i * 24
            val packed = d[o + 21].toInt() and 0xFF
            val s = scaleOf(packed shr 4)
            val preset = d[o + 20].toInt() and 0xFF
            if (preset !in 1..10) continue
            val rel = ((u32(o + 16) - poolStart) and 0xFFFFFFFFL).toInt()
            if (rel < 0 || rel >= poolLen) continue
            var e = pool + rel
            while (e < pool + poolLen && d[e] != 0.toByte()) e++
            val text = Jww.decode(d.copyOfRange(pool + rel, e))
            val h = (u16(TEXT_PRESETS + 44 + 2 * preset).takeIf { it > 0 } ?: 20) / 10.0 * s
            val w = (u16(TEXT_PRESETS + 22 + 2 * preset).takeIf { it > 0 } ?: 20) / 10.0 * s
            val x1 = px(f32(o), s); val y1 = py(f32(o + 4), s); val x2 = px(f32(o + 8), s); val y2 = py(f32(o + 12), s)
            val cells = text.sumOf { ch -> if (ch.code in 0x20..0x80 || ch.code in 0xFF61..0xFF9F) 0.5 else 1.0 }
            val span = hypot(x2 - x1, y2 - y1)
            val factor = if (cells > 0 && span > 0 && h > 0) (span / (h * cells)).coerceIn(0.05, 20.0) else w / h
            b.text(layerOf(packed), color(u16(TEXT_PRESETS + 2 * preset)), x1, y1, h, Math.toDegrees(atan2(y2 - y1, x2 - x1)), text, 0, 1, factor)
        }
        // 点は描かない(仮点も含む)
        return b.build()
    }

    /** 固定長の名前(NUL か空白で終わる) */
    private fun cstr(d: ByteArray, o: Int, n: Int): String {
        var e = o
        while (e < o + n && d[e] != 0.toByte()) e++
        return Jww.decode(d.copyOfRange(o, e)).trim()
    }
}
