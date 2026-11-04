package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeColorTest {
    private fun check(name: String, ratio: Double, min: Double, failures: MutableList<String>) {
        if (ratio < min) failures.add("$name = ${"%.2f".format(ratio)} (< $min)")
    }

    /** 新しいテーマ(ティール・ワイン赤)は、ライト・ダークとも、文字が読める濃さになっていること */
    @Test
    fun newThemesAreReadable() {
        val failures = mutableListOf<String>()
        for (c in listOf(ThemeColor.TEAL, ThemeColor.WINE)) {
            for (dark in listOf(false, true)) {
                val p = palOf(c, dark)
                val n = "${c.id}/${if (dark) "dark" else "light"}"
                check("$n text/background", contrastRatio(p.text, p.background), 7.0, failures)
                check("$n text/surface", contrastRatio(p.text, p.surface), 7.0, failures)
                check("$n text/selectedRow", contrastRatio(p.text, p.selectedRow), 4.5, failures)
                check("$n text/tileBg", contrastRatio(p.text, p.tileBg), 7.0, failures)
                check("$n subText/background", contrastRatio(p.subText, p.background), 4.5, failures)
                check("$n subText/surface", contrastRatio(p.subText, p.surface), 4.5, failures)
                check("$n subText/tileBg", contrastRatio(p.subText, p.tileBg), 4.5, failures)
                check("$n onBar/topBar", contrastRatio(p.onBar, p.topBar), 4.5, failures)
                check("$n onBar/actionBar", contrastRatio(p.onBar, p.actionBar), 4.5, failures)
                check("$n onBar/selectBar", contrastRatio(p.onBar, p.selectBar), 4.5, failures)
                check("$n onAccent/accent", contrastRatio(p.onAccent, p.accent), 4.5, failures)
                check("$n accent/surface", contrastRatio(p.accent, p.surface), 4.5, failures)
                check("$n accent/background", contrastRatio(p.accent, p.background), 4.5, failures)
                check("$n actionAccent/actionBar", contrastRatio(p.actionAccent, p.actionBar), 3.0, failures)
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun unknownIdFallsBackToIndigo() {
        assertEquals(ThemeColor.INDIGO, ThemeColor.of("???"))
        assertEquals(ThemeColor.WINE, ThemeColor.of("wine"))
        assertEquals(ThemeColor.entries.size, ThemeColor.entries.map { it.id }.toSet().size)
    }

    @Test
    fun luminanceEndpoints() {
        assertEquals(21.0, contrastRatio(androidx.compose.ui.graphics.Color.White, androidx.compose.ui.graphics.Color.Black), 0.01)
    }
}
