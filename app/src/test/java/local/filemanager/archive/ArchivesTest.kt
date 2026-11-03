package local.filemanager

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream

private val tmp: File = File(System.getProperty("java.io.tmpdir"), "filer_archives_test").also { it.mkdirs() }

private fun tarBytes(files: List<Pair<String, ByteArray?>>): ByteArray {
    val bos = ByteArrayOutputStream()
    TarArchiveOutputStream(bos, "UTF-8").use { t ->
        t.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
        t.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
        for ((name, data) in files) {
            if (data == null) {
                t.putArchiveEntry(TarArchiveEntry("$name/"))
                t.closeArchiveEntry()
            } else {
                val e = TarArchiveEntry(name)
                e.size = data.size.toLong()
                t.putArchiveEntry(e)
                t.write(data)
                t.closeArchiveEntry()
            }
        }
    }
    return bos.toByteArray()
}

private fun write(name: String, bytes: ByteArray): FileItem {
    val f = File(tmp, name)
    f.writeBytes(bytes)
    return FileItem(f.path, f.name, false, f.length(), f.lastModified())
}

private fun gz(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()
private fun bz2(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> BZip2CompressorOutputStream(o).use { it.write(b) } }.toByteArray()
private fun xz(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> XZCompressorOutputStream(o).use { it.write(b) } }.toByteArray()

private fun open(item: FileItem) = ArchiveOpener.open(LocalFs, item, File(tmp, "cache"))

private fun ArchiveSource.read(name: String): ByteArray = open(entries.first { it.name == name }).use { it.readBytes() }

class ArchiveNamesTest {
    @Test
    fun 種類は長い拡張子から() {
        assertEquals(ArchiveType.TAR_GZ, Archives.typeOf("a.tar.gz"))
        assertEquals(ArchiveType.TAR_GZ, Archives.typeOf("A.TGZ"))
        assertEquals(ArchiveType.TAR_BZ2, Archives.typeOf("a.tar.bz2"))
        assertEquals(ArchiveType.TAR_XZ, Archives.typeOf("a.tar.xz"))
        assertEquals(ArchiveType.GZ, Archives.typeOf("dump.sql.gz"))
        assertEquals(ArchiveType.SEVEN_Z, Archives.typeOf("日本語.7z"))
        assertEquals(ArchiveType.RAR, Archives.typeOf("x.rar"))
        assertEquals(ArchiveType.ZIP, Archives.typeOf("x.xapk"))
        assertNull(Archives.typeOf("x.txt"))
        assertNull(Archives.typeOf(".gz"))
        assertNull(Archives.typeOf("gz"))
    }

    @Test
    fun 展開先の名前() {
        assertEquals("a", Archives.baseName("a.tar.gz"))
        assertEquals("a.sql", Archives.baseName("a.sql.gz"))
        assertEquals("日本語", Archives.baseName("日本語.7z"))
        assertEquals("noext", Archives.baseName("noext"))
        assertTrue(Archives.isOtherArchive("a.rar"))
        assertFalse(Archives.isOtherArchive("a.zip"))
        assertFalse(Archives.isOtherArchive("a.txt"))
    }

    @Test
    fun ビューアの種類() {
        assertEquals(PreviewKind.ARCHIVE, PreviewKind.of("a.7z"))
        assertEquals(PreviewKind.ARCHIVE, PreviewKind.of("a.tar.gz"))
        assertEquals(PreviewKind.ARCHIVE, PreviewKind.of("a.rar"))
        assertEquals(PreviewKind.ZIP, PreviewKind.of("a.zip"))
        assertEquals(PreviewKind.BUNDLE, PreviewKind.of("a.xapk"))
    }
}

class ArchiveReadTest {
    private val hello = "こんにちは tar".toByteArray()
    private val big = ByteArray(300_000) { (it * 31 + 7).toByte() }
    private val files = listOf("docs" to null, "docs/メモ.txt" to hello, "docs/sub/大きい.bin" to big, "readme.md" to "# md".toByteArray())

    private fun check(src: ArchiveSource) {
        val names = src.entries.map { it.name.trimEnd('/') }
        assertEquals(listOf("docs", "docs/メモ.txt", "docs/sub/大きい.bin", "readme.md"), names)
        assertTrue(src.entries[0].isDir)
        assertFalse(src.entries[1].isDir)
        assertEquals(hello.size.toLong(), src.entries[1].size)
        assertEquals(big.size.toLong(), src.entries[2].size)
        // 後ろのものから先に取り出しても、正しく取り出せる
        assertArrayEquals("# md".toByteArray(), src.read("readme.md"))
        assertArrayEquals(big, src.read("docs/sub/大きい.bin"))
        assertArrayEquals(hello, src.read("docs/メモ.txt"))
        // 階層の整理(ZIP と同じ画面で使う)
        val top = ZipTree.list(src.entries, "")
        assertEquals(listOf("docs", "readme.md"), top.map { it.name })
        assertEquals(2, top[0].count)
    }

    @Test
    fun tar() = open(write("a.tar", tarBytes(files))).use { check(it.source) }

    @Test
    fun tar_gz() = open(write("a.tar.gz", gz(tarBytes(files)))).use { check(it.source) }

    @Test
    fun tgz() = open(write("a.tgz", gz(tarBytes(files)))).use { check(it.source) }

    @Test
    fun tar_bz2() = open(write("a.tar.bz2", bz2(tarBytes(files)))).use { check(it.source) }

    @Test
    fun tar_xz() = open(write("a.tar.xz", xz(tarBytes(files)))).use { check(it.source) }

    @Test
    fun 名前がtar系でなくても_中身がtarなら書庫として開く() {
        open(write("weird.gz", gz(tarBytes(files)))).use { check(it.source) }
        open(write("weird.xz", xz(tarBytes(files)))).use { check(it.source) }
    }

    @Test
    fun gzの単体ファイル() {
        val text = "SELECT 1;\n".repeat(1000).toByteArray()
        open(write("dump.sql.gz", gz(text))).use { h ->
            assertEquals(1, h.source.entries.size)
            assertEquals("dump.sql", h.source.entries[0].name)
            assertEquals(text.size.toLong(), h.source.entries[0].size)
            assertArrayEquals(text, h.source.read("dump.sql"))
        }
        open(write("log.txt.xz", xz(text))).use { h ->
            assertEquals("log.txt", h.source.entries[0].name)
            assertArrayEquals(text, h.source.read("log.txt"))
        }
        open(write("log2.txt.bz2", bz2(text))).use { h ->
            assertEquals("log2.txt", h.source.entries[0].name)
            assertArrayEquals(text, h.source.read("log2.txt"))
        }
    }

    @Test
    fun 七z() {
        val f = File(tmp, "a.7z").also { it.delete() }
        SevenZOutputFile(f).use { o ->
            for ((name, data) in files) {
                val e = SevenZArchiveEntry()
                e.name = name
                e.isDirectory = data == null
                if (data != null) e.size = data.size.toLong()
                e.lastModifiedDate = java.util.Date(1_700_000_000_000L)
                o.putArchiveEntry(e)
                if (data != null) o.write(data)
                o.closeArchiveEntry()
            }
        }
        open(FileItem(f.path, f.name, false, f.length(), f.lastModified())).use { h ->
            check(h.source)
            assertEquals(1_700_000_000_000L, h.source.entries[1].modified)
        }
    }

    @Test
    fun 七zは大きなファイルの途中も読める() {
        val data = ByteArray(3_500_000) { (it % 251).toByte() }
        val f = File(tmp, "big.7z").also { it.delete() }
        SevenZOutputFile(f).use { o ->
            val e = SevenZArchiveEntry()
            e.name = "data.bin"
            e.size = data.size.toLong()
            o.putArchiveEntry(e)
            o.write(data)
            o.closeArchiveEntry()
        }
        open(FileItem(f.path, f.name, false, f.length(), f.lastModified())).use { h ->
            assertArrayEquals(data, h.source.read("data.bin"))
        }
    }

    @Test
    fun 壊れたものはエラー() {
        val item = write("broken.7z", ByteArray(200) { it.toByte() })
        try {
            open(item).close()
            fail("読めてしまった")
        } catch (e: OpException) {
            assertTrue(e.message!!.contains("7z"))
        }
    }
}

class SourceStreamTest {
    private class Mem(val data: ByteArray) : RandomSource {
        override val size: Long get() = data.size.toLong()
        override fun readAt(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            val n = minOf(len, (data.size - pos).toInt())
            System.arraycopy(data, pos.toInt(), buf, off, n)
            return n
        }

        override fun close() {}
    }

    @Test
    fun チャンネルは境界をまたいで読める() {
        val data = ByteArray(2_500_000) { (it * 7).toByte() }
        val ch = SourceChannel(Mem(data))
        val bb = ByteBuffer.allocate(300)
        ch.position(1024 * 1024 - 100)
        assertEquals(300, ch.read(bb))
        assertArrayEquals(data.copyOfRange(1024 * 1024 - 100, 1024 * 1024 + 200), bb.array())
        ch.position(data.size - 10L)
        val tail = ByteBuffer.allocate(50)
        assertEquals(10, ch.read(tail))
        assertEquals(-1, ch.read(ByteBuffer.allocate(1)))
        assertEquals(data.size.toLong(), ch.size())
    }

    @Test
    fun ストリームは頭から順に読む() {
        val data = ByteArray(1000) { it.toByte() }
        val s = SourceInputStream(Mem(data))
        assertEquals(0, s.read())
        assertEquals(10, s.skip(10))
        val b = ByteArray(5)
        assertEquals(5, s.read(b))
        assertArrayEquals(byteArrayOf(11, 12, 13, 14, 15), b)
        assertArrayEquals(data.copyOfRange(16, 1000), s.readBytes())
        assertEquals(-1, s.read())
    }
}

class ArchiveExtractTest {
    @Test
    fun tar_gzを展開する() {
        val dir = File(tmp, "extract").also { it.deleteRecursively(); it.mkdirs() }
        val f = File(dir, "pack.tar.gz")
        f.writeBytes(gz(tarBytes(listOf("a" to null, "a/一.txt" to "いち".toByteArray(), "b.txt" to "に".toByteArray()))))
        val item = FileItem(f.path, f.name, false, f.length(), f.lastModified())
        val root = Transfer.extractArchive(LocalFs, item, File(tmp, "cache"))
        assertEquals(File(dir, "pack").path, File(root).path)
        assertEquals("いち", File(root, "a/一.txt").readText())
        assertEquals("に", File(root, "b.txt").readText())
        // もう一度展開すると、別のフォルダ(pack (1))になる
        val again = Transfer.extractArchive(LocalFs, item, File(tmp, "cache"))
        assertEquals("pack (1)", File(again).name)
    }

    @Test
    fun 外へ出る名前は拒否() {
        val dir = File(tmp, "extract2").also { it.deleteRecursively(); it.mkdirs() }
        val f = File(dir, "evil.tar")
        f.writeBytes(tarBytes(listOf("../evil.txt" to "x".toByteArray())))
        val item = FileItem(f.path, f.name, false, f.length(), f.lastModified())
        try {
            Transfer.extractArchive(LocalFs, item, File(tmp, "cache"))
            fail("展開できてしまった")
        } catch (e: OpException) {
            assertFalse(File(dir, "evil.txt").exists())
            assertFalse(File(dir, "evil").exists())
        }
    }
}

// ---------------------------------------------------------------- RAR(圧縮なしの「保存」で、手で作った小さな書庫)

private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
private fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
private fun crc32(b: ByteArray): Long = CRC32().also { it.update(b) }.value

private fun rar4(files: List<Pair<String, ByteArray>>): ByteArray {
    val o = ByteArrayOutputStream()
    o.write(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00))
    val main = byteArrayOf(0x73, 0, 0, 13, 0, 0, 0, 0, 0, 0, 0)
    o.write(le16((crc32(main) and 0xFFFF).toInt())); o.write(main)
    for ((name, data) in files) {
        val nb = name.toByteArray(Charsets.US_ASCII)
        val body = ByteArrayOutputStream()
        body.write(0x74); body.write(le16(0x8000)); body.write(le16(32 + nb.size))
        body.write(le32(data.size.toLong())); body.write(le32(data.size.toLong()))
        body.write(2); body.write(le32(crc32(data))); body.write(le32(0x5A8B0000L))
        body.write(20); body.write(0x30); body.write(le16(nb.size)); body.write(le32(0x20)); body.write(nb)
        val hb = body.toByteArray()
        o.write(le16((crc32(hb) and 0xFFFF).toInt())); o.write(hb); o.write(data)
    }
    o.write(byteArrayOf(0xC4.toByte(), 0x3D, 0x7B, 0x00, 0x40, 0x07, 0x00))
    return o.toByteArray()
}

