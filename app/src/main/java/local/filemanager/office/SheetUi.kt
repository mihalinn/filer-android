package local.filemanager

/** 表の 1 つのセルの見た目。 */
internal class GridCell(
    val text: String,
    val fill: Int? = null,
    val color: Int? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    /** -1 左 / 0 中央 / 1 右 */
    val align: Int = -1,
    val wrap: Boolean = false,
    val border: Boolean = false,
    val sizePt: Float? = null,
)

/** Excel 風の表に出す中身。Excel(.xlsx)と CSV で共通。 */
internal interface GridModel {
    val rowCount: Int
    val colCount: Int

    /** 上に固定する行の数 */
    val frozenRows: Int

    /** セルの見た目。何も無い(文字も書式も)ときは null。 */
    fun cell(r: Int, c: Int): GridCell?

    /** セル (r, c) から右へ何列分の幅か(結合したセルの先頭)。結合された右側のセルは 0(出さない)。通常は 1。 */
    fun span(r: Int, c: Int): Int

    /** 列の幅(dp。標準の文字の大きさのとき) */
    fun colWidth(c: Int): Float

    /**
     * 全部の行が同じ高さで、セルの結合も折り返しも無いとき true(CSV)。この表は、見えている列だけを組み立てる軽い作りで描く。
     * Excel のように、行によって高さが変わる・結合があるものは false(従来の描き方)。
     */
    val uniformRows: Boolean get() = false
}

internal class XlsxGridModel(private val sheet: XlsxSheet, private val file: SheetBook) : GridModel {
    override val rowCount = sheet.rows.size
    override val colCount = sheet.columns
    override val frozenRows = sheet.frozenRows.coerceIn(0, 20)

    private val spans = HashMap<Long, Int>().also { m ->
        for (g in sheet.merges) {
            val (r0, c0, _, c1) = g.toList()
            if (c1 > c0) {
                m[XlsxSheet.key(r0, c0)] = c1 - c0 + 1
                for (c in c0 + 1..c1) m[XlsxSheet.key(r0, c)] = 0
            }
        }
    }

    override fun span(r: Int, c: Int): Int = spans[XlsxSheet.key(r, c)] ?: 1

    override fun cell(r: Int, c: Int): GridCell? {
        val text = sheet.rows.getOrNull(r)?.getOrNull(c) ?: ""
        val st = file.styles.getOrNull(sheet.styleAt(r, c))
        if (text.isEmpty() && (st == null || (st.fill == null && !st.border))) return null
        val kind = sheet.kindAt(r, c)
        val align = when (st?.halign) {
            "left" -> -1
            "center" -> 0
            "right" -> 1
            // 「標準」の配置: 数は右、文字は左、真偽値・エラーは中央
            else -> when (kind) { XlsxSheet.KIND_NUMBER -> 1; XlsxSheet.KIND_BOOL_ERROR -> 0; else -> -1 }
        }
        return GridCell(text, st?.fill, st?.color, st?.bold ?: false, st?.italic ?: false, align, st?.wrap ?: false, st?.border ?: false, st?.sizePt)
    }

    override fun colWidth(c: Int): Float = ((sheet.colWidths[c] ?: 8.43f) * 7.4f + 6f).coerceIn(28f, 420f)
}

/** CSV の表。headerOn のときは、1 行目を見出し(固定・太字・色つき)にする。 */
internal class CsvGridModel(private val table: Csv.Table, private val headerOn: Boolean) : GridModel {
    override val rowCount = table.rows.size
    override val colCount = table.columns
    override val frozenRows = if (headerOn && rowCount > 0) 1 else 0

    private val numeric = Regex("^[+-]?[\\d,]*\\.?\\d+([eE][+-]?\\d+)?%?$")
    private val widths: List<Float> = List(colCount) { c ->
        var m = 0
        for (r in 0 until minOf(table.rows.size, 300)) {
            val s = table.rows[r].getOrNull(c) ?: continue
            var w = 0
            for (ch in s.lineSequence().firstOrNull() ?: "") w += if (ch.code >= 0x2E80) 2 else 1
            if (w > m) m = w
        }
        (m * 7.4f + 16f).coerceIn(48f, 360f)
    }

    override fun span(r: Int, c: Int) = 1

    override val uniformRows: Boolean get() = true

    /** 数の形か(右寄せにする)。正規表現は重いので、先頭の文字と長さで先にふるいにかける。 */
    private fun looksNumeric(text: String): Boolean {
        if (text.isEmpty() || text.length > 40) return false
        val ch = text[0]
        if (!(ch in '0'..'9' || ch == '+' || ch == '-' || ch == '.' || ch == ' ')) return false
        return numeric.matches(text.trim())
    }

    override fun cell(r: Int, c: Int): GridCell? {
        val text = table.rows.getOrNull(r)?.getOrNull(c) ?: ""
        val head = headerOn && r == 0
        if (text.isEmpty() && !head) return null
        val align = if (!head && looksNumeric(text)) 1 else -1
        return GridCell(text, if (head) 0xFFE7ECF4.toInt() else null, null, head, false, align, false, head)
    }

    override fun colWidth(c: Int): Float = widths.getOrElse(c) { 68f }
}
