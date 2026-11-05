package local.filemanager

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 3D モデルの三角形の集まり(points = true のときは点群)。頂点は x y z の Float 3 個ずつ。
 * colors があれば、頂点と同じ並びの r g b(0〜1)。無ければ表示側の既定の色。
 */
class Mesh(
    val vertices: FloatBuffer,
    val vertexCount: Int,
    val min: FloatArray,
    val max: FloatArray,
    val colors: FloatBuffer? = null,
    val points: Boolean = false,
) {
    val triangles: Int get() = if (points) 0 else vertexCount / 3
    val sizeX: Float get() = max[0] - min[0]
    val sizeY: Float get() = max[1] - min[1]
    val sizeZ: Float get() = max[2] - min[2]
}

/** バイナリ形式と ASCII 形式の STL を読む。Android に依存しない。 */
object Stl {
    /** ビューアで開くファイルの大きさの上限(バイナリ。1 三角形 50 バイト → 約 160 万枚)。 */
    const val MAX_BINARY_BYTES = 80L * 1024 * 1024
    const val MAX_ASCII_BYTES = 40L * 1024 * 1024

    private class Bounds {
        val min = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val max = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        fun add(x: Float, y: Float, z: Float) {
            if (x < min[0]) min[0] = x
            if (y < min[1]) min[1] = y
            if (z < min[2]) min[2] = z
            if (x > max[0]) max[0] = x
            if (y > max[1]) max[1] = y
            if (z > max[2]) max[2] = z
        }
    }

    private fun direct(floats: Int): FloatBuffer =
        ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    /** size: ファイルの大きさ。形式が読めない・大きすぎる・三角形が無いときは OpException。 */
    fun parse(input: InputStream, size: Long): Mesh {
        val head = ByteArray(84)
        var got = 0
        while (got < head.size) {
            val n = input.read(head, got, head.size - got)
            if (n < 0) break
            got += n
        }
        if (got < 15) throw OpException(Txt(R.string.stl_001))
        val startsSolid = String(head, 0, 5, Charsets.US_ASCII).equals("solid", ignoreCase = true)
        val count = if (got >= 84) (ByteBuffer.wrap(head, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL) else -1L
        val binaryExact = count >= 0 && 84 + 50 * count == size
        // 「solid」で始まっていても、大きさがぴったり合うバイナリがある(ヘッダーに solid と書く作成ソフトがある)
        val binary = binaryExact || (!startsSolid && count in 1..((size - 84) / 50))
        return if (binary) {
            if (size > MAX_BINARY_BYTES) throw OpException(Txt(R.string.stl_002, MAX_BINARY_BYTES / 1024 / 1024))
            readBinary(input, count.toInt())
        } else {
            if (size > MAX_ASCII_BYTES) throw OpException(Txt(R.string.stl_003, MAX_ASCII_BYTES / 1024 / 1024))
            val rest = input.readBytes()
            val all = ByteArray(got + rest.size)
            System.arraycopy(head, 0, all, 0, got)
            System.arraycopy(rest, 0, all, got, rest.size)
            parseAscii(String(all, Charsets.US_ASCII))
        }
    }

    fun parse(bytes: ByteArray): Mesh = parse(bytes.inputStream(), bytes.size.toLong())

    private fun readBinary(input: InputStream, count: Int): Mesh {
        if (count <= 0) throw OpException(Txt(R.string.stl_004))
        val buf = direct(count * 9)
        val b = Bounds()
        val rec = ByteArray(50 * 1024) // 1024 三角形ずつ
        val bb = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN)
        var left = count
        var written = 0
        while (left > 0) {
            val want = minOf(left, 1024) * 50
            var got = 0
            while (got < want) {
                val n = input.read(rec, got, want - got)
                if (n < 0) break
                got += n
            }
            val tris = got / 50
            if (tris == 0) break
            for (t in 0 until tris) {
                val o = t * 50 + 12 // 法線(12 バイト)は使わず、頂点だけ読む
                var ok = true
                val v = FloatArray(9)
                for (k in 0 until 9) {
                    v[k] = bb.getFloat(o + k * 4)
                    if (v[k].isNaN() || v[k].isInfinite()) ok = false
                }
                if (!ok) continue
                for (k in 0 until 3) {
                    b.add(v[k * 3], v[k * 3 + 1], v[k * 3 + 2])
                }
                buf.put(v)
                written++
            }
            left -= tris
            if (tris < minOf(left + tris, 1024)) break // ファイルが途中で終わっている
        }
        if (written == 0) throw OpException(Txt(R.string.stl_005))
        buf.flip()
        return Mesh(buf, written * 3, b.min, b.max)
    }

    private fun parseAscii(text: String): Mesh {
        // 「vertex x y z」の 3 つで 1 三角形
        val vals = ArrayList<Float>()
        var i = 0
        val n = text.length
        while (true) {
            val at = text.indexOf("vertex", i, ignoreCase = true)
            if (at < 0) break
            i = at + 6
            for (k in 0 until 3) {
                while (i < n && text[i].isWhitespace()) i++
                val s = i
                while (i < n && !text[i].isWhitespace()) i++
                vals.add(text.substring(s, i).toFloatOrNull() ?: Float.NaN)
            }
        }
        val tris = vals.size / 9
        if (tris == 0) throw OpException(Txt(R.string.stl_006))
        val buf = direct(tris * 9)
        val b = Bounds()
        var written = 0
        for (t in 0 until tris) {
            var ok = true
            for (k in 0 until 9) if (vals[t * 9 + k].isNaN() || vals[t * 9 + k].isInfinite()) ok = false
            if (!ok) continue
            for (k in 0 until 3) b.add(vals[t * 9 + k * 3], vals[t * 9 + k * 3 + 1], vals[t * 9 + k * 3 + 2])
            for (k in 0 until 9) buf.put(vals[t * 9 + k])
            written++
        }
        if (written == 0) throw OpException(Txt(R.string.stl_007))
        buf.flip()
        return Mesh(buf, written * 3, b.min, b.max)
    }
}
