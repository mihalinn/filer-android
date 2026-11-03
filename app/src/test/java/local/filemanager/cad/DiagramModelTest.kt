package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.Deflater

class DrawioTest {
    private fun wrap(cells: String) =
        """<mxfile><diagram name="P1"><mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/>$cells</root></mxGraphModel></diagram></mxfile>"""

    private val two = """
        <mxCell id="a" value="A" style="rounded=1;fillColor=#ffe6cc;strokeColor=#d79b00;" vertex="1" parent="1"><mxGeometry x="40" y="40" width="100" height="60" as="geometry"/></mxCell>
        <mxCell id="b" value="B" style="ellipse;" vertex="1" parent="1"><mxGeometry x="300" y="40" width="80" height="60" as="geometry"/></mxCell>
        <mxCell id="e" value="" style="edgeStyle=orthogonalEdgeStyle;endArrow=classic;" edge="1" parent="1" source="a" target="b"><mxGeometry relative="1" as="geometry"/></mxCell>
    """

    @Test
    fun 図形と線を読む() {
        val pages = Drawio.parse(wrap(two))!!
        assertEquals(1, pages.size)
        assertEquals("P1", pages[0].name)
        val shapes = pages[0].items.filterIsInstance<Drawio.Shape>()
        val lines = pages[0].items.filterIsInstance<Drawio.Line>()
        assertEquals(2, shapes.size)
        assertEquals(1, lines.size)
        val a = shapes[0]
        assertEquals("rect", a.kind)
        assertTrue(a.rounded)
        assertEquals(0xFFFFE6CC.toInt(), a.fill)
        assertEquals(0xFFD79B00.toInt(), a.stroke)
        assertEquals("A", a.label)
        assertEquals("ellipse", shapes[1].kind)
        // 既定: 白い塗り・黒い枠
        assertEquals(0xFFFFFFFF.toInt(), shapes[1].fill)
        assertEquals(0xFF000000.toInt(), shapes[1].stroke)
    }

    @Test
    fun 直角の線は_Aの右側からBの左側へ() {
        val line = Drawio.parse(wrap(two))!![0].items.filterIsInstance<Drawio.Line>()[0]
        assertEquals(Drawio.Pt(140f, 70f), line.points.first())
        assertEquals(Drawio.Pt(300f, 70f), line.points.last())
        assertEquals("classic", line.endArrow)
        assertEquals("none", line.startArrow)
        // 同じ高さなので、折れ曲がりは無い(まっすぐ)
        assertTrue(line.points.all { it.y == 70f })
    }

    @Test
    fun 高さが違うときは_縦横だけの折れ線() {
        val xml = wrap(
            """
            <mxCell id="a" value="A" style="" vertex="1" parent="1"><mxGeometry x="0" y="0" width="100" height="40" as="geometry"/></mxCell>
            <mxCell id="b" value="B" style="" vertex="1" parent="1"><mxGeometry x="300" y="200" width="100" height="40" as="geometry"/></mxCell>
            <mxCell id="e" style="edgeStyle=orthogonalEdgeStyle;" edge="1" parent="1" source="a" target="b"><mxGeometry relative="1" as="geometry"/></mxCell>
            """,
        )
        val pts = Drawio.parse(xml)!![0].items.filterIsInstance<Drawio.Line>()[0].points
        assertEquals(Drawio.Pt(100f, 20f), pts.first())
        assertEquals(Drawio.Pt(300f, 220f), pts.last())
        for (i in 1 until pts.size) assertTrue(pts[i].x == pts[i - 1].x || pts[i].y == pts[i - 1].y)
    }

    @Test
    fun 入れ物の中の図形は_親からの相対位置() {
        val xml = wrap(
            """
            <mxCell id="g" value="G" style="swimlane;startSize=30;" vertex="1" parent="1"><mxGeometry x="100" y="200" width="300" height="200" as="geometry"/></mxCell>
            <mxCell id="c" value="C" style="" vertex="1" parent="g"><mxGeometry x="10" y="40" width="50" height="30" as="geometry"/></mxCell>
            """,
        )
        val shapes = Drawio.parse(xml)!![0].items.filterIsInstance<Drawio.Shape>()
        assertEquals("swimlane", shapes[0].kind)
        assertEquals(30f, shapes[0].header)
        assertEquals(110f, shapes[1].x)
        assertEquals(240f, shapes[1].y)
    }

