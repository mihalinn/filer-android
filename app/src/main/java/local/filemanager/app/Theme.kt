package local.filemanager

import android.app.Activity
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
    val selectedRow: Color,
    val tileBg: Color,
    val tileBorder: Color,
    val accent: Color,
    /** accent の上に置く文字の色 */
    val onAccent: Color,
)

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
fun FmTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) {
        "dark" -> true
        "system" -> isSystemInDarkTheme()
        else -> false
    }
    SideEffect { Pv.appDark = dark }
    // ライトのバーは淡い色なので、状態バー・ナビゲーションバーの記号は暗くする
    val view = LocalView.current
    DisposableEffect(dark) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val ctl = WindowCompat.getInsetsController(window, view)
            ctl.isAppearanceLightStatusBars = !dark
            ctl.isAppearanceLightNavigationBars = !dark
        }
        onDispose {}
    }
    CompositionLocalProvider(LocalPal provides (if (dark) DarkPal else LightPal)) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, shapes = AppShapes, content = content)
    }
}

/** 淡い色の上に置く記号や文字の色。黒に寄せると濁るので、同じ色合いを藍色へ寄せる。 */
fun Color.ink(f: Float = 0.55f) = androidx.compose.ui.graphics.lerp(this, Color(0xFF2A2E6E), f)

/** 色を暗くする(f=0〜1。小さいほど暗い) */
fun Color.deeper(f: Float) = Color(red = red * f, green = green * f, blue = blue * f, alpha = alpha)

/** 色を明るくする(f=0〜1。大きいほど白に近い) */
fun Color.lighter(f: Float) = Color(red = red + (1f - red) * f, green = green + (1f - green) * f, blue = blue + (1f - blue) * f, alpha = alpha)

fun kindColor(kind: FileKind): Color = when (kind) {
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
