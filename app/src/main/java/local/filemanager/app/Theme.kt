package local.filemanager

import android.app.Activity
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** 画面全体で使う色。ライトは、白に近い地に淡い藤色と桃色などを重ねた、パステル調。 */
@Immutable
class Pal(
    val dark: Boolean,
    val background: Color,
    val surface: Color,
    val text: Color,
    val subText: Color,
    val divider: Color,
    val topBar: Color,
    val actionBar: Color,
    /** 上のバー・下の操作バーの上に置く文字と記号の色 */
    val onBar: Color,
    val actionAccent: Color,
    /** 選んだ行の地(文字は text)。 */
    val selectedRow: Color,
    /** ファイルを選んでいる間の、上のバーの色(文字は onBar)。 */
    val selectBar: Color,
    val tileBg: Color,
    val tileBorder: Color,
    val accent: Color,
    /** accent の上に置く文字の色 */
    val onAccent: Color,
)

/** テーマカラー。藍が既定(パステル調)。ティールは、濃いバーに青緑の差し色。ワイン赤は、深い赤のバー。 */
enum class ThemeColor(val id: String, @StringRes val labelRes: Int) {
    INDIGO("indigo", R.string.theme_color_indigo),
    TEAL("teal", R.string.theme_color_teal),
    WINE("wine", R.string.theme_color_wine);

    val label: String get() = Txt(labelRes)

    companion object {
        fun of(id: String): ThemeColor = entries.firstOrNull { it.id == id } ?: INDIGO
    }
}

/** 見た目で、ファイルの種類の色を濃くするか(ティール・ワイン赤)。composition の最初に FmTheme が決める。 */
object KindPalette {
    @Volatile
    var saturated = false
}

internal fun palOf(c: ThemeColor, dark: Boolean): Pal = when (c) {
    ThemeColor.INDIGO -> if (dark) DarkPal else LightPal
    ThemeColor.TEAL -> if (dark) TealDark else TealLight
    ThemeColor.WINE -> if (dark) WineDark else WineLight
}

private val TealLight = Pal(
    dark = false,
    background = Color(0xFFFAFAFA),
    surface = Color.White,
    text = Color(0xFF212121),
    subText = Color(0xFF6B6B6B),
    divider = Color(0xFFD9D9D9),
    topBar = Color(0xFF1F1F1F),
    actionBar = Color(0xFF12303F),
    onBar = Color(0xFFF2F2F2),
    actionAccent = Color(0xFF3B9BD9),
    selectedRow = Color(0xFFBFE3DE),
    selectBar = Color(0xFF1B5E56),
    tileBg = Color(0xFFF0F4F3),
    tileBorder = Color(0xFFDDDDDD),
    accent = Color(0xFF00796B),
    onAccent = Color.White,
)

private val TealDark = Pal(
    dark = true,
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    text = Color(0xFFE6E6E6),
    subText = Color(0xFFA0A0A0),
    divider = Color(0xFF2F2F2F),
    topBar = Color(0xFF1F1F1F),
    actionBar = Color(0xFF12303F),
    onBar = Color(0xFFF2F2F2),
    actionAccent = Color(0xFF3B9BD9),
    selectedRow = Color(0xFF2E6E66),
    selectBar = Color(0xFF2E6E66),
    tileBg = Color(0xFF2A2A2A),
    tileBorder = Color(0xFF3A3A3A),
    accent = Color(0xFF66B9AD),
    onAccent = Color(0xFF003731),
)

private val WineLight = Pal(
    dark = false,
    background = Color(0xFFFAF7F6),
    surface = Color.White,
    text = Color(0xFF261A1A),
    subText = Color(0xFF6F5D5D),
    divider = Color(0xFFE8DEDD),
    topBar = Color(0xFFB71C1C),
    actionBar = Color(0xFF7F1D1D),
    onBar = Color(0xFFFFF3F0),
    actionAccent = Color(0xFFFFCC80),
    selectedRow = Color(0xFFF6D6D4),
    selectBar = Color(0xFF7F1D1D),
    tileBg = Color(0xFFFBEFEE),
    tileBorder = Color(0xFFF1DCDA),
    accent = Color(0xFFB71C1C),
    onAccent = Color.White,
)

