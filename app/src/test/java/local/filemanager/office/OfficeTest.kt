package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

private fun zipOf(vararg parts: Pair<String, String>): (String) -> ByteArray? {
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { z -> for ((n, t) in parts) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
    val f = File.createTempFile("office", ".zip")
    f.writeBytes(bos.toByteArray())
    f.deleteOnExit()
    val zf = ZipFile(f)
    return { name -> zf.getEntry(name)?.let { e -> zf.getInputStream(e).readBytes() } }
}

class XlsxTest {
    private val workbook = """<workbook xmlns:r="x"><workbookPr/><sheets><sheet name="売上" sheetId="1" r:id="rId1"/><sheet name="メモ" sheetId="2" r:id="rId2"/></sheets></workbook>"""
    private val rels = """<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Target="/xl/worksheets/sheet2.xml"/></Relationships>"""
    private val shared = """<sst><si><t>品名</t></si><si><r><t>りん</t></r><r><t>ご</t></r><rPh sb="0" eb="1"><t>ヨミ</t></rPh></si><si><t>数量</t></si></sst>"""
    private val styles = """<styleSheet><numFmts><numFmt numFmtId="164" formatCode="yyyy/m/d"/><numFmt numFmtId="165" formatCode="0.00"/></numFmts>
        <cellXfs><xf numFmtId="0"/><xf numFmtId="14"/><xf numFmtId="164"/><xf numFmtId="165"/></cellXfs></styleSheet>"""
    private val sheet1 = """<worksheet><sheetData>
        <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>2</v></c><c r="D1" t="inlineStr"><is><t>備考</t></is></c></row>
        <row r="2"><c r="A2" t="s"><v>1</v></c><c r="B2"><v>3</v></c><c r="C2" s="1"><v>46319</v></c><c r="D2" t="b"><v>1</v></c></row>
        <row r="4"><c r="A4"><v>0.30000000000000004</v></c><c r="B4" s="2"><v>46319.5</v></c><c r="C4" t="str"><f>A1&amp;"x"</f><v>結果</v></c><c r="D4" t="e"><v>#DIV/0!</v></c></row>
        <row r="6"><c r="A6" s="3"><v>1.5</v></c></row>
        </sheetData></worksheet>"""

    private fun file() = XlsxFile(zipOf(
        "xl/workbook.xml" to workbook, "xl/_rels/workbook.xml.rels" to rels, "xl/sharedStrings.xml" to shared,
        "xl/styles.xml" to styles, "xl/worksheets/sheet1.xml" to sheet1, "xl/worksheets/sheet2.xml" to "<worksheet><sheetData/></worksheet>",
    ))

    @Test
    fun シートの一覧() {
        val f = file()
        assertEquals(listOf("売上", "メモ"), f.sheets.map { it.name })
        assertEquals("xl/worksheets/sheet1.xml", f.sheets[0].path)
        assertEquals("xl/worksheets/sheet2.xml", f.sheets[1].path) // 「/」から始まる絶対の書き方
    }

    @Test
    fun セルの読み取り() {
        val s = file().load(0)
        assertEquals(listOf("品名", "数量", "", "備考"), s.rows[0])
        // ふりがな(rPh)は含めず、書式つきの文字は 1 つにつなぐ。日付の書式(14)は日付に
        assertEquals(listOf("りんご", "3", "2026/10/24", "TRUE"), s.rows[1])
        assertTrue(s.rows[2].isEmpty()) // 飛ばされた行は空
        assertEquals("0.3", s.rows[3][0]) // 有効数字 15 桁
        assertEquals("2026/10/24 12:00", s.rows[3][1]) // 独自の日付の書式
        assertEquals("結果", s.rows[3][2])
        assertEquals("#DIV/0!", s.rows[3][3])
        assertEquals("1.50", s.rows[5][0]) // 書式「0.00」
        assertEquals(4, s.columns)
        assertEquals(6, s.rows.size)
        assertFalse(s.truncated)
    }

    @Test
    fun 空のシートと行数の上限() {
        val f = file()
        assertTrue(f.load(1).rows.isEmpty())
        val big = XlsxFile(zipOf(
            "xl/workbook.xml" to workbook, "xl/_rels/workbook.xml.rels" to rels,
            "xl/worksheets/sheet1.xml" to "<worksheet><sheetData>" + (1..20).joinToString("") { """<row r="$it"><c r="A$it"><v>$it</v></c></row>""" } + "</sheetData></worksheet>",
        ))
        val s = big.load(0, maxRows = 5)
        assertEquals(5, s.rows.size)
        assertTrue(s.truncated)
    }

    @Test
    fun 読めないファイルは例外() {
        try {
            XlsxFile { null }
            org.junit.Assert.fail()
        } catch (e: OpException) { }
    }

    @Test
    fun 列の名前と番号() {
        assertEquals(0, XlsxFormat.columnIndex("A1"))
        assertEquals(2, XlsxFormat.columnIndex("C5"))
        assertEquals(26, XlsxFormat.columnIndex("AA10"))
        assertEquals("A", XlsxFormat.columnName(0))
        assertEquals("Z", XlsxFormat.columnName(25))
        assertEquals("AA", XlsxFormat.columnName(26))
        assertEquals("AZ", XlsxFormat.columnName(51))
        assertEquals("BA", XlsxFormat.columnName(52))
    }

    @Test
    fun 数と日付の整え方() {
        assertEquals("100", XlsxFormat.numberText("1E+2"))
        assertEquals("0.1", XlsxFormat.numberText("0.10000000000000001"))
        assertEquals("0", XlsxFormat.numberText("0.0"))
        assertEquals("-12.5", XlsxFormat.numberText("-12.50"))
        assertEquals("abc", XlsxFormat.numberText("abc"))
        assertEquals("1900/01/01", XlsxFormat.dateText("1", false))
        assertEquals("2026/10/24", XlsxFormat.dateText("46319", false))
        assertEquals("1900/03/01", XlsxFormat.dateText("61", false)) // Excel の 1900 年の数え方の誤りの境目
        assertEquals("1900/02/28", XlsxFormat.dateText("59", false))
        assertEquals("13:30:00", XlsxFormat.dateText("0.5625", false))
        assertEquals("1904/01/02", XlsxFormat.dateText("1", true))
        assertTrue(XlsxFormat.isDateFormat(14, null))
        assertTrue(XlsxFormat.isDateFormat(164, "yyyy\"年\"m\"月\"d\"日\""))
        assertTrue(XlsxFormat.isDateFormat(165, "[\$-411]ggge\"年\"m\"月\"d\"日\";@"))
        assertFalse(XlsxFormat.isDateFormat(165, "0.00"))
        assertFalse(XlsxFormat.isDateFormat(166, "#,##0\"円\""))
        assertFalse(XlsxFormat.isDateFormat(0, "General"))
    }

    /** 実物での確認用(環境変数 FILER_XLSX_SAMPLE)。個人情報のことがあるので、中身は出さず、数だけを出す。 */
    @Test
    fun 実物のxlsx() {
        val path = System.getenv("FILER_XLSX_SAMPLE")
        assumeTrue(path != null)
        val zf = ZipFile(File(path!!))
        val f = XlsxFile { name -> zf.getEntry(name)?.let { e -> zf.getInputStream(e).readBytes() } }
        for ((i, ref) in f.sheets.withIndex()) {
            val s = f.load(i)
            val cells = s.rows.sumOf { r -> r.count { it.isNotEmpty() } }
            println("xlsx sheet#${i + 1}: rows=${s.rows.size} cols=${s.columns} nonEmptyCells=$cells truncated=${s.truncated}")
        }
        assertTrue(f.sheets.isNotEmpty())
    }
}

class DocxTest {
    private val styles = """<w:styles>
        <w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Century" w:eastAsia="游明朝"/><w:sz w:val="21"/></w:rPr></w:rPrDefault>
          <w:pPrDefault><w:pPr><w:spacing w:after="160" w:line="259" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>
        <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
        <w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/>
          <w:pPr><w:spacing w:before="240" w:after="0"/><w:jc w:val="center"/></w:pPr>
          <w:rPr><w:rFonts w:eastAsia="ＭＳ ゴシック"/><w:b/><w:color w:val="2F5496"/><w:sz w:val="32"/></w:rPr></w:style>
        <w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Heading1"/><w:rPr><w:sz w:val="26"/></w:rPr></w:style>
        <w:style w:type="paragraph" w:styleId="ListParagraph"><w:name w:val="List Paragraph"/><w:basedOn w:val="Normal"/><w:pPr><w:ind w:left="840"/></w:pPr></w:style>
        <w:style w:type="character" w:styleId="Strong"><w:name w:val="Strong"/><w:rPr><w:b/><w:color w:val="C00000"/></w:rPr></w:style>
        <w:style w:type="table" w:styleId="TableGrid"><w:name w:val="Table Grid"/><w:tblPr><w:tblBorders><w:top w:val="single"/><w:left w:val="single"/></w:tblBorders></w:tblPr></w:style>
        <w:style w:type="table" w:styleId="Plain"><w:name w:val="Plain"/><w:tblPr><w:tblBorders><w:top w:val="nil"/></w:tblBorders></w:tblPr></w:style>
        </w:styles>"""

    private val numbering = """<w:numbering>
        <w:abstractNum w:abstractNumId="0"><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="&#xF0B7;"/><w:pPr><w:ind w:left="420" w:hanging="420"/></w:pPr></w:lvl>
          <w:lvl w:ilvl="1"><w:numFmt w:val="bullet"/><w:lvlText w:val="o"/><w:pPr><w:ind w:left="840" w:hanging="420"/></w:pPr></w:lvl></w:abstractNum>
        <w:abstractNum w:abstractNumId="1"><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/></w:lvl>
          <w:lvl w:ilvl="1"><w:start w:val="1"/><w:numFmt w:val="lowerLetter"/><w:lvlText w:val="%1-%2)"/></w:lvl></w:abstractNum>
        <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>
        <w:num w:numId="2"><w:abstractNumId w:val="1"/></w:num>
        <w:num w:numId="3"><w:abstractNumId w:val="1"/><w:lvlOverride w:ilvl="0"><w:startOverride w:val="1"/></w:lvlOverride></w:num>
        </w:numbering>"""

    private val rels = """<Relationships>
        <Relationship Id="rId5" Target="media/image1.png"/>
        <Relationship Id="rId6" Target="media/clip.emf"/>
        <Relationship Id="rId7" Target="https://example.com/page" TargetMode="External"/></Relationships>"""

    private fun read(body: String) = zipOf(
        "word/document.xml" to """<w:document xmlns:w="w"><w:body>$body</w:body></w:document>""",
        "word/styles.xml" to styles, "word/numbering.xml" to numbering, "word/_rels/document.xml.rels" to rels,
    )

    @Test
    fun スタイルの引き継ぎ_見出し() {
        val doc = Docx.parse(read("""<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>題名</w:t></w:r></w:p>
            <w:p><w:pPr><w:pStyle w:val="Heading2"/></w:pPr><w:r><w:t>小見出し</w:t></w:r></w:p>
            <w:p><w:r><w:t>本文</w:t></w:r></w:p>"""))
        val p = doc.blocks.filterIsInstance<DocBlock.Para>()
        assertEquals(1, p[0].heading)
        assertEquals("center", p[0].align)
        assertEquals(12f, p[0].beforePt) // 240 twip
        val r = p[0].runs.single().r
        assertEquals(true, r.bold)
        assertEquals(32, r.sizeHp)
        assertEquals(0xFF2F5496.toInt(), r.color)
        assertEquals("ＭＳ ゴシック", r.font) // 見出しの指定が、文書の既定より優先
        // 元のスタイルを引き継ぐ(Heading2 は、大きさだけ違う)
        assertEquals(2, p[1].heading)
        assertEquals(26, p[1].runs.single().r.sizeHp)
        assertEquals(true, p[1].runs.single().r.bold)
        assertEquals(0xFF2F5496.toInt(), p[1].runs.single().r.color)
        // 本文は、文書の既定(10.5pt・游明朝・行間と段落の後ろの余白)
        assertEquals(0, p[2].heading)
        assertEquals(21, p[2].runs.single().r.sizeHp)
        assertEquals("游明朝", p[2].runs.single().r.font)
        assertEquals(8f, p[2].afterPt, 0.01f)
        assertEquals(259 / 240f, p[2].lineMult, 0.01f)
        assertNull(p[2].runs.single().r.bold)
    }

    @Test
    fun 文字の飾り() {
        val doc = Docx.parse(read("""<w:p><w:r><w:t xml:space="preserve">普通の </w:t></w:r>
            <w:r><w:rPr><w:b/><w:i/><w:u w:val="single"/><w:strike/><w:sz w:val="28"/><w:color w:val="FF0000"/><w:highlight w:val="yellow"/></w:rPr><w:t>飾り</w:t></w:r>
            <w:r><w:rPr><w:rStyle w:val="Strong"/><w:b w:val="0"/></w:rPr><w:t>強調</w:t></w:r>
            <w:r><w:rPr><w:vertAlign w:val="superscript"/></w:rPr><w:t>2</w:t></w:r>
            <w:r><w:tab/><w:t>後</w:t><w:br/><w:t>次</w:t></w:r></w:p>"""))
        val runs = (doc.blocks.single() as DocBlock.Para).runs
        assertEquals("普通の 飾り強調2\t後\n次", runs.joinToString("") { it.text })
        val deco = runs.first { it.text == "飾り" }.r
        assertEquals(true, deco.bold); assertEquals(true, deco.italic); assertEquals(true, deco.underline); assertEquals(true, deco.strike)
        assertEquals(28, deco.sizeHp); assertEquals(0xFFFF0000.toInt(), deco.color); assertEquals(0xFFFFFF00.toInt(), deco.highlight)
        // 文字のスタイル(Strong)の色を引き継ぎつつ、直接の指定(w:b=0)が優先
        val strong = runs.first { it.text == "強調" }.r
        assertEquals(0xFFC00000.toInt(), strong.color)
        assertEquals(false, strong.bold)
        assertEquals(1, runs.first { it.text == "2" }.r.vert)
    }

    @Test
    fun 段落の書式() {
        val doc = Docx.parse(read("""<w:p><w:pPr><w:jc w:val="both"/><w:ind w:left="720" w:hanging="360"/><w:spacing w:before="100" w:after="40" w:line="360" w:lineRule="auto"/><w:shd w:val="clear" w:fill="FFF2CC"/>
            <w:tabs><w:tab w:val="left" w:pos="720"/></w:tabs></w:pPr><w:r><w:t>字下げ</w:t></w:r></w:p>
            <w:p><w:pPr><w:ind w:firstLine="210"/><w:spacing w:line="300" w:lineRule="exact"/></w:pPr><w:r><w:t>最初の行</w:t></w:r></w:p>"""))
        val p = doc.blocks.filterIsInstance<DocBlock.Para>()
        assertEquals("both", p[0].align)
        assertEquals(36f, p[0].leftPt); assertEquals(18f, p[0].hangingPt)
        assertEquals(5f, p[0].beforePt); assertEquals(2f, p[0].afterPt)
        assertEquals(1.5f, p[0].lineMult, 0.001f)
        assertEquals(0xFFFFF2CC.toInt(), p[0].fill)
        // タブ位置の定義(w:tabs/w:tab)は、文字にならない
        assertEquals("字下げ", p[0].text)
        assertEquals(10.5f, p[1].firstLinePt)
        // 「固定」の行間は読まない(標準の既定になる)
        assertEquals(259 / 240f, p[1].lineMult, 0.01f)
    }

    @Test
    fun 箇条書きと番号() {
        val doc = Docx.parse(read("""
            <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>点1</w:t></w:r></w:p>
            <w:p><w:pPr><w:numPr><w:ilvl w:val="1"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>点1-1</w:t></w:r></w:p>
            <w:p><w:pPr><w:numPr><w:numId w:val="2"/></w:numPr></w:pPr><w:r><w:t>一</w:t></w:r></w:p>
            <w:p><w:pPr><w:numPr><w:numId w:val="2"/></w:numPr></w:pPr><w:r><w:t>二</w:t></w:r></w:p>
            <w:p><w:pPr><w:numPr><w:ilvl w:val="1"/><w:numId w:val="2"/></w:numPr></w:pPr><w:r><w:t>二の一</w:t></w:r></w:p>
            <w:p><w:pPr><w:numPr><w:ilvl w:val="1"/><w:numId w:val="2"/></w:numPr></w:pPr><w:r><w:t>二の二</w:t></w:r></w:p>
            <w:p><w:pPr><w:numPr><w:numId w:val="2"/></w:numPr></w:pPr><w:r><w:t>三</w:t></w:r></w:p>
            <w:p><w:pPr><w:numPr><w:ilvl w:val="1"/><w:numId w:val="2"/></w:numPr></w:pPr><w:r><w:t>三の一</w:t></w:r></w:p>
            <w:p><w:pPr><w:numPr><w:numId w:val="3"/></w:numPr></w:pPr><w:r><w:t>別の番号</w:t></w:r></w:p>"""))
        val p = doc.blocks.filterIsInstance<DocBlock.Para>()
        assertEquals(listOf("•", "◦", "1.", "2.", "2-a)", "2-b)", "3.", "3-a)", "1."), p.map { it.marker })
        // 番号の段の字下げが使われる(本文 420 twip・ぶら下げ 420 twip → 21pt)
        assertEquals(21f, p[0].leftPt); assertEquals(21f, p[0].hangingPt)
        assertEquals(42f, p[1].leftPt)
    }

    @Test
    fun 番号の段の字下げは_段落のスタイルより優先() {
        val doc = Docx.parse(read("""
            <w:p><w:pPr><w:pStyle w:val="ListParagraph"/><w:numPr><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>スタイルつき</w:t></w:r></w:p>
            <w:p><w:pPr><w:pStyle w:val="ListParagraph"/><w:numPr><w:numId w:val="1"/></w:numPr><w:ind w:left="1000"/></w:pPr><w:r><w:t>直接指定</w:t></w:r></w:p>
            <w:p><w:pPr><w:pStyle w:val="ListParagraph"/></w:pPr><w:r><w:t>番号なし</w:t></w:r></w:p>"""))
        val p = doc.blocks.filterIsInstance<DocBlock.Para>()
        assertEquals(21f, p[0].leftPt) // 番号の段(420)。スタイルの 840 ではない
        assertEquals(50f, p[1].leftPt) // 段落自身の指定が最優先
        assertEquals(42f, p[2].leftPt) // 番号が無ければ、スタイルの字下げ
    }

    @Test
    fun 番号の書き方() {
        assertEquals("iv", DocNumbering.format(4, "lowerRoman"))
        assertEquals("XII", DocNumbering.format(12, "upperRoman"))
        assertEquals("c", DocNumbering.format(3, "lowerLetter"))
        assertEquals("AA", DocNumbering.format(27, "upperLetter"))
        assertEquals("三", DocNumbering.format(3, "japaneseCounting"))
        assertEquals("③", DocNumbering.format(3, "decimalEnclosedCircle"))
        assertEquals("05", DocNumbering.format(5, "decimalZero"))
        assertEquals("•", DocNumbering.bulletChar("\uF0B7"))
        assertEquals("◦", DocNumbering.bulletChar("o"))
        assertEquals("●", DocNumbering.bulletChar("●"))
    }

    @Test
    fun 表_列の幅_罫線_結合_色() {
        val doc = Docx.parse(read("""<w:tbl><w:tblPr><w:tblStyle w:val="TableGrid"/></w:tblPr><w:tblGrid><w:gridCol w:w="2000"/><w:gridCol w:w="3000"/><w:gridCol w:w="1000"/></w:tblGrid>
            <w:tr><w:tc><w:tcPr><w:gridSpan w:val="2"/><w:shd w:fill="D9E2F3"/></w:tcPr><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>見出し</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>C1</w:t></w:r></w:p></w:tc></w:tr>
            <w:tr><w:tc><w:p><w:r><w:t>A2</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>B2上</w:t></w:r></w:p><w:p><w:r><w:t>B2下</w:t></w:r></w:p></w:tc><w:tc><w:p/></w:tc></w:tr></w:tbl>
            <w:tbl><w:tblPr><w:tblStyle w:val="Plain"/></w:tblPr><w:tblGrid><w:gridCol w:w="1000"/></w:tblGrid><w:tr><w:tc><w:p><w:r><w:t>罫線なし</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"""))
        val tables = doc.blocks.filterIsInstance<DocBlock.Table>()
        val t = tables[0]
        assertEquals(listOf(2000, 3000, 1000), t.weights)
        assertTrue(t.borders)
        assertEquals(2, t.rows.size)
        assertEquals(2, t.rows[0][0].span)
        assertEquals(0xFFD9E2F3.toInt(), t.rows[0][0].fill)
        assertEquals("見出し", (t.rows[0][0].blocks.single() as DocBlock.Para).text)
        assertEquals(listOf("B2上", "B2下"), t.rows[1][1].blocks.filterIsInstance<DocBlock.Para>().map { it.text })
        assertFalse(tables[1].borders)
    }

    @Test
    fun 画像_ページ区切り_リンク() {
        val doc = Docx.parse(read("""<w:p><w:r><w:t>1ページ目</w:t></w:r><w:r><w:drawing><wp:inline><wp:extent cx="2540000" cy="1270000"/><a:graphic><a:blip r:embed="rId5"/></a:graphic></wp:inline></w:drawing></w:r>
            <w:r><w:drawing><wp:extent cx="100" cy="100"/><a:blip r:embed="rId6"/></w:drawing></w:r></w:p>
            <w:p><w:r><w:br w:type="page"/></w:r></w:p>
            <w:p><w:hyperlink r:id="rId7"><w:r><w:t>リンク</w:t></w:r></w:hyperlink><w:r><w:t>の外</w:t></w:r></w:p>
            <w:p><w:pPr><w:pageBreakBefore/></w:pPr><w:r><w:t>3ページ目</w:t></w:r></w:p>"""))
        assertEquals(3, doc.pages.size)
        val img = doc.pages[0].filterIsInstance<DocBlock.Image>().single() // .emf は出さない
        assertEquals("word/media/image1.png", img.path)
        assertEquals(200f, img.widthPt)
        assertEquals(100f, img.heightPt)
        val p2 = doc.pages[1].filterIsInstance<DocBlock.Para>().first { it.text.isNotEmpty() }
        assertEquals("https://example.com/page", p2.runs[0].link)
        assertNull(p2.runs[1].link)
        assertEquals("3ページ目", (doc.pages[2].first() as DocBlock.Para).text)
    }

    @Test
    fun 図の中の文字は二重にならず_終わりの空の段落は落とす() {
        val doc = Docx.parse(read("""<w:p><w:r><w:t>本文</w:t></w:r></w:p>
            <mc:AlternateContent><mc:Choice><w:p><w:r><w:t>図の文字</w:t></w:r></w:p></mc:Choice><mc:Fallback><w:p><w:r><w:t>図の文字</w:t></w:r></w:p></mc:Fallback></mc:AlternateContent>
            <w:p/><w:p><w:r><w:t>間の後</w:t></w:r></w:p><w:p/><w:p/>"""))
        val texts = doc.blocks.filterIsInstance<DocBlock.Para>().map { it.text }
        assertEquals(listOf("本文", "図の文字", "", "間の後"), texts)
    }

    @Test
    fun 見出しの書式名() {
        assertEquals(1, Docx.headingLevel("Heading1"))
        assertEquals(3, Docx.headingLevel("heading 3"))
        assertEquals(2, Docx.headingLevel("見出し 2"))
        assertEquals(1, Docx.headingLevel("Title"))
        assertEquals(0, Docx.headingLevel("Normal"))
        assertEquals(0, Docx.headingLevel(null))
    }

    @Test
    fun フォントの系統() {
        assertEquals(androidx.compose.ui.text.font.FontFamily.Serif, fontFamilyOf("游明朝"))
        assertEquals(androidx.compose.ui.text.font.FontFamily.Serif, fontFamilyOf("MS P明朝"))
        assertEquals(androidx.compose.ui.text.font.FontFamily.Serif, fontFamilyOf("Times New Roman"))
        assertEquals(androidx.compose.ui.text.font.FontFamily.Monospace, fontFamilyOf("Consolas"))
        assertEquals(androidx.compose.ui.text.font.FontFamily.SansSerif, fontFamilyOf("メイリオ"))
        assertEquals(androidx.compose.ui.text.font.FontFamily.SansSerif, fontFamilyOf("Arial"))
        assertEquals(androidx.compose.ui.text.font.FontFamily.SansSerif, fontFamilyOf("ＭＳ Ｐゴシック"))
        assertNull(fontFamilyOf(null))
    }

    @Test
    fun 読めなければ例外() {
        try {
            Docx.parse { null }
            org.junit.Assert.fail()
        } catch (e: OpException) { }
    }

    @Test
    fun スタイルの無い文書でも読める() {
        val doc = Docx.parse(zipOf("word/document.xml" to """<w:document><w:body><w:p><w:r><w:t>素朴な文書</w:t></w:r></w:p></w:body></w:document>"""))
        assertEquals("素朴な文書", (doc.blocks.single() as DocBlock.Para).text)
    }
}
class PptxTest {
    private val pres = """<p:presentation xmlns:p="p" xmlns:r="r"><p:sldIdLst><p:sldId id="256" r:id="rId2"/><p:sldId id="257" r:id="rId3"/></p:sldIdLst></p:presentation>"""
    private val rels = """<Relationships><Relationship Id="rId2" Target="slides/slide2.xml"/><Relationship Id="rId3" Target="slides/slide1.xml"/></Relationships>"""
    private val slide1 = """<p:sld xmlns:p="p" xmlns:a="a"><p:cSld><p:spTree>
        <p:sp><p:nvSpPr><p:nvPr><p:ph type="ctrTitle"/></p:nvPr></p:nvSpPr><p:txBody><a:p><a:r><a:t>発表の</a:t></a:r><a:r><a:t>題名</a:t></a:r></a:p></p:txBody></p:sp>
        <p:sp><p:txBody><a:p><a:r><a:t>要点1</a:t></a:r></a:p><a:p><a:r><a:t>要点2</a:t></a:r><a:br/><a:r><a:t>続き</a:t></a:r></a:p></p:txBody></p:sp>
        </p:spTree></p:cSld></p:sld>"""
    private val slide2 = """<p:sld xmlns:p="p" xmlns:a="a"><p:cSld><p:spTree>
        <p:graphicFrame><a:graphic><a:tbl><a:tr><a:tc><a:txBody><a:p><a:r><a:t>名</a:t></a:r></a:p></a:txBody></a:tc><a:tc><a:txBody><a:p><a:r><a:t>値</a:t></a:r></a:p></a:txBody></a:tc></a:tr></a:tbl></a:graphic></p:graphicFrame>
        </p:spTree></p:cSld></p:sld>"""

    @Test
    fun スライドの順番と文字() {
        val slides = Pptx.parse(zipOf(
            "ppt/presentation.xml" to pres, "ppt/_rels/presentation.xml.rels" to rels,
            "ppt/slides/slide1.xml" to slide1, "ppt/slides/slide2.xml" to slide2,
        ))
        assertEquals(2, slides.size)
        // 並びは presentation.xml の順(ファイル名の順ではない)
        assertEquals(listOf("名 | 値"), slides[0].lines)
        assertNull(slides[0].title)
        assertEquals("発表の題名", slides[1].title)
        assertEquals(listOf("要点1", "要点2\n続き"), slides[1].lines)
        assertEquals(1, slides[0].number)
    }

    @Test
    fun 読めなければ例外() {
        try {
            Pptx.parse { null }
            org.junit.Assert.fail()
        } catch (e: OpException) { }
    }

    @Test
    fun 見せ方の種類_Office() {
        assertEquals(PreviewKind.XLSX, PreviewKind.of("a.XLSX"))
        assertEquals(PreviewKind.DOCX, PreviewKind.of("a.docx"))
        assertEquals(PreviewKind.PPTX, PreviewKind.of("a.pptx"))
        assertEquals(PreviewKind.XLS, PreviewKind.of("a.xls"))
        assertEquals(PreviewKind.ODS, PreviewKind.of("a.ods"))
        assertEquals(PreviewKind.PPT, PreviewKind.of("a.ppt"))
    }
}
class XlsxNumberFormatTest {
    private fun f(v: String, code: String) = XlsxFormat.formatNumber(v, code)

    @Test
    fun 桁区切りと小数() {
        assertEquals("1,234,568", f("1234567.891", "#,##0"))
        assertEquals("3.14", f("3.14159", "0.00"))
        assertEquals("0.50", f("0.5", "#,##0.00"))
        assertEquals("1,234.50", f("1234.5", "#,##0.00"))
        assertEquals("007", f("7", "000"))
        assertEquals("2", f("2.0", "0.##"))
        assertEquals("2.5", f("2.5", "0.##"))
        assertEquals("3", f("2.5", "0")) // 四捨五入(Excel と同じ、0.5 は切り上げ)
        assertEquals("0", f("0", "#,##0"))
    }

    @Test
    fun パーセント() {
        assertEquals("26%", f("0.256", "0%"))
        assertEquals("2.6%", f("0.0256", "0.0%"))
        assertEquals("100.00%", f("1", "0.00%"))
        assertEquals("-12%", f("-0.12", "0%"))
    }

    @Test
    fun 通貨と文字つき() {
        assertEquals("¥1,500", f("1500", "\"¥\"#,##0"))
        assertEquals("1,500円", f("1500", "#,##0\"円\""))
        assertEquals("¥1,500", f("1500", "[\$¥-411]#,##0"))
        assertEquals("-¥1,500", f("-1500", "\"¥\"#,##0"))
        assertEquals("\$1,234.50", f("1234.5", "\$#,##0.00"))
        assertEquals("5 個", f("5", "0 \"個\""))
        assertEquals("No.12", f("12", "\"No.\"0"))
    }

    @Test
    fun 負の数とゼロの部分() {
        assertEquals("(1,500)", f("-1500", "#,##0;(#,##0)"))
        assertEquals("1,500", f("1500", "#,##0;(#,##0)"))
        assertEquals("-1,500", f("-1500", "#,##0;[Red]-#,##0"))
        assertEquals("-", f("0", "#,##0;-#,##0;\"-\""))
        assertEquals("▲1,500", f("-1500", "#,##0;\"▲\"#,##0"))
    }

    @Test
    fun 指数() {
        assertEquals("1.23E+04", f("12345", "0.00E+00"))
        assertEquals("1.00E-03", f("0.001", "0.00E+00"))
        assertEquals("0.00E+00", f("0", "0.00E+00"))
    }

    @Test
    fun 標準や文字の書式は_そのまま() {
        assertEquals("0.3", f("0.30000000000000004", "General"))
        assertEquals("12.5", f("12.5", ""))
        assertEquals("abc", f("abc", "0.00"))
        assertEquals("12.5", f("12.5", "@"))
    }

    @Test
    fun 区切りの分け方() {
        assertEquals(listOf("#,##0", "(#,##0)"), XlsxFormat.splitSections("#,##0;(#,##0)"))
        assertEquals(listOf("\"a;b\"0", "0"), XlsxFormat.splitSections("\"a;b\"0;0"))
        assertEquals(listOf("[\$-411]", "0"), XlsxFormat.splitSections("[\$-411];0"))
    }

    @Test
    fun 組み込みの書式の番号() {
        assertEquals("#,##0", XlsxFormat.builtinCode(3))
        assertEquals("0%", XlsxFormat.builtinCode(9))
        assertEquals(null, XlsxFormat.builtinCode(0))
    }
}
class OfficeSampleTest {
    /** 見本のファイル(環境変数 FILER_DOCX_SAMPLE / FILER_XLSX_SAMPLE)で、読み取りの結果を出す確認用。 */
    @Test
    fun 見本のWord() {
        val path = System.getenv("FILER_DOCX_SAMPLE")
        assumeTrue(path != null)
        val zf = ZipFile(File(path!!))
        val doc = Docx.parse { name -> zf.getEntry(name)?.let { e -> zf.getInputStream(e).readBytes() } }
        val all = doc.blocks
        println("docx pages=${doc.pages.size} blocks=${all.size} tables=${all.count { it is DocBlock.Table }} images=${all.count { it is DocBlock.Image }}")
        for ((pi, page) in doc.pages.withIndex()) {
            println("-- page ${pi + 1}")
            for (b in page) when (b) {
                is DocBlock.Para -> {
                    val r = b.runs.firstOrNull()?.r
                    println("  P h=${b.heading} al=${b.align} L=${b.leftPt} H=${b.hangingPt} F=${b.firstLinePt} marker=${b.marker} sz=${r?.sizeHp} b=${r?.bold} col=${r?.color?.toUInt()?.toString(16)} font=${r?.font} | ${b.text.take(36).replace("\t", "<TAB>").replace("\n", "<BR>")}")
                }
                is DocBlock.Table -> println("  TABLE rows=${b.rows.size} weights=${b.weights} borders=${b.borders} spans=${b.rows.map { r -> r.map { it.span } }}")
                is DocBlock.Image -> println("  IMAGE ${b.path} ${b.widthPt}x${b.heightPt}pt")
            }
        }
        val links = all.filterIsInstance<DocBlock.Para>().flatMap { it.runs }.filter { it.link != null }.map { it.link }
        println("links=$links")
        val img = all.filterIsInstance<DocBlock.Image>().first()
        val bytes = zf.getInputStream(zf.getEntry(img.path)).readBytes()
        println("image bytes=${bytes.size} png=${bytes[1].toInt().toChar()}${bytes[2].toInt().toChar()}${bytes[3].toInt().toChar()}")
        assertTrue(doc.pages.size == 3)
    }

    @Test
    fun 見本のExcel() {
        val path = System.getenv("FILER_XLSX_SAMPLE2")
        assumeTrue(path != null)
        val zf = ZipFile(File(path!!))
        val f = XlsxFile { name -> zf.getEntry(name)?.let { e -> zf.getInputStream(e).readBytes() } }
        println("xlsx sheets=${f.sheets.map { it.name }} styles=${f.styles.size}")
        run {
            val s0 = f.load(0)
            val m = XlsxGridModel(s0, f)
            fun d(r: Int, c: Int) = m.cell(r, c)?.let { "${it.text.take(10)} fill=${it.fill?.toUInt()?.toString(16)} color=${it.color?.toUInt()?.toString(16)} bold=${it.bold} al=${it.align} border=${it.border}" }
            println("grid frozen=${m.frozenRows} merges=${s0.merges.map { it.toList() }} widths=${s0.colWidths}")
            println("  title: ${d(0, 0)} span=${m.span(0, 0)}")
            println("  header: ${d(1, 0)}")
            println("  num: ${d(2, 4)}")
            println("  total: ${d(22, 4)}")
        }
        for (i in f.sheets.indices) {
            val s = f.load(i)
            println("-- sheet ${s.name}: rows=${s.rows.size} cols=${s.columns} truncated=${s.truncated}")
            val show = if (i == 2) 4 else 40
            for ((ri, row) in s.rows.take(show).withIndex()) println("  ${ri + 1}: " + row.joinToString(" | ") { it.replace("\n", "<LF>").take(28) })
            if (i == 0) println("  ...: " + s.rows.last().joinToString(" | "))
        }
        assertEquals(4, f.sheets.size)
    }
}
class XlsxStyleTest {
    private val theme = """<a:theme><a:themeElements><a:clrScheme name="Office">
        <a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1>
        <a:dk2><a:srgbClr val="44546A"/></a:dk2><a:lt2><a:srgbClr val="E7E6E6"/></a:lt2>
        <a:accent1><a:srgbClr val="4472C4"/></a:accent1><a:accent2><a:srgbClr val="ED7D31"/></a:accent2><a:accent3><a:srgbClr val="A5A5A5"/></a:accent3>
        <a:accent4><a:srgbClr val="FFC000"/></a:accent4><a:accent5><a:srgbClr val="5B9BD5"/></a:accent5><a:accent6><a:srgbClr val="70AD47"/></a:accent6>
        <a:hlink><a:srgbClr val="0563C1"/></a:hlink><a:folHlink><a:srgbClr val="954F72"/></a:folHlink></a:clrScheme></a:themeElements></a:theme>"""
    private val styles = """<styleSheet>
        <numFmts count="1"><numFmt numFmtId="164" formatCode="#,##0&quot;円&quot;"/></numFmts>
        <fonts count="3"><font><sz val="11"/></font><font><b/><sz val="12"/><color rgb="FFFFFFFF"/></font><font><i/><sz val="10"/><color theme="4" tint="-0.25"/></font></fonts>
        <fills count="4"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>
          <fill><patternFill patternType="solid"><fgColor rgb="FF217346"/></patternFill></fill>
          <fill><patternFill patternType="solid"><fgColor theme="4" tint="0.7999816888943144"/></patternFill></fill></fills>
        <borders count="2"><border><left/><right/><top/><bottom/></border><border><left style="thin"><color auto="1"/></left><right/><top/><bottom/></border></borders>
        <cellXfs count="4">
          <xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
          <xf numFmtId="0" fontId="1" fillId="2" borderId="1"><alignment horizontal="center"/></xf>
          <xf numFmtId="164" fontId="2" fillId="3" borderId="0"><alignment horizontal="right" wrapText="1"/></xf>
          <xf numFmtId="0" fontId="0" fillId="0" borderId="0"><alignment horizontal="centerContinuous"/></xf>
        </cellXfs></styleSheet>"""
    private val sheet = """<worksheet><sheetViews><sheetView><pane ySplit="2" topLeftCell="A3" state="frozen"/></sheetView></sheetViews>
        <cols><col min="1" max="1" width="20"/><col min="3" max="4" width="12.5"/><col min="5" max="5" width="9" hidden="1"/></cols>
        <sheetData>
        <row r="1"><c r="A1" s="1" t="inlineStr"><is><t>題名</t></is></c><c r="B1" s="1"/></row>
        <row r="2"><c r="A2" t="inlineStr"><is><t>文字</t></is></c><c r="B2"><v>1500</v></c><c r="C2" s="2"><v>1500</v></c><c r="D2" t="b"><v>1</v></c><c r="E2" t="e"><v>#N/A</v></c></row>
        </sheetData><mergeCells count="1"><mergeCell ref="A1:C1"/></mergeCells></worksheet>"""

    private fun file() = XlsxFile(zipOf(
        "xl/workbook.xml" to """<workbook xmlns:r="x"><sheets><sheet name="S" r:id="rId1"/></sheets></workbook>""",
        "xl/_rels/workbook.xml.rels" to """<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>""",
        "xl/styles.xml" to styles, "xl/theme/theme1.xml" to theme, "xl/worksheets/sheet1.xml" to sheet,
    ))

    @Test
    fun セルの書式を読む() {
        val st = file().styles
        assertEquals(4, st.size)
        assertEquals(true, st[1].bold); assertEquals(0xFFFFFFFF.toInt(), st[1].color); assertEquals(0xFF217346.toInt(), st[1].fill)
        assertEquals(true, st[1].border); assertEquals("center", st[1].halign); assertEquals(12f, st[1].sizePt)
        assertEquals(true, st[2].italic); assertEquals("right", st[2].halign); assertEquals(true, st[2].wrap)
        assertEquals("#,##0\"円\"", st[2].code)
        assertNull(st[0].fill); assertEquals(false, st[0].bold); assertNull(st[0].halign)
        assertNull(st[3].halign) // 「選択範囲内で中央」などは、標準扱い
    }

    @Test
    fun テーマの色と濃淡() {
        val st = file().styles
        // 塗り: テーマのアクセント1(4472C4)を 80% 明るく ≒ D9E2F3(Excel の「青、アクセント1、白+基本色80%」)
        val fill = st[2].fill!!
        assertTrue(Math.abs(((fill shr 16) and 0xFF) - 0xD9) <= 3)
        assertTrue(Math.abs(((fill shr 8) and 0xFF) - 0xE2) <= 3)
        assertTrue(Math.abs((fill and 0xFF) - 0xF3) <= 3)
        // 文字: アクセント1を 25% 暗く ≒ 2F5597
        val c = st[2].color!!
        assertTrue(Math.abs(((c shr 16) and 0xFF) - 0x2F) <= 4)
        assertTrue(Math.abs((c and 0xFF) - 0x97) <= 4)
    }

    @Test
    fun 濃淡の計算() {
        assertEquals(0xFF4472C4.toInt(), XlsxColors.tint(0xFF4472C4.toInt(), 0.0))
        assertEquals(0xFFFFFFFF.toInt(), XlsxColors.tint(0xFFFFFFFF.toInt(), 0.5))
        assertEquals(0xFF000000.toInt(), XlsxColors.tint(0xFF000000.toInt(), -0.5))
        assertEquals(0xFF808080.toInt(), XlsxColors.tint(0xFF000000.toInt(), 0.5)) // 黒を 50% 明るく
        assertEquals(0xFF000000.toInt(), XlsxColors.indexed(0))
        assertNull(XlsxColors.indexed(64))
    }

    @Test
    fun シートの列の幅_結合_固定行_種類() {
        val f = file()
        val s = f.load(0)
        assertEquals(20f, s.colWidths[0])
        assertEquals(null, s.colWidths[1])
        assertEquals(12.5f, s.colWidths[2]); assertEquals(12.5f, s.colWidths[3])
        assertEquals(null, s.colWidths[4]) // 非表示の列
        assertEquals(1, s.merges.size)
        assertEquals(listOf(0, 0, 0, 2), s.merges[0].toList())
        assertEquals(2, s.frozenRows)
        assertEquals(1, s.styleAt(0, 0)); assertEquals(1, s.styleAt(0, 1)) // 文字の無い書式つきのセルも残る
        assertEquals(2, s.styleAt(1, 2))
        assertEquals(XlsxSheet.KIND_TEXT, s.kindAt(1, 0))
        assertEquals(XlsxSheet.KIND_NUMBER, s.kindAt(1, 1))
        assertEquals(XlsxSheet.KIND_BOOL_ERROR, s.kindAt(1, 3))
        assertEquals(XlsxSheet.KIND_BOOL_ERROR, s.kindAt(1, 4))
        assertEquals("1,500円", s.rows[1][2]) // 書式「#,##0"円"」
        assertEquals("TRUE", s.rows[1][3])
    }

    @Test
    fun 表の中身_配置と結合() {
        val f = file()
        val s = f.load(0)
        val m = XlsxGridModel(s, f)
        assertEquals(2, m.rowCount); assertEquals(5, m.colCount); assertEquals(2, m.frozenRows)
        // 結合 A1:C1 → A1 が 3 列分、B1・C1 は出さない
        assertEquals(3, m.span(0, 0)); assertEquals(0, m.span(0, 1)); assertEquals(0, m.span(0, 2)); assertEquals(1, m.span(0, 3))
        val title = m.cell(0, 0)!!
        assertEquals("題名", title.text); assertEquals(true, title.bold); assertEquals(0xFF217346.toInt(), title.fill); assertEquals(0, title.align)
        // 標準の配置: 文字は左、数は右、真偽値・エラーは中央
        assertEquals(-1, m.cell(1, 0)!!.align); assertEquals(1, m.cell(1, 1)!!.align)
        assertEquals(0, m.cell(1, 3)!!.align); assertEquals(0, m.cell(1, 4)!!.align)
        assertEquals(1, m.cell(1, 2)!!.align) // 書式で右と指定
        assertEquals(true, m.cell(1, 2)!!.wrap)
        assertNull(m.cell(1, 9)) // 何も無いセル
        assertTrue(m.colWidth(0) > m.colWidth(1)) // 列の幅(20 と標準 8.43)
    }

    @Test
    fun CSVの表_見出しと配置() {
        val table = Csv.parse("名前,金額,率\n山田,\"1,500\",12.5%\n佐藤,-30,\n", ',')
        val withHead = CsvGridModel(table, true)
        assertEquals(1, withHead.frozenRows)
        val h = withHead.cell(0, 0)!!
        assertEquals(true, h.bold); assertNotNull(h.fill)
        assertEquals(-1, withHead.cell(1, 0)!!.align) // 文字は左
        assertEquals(1, withHead.cell(1, 1)!!.align) // 数(桁区切りつき)は右
        assertEquals(1, withHead.cell(1, 2)!!.align) // パーセントも右
        assertEquals(1, withHead.cell(2, 1)!!.align) // 負の数も右
        assertNull(withHead.cell(2, 2)) // 空のセル
        val noHead = CsvGridModel(table, false)
        assertEquals(0, noHead.frozenRows)
        assertEquals(false, noHead.cell(0, 0)!!.bold)
        assertEquals(1, withHead.span(0, 0))
    }

    @Test
    fun 行の番号と列の名前() {
        assertEquals(0, XlsxFormat.rowIndex("A1"))
        assertEquals(4, XlsxFormat.rowIndex("C5"))
        assertEquals(9, XlsxFormat.rowIndex("AB10"))
    }
}
class PptxSampleTest {
    @Test
    fun 見本のPowerPoint() {
        val path = System.getenv("FILER_PPTX_SAMPLE")
        assumeTrue(path != null)
        val zf = ZipFile(File(path!!))
        val slides = Pptx.parse { name -> zf.getEntry(name)?.let { e -> zf.getInputStream(e).readBytes() } }
        println("pptx slides=${slides.size}")
        for (s in slides) {
            println("-- slide ${s.number}: title=${s.title}")
            for (l in s.lines) println("     ${l.replace("\n", "<BR>")}")
        }
        assertEquals(6, slides.size)
    }
}