private fun vint(v: Long): ByteArray {
    val o = ByteArrayOutputStream()
    var x = v
    while (true) {
        val b = (x and 0x7F).toInt()
        x = x shr 7
        if (x == 0L) { o.write(b); break } else o.write(b or 0x80)
    }
    return o.toByteArray()
}

private fun rar5(files: List<Pair<String, ByteArray>>): ByteArray {
    val o = ByteArrayOutputStream()
    o.write(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00))
    fun block(content: ByteArray, data: ByteArray = ByteArray(0)) {
        val sized = vint(content.size.toLong()) + content
        o.write(le32(crc32(sized))); o.write(sized); o.write(data)
    }
    block(byteArrayOf(1, 0, 0))
    for ((name, data) in files) {
        val nb = name.toByteArray(Charsets.UTF_8)
        val c = ByteArrayOutputStream()
        c.write(vint(2)); c.write(vint(2)); c.write(vint(data.size.toLong()))
        c.write(vint(4)); c.write(vint(data.size.toLong())); c.write(vint(0x20))
        c.write(le32(crc32(data)))
        c.write(vint(0)); c.write(vint(0)); c.write(vint(nb.size.toLong())); c.write(nb)
        block(c.toByteArray(), data)
    }
    block(byteArrayOf(5, 0, 0))
    return o.toByteArray()
}

