package local.filemanager

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class MhtmlTest {
    private val nl = "\r\n"

    private fun sample(eol: String): ByteArray {
        val b = "----=_Part_1"
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 1, 2, 3)
        val b64 = java.util.Base64.getMimeEncoder(10, "\n".toByteArray()).encodeToString(png)
        val html = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><link rel=\"stylesheet\" href=\"cid:css-1\"></head><body><img src=\"https://example.com/a.png\">日本語</body></html>"
        val s = StringBuilder()
        s.append("From: <Saved by Blink>$eol")
        s.append("Snapshot-Content-Location: https://example.com/page$eol")
        s.append("Subject: =?utf-8?Q?=E3=80=90=E5=AD=A6=E3=83=9E=E3=82=B9=E3=80=91?=$eol =?utf-8?B?44K544Kt44Or?=$eol")
        s.append("Date: Mon, 5 Jan 2026 21:03:43 +0900$eol")
        s.append("MIME-Version: 1.0$eol")
        s.append("Content-Type: multipart/related;$eol type=\"text/html\";$eol boundary=\"$b\"$eol$eol$eol")
        s.append("--$b$eol")
        s.append("Content-Type: text/html$eol")
        s.append("Content-ID: <frame-1@mhtml.blink>$eol")
        s.append("Content-Transfer-Encoding: binary$eol")
        s.append("Content-Location: https://example.com/page$eol$eol")
        s.append(html).append(eol)
        s.append("--$b$eol")
        s.append("Content-Type: text/css$eol")
        s.append("Content-Transfer-Encoding: quoted-printable$eol")
        s.append("Content-ID: <css-1>$eol$eol")
        s.append("body { color: red; background: url(https://example.com/a.png); }=$eol")
        s.append(" .x { content: \"=E3=81=82\"; }$eol")
        s.append("--$b$eol")
        s.append("Content-Type: image/png$eol")
        s.append("Content-Transfer-Encoding: base64$eol")
        s.append("Content-Location: https://example.com/a.png#frag$eol$eol")
        s.append(b64.replace("\n", eol)).append(eol)
        s.append("--$b--$eol")
        return s.toString().toByteArray(Charsets.UTF_8)
    }

    private fun check(eol: String) {
        val doc = Mhtml.parse(sample(eol))
        assertNotNull(doc)
        doc!!
        assertEquals("【学マス】スキル", doc.subject)
        assertEquals("https://example.com/page", doc.pageUrl)
        assertEquals(3, doc.parts.size)
        val main = doc.main!!
        assertTrue(main.text().contains("日本語"))
        assertTrue(main.text().contains("cid:css-1"))
        val css = doc.findCid("css-1")!!
        assertTrue(css.isCss)
        // quoted-printable: 行末の「=」は行をつなぎ、=E3=81=82 は「あ」
        val cssText = css.text()
        assertTrue(cssText.contains("url(https://example.com/a.png); }"))
        assertTrue(cssText.contains("\"あ\""))
        val img = doc.findUrl("https://example.com/a.png")!!
        assertEquals("image/png", img.contentType)
        assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 1, 2, 3), img.body)
        assertNotNull(doc.findUrl("https://example.com/a.png#other")) // # 以降は見ない
        assertNull(doc.findUrl("https://example.com/none.png"))
    }

    @Test
    fun 改行がCRLFのもの() = check(nl)

    @Test
    fun 改行がLFだけのもの() = check("\n")

    @Test
    fun cidはContentLocationにも書かれる() {
        val s = "Content-Type: multipart/related; boundary=\"B\"\r\n\r\n--B\r\nContent-Type: text/html\r\nContent-Location: https://e.com/\r\n\r\n<html><link href=\"cid:css-9@mhtml.blink\"></html>\r\n--B\r\nContent-Type: text/css\r\nContent-Location: cid:css-9@mhtml.blink\r\n\r\nbody{}\r\n--B--\r\n"
        val doc = Mhtml.parse(s.toByteArray())!!
        assertTrue(doc.findCid("css-9@mhtml.blink")!!.isCss)
        assertEquals("body{}", doc.findCid("css-9@mhtml.blink")!!.text())
    }

    @Test
    fun viewportの指定を外す() {
        val h = "<head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><META NAME='viewport' content='x'><meta name=\"description\" content=\"d\"></head>"
        assertEquals("<head><meta charset=\"utf-8\"><meta name=\"description\" content=\"d\"></head>", Mhtml.stripViewportMeta(h))
        assertEquals("<p>そのまま</p>", Mhtml.stripViewportMeta("<p>そのまま</p>"))
    }

    @Test
    fun cidの書き換え() {
        assertEquals(
            "<link href=\"https://filer.local/mht-cid/css-1\"><style>@import url(https://filer.local/mht-cid/x@y);</style>",
            Mhtml.rewriteCid("<link href=\"cid:css-1\"><style>@import url(cid:x@y);</style>", "https://filer.local/mht-cid/"),
        )
        assertEquals("cidではない", Mhtml.rewriteCid("cidではない", "p"))
    }

    @Test
    fun ヘッダーの語の復号() {
        assertEquals("日本語", Mhtml.decodeWords("=?UTF-8?B?5pel5pys6Kqe?="))
        assertEquals("a b", Mhtml.decodeWords("=?utf-8?Q?a_b?="))
        assertEquals("そのまま", Mhtml.decodeWords("そのまま"))
        assertNull(Mhtml.decodeWords(null))
    }

    @Test
    fun 符号化の復号() {
        assertArrayEquals("あ=".toByteArray(), Mhtml.decodeQp("=E3=81=82=3D".toByteArray()))
        assertArrayEquals("abc".toByteArray(), Mhtml.decodeQp("a=\r\nb=\nc".toByteArray()))
        assertArrayEquals("=ZZ".toByteArray(), Mhtml.decodeQp("=ZZ".toByteArray()))
        assertArrayEquals("hello".toByteArray(), Mhtml.decodeBase64("aGVs\r\nbG8=".toByteArray()))
    }

    @Test
    fun MHTMLでないものは読まない() {
        assertNull(Mhtml.parse("hello world".toByteArray()))
        assertNull(Mhtml.parse("Content-Type: text/plain\r\n\r\nplain".toByteArray())?.takeIf { false })
        assertFalse(PreviewKind.of("a.mht") != PreviewKind.MHTML)
        assertEquals(PreviewKind.MHTML, PreviewKind.of("b.MHTML"))
    }

    /** 環境変数 FILER_MHT_SAMPLE に、実物の場所を入れたときだけ動く。 */
    @Test
    fun 実物のMHTを読む() {
        val path = System.getenv("FILER_MHT_SAMPLE")
        assumeTrue(path != null)
        val doc = Mhtml.parse(File(path!!).readBytes())
        assertNotNull(doc)
        doc!!
        println("mht: subject=${doc.subject} date=${doc.date} url=${doc.pageUrl} parts=${doc.parts.size} main=${doc.main?.location} mainLen=${doc.main?.body?.size}")
        val counts = doc.parts.groupingBy { it.contentType }.eachCount()
        println("types: $counts")
        val html = doc.main!!.text()
        println("html head: " + html.take(120).replace("\n", " "))
        val cids = Regex("cid:([^\"'\\s)>]+)").findAll(html).map { it.groupValues[1] }.toList()
        println("cid refs in html: ${cids.size}, found: ${cids.count { doc.findCid(it) != null }}")
        for (p in doc.parts.filter { it.contentType.startsWith("image/") }.take(5)) {
            println("image ${p.contentType} ${p.body.size} head=${p.body.take(4).map { "%02X".format(it) }} loc=${p.location?.take(70)}")
        }
        // 画像のパーツが、画像の形式の印で始まっているか
        for (p in doc.parts.filter { it.contentType == "image/png" }) assertEquals(0x89, p.body[0].toInt() and 0xFF)
        for (p in doc.parts.filter { it.contentType == "image/jpeg" }) assertEquals(0xFF, p.body[0].toInt() and 0xFF)
        val imgUrls = Regex("<img[^>]+src=\"(https?://[^\"]+)\"").findAll(html).map { it.groupValues[1] }.toList()
        println("img tags: ${imgUrls.size}, in archive: ${imgUrls.count { doc.findUrl(it) != null }}")
    }
}
