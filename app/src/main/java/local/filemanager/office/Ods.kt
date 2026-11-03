package local.filemanager

/** 文章(text:p など)から、表示される文字だけを取り出す。空白の並びは 1 つにまとめ、text:s・text:tab・text:line-break は、その文字にする。 */
internal object OdfPlain {
    fun paragraphs(n: XNode): String {
        val parts = ArrayList<String>()
        for (k in n.kids) if (k.name == "p" || k.name == "h") parts.add(para(k))
        return parts.joinToString("\n")
    }

    fun para(p: XNode): String {
        val sb = StringBuilder()
        var lastSpace = true
        fun walk(n: XNode) {
            for (k in n.kids) when (k.name) {
                "#text" -> for (ch in k.text) {
                    if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
                        if (!lastSpace) { sb.append(' '); lastSpace = true }
                    } else {
                        sb.append(ch); lastSpace = false
                    }
                }
                "s" -> { sb.append(" ".repeat((k.int("text:c") ?: 1).coerceIn(1, 200))); lastSpace = false }
                "tab" -> { sb.append('\t'); lastSpace = false }
                "line-break" -> { sb.append('\n'); lastSpace = true }
                "note", "annotation", "frame" -> {}
                else -> walk(k)
            }
        }
        walk(p)
        return sb.toString().trimEnd(' ')
    }
}

/** OpenDocument の表計算(.ods)。Excel と同じ表示(SheetBook)で見せる。Android に依存しない。 */
class OdsBook(private val read: (String) -> ByteArray?) : SheetBook {
    private val content: XNode = Odf.xml(read, "content.xml") ?: throw OpException("OpenDocumentのファイルとして読めません")
    private val st = OdfStyles(Odf.xml(read, "styles.xml"), content)
    private val tables: List<XNode> = content.child("body")?.child("spreadsheet")?.children("table")?.takeIf { it.isNotEmpty() }
        ?: throw OpException("表計算(.ods)として読めません")
    override val sheetNames: List<String> = tables.mapIndexed { i, t -> t.a("table:name") ?: "シート${i + 1}" }

    private val styleList = ArrayList<XlsxStyle>().also { it.add(plainStyle()) }
    private val styleIndex = HashMap<String, Int>()
    override val styles: List<XlsxStyle> get() = styleList

    private fun plainStyle() = XlsxStyle(0, null, false, false, null, null, false, null, false, null)

    private fun styleOf(name: String?): Int {
        if (name == null) return 0
        return styleIndex.getOrPut(name) {
            val p = st.props("table-cell", name)
            val borderAny = listOf("c|fo:border", "c|fo:border-top", "c|fo:border-bottom", "c|fo:border-left", "c|fo:border-right")
                .any { k -> Odf.hasBorder(p[k]) }
            val bold = p["t|fo:font-weight"]?.let { it == "bold" || (it.toIntOrNull() ?: 400) >= 600 } ?: false
            val halign = when (p["p|fo:text-align"]) { "center" -> "center"; "end", "right" -> "right"; "start", "left" -> "left"; else -> null }
            val s = XlsxStyle(
                0, null, bold, p["t|fo:font-style"] == "italic", Odf.color(p["t|fo:color"]), Odf.color(p["c|fo:background-color"]), borderAny,
                halign, p["c|fo:wrap-option"] == "wrap", Odf.len(p["t|fo:font-size"]),
            )
            styleList.add(s)
            styleList.size - 1
        }
    }

    private fun frozenRows(sheetName: String): Int {
        val settings = Odf.xml(read, "settings.xml") ?: return 0
        var result = 0
        fun walk(n: XNode, inTables: Boolean) {
            for (k in n.kids) {
                if (k.name == "config-item-map-named" && k.a("config:name") == "Tables") {
                    for (e in k.kids) if (e.a("config:name") == sheetName) {
                        var mode = 0
                        var pos = 0
                        for (it in e.kids) if (it.name == "config-item") {
                            if (it.a("config:name") == "VerticalSplitMode") mode = it.text.toString().trim().toIntOrNull() ?: 0
                            if (it.a("config:name") == "VerticalSplitPosition") pos = it.text.toString().trim().toIntOrNull() ?: 0
                        }
                        if (mode == 2) result = pos
                    }
                } else {
                    walk(k, inTables)
                }
            }
        }
        walk(settings, false)
        return result
    }

    override fun load(i: Int): XlsxSheet = load(i, 50_000, 256)

