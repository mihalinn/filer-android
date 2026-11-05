package local.filemanager

/** OpenDocument のプレゼンテーション(.odp)を、PowerPoint と同じ描画(SlideDeck)にする。Android に依存しない。 */
internal object Odp {
    private const val DEFAULT_W = 720f
    private const val DEFAULT_H = 405f

    fun parse(read: (String) -> ByteArray?): SlideDeck {
        val content = Odf.xml(read, "content.xml") ?: throw OpException(Txt(R.string.odp_001))
        val styles = Odf.xml(read, "styles.xml")
        val st = OdfStyles(styles, content)
        val pres = content.child("body")?.child("presentation") ?: throw OpException(Txt(R.string.odp_002))

        // ページの大きさ: マスターページ → ページレイアウト
        val layouts = HashMap<String, Pair<Float, Float>>()
        val masters = HashMap<String, XNode>()
        val masterLayout = HashMap<String, String?>()
        for (root in listOfNotNull(styles, content)) for (box in root.kids) {
            if (box.name == "automatic-styles") for (pl in box.children("page-layout")) {
                val p = pl.child("page-layout-properties") ?: continue
                val w = Odf.len(p.a("fo:page-width")) ?: continue
                val h = Odf.len(p.a("fo:page-height")) ?: continue
                pl.a("style:name")?.let { layouts[it] = w to h }
            }
            if (box.name == "master-styles") for (m in box.children("master-page")) {
                val nm = m.a("style:name") ?: continue
                masters[nm] = m
                masterLayout[nm] = m.a("style:page-layout-name")
            }
        }
        val pages = pres.children("page")
        val firstMaster = pages.firstOrNull()?.a("draw:master-page-name")
        val size = (masterLayout[firstMaster] ?: masterLayout.values.firstOrNull())?.let { layouts[it] } ?: (DEFAULT_W to DEFAULT_H)

        val slides = pages.mapIndexed { i, pg ->
            val shapes = ArrayList<SShape>()
            val master = pg.a("draw:master-page-name")?.let { masters[it] }
            val bgProps = st.props("drawing-page", pg.a("draw:style-name")).let { own ->
                if (own["d|draw:fill"] != null) own else {
                    // 自分に指定が無ければ、マスターページの背景
                    val ms = master?.a("draw:style-name")
                    if (ms != null) st.props("drawing-page", ms) else own
                }
            }
            val bg = background(bgProps, read)
            master?.let { m -> Shapes(st, read).convert(m.kids, shapes, masterMode = true) }
            Shapes(st, read).convert(pg.kids, shapes, masterMode = false)
            SlideDoc(i + 1, pg.a("presentation:visibility") == "hidden", bg, shapes)
        }
        return SlideDeck(size.first, size.second, slides)
    }

    private fun background(p: Map<String, String>, read: (String) -> ByteArray?): Paint? {
        return when (p["d|draw:fill"]) {
            "solid" -> Odf.color(p["d|draw:fill-color"])?.let { Paint.Solid(it) }
            "bitmap" -> null
            else -> null
        }
    }

    private class Shapes(val st: OdfStyles, val read: (String) -> ByteArray?) {
        fun convert(nodes: List<XNode>, out: MutableList<SShape>, masterMode: Boolean) {
            for (n in nodes) when (n.name) {
                "frame" -> frame(n, out, masterMode)
                "custom-shape", "rect", "ellipse", "circle", "polygon", "polyline", "path", "regular-polygon" -> shape(n, out, masterMode)
                "line", "connector" -> line(n, out)
                "g" -> convert(n.kids, out, masterMode)
                "text-box" -> {}
            }
        }

        private fun box(n: XNode): FloatArray? {
            val x = Odf.len(n.a("svg:x")) ?: 0f
            val y = Odf.len(n.a("svg:y")) ?: 0f
            val w = Odf.len(n.a("svg:width")) ?: return null
            val h = Odf.len(n.a("svg:height")) ?: return null
            return floatArrayOf(x, y, w, h)
        }

        /** 図形(や枠)のグラフィックの書式。枠のプレゼンテーション用スタイル → 図形のスタイルの順に重ねる。 */
        private fun gprops(n: XNode): Map<String, String> {
            val m = LinkedHashMap<String, String>()
            n.a("presentation:style-name")?.let { m.putAll(st.props("presentation", it)) }
            val gs = n.a("draw:style-name")
            m.putAll(st.props("graphic", gs))
            return m
        }