private val WineDark = Pal(
    dark = true,
    background = Color(0xFF140E0E),
    surface = Color(0xFF211717),
    text = Color(0xFFEBDDDD),
    subText = Color(0xFFA89A9A),
    divider = Color(0xFF372A2A),
    topBar = Color(0xFF5C1414),
    actionBar = Color(0xFF430E0E),
    onBar = Color(0xFFF5E6E6),
    actionAccent = Color(0xFFFFB74D),
    selectedRow = Color(0xFF6B2626),
    selectBar = Color(0xFF6B2626),
    tileBg = Color(0xFF2B1D1D),
    tileBorder = Color(0xFF3A2828),
    accent = Color(0xFFEF9A9A),
    onAccent = Color(0xFF4A0E0E),
)

private fun schemeOf(p: Pal) = if (p.dark) {
    darkColorScheme(
        primary = p.accent, onPrimary = p.onAccent, primaryContainer = p.selectedRow, onPrimaryContainer = p.text,
        secondary = p.subText, background = p.background, onBackground = p.text, surface = p.surface, onSurface = p.text,
        surfaceVariant = p.tileBg, onSurfaceVariant = p.subText, outline = p.subText,
        surfaceContainerHigh = p.tileBg, surfaceContainer = p.surface,
    )
} else {
    lightColorScheme(
        primary = p.accent, onPrimary = p.onAccent, primaryContainer = p.selectedRow, onPrimaryContainer = p.text,
        secondary = p.subText, background = p.background, onBackground = p.text, surface = p.surface, onSurface = p.text,
        surfaceVariant = p.tileBg, onSurfaceVariant = p.subText, outline = p.subText,
        surfaceContainerHigh = p.surface, surfaceContainer = p.background,
    )
}

/** 色の明るさ(0〜1。WCAG の相対輝度)。 */
internal fun relLuminance(c: Color): Double {
    fun lin(v: Float): Double = v.toDouble().let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
    return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
}

/** 2 色の見分けやすさ(1〜21。文字は 4.5 以上が目安)。 */
internal fun contrastRatio(a: Color, b: Color): Double {
    val la = relLuminance(a)
    val lb = relLuminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

private val LightPal = Pal(
    dark = false,
    background = Color(0xFFF6F7FC),
    surface = Color.White,
    text = Color(0xFF2A2E47),
    subText = Color(0xFF7A7F9A),
    divider = Color(0xFFE7E9F4),
    topBar = Color(0xFFE9EDFD),
    actionBar = Color(0xFFE3E8FC),
    onBar = Color(0xFF2A2E47),
    actionAccent = Color(0xFFAAB6F3),
    selectedRow = Color(0xFFDFE5FF),
    selectBar = Color(0xFFDFE5FF),
    tileBg = Color(0xFFEEF1FC),
    tileBorder = Color(0xFFEEF1FC),
    accent = Color(0xFF5766D6),
    onAccent = Color.White,
)

private val DarkPal = Pal(
    dark = true,
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    text = Color(0xFFE6E6E6),
    subText = Color(0xFFA0A0A0),
    divider = Color(0xFF2F2F2F),
    topBar = Color(0xFF1B1F3E),
    actionBar = Color(0xFF141836),
    onBar = Color(0xFFE6E8F5),
    actionAccent = Color(0xFF7986CB),
    selectedRow = Color(0xFF3A4180),
    selectBar = Color(0xFF3A4180),
    tileBg = Color(0xFF262A45),
    tileBorder = Color(0xFF262A45),
    accent = Color(0xFF9FA8DA),
    onAccent = Color(0xFF141A52),
)

val LocalPal = staticCompositionLocalOf { LightPal }

private val LightScheme = lightColorScheme(
    primary = Color(0xFF5766D6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E5FF),
    onPrimaryContainer = Color(0xFF1A2270),
    secondary = Color(0xFF6B7090),
    background = Color(0xFFF6F7FC),
    onBackground = Color(0xFF2A2E47),
    surface = Color.White,
    onSurface = Color(0xFF2A2E47),
    surfaceVariant = Color(0xFFECEEF8),
    onSurfaceVariant = Color(0xFF5E6482),
    outline = Color(0xFF8A8FAB),
    surfaceContainerHigh = Color(0xFFFBFBFF),
    surfaceContainer = Color(0xFFF7F8FE),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFB4BBFF),
    onPrimary = Color(0xFF1A2478),
    primaryContainer = Color(0xFF31408F),
    onPrimaryContainer = Color(0xFFDDE0FF),
    secondary = Color(0xFFC3C5EA),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF2C2C2C),
    onSurfaceVariant = Color(0xFFBDBDBD),
    outline = Color(0xFF8A8A8A),
)