    @Test
    fun HTMLの文字は_タグと実体参照を取り除く() {
        assertEquals("a\nb & <c>", Drawio.htmlToText("<div>a</div><span style=\"x\">b</span> &amp; &lt;c&gt;"))
        assertEquals("1行目\n2行目", Drawio.htmlToText("1行目<br>2行目"))
        assertEquals("A B", Drawio.htmlToText("A&nbsp;B"))
        assertEquals("日", Drawio.htmlToText("&#26085;"))
        assertEquals("plain", Drawio.htmlToText("plain"))
    }

    @Test
    fun 色の読み方() {
        val st = Drawio.parseStyle("fillColor=none;strokeColor=#abc;fontColor=default;x=light-dark(#112233,#fff)")
        assertNull(Drawio.colorOf(st, "fillColor", 0xFFFFFFFF.toInt()))
        assertEquals(0xFFAABBCC.toInt(), Drawio.colorOf(st, "strokeColor", null))
        assertEquals(0xFF000000.toInt(), Drawio.colorOf(st, "fontColor", 0xFF000000.toInt()))
        assertEquals(0xFF112233.toInt(), Drawio.colorOf(st, "x", null))
        assertEquals(7, Drawio.colorOf(st, "missing", 7))
    }

    @Test
    fun スタイルの先頭の図形名() {
        assertEquals("ellipse", Drawio.parseStyle("ellipse;whiteSpace=wrap;html=1;")["shape"])
        assertEquals("1", Drawio.parseStyle("ellipse;whiteSpace=wrap;html=1;")["html"])
    }

    @Test
    fun 圧縮された図面も読める() {
        val inner = """<mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/>$two</root></mxGraphModel>"""
        val enc = URLEncoder.encode(inner, "UTF-8").replace("+", "%20")
        val d = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        d.setInput(enc.toByteArray())
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        val b64 = Base64.getEncoder().encodeToString(out.toByteArray())
        val pages = Drawio.parse("""<mxfile><diagram name="圧縮">$b64</diagram></mxfile>""")
        assertNotNull(pages)
        assertEquals("圧縮", pages!![0].name)
        assertEquals(3, pages[0].items.size)
    }

    @Test
    fun 複数ページ() {
        val m = "<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/></root></mxGraphModel>"
        val pages = Drawio.parse("<mxfile><diagram name=\"一\">$m</diagram><diagram name=\"二\">$m</diagram></mxfile>")!!
        assertEquals(listOf("一", "二"), pages.map { it.name })
    }

    @Test
    fun 図面でないものはnull() {
        assertNull(Drawio.parse("hello"))
        assertNull(Drawio.parse("<html></html>"))
        assertNull(Drawio.parse("<mxfile></mxfile>"))
        assertNull(Drawio.parse(""))
    }

    @Test
    fun 外部参照を含む図面でも外へ読みに行かない() {
        val evil = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><mxfile><diagram>&e;</diagram></mxfile>"
        assertNull(Drawio.parse(evil))
    }

    @Test
    fun 線の途中の点() {
        val m = Drawio.midpoint(listOf(Drawio.Pt(0f, 0f), Drawio.Pt(100f, 0f), Drawio.Pt(100f, 100f)))
        assertEquals(100f, m.x, 0.01f)
        assertEquals(0f, m.y, 0.01f)
    }

    @Test
    fun 線の上の文字は_線の真ん中() {
        val xml = wrap(
            """
            <mxCell id="a" value="A" style="" vertex="1" parent="1"><mxGeometry x="0" y="0" width="100" height="40" as="geometry"/></mxCell>
            <mxCell id="b" value="B" style="" vertex="1" parent="1"><mxGeometry x="300" y="0" width="100" height="40" as="geometry"/></mxCell>
            <mxCell id="e" value="通信" style="endArrow=none;" edge="1" parent="1" source="a" target="b"><mxGeometry relative="1" as="geometry"/></mxCell>
            """,
        )
        val items = Drawio.parse(xml)!![0].items
        val label = items.filterIsInstance<Drawio.Label>().single()
        assertEquals("通信", label.text)
        assertEquals(200f, label.x, 0.5f)
        assertEquals("none", items.filterIsInstance<Drawio.Line>().single().endArrow)
    }

