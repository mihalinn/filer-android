package local.filemanager

/** OpenDocument のテキスト文書(.odt)を、Word と同じ形(DocxDoc)にする。Android に依存しない。 */
object Odt {
    fun parse(read: (String) -> ByteArray?): DocxDoc {
        val content = Odf.xml(read, "content.xml") ?: throw OpException("OpenDocumentのファイルとして読めません")
        val styles = Odf.xml(read, "styles.xml")
        val st = OdfStyles(styles, content)
        val text = content.child("body")?.child("text") ?: throw OpException("文書(.odt)として読めません")
        val b = Builder(st)
        b.blocks(text.kids, b.pages.last(), true)
        val pages = b.pages.filter { it.isNotEmpty() }
        return DocxDoc(if (pages.isEmpty()) listOf(emptyList()) else pages)
    }

    private class Spaces { var last = true }

    private class Builder(val st: OdfStyles) {
        val pages = ArrayList<ArrayList<DocBlock>>().also { it.add(ArrayList()) }
        val baseSize: Float = Odf.len(st.props("paragraph", null)["t|fo:font-size"]) ?: 12f
        private val counters = HashMap<String, IntArray>()

        fun blocks(nodes: List<XNode>, into: MutableList<DocBlock>, top: Boolean) {
            for (n in nodes) when (n.name) {
                "h" -> paragraph(n, into, top, n.int("text:outline-level")?.coerceIn(1, 6) ?: 1, null, 0)
                "p" -> paragraph(n, into, top, 0, null, 0)
                "list" -> list(n, into, top, 0, n.a("text:style-name"))
                "table" -> table(n, if (top) pages.last() else into)
                "section" -> blocks(n.kids, into, top)
                "table-of-content", "illustration-index", "alphabetical-index", "user-index", "bibliography" -> n.child("index-body")?.let { blocks(it.kids, into, top) }
                "index-body" -> blocks(n.kids, into, top)
            }
        }

        private fun collapse(s: String, sp: Spaces): String {
            val sb = StringBuilder()
            for (ch in s) {
                if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
                    if (!sp.last) { sb.append(' '); sp.last = true }
                } else {
                    sb.append(ch)
                    sp.last = false
                }
            }
            return sb.toString()
        }

        private fun runs(n: XNode, cur: RPr, out: MutableList<DocRun>, link: String?, sp: Spaces, extra: MutableList<DocBlock>) {
            for (k in n.kids) when (k.name) {
                "#text" -> collapse(k.text.toString(), sp).let { if (it.isNotEmpty()) out.add(DocRun(it, cur, link)) }
                "span" -> runs(k, OdfText.rpr(st.props("text", k.a("text:style-name")), baseSize).over(cur), out, link, sp, extra)
                "a" -> runs(k, cur, out, k.a("xlink:href") ?: link, sp, extra)
                "s" -> { out.add(DocRun(" ".repeat((k.int("text:c") ?: 1).coerceIn(1, 200)), cur, link)); sp.last = false }
                "tab" -> { out.add(DocRun("\t", cur, link)); sp.last = false }
                "line-break" -> { out.add(DocRun("\n", cur, link)); sp.last = true }
                "frame" -> frame(k, extra)
                "note", "annotation", "bookmark", "bookmark-start", "bookmark-end", "soft-page-break", "change", "change-start", "change-end" -> {}
                else -> runs(k, cur, out, link, sp, extra)
            }
        }

        private fun frame(f: XNode, extra: MutableList<DocBlock>) {
            val img = f.child("image")
            if (img != null) {
                val href = img.a("xlink:href")
                if (href != null) {
                    val w = Odf.len(f.a("svg:width")) ?: 200f
                    val h = Odf.len(f.a("svg:height")) ?: 150f
                    extra.add(DocBlock.Image(href.removePrefix("./"), w, h))
                }
            }
            f.child("text-box")?.let { tb -> blocks(tb.kids, extra, false) }
        }

        private fun paragraph(n: XNode, into: MutableList<DocBlock>, top: Boolean, heading: Int, marker: String?, indent: Int, hangingMarker: Boolean = marker != null) {
            val sname = n.a("text:style-name")
            val pp = st.props("paragraph", sname)
            if (top && (pp["p|fo:break-before"] == "page" || pp["@style:master-page-name"] != null) && pages.last().isNotEmpty()) {
                pages.add(ArrayList())
            }
            val target = if (top) pages.last() else into
            val base = OdfText.rpr(pp, baseSize)
            val rs = ArrayList<DocRun>()
            val extra = ArrayList<DocBlock>()
            runs(n, base, rs, null, Spaces(), extra)
            val ml = Odf.len(pp["p|fo:margin-left"]) ?: 0f
            val ti = Odf.len(pp["p|fo:text-indent"]) ?: 0f
            val listLeft = indent * 18f
            val align = when (pp["p|fo:text-align"]) { "center" -> "center"; "end", "right" -> "right"; "justify" -> "both"; else -> "left" }
            val para = DocBlock.Para(
                runs = if (rs.isEmpty()) listOf(DocRun("", base)) else rs,
                heading = heading,
                align = align,
                leftPt = ml + listLeft,
                firstLinePt = if (ti > 0) ti else 0f,
                hangingPt = if (hangingMarker) 18f else if (ti < 0) -ti else 0f,
                beforePt = Odf.len(pp["p|fo:margin-top"]) ?: 0f,
                afterPt = Odf.len(pp["p|fo:margin-bottom"]) ?: 0f,
                lineMult = Odf.percent(pp["p|fo:line-height"]) ?: 1f,
                marker = marker,
                fill = Odf.color(pp["p|fo:background-color"]),
            )
            target.add(para)
            target.addAll(extra)
        }

