package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

private const val NS = """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships""""

private const val THEME = """<a:theme $NS><a:themeElements><a:clrScheme name="t">
<a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1>
<a:dk2><a:srgbClr val="44546A"/></a:dk2><a:lt2><a:srgbClr val="E7E6E6"/></a:lt2>
<a:accent1><a:srgbClr val="4472C4"/></a:accent1><a:accent2><a:srgbClr val="ED7D31"/></a:accent2>
<a:accent3><a:srgbClr val="A5A5A5"/></a:accent3><a:accent4><a:srgbClr val="FFC000"/></a:accent4>
<a:accent5><a:srgbClr val="5B9BD5"/></a:accent5><a:accent6><a:srgbClr val="70AD47"/></a:accent6>
<a:hlink><a:srgbClr val="0563C1"/></a:hlink><a:folHlink><a:srgbClr val="954F72"/></a:folHlink></a:clrScheme>
<a:fontScheme name="f"><a:majorFont><a:latin typeface="Calibri Light"/><a:ea typeface="游ゴシック Light"/></a:majorFont><a:minorFont><a:latin typeface="Calibri"/><a:ea typeface="游ゴシック"/></a:minorFont></a:fontScheme>
<a:fmtScheme name="x"><a:lnStyleLst><a:ln w="6350"/><a:ln w="12700"/><a:ln w="19050"/></a:lnStyleLst></a:fmtScheme></a:themeElements></a:theme>"""

private const val MASTER = """<p:sldMaster $NS><p:cSld><p:bg><p:bgRef idx="1001"><a:schemeClr val="bg1"/></p:bgRef></p:bg><p:spTree>
<p:sp><p:nvSpPr><p:cNvPr id="2" name="T"/><p:cNvSpPr/><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x="609600" y="274638"/><a:ext cx="10972800" cy="1143000"/></a:xfrm></p:spPr><p:txBody><a:bodyPr anchor="ctr"/><a:lstStyle/><a:p><a:r><a:t>マスターのタイトル</a:t></a:r></a:p></p:txBody></p:sp>
<p:sp><p:nvSpPr><p:cNvPr id="3" name="B"/><p:cNvSpPr/><p:nvPr><p:ph type="body" idx="1"/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x="609600" y="1600200"/><a:ext cx="10972800" cy="4525963"/></a:xfrm></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>マスターの本文</a:t></a:r></a:p></p:txBody></p:sp>
<p:sp><p:nvSpPr><p:cNvPr id="4" name="Deco"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="0" y="6400800"/><a:ext cx="12192000" cy="457200"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:solidFill><a:schemeClr val="accent2"/></a:solidFill></p:spPr></p:sp>
</p:spTree></p:cSld><p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>
<p:txStyles>
<p:titleStyle><a:lvl1pPr algn="l"><a:defRPr sz="4400" b="1"><a:solidFill><a:schemeClr val="tx2"/></a:solidFill><a:latin typeface="+mj-lt"/><a:ea typeface="+mj-ea"/></a:defRPr></a:lvl1pPr></p:titleStyle>
<p:bodyStyle>
<a:lvl1pPr marL="342900" indent="-342900"><a:spcBef><a:spcPts val="1000"/></a:spcBef><a:buFont typeface="Arial"/><a:buChar char="•"/><a:defRPr sz="3200"><a:solidFill><a:schemeClr val="tx1"/></a:solidFill></a:defRPr></a:lvl1pPr>
<a:lvl2pPr marL="742950" indent="-285750"><a:buFont typeface="Arial"/><a:buChar char="–"/><a:defRPr sz="2800"/></a:lvl2pPr>
</p:bodyStyle><p:otherStyle><a:lvl1pPr><a:defRPr sz="1800"/></a:lvl1pPr></p:otherStyle></p:txStyles></p:sldMaster>"""

