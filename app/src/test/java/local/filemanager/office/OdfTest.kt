package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ONS = """xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" xmlns:xlink="http://www.w3.org/1999/xlink" xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" xmlns:presentation="urn:oasis:names:tc:opendocument:xmlns:presentation:1.0" xmlns:config="urn:oasis:names:tc:opendocument:xmlns:config:1.0""""

private fun files(vararg p: Pair<String, String>): (String) -> ByteArray? {
    val m = p.toMap()
    return { name -> m[name]?.toByteArray() }
}

private fun odt(body: String, autos: String = "", styles: String = ""): (String) -> ByteArray? = files(
    "content.xml" to """<office:document-content $ONS><office:automatic-styles>$autos</office:automatic-styles><office:body><office:text>$body</office:text></office:body></office:document-content>""",
    "styles.xml" to """<office:document-styles $ONS><office:styles>$styles</office:styles></office:document-styles>""",
)

class OdfUnitTest {
    @Test
    fun lengthUnits() {
        assertEquals(72f, Odf.len("1in")!!, 0.01f)
        assertEquals(28.3465f, Odf.len("1cm")!!, 0.01f)
        assertEquals(12f, Odf.len("12pt")!!, 0.01f)
        assertEquals(0.75f, Odf.len("1px")!!, 0.01f)
        assertEquals(-14.17f, Odf.len("-5mm")!!, 0.01f)
        assertNull(Odf.len("80%"))
        assertNull(Odf.len(null))
    }

    @Test
    fun colors() {
        assertEquals(0xFFFF0000.toInt(), Odf.color("#ff0000"))
        assertNull(Odf.color("transparent"))
        assertEquals(0.8f, Odf.percent("80%")!!, 0.001f)
    }

    @Test
    fun borders() {
        assertTrue(Odf.hasBorder("0.74pt solid #000000"))
        assertTrue(Odf.hasBorder("1pt solid #333333"))
        assertFalse(Odf.hasBorder("none"))
        assertFalse(Odf.hasBorder("0pt solid #000000"))
        assertFalse(Odf.hasBorder(null))
    }
}

class OdtTest {
    @Test
    fun paragraphsHeadingsAndSpans() {
        val read = odt(
            """<text:h text:style-name="H" text:outline-level="2">見出し</text:h>
<text:p text:style-name="P1">はじめ<text:span text:style-name="B">太字</text:span>と<text:span text:style-name="R">赤</text:span>   空白<text:s text:c="3"/>つづき<text:line-break/>改行後<text:a xlink:href="https://example.com/">リンク</text:a></text:p>""",
            autos = """<style:style style:name="P1" style:family="paragraph"><style:paragraph-properties fo:text-align="center" fo:margin-top="0.5cm"/></style:style>
<style:style style:name="B" style:family="text"><style:text-properties fo:font-weight="bold" fo:font-size="16pt"/></style:style>
<style:style style:name="R" style:family="text"><style:text-properties fo:color="#ff0000" style:text-underline-style="solid" fo:font-style="italic"/></style:style>""",
            styles = """<style:style style:name="H" style:family="paragraph"><style:text-properties fo:font-size="20pt" fo:font-weight="bold"/></style:style>""",
        )
        val doc = Odt.parse(read)
        val blocks = doc.blocks
        assertEquals(2, blocks.size)
        val h = blocks[0] as DocBlock.Para
        assertEquals(2, h.heading)
        assertEquals("見出し", h.text)
        assertEquals(true, h.runs[0].r.bold)
        assertEquals(40, h.runs[0].r.sizeHp)
        val p = blocks[1] as DocBlock.Para
        assertEquals("center", p.align)
        assertEquals(14.17f, p.beforePt, 0.05f)
        val bold = p.runs.first { it.text == "太字" }
        assertEquals(true, bold.r.bold)
        assertEquals(32, bold.r.sizeHp)
        val red = p.runs.first { it.text == "赤" }
        assertEquals(0xFFFF0000.toInt(), red.r.color)
        assertEquals(true, red.r.underline)
        assertEquals(true, red.r.italic)
        // 連続する空白は 1 つ、text:s は指定の数、text:line-break は改行
        val joined = p.text
        assertTrue(joined, joined.contains("赤 空白   つづき\n改行後"))
        assertEquals("https://example.com/", p.runs.last().link)
    }

