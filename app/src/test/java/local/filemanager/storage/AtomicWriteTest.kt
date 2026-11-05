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

class AtomicWriteTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun names(dir: File) = dir.list()!!.toSet()

    @Test
    fun 書いている間は仮の名前で_終わると本来の名前だけが残る() {
        val dir = tmp.newFolder()
        val dst = File(dir, "a.bin")
        FileOps.writeAtomically(dst) { w ->
            assertEquals(File(dir, "a.bin.filerpart"), w)
            w.writeText("x")
            assertFalse("書き終わるまで本来の名前では見えない", dst.exists())
        }
        assertEquals(setOf("a.bin"), names(dir))
        assertEquals("x", dst.readText())
    }

    @Test
    fun 失敗したら仮のファイルも本来のファイルも残らない() {
        val dir = tmp.newFolder()
        try {
            FileOps.writeAtomically(File(dir, "a.bin")) { w ->
                w.writeText("half")
                throw OpException("中断")
            }
            fail()
        } catch (e: OpException) { /* 想定どおり */ }
        assertTrue(names(dir).isEmpty())
    }

    @Test
    fun 名前が長すぎるときは直接書く() {
        val dir = tmp.newFolder()
        val name = "b".repeat(250)
        FileOps.writeAtomically(File(dir, name)) { w ->
            assertEquals(name, w.name)
            w.writeText("y")
        }
        assertEquals(setOf(name), names(dir))
    }

    @Test
    fun 端末内のコピーは完了後に仮のファイルが無い() {
        val src = File(tmp.newFolder(), "s").apply { mkdirs() }
        File(src, "one.txt").writeText("1")
        File(src, "in").mkdirs()
        File(src, "in/two.txt").writeText("2")
        val dest = tmp.newFolder()
        val out = FileOps.copy(src, dest)
        val all = out.walkTopDown().map { it.name }.toList()
        assertTrue(all.none { it.endsWith(FileOps.PART_SUFFIX) })
        assertEquals("2", File(out, "in/two.txt").readText())
    }

    @Test
    fun 端末内のコピーが途中で失敗したら何も残らない() {
        val src = File(tmp.newFolder(), "big.bin").apply { writeBytes(ByteArray(900 * 1024)) }
        val dest = tmp.newFolder()
        var calls = 0
        try {
            FileOps.copy(src, dest, check = { if (++calls > 3) throw OpException("中断") })
            fail()
        } catch (e: OpException) { /* 想定どおり */ }
        assertTrue("残ったもの: ${names(dest)}", names(dest).isEmpty())
    }

    @Test
    fun ZIPの圧縮も仮の名前を経由して_失敗時は残らない() {
        val dir = tmp.newFolder()
        val a = File(dir, "a.bin").apply { writeBytes(ByteArray(900 * 1024)) }
        val zip = File(dir, "out.zip")
        FileOps.zip(listOf(a), zip)
        assertEquals(setOf("a.bin", "out.zip"), names(dir))

        val zip2 = File(dir, "out2.zip")
        var calls = 0
        try {
            FileOps.zip(listOf(a), zip2, check = { if (++calls > 3) throw OpException("中断") })
            fail()
        } catch (e: OpException) { /* 想定どおり */ }
        assertEquals(setOf("a.bin", "out.zip"), names(dir))
    }

    @Test
    fun 通知の文は進み具合から作られる() {
        val p = ProgressView(OpKind.COPY, "/a", "/b", doneBytes = 512L * 1024 * 1024, totalBytes = 1024L * 1024 * 1024,
            bytesPerSecond = 100L * 1024 * 1024, itemsDone = 0, itemsTotal = 2, elapsedMs = 65_000)
        val c = noticeContent("コピー中…", p)
        assertEquals("コピーしています 50%", c.title)
        assertEquals(50, c.percent)
        assertEquals("512 MB / 1.0 GB・100 MB/s", c.text)
        assertTrue(c.detail.contains("項目 0 / 2"))
        assertTrue(c.detail.contains("経過 0:01:05"))
    }

    @Test
    fun 全体の大きさが分からないときは動き続けるバー() {
        val p = ProgressView(OpKind.UNZIP, "", "", 3L * 1024 * 1024, 0, 0, 0, 1, 1000)
        val c = noticeContent("展開中…", p)
        assertNull(c.percent)
        assertEquals("展開しています", c.title)
        assertEquals("3.0 MB", c.text)
    }

    @Test
    fun 進捗が無い操作は操作名を出す() {
        val c = noticeContent("削除中…", null)
        assertEquals("削除中…", c.title)
        assertNull(c.percent)
    }
}