    /** 実物の図面での確認用(環境変数 FILER_DRAWIO_SAMPLE に、ファイルの場所を入れたときだけ動く)。 */
    @Test
    fun 実物の図面() {
        val path = System.getenv("FILER_DRAWIO_SAMPLE")
        assumeTrue(path != null)
        val text = TextDecode.decode(java.io.File(path!!).readBytes()).text
        val pages = Drawio.parse(text)
        assertNotNull(pages)
        for (p in pages!!) {
            val shapes = p.items.filterIsInstance<Drawio.Shape>().size
            val lines = p.items.filterIsInstance<Drawio.Line>().size
            println("drawio sample page '${p.name}': shapes=$shapes lines=$lines bounds=(${p.minX},${p.minY})-(${p.maxX},${p.maxY})")
            assertTrue(shapes > 0)
            for (it in p.items) if (it is Drawio.Line) for (q in it.points) assertFalse(q.x.isNaN() || q.y.isNaN())
        }
    }
}

class StlTest {
    private fun binaryStl(tris: List<FloatArray>, header: String = "binary"): ByteArray {
        val bb = ByteBuffer.allocate(84 + 50 * tris.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(header.toByteArray().copyOf(80))
        bb.putInt(tris.size)
        for (t in tris) {
            bb.putFloat(0f); bb.putFloat(0f); bb.putFloat(1f)
            for (v in t) bb.putFloat(v)
            bb.putShort(0)
        }
        return bb.array()
    }

    private val tri1 = floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 20f, 5f)
    private val tri2 = floatArrayOf(-5f, -5f, -1f, 1f, 1f, 1f, 2f, 3f, 30f)

    @Test
    fun バイナリを読む() {
        val m = Stl.parse(binaryStl(listOf(tri1, tri2)))
        assertEquals(2, m.triangles)
        assertEquals(18, m.vertices.limit())
        assertEquals(-5f, m.min[0]); assertEquals(10f, m.max[0])
        assertEquals(-5f, m.min[1]); assertEquals(20f, m.max[1])
        assertEquals(-1f, m.min[2]); assertEquals(30f, m.max[2])
        assertEquals(15f, m.sizeX)
        assertEquals(10f, m.vertices.get(3))
        assertEquals(30f, m.vertices.get(17))
    }

    @Test
    fun ヘッダーにsolidと書かれたバイナリも読める() {
        val m = Stl.parse(binaryStl(listOf(tri1), header = "solid made by some tool"))
        assertEquals(1, m.triangles)
        assertEquals(10f, m.max[0])
    }

    @Test
    fun ASCIIを読む() {
        val txt = """
            solid test
              facet normal 0 0 1
                outer loop
                  vertex 0 0 0
                  vertex 1.5e1 0 0
                  vertex 0 -2 3
                endloop
              endfacet
            endsolid test
        """.trimIndent()
        val m = Stl.parse(txt.toByteArray())
        assertEquals(1, m.triangles)
        assertEquals(15f, m.max[0])
        assertEquals(-2f, m.min[1])
        assertEquals(3f, m.max[2])
    }

    @Test
    fun 壊れたデータは例外() {
        for (bad in listOf(ByteArray(0), "hello".toByteArray(), ByteArray(100), "solid x\nendsolid x".toByteArray())) {
            try {
                Stl.parse(bad)
                fail("読めてはいけない: ${bad.size}")
            } catch (e: OpException) {
                // 想定どおり
            }
        }
    }

    @Test
    fun 途中で切れたバイナリは_読めた分だけ() {
        val full = binaryStl(listOf(tri1, tri2))
        val cut = full.copyOf(84 + 50 + 20)
        val m = Stl.parse(java.io.ByteArrayInputStream(cut), full.size.toLong())
        assertEquals(1, m.triangles)
    }

    @Test
    fun 数がNaNの三角形は飛ばす() {
        val bad = floatArrayOf(Float.NaN, 0f, 0f, 1f, 1f, 1f, 2f, 2f, 2f)
        val m = Stl.parse(binaryStl(listOf(bad, tri1)))
        assertEquals(1, m.triangles)
    }
}