    @Test
    fun listsAreNumberedAndBulleted() {
        val read = odt(
            """<text:list text:style-name="L1"><text:list-item><text:p>りんご</text:p></text:list-item><text:list-item><text:p>みかん</text:p>
<text:list><text:list-item><text:p>ふじ</text:p></text:list-item></text:list></text:list-item></text:list>
<text:list text:style-name="L2"><text:list-item><text:p>いち</text:p></text:list-item><text:list-item><text:p>に</text:p></text:list-item></text:list>""",
            autos = """<text:list-style style:name="L1"><text:list-level-style-bullet text:level="1" text:bullet-char="●"/><text:list-level-style-bullet text:level="2" text:bullet-char="–"/></text:list-style>
<text:list-style style:name="L2"><text:list-level-style-number text:level="1" style:num-format="1" style:num-suffix="."/></text:list-style>""",
        )
        val paras = Odt.parse(read).blocks.map { it as DocBlock.Para }
        assertEquals(listOf("●", "●", "–", "1.", "2."), paras.map { it.marker })
        assertEquals(listOf("りんご", "みかん", "ふじ", "いち", "に"), paras.map { it.text })
        assertTrue(paras[2].leftPt > paras[0].leftPt)
    }

    @Test
    fun tablesKeepSpansAndFills() {
        val read = odt(
            """<table:table table:name="T"><table:table-column table:style-name="C1"/><table:table-column table:style-name="C2" table:number-columns-repeated="2"/>
<table:table-row><table:table-cell table:style-name="Cell" table:number-columns-spanned="3"><text:p>タイトル</text:p></table:table-cell><table:covered-table-cell/><table:covered-table-cell/></table:table-row>
<table:table-row><table:table-cell><text:p>a</text:p></table:table-cell><table:table-cell><text:p>b</text:p></table:table-cell><table:table-cell><text:p>c</text:p></table:table-cell></table:table-row></table:table>""",
            autos = """<style:style style:name="C1" style:family="table-column"><style:table-column-properties style:column-width="4cm"/></style:style>
<style:style style:name="C2" style:family="table-column"><style:table-column-properties style:column-width="2cm"/></style:style>
<style:style style:name="Cell" style:family="table-cell"><style:table-cell-properties fo:background-color="#ffff00" fo:border="0.05pt solid #000000"/></style:style>""",
        )
        val t = Odt.parse(read).blocks.single() as DocBlock.Table
        assertEquals(2, t.rows.size)
        assertEquals(3, t.rows[0][0].span)
        assertEquals(0xFFFFFF00.toInt(), t.rows[0][0].fill)
        assertTrue(t.borders)
        assertEquals(3, t.weights.size)
        assertTrue(t.weights[0] > t.weights[1])
        assertEquals("c", ((t.rows[1][2].blocks[0]) as DocBlock.Para).text)
    }

    @Test
    fun pageBreakSplitsPages() {
        val read = odt(
            """<text:p>1枚目</text:p><text:p text:style-name="PB">2枚目</text:p><text:p>つづき</text:p>""",
            autos = """<style:style style:name="PB" style:family="paragraph"><style:paragraph-properties fo:break-before="page"/></style:style>""",
        )
        val doc = Odt.parse(read)
        assertEquals(2, doc.pages.size)
        assertEquals(2, doc.pages[1].size)
    }

    @Test
    fun framesBecomeImages() {
        val read = odt("""<text:p><draw:frame svg:width="5cm" svg:height="2cm"><draw:image xlink:href="Pictures/a.png"/></draw:frame></text:p>""")
        val img = Odt.parse(read).blocks.filterIsInstance<DocBlock.Image>().single()
        assertEquals("Pictures/a.png", img.path)
        assertEquals(141.7f, img.widthPt, 0.1f)
    }

    @Test
    fun notOdfFails() {
        try {
            Odt.parse(files())
            org.junit.Assert.fail()
        } catch (e: OpException) {
            assertNotNull(e.message)
        }
    }
}

