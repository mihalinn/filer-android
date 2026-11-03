package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun res(name: String): ByteArray = object {}.javaClass.getResourceAsStream("/legacy/$name")!!.use { it.readBytes() }

private fun texts(blocks: List<DocBlock>): List<String> = blocks.flatMap { b ->
    when (b) {
        is DocBlock.Para -> listOf(b.text)
        is DocBlock.Table -> b.rows.flatMap { r -> r.flatMap { c -> texts(c.blocks) } }
        else -> emptyList()
    }
}

class Ole2Test {
    @Test
    fun readsStreams() {
        val ole = Ole2(res("report.doc"))
        assertTrue(ole.has("WordDocument"))
        assertTrue(ole.has("1Table") || ole.has("0Table"))
        assertTrue(ole.stream("WordDocument")!!.size > 1000)
        assertNull(ole.stream("NoSuchStream"))
    }

    @Test
    fun smallStreamsUseTheMiniStream() {
        val ole = Ole2(res("sales.xls"))
        // 4096 バイト未満のストリーム(文書の情報など)もミニストリームから読める
        val small = ole.entries.filter { it.type == 2 && it.size in 1..4095 }
        assertTrue(small.isNotEmpty())
        for (e in small) assertEquals(e.size.toInt(), ole.stream(e.name)!!.size)
    }

    @Test
    fun rejectsOtherFiles() {
        assertFalse(Ole2.isOle2("hello world".toByteArray()))
        assertFalse(Ole2.isOle2(ByteArray(1000)))
        try {
            Ole2("PK".toByteArray() + ByteArray(600))
            org.junit.Assert.fail()
        } catch (e: OpException) {
            assertNotNull(e.message)
        }
    }
}

class XlsTest {
    @Test
    fun readsSheetsValuesAndFormats() {
        val book = XlsBook.open(res("sales.xls"))
        assertEquals(listOf("売上", "九九", "長い一覧"), book.sheetNames)
        val s = book.load(0)
        assertEquals("2026年度 部門別売上（単位: 万円）", s.rows[0][0])
        assertTrue(s.merges.any { it.toList() == listOf(0, 0, 0, 3) })
        assertEquals(listOf("部門", "上期", "下期", "前年比"), s.rows[1])
        assertEquals("海外事業部", s.rows[6][0])
        assertEquals("1010", s.rows[6][2])
        assertTrue(s.rows[6][3], s.rows[6][3].startsWith("138"))
        assertTrue(s.rows[6][3].endsWith("%"))
        assertEquals(XlsxSheet.KIND_NUMBER, s.kindAt(6, 2))
        assertEquals(XlsxSheet.KIND_TEXT, s.kindAt(6, 0))
        // 題名の行: 14pt の太字・色つきの文字・背景色
        val head = book.styles[s.styleAt(0, 0)]
        assertTrue(head.bold)
        assertEquals(14f, head.sizePt!!, 0.01f)
        assertNotNull(head.fill)
        assertNotNull(head.color)
        assertTrue(book.styles[s.styleAt(1, 0)].border)
        assertTrue(s.colWidths.getValue(0) > 10f)
        // 日付(セルの形式から)と真偽値
        val last = s.rows[13]
        assertEquals("TRUE", last[1])
        assertEquals("2026/10/25", last[2])
        assertEquals(XlsxSheet.KIND_BOOL_ERROR, s.kindAt(13, 1))
    }

    @Test
    fun multiplicationTableAndLongList() {
        val book = XlsBook.open(res("sales.xls"))
        val t = book.load(1)
        assertEquals(10, t.rows.size)
        assertEquals("81", t.rows[9][9])
        assertEquals("×", t.rows[0][0])
        val l = book.load(2)
        assertEquals(501, l.rows.size)
        assertEquals(listOf("500", "品物500", "8", "960"), l.rows[500])
    }

