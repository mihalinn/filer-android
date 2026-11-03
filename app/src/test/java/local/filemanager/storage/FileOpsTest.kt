package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FileOpsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(dir: File, name: String, text: String = name): File =
        File(dir, name).apply { parentFile?.mkdirs(); writeText(text) }

    @Test
    fun 一覧はフォルダが先頭で数字は数値順() {
        val d = tmp.newFolder()
        file(d, "img10.jpg"); file(d, "img2.jpg"); file(d, "B.txt"); file(d, "a.txt")
        File(d, "zdir").mkdir()
        val names = FileOps.list(d, true, SortKey.NAME, true)!!.map { it.name }
        assertEquals(listOf("zdir", "a.txt", "B.txt", "img2.jpg", "img10.jpg"), names)
        val desc = FileOps.list(d, true, SortKey.NAME, false)!!.map { it.name }
        assertEquals("zdir", desc.first())
    }

    @Test
    fun 隠しファイルは設定で出し分け() {
        val d = tmp.newFolder()
        file(d, ".hidden"); file(d, "shown")
        assertEquals(listOf("shown"), FileOps.list(d, false, SortKey.NAME, true)!!.map { it.name })
        assertEquals(2, FileOps.list(d, true, SortKey.NAME, true)!!.size)
    }

    @Test
    fun 読めない場所は_null() {
        assertNull(FileOps.list(File(tmp.root, "none"), true, SortKey.NAME, true))
    }

    @Test
    fun サイズ順と種類順() {
        val d = tmp.newFolder()
        file(d, "a.txt", "1"); file(d, "b.mp3", "123456"); file(d, "c.txt", "123")
        assertEquals(listOf("a.txt", "c.txt", "b.mp3"), FileOps.list(d, true, SortKey.SIZE, true)!!.map { it.name })
        assertEquals(listOf("b.mp3", "a.txt", "c.txt"), FileOps.list(d, true, SortKey.TYPE, true)!!.map { it.name })
    }

    @Test
    fun コピーは同名なら番号を付ける() {
        val d = tmp.newFolder()
        val src = file(d, "a.txt", "hello")
        val copy = FileOps.copy(src, d)
        assertEquals("a (1).txt", copy.name)
        assertEquals("hello", copy.readText())
        assertEquals("a (2).txt", FileOps.copy(src, d).name)
    }

    @Test
    fun フォルダは中身ごとコピーできて自分自身の中には不可() {
        val d = tmp.newFolder()
        val dest = tmp.newFolder()
        file(d, "src/sub/x.txt", "x")
        val copied = FileOps.copy(File(d, "src"), dest)
        assertEquals("x", File(copied, "sub/x.txt").readText())
        try {
            FileOps.copy(File(d, "src"), File(d, "src/sub"))
            fail("自分自身の中へのコピーは失敗するはず")
        } catch (e: OpException) {
            assertTrue(e.message!!.contains("自分自身"))
        }
        assertFalse(File(d, "src/sub/src").exists())
    }

    @Test
    fun 移動すると元が消える() {
        val a = tmp.newFolder()
        val b = tmp.newFolder()
        val src = file(a, "m.txt", "moved")
        val moved = FileOps.move(src, b)
        assertFalse(src.exists())
        assertEquals("moved", moved.readText())
    }

    @Test
    fun 同じフォルダへの移動は拒否() {
        val a = tmp.newFolder()
        val src = file(a, "m.txt")
        try {
            FileOps.move(src, a)
            fail()
        } catch (e: OpException) {
            assertTrue(src.exists())
        }
    }

    @Test
    fun 削除はフォルダの中身ごと() {
        val d = tmp.newFolder()
        file(d, "t/a/b.txt")
        FileOps.delete(File(d, "t"))
        assertFalse(File(d, "t").exists())
    }

    @Test
    fun 名前変更の検証() {
        val d = tmp.newFolder()
        val a = file(d, "a.txt")
        file(d, "b.txt")
        assertEquals("c.txt", FileOps.rename(a, "c.txt").name)
        for (bad in listOf("", "  ", "..", "x/y")) {
            try {
                FileOps.rename(File(d, "c.txt"), bad)
                fail("拒否されるはず: [$bad]")
            } catch (e: OpException) { /* 想定どおり */ }
        }
        try {
            FileOps.rename(File(d, "c.txt"), "b.txt")
            fail()
        } catch (e: OpException) {
            assertTrue(File(d, "c.txt").exists())
        }
    }

    @Test
    fun 新しいフォルダ() {
        val d = tmp.newFolder()
        assertTrue(FileOps.createFolder(d, "new").isDirectory)
        try {
            FileOps.createFolder(d, "new")
            fail()
        } catch (e: OpException) { /* 想定どおり */ }
    }

    @Test
    fun ZIPの圧縮と展開で内容が戻る() {
        val d = tmp.newFolder()
        file(d, "dir/a.txt", "A"); file(d, "dir/in/b.txt", "B"); file(d, "top.txt", "T")
        val zip = File(d, "out.zip")
        FileOps.zip(listOf(File(d, "dir"), File(d, "top.txt")), zip)
        val root = FileOps.unzip(zip)
        assertEquals("out", root.name)
        assertEquals("A", File(root, "dir/a.txt").readText())
        assertEquals("B", File(root, "dir/in/b.txt").readText())
        assertEquals("T", File(root, "top.txt").readText())
    }

    @Test
    fun 同名の複数ファイルをZIPにしても失敗しない() {
        val d = tmp.newFolder()
        val x = file(d, "1/same.txt", "1")
        val y = file(d, "2/same.txt", "2")
        val zip = File(d, "s.zip")
        FileOps.zip(listOf(x, y), zip)
        val root = FileOps.unzip(zip)
        assertEquals(setOf("same.txt", "same.txt (1)"), root.list()!!.toSet())
    }

    @Test
    fun ZIPの親ディレクトリ参照は拒否して何も残さない() {
        val d = tmp.newFolder()
        val zip = File(d, "evil.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("../evil.txt")); z.write(1); z.closeEntry()
        }
        try {
            FileOps.unzip(zip)
            fail("拒否されるはず")
        } catch (e: OpException) { /* 想定どおり */ }
        assertFalse(File(d, "evil.txt").exists())
        assertFalse(File(d, "evil").exists())
    }

    @Test
    fun ShiftJISで作られたZIPの日本語名を読める() {
        val d = tmp.newFolder()
        val zip = File(d, "sjis.zip")
        ZipOutputStream(zip.outputStream(), Charset.forName("windows-31j")).use { z ->
            z.putNextEntry(ZipEntry("日本語/ファイル.txt")); z.write("内容".toByteArray()); z.closeEntry()
        }
        val root = FileOps.unzip(zip)
        assertEquals("内容", File(root, "日本語/ファイル.txt").readText())
    }

    @Test
    fun 検索は部分一致で大文字小文字を区別しない() {
        val d = tmp.newFolder()
        file(d, "a/Report.TXT"); file(d, "b/c/report2.md"); file(d, "other.txt")
        val hits = FileOps.search(d, "report", true, 100).map { it.name }.toSet()
        assertEquals(setOf("Report.TXT", "report2.md"), hits)
        assertEquals(1, FileOps.search(d, "report", true, 1).size)
        assertTrue(FileOps.search(d, "  ", true, 10).isEmpty())
    }

    @Test
    fun 統計() {
        val d = tmp.newFolder()
        file(d, "x/a", "12"); file(d, "x/y/b", "345")
        val s = FileOps.stats(listOf(File(d, "x")))
        assertEquals(Stats(files = 2, folders = 2, bytes = 5), s)
    }

    @Test
    fun サイズ表記() {
        assertEquals("0 B", FileOps.formatSize(0))
        assertEquals("1.0 KB", FileOps.formatSize(1024))
        assertEquals("1.5 MB", FileOps.formatSize(1_572_864))
        assertEquals("100 GB", FileOps.formatSize(100L * 1024 * 1024 * 1024))
    }

    @Test
    fun 拡張子と種類() {
        assertEquals("", FileOps.extensionOf(".bashrc"))
        assertEquals("", FileOps.extensionOf("noext"))
        assertEquals("jpg", FileOps.extensionOf("A.B.JPG"))
        assertEquals(FileKind.APK, FileOps.kindOf("x.apk"))
        assertEquals(FileKind.OTHER, FileOps.kindOf("x.unknown"))
        assertEquals("image/png", FileOps.mimeOf("a.PNG"))
    }
}