class OdsTest {
    private fun ods(rows: String, autos: String = "", extra: List<Pair<String, String>> = emptyList()): (String) -> ByteArray? = files(
        "content.xml" to """<office:document-content $ONS><office:automatic-styles>$autos</office:automatic-styles><office:body><office:spreadsheet>$rows</office:spreadsheet></office:body></office:document-content>""",
        *extra.toTypedArray(),
    )

    @Test
    fun readsSheetsValuesAndRepeats() {
        val read = ods(
            """<table:table table:name="売上"><table:table-column table:style-name="W"/><table:table-column/>
<table:table-row><table:table-cell office:value-type="string"><text:p>品名</text:p></table:table-cell><table:table-cell office:value-type="string"><text:p>金額</text:p></table:table-cell></table:table-row>
<table:table-row><table:table-cell office:value-type="string"><text:p>りんご</text:p></table:table-cell><table:table-cell office:value-type="float" office:value="1200"><text:p>1,200円</text:p></table:table-cell></table:table-row>
<table:table-row table:number-rows-repeated="1048000"><table:table-cell table:number-columns-repeated="1024"/></table:table-row></table:table>
<table:table table:name="空"><table:table-row><table:table-cell/></table:table-row></table:table>""",
            autos = """<style:style style:name="W" style:family="table-column"><style:table-column-properties style:column-width="5cm"/></style:style>""",
        )
        val book = OdsBook(read)
        assertEquals(listOf("売上", "空"), book.sheetNames)
        val s = book.load(0)
        assertEquals(2, s.rows.size)
        assertEquals(listOf("品名", "金額"), s.rows[0])
        assertEquals("1,200円", s.rows[1][1])
        assertEquals(XlsxSheet.KIND_NUMBER, s.kindAt(1, 1))
        assertEquals(XlsxSheet.KIND_TEXT, s.kindAt(1, 0))
        assertTrue(s.colWidths.getValue(0) > 20f)
        assertEquals(0, book.load(1).rows.count { r -> r.any { it.isNotEmpty() } })
    }

    @Test
    fun stylesMergesAndColumnRepeats() {
        val read = ods(
            """<table:table table:name="S"><table:table-column table:number-columns-repeated="3"/>
<table:table-row><table:table-cell table:style-name="Head" table:number-columns-spanned="2" office:value-type="string"><text:p>見出し</text:p></table:table-cell><table:covered-table-cell/><table:table-cell office:value-type="string"><text:p>x</text:p></table:table-cell></table:table-row>
<table:table-row><table:table-cell office:value-type="string" table:number-columns-repeated="2"><text:p>同じ</text:p></table:table-cell></table:table-row></table:table>""",
            autos = """<style:style style:name="Head" style:family="table-cell"><style:table-cell-properties fo:background-color="#cccccc" fo:border="0.74pt solid #000000"/><style:text-properties fo:font-weight="bold" fo:color="#0000ff"/></style:style>""",
        )
        val book = OdsBook(read)
        val s = book.load(0)
        assertEquals(1, s.merges.size)
        assertEquals(listOf(0, 0, 0, 1), s.merges[0].toList())
        val st = book.styles[s.styleAt(0, 0)]
        assertTrue(st.bold)
        assertEquals(0xFFCCCCCC.toInt(), st.fill)
        assertEquals(0xFF0000FF.toInt(), st.color)
        assertTrue(st.border)
        assertEquals(listOf("同じ", "同じ"), s.rows[1])
    }

    @Test
    fun frozenRowsFromSettings() {
        val settings = """<office:document-settings $ONS><office:settings><config:config-item-set config:name="ooo:view-settings"><config:config-item-map-indexed config:name="Views"><config:config-item-map-entry><config:config-item-map-named config:name="Tables"><config:config-item-map-entry config:name="F"><config:config-item config:name="VerticalSplitMode" config:type="short">2</config:config-item><config:config-item config:name="VerticalSplitPosition" config:type="int">1</config:config-item></config:config-item-map-entry></config:config-item-map-named></config:config-item-map-entry></config:config-item-map-indexed></config:config-item-set></office:settings></office:document-settings>"""
        val read = ods(
            """<table:table table:name="F"><table:table-row><table:table-cell office:value-type="string"><text:p>h</text:p></table:table-cell></table:table-row></table:table>""",
            extra = listOf("settings.xml" to settings),
        )
        assertEquals(1, OdsBook(read).load(0).frozenRows)
    }
}