        private fun list(n: XNode, into: MutableList<DocBlock>, top: Boolean, depth: Int, styleName: String?) {
            val ls = styleName?.let { st.listStyles[it] }
            val level = ls?.kids?.firstOrNull { (it.int("text:level") ?: 0) == depth + 1 }
            val cnt = counters.getOrPut("$styleName#${System.identityHashCode(n.attrs)}#$depth") { IntArray(1) }
            for (item in n.kids) {
                if (item.name != "list-item" && item.name != "list-header") continue
                cnt[0]++
                var first = true
                for (c in item.kids) when (c.name) {
                    "p", "h" -> {
                        val marker = if (first && item.name == "list-item") markerOf(level, cnt[0]) else null
                        paragraph(c, into, top, if (c.name == "h") (c.int("text:outline-level") ?: 0).coerceIn(0, 6) else 0, marker, depth + 1, marker != null)
                        first = false
                    }
                    "list" -> list(c, into, top, depth + 1, c.a("text:style-name") ?: styleName)
                }
            }
        }

        private fun markerOf(level: XNode?, n: Int): String {
            if (level == null) return "•"
            return when (level.name) {
                "list-level-style-number" -> {
                    val fmt = level.a("style:num-format") ?: "1"
                    (level.a("style:num-prefix") ?: "") + number(fmt, n + (level.int("text:start-value")?.minus(1) ?: 0)) + (level.a("style:num-suffix") ?: "")
                }
                "list-level-style-bullet" -> level.a("text:bullet-char")?.takeIf { it.isNotEmpty() } ?: "•"
                else -> "•"
            }
        }

        private fun number(fmt: String, n: Int): String = when (fmt) {
            "a" -> SlideText.autoNumber("alphaLcPeriod", n).dropLast(1)
            "A" -> SlideText.autoNumber("alphaUcPeriod", n).dropLast(1)
            "i" -> SlideText.autoNumber("romanLcPeriod", n).dropLast(1)
            "I" -> SlideText.autoNumber("romanUcPeriod", n).dropLast(1)
            "" -> ""
            else -> n.toString()
        }

        private fun table(n: XNode, into: MutableList<DocBlock>) {
            val widths = ArrayList<Float>()
            fun cols(nodes: List<XNode>) {
                for (c in nodes) when (c.name) {
                    "table-column" -> {
                        val w = Odf.len(st.props("table-column", c.a("table:style-name"))["col|style:column-width"]) ?: 80f
                        repeat((c.int("table:number-columns-repeated") ?: 1).coerceIn(1, 64)) { widths.add(w) }
                    }
                    "table-columns", "table-header-columns" -> cols(c.kids)
                }
            }
            cols(n.kids)
            val rows = ArrayList<List<DocBlock.Cell>>()
            var borders = false
            fun rowsOf(nodes: List<XNode>) {
                for (r in nodes) when (r.name) {
                    "table-row" -> {
                        val cells = ArrayList<DocBlock.Cell>()
                        for (c in r.kids) {
                            if (c.name != "table-cell") continue
                            val cp = st.props("table-cell", c.a("table:style-name"))
                            if (Odf.hasBorder(cp["c|fo:border"]) || Odf.hasBorder(cp["c|fo:border-top"])) borders = true
                            val inner = ArrayList<DocBlock>()
                            blocks(c.kids, inner, false)
                            val span = (c.int("table:number-columns-spanned") ?: 1).coerceAtLeast(1)
                            repeat((c.int("table:number-columns-repeated") ?: 1).coerceIn(1, 64)) {
                                cells.add(DocBlock.Cell(inner, span, Odf.color(cp["c|fo:background-color"])))
                            }
                        }
                        if (cells.isNotEmpty()) rows.add(cells)
                    }
                    "table-header-rows", "table-rows", "table-row-group" -> rowsOf(r.kids)
                }
            }
            rowsOf(n.kids)
            if (rows.isEmpty()) return
            val ncols = maxOf(widths.size, rows.maxOf { r -> r.sumOf { it.span } })
            while (widths.size < ncols) widths.add(80f)
            into.add(DocBlock.Table(rows, widths.map { (it * 10).toInt().coerceAtLeast(1) }, borders))
        }
    }
}