/** mode: "light"(既定) / "dark" / "system"(端末の設定に合わせる) */
@Composable
fun FmTheme(mode: String, color: String = "indigo", content: @Composable () -> Unit) {
    val dark = when (mode) {
        "dark" -> true
        "system" -> isSystemInDarkTheme()
        else -> false
    }
    val theme = ThemeColor.of(color)
    val pal = palOf(theme, dark)
    SideEffect { Pv.appDark = dark }
    // 子の画面より先に決める(SideEffect だと、切り替えた直後の 1 回は古い色で描かれる)
    KindPalette.saturated = theme != ThemeColor.INDIGO
    // バーが淡い色のときは、状態バー・ナビゲーションバーの記号を暗くする(濃い色のバーなら明るくする)
    val view = LocalView.current
    val lightStatus = relLuminance(pal.topBar) > 0.4
    val lightNav = relLuminance(pal.background) > 0.4
    DisposableEffect(lightStatus, lightNav) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val ctl = WindowCompat.getInsetsController(window, view)
            ctl.isAppearanceLightStatusBars = lightStatus
            ctl.isAppearanceLightNavigationBars = lightNav
        }
        onDispose {}
    }
    val scheme = when (theme) {
        ThemeColor.INDIGO -> if (dark) DarkScheme else LightScheme
        else -> schemeOf(pal)
    }
    CompositionLocalProvider(LocalPal provides pal) {
        MaterialTheme(colorScheme = scheme, shapes = AppShapes, content = content)
    }
}

/** 淡い色の上に置く記号や文字の色。黒に寄せると濁るので、同じ色合いを藍色へ寄せる。 */
fun Color.ink(f: Float = 0.55f) = androidx.compose.ui.graphics.lerp(this, Color(0xFF2A2E6E), f)

/** 色を暗くする(f=0〜1。小さいほど暗い) */
fun Color.deeper(f: Float) = Color(red = red * f, green = green * f, blue = blue * f, alpha = alpha)

/** 色を明るくする(f=0〜1。大きいほど白に近い) */
fun Color.lighter(f: Float) = Color(red = red + (1f - red) * f, green = green + (1f - green) * f, blue = blue + (1f - blue) * f, alpha = alpha)

fun kindColor(kind: FileKind): Color = if (KindPalette.saturated) saturatedKindColor(kind) else pastelKindColor(kind)

/** 濃いテーマ用の、はっきりした種類の色。 */
private fun saturatedKindColor(kind: FileKind): Color = when (kind) {
    FileKind.FOLDER -> Color(0xFFDDB06C)
    FileKind.IMAGE -> Color(0xFF943FE5)
    FileKind.VIDEO -> Color(0xFFD92525)
    FileKind.AUDIO -> Color(0xFF00B2AC)
    FileKind.DOCUMENT -> Color(0xFF2987D9)
    FileKind.ARCHIVE -> Color(0xFF6D7D7A)
    FileKind.APK -> Color(0xFFA0C639)
    FileKind.OTHER -> Color(0xFFB4BDBB)
}

private fun pastelKindColor(kind: FileKind): Color = when (kind) {
    FileKind.FOLDER -> Color(0xFFFFC26B)
    FileKind.IMAGE -> Color(0xFFF08FB4)
    FileKind.VIDEO -> Color(0xFFFF9E7A)
    FileKind.AUDIO -> Color(0xFF6EC6D8)
    FileKind.DOCUMENT -> Color(0xFF7F9CF5)
    FileKind.ARCHIVE -> Color(0xFFC2A58C)
    FileKind.APK -> Color(0xFF8DCB98)
    FileKind.OTHER -> Color(0xFFAEB4CE)
}

/** 角はあまり丸めない。ダイアログ(extraLarge)も含めて、控えめな丸みにそろえる。 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(12.dp),
)
