package local.filemanager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/** 画像を、大きさを抑えて読み込む(縮小しながら読むので、大きな写真でもメモリを使いすぎない)。 */
object ImageDecode {
    /** NAS の画像をまるごと読み込むときの上限。これを超えるものはビューアでは開かず、他のアプリで開く。 */
    const val MAX_REMOTE_BYTES = 64L * 1024 * 1024

    /** サムネイル用に NAS から丸ごと読む上限。これより大きい画像は、中に埋め込まれた小さな画像だけを使う。 */
    const val MAX_THUMB_SOURCE_BYTES = 16L * 1024 * 1024

    /** 幅・高さの大きい方が limit 以下になる、最小の 2 のべき乗の縮小率。 */
    fun sampleFor(width: Int, height: Int, limit: Int): Int {
        var s = 1
        while (maxOf(width, height) / s > limit) s *= 2
        return s
    }

    fun decodeBytes(bytes: ByteArray, limit: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, limit) }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let { if (isHeif(bytes)) it else upright(it, orientationOf(ByteArrayInputStream(bytes))) }
        }
    } catch (e: Throwable) {
        null // OutOfMemoryError を含む。表示できないだけで、アプリは落とさない
    }

    fun decodeFile(path: String, limit: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, limit) }
            BitmapFactory.decodeFile(path, opts)?.let { if (isHeifFile(path)) it else upright(it, File(path).inputStream().use { s -> orientationOf(s) }) }
        }
    } catch (e: Throwable) {
        null
    }

    /** EXIF に埋め込まれた小さな画像(あれば)。ファイルの先頭付近だけを読めば取れる。 */
    fun embeddedThumbnail(stream: InputStream): Bitmap? = try {
        val exif = ExifInterface(stream)
        val bytes = exif.thumbnailBytes
        bytes?.let { b -> BitmapFactory.decodeByteArray(b, 0, b.size)?.let { upright(it, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) } }
    } catch (e: Throwable) {
        null
    }

    /** HEIF(.heic / .heif / .avif)か。向きは画像の中の回転情報(irot)で決まり、デコーダーが適用済みなので、EXIF の向きを重ねて回さない。 */
    internal fun isHeif(b: ByteArray): Boolean = b.size >= 12 && b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() && b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte() &&
        String(b, 8, 4, Charsets.ISO_8859_1).let { it.startsWith("hei") || it.startsWith("hev") || it == "mif1" || it == "msf1" || it == "avif" || it == "avis" }

    private fun isHeifFile(path: String): Boolean = try {
        val h = ByteArray(12)
        File(path).inputStream().use { it.read(h) }
        isHeif(h)
    } catch (e: Throwable) {
        false
    }

    private fun orientationOf(stream: InputStream): Int = try {
        ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: Throwable) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /** EXIF の向きに合わせて回転・反転した画像にする。 */
    private fun upright(bmp: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bmp
        }
        return try {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        } catch (e: Throwable) {
            bmp
        }
    }

    /** 長い辺が size 以下になるよう縮小する(拡大はしない)。 */
    fun fit(bmp: Bitmap, size: Int): Bitmap {
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= size) return bmp
        val scale = size.toFloat() / longest
        return Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * scale).toInt()), maxOf(1, (bmp.height * scale).toInt()), true)
    }
}