private const val LAYOUT = """<p:sldLayout $NS type="obj"><p:cSld><p:spTree>
<p:sp><p:nvSpPr><p:cNvPr id="2" name="T"/><p:cNvSpPr/><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x="914400" y="457200"/><a:ext cx="10363200" cy="914400"/></a:xfrm></p:spPr></p:sp>
<p:sp><p:nvSpPr><p:cNvPr id="3" name="B"/><p:cNvSpPr/><p:nvPr><p:ph idx="1"/></p:nvPr></p:nvSpPr><p:spPr/></p:sp>
</p:spTree></p:cSld></p:sldLayout>"""

private fun pack(slide: String, layout: String = LAYOUT, master: String = MASTER, extraRels: String = ""): (String) -> ByteArray? {
    val rel = "<Relationships xmlns=\"x\">"
    val files = mapOf(
        "ppt/presentation.xml" to """<p:presentation $NS><p:sldIdLst><p:sldId id="256" r:id="rId1"/></p:sldIdLst><p:sldSz cx="12192000" cy="6858000"/><p:defaultTextStyle><a:lvl1pPr><a:defRPr sz="1800"/></a:lvl1pPr></p:defaultTextStyle></p:presentation>""",
        "ppt/_rels/presentation.xml.rels" to """$rel<Relationship Id="rId1" Target="slides/slide1.xml"/></Relationships>""",
        "ppt/slides/slide1.xml" to """<p:sld $NS><p:cSld><p:spTree><p:nvGrpSpPr/><p:grpSpPr/>$slide</p:spTree></p:cSld></p:sld>""",
        "ppt/slides/_rels/slide1.xml.rels" to """$rel<Relationship Id="rId1" Target="../slideLayouts/slideLayout1.xml"/><Relationship Id="rId2" Target="../media/image1.png"/><Relationship Id="rId3" Target="https://example.com/" TargetMode="External"/>$extraRels</Relationships>""",
        "ppt/slideLayouts/slideLayout1.xml" to layout,
        "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to """$rel<Relationship Id="rId1" Target="../slideMasters/slideMaster1.xml"/></Relationships>""",
        "ppt/slideMasters/slideMaster1.xml" to master,
        "ppt/slideMasters/_rels/slideMaster1.xml.rels" to """$rel<Relationship Id="rId2" Target="../theme/theme1.xml"/></Relationships>""",
        "ppt/theme/theme1.xml" to THEME,
    )
    return { name -> files[name]?.toByteArray() }
}

private fun sp(id: Int, body: String, ph: String = "", off: String = "", ext: String = "", geom: String = "rect", extra: String = "", tx: String = "") =
    """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="s$id"/><p:cNvSpPr/><p:nvPr>$ph</p:nvPr></p:nvSpPr><p:spPr>${if (off.isNotEmpty()) """<a:xfrm><a:off x="${off.substringBefore(',')}" y="${off.substringAfter(',')}"/><a:ext cx="${ext.substringBefore(',')}" cy="${ext.substringAfter(',')}"/></a:xfrm><a:prstGeom prst="$geom"><a:avLst/></a:prstGeom>""" else ""}$extra</p:spPr><p:txBody><a:bodyPr$tx/><a:lstStyle/>$body</p:txBody></p:sp>"""

private fun para(text: String, ppr: String = "", rpr: String = "") = """<a:p>$ppr<a:r><a:rPr lang="ja-JP"$rpr/><a:t>$text</a:t></a:r></a:p>"""

private fun deckOf(slide: String, layout: String = LAYOUT, master: String = MASTER) = SlideDeckParser.parse(pack(slide, layout, master))

private fun SlideDoc.byText(t: String): SShape = shapes.first { s -> s.text?.paras?.any { p -> p.runs.any { it.text == t } } == true }

private fun rgb(argb: Int) = argb and 0xFFFFFF

class SlideGeomTest {
    @Test
    fun 四角と反転() {
        val c = SlideGeom.contours(Geom("rect"), 100f, 50f, false, false)
        assertEquals(1, c.size)
        assertEquals(listOf(0f, 0f, 100f, 0f, 100f, 50f, 0f, 50f), c[0].pts.toList())
        val f = SlideGeom.contours(Geom("rightArrow"), 100f, 50f, true, false)[0]
        // 右向きの矢印を左右反転 → 先端(一番外の点)が左端
        val xs = (0 until f.pts.size / 2).map { f.pts[it * 2] }
        assertEquals(0f, xs.min(), 0.001f)
        assertEquals(100f, xs.max(), 0.001f)
    }

    @Test
    fun 矢印の先端() {
        val c = SlideGeom.contours(Geom("rightArrow"), 100f, 50f, false, false)[0]
        val tip = (0 until c.pts.size / 2).map { c.pts[it * 2] to c.pts[it * 2 + 1] }.first { it.first == 100f }
        assertEquals(25f, tip.second, 0.001f)
        val up = SlideGeom.contours(Geom("upArrow"), 50f, 100f, false, false)[0]
        val tipUp = (0 until up.pts.size / 2).map { up.pts[it * 2] to up.pts[it * 2 + 1] }.first { it.second == 0f }
        assertEquals(25f, tipUp.first, 0.001f)
    }

    @Test
    fun 角丸と楕円は折れ線() {
        val e = SlideGeom.contours(Geom("ellipse"), 100f, 100f, false, false)[0]
        assertTrue(e.pts.size > 40)
        val xs = (0 until e.pts.size / 2).map { e.pts[it * 2] }
        assertEquals(0f, xs.min(), 0.01f)
        assertEquals(100f, xs.max(), 0.01f)
        val r = SlideGeom.contours(Geom("roundRect", mapOf("adj" to 50000f)), 100f, 40f, false, false)[0]
        val ys = (0 until r.pts.size / 2).map { r.pts[it * 2 + 1] }
        assertEquals(0f, ys.min(), 0.01f)
        assertEquals(40f, ys.max(), 0.01f)
    }

    @Test
    fun 線は塗らない() {
        val l = SlideGeom.contours(Geom("straightConnector1"), 80f, 20f, false, true)[0]
        assertFalse(l.closed)
        assertFalse(l.fill)
        assertEquals(listOf(0f, 20f, 80f, 0f), l.pts.toList())
    }

    @Test
    fun 自由な形() {
        val g = Geom(
            "cust",
            cust = listOf(
                CustPath(
                    100f, 100f,
                    listOf(CustCmd.Move(0f, 0f), CustCmd.Line(100f, 0f), CustCmd.Line(50f, 100f), CustCmd.Close),
                    fill = true, stroke = true,
                ),
            ),
        )
        val c = SlideGeom.contours(g, 200f, 50f, false, false)
        assertEquals(1, c.size)
        assertTrue(c[0].closed)
        assertEquals(listOf(0f, 0f, 200f, 0f, 100f, 50f), c[0].pts.toList())
    }

    @Test
    fun 自由な形の弧() {
        // 左下から右下まで、上に半円
        val g = Geom("cust", cust = listOf(CustPath(100f, 50f, listOf(CustCmd.Move(0f, 50f), CustCmd.Arc(50f, 50f, 180f, 180f)), true, true)))
        val c = SlideGeom.contours(g, 100f, 50f, false, false)[0]
        val n = c.pts.size
        assertEquals(100f, c.pts[n - 2], 0.5f)
        assertEquals(50f, c.pts[n - 1], 0.5f)
        val top = (0 until n / 2).minOf { c.pts[it * 2 + 1] }
        assertEquals(0f, top, 0.5f)
    }
}

class SlideColorTest {
    private val theme = SlideTheme.parse(THEME.toByteArray())
    private val ctx = ColorCtx(theme, SlideTheme.DEFAULT_CLRMAP)

    private fun color(xml: String): Int? = SlideColor.fromHolder(XmlTree.parse("""<a:solidFill $NS>$xml</a:solidFill>""".toByteArray()), ctx)

    @Test
    fun 色の指定いろいろ() {
        assertEquals(0xFF336699.toInt(), color("""<a:srgbClr val="336699"/>"""))
        assertEquals(0xFF4472C4.toInt(), color("""<a:schemeClr val="accent1"/>"""))
        assertEquals(0xFF000000.toInt(), color("""<a:schemeClr val="tx1"/>"""))
        assertEquals(0xFFFFFFFF.toInt(), color("""<a:schemeClr val="bg1"/>"""))
        assertEquals(0xFF44546A.toInt(), color("""<a:schemeClr val="tx2"/>"""))
        assertEquals(0xFFFF0000.toInt(), color("""<a:prstClr val="red"/>"""))
    }

    @Test
    fun 明るさの調整() {
        // 「濃く 25%」は Office では 2F5597
        assertEquals(0x2F5597, rgb(color("""<a:schemeClr val="accent1"><a:lumMod val="75000"/></a:schemeClr>""")!!))
        // 白に近づける
        val light = rgb(color("""<a:schemeClr val="accent1"><a:lumMod val="20000"/><a:lumOff val="80000"/></a:schemeClr>""")!!)
        assertTrue(((light shr 16) and 0xFF) > 0xD0 && (light and 0xFF) > 0xE0)
    }

    @Test
    fun 薄く_透明() {
        assertEquals(0xB4C6E7, rgb(color("""<a:schemeClr val="accent1"><a:tint val="40000"/></a:schemeClr>""")!!))
        val half = color("""<a:srgbClr val="000000"><a:alpha val="50000"/></a:srgbClr>""")!!
        assertEquals(127, (half ushr 24) and 0xFF)
    }

    @Test
    fun 配色の入れ替え() {
        val dark = ColorCtx(theme, SlideTheme.DEFAULT_CLRMAP + mapOf("bg1" to "dk1", "tx1" to "lt1"))
        val n = XmlTree.parse("""<a:solidFill $NS><a:schemeClr val="tx1"/></a:solidFill>""".toByteArray())
        assertEquals(0xFFFFFFFF.toInt(), SlideColor.fromHolder(n, dark))
    }

    @Test
    fun テーマの書体() {
        assertEquals("Calibri Light", theme.font("+mj-lt"))
        assertEquals("游ゴシック", theme.font("+mn-ea"))
        assertEquals("Meiryo", theme.font("Meiryo"))
        assertNull(theme.font("+mn-cs"))
    }
}

class SlideDeckTest {
    @Test
    fun 場所と書式をマスターから引き継ぐ() {
        val slide = sp(2, para("題名"), ph = """<p:ph type="title"/>""") +
            sp(3, para("項目") + para("子項目", ppr = """<a:pPr lvl="1"/>"""), ph = """<p:ph idx="1"/>""")
        val deck = deckOf(slide)
        assertEquals(960f, deck.widthPt, 0.01f)
        assertEquals(540f, deck.heightPt, 0.01f)
        val d = deck.slides[0]
        // 先頭はマスターの飾りの四角(アクセント2)
        val deco = d.shapes[0]
        assertEquals(0xED7D31, rgb((deco.fill as Paint.Solid).argb))
        assertEquals(0f, deco.x, 0.01f)
        assertEquals(504f, deco.y, 0.01f)
        // タイトル: レイアウトの位置・マスターの書式
        val t = d.byText("題名")
        assertEquals(72f, t.x, 0.01f)
        assertEquals(36f, t.y, 0.01f)
        assertEquals(816f, t.w, 0.01f)
        val run = t.text!!.paras[0].runs[0]
        assertEquals(44f, run.sizePt, 0.01f)
        assertTrue(run.bold)
        assertEquals(0x44546A, rgb(run.color))
        assertEquals("游ゴシック Light", run.font)
        assertEquals(1, t.text.anchor)
        // 本文: 位置はマスターから、箇条書き・字下げもマスターから
        val b = d.byText("項目")
        assertEquals(48f, b.x, 0.01f)
        assertEquals(126f, b.y, 0.01f)
        val p0 = b.text!!.paras[0]
        assertEquals("•", p0.marker)
        assertEquals(27f, p0.marL, 0.01f)
        assertEquals(-27f, p0.indent, 0.01f)
        assertEquals(32f, p0.runs[0].sizePt, 0.01f)
        assertEquals(10f, p0.beforePt, 0.01f)
        val p1 = b.text.paras[1]
        assertEquals("–", p1.marker)
        assertEquals(28f, p1.runs[0].sizePt, 0.01f)
        assertEquals(58.5f, p1.marL, 0.01f)
        // 背景はマスターの bgRef(bg1 = 白)
        assertEquals(0xFFFFFF, rgb((d.background as Paint.Solid).argb))
    }

    @Test
    fun 自分の指定が優先される() {
        val slide2 ="""<p:sp><p:nvSpPr><p:cNvPr id="2" name="a"/><p:cNvSpPr/><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x="127000" y="254000"/><a:ext cx="1270000" cy="635000"/></a:xfrm></p:spPr>
            <p:txBody><a:bodyPr anchor="b"/><a:lstStyle/><a:p><a:pPr algn="ctr"/><a:r><a:rPr sz="2000" b="0"><a:solidFill><a:srgbClr val="FF0000"/></a:solidFill></a:rPr><a:t>題名</a:t></a:r></a:p></p:txBody></p:sp>"""
        val t = deckOf(slide2).slides[0].byText("題名")
        assertEquals(10f, t.x, 0.01f)
        assertEquals(20f, t.y, 0.01f)
        assertEquals(100f, t.w, 0.01f)
        val r = t.text!!.paras[0].runs[0]
        assertEquals(20f, r.sizePt, 0.01f)
        assertFalse(r.bold)
        assertEquals(0xFF0000, rgb(r.color))
        assertEquals(0, t.text.paras[0].algn)
        assertEquals(2, t.text.anchor)
    }

    @Test
    fun グループの座標変換() {
        val grp = """<p:grpSp><p:nvGrpSpPr><p:cNvPr id="9" name="g"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="1000000" y="1000000"/><a:ext cx="2000000" cy="1000000"/><a:chOff x="0" y="0"/><a:chExt cx="4000000" cy="2000000"/></a:xfrm></p:grpSpPr>""" +
            sp(10, para("中"), off = "2000000,1000000", ext = "1000000,500000") + "</p:grpSp>"
        val s = deckOf(grp).slides[0].byText("中")
        assertEquals(2000000f / 12700f, s.x, 0.01f)
        assertEquals(1500000f / 12700f, s.y, 0.01f)
        assertEquals(500000f / 12700f, s.w, 0.01f)
        assertEquals(250000f / 12700f, s.h, 0.01f)
    }

    @Test
    fun 番号つき箇条書き_リンク_スライド番号() {
        val body = """<a:p><a:pPr marL="457200" indent="-457200"><a:buFont typeface="+mj-lt"/><a:buAutoNum type="arabicPeriod"/></a:pPr><a:r><a:t>一</a:t></a:r></a:p>""" +
            """<a:p><a:pPr marL="457200" indent="-457200"><a:buAutoNum type="arabicPeriod"/></a:pPr><a:r><a:t>二</a:t></a:r></a:p>""" +
            """<a:p><a:pPr><a:buNone/></a:pPr><a:r><a:t>区切り</a:t></a:r></a:p>""" +
            """<a:p><a:pPr marL="457200" indent="-457200"><a:buAutoNum type="alphaLcPeriod"/></a:pPr><a:r><a:t>三</a:t></a:r></a:p>""" +
            """<a:p><a:r><a:rPr><a:hlinkClick r:id="rId3"/></a:rPr><a:t>リンク</a:t></a:r><a:fld id="{1}" type="slidenum"><a:rPr/><a:t>‹#›</a:t></a:fld></a:p>"""
        val t = deckOf(sp(2, body, off = "0,0", ext = "5000000,5000000")).slides[0].byText("一")
        val ps = t.text!!.paras
        assertEquals("1.", ps[0].marker)
        assertEquals("2.", ps[1].marker)
        assertNull(ps[2].marker)
        assertEquals("a.", ps[3].marker)
        val last = ps[4].runs
        assertEquals("https://example.com/", last[0].link)
        assertTrue(last[0].underline)
        assertEquals(0x0563C1, rgb(last[0].color))
        assertEquals("1", last[1].text)
    }

    @Test
    fun 縮小して全体を表示_の倍率() {
        val s = deckOf(sp(2, para("小", rpr = """ sz="2000""""), off = "0,0", ext = "5000000,5000000", tx = """ wrap="none"""", extra = "")).slides[0].byText("小")
        assertFalse(s.text!!.wrap)
        val fit = """<p:sp><p:nvSpPr><p:cNvPr id="2" name="f"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="5000000" cy="5000000"/></a:xfrm></p:spPr><p:txBody><a:bodyPr><a:normAutofit fontScale="50000" lnSpcReduction="20000"/></a:bodyPr><a:lstStyle/><a:p><a:pPr><a:lnSpc><a:spcPct val="100000"/></a:lnSpc></a:pPr><a:r><a:rPr sz="2000"/><a:t>縮</a:t></a:r></a:p></p:txBody></p:sp>"""
        val f = deckOf(fit).slides[0].byText("縮")
        assertEquals(10f, f.text!!.paras[0].runs[0].sizePt, 0.01f)
        assertEquals(0.8f, f.text.paras[0].lineMult!!, 0.001f)
    }

    @Test
    fun 画像の切り抜きと回転() {
        val pic = """<p:pic><p:nvPicPr><p:cNvPr id="3" name="p"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="rId2"/><a:srcRect l="10000" t="20000" r="5000" b="0"/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr><a:xfrm rot="2700000" flipH="1"><a:off x="0" y="0"/><a:ext cx="1270000" cy="635000"/></a:xfrm><a:prstGeom prst="ellipse"><a:avLst/></a:prstGeom></p:spPr></p:pic>"""
        val s = deckOf(pic).slides[0].shapes.first { it.picture != null }
        val p = s.picture!!
        assertEquals("ppt/media/image1.png", p.path)
        assertEquals(0.1f, p.cropL, 0.0001f)
        assertEquals(0.2f, p.cropT, 0.0001f)
        assertEquals(0.05f, p.cropR, 0.0001f)
        assertEquals(45f, s.rot, 0.001f)
        assertTrue(s.flipH)
        assertEquals("ellipse", s.geom!!.prst)
    }

    @Test
    fun 図形のスタイル参照と線() {
        val styled = """<p:sp><p:nvSpPr><p:cNvPr id="2" name="r"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="1270000" cy="635000"/></a:xfrm><a:prstGeom prst="roundRect"><a:avLst><a:gd name="adj" fmla="val 30000"/></a:avLst></a:prstGeom></p:spPr>
            <p:style><a:lnRef idx="2"><a:schemeClr val="accent1"><a:shade val="50000"/></a:schemeClr></a:lnRef><a:fillRef idx="1"><a:schemeClr val="accent1"/></a:fillRef><a:effectRef idx="0"><a:schemeClr val="accent1"/></a:effectRef><a:fontRef idx="minor"><a:schemeClr val="lt1"/></a:fontRef></p:style>
            <p:txBody><a:bodyPr anchor="ctr"/><a:lstStyle/><a:p><a:pPr algn="ctr"/><a:r><a:rPr/><a:t>白文字</a:t></a:r></a:p></p:txBody></p:sp>"""
        val s = deckOf(styled).slides[0].byText("白文字")
        assertEquals(0x4472C4, rgb((s.fill as Paint.Solid).argb))
        assertEquals(1.0f, s.line!!.widthPt, 0.001f)
        assertEquals(30000f, s.geom!!.adj["adj"]!!, 0.01f)
        assertEquals(0xFFFFFF, rgb(s.text!!.paras[0].runs[0].color))
        assertEquals(18f, s.text.paras[0].runs[0].sizePt, 0.01f)
        // 線の色は、アクセント1を 50% 暗くした色
        assertTrue(rgb(s.line.argb) < 0x4472C4)
    }

    @Test
    fun 線と矢印() {
        val cxn = """<p:cxnSp><p:nvCxnSpPr><p:cNvPr id="4" name="c"/><p:cNvCxnSpPr/><p:nvPr/></p:nvCxnSpPr><p:spPr><a:xfrm flipV="1"><a:off x="0" y="0"/><a:ext cx="1270000" cy="635000"/></a:xfrm><a:prstGeom prst="straightConnector1"><a:avLst/></a:prstGeom><a:ln w="25400"><a:solidFill><a:srgbClr val="FF0000"/></a:solidFill><a:prstDash val="dash"/><a:tailEnd type="triangle"/></a:ln></p:spPr></p:cxnSp>"""
        val s = deckOf(cxn).slides[0].shapes.last()
        assertEquals("straightConnector1", s.geom!!.prst)
        assertEquals(2f, s.line!!.widthPt, 0.001f)
        assertEquals(0xFF0000, rgb(s.line.argb))
        assertTrue(s.line.tail)
        assertFalse(s.line.head)
        assertNotNull(s.line.dash)
        assertTrue(s.flipV)
    }

    @Test
    fun 塗りは_グラデーションも読む() {
        val g = """<p:sp><p:nvSpPr><p:cNvPr id="2" name="g"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="1270000" cy="635000"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:gradFill><a:gsLst><a:gs pos="0"><a:srgbClr val="FF0000"/></a:gs><a:gs pos="100000"><a:srgbClr val="0000FF"/></a:gs></a:gsLst><a:lin ang="5400000"/></a:gradFill></p:spPr></p:sp>"""
        val s = deckOf(g).slides[0].shapes.last()
        val f = s.fill as Paint.Grad
        assertEquals(2, f.stops.size)
        assertEquals(90f, f.angle, 0.001f)
        assertEquals(0xFF0000, rgb(f.stops[0].second))
    }

    @Test
    fun 表のスタイルと結合() {
        val tbl = """<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="3" name="t"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr><p:xfrm><a:off x="0" y="0"/><a:ext cx="6000000" cy="1500000"/></p:xfrm><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/table"><a:tbl>
            <a:tblPr firstRow="1" bandRow="1"><a:tableStyleId>{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}</a:tableStyleId></a:tblPr>
            <a:tblGrid><a:gridCol w="2000000"/><a:gridCol w="4000000"/></a:tblGrid>
            <a:tr h="500000"><a:tc gridSpan="2"><a:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr sz="2000"/><a:t>見出し</a:t></a:r></a:p></a:txBody><a:tcPr/></a:tc><a:tc hMerge="1"><a:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr/></a:p></a:txBody><a:tcPr/></a:tc></a:tr>
            <a:tr h="500000"><a:tc><a:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr sz="1400"/><a:t>甲</a:t></a:r></a:p></a:txBody><a:tcPr anchor="ctr"/></a:tc><a:tc><a:txBody><a:bodyPr/><a:lstStyle/><a:p><a:pPr algn="r"/><a:r><a:rPr sz="1400"/><a:t>100</a:t></a:r></a:p></a:txBody><a:tcPr><a:solidFill><a:srgbClr val="FFFF00"/></a:solidFill></a:tcPr></a:tc></a:tr>
            <a:tr h="500000"><a:tc><a:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr sz="1400"/><a:t>乙</a:t></a:r></a:p></a:txBody><a:tcPr/></a:tc><a:tc><a:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr sz="1400"/><a:t>200</a:t></a:r></a:p></a:txBody><a:tcPr/></a:tc></a:tr>
            </a:tbl></a:graphicData></a:graphic></p:graphicFrame>"""
        val s = deckOf(tbl).slides[0].shapes.first { it.table != null }
        val t = s.table!!
        assertEquals(2, t.colW.size)
        assertEquals(2000000f / 12700f, t.colW[0], 0.001f)
        assertEquals(4000000f / 12700f, t.colW[1], 0.001f)
        assertEquals(3, t.rows.size)
        val h = t.rows[0][0]
        assertEquals(2, h.gridSpan)
        assertTrue(t.rows[0][1].hMerge)
        assertEquals(0x4472C4, rgb(h.fill!!))
        val hr = h.body.paras[0].runs[0]
        assertTrue(hr.bold)
        assertEquals(0xFFFFFF, rgb(hr.color))
        assertEquals(20f, hr.sizePt, 0.01f)
        // 1 行目のデータは帯の色(濃いほう)、2 行目は薄いほう
        assertEquals(0xB4C6E7, rgb(t.rows[1][0].fill!!))
        assertEquals(0xFFFF00, rgb(t.rows[1][1].fill!!))
        assertEquals(0xD9E2F3, rgb(t.rows[2][0].fill!!))
        assertEquals(1, t.rows[1][0].body.anchor)
        assertEquals(1, t.rows[1][1].body.paras[0].algn)
        // 白い罫線
        assertEquals(0xFFFFFF, rgb(t.rows[1][0].top!!.argb))
        assertEquals(1.0f, t.rows[1][0].top!!.widthPt, 0.001f)
        assertEquals(3.0f, h.bottom!!.widthPt, 0.001f)
    }

    @Test
    fun マスターの飾りを隠す() {
        val master = deckOf("").slides[0].shapes.size
        assertEquals(1, master)
        val noMaster = SlideDeckParser.parse(
            pack("", layout = LAYOUT.replace("""type="obj"""", """type="obj" showMasterSp="0"""")),
        ).slides[0].shapes.size
        assertEquals(0, noMaster)
    }

    @Test
    fun グラフは場所取りの枠() {
        val chart = """<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="3" name="c"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr><p:xfrm><a:off x="0" y="0"/><a:ext cx="6000000" cy="3000000"/></p:xfrm><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/chart"/></a:graphic></p:graphicFrame>"""
        val s = deckOf(chart).slides[0].shapes.last()
        assertTrue(s.text!!.paras[0].runs[0].text.startsWith("グラフ"))
    }

    @Test
    fun 自由な形を読む() {
        val cust = """<p:sp><p:nvSpPr><p:cNvPr id="2" name="c"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="1270000" cy="1270000"/></a:xfrm><a:custGeom><a:avLst/><a:pathLst><a:path w="100" h="100"><a:moveTo><a:pt x="0" y="0"/></a:moveTo><a:lnTo><a:pt x="100" y="0"/></a:lnTo><a:cubicBezTo><a:pt x="100" y="50"/><a:pt x="50" y="100"/><a:pt x="0" y="100"/></a:cubicBezTo><a:close/></a:path></a:pathLst></a:custGeom><a:solidFill><a:srgbClr val="00FF00"/></a:solidFill></p:spPr></p:sp>"""
        val s = deckOf(cust).slides[0].shapes.last()
        val c = SlideGeom.contours(s.geom!!, s.w, s.h, false, false)
        assertEquals(1, c.size)
        assertTrue(c[0].closed)
        assertEquals(0f, c[0].pts[0], 0.001f)
        assertEquals(100f, c[0].pts[2], 0.001f)
    }

    @Test
    fun 壊れた図形があっても_ほかは出る() {
        val bad = """<p:sp><p:nvSpPr><p:cNvPr id="2" name="x"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="abc" y="0"/><a:ext cx="1270000" cy="635000"/></a:xfrm></p:spPr></p:sp>"""
        val d = deckOf(bad + sp(3, para("無事"), off = "0,0", ext = "1270000,635000"))
        assertNotNull(d.slides[0].byText("無事"))
    }
}

class SlideSampleTest {
    @Test
    fun 見本のPowerPointを位置つきで読む() {
        val path = System.getenv("FILER_PPTX_SAMPLE")
        assumeTrue(path != null)
        val zf = ZipFile(File(path!!))
        val deck = SlideDeckParser.parse { name -> zf.getEntry(name)?.let { e -> zf.getInputStream(e).readBytes() } }
        println("deck ${deck.widthPt}x${deck.heightPt} slides=${deck.slides.size}")
        for (s in deck.slides) {
            println("-- slide ${s.number} bg=${s.background?.let { it::class.simpleName }} shapes=${s.shapes.size}")
            for (sh in s.shapes) {
                val tx = sh.text?.paras?.joinToString(" / ") { p -> (p.marker ?: "") + p.runs.joinToString("") { it.text } }
                println("   (%.0f,%.0f %.0fx%.0f) %s fill=%s pic=%s table=%s text=%s".format(sh.x, sh.y, sh.w, sh.h, sh.geom?.prst, sh.fill?.let { it::class.simpleName }, sh.picture?.path, sh.table?.rows?.size, tx))
            }
        }
        assertTrue(deck.slides.size >= 4)
        assertTrue(deck.slides.any { s -> s.shapes.any { it.table != null } })
    }
}