        private fun fillOf(p: Map<String, String>): Paint? = when (p["g|draw:fill"]) {
            "solid" -> Odf.color(p["g|draw:fill-color"])?.let { Paint.Solid(it) }
            else -> null
        }

        private fun lineOf(p: Map<String, String>): SLine? {
            if (p["g|draw:stroke"] == null || p["g|draw:stroke"] == "none") return null
            val color = Odf.color(p["g|svg:stroke-color"]) ?: 0xFF3465A4.toInt()
            val w = Odf.len(p["g|svg:stroke-width"])?.coerceAtLeast(0.5f) ?: 0.75f
            val dash = if (p["g|draw:stroke"] == "dash") floatArrayOf(w * 4, w * 3) else null
            return SLine(color, w, dash, p["g|draw:marker-start"] != null, p["g|draw:marker-end"] != null)
        }

        private fun frame(n: XNode, out: MutableList<SShape>, masterMode: Boolean) {
            val b = box(n) ?: return
            val cls = n.a("presentation:class")
            // マスターにある「題名・本文・日付・フッター」などの枠は、スライドには描かない
            if (masterMode && cls != null) return
            val p = gprops(n)
            n.child("image")?.let { img ->
                val href = img.a("xlink:href") ?: return
                if (href.startsWith("data:")) return
                out.add(SShape(b[0], b[1], b[2], b[3], 0f, false, false, null, null, null, null, SPicture(href.removePrefix("./"), 0f, 0f, 0f, 0f), null))
                return
            }
            n.child("text-box")?.let { tb ->
                val body = textBody(tb.kids, p, anchorDefault = if (cls == "title") 1 else 0, sizeDefault = if (cls == "title") 32f else 18f)
                out.add(SShape(b[0], b[1], b[2], b[3], 0f, false, false, Geom("rect"), fillOf(p), lineOf(p), body, null, null))
                return
            }
            n.child("table")?.let { t ->
                table(t)?.let { out.add(SShape(b[0], b[1], b[2], b[3], 0f, false, false, null, null, null, null, null, it)) }
                return
            }
            if (n.child("object") != null || n.child("object-ole") != null || n.child("plugin") != null) {
                out.add(placeholder(b, Txt(R.string.odp_003)))
            }
        }

        private fun placeholder(b: FloatArray, label: String): SShape {
            val run = SRun(label, 14f, 0xFF7F7F7F.toInt(), false, false, false, false, 0, null, null)
            val para = SPara(listOf(run), 0, 0f, 0f, 0f, 0f, null, null, null, null, 1f, 14f)
            return SShape(
                b[0], b[1], b[2], b[3], 0f, false, false, Geom("rect"), Paint.Solid(0xFFF3F3F3.toInt()),
                SLine(0xFFBBBBBB.toInt(), 1f, floatArrayOf(4f, 3f), false, false),
                STextBody(listOf(para), 1, 7.2f, 3.6f, 7.2f, 3.6f, true, null), null, null,
            )
        }

        private fun shape(n: XNode, out: MutableList<SShape>, masterMode: Boolean) {
            val b = box(n) ?: return
            val p = gprops(n)
            val prst = when (n.name) {
                "ellipse", "circle" -> "ellipse"
                "custom-shape" -> when (n.child("enhanced-geometry")?.a("draw:type")) {
                    "ellipse" -> "ellipse"
                    "round-rectangle" -> "roundRect"
                    "isosceles-triangle" -> "triangle"
                    "diamond" -> "diamond"
                    "right-arrow" -> "rightArrow"
                    else -> "rect"
                }
                else -> "rect"
            }
            val fill = fillOf(p)
            val ln = lineOf(p)
            val sz = if (p["g|draw:textarea-vertical-align"] == "top") 0 else 1
            val body = if (n.kids.any { it.name == "p" || it.name == "list" }) textBody(n.kids, p, anchorDefault = sz, sizeDefault = 18f) else null
            out.add(SShape(b[0], b[1], b[2], b[3], 0f, false, false, Geom(prst), fill, ln, body, null, null))
        }

