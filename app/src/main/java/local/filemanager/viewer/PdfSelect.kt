package local.filemanager

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

private val SELECT_FILL = Color(0x663D8BFF)
private val HANDLE = Color(0xFF2F7DF6)

/**
 * PDF のページの上に重ねる、文字の選択の表示。選んだ範囲の色と、両端のつまみ(ドラッグで範囲を変える)。
 * k は 1pt あたりの画面のピクセル数(拡大前)、scale は画面の拡大(つまみの大きさを変えないために使う)。
 * つまみ以外に触れた操作は消費しないので、スクロールや拡大は今まで通りに動く。
 * つまみを動かしている間は dragging[0] を true にして、ページの横移動を止める。
 */
@Composable
internal fun PdfSelectionOverlay(
    lt: PdfPageText, range: IntRange, k: Float, scale: Float, dragging: BooleanArray, onRange: (IntRange) -> Unit,
) {
    val ltNow by rememberUpdatedState(lt)
    val rangeNow by rememberUpdatedState(range)
    val kNow by rememberUpdatedState(k)
    val scaleNow by rememberUpdatedState(scale)
    val onRangeNow by rememberUpdatedState(onRange)
    Canvas(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val text = ltNow
                val r = rangeNow
                val first = text.chars.getOrNull(r.first) ?: return@awaitEachGesture
                val last = text.chars.getOrNull(r.last) ?: return@awaitEachGesture
                val rad = HANDLE_RADIUS.dp.toPx() / scaleNow
                val kk = kNow
                val startC = Offset(first.x * kk, (first.y + first.h) * kk + rad)
                val endC = Offset((last.x + last.w) * kk, (last.y + last.h) * kk + rad)
                val slop = 26.dp.toPx() / scaleNow
                val ds = (down.position - startC).getDistance()
                val de = (down.position - endC).getDistance()
                if (min(ds, de) > slop) return@awaitEachGesture
                val anchor = if (ds <= de) r.last else r.first
                down.consume()
                dragging[0] = true
                try {
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        ch.consume()
                        if (!ch.pressed) break
                        // 指はつまみ(文字の下)にあるので、文字の行は少し上
                        val i = text.nearest(ch.position.x / kNow, (ch.position.y - rad * 2.2f) / kNow)
                        if (i >= 0) onRangeNow(min(anchor, i)..max(anchor, i))
                    }
                } finally {
                    dragging[0] = false
                }
            }
        },
    ) {
        val kk = kNow
        val r = rangeNow
        val chars = ltNow.chars
        if (r.isEmpty() || r.first !in chars.indices || r.last !in chars.indices) return@Canvas
        // 同じ行の続きは 1 つの帯にして塗る
        var c0 = chars[r.first]
        var l = c0.x
        var rr = c0.x + c0.w
        val t = { c: PdfChar -> c.y }
        fun band(c: PdfChar, left: Float, right: Float) {
            drawRect(SELECT_FILL, Offset(left * kk, c.y * kk), Size((right - left) * kk, c.h * kk))
        }
        for (i in r.first + 1..r.last) {
            val c = chars[i]
            if (!chars[i - 1].lineEnd && t(c) == t(c0)) {
                l = min(l, c.x)
                rr = max(rr, c.x + c.w)
            } else {
                band(c0, l, rr)
                c0 = c; l = c.x; rr = c.x + c.w
            }
        }
        band(c0, l, rr)
        val rad = HANDLE_RADIUS.dp.toPx() / scaleNow
        val first = chars[r.first]
        val last = chars[r.last]
        val sx = first.x * kk
        val sy = (first.y + first.h) * kk
        val ex = (last.x + last.w) * kk
        val ey = (last.y + last.h) * kk
        val stem = 1.5.dp.toPx() / scaleNow
        drawRect(HANDLE, Offset(sx - stem / 2, sy), Size(stem, rad))
        drawCircle(HANDLE, rad, Offset(sx, sy + rad))
        drawRect(HANDLE, Offset(ex - stem / 2, ey), Size(stem, rad))
        drawCircle(HANDLE, rad, Offset(ex, ey + rad))
    }
}

private const val HANDLE_RADIUS = 8
