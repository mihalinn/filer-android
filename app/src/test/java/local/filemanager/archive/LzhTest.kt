package local.filemanager

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class LzhTest {
    private class Mem(val data: ByteArray) : RandomSource {
        override val size: Long get() = data.size.toLong()
        override fun readAt(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            val n = minOf(len.toLong(), data.size - pos).toInt()
            System.arraycopy(data, pos.toInt(), buf, off, n)
            return n
        }

        override fun close() {}
    }

    private val sjis = java.nio.charset.Charset.forName("windows-31j")

    private fun crc16(b: ByteArray): Int {
        var c = 0
        for (x in b) c = Crc16.update(c, x.toInt() and 0xFF)
        return c
    }

    private fun le(o: ByteArrayOutputStream, v: Long, n: Int) {
        for (i in 0 until n) o.write(((v ushr (8 * i)) and 0xFF).toInt())
    }

    private fun dos(): Long = ((2020L - 1980) shl 25) or (6L shl 21) or (15L shl 16) or (10L shl 11) or (30L shl 5) or 20L

    /** level 0 の見出し + 中身。 */
    private fun level0(name: ByteArray, data: ByteArray, method: String = "-lh0-", orig: Int = data.size, crc: Int = crc16(data)): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(method.toByteArray(Charsets.ISO_8859_1))
        le(body, data.size.toLong(), 4); le(body, orig.toLong(), 4); le(body, dos(), 4)
        body.write(0x20); body.write(0)
        body.write(name.size); body.write(name)
        le(body, crc.toLong(), 2)
        val b = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(b.size)
        out.write(b.sumOf { it.toInt() and 0xFF } and 0xFF)
        out.write(b); out.write(data)
        return out.toByteArray()
    }

    /** level 1 の見出し(拡張見出しは DOS 属性 1 つ)+ 中身。 */
    private fun level1(name: ByteArray, data: ByteArray): ByteArray {
        val ext = byteArrayOf(0x40, 0x20, 0x00, 0x00, 0x00) // 種類 0x40・属性 2 バイト・次の大きさ 0
        val body = ByteArrayOutputStream()
        body.write("-lh0-".toByteArray())
        le(body, (data.size + ext.size).toLong(), 4); le(body, data.size.toLong(), 4); le(body, dos(), 4)
        body.write(0x20); body.write(1)
        body.write(name.size); body.write(name)
        le(body, crc16(data).toLong(), 2)
        body.write('M'.code)
        le(body, ext.size.toLong(), 2) // 最初の拡張見出しの大きさ
        val b = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(b.size)
        out.write(b.sumOf { it.toInt() and 0xFF } and 0xFF)
        out.write(b); out.write(ext); out.write(data)
        return out.toByteArray()
    }

    /** level 2 の見出し。ファイル名(0x01)とフォルダ名(0x02、区切りは 0xFF)を拡張見出しに持つ。 */
    private fun level2(name: ByteArray, dir: ByteArray?, data: ByteArray, method: String = "-lh0-", unix: Long = 1_600_000_000L): ByteArray {
        val e1 = ByteArrayOutputStream().apply { write(1); write(name) }.toByteArray()
        val e2 = dir?.let { ByteArrayOutputStream().apply { write(2); write(it) }.toByteArray() }
        val s1 = e1.size + 2
        val s2 = (e2?.size ?: 0) + 2
        val total = 26 + s1 + (if (e2 != null) s2 else 0)
        val o = ByteArrayOutputStream()
        le(o, total.toLong(), 2)
        o.write(method.toByteArray())
        le(o, data.size.toLong(), 4); le(o, data.size.toLong(), 4); le(o, unix, 4)
        o.write(0x20); o.write(2)
        le(o, crc16(data).toLong(), 2)
        o.write('M'.code)
        le(o, s1.toLong(), 2)
        o.write(e1)
        if (e2 != null) {
            le(o, s2.toLong(), 2)
            o.write(e2)
        }
        le(o, 0, 2)
        o.write(data)
        return o.toByteArray()
    }

    private fun archive(vararg parts: ByteArray) = ByteArrayOutputStream().also { o -> parts.forEach { o.write(it) }; o.write(0) }.toByteArray()

    private fun read(r: LzhReader, e: LzhReader.Entry) = r.open(e).use { it.readBytes() }

    @Test
    fun level0_1_2_areAllListedAndReadBack() {
        val a = "あいう".toByteArray(sjis)
        val d0 = "hello level0".toByteArray()
        val d1 = ByteArray(5000) { (it * 7).toByte() }
        val d2 = "日本語のテキスト".toByteArray()
        val r = LzhReader(Mem(archive(level0("a.txt".toByteArray(), d0), level1("b.bin".toByteArray(), d1), level2("c.txt".toByteArray(), "subÿdeepÿ".toByteArray(Charsets.ISO_8859_1), d2))))
        assertEquals(listOf("a.txt", "b.bin", "sub/deep/c.txt"), r.entries.map { it.name })
        assertEquals(listOf(d0.size.toLong(), d1.size.toLong(), d2.size.toLong()), r.entries.map { it.size })
        assertArrayEquals(d0, read(r, r.entries[0]))
        assertArrayEquals(d1, read(r, r.entries[1]))
        assertArrayEquals(d2, read(r, r.entries[2]))
        assertTrue(a.isNotEmpty())
    }

    @Test
    fun shiftJisNamesAreReadAndBackslashInsideACharacterIsNotASeparator() {
        // 「ソ」は 83 5C。2 バイト目が '\' と同じ
        val name = "ソフト\\説明.txt".toByteArray(sjis)
        val r = LzhReader(Mem(archive(level0(name, "x".toByteArray()))))
        assertEquals("ソフト/説明.txt", r.entries.single().name)
    }

    @Test
    fun utf8NamesAreReadToo() {
        val r = LzhReader(Mem(archive(level2("名前.txt".toByteArray(), null, "x".toByteArray()))))
        assertEquals("名前.txt", r.entries.single().name)
    }

    @Test
    fun dateIsReadFromBothDosAndUnixHeaders() {
        val r = LzhReader(Mem(archive(level0("a".toByteArray(), "x".toByteArray()), level2("b".toByteArray(), null, "x".toByteArray(), unix = 1_600_000_000L))))
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = r.entries[0].modified }
        assertEquals(2020, cal.get(java.util.Calendar.YEAR))
        assertEquals(5, cal.get(java.util.Calendar.MONTH))
        assertEquals(15, cal.get(java.util.Calendar.DAY_OF_MONTH))
        assertEquals(1_600_000_000_000L, r.entries[1].modified)
    }

    @Test
    fun pathsCannotEscapeTheDestination() {
        val r = LzhReader(Mem(archive(level0("..\\..\\evil.txt".toByteArray(), "x".toByteArray()), level0("/abs/./x.txt".toByteArray(), "y".toByteArray()))))
        assertEquals(listOf("evil.txt", "abs/x.txt"), r.entries.map { it.name })
    }

    @Test
    fun directoryEntryHasNoContent() {
        val dirEntry = level2(ByteArray(0), "docsÿ".toByteArray(Charsets.ISO_8859_1), ByteArray(0), method = "-lhd-")
        val r = LzhReader(Mem(archive(dirEntry, level2("a.txt".toByteArray(), "docsÿ".toByteArray(Charsets.ISO_8859_1), "x".toByteArray()))))
        assertEquals(listOf("docs", "docs/a.txt"), r.entries.map { it.name })
        assertTrue(r.entries[0].isDir)
        assertFalse(r.entries[1].isDir)
        assertEquals(0, r.open(r.entries[0]).readBytes().size)
    }

    @Test
    fun aDamagedContentFailsTheCrcCheck() {
        val data = ByteArray(300) { it.toByte() }
        val bytes = archive(level0("a.bin".toByteArray(), data))
        bytes[bytes.size - 50] = (bytes[bytes.size - 50] + 1).toByte()
        val r = LzhReader(Mem(bytes))
        try {
            read(r, r.entries[0])
            fail()
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("CRC"))
        }
    }

    @Test
    fun notAnLzhFileIsRefused() {
        try {
            LzhReader(Mem("これはLZHではないただの文章です。".toByteArray()))
            fail()
        } catch (e: OpException) {
            assertTrue(e.message!!.contains("LZH"))
        }
    }

    @Test
    fun aCutOffArchiveStillListsWhatIsComplete() {
        val whole = archive(level0("a.txt".toByteArray(), "first".toByteArray()), level0("b.txt".toByteArray(), "second".toByteArray()))
        val cut = whole.copyOf(whole.size - 12)
        val r = LzhReader(Mem(cut))
        assertEquals(listOf("a.txt"), r.entries.map { it.name })
    }

    @Test
    fun anUnsupportedMethodIsListedButNotExtracted() {
        val r = LzhReader(Mem(archive(level0("old.txt".toByteArray(), "abc".toByteArray(), method = "-lh1-"))))
        assertEquals("old.txt", r.entries.single().name)
        try {
            r.open(r.entries.single())
            fail()
        } catch (e: OpException) {
            assertTrue(e.message!!.contains("-lh1-"))
        }
    }

    @Test
    fun extensionsAreRecognised() {
        assertEquals(ArchiveType.LZH, Archives.typeOf("a.lzh"))
        assertEquals(ArchiveType.LZH, Archives.typeOf("A.LHA"))
        assertEquals("a", Archives.baseName("a.lzh"))
        assertTrue(Archives.isOtherArchive("a.lzh"))
        assertEquals("lzh", Sniff.detect(archive(level0("a".toByteArray(), "x".toByteArray()))).ext)
        assertNull(Archives.typeOf("lzh"))
    }

    /**
     * 実際の LZH(圧縮つき)を、環境変数 FILER_LZH_DIR のフォルダにある *.lzh から読む。無いときは何もしない。
     * 全部の中身を取り出して CRC が合うことを確かめ、一覧を FILER_LZH_REPORT(省略時は build/lzh_report.txt)に書く。
     */
    @Test
    fun realLzhFilesDecodeAndTheCrcMatches() {
        val dir = System.getenv("FILER_LZH_DIR")
        assumeTrue(dir != null)
        val files = File(dir!!).listFiles { f -> f.name.lowercase().let { it.endsWith(".lzh") || it.endsWith(".lha") } }.orEmpty().sortedBy { it.name }
        assumeTrue("lzh が見つかりません", files.isNotEmpty())
        val sb = StringBuilder()
        for (f in files) {
            val r = LzhReader(Mem(f.readBytes()))
            sb.append("${f.name}: ${r.entries.size} 個\n")
            for (e in r.entries) {
                val bytes = read(r, e)
                assertEquals("${f.name}/${e.name} の大きさ", e.size, bytes.size.toLong())
                sb.append("  ${e.method} ${e.name}  圧縮 ${e.compressedSize} → ${e.size}  CRC ok  先頭 ${bytes.take(8).joinToString(" ") { "%02x".format(it) }}\n")
            }
        }
        val out = File(System.getenv("FILER_LZH_REPORT") ?: "build/lzh_report.txt")
        out.parentFile?.mkdirs()
        out.writeText(sb.toString())
        println(sb)
    }
}
