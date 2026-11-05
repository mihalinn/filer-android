package local.filemanager

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

class PhotoInfo(val rows: List<Pair<String, String>>, val lat: Double?, val lon: Double?)

object PhotoInfoReader {
    /** EXIF は、たいてい先頭の数十 KB にある。大きな写真でも、先頭の数 MB だけ読めばよい。 */
    private const val HEAD_BYTES = 4 * 1024 * 1024

    fun read(fs: Fs, item: FileItem): PhotoInfo {
        val head = fs.open(item.path).use { TextDecode.readUpTo(it, HEAD_BYTES).first }
        val rows = ArrayList<Pair<String, String>>()
        rows.add(Txt(R.string.photo_info_001) to item.name)
        rows.add(Txt(R.string.photo_info_002) to FileOps.formatSize(item.size))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(head, 0, head.size, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            rows.add(Txt(R.string.photo_info_003) to "${bounds.outWidth} × ${bounds.outHeight}(${ExifFormat.megapixels(bounds.outWidth, bounds.outHeight)})")
        }
        val exif = try {
            ExifInterface(ByteArrayInputStream(head))
        } catch (e: Exception) {
            null
        }
        if (exif == null) return PhotoInfo(rows, null, null)
        fun add(label: String, v: String?) {
            if (!v.isNullOrBlank()) rows.add(label to v)
        }
        val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()
        val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
        add(Txt(R.string.photo_info_004), listOfNotNull(make?.takeIf { it.isNotEmpty() }, model?.takeIf { it.isNotEmpty() && (make == null || !it.startsWith(make)) }).joinToString(" "))
        add(Txt(R.string.photo_info_005), exif.getAttribute(ExifInterface.TAG_LENS_MODEL))
        add(Txt(R.string.photo_info_006), ExifFormat.date(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME)))
        add(Txt(R.string.photo_info_007), ExifFormat.exposure(exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)))
        add(Txt(R.string.photo_info_008), ExifFormat.fNumber(exif.getAttribute(ExifInterface.TAG_F_NUMBER)))
        add(Txt(R.string.photo_info_009), ExifFormat.iso(exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)))
        add(Txt(R.string.photo_info_010), ExifFormat.focalLength(exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH), exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)))
        add(Txt(R.string.photo_info_011), ExifFormat.exposureBias(exif.getAttribute(ExifInterface.TAG_EXPOSURE_BIAS_VALUE)))
        add(Txt(R.string.photo_info_012), ExifFormat.flash(exif.getAttribute(ExifInterface.TAG_FLASH)?.toIntOrNull()))
        add(Txt(R.string.photo_info_013), ExifFormat.orientation(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)))
        add(Txt(R.string.photo_info_014), exif.getAttribute(ExifInterface.TAG_SOFTWARE))
        var lat: Double? = null
        var lon: Double? = null
        val ll = exif.latLong
        if (ll != null && ll.size == 2) {
            lat = ll[0]
            lon = ll[1]
            rows.add(Txt(R.string.photo_info_015) to "${ExifFormat.latitude(ll[0])}  ${ExifFormat.longitude(ll[1])}")
            val alt = exif.getAltitude(Double.NaN)
            if (!alt.isNaN()) rows.add(Txt(R.string.photo_info_016) to "%.1f m".format(alt))
        }
        if (rows.size <= 4 && lat == null) rows.add(Txt(R.string.photo_info_017) to Txt(R.string.photo_info_018))
        return PhotoInfo(rows, lat, lon)
    }
}

/** 写真の情報(カメラ・撮影日時・露出・場所など)。場所があれば、地図アプリで開ける。 */
@Composable
internal fun PhotoInfoDialog(fs: Fs, item: FileItem, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val info by produceState<Result<PhotoInfo>?>(null, item.path) {
        value = withContext(Dispatchers.IO) { runCatching { PhotoInfoReader.read(fs, item) } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Txt(R.string.photo_info_019)) },
        text = {
            val r = info
            when {
                r == null -> CircularProgressIndicator()
                r.isFailure -> Text(r.exceptionOrNull()?.message ?: Txt(R.string.photo_info_020))
                else -> SelectionContainer {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        for ((label, value) in r.getOrThrow().rows) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.width(104.dp))
                                Text(value, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(Txt(R.string.photo_info_021)) } },
        dismissButton = {
            val p = info?.getOrNull()
            if (p?.lat != null && p.lon != null) {
                TextButton(onClick = {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ExifFormat.geoUri(p.lat, p.lon))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text(Txt(R.string.photo_info_022)) }
            }
        },
    )
}
