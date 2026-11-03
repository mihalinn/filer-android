package local.filemanager

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * LZH(LHA)書庫を、ファイルの好きな位置から読みながら開く。先頭から順に見出し(ヘッダー)をたどって一覧を作り、
 * 取り出すときは必要な部分だけを読む。ヘッダーは level 0〜3。圧縮方式は -lh0-(無圧縮)・-lh4-〜-lh7-・-lz4-(無圧縮)。
 * -lh1-・-lh2-・-lh3-・-lzs-・-lz5- は一覧には出るが、取り出すときに未対応のエラーにする。
 * 取り出した中身は、見出しにある CRC-16 で確かめる。Android に依存しない。
 */
class LzhReader(private val src: RandomSource) {
    class Entry(
        val name: String,
        val isDir: Boolean,
        /** 圧縮方式("-lh5-" など)。 */
        val method: String,
        val compressedSize: Long,
        val size: Long,
        /** 圧縮された中身の、ファイルの中の位置。 */
        val dataPos: Long,
        val crc: Int,
        val modified: Long,
    )

    val entries: List<Entry> = readEntries()

    private fun readAt(pos: Long, len: Int): ByteArray {
        if (len < 0 || pos < 0 || pos + len > src.size) throw OpException("LZHが途中で切れているか、壊れています")
        val b = ByteArray(len)
        var got = 0
        while (got < len) {
            val n = src.readAt(pos + got, b, got, len - got)
            if (n <= 0) throw OpException("LZHが途中で切れているか、壊れています")
            got += n
        }
        return b
    }

    private fun readEntries(): List<Entry> {
        val out = ArrayList<Entry>()
        var pos = 0L
        while (pos + 22 <= src.size && out.size < MAX_ENTRIES) {
            val head = readAt(pos, minOf(32L, src.size - pos).toInt())
            if (head[0].toInt() == 0) break
            val level = head[20].toInt() and 0xFF
            val total = when (level) {
                0, 1 -> (head[0].toInt() and 0xFF) + 2
                2 -> u16(head, 0)
                3 -> if (head.size >= 28) u32(head, 24).toInt() else -1
                else -> -1
            }
            val methodOk = isMethod(head)
            if (total < 24 || total > MAX_HEADER || !methodOk || pos + total > src.size) {
                if (out.isEmpty()) throw OpException("LZHとして読めません")
                break
            }
            val h = if (total <= head.size) head.copyOf(total) else readAt(pos, total)
            if (level <= 1) {
                var sum = 0
                for (i in 2 until total) sum += h[i].toInt() and 0xFF
                if ((sum and 0xFF) != (h[1].toInt() and 0xFF)) {
                    if (out.isEmpty()) throw OpException("LZHの見出しが壊れています")
                    break
                }
            }
            val e = parse(h, level, total, pos)
            if (e.second < 0) {
                if (out.isEmpty()) throw OpException("LZHの見出しが壊れています")
                break
            }
            e.first?.let { out.add(it) }
            pos = e.second
        }
        if (out.isEmpty() && src.size > 1) throw OpException("LZHとして読めません")
        return out
    }

