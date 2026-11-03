package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 端末内の上書き(同名があるとき置き換え・フォルダはまとめる)。 */
class OverwriteTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun names(dir: File) = dir.list()!!.toSet()

    @Test
    fun 端末内でも別のフォルダに同名があれば確認の対象_同じフォルダなら対象外() {
        val a = tmp.newFolder("a")
        val b = tmp.newFolder("b")
        val f = File(a, "x.txt").apply { writeText("1") }
        val g = File(a, "only.txt").apply { writeText("2") }
        File(b, "x.txt").writeText("old")
        val items = listOf(FileOps.toItem(f), FileOps.toItem(g))
        // 別のフォルダへ: 同名の x.txt だけが対象
        assertEquals(listOf("x.txt"), Transfer.conflicts(LocalFs, items, LocalFs, b.path).map { it.src.name })
        // 同じフォルダへ(複製): 確認しない
        assertTrue(Transfer.conflicts(LocalFs, items, LocalFs, a.path).isEmpty())
    }

    @Test
    fun ファイルの上書きコピーは同じ名前のまま置き換わる() {
        val src = File(tmp.newFolder(), "x.txt").apply { writeText("new") }
        val dest = tmp.newFolder()
        File(dest, "x.txt").writeText("old")
        val out = FileOps.copy(src, dest, overwrite = true)
        assertEquals("x.txt", out.name)
        assertEquals("new", out.readText())
        assertEquals(setOf("x.txt"), names(dest))
    }

    @Test
    fun 両方残すなら番号が付く() {
        val src = File(tmp.newFolder(), "x.txt").apply { writeText("new") }
        val dest = tmp.newFolder()
        File(dest, "x.txt").writeText("old")
        val out = FileOps.copy(src, dest)
        assertEquals("x (1).txt", out.name)
        assertEquals("old", File(dest, "x.txt").readText())
    }

    @Test
    fun フォルダの上書きコピーは中身をまとめる() {
        val src = File(tmp.newFolder(), "d").apply { mkdirs() }
        File(src, "same.txt").writeText("new")
        File(src, "added.txt").writeText("added")
        val dest = tmp.newFolder()
        File(dest, "d").mkdirs()
        File(dest, "d/same.txt").writeText("old")
        File(dest, "d/keep.txt").writeText("keep")
        FileOps.copy(src, dest, overwrite = true)
        assertEquals("new", File(dest, "d/same.txt").readText())
        assertEquals("added", File(dest, "d/added.txt").readText())
        assertEquals("keep", File(dest, "d/keep.txt").readText())
        assertEquals(setOf("d"), names(dest))
    }

    @Test
    fun 上書きコピーが途中で失敗しても古いファイルは残る() {
        val src = File(tmp.newFolder(), "x.bin").apply { writeBytes(ByteArray(900 * 1024)) }
        val dest = tmp.newFolder()
        File(dest, "x.bin").writeText("old")
        var calls = 0
        try {
            FileOps.copy(src, dest, check = { if (++calls > 3) throw OpException("中断") }, overwrite = true)
            fail()
        } catch (e: OpException) { /* 想定どおり */ }
        assertEquals("old", File(dest, "x.bin").readText())
        assertEquals(setOf("x.bin"), names(dest))
    }

    @Test
    fun 自分自身への上書きは拒否される() {
        val f = File(tmp.newFolder(), "x.txt").apply { writeText("data") }
        try {
            FileOps.copy(f, f.parentFile!!, overwrite = true)
            fail()
        } catch (e: OpException) { /* 想定どおり */ }
        assertEquals("data", f.readText())
    }

    @Test
    fun 種類が違うものへは上書きコピーも移動もできない() {
        val file = File(tmp.newFolder(), "n").apply { writeText("file") }
        val dest = tmp.newFolder()
        File(dest, "n").mkdirs()
        File(dest, "n/inner.txt").writeText("inner")
        try { FileOps.copy(file, dest, overwrite = true); fail() } catch (e: OpException) { /* 想定どおり */ }
        try { FileOps.move(file, dest, overwrite = true); fail() } catch (e: OpException) { /* 想定どおり */ }
        assertEquals("inner", File(dest, "n/inner.txt").readText())
        assertEquals("file", file.readText())
    }

    @Test
    fun ファイルの上書き移動は置き換えて元が消える() {
        val src = File(tmp.newFolder(), "m.txt").apply { writeText("new") }
        val dest = tmp.newFolder()
        File(dest, "m.txt").writeText("old")
        val out = FileOps.move(src, dest, overwrite = true)
        assertEquals("new", out.readText())
        assertFalse(src.exists())
        assertEquals(setOf("m.txt"), names(dest))
    }

    @Test
    fun フォルダの上書き移動は中身をまとめて元が消える() {
        val src = File(tmp.newFolder(), "d").apply { mkdirs() }
        File(src, "a.txt").writeText("A")
        File(src, "sub").mkdirs()
        File(src, "sub/b.txt").writeText("B")
        val dest = tmp.newFolder()
        File(dest, "d").mkdirs()
        File(dest, "d/a.txt").writeText("OLD")
        File(dest, "d/keep.txt").writeText("keep")
        FileOps.move(src, dest, overwrite = true)
        assertEquals("A", File(dest, "d/a.txt").readText())
        assertEquals("B", File(dest, "d/sub/b.txt").readText())
        assertEquals("keep", File(dest, "d/keep.txt").readText())
        assertTrue(!src.exists())
    }
}