class VcardTest {
    @Test
    fun バージョン3_0() {
        val photo = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        val txt = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:山田 太郎\r\nN:山田;太郎;;;\r\nORG:株式会社テスト;営業部\r\n" +
            "TEL;TYPE=CELL:090-1234-5678\r\nTEL;TYPE=HOME,VOICE:03-0000-0000\r\nitem1.EMAIL;TYPE=INTERNET:taro@example.com\r\n" +
            "ADR;TYPE=WORK:;;1-2-3 丸の内;千代田区;東京都;100-0005;日本\r\nNOTE:1行目\\n2行目\\, カンマ\r\n" +
            "PHOTO;ENCODING=b;TYPE=JPEG:" + photo.substring(0, 6) + "\r\n " + photo.substring(6) + "\r\nEND:VCARD\r\n"
        val c = Vcard.parse(txt).single()
        assertEquals("山田 太郎", c.name)
        assertEquals("株式会社テスト 営業部", c.org)
        assertEquals(VField("電話(携帯)", "090-1234-5678", VField.Kind.PHONE), c.fields[0])
        assertEquals(VField("電話(自宅)", "03-0000-0000", VField.Kind.PHONE), c.fields[1])
        assertEquals(VField("メール", "taro@example.com", VField.Kind.EMAIL), c.fields[2])
        assertEquals("住所(勤務先)", c.fields[3].label)
        assertEquals("〒100-0005 日本 東京都 千代田区 1-2-3 丸の内", c.fields[3].value)
        assertEquals("1行目\n2行目, カンマ", c.fields[4].value)
        assertTrue(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8).contentEquals(c.photo))
    }

    @Test
    fun 名前はFNが無ければN() {
        val c = Vcard.parse("BEGIN:VCARD\nVERSION:3.0\nN:山田;太郎;;;\nEND:VCARD").single()
        assertEquals("山田 太郎", c.name)
        assertEquals("(名前なし)", Vcard.parse("BEGIN:VCARD\nEND:VCARD").single().name)
    }

    @Test
    fun バージョン2_1のQUOTED_PRINTABLEと続きの行() {
        // 「山田」 を UTF-8 で QP にしたもの。行末の = で次の行に続く
        val txt = "BEGIN:VCARD\r\nVERSION:2.1\r\nFN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:=E5=B1=B1=\r\n=E7=94=B0\r\n" +
            "TEL;CELL;VOICE:080-1111-2222\r\nEND:VCARD\r\n"
        val c = Vcard.parse(txt).single()
        assertEquals("山田", c.name)
        assertEquals("電話(携帯)", c.fields[0].label)
    }

    @Test
    fun QUOTED_PRINTABLEのShift_JIS() {
        // 「山」 = 0x8E 0x52 (Shift_JIS)
        val txt = "BEGIN:VCARD\nVERSION:2.1\nFN;CHARSET=SHIFT_JIS;ENCODING=QUOTED-PRINTABLE:=8E=52\nEND:VCARD"
        assertEquals("山", Vcard.parse(txt).single().name)
    }

    @Test
    fun 複数の連絡先と壊れた行() {
        val txt = "BEGIN:VCARD\nFN:A\nEND:VCARD\ngarbage line\nBEGIN:VCARD\nFN:B\nTEL:1\nEND:VCARD\nBEGIN:VCARD\nFN:C"
        val cards = Vcard.parse(txt)
        assertEquals(listOf("A", "B"), cards.map { it.name })
        assertTrue(Vcard.parse("hello").isEmpty())
        assertTrue(Vcard.parse("").isEmpty())
    }

    @Test
    fun 写真が壊れていても落ちない() {
        val c = Vcard.parse("BEGIN:VCARD\nFN:A\nPHOTO;ENCODING=b:!!!not base64!!!\nEND:VCARD").single()
        assertNull(c.photo)
        assertEquals("A", c.name)
    }
}

class StlSampleTest {
    /** 実物の STL での確認用(環境変数 FILER_STL_SAMPLE に、ファイルの場所を入れたときだけ動く)。 */
    @Test
    fun 実物のSTL() {
        val path = System.getenv("FILER_STL_SAMPLE")
        assumeTrue(path != null)
        val f = java.io.File(path!!)
        val m = f.inputStream().use { Stl.parse(it, f.length()) }
        println("stl sample: triangles=${m.triangles} size=${m.sizeX} x ${m.sizeY} x ${m.sizeZ}")
        assertEquals((f.length() - 84) / 50, m.triangles.toLong())
        assertTrue(m.sizeX > 0f && m.sizeY > 0f && m.sizeZ > 0f)
    }
}
