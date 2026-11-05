package local.filemanager

import kotlin.math.abs
import kotlin.math.roundToInt

/** 写真の情報(EXIF)の値を、読みやすい文字にする。Android に依存しない。 */
object ExifFormat {
    /** 「24/1」「0.004」「2.8」のような値を数にする。読めなければ null。 */
    fun number(s: String?): Double? {
        val t = s?.trim() ?: return null
        if (t.isEmpty()) return null
        val slash = t.indexOf('/')
        if (slash > 0) {
            val a = t.substring(0, slash).trim().toDoubleOrNull() ?: return null
            val b = t.substring(slash + 1).trim().toDoubleOrNull() ?: return null
            return if (b == 0.0) null else a / b
        }
        return t.toDoubleOrNull()
    }

    private fun plain(v: Double, digits: Int = 1): String {
        val s = "%.${digits}f".format(v)
        return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
    }

    /** シャッター速度: 0.004 → 「1/250 秒」、2 → 「2 秒」 */
    fun exposure(s: String?): String? {
        val v = number(s) ?: return null
        if (v <= 0.0) return null
        return if (v < 0.95) Txt(R.string.exif_shutter_fraction, (1.0 / v).roundToInt()) else Txt(R.string.exif_shutter_seconds, plain(v))
    }

    fun fNumber(s: String?): String? = number(s)?.takeIf { it > 0 }?.let { "f/${plain(it)}" }

    fun focalLength(s: String?, in35: String? = null): String? {
        val v = number(s)?.takeIf { it > 0 } ?: return null
        val eq = number(in35)?.takeIf { it > 0 }
        return "${plain(v)} mm" + if (eq != null && abs(eq - v) > 0.5) Txt(R.string.exif_format_001, plain(eq, 0)) else ""
    }

    fun iso(s: String?): String? = s?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.let { "ISO $it" }

    fun exposureBias(s: String?): String? {
        val v = number(s) ?: return null
        return if (abs(v) < 0.05) "±0 EV" else "%+.1f EV".format(v)
    }

    /** 「2026:10:25 09:30:15」→「2026/10/25 09:30:15」 */
    fun date(s: String?): String? {
        val t = s?.trim() ?: return null
        if (t.isEmpty() || t.startsWith("0000")) return null
        val m = Regex("^(\\d{4}):(\\d{2}):(\\d{2})[ T](\\d{2}:\\d{2}:\\d{2})").find(t) ?: return t
        return "${m.groupValues[1]}/${m.groupValues[2]}/${m.groupValues[3]} ${m.groupValues[4]}"
    }

    fun orientation(v: Int): String? = when (v) {
        1 -> null // 標準(出さない)
        2 -> Txt(R.string.exif_format_002)
        3 -> Txt(R.string.exif_format_003)
        4 -> Txt(R.string.exif_format_004)
        5 -> Txt(R.string.exif_format_005)
        6 -> Txt(R.string.exif_format_006)
        7 -> Txt(R.string.exif_format_007)
        8 -> Txt(R.string.exif_format_008)
        else -> null
    }

    fun flash(v: Int?): String? = when {
        v == null || v < 0 -> null
        v and 1 == 1 -> Txt(R.string.exif_format_009)
        else -> Txt(R.string.exif_format_010)
    }

    fun megapixels(w: Int, h: Int): String = Txt(R.string.exif_format_011).format(w.toLong() * h / 1_000_000.0)

    /** 緯度・経度 → 「北緯 35.65875°」「東経 139.74556°」 */
    fun latitude(v: Double): String = (if (v >= 0) Txt(R.string.exif_format_012) else Txt(R.string.exif_format_013)) + "%.5f°".format(abs(v))

    fun longitude(v: Double): String = (if (v >= 0) Txt(R.string.exif_format_014) else Txt(R.string.exif_format_015)) + "%.5f°".format(abs(v))

    /** 地図アプリを開くための URI */
    fun geoUri(lat: Double, lon: Double): String = "geo:%.6f,%.6f?q=%.6f,%.6f".format(java.util.Locale.US, lat, lon, lat, lon)
}
