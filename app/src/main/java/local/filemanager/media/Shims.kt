package local.filemanager

import java.io.IOException

/** 再生エンジンが直接は読めない形式を、読める形に「見せかけて」渡すための部品。Android に依存しない。 */
interface ShimReader {
    /** 読めたバイト数。終わりなら -1。 */
    fun read(out: ByteArray, off: Int, len: Int): Int
}

interface Shim {
    /** 見せかけの形でのファイルの大きさ */
    val size: Long
    fun reader(start: Long, length: Long): ShimReader
}

object Shims {
    private val bdavExt = setOf("m2ts", "mts")
    private val aiffExt = setOf("aif", "aiff", "aifc")
    private val cafExt = setOf("caf")

    /** 変換が要りそうな名前か(開いて中を見る前の、名前だけでの判断)。 */
    fun needed(name: String): Boolean = FileOps.extensionOf(name).let { it in bdavExt || it in aiffExt || it in cafExt }

    fun detect(name: String, src: RandomSource): Shim? = when (FileOps.extensionOf(name)) {
        in bdavExt -> Bdav.detect(src)
        in aiffExt -> AiffWav.detect(src)
        in cafExt -> Caf.detect(src)
        else -> null
    }

    internal fun readFully(src: RandomSource, pos: Long, out: ByteArray, len: Int): Int {
        var got = 0
        while (got < len) {
            val n = src.readAt(pos + got, out, got, len - got)
            if (n <= 0) break
            got += n
        }
        return got
    }
}

/**
 * BDAV 形式(.m2ts / .mts)は、188 バイトの TS パケットの前に 4 バイトの時刻がついた 192 バイト単位。
 * 再生エンジンは 188 バイトの普通の TS しか読めないので、4 バイトを取り除いて見せる。
 */
class Bdav private constructor(private val src: RandomSource) : Shim {
    override val size: Long = (src.size / 192) * 188

    override fun reader(start: Long, length: Long): ShimReader = object : ShimReader {
        private var pos = start
        private var remaining = minOf(length, size - start).coerceAtLeast(0L)
        private val tmp = ByteArray(PACKETS * 192)
        private val out = ByteArray(PACKETS * 188)
        private var oPos = 0
        private var oLen = 0

        private fun fill(): Boolean {
            if (remaining <= 0) return false
            val k = pos / 188
            val off = (pos % 188).toInt()
            val want = minOf(tmp.size.toLong(), src.size - k * 192).toInt()
            if (want < 192) return false
            val got = Shims.readFully(src, k * 192, tmp, want)
            val packets = got / 192
            if (packets == 0) throw IOException("ファイルが途中までしか読めませんでした")
            for (i in 0 until packets) System.arraycopy(tmp, i * 192 + 4, out, i * 188, 188)
            oPos = off
            oLen = packets * 188
            return oPos < oLen
        }

        override fun read(out2: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (remaining <= 0) return -1
            if (oPos >= oLen && !fill()) return -1
            val n = minOf(len.toLong(), (oLen - oPos).toLong(), remaining).toInt()
            System.arraycopy(out, oPos, out2, off, n)
            oPos += n
            pos += n
            remaining -= n
            return n
        }
    }

    companion object {
        private const val PACKETS = 2048

        /** 先頭の 3 パケットを見て、BDAV(4 バイト目から 192 バイトおきに 0x47)か判断する。普通の TS(0 バイト目から 188 バイトおき)は対象外。 */
        fun detect(src: RandomSource): Bdav? {
            if (src.size < 192 * 3) return null
            val b = ByteArray(192 * 2 + 5)
            if (Shims.readFully(src, 0, b, b.size) < b.size) return null
            val bdav = b[4] == 0x47.toByte() && b[196] == 0x47.toByte() && b[388] == 0x47.toByte()
            val plain = b[0] == 0x47.toByte() && b[188] == 0x47.toByte()
            return if (bdav && !plain) Bdav(src) else null
        }
    }
}

/**
 * AIFF(Mac の音声)は、数を「上の桁から」並べる(ビッグエンディアン)形で、再生エンジンが読めない。
 * 中身(PCM)の並びを入れ替えて、WAV のように見せる。AIFC の 'sowt'(並びが逆)も読む。圧縮されたものは対象外。
 */
