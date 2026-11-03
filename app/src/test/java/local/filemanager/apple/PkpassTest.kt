package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

private fun passFiles(name: String): Map<String, ByteArray> {
    val bytes = object {}.javaClass.getResourceAsStream("/apple/$name")!!.use { it.readBytes() }
    val out = LinkedHashMap<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
        while (true) {
            val e = z.nextEntry ?: break
            out[e.name] = z.readBytes()
        }
    }
    return out
}

private fun pass(name: String, locales: List<String> = listOf("ja", "en")): Pass {
    val f = passFiles(name)
    return Pkpass.parse(f.keys, { f[it] }, locales)
}

class JsonTest {
    @Test
    fun parsesAllKinds() {
        val v = Json.obj(Json.parse("""{"a":1,"b":-2.5,"c":"x\n\u3042\"","d":[true,false,null,{"e":[]}],"f":{},"g":1e3,"h":12345678901234}"""))!!
        assertEquals(1L, v["a"])
        assertEquals(-2.5, v["b"] as Double, 0.0)
        assertEquals("x\nあ\"", v["c"])
        val d = Json.arr(v["d"])
        assertEquals(listOf(true, false, null), d.take(3))
        assertEquals(emptyList<Any?>(), Json.arr(Json.obj(d[3])!!["e"]))
        assertTrue(Json.obj(v["f"])!!.isEmpty())
        assertEquals(1000.0, v["g"] as Double, 0.0)
        assertEquals(12345678901234L, v["h"])
        assertEquals("1", Json.str(1L))
        assertEquals("3", Json.str(3.0))
        assertEquals("2.5", Json.str(2.5))
    }

    @Test
    fun rejectsBadJson() {
        for (bad in listOf("", "{", "{\"a\":}", "[1,]", "{\"a\" 1}", "\"abc", "tru", "{\"a\":1} x", "[" .repeat(500))) {
            try {
                Json.parse(bad)
                org.junit.Assert.fail("読めてしまった: $bad")
            } catch (e: OpException) {
                assertNotNull(e.message)
            }
        }
    }
}

class PkpassTest {
    @Test
    fun eventTicketIsLocalizedToJapanese() {
        val p = pass("event.pkpass")
        assertEquals("eventTicket", p.style)
        assertEquals("チケット", p.styleName)
        assertEquals("テスト興行", p.organization)
        assertEquals("ファイラーの表示確認用イベントチケット", p.description)
        assertEquals("テスト興行", p.logoText)
        assertEquals(0xFF202652.toInt(), p.bg)
        assertEquals(0xFFFFFFFF.toInt(), p.fg)
        assertEquals("開演", p.header[0].label)
        assertEquals("2026/12/24 18:00", p.header[0].value)
        assertEquals("公演名", p.primary[0].label)
        assertEquals("冬のスペシャルコンサート", p.primary[0].value)
        assertEquals(listOf("東京ベイホール", "A-12"), p.secondary.map { it.value })
        assertEquals("JPY 8800", p.auxiliary[1].value)
        assertEquals("開演後の入場はできません。\nこの券は譲渡できません。", p.back[0].value)
        assertEquals("QRコード", p.barcode!!.formatName)
        assertEquals("EV-2026-000123|SEAT=A-12|GATE=3", p.barcode!!.message)
        assertEquals("EV-2026-000123", p.barcode!!.altText)
        assertEquals("2026/12/24 23:59", p.expiration)
        assertEquals(1, p.locations.size)
        assertTrue(p.locations[0].startsWith("会場の近くです"))
        // 画像は @2x を優先
        assertEquals("strip@2x.png", p.images["strip"])
        assertEquals("logo@2x.png", p.images["logo"])
        assertEquals("icon@2x.png", p.images["icon"])
        assertFalse(p.voided)
    }

    @Test
    fun englishWhenJapaneseIsNotWanted() {
        val p = pass("event.pkpass", listOf("en"))
        assertEquals("Winter Special Concert", p.primary[0].value)
        assertEquals("DOORS", p.header[0].label)
        // 言語の指定がなければ、置き換えない(キーのまま)
        val raw = pass("event.pkpass", emptyList())
        assertEquals("EVENT_NAME", raw.primary[0].value)
    }

    @Test
    fun boardingPassWithPdf417() {
        val p = pass("boarding.pkpass")
        assertEquals("boardingPass", p.style)
        assertEquals("PKTransitTypeAir", p.transit)
        assertEquals(listOf("HND", "ITM"), p.primary.map { it.value })
        assertEquals("PDF417", p.barcode!!.formatName)
        // 時差つきの日時(秒なし)
        assertEquals("2026/11/03 08:20", p.auxiliary[0].value)
        assertEquals(2, p.header.size)
    }

    @Test
    fun couponStoreCardAndGeneric() {
        val c = pass("coupon.pkpass")
        assertEquals("coupon", c.style)
        assertEquals("300円引き", c.primary[0].value)
        assertEquals("JPY 1200", c.secondary[0].value)
        assertEquals("2026/12/31", c.auxiliary[0].value)
        assertEquals("Code 128", c.barcode!!.formatName)
        assertEquals("2026/12/31 23:59 UTC", c.expiration)
        assertEquals("thumbnail.png", c.images["thumbnail"])

        val s = pass("storecard.pkpass")
        assertEquals("ストアカード", s.styleName)
        assertEquals("JPY 3480.5", s.header[0].value)
        assertEquals("1280", s.primary[0].value)
        assertEquals("山田 太郎", s.secondary[0].value)
        assertEquals("Aztecコード", s.barcode!!.formatName)

        val g = pass("generic_voided.pkpass")
        assertEquals("generic", g.style)
        assertTrue(g.voided)
        assertNull(g.barcode)
        assertEquals(1, g.fieldCount - 1)
    }

    @Test
    fun missingPassJson() {
        try {
            pass("broken.pkpass")
            org.junit.Assert.fail()
        } catch (e: OpException) {
            assertTrue(e.message!!.contains("pass.json"))
        }
    }

    @Test
    fun helpers() {
        assertEquals(0xFFFF8000.toInt(), Pkpass.color("rgb(255, 128, 0)"))
        assertNull(Pkpass.color("#ff8000"))
        val m = Pkpass.strings("\"A\" = \"あ\\\"い\";\n\"B\"=\"x\\ny\";".toByteArray())
        assertEquals("あ\"い", m["A"])
        assertEquals("x\ny", m["B"])
        assertEquals("2026/10/26", Pkpass.dateText("2026-10-26"))
        assertEquals("not a date", Pkpass.dateText("not a date"))
        assertEquals("2026/10/26 09:30", Pkpass.dateText("2026-10-26T09:30:00"))
    }
}