class OdpTest {
    private fun odp(slides: String, autos: String = "", masterShapes: String = ""): (String) -> ByteArray? = files(
        "content.xml" to """<office:document-content $ONS><office:automatic-styles>$autos</office:automatic-styles><office:body><office:presentation>$slides</office:presentation></office:body></office:document-content>""",
        "styles.xml" to """<office:document-styles $ONS><office:automatic-styles><style:page-layout style:name="PL"><style:page-layout-properties fo:page-width="28cm" fo:page-height="15.75cm"/></style:page-layout></office:automatic-styles>
<office:master-styles><style:master-page style:name="M" style:page-layout-name="PL" draw:style-name="dpM">$masterShapes</style:master-page></office:master-styles></office:document-styles>""",
    )

    @Test
    fun readsSizeBackgroundTextAndShapes() {
        val read = odp(
            """<draw:page draw:name="p1" draw:master-page-name="M" draw:style-name="dp1">
<draw:frame presentation:class="title" svg:x="2cm" svg:y="1cm" svg:width="24cm" svg:height="3cm" draw:style-name="gr1" presentation:style-name="pr1"><draw:text-box><text:p text:style-name="Pc">題名です</text:p></draw:text-box></draw:frame>
<draw:frame presentation:class="outline" svg:x="2cm" svg:y="5cm" svg:width="24cm" svg:height="8cm"><draw:text-box><text:list text:style-name="L"><text:list-item><text:p>ひとつめ</text:p></text:list-item><text:list-item><text:p>ふたつめ</text:p></text:list-item></text:list></draw:text-box></draw:frame>
<draw:custom-shape svg:x="1cm" svg:y="1cm" svg:width="3cm" svg:height="2cm" draw:style-name="gr2"><draw:enhanced-geometry draw:type="ellipse"/><text:p>丸</text:p></draw:custom-shape>
<draw:line svg:x1="5cm" svg:y1="10cm" svg:x2="2cm" svg:y2="8cm" draw:style-name="gr3"/>
</draw:page>
<draw:page draw:name="p2" draw:master-page-name="M" presentation:visibility="hidden"></draw:page>""",
            autos = """<style:style style:name="dp1" style:family="drawing-page"><style:drawing-page-properties draw:fill="solid" draw:fill-color="#112233"/></style:style>
<style:style style:name="gr1" style:family="graphic"><style:graphic-properties draw:fill="none" draw:textarea-vertical-align="middle"/></style:style>
<style:style style:name="pr1" style:family="presentation"><style:text-properties fo:font-size="32pt" fo:color="#ffffff" fo:font-weight="bold"/></style:style>
<style:style style:name="Pc" style:family="paragraph"><style:paragraph-properties fo:text-align="center"/></style:style>
<style:style style:name="gr2" style:family="graphic"><style:graphic-properties draw:fill="solid" draw:fill-color="#ff9900" draw:stroke="solid" svg:stroke-color="#000000" svg:stroke-width="0.1cm"/></style:style>
<style:style style:name="gr3" style:family="graphic"><style:graphic-properties draw:stroke="solid" svg:stroke-color="#ff0000" svg:stroke-width="0.05cm" draw:marker-end="Arrow"/></style:style>
<text:list-style style:name="L"><text:list-level-style-bullet text:level="1" text:bullet-char="■"/></text:list-style>""",
            masterShapes = """<draw:frame svg:x="0cm" svg:y="15cm" svg:width="28cm" svg:height="0.75cm" draw:style-name="gr2"><draw:text-box><text:p>マスターの帯</text:p></draw:text-box></draw:frame>
<draw:frame presentation:class="title" svg:x="1cm" svg:y="1cm" svg:width="5cm" svg:height="1cm"><draw:text-box><text:p>タイトルを入力</text:p></draw:text-box></draw:frame>""",
        )
        val deck = Odp.parse(read)
        assertEquals(793.7f, deck.widthPt, 0.5f)
        assertEquals(446.4f, deck.heightPt, 0.5f)
        assertEquals(2, deck.slides.size)
        assertTrue(deck.slides[1].hidden)
        val s = deck.slides[0]
        assertEquals(0xFF112233.toInt(), (s.background as Paint.Solid).argb)
        val title = s.shapes.first { it.text?.paras?.firstOrNull()?.runs?.firstOrNull()?.text == "題名です" }
        val run = title.text!!.paras[0].runs[0]
        assertEquals(32f, run.sizePt, 0.01f)
        assertEquals(0xFFFFFFFF.toInt(), run.color)
        assertTrue(run.bold)
        assertEquals(0, title.text!!.paras[0].algn)
        assertEquals(1, title.text!!.anchor)
        assertEquals(56.69f, title.x, 0.1f)
        val body = s.shapes.first { it.text?.paras?.firstOrNull()?.runs?.firstOrNull()?.text == "ひとつめ" }
        assertEquals(listOf("■", "■"), body.text!!.paras.map { it.marker })
        val ell = s.shapes.first { it.geom?.prst == "ellipse" }
        assertEquals(0xFFFF9900.toInt(), (ell.fill as Paint.Solid).argb)
        assertNotNull(ell.line)
        assertEquals("丸", ell.text!!.paras[0].runs[0].text)
        val line = s.shapes.first { it.geom?.prst == "line" }
        assertTrue(line.flipH)
        assertTrue(line.flipV)
        assertTrue(line.line!!.tail)
        // マスターの飾りは描き、マスターの題名の枠(プレースホルダー)は描かない
        assertTrue(s.shapes.any { it.text?.paras?.firstOrNull()?.runs?.firstOrNull()?.text == "マスターの帯" })
        assertFalse(s.shapes.any { it.text?.paras?.firstOrNull()?.runs?.firstOrNull()?.text == "タイトルを入力" })
    }

