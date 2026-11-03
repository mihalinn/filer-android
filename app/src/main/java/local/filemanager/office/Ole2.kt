package local.filemanager

/**
 * OLE2 複合ファイル(古い Office の .doc / .xls / .ppt の入れ物)の読み取り。Android に依存しない。
 * 中のストリームを名前で取り出す。ファイル全体をメモリに読んで使う。
 */
class Ole2(private val d: ByteArray) {
    class Entry(val name: String, val type: Int, val start: Int, val size: Long, val left: Int, val right: Int, val child: Int)

    private val sectorSize: Int
    private val miniSectorSize: Int
    private val miniCutoff: Int
    private val fat: IntArray
    private val miniFat: IntArray
    val entries: List<Entry>
    private val miniStream: ByteArray

    init {
        if (!isOle2(d)) throw OpException("古い形式のOfficeファイル(OLE2)ではありません")
        val shift = u16(30)
        if (shift != 9 && shift != 12) throw OpException("OLE2の形式が読めません")
        sectorSize = 1 shl shift
        miniSectorSize = 1 shl u16(32)
        miniCutoff = i32(56).let { if (it <= 0) 4096 else it }

        // FAT(セクターのつながり表)の場所: ヘッダーの 109 個 + DIFAT の鎖
        val fatSectors = ArrayList<Int>()
        for (i in 0 until 109) {
            val s = i32(76 + i * 4)
            if (s >= 0) fatSectors.add(s)
        }
        var difat = i32(68)
        var guard = 0
        while (difat >= 0 && guard++ < 100_000) {
            val off = sectorOffset(difat)
            if (off < 0 || off + sectorSize > d.size) break
            val per = sectorSize / 4 - 1
            for (i in 0 until per) {
                val s = i32(off + i * 4)
                if (s >= 0) fatSectors.add(s)
            }
            difat = i32(off + per * 4)
        }
        val fatList = ArrayList<Int>()
        for (s in fatSectors) {
            val off = sectorOffset(s)
            if (off < 0 || off + sectorSize > d.size) continue
            for (i in 0 until sectorSize / 4) fatList.add(i32(off + i * 4))
        }
        fat = fatList.toIntArray()

        val dirBytes = readChain(i32(48), -1L) ?: throw OpException("OLE2の目録が読めません")
        val list = ArrayList<Entry>()
        var p = 0
        while (p + 128 <= dirBytes.size) {
            val nameLen = (dirBytes[p + 64].toInt() and 0xFF) or ((dirBytes[p + 65].toInt() and 0xFF) shl 8)
            val type = dirBytes[p + 66].toInt() and 0xFF
            val name = if (type == 0 || nameLen < 2) "" else String(dirBytes, p, (nameLen - 2).coerceIn(0, 62), Charsets.UTF_16LE)
            val start = le32(dirBytes, p + 116)
            val size = le32(dirBytes, p + 120).toLong() and 0xFFFFFFFFL
            list.add(Entry(name, type, start, size, le32(dirBytes, p + 68), le32(dirBytes, p + 72), le32(dirBytes, p + 76)))
            p += 128
        }
        entries = list
        val root = list.firstOrNull { it.type == 5 }
        miniStream = if (root != null && root.start >= 0) readChain(root.start, root.size) ?: ByteArray(0) else ByteArray(0)

        val mf = ArrayList<Int>()
        var ms = i32(60)
        var g2 = 0
        val chain = ArrayList<Int>()
        while (ms >= 0 && ms < fat.size && g2++ < 1_000_000) {
            chain.add(ms)
            ms = fat[ms]
        }
        for (s in chain) {
            val off = sectorOffset(s)
            if (off < 0 || off + sectorSize > d.size) continue
            for (i in 0 until sectorSize / 4) mf.add(i32(off + i * 4))
        }
        miniFat = mf.toIntArray()
    }

    /** ルートの直下にあるストリーム(大文字小文字は区別しない)。無ければ null。 */
    fun stream(name: String): ByteArray? {
        val e = entries.firstOrNull { it.type == 2 && it.name.equals(name, ignoreCase = true) } ?: return null
        return open(e)
    }

    fun has(name: String): Boolean = entries.any { it.type == 2 && it.name.equals(name, ignoreCase = true) }

    private fun open(e: Entry): ByteArray? {
        if (e.start < 0 || e.size > MAX_STREAM) return null
        return if (e.size < miniCutoff) readMini(e.start, e.size.toInt()) else readChain(e.start, e.size)
    }

    private fun sectorOffset(s: Int): Int {
        if (s < 0) return -1
        val off = (s.toLong() + 1) * sectorSize
        return if (off > d.size) -1 else off.toInt()
    }

    /** size が負なら、鎖の終わりまで全部。 */
    private fun readChain(start: Int, size: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        var s = start
        var count = 0
        val limit = if (size < 0) Long.MAX_VALUE else size
        while (s >= 0 && s < fat.size && out.size() < limit && count++ < 4_000_000) {
            val off = sectorOffset(s)
            if (off < 0 || off >= d.size) break
            val n = minOf(sectorSize, d.size - off)
            out.write(d, off, n)
            s = fat[s]
        }
        val bytes = out.toByteArray()
        return if (size < 0 || bytes.size <= size) bytes else bytes.copyOf(size.toInt())
    }

    private fun readMini(start: Int, size: Int): ByteArray? {
        val out = ByteArray(size)
        var s = start
        var pos = 0
        var count = 0
        while (s >= 0 && pos < size && count++ < 4_000_000) {
            val off = s * miniSectorSize
            if (off < 0 || off >= miniStream.size) break
            val n = minOf(miniSectorSize, size - pos, miniStream.size - off)
            System.arraycopy(miniStream, off, out, pos, n)
            pos += n
            s = if (s < miniFat.size) miniFat[s] else -2
        }
        return out
    }

    private fun u16(o: Int) = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)
    private fun i32(o: Int): Int = if (o + 4 > d.size) -1 else le32(d, o)

    companion object {
        private const val MAX_STREAM = 256L * 1024 * 1024
        private val MAGIC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())

        fun isOle2(b: ByteArray): Boolean = b.size >= 512 && MAGIC.indices.all { b[it] == MAGIC[it] }

        fun le32(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

        fun le16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    }
}
