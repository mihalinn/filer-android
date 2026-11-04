package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvSheetTest {
    private fun sheet(vararg rows: List<String>) = CsvSheet(rows.toList())

    @Test
    fun writeQuotesOnlyWhenNeeded() {
        val rows = listOf(listOf("a", "b,c", "d\"e"), listOf("x\ny", "", " z "))
        assertEquals("a,\"b,c\",\"d\"\"e\"\n\"x\ny\",, z \n", Csv.write(rows, ','))
    }

    @Test
    fun writeUsesTheGivenDelimiter() {
        assertEquals("a\tb,c\n\"x\ty\"\tz\n", Csv.write(listOf(listOf("a", "b,c"), listOf("x\ty", "z")), '\t'))
    }

    @Test
    fun singleEmptyColumnIsQuotedSoItIsNotABlankLine() {
        val rows = listOf(listOf("a"), listOf(""), listOf("b"))
        val text = Csv.write(rows, ',')
        assertEquals("a\n\"\"\nb\n", text)
        assertEquals(rows, Csv.parse(text, ',').rows)
    }

    @Test
    fun parseOfWriteGivesTheSameRows() {
        val rows = listOf(
            listOf("名前", "メモ", "値"),
            listOf("A\"B", "1行目\n2行目", "1,234"),
            listOf("", "", ""),
            listOf("末尾の空白 ", ";", "|"),
        )
        for (d in charArrayOf(',', '\t', ';', '|')) {
            assertEquals("delimiter=$d", rows, Csv.parse(Csv.write(rows, d), d).rows)
        }
    }

    @Test
    fun rowsAreNormalizedToTheSameColumnCount() {
        val s = sheet(listOf("a"), listOf("b", "c", "d"), listOf())
        assertEquals(3, s.columnCount)
        assertTrue(s.rows.all { it.size == 3 })
        assertEquals(listOf("a", "", ""), s.rows[0])
    }

    @Test
    fun emptyInputBecomesOneEmptyCell() {
        val s = CsvSheet(emptyList())
        assertEquals(1, s.rowCount)
        assertEquals(1, s.columnCount)
        assertEquals("", s.cell(0, 0))
    }

    @Test
    fun setCellChangesOnlyThatCellAndKeepsOtherRows() {
        val s = sheet(listOf("a", "b"), listOf("c", "d"))
        val before = s.rows
        assertTrue(s.setCell(1, 0, "X"))
        assertEquals(listOf(listOf("a", "b"), listOf("X", "d")), s.rows)
        assertSame(before[0], s.rows[0])
        assertFalse(s.setCell(1, 0, "X"))
        assertFalse(s.setCell(5, 0, "Y"))
        assertFalse(s.setCell(0, 9, "Y"))
    }

    @Test
    fun insertAndDeleteRows() {
        val s = sheet(listOf("a", "b"), listOf("c", "d"))
        s.insertRow(1)
        assertEquals(listOf(listOf("a", "b"), listOf("", ""), listOf("c", "d")), s.rows)
        s.insertRow(3)
        assertEquals(4, s.rowCount)
        assertEquals(listOf("", ""), s.rows[3])
        assertTrue(s.deleteRow(0))
        assertEquals(listOf("", ""), s.rows[0])
        assertEquals(3, s.rowCount)
    }

    @Test
    fun insertAndDeleteColumns() {
        val s = sheet(listOf("a", "b"), listOf("c", "d"))
        s.insertColumn(1)
        assertEquals(listOf(listOf("a", "", "b"), listOf("c", "", "d")), s.rows)
        s.insertColumn(3)
        assertEquals(4, s.columnCount)
        assertTrue(s.deleteColumn(0))
        assertEquals(listOf(listOf("", "b", ""), listOf("", "d", "")), s.rows)
    }

    @Test
    fun lastRowAndColumnCannotBeDeleted() {
        val s = sheet(listOf("a"))
        assertFalse(s.deleteRow(0))
        assertFalse(s.deleteColumn(0))
        assertEquals(listOf(listOf("a")), s.rows)
        assertFalse(s.canUndo)
    }

    @Test
    fun undoAndRedoRestoreTheExactSameLists() {
        val s = sheet(listOf("a", "b"), listOf("c", "d"))
        val original = s.rows
        s.setCell(0, 0, "X")
        s.insertRow(1)
        s.deleteColumn(1)
        assertTrue(s.canUndo)
        assertTrue(s.undo())
        assertTrue(s.undo())
        assertTrue(s.undo())
        assertSame(original, s.rows)
        assertFalse(s.canUndo)
        assertFalse(s.undo())
        assertTrue(s.redo())
        assertEquals("X", s.cell(0, 0))
        assertTrue(s.canRedo)
        // 新しく編集すると、やり直しは消える
        s.setCell(1, 1, "Z")
        assertFalse(s.canRedo)
    }

    @Test
    fun undoHistoryIsLimited() {
        val s = sheet(listOf("0"))
        repeat(CsvSheet.MAX_UNDO + 20) { s.setCell(0, 0, "v$it") }
        var n = 0
        while (s.undo()) n++
        assertEquals(CsvSheet.MAX_UNDO, n)
    }
}
