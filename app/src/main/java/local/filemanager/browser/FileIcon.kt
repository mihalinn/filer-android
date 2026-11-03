package local.filemanager

import android.content.Context
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * サムネイル・APKのアイコン・フォルダの中身の数を、少ない本数のスレッドで読み込む。
 * 速くスクロールしても大量の読み込みが同時に走らないようにして、画面の動きを重くしない。
 */
private object Loader {
    val dispatcher = Executors.newFixedThreadPool(3).asCoroutineDispatcher()
    // 枚数ではなく使うメモリ(約32MB)で上限を決める。グリッド用の大きいサムネイルが混ざっても増えすぎない
    val bitmaps = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    val counts = LruCache<String, Int>(2000)
    private val small = Size(144, 144)
    private val large = Size(320, 320)

    fun bitmap(ctx: Context, item: FileItem, key: String, big: Boolean): Bitmap? {
        bitmaps.get(key)?.let { return it }
        val f = File(item.path)
        val size = if (big) large else small
        val bmp = try {
            when (item.kind) {
                FileKind.VIDEO -> ThumbnailUtils.createVideoThumbnail(f, size, null)
                FileKind.IMAGE ->
                    if (FileOps.extensionOf(item.name) == "svg") {
                        if (f.length() in 1..SvgRender.MAX_BYTES) SvgRender.render(f.readBytes(), size.width) else null
                    } else {
                        ThumbnailUtils.createImageThumbnail(f, size, null)
                    }
                FileKind.APK -> apkIcon(ctx, item.path)
                else -> null
            }
        } catch (e: Throwable) {
            null
        }
        if (bmp != null) bitmaps.put(key, bmp)
        return bmp
    }

    /** NAS 上の画像・動画のサムネイル。保存済みならそれを、無ければ NAS から作る。 */
    fun remoteBitmap(ctx: Context, fs: Fs, item: FileItem, key: String, big: Boolean): Bitmap? {
        bitmaps.get(key)?.let { return it }
        val bmp = Thumbs.load(ctx, fs, item, big)
        if (bmp != null) bitmaps.put(key, bmp)
        return bmp
    }

    private fun apkIcon(ctx: Context, path: String): Bitmap? {
        val pm = ctx.packageManager
        val info = pm.getPackageArchiveInfo(path, 0) ?: return null
        val ai = info.applicationInfo ?: return null
        ai.sourceDir = path
        ai.publicSourceDir = path
        return ai.loadIcon(pm).toBitmap(96, 96)
    }
}

private fun hasPicture(kind: FileKind) = kind == FileKind.IMAGE || kind == FileKind.VIDEO || kind == FileKind.APK

@Composable
private fun rememberPicture(item: FileItem, local: Boolean, big: Boolean, remoteFs: Fs?): ImageBitmap? {
    if (!hasPicture(item.kind)) return null
    // NAS のファイルは、サムネイルを出す設定のときだけ(remoteFs が渡されたときだけ)、画像と動画に限って読み込む
    if (!local && (remoteFs == null || (item.kind != FileKind.IMAGE && item.kind != FileKind.VIDEO))) return null
    val ctx = LocalContext.current
    val key = (if (local) "" else "${remoteFs!!.id}:") + "${item.path}@${item.modified}" + if (big) "#L" else ""
    val state = produceState<ImageBitmap?>(Loader.bitmaps.get(key)?.asImageBitmap(), key) {
        if (value == null) {
            value = if (local) {
                withContext(Loader.dispatcher) { Loader.bitmap(ctx, item, key, big) }?.asImageBitmap()
            } else {
                withContext(Thumbs.dispatcher) { Loader.remoteBitmap(ctx, remoteFs!!, item, key, big) }?.asImageBitmap()
            }
        }
    }
    return state.value
}

/** フォルダの中身の数。表示される行だけが数える(全部のフォルダを先に数えると遅いため)。 */
@Composable
fun rememberChildCount(item: FileItem, enabled: Boolean): Int? {
    if (!item.isDir || !enabled) return null
    if (item.childCount >= 0) return item.childCount
    // フォルダの更新日時が変われば(中身が増減すれば)数え直す
    val key = "${item.path}@${item.modified}"
    val state = produceState<Int?>(Loader.counts.get(key), key) {
        if (value == null) {
            val n = withContext(Loader.dispatcher) { FileOps.countChildren(File(item.path)) }
            Loader.counts.put(key, n)
            value = n
        }
    }
    return state.value
}

@Composable
/** big=true はグリッド用。サムネイルを大きく読み、拡張子の文字も大きくする。remoteFs は NAS のサムネイルを読むときだけ渡す。 */
fun FileIcon(item: FileItem, local: Boolean, modifier: Modifier = Modifier, big: Boolean = false, remoteFs: Fs? = null) {
    if (item.isDir) {
        FolderGlyph(kindColor(FileKind.FOLDER), modifier)
        return
    }
    val picture = rememberPicture(item, local, big, remoteFs)
    if (picture != null) {
        Image(
            bitmap = picture,
            contentDescription = null,
            contentScale = if (item.kind == FileKind.APK) ContentScale.Fit else ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(if (item.kind == FileKind.APK) 10.dp else 6.dp)),
        )
        return
    }
    val color = kindColor(item.kind)
    val ext = FileOps.extensionOf(item.name).uppercase().take(4)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // 右上の角を折った紙。種類の色で塗り、折り目は少し暗くする
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val l = w * 0.10f
            val r = w * 0.90f
            val t = h * 0.03f
            val b = h * 0.97f
            val fold = w * 0.26f
            val sheet = Path().apply {
                moveTo(l, t); lineTo(r - fold, t); lineTo(r, t + fold); lineTo(r, b); lineTo(l, b); close()
            }
            drawPath(sheet, color)
            val dark = color.deeper(0.86f)
            val corner = Path().apply { moveTo(r - fold, t); lineTo(r - fold, t + fold); lineTo(r, t + fold); close() }
            drawPath(corner, dark)
        }
        Text(
            text = ext,
            color = color.ink(0.62f),
            fontSize = if (big) (if (ext.length > 3) 14.sp else 18.sp) else (if (ext.length > 3) 9.sp else 11.sp),
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(top = if (big) 12.dp else 7.dp),
        )
    }
}

/** 淡い杏色のフォルダ。うしろの板だけ少し濃く、手前の板の上の縁に明るい線。 */
@Composable
fun FolderGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val r = CornerRadius(w * 0.10f, w * 0.10f)
        val back = color.deeper(0.88f)
        val edge = color.lighter(0.5f)
        // うしろの板(タブつき)
        drawRoundRect(back, Offset(0f, h * 0.12f), GSize(w * 0.40f, h * 0.24f), r)
        drawRoundRect(back, Offset(0f, h * 0.22f), GSize(w, h * 0.68f), r)
        // 手前の板(はっきりした色)と、上の縁の明るい線
        drawRoundRect(color, Offset(0f, h * 0.36f), GSize(w, h * 0.54f), r)
        drawRoundRect(edge, Offset(w * 0.04f, h * 0.38f), GSize(w * 0.92f, h * 0.035f), CornerRadius(1f))
    }
}
