package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OfficeThemeTest {
    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF
    private fun lum(c: Int) = (0.2126f * r(c) + 0.7152f * g(c) + 0.0722f * b(c)) / 255f

    @Test
    fun whiteAndBlackSwapToDarkAndLightGrays() {
        assertEquals(0xFF1A1A1A.toInt(), OfficeTheme.flip(0xFFFFFFFF.toInt()))
        assertEquals(0xFFE6E6E6.toInt(), OfficeTheme.flip(0xFF000000.toInt()))
    }

    @Test
    fun lightFillsBecomeDarkAndKeepTheirHue() {
        val yellow = OfficeTheme.flip(0xFFFFF2CC.toInt())
        assertTrue("薄い黄色は暗くなる", lum(yellow) < 0.3f)
        assertTrue("色相は黄色のまま(赤・緑が青より大きい)", r(yellow) > b(yellow) && g(yellow) > b(yellow))
        val blueText = OfficeTheme.flip(0xFF1F3864.toInt())
        assertTrue("濃い青い文字は明るくなる", lum(blueText) > 0.5f)
        assertTrue(b(blueText) > r(blueText))
    }

    @Test
    fun midTonesBarelyMove() {
        val red = OfficeTheme.flip(0xFFFF0000.toInt())
        assertEquals(255, r(red))
        assertTrue(g(red) < 5 && b(red) < 5)
        val gray = OfficeTheme.flip(0xFF808080.toInt())
        assertTrue(abs(r(gray) - 0x80) < 40)
    }

    @Test
    fun keepsAlphaAndContrastOrder() {
        assertEquals(0x80, (OfficeTheme.flip(0x80123456.toInt()) ushr 24))
        // 濃い字と薄い地の関係は、反転しても「地の方が暗い」に保たれる
        val ink = 0xFF222222.toInt()
        val paper = 0xFFF2F2F2.toInt()
        assertTrue(lum(OfficeTheme.flip(ink)) > lum(OfficeTheme.flip(paper)))
        // 明るさの順序が逆になる(単調)
        var prev = 2f
        for (v in 0..255 step 15) {
            val l = lum(OfficeTheme.flip((0xFF shl 24) or (v shl 16) or (v shl 8) or v))
            assertTrue(l < prev)
            prev = l
        }
    }

    @Test
    fun lightModeKeepsOriginalColors() {
        val c = OfficeColors(false)
        assertEquals(androidx.compose.ui.graphics.Color.White, c.paper)
        assertEquals(androidx.compose.ui.graphics.Color(0xFFE6E6E6.toInt()), c.backdrop)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF123456.toInt()), c.c(0xFF123456.toInt()))
        val d = OfficeColors(true)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF1A1A1A.toInt()), d.paper)
        assertEquals(androidx.compose.ui.graphics.Color(0xFFE6E6E6.toInt()), d.ink)
    }
}

class DarkFilterTest {
    /** ColorMatrix(4×5)を 0〜255 の (r,g,b) に当てる */
    private fun apply(r: Int, g: Int, b: Int): IntArray {
        val m = DarkFilter.MATRIX
        return IntArray(3) { row -> (m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * b + m[row * 5 + 4]).toInt().coerceIn(0, 255) }
    }

    @Test
    fun whiteBecomesDarkGrayAndBlackBecomesLightGray() {
        val w = apply(255, 255, 255)
        assertTrue(w.all { it in 25..27 })
        val k = apply(0, 0, 0)
        assertTrue(k.all { it in 229..231 })
    }

    @Test
    fun grayStaysGrayAndHueIsRoughlyKept() {
        val gray = apply(128, 128, 128)
        assertTrue(gray.max() - gray.min() <= 2)
        // 赤いものは、反転しても赤っぽいまま(赤が一番大きい)
        val red = apply(220, 30, 30)
        assertTrue(red[0] > red[1] && red[0] > red[2])
        assertEquals(1f, DarkFilter.MATRIX[18], 0f)
    }
}

class HtmlDarkTest {
    @Test
    fun injectsAfterHeadOpeningTag() {
        val out = HtmlDark.inject("<!doctype html><html><HEAD lang=x><title>t</title></head><body>a</body></html>")
        val i = out.indexOf("<style id=\"filer-dark\">")
        assertTrue(i > out.indexOf("<HEAD lang=x>"))
        assertTrue(i < out.indexOf("<title>"))
        assertTrue(out.contains("invert(1) hue-rotate(180deg)"))
    }

    @Test
    fun fallsBackToHtmlTagOrTop() {
        assertTrue(HtmlDark.inject("<html><body>x</body></html>").startsWith("<html><style"))
        assertTrue(HtmlDark.inject("just text").startsWith("<style"))
    }
}
