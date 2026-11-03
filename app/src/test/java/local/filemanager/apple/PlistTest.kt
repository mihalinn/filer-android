package local.filemanager

import local.filemanager.Plist.dict
import local.filemanager.Plist.items
import local.filemanager.Plist.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun apple(name: String): ByteArray = object {}.javaClass.getResourceAsStream("/apple/$name")!!.use { it.readBytes() }

class PlistTest {
    @Test
    fun binaryAndXmlGiveTheSameValues() {
        val a = Plist.parse(apple("s_bin.plist"))
        val b = Plist.parse(apple("s_xml.plist"))
        assertTrue(Plist.isBinary(apple("s_bin.plist")))
        assertFalse(Plist.isBinary(apple("s_xml.plist")))
        assertEquals(Plist.toXml(b), Plist.toXml(a))
    }

    @Test
    fun readsEveryKindOfValue() {
        for (name in listOf("s_bin.plist", "s_xml.plist")) {
            val p = Plist.parse(apple(name))
            assertEquals("ファイラー", p.str("CFBundleName"))
            assertEquals("local.filemanager.ios", p.str("CFBundleIdentifier"))
            assertEquals(2, p.items("UIDeviceFamily").size)
            assertEquals(45L, ((Plist.parse(apple(name)).dict("CFBundleVersion") as PValue.Str).value.toLong()))
            assertEquals(true, (p.dict("LSRequiresIPhoneOS") as PValue.Bool).value)
            assertEquals(false, (p.dict("ITSAppUsesNonExemptEncryption") as PValue.Bool).value)
            assertEquals(2.5, (p.dict("ScaleFactor") as PValue.Real).value, 0.0001)
            assertEquals(9007199254740993L, (p.dict("BigNumber") as PValue.Int).value)
            assertEquals(-42L, (p.dict("Negative") as PValue.Int).value)
            assertEquals("", p.str("EmptyString"))
            assertTrue((p.dict("EmptyDict") as PValue.Dict).map.isEmpty())
            assertTrue(p.str("Unicode")!!.contains("絵文字😀🎉"))
            assertEquals("<tag> & \"quote\" 'single'", p.str("Escapes"))
            assertEquals("2026-10-26T09:30:00Z", (p.dict("ReleaseDate") as PValue.Date).iso)
            assertEquals(32, (p.dict("SmallData") as PValue.Data).bytes.size)
            val deep = p.dict("Nested").dict("level1").dict("level2").items("level3")
            assertEquals(5, deep.size)
            assertEquals("yes", deep[4].str("deep"))
        }
    }

    @Test
    fun xmlOutputEscapesAndShortensBigData() {
        val big = Plist.parse(apple("big_bin.plist"))
        assertEquals(300 * 1024, (big.dict("BigBlob") as PValue.Data).bytes.size)
        assertEquals(400, big.items("Items").size)
        val xml = Plist.toXml(big)
        assertTrue(xml.contains("全体 307200 バイト"))
        assertTrue(xml.length < 100_000)
        val small = Plist.toXml(Plist.parse(apple("s_bin.plist")))
        assertTrue(small.contains("&lt;tag&gt; &amp;"))
        assertTrue(small.startsWith("<?xml"))
    }

    @Test
    fun displayText() {
        val bin = Plist.displayText("a.plist", apple("s_bin.plist"))!!
        assertEquals("バイナリplist→XML", bin.second)
        assertTrue(bin.first.contains("<key>CFBundleName</key>"))
        assertNull(Plist.displayText("a.plist", apple("s_xml.plist")))
        val prov = Plist.displayText("x.mobileprovision", apple("test.mobileprovision"))!!
        assertTrue(prov.first.contains("<key>TeamName</key>"))
        assertTrue(prov.first.contains("ABCDE12345"))
        // 構成プロファイルは普通の XML plist
        val cfg = Plist.parse(apple("wifi.mobileconfig"))
        assertEquals("自宅のWi-Fi(テスト)", cfg.str("PayloadDisplayName"))
        assertEquals("HomeNet", cfg.items("PayloadContent")[0].str("SSID_STR"))
    }

    @Test
    fun brokenInputFails() {
        val b = apple("s_bin.plist")
        try {
            Plist.parse(b.copyOf(b.size - 20))
            org.junit.Assert.fail()
        } catch (e: OpException) {
            assertNotNull(e.message)
        }
        try {
            Plist.parse("<plist><dict><key>a</key>".toByteArray())
            org.junit.Assert.fail()
        } catch (e: OpException) {
            assertNotNull(e.message)
        }
        // 位置が範囲外の壊れたバイナリ(無限ループ・例外にならない)
        val broken = b.copyOf()
        broken[broken.size - 9] = 0x7F
        try { Plist.parse(broken) } catch (e: OpException) { /* 読めない旨のエラーでよい */ }
    }

    @Test
    fun webArchiveBecomesAPage() {
        for (name in listOf("page_bin.webarchive", "page_xml.webarchive")) {
            val doc = Plist.webArchive(apple(name))!!
            val main = doc.main!!
            assertTrue(main.text().contains("Safari のウェブアーカイブ"))
            assertEquals("https://example.test/page/index.html", doc.pageUrl)
            val css = doc.findUrl("https://example.test/page/style.css")!!
            assertTrue(css.isCss)
            assertTrue(css.text().contains("#0a5"))
            val img = doc.findUrl("https://example.test/page/images/photo.png")!!
            assertEquals("image/png", img.contentType)
            assertEquals(0x89, img.body[0].toInt() and 0xFF)
            // 入れ子の枠
            assertTrue(doc.findUrl("https://example.test/page/frame.html")!!.text().contains("入れ子の枠"))
        }
    }

    @Test
    fun notAWebArchive() {
        assertNull(Plist.webArchive(apple("s_xml.plist")))
    }
}
