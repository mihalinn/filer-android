package local.filemanager

/** CSV / TSV の読み取り。ダブルクォートで囲んだ中の区切り・改行・"" に対応する。Android に依存しない。 */
object Csv {
    data class Table(val rows: List<List<String>>, val delimiter: Char, val truncated: Boolean) {
        val columns: Int get() = rows.maxOfOrNull { it.size } ?: 0
    }

    private val candidates = charArrayOf(',', '\t', ';', '|')

    /**
     * 区切り文字を決める。拡張子が tsv ならタブ。そうでなければ、先頭の数行で「どの行でも同じ数だけ出てくる」ものを選ぶ
     * (引用符の中の区切りは数えない)。決められなければカンマ。
     */
    fun detectDelimiter(text: String, extension: String = ""): Char {
        if (extension == "tsv" || extension == "tab") return '\t'
        val counts = Array(candidates.size) { ArrayList<Int>() }
        val cur = IntArray(candidates.size)
        var inQuote = false
        var lines = 0
        var i = 0
        val end = minOf(text.length, 20_000)
        while (i < end && lines < 10) {
            val c = text[i]
            if (c == '"') {
                inQuote = !inQuote
            } else if (!inQuote) {
                if (c == '\n') {
                    if (cur.any { it > 0 }) {
                        for (k in candidates.indices) counts[k].add(cur[k])
                        lines++
                    }
                    cur.fill(0)
                } else {
                    val k = candidates.indexOf(c)
                    if (k >= 0) cur[k]++
                }
            }
            i++
        }
        var best = ','
        var bestScore = -1L
        for (k in candidates.indices) {
            val list = counts[k]
            if (list.isEmpty() || list.all { it == 0 }) continue
            val consistent = list.all { it == list[0] } && list[0] > 0
            // 全行で同数のものを優先し、その中では多く出るものを選ぶ
            val score = (if (consistent) 1_000_000L else 0L) + list.min().toLong() * 1000 + list.sum()
            if (score > bestScore) {
                bestScore = score
                best = candidates[k]
            }
        }
        return best
    }

    /**
     * 表を CSV の文字にする(行の区切りは LF。保存するときに、元の改行に直す)。
     * 区切り・引用符・改行を含むセルだけを "" で囲む。1 列の表で空のセルは、空行と区別できるよう "" と書く。
     */
    fun write(rows: List<List<String>>, delimiter: Char): String {
        val sb = StringBuilder()
        for (row in rows) {
            if (row.size == 1 && row[0].isEmpty()) {
                sb.append("\"\"")
            } else {
                row.forEachIndexed { i, cell ->
                    if (i > 0) sb.append(delimiter)
                    val quote = cell.any { it == delimiter || it == '"' || it == '\n' || it == '\r' }
                    if (quote) sb.append('"').append(cell.replace("\"", "\"\"")).append('"') else sb.append(cell)
                }
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    fun parse(text: String, delimiter: Char, maxRows: Int = 50_000): Table {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var inQuote = false
        var started = false // いまの行に、何か(区切りや引用符を含む)が出てきた
        var truncated = false
        val n = text.length
        var i = if (text.startsWith("﻿")) 1 else 0

        fun endRow() {
            row.add(cell.toString())
            cell.setLength(0)
            rows.add(row)
            row = ArrayList()
            started = false
        }

        while (i < n) {
            val c = text[i]
            if (inQuote) {
                if (c == '"') {
                    if (i + 1 < n && text[i + 1] == '"') {
                        cell.append('"')
                        i++
                    } else {
                        inQuote = false
                    }
                } else {
                    cell.append(c)
                }
            } else if (c == '"' && cell.isEmpty()) {
                inQuote = true
                started = true
            } else if (c == delimiter) {
                row.add(cell.toString())
                cell.setLength(0)
                started = true
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < n && text[i + 1] == '\n') i++
                if (started || cell.isNotEmpty() || row.isNotEmpty()) endRow()
                if (rows.size >= maxRows) {
                    truncated = text.substring(i + 1).any { !it.isWhitespace() }
                    break
                }
            } else {
                cell.append(c)
                started = true
            }
            i++
        }
        if (!truncated && (started || cell.isNotEmpty() || row.isNotEmpty())) endRow()
        return Table(rows, delimiter, truncated)
    }
}
