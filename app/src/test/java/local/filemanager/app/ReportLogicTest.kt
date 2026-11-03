package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class ReportLogicTest {
    private val repo = "https://github.com/mihalinn/filer-android"

    private fun params(url: String): Map<String, String> =
        url.substringAfter('?').split('&').associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

    @Test
    fun テンプレートと入力欄がURLに入る() {
        val url = ReportLogic.issueUrl(repo, "bug_report.yml", mapOf("version" to "0.45.0", "device" to "Pixel 8 / Android 14"))
        assertTrue(url.startsWith("$repo/issues/new?template=bug_report.yml"))
        val p = params(url)
        assertEquals("0.45.0", p["version"])
        assertEquals("Pixel 8 / Android 14", p["device"])
        assertFalse(url.contains(" "))
    }

    @Test
    fun 空の欄は付けない() {
        val url = ReportLogic.issueUrl(repo, "feature_request.yml", mapOf("version" to "0.45.0", "idea" to "  "))
        assertFalse(params(url).containsKey("idea"))
    }

    @Test
    fun 長い記録はうしろを省いて_URLを規定の長さに収める() {
        val log = "例外 java.lang.IllegalStateException: 日本語を含む長い記録\n".repeat(400)
        val url = ReportLogic.issueUrl(repo, "bug_report.yml", mapOf("version" to "0.45.0", "log" to log), longKey = "log")
        assertTrue("長さ ${url.length}", url.length <= ReportLogic.MAX_URL)
        val p = params(url)
        assertEquals("0.45.0", p["version"])
        assertTrue(p["log"]!!.startsWith("例外 java.lang.IllegalStateException"))
        assertTrue(p["log"]!!.endsWith("…(長いので省略)"))
    }

    @Test
    fun 短い記録はそのまま入る() {
        val url = ReportLogic.issueUrl(repo, "bug_report.yml", mapOf("log" to "短い記録"), longKey = "log")
        assertEquals("短い記録", params(url)["log"])
    }

    @Test
    fun 端末の情報の行() {
        assertEquals("Google Pixel 8 / Android 14(API 34)", ReportLogic.deviceLine("Google", "Pixel 8", "14", 34))
    }
}