    @Test
    fun tablesAndImages() {
        val read = odp(
            """<draw:page draw:master-page-name="M">
<draw:frame svg:x="1cm" svg:y="1cm" svg:width="10cm" svg:height="4cm"><table:table><table:table-column table:style-name="c1" table:number-columns-repeated="2"/>
<table:table-row table:style-name="r1"><table:table-cell table:style-name="tc" table:number-columns-spanned="2"><text:p>結合</text:p></table:table-cell><table:covered-table-cell/></table:table-row>
<table:table-row><table:table-cell><text:p>a</text:p></table:table-cell><table:table-cell><text:p>b</text:p></table:table-cell></table:table-row></table:table></draw:frame>
<draw:frame svg:x="12cm" svg:y="1cm" svg:width="6cm" svg:height="4cm"><draw:image xlink:href="Pictures/x.png"/></draw:frame></draw:page>""",
            autos = """<style:style style:name="c1" style:family="table-column"><style:table-column-properties style:column-width="5cm"/></style:style>
<style:style style:name="r1" style:family="table-row"><style:table-row-properties style:row-height="1.5cm"/></style:style>
<style:style style:name="tc" style:family="table-cell"><style:table-cell-properties fo:background-color="#dddddd" fo:border="1pt solid #333333"/></style:style>""",
        )
        val s = Odp.parse(read).slides[0]
        val t = s.shapes.first { it.table != null }.table!!
        assertEquals(2, t.colW.size)
        assertEquals(141.7f, t.colW[0], 0.1f)
        assertEquals(42.5f, t.rowH[0], 0.1f)
        assertEquals(2, t.rows[0][0].gridSpan)
        assertTrue(t.rows[0][1].hMerge)
        assertEquals(0xFFDDDDDD.toInt(), t.rows[0][0].fill)
        assertNotNull(t.rows[0][0].top)
        assertEquals("結合", t.rows[0][0].body.paras[0].runs[0].text)
        assertEquals("Pictures/x.png", s.shapes.first { it.picture != null }.picture!!.path)
    }
}

class RtfTest {
    private fun doc(rtf: String) = Rtf.parse(rtf.toByteArray(Charsets.ISO_8859_1))
    private fun paras(d: DocxDoc) = d.blocks.filterIsInstance<DocBlock.Para>()

