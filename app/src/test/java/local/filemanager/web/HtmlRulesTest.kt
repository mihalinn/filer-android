package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlRulesTest {
    @Test
    fun パスのたたみ方() {
        assertEquals("/a/c", HtmlRules.normalize("/a/b/../c"))
        assertEquals("/a/b", HtmlRules.normalize("/a/./b//"))
        assertEquals("/", HtmlRules.normalize("/../.."))
        assertEquals("/x", HtmlRules.normalize("x"))
        assertEquals("/a", HtmlRules.dirOf("/a/page.html"))
        assertEquals("/", HtmlRules.dirOf("/page.html"))
    }

    @Test
    fun 同じフォルダ以下は読める() {
        val page = "/共有/site/page.html"
        assertTrue(HtmlRules.isAllowed(page, "/共有/site/img/a.png"))
        assertTrue(HtmlRules.isAllowed(page, "/共有/site/other.html"))
        assertTrue(HtmlRules.isAllowed(page, "/共有/site/data.json"))
    }

    @Test
    fun 一つ上のフォルダは_画像とCSSとフォントだけ() {
        val page = "/共有/site/page.html"
        assertTrue(HtmlRules.isAllowed(page, "/共有/css/style.css"))
        assertTrue(HtmlRules.isAllowed(page, "/共有/site/../img/logo.PNG"))
        assertFalse(HtmlRules.isAllowed(page, "/共有/secret.txt"))
        assertFalse(HtmlRules.isAllowed(page, "/共有/other/data.json"))
        assertFalse(HtmlRules.isAllowed(page, "/共有/script.js"))
    }

    @Test
    fun それより外は読めない() {
        val page = "/共有/site/page.html"
        assertFalse(HtmlRules.isAllowed(page, "/etc/passwd"))
        assertFalse(HtmlRules.isAllowed(page, "/共有/site/../../storage/secret.png"))
        assertFalse(HtmlRules.isAllowed(page, "/共有/site/../../../etc/hosts"))
        // 名前が似ているだけの別のフォルダは対象外
        assertFalse(HtmlRules.isAllowed(page, "/共有/sitex/a.txt"))
    }

    @Test
    fun ルート直下のページ() {
        assertTrue(HtmlRules.isAllowed("/page.html", "/img/a.png"))
        assertTrue(HtmlRules.isAllowed("/page.html", "/a.txt"))
    }

    @Test
    fun 種類() {
        assertEquals("text/css", HtmlRules.mimeFor("css"))
        assertEquals("image/svg+xml", HtmlRules.mimeFor("svg"))
        assertEquals("image/png", HtmlRules.mimeFor("png"))
        assertTrue(HtmlRules.isTextual("html"))
        assertFalse(HtmlRules.isTextual("png"))
    }

    @Test
    fun 外部リンクの判定() {
        assertTrue(HtmlRules.isExternalLink("https://example.com/"))
        assertTrue(HtmlRules.isExternalLink("MAILTO:a@example.com"))
        assertFalse(HtmlRules.isExternalLink("javascript:alert(1)"))
        assertFalse(HtmlRules.isExternalLink("file:///etc/passwd"))
        assertFalse(HtmlRules.isExternalLink("intent://x"))
    }

    @Test
    fun HTMLの見せ方() {
        assertEquals(PreviewKind.HTML, PreviewKind.of("index.html"))
        assertEquals(PreviewKind.HTML, PreviewKind.of("A.HTM"))
        assertEquals(PreviewKind.XML, PreviewKind.of("a.xml"))
    }

    @Test
    fun 止めるのは外のサーバーへの通信だけ() {
        // 外部の読み込みを許可していないとき
        assertTrue(HtmlRules.shouldBlock("https", "cdn.example.com", false))
        assertTrue(HtmlRules.shouldBlock("HTTP", "example.com", false))
        // ページ本体や埋め込みの画像(data:)は止めない
        assertFalse(HtmlRules.shouldBlock("data", null, false))
        assertFalse(HtmlRules.shouldBlock("about", null, false))
        assertFalse(HtmlRules.shouldBlock(null, null, false))
        // アプリが返す場所は止めない
        assertFalse(HtmlRules.shouldBlock("https", "filer.local", false))
        // 許可しているときは何も止めない
        assertFalse(HtmlRules.shouldBlock("https", "cdn.example.com", true))
    }
}