/** 環境変数 FILER_ARCHIVE_OUT があるときだけ、実機で試す見本の書庫をそのフォルダに作る。 */
class ArchiveSampleMaker {
    @Test
    fun 見本を作る() {
        val out = System.getenv("FILER_ARCHIVE_OUT")
        org.junit.Assume.assumeTrue(out != null)
        val dir = File(out!!).also { it.mkdirs() }
        val text = "ファイラーの書庫テスト\nこの文章が読めれば、取り出しは成功です。\n".repeat(3)
        val csv = "名前,数量,単価\nりんご,3,120\nみかん,10,80\nぶどう,2,450\n"
        val md = "# 書庫の中のMarkdown\n\n- 箇条書き1\n- 箇条書き2\n\n**太字**と`コード`。\n"
        val json = """{"名前":"見本","数":[1,2,3],"入れ子":{"a":true}}"""
        val files = listOf(
            "メモ" to null,
            "メモ/説明.txt" to text.toByteArray(),
            "メモ/表.csv" to csv.toByteArray(),
            "メモ/文書.md" to md.toByteArray(),
            "データ" to null,
            "データ/設定.json" to json.toByteArray(),
            "データ/大きいファイル.bin" to ByteArray(2_000_000) { (it * 13).toByte() },
            "readme.txt" to "ルートにあるファイル\n".toByteArray(),
        )
        File(dir, "テスト用_書庫.tar").writeBytes(tarBytes(files))
        File(dir, "テスト用_書庫.tar.gz").writeBytes(gz(tarBytes(files)))
        File(dir, "テスト用_書庫.tar.bz2").writeBytes(bz2(tarBytes(files)))
        File(dir, "テスト用_書庫.tar.xz").writeBytes(xz(tarBytes(files)))
        File(dir, "テスト用_単体.txt.gz").writeBytes(gz(text.toByteArray()))
        val f7 = File(dir, "テスト用_書庫.7z").also { it.delete() }
        SevenZOutputFile(f7).use { o ->
            for ((name, data) in files) {
                val e = SevenZArchiveEntry()
                e.name = name
                e.isDirectory = data == null
                if (data != null) e.size = data.size.toLong()
                e.lastModifiedDate = java.util.Date()
                o.putArchiveEntry(e)
                if (data != null) o.write(data)
                o.closeArchiveEntry()
            }
        }
        val rarFiles = listOf("hello.txt" to "RARの中身(圧縮なし)".toByteArray(), "data.bin" to ByteArray(5000) { (it % 200).toByte() })
        // RAR は圧縮ソフトが無いので、圧縮なし(保存)で手作りした小さなもの。ファイル名は ASCII のみ
        File(dir, "テスト用_RAR4.rar").writeBytes(rar4(rarFiles))
        File(dir, "テスト用_RAR5.rar").writeBytes(rar5(rarFiles))
    }
}

class RarTest {
    private val files = listOf("hello.txt" to "RAR hello".toByteArray(), "data.bin" to ByteArray(5000) { (it % 200).toByte() })

    @Test
    fun rar4_保存() {
        open(write("r4.rar", rar4(files))).use { h ->
            assertEquals(listOf("hello.txt", "data.bin"), h.source.entries.map { it.name })
            assertArrayEquals(files[0].second, h.source.read("hello.txt"))
            assertArrayEquals(files[1].second, h.source.read("data.bin"))
        }
    }

    @Test
    fun rar5_保存() {
        val item = write("r5.rar", rar5(files))
        try {
            open(item).use { h ->
                assertEquals(listOf("hello.txt", "data.bin"), h.source.entries.map { it.name })
                assertArrayEquals(files[0].second, h.source.read("hello.txt"))
                assertArrayEquals(files[1].second, h.source.read("data.bin"))
            }
        } catch (e: OpException) {
            fail("RAR5を読めません: ${e.message}")
        }
    }

    @Test
    fun rarではないものはエラー() {
        try {
            open(write("fake.rar", ByteArray(100))).close()
            fail("読めてしまった")
        } catch (e: OpException) {
            assertTrue(e.message!!.contains("RAR"))
        }
    }
}
