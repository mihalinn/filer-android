package local.filemanager

import androidx.compose.animation.SplineBasedFloatDecayAnimationSpec
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.generateDecayAnimationSpec
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** つまんで拡大できる倍率の範囲 */
internal const val SHEET_ZOOM_MIN = 0.5f
internal const val SHEET_ZOOM_MAX = 4f

/** 表の中のスクロールの状態を、拡大のあとの位置合わせのために SheetGrid へ渡す受け口。表の描き方(2 種類)が、組み立てるときに入れる。 */
internal class GridScrollHandle {
    var hs: ScrollState? = null
    var list: LazyListState? = null

    /** 縦にスクロールする部分の上端(表の上端から。見出しと固定の行の高さ) */
    var lazyTopPx: Float = 0f
}

/** 1 つの方向の「流れ」。velocity は、スクロール量が増える向きを正とした速さ(px/秒)。apply は動かした量を返し、端に着いたら止める。 */
private suspend fun flingAxis(velocity: Float, spec: DecayAnimationSpec<Float>, apply: (Float) -> Float) {
    if (abs(velocity) < 50f) return
    var last = 0f
    AnimationState(0f, velocity).animateDecay(spec) {
        val delta = value - last
        last = value
        val used = apply(delta)
        if (abs(delta) > 0.5f && abs(used) < 0.5f) cancelAnimation()
    }
}

/** つまんだ瞬間の「どこを見ていたか」。拡大したあと、同じ場所が同じ位置に見えるようにスクロールを合わせる。 */
private class ZoomFix(
    val focus: Offset,
    val hsBefore: Int,
    val ratio: Float,
    val anchorIndex: Int?,
    val within: Float,
    val itemSize: Int,
    val focusLazyY: Float,
)

/**
 * 表の入り口。2 本の指でつまんで、拡大・縮小できる(0.5〜4 倍)。
 *
 * つまんでいる間は、表を作り直さず、いまの絵をそのまま拡大縮小して見せる(なめらか)。指を離したときに、
 * 新しい倍率で表を組み立て直し、つまんだ中心が動かないようにスクロール位置を合わせる。
 * 表そのものの描き方は FastGrid(CSV・Excel・SQLite の結果で共通)。
 */
@Composable
internal fun SheetGrid(
    model: GridModel, fontSp: Int, modifier: Modifier = Modifier, dark: Boolean = false, copyEnabled: Boolean = true,
    /** 編集のとき: 選んでいるセル(行, 列)と、セルを押したときの動き。onCellTap があると、タップは選択になる(全文の表示はしない)。 */
    selected: Pair<Int, Int>? = null,
    onCellTap: ((Int, Int) -> Unit)? = null,
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pinch by remember { mutableFloatStateOf(1f) }
    var pivot by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var fix by remember { mutableStateOf<ZoomFix?>(null) }
    val scroll = remember { GridScrollHandle() }

    // 新しい倍率で組み立て直したあとに、スクロール位置を合わせる
    LaunchedEffect(zoom) {
        val f = fix ?: return@LaunchedEffect
        fix = null
        withFrameNanos { }
        withFrameNanos { }
        scroll.hs?.scrollTo(((f.hsBefore + f.focus.x) * f.ratio - f.focus.x).roundToInt().coerceAtLeast(0))
        val list = scroll.list
        val idx = f.anchorIndex
        if (list != null && idx != null && f.itemSize > 0) {
            val newSize = f.itemSize * f.ratio
            // つまんだ場所の行の上端が、あるべき位置(表の縦スクロール部分の上端から t)
            val t = f.focusLazyY - f.within * f.ratio
            if (t <= 0f) {
                list.scrollToItem(idx, (-t).roundToInt())
            } else {
                val n = ceil(t / newSize).toInt()
                list.scrollToItem(maxOf(0, idx - n), (n * newSize - t).roundToInt())
            }
        }
    }

    /** 指を離したとき。つまんだ分を、本当の倍率にする。 */
    fun commit() {
        val newZoom = (zoom * pinch).coerceIn(SHEET_ZOOM_MIN, SHEET_ZOOM_MAX)
        val ratio = newZoom / zoom
        pinch = 1f
        if (abs(ratio - 1f) < 0.01f) return
        val list = scroll.list
        val yy = pivot.y - scroll.lazyTopPx
        val item = list?.layoutInfo?.visibleItemsInfo?.firstOrNull { yy >= it.offset && yy < it.offset + it.size }
        fix = ZoomFix(pivot, scroll.hs?.value ?: 0, ratio, item?.index, if (item != null) yy - item.offset else 0f, item?.size ?: 0, yy)
        zoom = newZoom
    }

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val decay = remember(density) { SplineBasedFloatDecayAnimationSpec(density).generateDecayAnimationSpec<Float>() }
    val fling = remember { arrayOfNulls<Job>(1) }

    /** 指を離したあと、勢いを残して流す(縦と横を同時に)。 */
    fun startFling(v: Velocity) {
        fling[0]?.cancel()
        fling[0] = scope.launch {
            launch { scroll.hs?.let { s -> flingAxis(-v.x, decay) { s.dispatchRawDelta(it) } } }
            launch { scroll.list?.let { s -> flingAxis(-v.y, decay) { s.dispatchRawDelta(it) } } }
        }
    }

    Box(
        modifier.clipToBounds().onSizeChanged { size = it }.pointerInput(Unit) {
            awaitEachGesture {
                // 子(縦・横それぞれのスクロール)より先に受ける。指 2 本ならつまむ、1 本なら縦横いっしょに(斜めにも)動かす。
                // 縦と横の別々のスクロールにまかせると、どちらか一方向にしか動かせず、斜めに動かせないため
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                fling[0]?.cancel()
                val slop = viewConfiguration.touchSlop
                var pinching = false
                var panning = false
                var travelled = Offset.Zero
                val tracker = VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.count { it.pressed } >= 2) {
                        if (!pinching) {
                            pinching = true
                            panning = false
                            pivot = event.calculateCentroid()
                        }
                        val z = event.calculateZoom()
                        if (z != 1f) pinch = (pinch * z).coerceIn(SHEET_ZOOM_MIN / zoom, SHEET_ZOOM_MAX / zoom)
                        event.changes.forEach { it.consume() }
                    } else if (!pinching) {
                        val ch = event.changes.firstOrNull { it.id == down.id }
                        if (ch != null && ch.pressed) {
                            val delta = ch.positionChange()
                            if (!panning) {
                                travelled += delta
                                if (travelled.getDistance() > slop) panning = true
                            }
                            if (panning) {
                                scroll.hs?.dispatchRawDelta(-delta.x)
                                scroll.list?.dispatchRawDelta(-delta.y)
                                tracker.addPosition(ch.uptimeMillis, ch.position)
                                ch.consume()
                            }
                        }
                    }
                } while (event.changes.any { it.pressed })
                if (pinching) commit() else if (panning) startFling(tracker.calculateVelocity())
            }
        },
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = pinch
                scaleY = pinch
                transformOrigin = TransformOrigin(pivot.x / size.width.coerceAtLeast(1), pivot.y / size.height.coerceAtLeast(1))
            },
        ) {
            FastGrid(model, fontSp, Modifier.fillMaxSize(), dark, copyEnabled, selected, onCellTap, zoom, scroll)
        }
    }
}