    /** 見出し 1 つを読む。戻りは (中身の一覧に入れるもの, 次の見出しの位置)。位置が負なら、壊れている。 */
    private fun parse(h: ByteArray, level: Int, total: Int, pos: Long): Pair<Entry?, Long> {
        val method = String(h, 2, 5, Charsets.ISO_8859_1)
        val packed = u32(h, 7)
        val size = u32(h, 11)
        var name: ByteArray? = null
        var dir: ByteArray? = null
        var crc = 0
        var modified = 0L
        var extModified = 0L
        var dataPos = pos + total
        var compressed = packed

        fun ext(type: Int, b: ByteArray, off: Int, len: Int) {
            when (type) {
                0x01 -> name = b.copyOfRange(off, off + len)
                0x02 -> dir = b.copyOfRange(off, off + len)
                0x41 -> if (len >= 24) extModified = fileTime(b, off + 8)
                0x54 -> if (len >= 4) extModified = u32(b, off) * 1000L
            }
        }

        when (level) {
            0, 1 -> {
                val nl = h[21].toInt() and 0xFF
                if (22 + nl > total) return null to -1L
                name = h.copyOfRange(22, 22 + nl)
                if (total >= 24 + nl) crc = u16(h, 22 + nl)
                modified = dosTime(u32(h, 15))
                if (level == 1) {
                    // 見出しのあとに、拡張見出しが続く。圧縮サイズには、拡張見出しの分も入っている
                    var p = pos + total
                    var s = u16(h, total - 2)
                    var extBytes = 0L
                    var guard = 0
                    while (s != 0) {
                        if (s < 3 || s > MAX_HEADER || ++guard > 64) return null to -1L
                        val body = readAt(p, s)
                        ext(body[0].toInt() and 0xFF, body, 1, s - 3)
                        extBytes += s
                        p += s
                        s = u16(body, s - 2)
                    }
                    compressed = packed - extBytes
                    dataPos = p
                    if (compressed < 0) return null to -1L
                }
            }
            2, 3 -> {
                val w = if (level == 2) 2 else 4
                crc = u16(h, 21)
                modified = u32(h, 15) * 1000L
                var o = if (level == 2) 24 else 28
                var s = if (w == 2) u16(h, o) else u32(h, o).toInt()
                var guard = 0
                while (s != 0) {
                    if (s < w + 1 || o + s + w > total || ++guard > 64) return null to -1L
                    ext(h[o + w].toInt() and 0xFF, h, o + w + 1, s - w - 1)
                    o += s
                    s = if (w == 2) u16(h, o) else u32(h, o).toInt()
                }
            }
        }
        if (extModified != 0L) modified = extModified
        val next = dataPos + compressed
        if (compressed < 0 || next > src.size) return null to -1L

        val isDir = method == "-lhd-"
        val dirText = dir?.let { decodeName(it.map { b -> if ((b.toInt() and 0xFF) == 0xFF) '/'.code.toByte() else b }.toByteArray()) } ?: ""
        val nameText = name?.let { decodeName(it) } ?: ""
        val path = cleanPath((dirText + nameText).replace('\\', '/'))
        // 保存先の名前が空のもの(目次だけの見出しなど)は一覧に出さない
        if (path.isEmpty()) return null to next
        if (method == "-lhs-") return null to next
        return Entry(path, isDir, method, compressed, if (isDir) 0L else size, dataPos, crc, modified) to next
    }

    /** 中身を取り出す入力。最後まで読むと、CRC を確かめる(合わなければ IOException)。 */
    fun open(e: Entry): InputStream {
        if (e.isDir || e.size == 0L) return ByteArrayInputStream(ByteArray(0))
        val packed = PackedBytes(src, e.dataPos, e.dataPos + e.compressedSize)
        return when (e.method) {
            "-lh0-", "-lz4-" -> StoredInput(packed, e.size, e.crc)
            "-lh4-" -> LhDecoder(packed, 12, e.size, e.crc)
            "-lh5-" -> LhDecoder(packed, 13, e.size, e.crc)
            "-lh6-" -> LhDecoder(packed, 15, e.size, e.crc)
            "-lh7-" -> LhDecoder(packed, 16, e.size, e.crc)
            else -> throw OpException("圧縮方式 ${e.method} は未対応")
        }
    }

    private companion object {
        const val MAX_ENTRIES = 200_000
        const val MAX_HEADER = 1 shl 20

        fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun u32(b: ByteArray, o: Int) = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)

        /** "-lh5-" のような圧縮方式の印か。 */
        fun isMethod(h: ByteArray): Boolean =
            h.size >= 7 && h[2].toInt() == '-'.code && h[3].toInt() == 'l'.code && h[6].toInt() == '-'.code &&
                (h[4].toInt().toChar() in 'a'..'z') && (h[5].toInt().toChar() in '0'..'9' || h[5].toInt().toChar() in 'a'..'z')

