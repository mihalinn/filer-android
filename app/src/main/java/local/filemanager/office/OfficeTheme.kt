package local.filemanager

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Excel・Word などの暗い配色。文書の色は、明るさだけを反転して(白 → 暗い灰色、黒 → 明るい灰色)、色相と鮮やかさは変えない。
 * 文字と背景の両方を同じ規則で反転するので、読みやすさの関係(濃い字 + 薄い地)はそのまま保たれる。画像はそのまま。
 */
internal object OfficeTheme {
    /** 明るさ L(0〜1)を、0.90 − 0.80 × L にする。白は 0.10(#1A1A1A)、黒は 0.90(#E6E6E6)、中間の色はほとんど動かない。 */
    private fun flipLightness(l: Float): Float = 0.90f - 0.80f * l

    fun flip(argb: Int): Int {
        val a = (argb ushr 24) and 0xFF
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2f
        val d = mx - mn
        var h = 0f
        var s = 0f
        if (d > 1e-6f) {
            s = d / (1f - abs(2f * l - 1f))
            h = when (mx) {
                r -> ((g - b) / d).mod(6f)
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            } * 60f
        }
        val l2 = flipLightness(l)
        val c = (1f - abs(2f * l2 - 1f)) * s.coerceIn(0f, 1f)
        val x = c * (1f - abs((h / 60f).mod(2f) - 1f))
        val m = l2 - c / 2f
        val (r1, g1, b1) = when ((h / 60f).toInt().coerceIn(0, 5)) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(v: Float) = ((v + m) * 255f + 0.5005f).toInt().coerceIn(0, 255) // 浮動小数点の誤差で 1 ずれないよう、わずかに上乗せする
        return (a shl 24) or (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1)
    }
}

/** Office の表示で使う色。dark のときは、文書の色(c)を反転し、背景は暗い灰色にする。 */
internal class OfficeColors(val dark: Boolean) {
    fun c(argb: Int): Color = Color(if (dark) OfficeTheme.flip(argb) else argb)

    /** 紙・セルの地の色(白) */
    val paper: Color = c(0xFFFFFFFF.toInt())

    /** 紙のまわり(Word・スライドの背景) */
    val backdrop: Color = if (dark) Color(0xFF0E0E0E) else Color(0xFFE6E6E6)

    /** 色の指定が無い文字(自動色 = 黒) */
    val ink: Color = c(0xFF000000.toInt())
    val gridLine: Color = c(0xFFD4D4D4.toInt())
    val headerFill: Color = c(0xFFEDEDED.toInt())
    val headerText: Color = c(0xFF555555.toInt())
    val strongLine: Color = c(0xFF8C8C8C.toInt())
    val frozenLine: Color = c(0xFF9E9E9E.toInt())
    val tabBar: Color = c(0xFFF1F1F1.toInt())
    val tabBorder: Color = c(0xFFBDBDBD.toInt())
    val tabText: Color = c(0xFF555555.toInt())
    val accent: Color = c(0xFF217346.toInt())
    val link: Color = c(0xFF0563C1.toInt())
    val note: Color = c(0xFF444444.toInt())
    val slideTitle: Color = c(0xFF1F3864.toInt())
    val slideBody: Color = c(0xFF222222.toInt())
    val slideNumber: Color = c(0xFF8C8C8C.toInt())
}

/**
 * 絵として描かれたもの(PDF のページ、図面、ウェブページ)を暗くするための「反転 + 色相を半回転」。
 * 白 → 暗い灰色(#1A1A1A)、黒 → 明るい灰色(#E6E6E6)で、色合いは近いまま。Android / Compose の ColorMatrix(4×5、並びは行ごと)。
 */
internal object DarkFilter {
    val MATRIX: FloatArray = run {
        // CSS の hue-rotate(180deg) の行列。各行の合計は 1
        val h = floatArrayOf(
            -0.574f, 1.430f, 0.144f,
            0.426f, 0.430f, 0.144f,
            0.426f, 1.430f, -0.856f,
        )
        val k = (230f - 26f) / 255f // 0〜255 を 26〜230 に縮める
        val m = FloatArray(20)
        for (row in 0 until 3) {
            for (col in 0 until 3) m[row * 5 + col] = -h[row * 3 + col] * k
            m[row * 5 + 4] = 26f + 255f * k // 255 − H·in を縮めたときの足し算の分
        }
        m[18] = 1f // 透明度はそのまま
        m
    }
}

/** HTML ページを暗くする。ページの色をまるごと反転し、画像・動画はもう一度反転して元の色に戻す(JavaScript は使わない)。 */
internal object HtmlDark {
    private const val CSS = "html{filter:invert(1) hue-rotate(180deg) !important;background:#fff !important}" +
        "img,video,picture,canvas,iframe,svg image,object,embed{filter:invert(1) hue-rotate(180deg) !important}"
    private val HEAD = Regex("<head[^>]*>", RegexOption.IGNORE_CASE)
    private val HTML = Regex("<html[^>]*>", RegexOption.IGNORE_CASE)

    fun inject(html: String): String {
        val style = "<style id=\"filer-dark\">$CSS</style>"
        HEAD.find(html)?.let { return html.substring(0, it.range.last + 1) + style + html.substring(it.range.last + 1) }
        HTML.find(html)?.let { return html.substring(0, it.range.last + 1) + style + html.substring(it.range.last + 1) }
        return style + html
    }
}
