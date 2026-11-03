package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun pptRes(name: String): ByteArray = object {}.javaClass.getResourceAsStream("/legacy/$name")!!.use { it.readBytes() }

class PptDeckDetailTest {
    private val r = PptDeck.parse(pptRes("plan.ppt"))

    @Test
    fun picturesAreExtracted() {
        val png = r.read("ppt-image/1")
        assertNotNull(png)
        val isPng = png!![0] == 0x89.toByte() && png[1] == 'P'.code.toByte()
        val isJpeg = png[0] == 0xFF.toByte() && png[1] == 0xD8.toByte()
        assertTrue(isPng || isJpeg)
        assertEquals(null, r.read("ppt-image/99"))
        assertEquals(null, r.read("zip/entry"))
    }

    @Test
    fun slidesKeepBackgroundAndTextFormat() {
        // 1 枚目は濃い紺の背景(自分の背景)、2 枚目はマスターの白
        assertEquals(0xFF1F3864.toInt(), (r.deck.slides[0].background as Paint.Solid).argb)
        assertEquals(0xFFFFFFFF.toInt(), (r.deck.slides[1].background as Paint.Solid).argb)
        val s = r.deck.slides[1]
        val title = s.shapes.first { it.text?.paras?.firstOrNull()?.runs?.firstOrNull()?.text == "今期の重点施策" }.text!!
        assertEquals(0, title.paras[0].algn) // 中央
        assertEquals(44f, title.paras[0].runs[0].sizePt, 0.01f)
        val body = s.shapes.first { it.text?.paras?.size == 5 }.text!!
        assertTrue(body.paras.all { it.marker != null })
        assertEquals(32f, body.paras[0].runs[0].sizePt, 0.01f)
        assertEquals(28f, body.paras[1].runs[0].sizePt, 0.01f)
    }

    @Test
    fun shapesKeepFillLineAndArrows() {
        val s3 = r.deck.slides[2].shapes
        val round = s3.first { it.geom?.prst == "roundRect" }
        assertEquals(0xFF5B9BD5.toInt(), (round.fill as Paint.Solid).argb)
        assertEquals(0xFF1F4E79.toInt(), round.line!!.argb)
        assertTrue(s3.any { it.geom?.prst == "ellipse" })
        val arrows = s3.filter { it.geom?.prst == "line" }
        assertEquals(4, arrows.size)
        assertEquals(3, arrows.count { it.line!!.tail })
        assertTrue(arrows.any { it.flipH }) // 逆向きの矢印
        assertTrue(arrows.any { it.line!!.dash != null }) // 破線
    }

    @Test
    fun tableCellsAreDrawnAsBoxes() {
        val s4 = r.deck.slides[3].shapes
        val texts = s4.mapNotNull { it.text?.paras?.joinToString("") { p -> p.runs.joinToString("") { x -> x.text } } }
        assertTrue(texts.containsAll(listOf("項目", "計画", "実績", "売上", "3,360", "結合したセル（3 列分）")))
        assertEquals("ppt-image/1", s4.mapNotNull { it.picture }.single().path)
    }

    @Test
    fun fallsBackWhenNotPowerPoint() {
        try {
            PptDeck.parse(pptRes("sales.xls"))
            org.junit.Assert.fail()
        } catch (e: OpException) {
            assertNotNull(e.message)
        }
    }
}

class PptDeckSampleTest {
    private fun dir(): java.io.File? =
        System.getenv("FILER_TD")?.let { java.io.File(it, "17_旧形式のOffice/PowerPoint") }?.takeIf { it.isDirectory }

    @Test
    fun allSamplesParse() {
        org.junit.Assume.assumeTrue(dir() != null)
        val files = dir()!!.listFiles { f -> f.extension == "ppt" }!!
        assertTrue(files.isNotEmpty())
        for (f in files) {
            val r = PptDeck.parse(f.readBytes())
            val shapes = r.deck.slides.sumOf { it.shapes.size }
            println(f.name + ": slides=" + r.deck.slides.size + " shapes=" + shapes + " size=" + r.deck.widthPt + "x" + r.deck.heightPt)
            for (s in r.deck.slides) {
                println("  " + s.number + " bg=" + s.background?.javaClass?.simpleName + " shapes=" + s.shapes.size + " pics=" + s.shapes.count { it.picture != null })
            }
            assertTrue(f.name, shapes > 0)
        }
    }
}