        fun dosTime(v: Long): Long = try {
            val d = ((v ushr 16) and 0xFFFF).toInt()
            val t = (v and 0xFFFF).toInt()
            LocalDateTime.of(1980 + (d ushr 9), (d ushr 5) and 15, d and 31, t ushr 11, (t ushr 5) and 63, (t and 31) * 2)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (e: Exception) {
            0L
        }

        /** Windows の FILETIME(1601 年からの 100 ナノ秒)を、ミリ秒(1970 年から)にする。 */
        fun fileTime(b: ByteArray, o: Int): Long {
            val ft = u32(b, o) or (u32(b, o + 4) shl 32)
            if (ft <= 0L) return 0L
            return ft / 10_000 - 11_644_473_600_000L
        }

        private val shiftJis: Charset by lazy { Charset.forName("windows-31j") }

        /** ファイル名の文字コード。UTF-8 として読めればそれ、そうでなければ Shift_JIS(日本のWindowsで作られたもの)。 */
        fun decodeName(b: ByteArray): String {
            if (b.all { it >= 0 }) return String(b, Charsets.US_ASCII)
            return try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(b)).toString()
            } catch (e: CharacterCodingException) {
                String(b, shiftJis)
            }
        }

        /** 区切りを '/' にそろえ、先頭の '/'・"."・".." を取り除く(展開先の外に出ないように)。 */
        fun cleanPath(p: String): String {
            val parts = p.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
            return parts.joinToString("/")
        }
    }
}

/** 圧縮された中身を、書庫の中の範囲だけ読む。範囲の終わりより先は読まない(-1)。 */
internal class PackedBytes(private val src: RandomSource, private var pos: Long, private val end: Long) {
    private val buf = ByteArray(64 * 1024)
    private var bufStart = 0L
    private var bufLen = 0

    /** 次の 1 バイト。終わりなら -1。 */
    fun next(): Int {
        if (pos >= end) return -1
        if (pos < bufStart || pos >= bufStart + bufLen) {
            val want = minOf(buf.size.toLong(), end - pos).toInt()
            var got = 0
            while (got < want) {
                val n = src.readAt(pos + got, buf, got, want - got)
                if (n <= 0) throw IOException("LZHが途中で切れています")
                got += n
            }
            bufStart = pos
            bufLen = got
        }
        return buf[(pos++ - bufStart).toInt()].toInt() and 0xFF
    }
}

internal object Crc16 {
    private val table = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var c = i
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xA001 else c ushr 1 }
            t[i] = c
        }
    }

    fun update(crc: Int, b: Int): Int = (crc ushr 8) xor table[(crc xor b) and 0xFF]
}

/** -lh0- など、圧縮していない中身。 */
private class StoredInput(private val packed: PackedBytes, private val total: Long, private val expectedCrc: Int) : InputStream() {
    private var done = 0L
    private var crc = 0

    override fun read(): Int {
        val b = ByteArray(1)
        return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (done >= total) return -1
        val want = minOf(len.toLong(), total - done).toInt()
        var n = 0
        while (n < want) {
            val c = packed.next()
            if (c < 0) throw IOException("LZHが途中で切れています")
            b[off + n++] = c.toByte()
            crc = Crc16.update(crc, c)
        }
        done += n
        if (done >= total && crc != expectedCrc) throw IOException("LZHの中身が壊れています(CRCが合いません)")
        return n
    }
}

/**
 * -lh4-〜-lh7- の展開(LZSS + ハフマン符号。奥村晴彦さんの ar002 で知られる方式を、この形式の公開されている仕様に沿って書き起こした)。辞書の大きさは 2 の dicBit 乗(lh4: 4KB、lh5: 8KB、lh6: 32KB、lh7: 64KB)。
 * 出力は total バイトで終わる。足りない入力はゼロとして読み、壊れた符号は IOException にする。
 */