    @Test
    fun stringsContinuedAcrossRecords() {
        // 文字の途中で CONTINUE に切れ、続きの先頭に「文字の幅」のフラグが入る(1 バイト文字 → 2 バイト文字に変わる)
        val first = byteArrayOf(5, 0, 0, 'a'.code.toByte(), 'b'.code.toByte())
        val second = byteArrayOf(1, 'X'.code.toByte(), 0, 'Y'.code.toByte(), 0, 'Z'.code.toByte(), 0)
        assertEquals("abXYZ", XlsBook.Segs(listOf(first, second)).unicode(2))
        // ふりがな・装飾つき(rich + ext)の文字列は、付属データを読み飛ばして次の文字列の頭へ
        val rich = byteArrayOf(2, 0, 0x0C, 1, 0, 3, 0, 0, 0, 'h'.code.toByte(), 'i'.code.toByte(), 0, 0, 0, 0, 9, 9, 9, 3, 0, 0, 'n'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte())
        val seg = XlsBook.Segs(listOf(rich))
        assertEquals("hi", seg.unicode(2))
        assertEquals("nex", seg.unicode(2))
    }

    @Test
    fun rejectsNonExcel() {
        try {
            XlsBook.open(res("report.doc"))
            org.junit.Assert.fail()
        } catch (e: OpException) {
            assertNotNull(e.message)
        }
    }
}

class DocTest {
    @Test
    fun readsStructureOfReport() {
        val opened = Doc.open(Ole2(res("report.doc")))
        val doc = opened.doc
        assertEquals(2, doc.pages.size)
        val paras = doc.blocks.filterIsInstance<DocBlock.Para>()
        val title = paras.first { it.text == "2026年度 事業報告書" }
        assertEquals("center", title.align)
        assertEquals(true, title.runs[0].r.bold)
        assertEquals(44, title.runs[0].r.sizeHp)
        assertTrue(paras.any { it.heading == 1 && it.text == "1. 概要" })
        assertTrue(paras.any { it.heading == 2 && it.text == "1.1 主な成果" })
        // 書式
        val body = paras.first { it.text.startsWith("本書は") }
        assertEquals("both", body.align)
        assertTrue(body.runs.any { it.r.color == 0xFFC00000.toInt() && it.text.contains("赤い太字") })
        assertTrue(body.runs.any { it.r.underline == true })
        // リンク
        val link = paras.flatMap { it.runs }.first { it.link != null }
        assertEquals("https://example.com/report", link.link)
        assertEquals("こちらのリンク", link.text)
        // 箇条書きと番号
        val marks = paras.filter { it.marker != null }.map { it.marker to it.text }
        assertTrue(marks.toString(), marks.any { it.second == "売上は前年比 112% を達成" && it.first != "1." })
        assertTrue(marks.any { it.first == "1." && it.second.startsWith("第1四半期") })
        assertTrue(marks.any { it.first == "2." && it.second.startsWith("第2四半期") })
        assertTrue(marks.any { it.first == "a)" && it.second == "準備" })
        assertTrue(marks.any { it.first == "b)" && it.second == "実施" })
        assertTrue(marks.any { it.first == "3." && it.second.startsWith("第3四半期") })
        assertTrue(marks.any { it.first == "I." })
        assertTrue(marks.any { it.first == "II." })
        // 見出しには記号をつけない
        assertTrue(paras.filter { it.heading > 0 }.all { it.marker == null })
        // 表: 結合したセル(3 列分)
        val t = doc.blocks.filterIsInstance<DocBlock.Table>().single()
        assertEquals(4, t.rows.size)
        assertEquals(3, t.rows[3][0].span)
        assertEquals("全社の合計（結合セル）", (t.rows[3][0].blocks[0] as DocBlock.Para).text)
        assertEquals(4, t.weights.size)
        assertEquals("1,780", (t.rows[2][3].blocks[0] as DocBlock.Para).text)
        // 画像
        val img = doc.blocks.filterIsInstance<DocBlock.Image>().single()
        assertTrue(img.path.startsWith("doc-picture/"))
        assertTrue(img.widthPt > 100f)
        val png = opened.read(img.path)!!
        assertEquals(0x89, png[0].toInt() and 0xFF)
        assertEquals('P'.code, png[1].toInt())
        assertNull(opened.read("doc-picture/9999999"))
    }

