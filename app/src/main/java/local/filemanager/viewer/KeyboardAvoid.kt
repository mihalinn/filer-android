package local.filemanager

import android.app.Activity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt

/**
 * キーボードの上に出す部品のための、下の余白。
 * `imePadding()` は、端末によって画面の枠そのものがキーボードの分だけ小さくなるとき、同じ高さをもう一度足してしまい、
 * 部品が画面の途中に浮く。そこで、囲いの底の画面上の位置と、キーボードの上端を比べて、重なっている分だけを余白にする。
 * 使い方: 囲い(画面いっぱいの部品)に [container] を付け、キーボードの上に置きたい部品に [bottom] を余白として付ける。
 */
internal class KeyboardAvoid(val container: Modifier, val bottom: Dp)

@Composable
internal fun rememberKeyboardAvoid(): KeyboardAvoid {
    val view = LocalView.current
    val density = LocalDensity.current
    val imePx = WindowInsets.ime.getBottom(density)
    val navPx = WindowInsets.navigationBars.getBottom(density)
    var containerBottom by remember { mutableIntStateOf(0) }
    // 枠が縮んでいても変わらない、画面全体の高さ
    val fullHeight = (view.context as? Activity)?.windowManager?.currentWindowMetrics?.bounds?.height() ?: view.rootView.height
    val overlap = if (imePx > 0) (containerBottom - (fullHeight - imePx)).coerceAtLeast(0) else 0
    val pad = if (overlap > 0) overlap else navPx
    val container = Modifier.onGloballyPositioned { c ->
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        containerBottom = loc[1] + c.boundsInWindow().bottom.roundToInt()
    }
    return KeyboardAvoid(container, with(density) { pad.toDp() })
}