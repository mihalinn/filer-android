package local.filemanager

/**
 * CSV の表の編集。rows はいつも変えない一覧で、編集のたびに新しい一覧を作る(変えなかった行は使い回す)。
 * 前の一覧をそのまま残すので、元に戻す・やり直しは、一覧を入れ替えるだけでできる。Android に依存しない。
 *
 * 全部の行を同じ列の数にそろえる(足りない所は空のセル)。いつも 1 行 1 列以上ある。
 */
class CsvSheet(initial: List<List<String>>) {
    var rows: List<List<String>> = normalize(initial)
        private set

    val rowCount: Int get() = rows.size
    val columnCount: Int get() = rows[0].size

    private val undo = ArrayDeque<List<List<String>>>()
    private val redo = ArrayDeque<List<List<String>>>()

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    fun cell(r: Int, c: Int): String = rows.getOrNull(r)?.getOrNull(c) ?: ""

    /** セルの文字を変える。同じなら何もしない(false)。 */
    fun setCell(r: Int, c: Int, text: String): Boolean {
        if (r !in 0 until rowCount || c !in 0 until columnCount || rows[r][c] == text) return false
        push(rows.mapIndexed { i, row -> if (i == r) row.mapIndexed { j, v -> if (j == c) text else v } else row })
        return true
    }

    /** at の位置に空の行を入れる(at == rowCount なら末尾)。 */
    fun insertRow(at: Int) {
        val i = at.coerceIn(0, rowCount)
        push(rows.take(i) + listOf(List(columnCount) { "" }) + rows.drop(i))
    }

    /** 行を消す。最後の 1 行は消せない(false)。 */
    fun deleteRow(at: Int): Boolean {
        if (rowCount <= 1 || at !in 0 until rowCount) return false
        push(rows.filterIndexed { i, _ -> i != at })
        return true
    }

    /** at の位置に空の列を入れる(at == columnCount なら右端)。 */
    fun insertColumn(at: Int) {
        val j = at.coerceIn(0, columnCount)
        push(rows.map { row -> row.take(j) + "" + row.drop(j) })
    }

    /** 列を消す。最後の 1 列は消せない(false)。 */
    fun deleteColumn(at: Int): Boolean {
        if (columnCount <= 1 || at !in 0 until columnCount) return false
        push(rows.map { row -> row.filterIndexed { j, _ -> j != at } })
        return true
    }

    fun undo(): Boolean {
        val prev = undo.removeLastOrNull() ?: return false
        redo.addLast(rows)
        rows = prev
        return true
    }

    fun redo(): Boolean {
        val next = redo.removeLastOrNull() ?: return false
        undo.addLast(rows)
        rows = next
        return true
    }

    private fun push(next: List<List<String>>) {
        undo.addLast(rows)
        if (undo.size > MAX_UNDO) undo.removeFirst()
        redo.clear()
        rows = next
    }

    companion object {
        const val MAX_UNDO = 100

        /** 行ごとに列の数が違うときは、いちばん多い列の数にそろえる。空なら 1 行 1 列。 */
        fun normalize(rows: List<List<String>>): List<List<String>> {
            val cols = maxOf(1, rows.maxOfOrNull { it.size } ?: 0)
            if (rows.isEmpty()) return listOf(List(cols) { "" })
            return rows.map { if (it.size == cols) it else it + List(cols - it.size) { "" } }
        }
    }
}