    @Test
    fun detects() {
        assertTrue(Rtf.looksLikeRtf("{\\rtf1\\ansi hello}".toByteArray()))
        assertFalse(Rtf.looksLikeRtf("plain".toByteArray()))
    }

    @Test
    fun textAndFormatting() {
        val d = doc("""{\rtf1\ansi\deff0{\fonttbl{\f0 Arial;}{\f1 Courier New;}}{\colortbl;\red255\green0\blue0;\red0\green0\blue255;}
\pard\qc\fs32 Title\par
\pard\ql Plain {\b bold} {\i italic} {\ul under} {\cf1 red} {\f1\fs20 mono}\par
\pard\qr\sb200\sa100 Right\line next\tab tabbed\par}""")
        val p = paras(d)
        assertEquals(3, p.size)
        assertEquals("center", p[0].align)
        assertEquals("Title", p[0].text)
        assertEquals(32, p[0].runs[0].r.sizeHp)
        val b = p[1].runs.first { it.text == "bold" }
        assertEquals(true, b.r.bold)
        assertEquals(true, p[1].runs.first { it.text == "italic" }.r.italic)
        assertEquals(true, p[1].runs.first { it.text == "under" }.r.underline)
        assertEquals(0xFFFF0000.toInt(), p[1].runs.first { it.text == "red" }.r.color)
        val mono = p[1].runs.first { it.text == "mono" }
        assertEquals("Courier New", mono.r.font)
        assertEquals(20, mono.r.sizeHp)
        assertEquals("right", p[2].align)
        assertEquals(10f, p[2].beforePt, 0.01f)
        assertEquals(5f, p[2].afterPt, 0.01f)
        assertEquals("Right\nnext\ttabbed", p[2].text)
    }

    @Test
    fun japaneseShiftJisAndUnicode() {
        // 「日本語」を Shift_JIS(93 FA 96 7B 8C EA)と、Unicode 指定で書いた「テスト」(Kotlin の生文字列では \ と u が続くと文字に変わるので、@u と書いて置き換える)
        val d = doc("""{\rtf1\ansi\ansicpg932\deff0{\fonttbl{\f0\fnil\fcharset128 MS Gothic;}}\pard\f0 \'93\'fa\'96\'7b\'8c\'ea @u12486?@u12473?@u12488?\par}""".replace("@u", "\\u"))
        assertEquals("日本語 テスト", paras(d).single().text)
    }

    @Test
    fun escapesAndSkippedGroups() {
        val d = doc("""{\rtf1\ansi{\*\generator Test;}{\info{\title Hidden}}\pard a\{b\}c\\d \emdash x\~y\par}""")
        assertEquals("a{b}c\\d —x y", paras(d).single().text)
    }

    @Test
    fun tables() {
        val d = doc("""{\rtf1\ansi\trowd\cellx3000\cellx6000\intbl A1\cell B1\cell\row\trowd\cellx3000\cellx6000\intbl A2\cell B2\cell\row\pard After\par}""")
        val t = d.blocks.filterIsInstance<DocBlock.Table>().single()
        assertEquals(2, t.rows.size)
        assertEquals(2, t.rows[0].size)
        assertEquals("B2", (t.rows[1][1].blocks[0] as DocBlock.Para).text)
        assertEquals("After", paras(d).last().text)
    }

    @Test
    fun hyperlinkField() {
        val d = doc("""{\rtf1\ansi\pard {\field{\*\fldinst{HYPERLINK "https://example.com/"}}{\fldrslt{\ul link}}} end\par}""")
        val r = paras(d).single().runs.first { it.text.contains("link") }
        assertEquals("https://example.com/", r.link)
    }

    @Test
    fun notRtfFails() {
        try {
            Rtf.parse("hello".toByteArray())
            org.junit.Assert.fail()
        } catch (e: OpException) {
            assertNotNull(e.message)
        }
    }
}

/** 共有のテスト用データ(16_OpenDocumentとRTF)を、環境変数 FILER_TD のフォルダから読む。無ければ飛ばす。 */
class OdfSampleTest {
    private fun dir(): java.io.File? = System.getenv("FILER_TD")?.let { java.io.File(it, "16_OpenDocumentとRTF") }?.takeIf { it.isDirectory }

