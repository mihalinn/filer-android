package local.filemanager

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.widget.ImageView
import androidx.compose.runtime.key
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

// ---------------------------------------------------------------- 画像

internal sealed interface ImageLoad {
    data object Busy : ImageLoad
    data class Ok(val bitmap: ImageBitmap) : ImageLoad

    /** 動く画像(GIF・アニメーション WebP)。 */
    data class Anim(val drawable: AnimatedImageDrawable) : ImageLoad
    data class Fail(val message: String) : ImageLoad
}

/** 動く画像なら、動くままの絵にして返す。動かない・読めないときは null(静止画として読み直す)。 */
private fun loadAnimated(fs: Fs, item: FileItem): ImageLoad? = try {
    val source = when {
        fs.isLocal -> ImageDecoder.createSource(File(item.path))
        item.size > ImageDecode.MAX_REMOTE_BYTES -> null
        else -> ImageDecoder.createSource(ByteBuffer.wrap(fs.open(item.path).use { it.readBytes() }))
    }
    if (source == null) {
        null
    } else {
        val d = ImageDecoder.decodeDrawable(source) { dec, info, _ ->
            dec.setTargetSampleSize(ImageDecode.sampleFor(info.size.width, info.size.height, 2048))
        }
        if (d is AnimatedImageDrawable) {
            d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            ImageLoad.Anim(d)
        } else {
            null
        }
    }
} catch (e: Throwable) {
    null // OutOfMemoryError を含む。静止画としての表示に切り替える
}

/** 表示に使う縮小後の大きさの上限(長い辺)。GPU の上限(多くは 4096)に収まる値。 */
private const val VIEW_LIMIT = 3072

private fun loadFull(fs: Fs, item: FileItem): ImageLoad {
    val ext = FileOps.extensionOf(item.name)
    if (ext == "svg") return loadSvg(fs, item)
    if (ext == "gif" || ext == "webp") loadAnimated(fs, item)?.let { return it }
    if (fs.isLocal) {
        return ImageDecode.decodeFile(item.path, VIEW_LIMIT)?.let { ImageLoad.Ok(it.asImageBitmap()) }
            ?: ImageLoad.Fail(Txt(R.string.image_ui_001))
    }
    if (item.size > ImageDecode.MAX_REMOTE_BYTES) {
        return ImageLoad.Fail(Txt(R.string.image_ui_002))
    }
    val bytes = try {
        fs.open(item.path).use { it.readBytes() }
    } catch (e: Exception) {
        return ImageLoad.Fail(e.message ?: Txt(R.string.image_ui_003))
    }
    return ImageDecode.decodeBytes(bytes, VIEW_LIMIT)?.let { ImageLoad.Ok(it.asImageBitmap()) }
        ?: ImageLoad.Fail(Txt(R.string.image_ui_004))
}

/** SVG を、白い背景の絵にして見せる。 */
private fun loadSvg(fs: Fs, item: FileItem): ImageLoad {
    if (item.size > SvgRender.MAX_BYTES) return ImageLoad.Fail(Txt(R.string.image_ui_005, SvgRender.MAX_BYTES / 1024 / 1024))
    val bytes = try {
        fs.open(item.path).use { it.readBytes() }
    } catch (e: Exception) {
        return ImageLoad.Fail(e.message ?: Txt(R.string.image_ui_006))
    }
    val bmp = SvgRender.render(bytes, VIEW_LIMIT.coerceAtMost(2560))
        ?: return ImageLoad.Fail(Txt(R.string.image_ui_007))
    return ImageLoad.Ok(bmp.asImageBitmap())
}

@Composable
internal fun ZoomableImage(fs: Fs, item: FileItem, active: Boolean, onTap: () -> Unit) =
    ZoomableLoaded(item.path, { loadFull(fs, item) }, active, onTap)

/** 拡大・移動できる画像。load は読み込み(別のスレッドで呼ぶ)。key が変わったら読み直す。 */
@Composable
internal fun ZoomableLoaded(key: Any, load: () -> ImageLoad, active: Boolean, onTap: () -> Unit) {
    val state = produceState<ImageLoad>(ImageLoad.Busy, key) {
        value = withContext(Dispatchers.IO) { load() }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    Box(
        Modifier.fillMaxSize().clipToBounds().onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                // 2本指のピンチか、拡大中のドラッグだけを受け止める。等倍のときの横ドラッグは、前後の画像へのスワイプに譲る
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val multi = event.changes.count { it.pressed } > 1
                        if (multi || scale > 1f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            val mx = box.width * (scale - 1f) / 2f
                            val my = box.height * (scale - 1f) / 2f
                            offset = Offset((offset.x + pan.x).coerceIn(-mx, mx), (offset.y + pan.y).coerceIn(-my, my))
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (scale < 1.05f) {
                        scale = 1f
                        offset = Offset.Zero
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (val s = state.value) {
            ImageLoad.Busy -> CircularProgressIndicator(color = Color.White)
            is ImageLoad.Fail -> Text(s.message, color = Color.White, modifier = Modifier.padding(32.dp))
            is ImageLoad.Anim -> {
                // 表示中のページだけ動かす(横にめくった先で動き続けないように)
                DisposableEffect(s.drawable, active) {
                    if (active) s.drawable.start() else s.drawable.stop()
                    onDispose { s.drawable.stop() }
                }
                AndroidView(
                    factory = { c -> ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageDrawable(s.drawable) } },
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                )
            }
            is ImageLoad.Ok -> Image(
                bitmap = s.bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            )
        }
    }
}
