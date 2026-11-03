package local.filemanager

import androidx.compose.ui.graphics.Color
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerThemeTest {
    @After
    fun reset() {
        Pv.mode = "dark"
        Pv.appDark = false
    }

    private fun lin(c: Float) = if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    private fun lum(c: Color) = 0.2126f * lin(c.red) + 0.7152f * lin(c.green) + 0.0722f * lin(c.blue)

    /** 不透明の色どうしの、読みやすさの比(WCAG のコントラスト比) */
    private fun contrast(a: Color, b: Color): Float {
        val l1 = lum(a)
        val l2 = lum(b)
        return (maxOf(l1, l2) + 0.05f) / (minOf(l1, l2) + 0.05f)
    }

    @Test
    fun 配色の選び方() {
        Pv.mode = "dark"
        assertTrue(Pv.dark)
        Pv.mode = "light"
        assertFalse(Pv.dark)
        Pv.mode = "app"
        Pv.appDark = true
        assertTrue(Pv.dark)
        Pv.appDark = false
        assertFalse(Pv.dark)
        Pv.mode = "でたらめ"
        assertTrue(Pv.dark) // 知らない値は、ダーク(これまでどおり)
    }

    private fun check(mode: String) {
        Pv.mode = mode
        val bad = ArrayList<String>()
        fun need(name: String, fg: Color, bg: Color, min: Float) {
            val c = contrast(fg, bg)
            if (c < min) bad.add("$mode: $name のコントラスト比 %.2f(%.1f 以上が必要)".format(c, min))
        }
        // 本文・薄い文字・色分け・リンクは、背景の上で読める
        need("本文", Pv.text, Pv.bg, 7f)
        need("薄い文字", Pv.dim, Pv.bg, 4.5f)
        need("キーワード", Pv.keyword, Pv.bg, 4.5f)
        need("文字列", Pv.string, Pv.bg, 4.5f)
        need("コメント", Pv.comment, Pv.bg, 4.2f)
        need("数", Pv.number, Pv.bg, 4.5f)
        need("リンク", Pv.link, Pv.bg, 4.5f)
        need("強調", Pv.accent, Pv.bg, 4.5f)
        need("見出しの項目名", Pv.label, Pv.bg, 4.5f)
        need("注意", Pv.warn, Pv.bg, 4.5f)
        need("OK", Pv.ok, Pv.bg, 4.5f)
        need("注意(黄)", Pv.caution, Pv.bg, 4.5f)
        need("エラー", Pv.bad, Pv.bg, 4.0f)
        need("危険な権限", Pv.danger, Pv.bg, 4.0f)
        need("中立", Pv.neutral, Pv.bg, 4.0f)
        // コードの囲み・カードの中
        need("コード内の本文", Pv.text, Pv.codeBg, 7f)
        need("コード内のキーワード", Pv.keyword, Pv.codeBg, 4.2f)
        need("コード内の文字列", Pv.string, Pv.codeBg, 4.2f)
        need("コード内のコメント", Pv.comment, Pv.codeBg, 3.8f)
        need("コード内の数", Pv.number, Pv.codeBg, 4.2f)
        need("カードの本文", Pv.text, Pv.panel, 7f)
        need("カードの薄い文字", Pv.dim, Pv.panel, 4.5f)
        need("表の見出し", Pv.text, Pv.tableHeader, 7f)
        // 上のバー・検索バー(半透明なので、背景と重ねた色で測る)
        fun over(top: Color, base: Color): Color {
            val a = top.alpha
            return Color(top.red * a + base.red * (1 - a), top.green * a + base.green * (1 - a), top.blue * a + base.blue * (1 - a))
        }
        need("上のバーの文字", Pv.barText, over(Pv.bar, Pv.bg), 7f)
        need("上のバーの薄い文字", Pv.barSub, over(Pv.bar, Pv.bg), 4.5f)
        need("検索欄の文字", Pv.barText, Pv.searchField, 7f)
        need("検索バーの文字", Pv.barText, over(Pv.searchBg, Pv.bg), 7f)
        assertEquals(bad.joinToString("\n"), 0, bad.size)
    }

    @Test
    fun ダークの色は読める() = check("dark")

    @Test
    fun ライトの色は読める() = check("light")

    @Test
    fun ライトとダークで背景は明暗が逆() {
        Pv.mode = "dark"
        val d = lum(Pv.bg)
        Pv.mode = "light"
        val l = lum(Pv.bg)
        assertTrue(d < 0.1f && l > 0.8f)
    }
}