    fun load(i: Int, maxRows: Int, maxCols: Int): XlsxSheet {
        val t = tables[i]
        val name = sheetNames[i]
        // 列: 幅と、その列の標準のセル書式
        val widths = HashMap<Int, Float>()
        val colStyle = ArrayList<String?>()
        fun cols(nodes: List<XNode>) {
            for (c in nodes) when (c.name) {
                "table-column" -> {
                    val w = Odf.len(st.props("table-column", c.a("table:style-name"))["col|style:column-width"])
                    repeat((c.int("table:number-columns-repeated") ?: 1).coerceIn(1, 1024)) {
                        if (colStyle.size < maxCols) {
                            if (w != null) widths[colStyle.size] = (w / 5.6f).coerceIn(2f, 120f)
                            colStyle.add(c.a("table:default-cell-style-name"))
                        }
                    }
                }
                "table-columns", "table-header-columns", "table-column-group" -> cols(c.kids)
            }
        }
        cols(t.kids)

        val rows = ArrayList<ArrayList<String>>()
        val meta = HashMap<Long, Int>()
        val merges = ArrayList<IntArray>()
        var truncated = false
        var lastContentRow = -1
        fun rowsOf(nodes: List<XNode>) {
            for (r in nodes) {
                if (r.name == "table-header-rows" || r.name == "table-rows" || r.name == "table-row-group") { rowsOf(r.kids); continue }
                if (r.name != "table-row") continue
                val rowRep = (r.int("table:number-rows-repeated") ?: 1).coerceAtLeast(1)
                val rowStyle = r.a("table:default-cell-style-name")
                val cells = ArrayList<String>()
                val rowMeta = ArrayList<Triple<Int, Int, Int>>() // 列, 書式, 種類
                val rowMerges = ArrayList<IntArray>()
                var col = 0
                var nonEmpty = false
                for (c in r.kids) {
                    if (c.name != "table-cell" && c.name != "covered-table-cell") continue
                    val crep = (c.int("table:number-columns-repeated") ?: 1).coerceAtLeast(1)
                    val text = if (c.name == "covered-table-cell") "" else cellText(c)
                    val styleName = c.a("table:style-name") ?: colStyle.getOrNull(col) ?: rowStyle
                    val vt = c.a("office:value-type")
                    val kind = when (vt) {
                        "float", "percentage", "currency", "date", "time" -> XlsxSheet.KIND_NUMBER
                        "boolean" -> XlsxSheet.KIND_BOOL_ERROR
                        else -> XlsxSheet.KIND_TEXT
                    }
                    val hasStyle = c.a("table:style-name") != null
                    val interesting = text.isNotEmpty() || (hasStyle && styleOf(styleName) != 0)
                    val span = (c.int("table:number-columns-spanned") ?: 1).coerceAtLeast(1)
                    val rspan = (c.int("table:number-rows-spanned") ?: 1).coerceAtLeast(1)
                    if (interesting) {
                        val si = styleOf(styleName)
                        val reps = if (text.isEmpty() && crep > 20) 1 else crep
                        for (k in 0 until reps) {
                            if (col + k >= maxCols) { truncated = true; break }
                            while (cells.size < col + k) cells.add("")
                            cells.add(text)
                            rowMeta.add(Triple(col + k, si, kind))
                            if (text.isNotEmpty()) nonEmpty = true
                        }
                        if ((span > 1 || rspan > 1) && c.name == "table-cell") rowMerges.add(intArrayOf(0, col, rspan - 1, col + span - 1))
                    }
                    col += crep
                }
                val reps = if (!nonEmpty && rowRep > 1) minOf(rowRep, 200) else minOf(rowRep, 1000)
                for (k in 0 until reps) {
                    if (rows.size >= maxRows) { truncated = true; return }
                    val ri = rows.size
                    rows.add(ArrayList(cells))
                    for ((cc, si, kind) in rowMeta) if (si != 0 || kind != 0) meta[XlsxSheet.key(ri, cc)] = (si shl 2) or kind
                    if (k == 0) for (m in rowMerges) merges.add(intArrayOf(ri, m[1], ri + m[2], m[3]))
                    if (nonEmpty || rowMeta.isNotEmpty()) lastContentRow = ri
                }
            }
        }
        rowsOf(t.kids)
        // 最後の、何も入っていない行は取り除く
        while (rows.size > lastContentRow + 1 && rows.isNotEmpty()) rows.removeAt(rows.size - 1)
        return XlsxSheet(name, rows, truncated, meta, widths, merges, frozenRows(name))
    }

    private fun cellText(c: XNode): String {
        val t = OdfPlain.paragraphs(c)
        if (t.isNotEmpty()) return t
        // 文字が無くて値だけのとき(まれ)
        return c.a("office:string-value") ?: c.a("office:value") ?: c.a("office:date-value") ?: c.a("office:boolean-value") ?: ""
    }
}