    @Test
    fun readsJapaneseTextTableAndBreak() {
        val doc = Doc.parse(res("notice.doc"))
        assertEquals(2, doc.pages.size)
        val all = texts(doc.blocks)
        assertTrue(all.any { it.startsWith("お知らせ（RTF 形式）") })
        assertTrue(all.any { it.contains("{braces}") && it.contains("backslash \\") })
        assertTrue(all.any { it.contains("line\nbreak") })
        val t = doc.blocks.filterIsInstance<DocBlock.Table>().single()
        assertEquals(3, t.rows.size)
        assertEquals(listOf(3000, 2000, 2000), t.weights)
        assertEquals("開発部", (t.rows[2][0].blocks[0] as DocBlock.Para).text)
        val indent = doc.blocks.filterIsInstance<DocBlock.Para>().first { it.text.startsWith("・インデント") }
        assertEquals(36f, indent.leftPt, 0.01f)
        assertTrue(doc.pages[1].filterIsInstance<DocBlock.Para>().any { it.text.startsWith("改ページの後") })
    }

    @Test
    fun rejectsNonWord() {
        try {
            Doc.parse(res("sales.xls"))
            org.junit.Assert.fail()
        } catch (e: OpException) {
            assertNotNull(e.message)
        }
    }
}

class PptTest {
    @Test
    fun readsSlideTexts() {
        val slides = Ppt.parse(res("plan.ppt"))
        assertEquals(5, slides.size)
        assertEquals("2026年度 事業計画", slides[0].title)
        assertTrue(slides[0].lines.contains("ファイラー OpenDocument 表示テスト"))
        assertTrue(slides[1].lines.contains("新規顧客の開拓"))
        assertTrue(slides[1].lines.contains("海外: 8 社（重点）"))
        assertTrue(slides[3].lines.containsAll(listOf("項目", "計画", "実績", "売上", "3,360")))
        assertTrue(slides[4].lines.any { it.contains("非表示") })
        assertEquals(listOf(1, 2, 3, 4, 5), slides.map { it.number })
    }

    @Test
    fun rejectsNonPowerPoint() {
        try {
            Ppt.parse(res("sales.xls"))
            org.junit.Assert.fail()
        } catch (e: OpException) {
            assertNotNull(e.message)
        }
    }
}

class PptDeckTest {
    private fun deck() = PptDeck.parse(res("plan.ppt"))

    @Test
    fun readsSizeAndShapes() {
        val r = deck()
        val d = r.deck
        assertEquals(5, d.slides.size)
        assertTrue(d.widthPt > 100f && d.heightPt > 100f)
        println("size=${d.widthPt}x${d.heightPt}")
        for (s in d.slides) {
            println("slide ${s.number} hidden=${s.hidden} bg=${s.background?.javaClass?.simpleName} shapes=${s.shapes.size}")
            for (sh in s.shapes) {
                val t = sh.text?.paras?.joinToString("|") { p -> p.runs.joinToString("") { it.text } + "@" + p.runs.firstOrNull()?.sizePt }
                println("  ${sh.geom?.prst} ${sh.x},${sh.y} ${sh.w}x${sh.h} fill=${sh.fill?.javaClass?.simpleName} line=${sh.line != null} pic=${sh.picture?.path} tbl=${sh.table != null} text=$t")
            }
        }
        assertTrue(d.slides.all { it.shapes.isNotEmpty() })
    }

    @Test
    fun titleTextIsPositionedWithSize() {
        val s = deck().deck.slides[0]
        val titled = s.shapes.firstOrNull { it.text?.paras?.any { p -> p.runs.any { r -> r.text.contains("事業計画") } } == true }
        assertNotNull(titled)
        val run = titled!!.text!!.paras.first { p -> p.runs.any { it.text.contains("事業計画") } }.runs.first()
        assertTrue(run.sizePt >= 20f)
        assertTrue(titled.w > 100f)
    }
}