        private fun line(n: XNode, out: MutableList<SShape>) {
            val x1 = Odf.len(n.a("svg:x1")) ?: return
            val y1 = Odf.len(n.a("svg:y1")) ?: return
            val x2 = Odf.len(n.a("svg:x2")) ?: return
            val y2 = Odf.len(n.a("svg:y2")) ?: return
            val p = gprops(n)
            val ln = lineOf(p) ?: SLine(0xFF3465A4.toInt(), 0.75f, null, false, false)
            out.add(
                SShape(
                    minOf(x1, x2), minOf(y1, y2), kotlin.math.abs(x2 - x1), kotlin.math.abs(y2 - y1), 0f, x2 < x1, y2 < y1,
                    Geom("line"), null, ln, null, null, null,
                ),
            )
        }

        // ---------------------------------------------------------------- 文字

        private fun textBody(nodes: List<XNode>, gp: Map<String, String>, anchorDefault: Int, sizeDefault: Float): STextBody {
            val paras = ArrayList<SPara>()
            collect(nodes, gp, sizeDefault, 0, null, paras)
            val anchor = when (gp["g|draw:textarea-vertical-align"]) { "middle" -> 1; "bottom" -> 2; "top" -> 0; else -> anchorDefault }
            val l = Odf.len(gp["g|fo:padding-left"]) ?: 7.2f
            val t = Odf.len(gp["g|fo:padding-top"]) ?: 3.6f
            val r = Odf.len(gp["g|fo:padding-right"]) ?: 7.2f
            val bt = Odf.len(gp["g|fo:padding-bottom"]) ?: 3.6f
            return STextBody(paras, anchor, l, t, r, bt, gp["g|fo:wrap-option"] != "no-wrap", null)
        }

        private fun collect(nodes: List<XNode>, gp: Map<String, String>, sizeDefault: Float, depth: Int, listStyle: String?, out: MutableList<SPara>, base: Int = 0) {
            var counter = base
            for (n in nodes) when (n.name) {
                "p", "h" -> { counter++; out.add(para(n, gp, sizeDefault, depth, listStyle, counter)) }
                "list" -> {
                    val ls = n.a("text:style-name") ?: listStyle
                    var cnt = 0
                    for (item in n.children("list-item")) {
                        cnt++
                        val first = ArrayList<SPara>()
                        collect(item.kids, gp, sizeDefault, depth + 1, ls, first, cnt - 1)
                        out.addAll(first)
                    }
                }
            }
        }

        private fun para(n: XNode, gp: Map<String, String>, sizeDefault: Float, depth: Int, listStyle: String?, counter: Int): SPara {
            val pp = LinkedHashMap<String, String>()
            // 文字の既定: 図形のスタイルの中の文字の指定 → 段落のスタイル
            for ((k, v) in gp) if (k.startsWith("t|") || k.startsWith("p|")) pp[k] = v
            pp.putAll(st.props("paragraph", n.a("text:style-name")))
            val baseRpr = OdfText.rpr(pp, sizeDefault)
            val runs = ArrayList<SRun>()
            val lastSpace = booleanArrayOf(true)
            fun add(text: String, p: Map<String, String>, link: String?) {
                if (text.isEmpty()) return
                val r = OdfText.rpr(p, sizeDefault)
                val size = r.sizeHp?.let { it / 2f } ?: sizeDefault
                runs.add(
                    SRun(
                        text, size, r.color ?: 0xFF000000.toInt(), r.bold == true, r.italic == true, r.underline == true || link != null,
                        r.strike == true, r.vert ?: 0, r.font, link,
                    ),
                )
            }
            fun walk(x: XNode, p: Map<String, String>, link: String?) {
                for (k in x.kids) when (k.name) {
                    "#text" -> {
                        val sb = StringBuilder()
                        for (ch in k.text) {
                            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
                                if (!lastSpace[0]) { sb.append(' '); lastSpace[0] = true }
                            } else {
                                sb.append(ch); lastSpace[0] = false
                            }
                        }
                        add(sb.toString(), p, link)
                    }
                    "span" -> walk(k, LinkedHashMap(p).also { m -> m.putAll(st.props("text", k.a("text:style-name"))) }, link)
                    "a" -> walk(k, p, k.a("xlink:href") ?: link)
                    "s" -> { add(" ".repeat((k.int("text:c") ?: 1).coerceIn(1, 100)), p, link); lastSpace[0] = false }
                    "tab" -> { add("\t", p, link); lastSpace[0] = false }
                    "line-break" -> { add("\n", p, link); lastSpace[0] = true }
                    "annotation", "frame", "note" -> {}
                    else -> walk(k, p, link)
                }
            }
            walk(n, pp, null)
            val endSize = baseRpr.sizeHp?.let { it / 2f } ?: sizeDefault
            val size = runs.maxOfOrNull { it.sizePt } ?: endSize
            val ls = listStyle?.let { st.listStyles[it] }
            val level = ls?.kids?.firstOrNull { (it.int("text:level") ?: 0) == depth }
            val hasText = runs.any { it.text.isNotBlank() }
            val marker = if (depth > 0 && hasText) markerOf(level, counter) else null
            val ml = Odf.len(pp["p|fo:margin-left"]) ?: (if (depth > 0) depth * size * 0.9f else 0f)
            val ti = Odf.len(pp["p|fo:text-indent"]) ?: (if (marker != null) -size * 0.9f else 0f)
            val algn = when (pp["p|fo:text-align"]) { "center" -> 0; "end", "right" -> 1; "justify" -> 2; else -> -1 }
            return SPara(
                runs, algn, ml, ti, Odf.len(pp["p|fo:margin-top"]) ?: 0f, Odf.len(pp["p|fo:margin-bottom"]) ?: 0f,
                Odf.percent(pp["p|fo:line-height"]), null, marker, runs.firstOrNull()?.color, 1f, size,
            )
        }