class AiffWav private constructor(
    private val src: RandomSource,
    private val channels: Int,
    private val bits: Int,
    private val rate: Int,
    private val dataStart: Long,
    private val dataLen: Long,
    private val bigEndian: Boolean,
    private val float: Boolean = false,
) : Shim {
    private val bps = bits / 8
    private val header: ByteArray = ByteArray(44).also { h ->
        fun put(o: Int, s: String) = s.forEachIndexed { i, c -> h[o + i] = c.code.toByte() }
        fun le32(o: Int, v: Long) { for (i in 0 until 4) h[o + i] = (v shr (8 * i)).toByte() }
        fun le16(o: Int, v: Int) { h[o] = v.toByte(); h[o + 1] = (v shr 8).toByte() }
        put(0, "RIFF"); le32(4, 36 + dataLen); put(8, "WAVE"); put(12, "fmt "); le32(16, 16)
        le16(20, if (float) 3 else 1); le16(22, channels); le32(24, rate.toLong()); le32(28, rate.toLong() * channels * bps)
        le16(32, channels * bps); le16(34, bits); put(36, "data"); le32(40, dataLen)
    }
    override val size: Long = 44 + dataLen

    override fun reader(start: Long, length: Long): ShimReader = object : ShimReader {
        private var pos = start
        private var remaining = minOf(length, size - start).coerceAtLeast(0L)
        private val buf = ByteArray(256 * 1024 / 12 * 12)
        private var bPos = 0
        private var bLen = 0

        private fun fill(): Boolean {
            if (remaining <= 0) return false
            if (pos < 44) {
                val n = minOf(44 - pos, remaining).toInt()
                System.arraycopy(header, pos.toInt(), buf, 0, n)
                bPos = 0
                bLen = n
            } else {
                val p = pos - 44
                val a = p - p % bps
                val need = minOf(buf.size.toLong(), dataLen - a).toInt()
                if (need <= 0) return false
                val got = Shims.readFully(src, dataStart + a, buf, need)
                if (got <= 0) throw IOException("ファイルが途中までしか読めませんでした")
                val usable = got - got % bps
                convert(buf, usable)
                bPos = (p - a).toInt()
                bLen = minOf(usable.toLong(), bPos + remaining).toInt()
                if (bPos >= bLen) return false
            }
            val n = bLen - bPos
            pos += n
            remaining -= n
            return true
        }

        override fun read(out: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (bPos >= bLen && !fill()) return -1
            val n = minOf(len, bLen - bPos)
            System.arraycopy(buf, bPos, out, off, n)
            bPos += n
            return n
        }
    }

    /** 上の桁から並んだ数を、下の桁から並んだ数(WAV)にする。8 ビットは、符号つき → 符号なし。 */
    private fun convert(b: ByteArray, n: Int) {
        when (bps) {
            1 -> for (i in 0 until n) b[i] = (b[i].toInt() xor 0x80).toByte()
            2 -> if (bigEndian) { var i = 0; while (i + 1 < n) { val t = b[i]; b[i] = b[i + 1]; b[i + 1] = t; i += 2 } }
            3 -> if (bigEndian) { var i = 0; while (i + 2 < n) { val t = b[i]; b[i] = b[i + 2]; b[i + 2] = t; i += 3 } }
            4 -> if (bigEndian) { var i = 0; while (i + 3 < n) { val t0 = b[i]; val t1 = b[i + 1]; b[i] = b[i + 3]; b[i + 1] = b[i + 2]; b[i + 2] = t1; b[i + 3] = t0; i += 4 } }
        }
    }

    companion object {
        /** CAF のリニア PCM など、並びと位置が分かっているものを WAV に見せる。 */
        fun forPcm(src: RandomSource, channels: Int, bits: Int, rate: Int, dataStart: Long, dataLen: Long, bigEndian: Boolean, float: Boolean): AiffWav =
            AiffWav(src, channels, bits, rate, dataStart, dataLen, bigEndian, float)

        private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
        private fun u32(b: ByteArray, o: Int) = (u16(b, o).toLong() shl 16) or u16(b, o + 2).toLong()

        fun detect(src: RandomSource): AiffWav? {
            if (src.size < 12 + 8) return null
            val head = ByteArray(12)
            if (Shims.readFully(src, 0, head, 12) < 12) return null
            val form = String(head, 0, 4, Charsets.ISO_8859_1)
            val kind = String(head, 8, 4, Charsets.ISO_8859_1)
            if (form != "FORM" || (kind != "AIFF" && kind != "AIFC")) return null
            var channels = 0
            var frames = 0L
            var bits = 0
            var rate = 0
            var big = true
            var comm = false
            var dataStart = -1L
            var ssndLen = 0L
            var p = 12L
            var guard = 0
            while (p + 8 <= src.size && guard++ < 200) {
                val ch = ByteArray(8)
                if (Shims.readFully(src, p, ch, 8) < 8) break
                val id = String(ch, 0, 4, Charsets.ISO_8859_1)
                val len = u32(ch, 4)
                if (id == "COMM") {
                    val c = ByteArray(minOf(len, 38L).toInt().coerceAtLeast(18))
                    if (Shims.readFully(src, p + 8, c, c.size) < 18) return null
                    channels = u16(c, 0)
                    frames = u32(c, 2)
                    bits = u16(c, 6)
                    val exp = u16(c, 8) and 0x7FFF
                    var mant = 0.0
                    for (i in 0 until 8) mant = mant * 256.0 + (c[10 + i].toInt() and 0xFF)
                    rate = (mant * Math.pow(2.0, (exp - 16383 - 63).toDouble())).toInt()
                    if (kind == "AIFC") {
                        if (c.size < 22) return null
                        when (String(c, 18, 4, Charsets.ISO_8859_1)) {
                            "NONE", "twos" -> big = true
                            "sowt" -> big = false
                            else -> return null
                        }
                    }
                    comm = true
                } else if (id == "SSND") {
                    val o = ByteArray(8)
                    if (Shims.readFully(src, p + 8, o, 8) < 8) return null
                    val skip = u32(o, 0)
                    dataStart = p + 16 + skip
                    ssndLen = (len - 8 - skip).coerceAtLeast(0L)
                    if (comm) break
                }
                if (comm && dataStart >= 0) break
                p += 8 + len + (len and 1L)
            }
            if (!comm || dataStart < 0 || channels !in 1..8 || bits !in intArrayOf(8, 16, 24, 32) || rate <= 0) return null
            val frame = channels * (bits / 8)
            var dataLen = minOf(ssndLen, frames * frame, (src.size - dataStart).coerceAtLeast(0L))
            dataLen -= dataLen % frame
            if (dataLen <= 0) return null
            return AiffWav(src, channels, bits, rate, dataStart, dataLen, big)
        }
    }
}