private class LhDecoder(
    private val packed: PackedBytes,
    dicBit: Int,
    private val total: Long,
    private val expectedCrc: Int,
) : InputStream() {
    private val dicMask = (1 shl dicBit) - 1
    private val window = ByteArray(1 shl dicBit).also { java.util.Arrays.fill(it, ' '.code.toByte()) }
    private var wpos = 0
    private var produced = 0L
    private var crc = 0
    private var matchLeft = 0
    private var matchSrc = 0

    private var bitbuf = 0
    private var subbuf = 0
    private var bitcount = 0
    private var blockSize = 0

    private val np = dicBit + 1
    private val pbit = if (dicBit <= 13) 4 else 5
    private val cLen = ByteArray(NC)
    private val cTable = IntArray(4096)
    private val cLeft = IntArray(2 * NC)
    private val cRight = IntArray(2 * NC)
    private val ptLen = ByteArray(NPT)
    private val ptTable = IntArray(256)
    private val pLeft = IntArray(2 * NPT)
    private val pRight = IntArray(2 * NPT)

    init {
        fillBuf(16)
    }

    override fun read(): Int {
        val b = ByteArray(1)
        return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (produced >= total) return -1
        val want = minOf(len.toLong(), total - produced).toInt()
        var n = 0
        while (n < want) {
            val v: Byte
            if (matchLeft > 0) {
                v = window[matchSrc]
                matchSrc = (matchSrc + 1) and dicMask
                matchLeft--
            } else {
                val c = decodeC()
                if (c < 256) {
                    v = c.toByte()
                } else {
                    matchLeft = c - 253
                    val p = decodeP()
                    matchSrc = (wpos - p - 1) and dicMask
                    continue
                }
            }
            window[wpos] = v
            wpos = (wpos + 1) and dicMask
            b[off + n++] = v
            crc = Crc16.update(crc, v.toInt() and 0xFF)
        }
        produced += n
        if (produced >= total && crc != expectedCrc) throw IOException("LZHの中身が壊れています(CRCが合いません)")
        return n
    }

    private fun fillBuf(n0: Int) {
        var n = n0
        while (n > bitcount) {
            n -= bitcount
            bitbuf = ((bitbuf shl bitcount) + (subbuf ushr (8 - bitcount))) and 0xFFFF
            subbuf = maxOf(packed.next(), 0)
            bitcount = 8
        }
        bitcount -= n
        bitbuf = ((bitbuf shl n) + (subbuf ushr (8 - n))) and 0xFFFF
        subbuf = (subbuf shl n) and 0xFF
    }

    private fun getBits(n: Int): Int {
        val x = bitbuf ushr (16 - n)
        fillBuf(n)
        return x
    }

    /** 符号表から 1 つ読む。表に収まらない長い符号は、木をたどる。 */
    private fun huff(table: IntArray, left: IntArray, right: IntArray, bits: Int, nchar: Int): Int {
        var j = table[bitbuf ushr (16 - bits)]
        if (j >= nchar) {
            var mask = 1 shl (15 - bits)
            do {
                if (mask == 0) throw IOException("LZHの符号が壊れています")
                j = if (bitbuf and mask != 0) right[j] else left[j]
                mask = mask ushr 1
            } while (j >= nchar)
        }
        return j
    }

    private fun readPtLen(nn: Int, nbit: Int, special: Int) {
        val n = getBits(nbit)
        if (n == 0) {
            val c = getBits(nbit)
            java.util.Arrays.fill(ptLen, 0, nn, 0)
            java.util.Arrays.fill(ptTable, c)
            return
        }
        if (n > nn) throw IOException("LZHの符号が壊れています")
        var i = 0
        while (i < n) {
            var c = bitbuf ushr 13
            if (c == 7) {
                var mask = 1 shl 12
                while (bitbuf and mask != 0) {
                    mask = mask ushr 1
                    c++
                    if (mask == 0) throw IOException("LZHの符号が壊れています")
                }
            }
            fillBuf(if (c < 7) 3 else c - 3)
            ptLen[i++] = c.toByte()
            if (i == special) {
                var z = getBits(2)
                while (--z >= 0) ptLen[i++] = 0
            }
        }
        while (i < nn) ptLen[i++] = 0
        makeTable(nn, ptLen, 8, ptTable, pLeft, pRight)
    }

    private fun readCLen() {
        val n = getBits(9)
        if (n == 0) {
            val c = getBits(9)
            java.util.Arrays.fill(cLen, 0)
            java.util.Arrays.fill(cTable, c)
            return
        }
        if (n > NC) throw IOException("LZHの符号が壊れています")
        var i = 0
        while (i < n) {
            var c = huff(ptTable, pLeft, pRight, 8, NT)
            fillBuf(ptLen[c].toInt())
            if (c <= 2) {
                c = when (c) {
                    0 -> 1
                    1 -> getBits(4) + 3
                    else -> getBits(9) + 20
                }
                if (i + c > NC) throw IOException("LZHの符号が壊れています")
                while (--c >= 0) cLen[i++] = 0
            } else {
                cLen[i++] = (c - 2).toByte()
            }
        }
        while (i < NC) cLen[i++] = 0
        makeTable(NC, cLen, 12, cTable, cLeft, cRight)
    }

    private fun decodeC(): Int {
        if (blockSize == 0) {
            blockSize = getBits(16)
            readPtLen(NT, 5, 3)
            readCLen()
            readPtLen(np, pbit, -1)
            if (blockSize == 0) blockSize = 0x10000
        }
        blockSize--
        val j = huff(cTable, cLeft, cRight, 12, NC)
        fillBuf(cLen[j].toInt())
        return j
    }

    private fun decodeP(): Int {
        var j = huff(ptTable, pLeft, pRight, 8, np)
        fillBuf(ptLen[j].toInt())
        if (j != 0) j = (1 shl (j - 1)) + getBits(j - 1)
        return j
    }

    /** 符号の長さの並びから、先頭 tableBits ビットで引ける表と、それより長い符号の木を作る。 */
    private fun makeTable(nchar: Int, bitlen: ByteArray, tableBits: Int, table: IntArray, left: IntArray, right: IntArray) {
        val count = IntArray(17)
        val weight = IntArray(17)
        val start = IntArray(18)
        for (i in 0 until nchar) {
            val bl = bitlen[i].toInt()
            if (bl < 0 || bl > 16) throw IOException("LZHの符号が壊れています")
            count[bl]++
        }
        for (i in 1..16) start[i + 1] = start[i] + (count[i] shl (16 - i))
        if ((start[17] and 0xFFFF) != 0) throw IOException("LZHの符号が壊れています")
        val jutbits = 16 - tableBits
        for (i in 1..tableBits) {
            start[i] = start[i] ushr jutbits
            weight[i] = 1 shl (tableBits - i)
        }
        for (i in tableBits + 1..16) weight[i] = 1 shl (16 - i)
        var i = start[tableBits + 1] ushr jutbits
        if (i != 0) {
            val k = 1 shl tableBits
            while (i != k) table[i++] = 0
        }
        var avail = nchar
        val mask = 1 shl (15 - tableBits)
        for (ch in 0 until nchar) {
            val len = bitlen[ch].toInt()
            if (len == 0) continue
            var k = start[len]
            val next = k + weight[len]
            if (len <= tableBits) {
                if (next > table.size) throw IOException("LZHの符号が壊れています")
                for (x in k until next) table[x] = ch
            } else {
                var arr = table
                var idx = k ushr jutbits
                var n = len - tableBits
                while (n != 0) {
                    if (arr[idx] == 0) {
                        if (avail >= left.size) throw IOException("LZHの符号が壊れています")
                        left[avail] = 0
                        right[avail] = 0
                        arr[idx] = avail++
                    }
                    val node = arr[idx]
                    arr = if (k and mask != 0) right else left
                    idx = node
                    k = k shl 1
                    n--
                }
                arr[idx] = ch
            }
            start[len] = next
        }
    }

    private companion object {
        /** 文字 256 + 長さ 254(3〜256)。 */
        const val NC = 510
        const val NT = 19
        const val NPT = 0x80
    }
}