        private fun markerOf(level: XNode?, n: Int): String {
            if (level == null) return "•"
            return when (level.name) {
                "list-level-style-number" -> (level.a("style:num-prefix") ?: "") + n + (level.a("style:num-suffix") ?: ".")
                "list-level-style-bullet" -> level.a("text:bullet-char")?.takeIf { it.isNotEmpty() } ?: "•"
                else -> "•"
            }
        }

        // ---------------------------------------------------------------- 表

        private fun table(t: XNode): STable? {
            val colW = ArrayList<Float>()
            fun cols(nodes: List<XNode>) {
                for (c in nodes) when (c.name) {
                    "table-column" -> {
                        val w = Odf.len(st.props("table-column", c.a("table:style-name"))["col|style:column-width"]) ?: 72f
                        repeat((c.int("table:number-columns-repeated") ?: 1).coerceIn(1, 64)) { colW.add(w) }
                    }
                    "table-columns", "table-header-columns" -> cols(c.kids)
                }
            }
            cols(t.kids)
            val rowH = ArrayList<Float>()
            val rows = ArrayList<List<STableCell>>()
            fun rowsOf(nodes: List<XNode>) {
                for (r in nodes) when (r.name) {
                    "table-row" -> {
                        rowH.add(Odf.len(st.props("table-row", r.a("table:style-name"))["row|style:row-height"]) ?: 24f)
                        val cells = ArrayList<STableCell>()
                        for (c in r.kids) {
                            val covered = c.name == "covered-table-cell"
                            if (c.name != "table-cell" && !covered) continue
                            val cp = st.props("table-cell", c.a("table:style-name"))
                            val paras = ArrayList<SPara>()
                            collect(c.kids, cp.mapKeys { (k, _) -> if (k.startsWith("c|")) "g|" + k.substring(2) else k }, 14f, 0, null, paras)
                            val body = STextBody(paras, 1, 5.4f, 3.6f, 5.4f, 3.6f, true, null)
                            val fill = Odf.color(cp["c|fo:background-color"])
                            fun side(key: String): SLine? {
                                val v = cp["c|fo:border-$key"] ?: cp["c|fo:border"] ?: return null
                                if (!Odf.hasBorder(v)) return null
                                val parts = v.trim().split(Regex("\\s+"))
                                val w = Odf.len(parts.getOrNull(0)) ?: 0.75f
                                val col = Odf.color(parts.lastOrNull()) ?: 0xFF000000.toInt()
                                return SLine(col, w.coerceAtLeast(0.5f), null, false, false)
                            }
                            repeat((c.int("table:number-columns-repeated") ?: 1).coerceIn(1, 64)) {
                                cells.add(
                                    STableCell(
                                        body, fill, (c.int("table:number-columns-spanned") ?: 1).coerceAtLeast(1),
                                        (c.int("table:number-rows-spanned") ?: 1).coerceAtLeast(1), covered, false,
                                        side("left"), side("top"), side("right"), side("bottom"),
                                    ),
                                )
                            }
                        }
                        rows.add(cells)
                    }
                    "table-header-rows", "table-rows", "table-row-group" -> rowsOf(r.kids)
                }
            }
            rowsOf(t.kids)
            if (rows.isEmpty()) return null
            return STable(colW.ifEmpty { listOf(72f) }, rowH, rows)
        }
    }
}
