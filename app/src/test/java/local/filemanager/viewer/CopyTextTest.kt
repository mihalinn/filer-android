package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CopyTextTest {
    private val grid = listOf(
        listOf("名前", "数", "メモ"),
        listOf("りんご", "3", "赤い"),
        listOf("みかん", "10", ""),
    )

    @Test
    fun 表全体はタブと改行で区切る() {
        val t = CopyText.tsv(3, 3) { r, c -> grid[r][c] }
        assertEquals("名前\t数\tメモ\nりんご\t3\t赤い\nみかん\t10\t", t)
    }

    @Test
    fun 行と列() {
        assertEquals("りんご\t3\t赤い", CopyText.row(3) { grid[1][it] })
        assertEquals("数\n3\n10", CopyText.column(3) { grid[it][1] })
    }

    @Test
    fun タブや改行や引用符を含むセルは引用符で囲む() {
        assertEquals("a b", CopyText.tsvCell("a b"))
        assertEquals("\"a\tb\"", CopyText.tsvCell("a\tb"))
        assertEquals("\"1行目\n2行目\"", CopyText.tsvCell("1行目\n2行目"))
        assertEquals("\"彼は\"\"はい\"\"と言った\"", CopyText.tsvCell("彼は\"はい\"と言った"))
    }

    @Test
    fun 長すぎる文字は先頭だけにして切ったことを返す() {
        val short = "短い文字"
        assertEquals(short to false, CopyText.limit(short))
        val long = "あ".repeat(CopyText.MAX_CHARS + 10)
        val (t, cut) = CopyText.limit(long)
        assertTrue(cut)
        assertEquals(CopyText.MAX_CHARS, t.length)
        assertFalse(CopyText.limit("x".repeat(CopyText.MAX_CHARS)).second)
    }
}