    private fun zipRead(f: java.io.File): (String) -> ByteArray? {
        val z = java.util.zip.ZipFile(f)
        return { name -> z.getEntry(name)?.let { e -> z.getInputStream(e).use { it.readBytes() } } }
    }

    @Test
    fun odtSample() {
        org.junit.Assume.assumeTrue(dir() != null)
        val doc = Odt.parse(zipRead(java.io.File(dir(), "事業報告書.odt")))
        assertEquals(2, doc.pages.size)
        val paras = doc.blocks.filterIsInstance<DocBlock.Para>()
        assertEquals(1, paras.count { it.heading == 0 && it.text.startsWith("2026年度 事業報告書") })
        assertTrue(paras.any { it.heading == 1 && it.text == "1. 概要" })
        assertTrue(paras.any { it.marker == "●" && it.text.startsWith("売上は") })
        assertTrue(paras.any { it.marker == "○" && it.text == "国内 40 社" })
        assertTrue(paras.any { it.marker == "■" && it.text == "アジア 6 社" })
        assertTrue(paras.any { it.marker == "a)" && it.text == "準備" })
        assertTrue(paras.any { it.marker == "I." })
        val table = doc.blocks.filterIsInstance<DocBlock.Table>().single()
        assertEquals(4, table.rows.size)
        assertEquals(3, table.rows[3][0].span)
        assertTrue(doc.blocks.any { it is DocBlock.Image && it.path == "Pictures/fig1.png" })
    }

    @Test
    fun odsSample() {
        org.junit.Assume.assumeTrue(dir() != null)
        val book = OdsBook(zipRead(java.io.File(dir(), "部門別売上.ods")))
        assertEquals(listOf("売上", "九九", "長い一覧"), book.sheetNames)
        val s = book.load(0)
        assertEquals("2026年度 部門別売上(単位: 万円)".replace("(", "（").replace(")", "）").replace(": ", ": "), s.rows[0][0])
        assertTrue(s.merges.any { it.toList() == listOf(0, 0, 0, 3) })
        assertEquals("海外事業部", s.rows[6][0])
        assertEquals("138%", s.rows[6][3])
        assertEquals(10, book.load(1).rows.size)
        val long = book.load(2)
        assertEquals(501, long.rows.size)
        assertEquals(1, long.frozenRows)
    }

    @Test
    fun odpSample() {
        org.junit.Assume.assumeTrue(dir() != null)
        val deck = Odp.parse(zipRead(java.io.File(dir(), "事業計画.odp")))
        assertEquals(5, deck.slides.size)
        assertTrue(deck.slides[4].hidden)
        assertTrue(deck.slides[2].shapes.count { it.geom?.prst == "line" } >= 4)
        assertTrue(deck.slides[3].shapes.any { it.table != null })
        assertTrue(deck.slides[3].shapes.any { it.picture != null })
    }

    @Test
    fun rtfSamples() {
        org.junit.Assume.assumeTrue(dir() != null)
        val a = Rtf.parse(java.io.File(dir(), "お知らせ_ShiftJIS.rtf").readBytes())
        val texts = a.blocks.filterIsInstance<DocBlock.Para>().map { it.text }
        assertTrue(texts.joinToString("|"), texts.any { it.startsWith("お知らせ（RTF 形式）") })
        assertTrue(texts.joinToString("|"), texts.any { it.contains("太字・斜体・下線・取り消し線・赤い太字・青・緑・蛍光ペン・大きな文字") })
        assertTrue(texts.any { it.contains("{braces}") && it.contains("backslash \\") })
        assertEquals(2, a.pages.size)
        val t = a.blocks.filterIsInstance<DocBlock.Table>().single()
        assertEquals(3, t.rows.size)
        assertEquals("開発部", (t.rows[2][0].blocks[0] as DocBlock.Para).text)
        val b = Rtf.parse(java.io.File(dir(), "Unicode_RTF.rtf").readBytes())
        val bt = b.blocks.filterIsInstance<DocBlock.Para>().map { it.text }
        assertTrue(bt.any { it.startsWith("日本語は") })
        assertTrue(b.blocks.filterIsInstance<DocBlock.Para>().any { p -> p.runs.any { it.link == "https://example.com/" } })
    }
}
