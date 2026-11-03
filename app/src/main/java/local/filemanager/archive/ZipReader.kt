package local.filemanager

import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * ZIP を、ファイルの好きな位置から読みながら開く(NAS 上の ZIP を、全部ダウンロードせずに展開するため)。
 * 末尾の目次(セントラルディレクトリ)から中身の一覧を読み、取り出すときは必要な部分だけを読む。
 * パスワード付き・4GB を超える ZIP(ZIP64)・「保存」と「Deflate」以外の圧縮方式は、未対応のエラーにする。
 */
class ZipReader(private val src: RandomSource) {
    data class Entry(
        val name: String,
        val isDir: Boolean,
        val method: Int,
        val compressedSize: Long,
        val size: Long,
        val localOffset: Long,
        val encrypted: Boolean,
        val modified: Long,
    )

    val entries: List<Entry> = readEntries()

    private fun readAt(pos: Long, len: Int): ByteArray {
        val b = ByteArray(len)
        var got = 0
        while (got < len) {
            val n = src.readAt(pos + got, b, got, len - got)
            if (n <= 0) throw OpException("ZIPが途中で切れているか、壊れています")
            got += n
        }
        return b
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int) = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)

    private class Raw(val nameBytes: ByteArray, val utf8Flag: Boolean, val e: Entry)

    private fun readEntries(): List<Entry> {
        val total = src.size
        if (total < 22) throw OpException("ZIPファイルではありません")
        // 末尾の「目次の終わり」を探す(コメントがあると末尾からずれるので、最大 64KB 手前まで)
        val tailLen = minOf(total, 22L + 65535L).toInt()
        val tail = readAt(total - tailLen, tailLen)
        var eocd = -1
        for (i in tailLen - 22 downTo 0) {
            if (u32(tail, i) == 0x06054b50L) {
                eocd = i
                break
            }
        }
        if (eocd < 0) throw OpException("ZIPファイルではありません")
        val count = u16(tail, eocd + 10)
        val cdSize = u32(tail, eocd + 12)
        val cdOffset = u32(tail, eocd + 16)
        if (count == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            throw OpException("4GBを超えるZIP(ZIP64)は未対応")
        }
        if (cdOffset + cdSize > total) throw OpException("ZIPが壊れています")
        val cd = readAt(cdOffset, cdSize.toInt())
        val raws = ArrayList<Raw>(count)
        var p = 0
        repeat(count) {
            if (p + 46 > cd.size || u32(cd, p) != 0x02014b50L) throw OpException("ZIPの目次が壊れています")
            val flags = u16(cd, p + 8)
            val method = u16(cd, p + 10)
            val dosTime = u16(cd, p + 12)
            val dosDate = u16(cd, p + 14)
            val csize = u32(cd, p + 20)
            val usize = u32(cd, p + 24)
            val nameLen = u16(cd, p + 28)
            val extraLen = u16(cd, p + 30)
            val commentLen = u16(cd, p + 32)
            val offset = u32(cd, p + 42)
            if (csize == 0xFFFFFFFFL || usize == 0xFFFFFFFFL || offset == 0xFFFFFFFFL) {
                throw OpException("4GBを超えるZIP(ZIP64)は未対応")
            }
            if (p + 46 + nameLen > cd.size) throw OpException("ZIPの目次が壊れています")
            val nameBytes = cd.copyOfRange(p + 46, p + 46 + nameLen)
            val isDir = nameBytes.isNotEmpty() && nameBytes.last() == '/'.code.toByte()
            raws.add(
                Raw(
                    nameBytes, (flags and 0x800) != 0,
                    Entry("", isDir, method, csize, usize, offset, (flags and 1) != 0, dosToMillis(dosDate, dosTime)),
                ),
            )
            p += 46 + nameLen + extraLen + commentLen
        }
        val cs = pickCharset(raws.filter { !it.utf8Flag }.map { it.nameBytes })
        return raws.map { it.e.copy(name = String(it.nameBytes, if (it.utf8Flag) Charsets.UTF_8 else cs)) }
    }

    /** UTF-8 の印の無い名前は、全体を見て UTF-8 か Shift_JIS 系かを決める(Windows の日本語環境で作った ZIP 対応)。 */
    private fun pickCharset(names: List<ByteArray>): Charset {
        if (names.all { n -> n.all { it >= 0 } }) return Charsets.UTF_8 // ASCII だけならどれでも同じ
        val joined = ByteArray(names.sumOf { it.size + 1 })
        var o = 0
        for (n in names) {
            n.copyInto(joined, o)
            o += n.size
            joined[o++] = '\n'.code.toByte()
        }
        return Charset.forName(TextDecode.decode(joined).charsetName)
    }

    private fun dosToMillis(date: Int, time: Int): Long = try {
        LocalDateTime.of(
            ((date shr 9) and 0x7F) + 1980, ((date shr 5) and 0xF).coerceIn(1, 12), (date and 0x1F).coerceAtLeast(1),
            ((time shr 11) and 0x1F).coerceAtMost(23), ((time shr 5) and 0x3F).coerceAtMost(59), ((time and 0x1F) * 2).coerceAtMost(59),
        ).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (e: Exception) {
        0L
    }

    /** 1つの中身を取り出す(展開した内容が読める)。使い終わったら close する。 */
    fun open(e: Entry): InputStream {
        if (e.encrypted) throw OpException("パスワード付きのZIPは未対応")
        val head = readAt(e.localOffset, 30)
        if (u32(head, 0) != 0x04034b50L) throw OpException("ZIPが壊れています: ${e.name}")
        val dataStart = e.localOffset + 30 + u16(head, 26) + u16(head, 28)
        return when (e.method) {
            0 -> RangeStream(src, dataStart, e.compressedSize, padByte = false)
            8 -> {
                val inflater = Inflater(true)
                // 生の Deflate は、最後にもう1バイト足さないと読み終われない実装があるので、1バイト足す
                object : InflaterInputStream(RangeStream(src, dataStart, e.compressedSize, padByte = true), inflater, 64 * 1024) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            inflater.end()
                        }
                    }
                }
            }
            else -> throw OpException("未対応の圧縮方式のZIP(${e.method}): ${e.name}")
        }
    }

    /** 範囲を決めて、まとめて読みながら順に返す。 */
    private class RangeStream(
        private val src: RandomSource,
        start: Long,
        private val length: Long,
        private val padByte: Boolean,
    ) : InputStream() {
        private var pos = start
        private var remaining = length
        private val buf = ByteArray(128 * 1024)
        private var bufPos = 0
        private var bufLen = 0
        private var padded = false

        private fun fill(): Boolean {
            if (remaining <= 0) return false
            val n = src.readAt(pos, buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n <= 0) throw IOException("ZIPが途中で切れています")
            pos += n
            remaining -= n
            bufPos = 0
            bufLen = n
            return true
        }

        override fun read(): Int {
            if (bufPos >= bufLen && !fill()) return pad()
            return buf[bufPos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (bufPos >= bufLen && !fill()) {
                val p = pad()
                if (p < 0) return -1
                b[off] = p.toByte()
                return 1
            }
            val n = minOf(len, bufLen - bufPos)
            System.arraycopy(buf, bufPos, b, off, n)
            bufPos += n
            return n
        }

        private fun pad(): Int {
            if (!padByte || padded) return -1
            padded = true
            return 0
        }
    }
}
