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
        return if (v < 0.95) "1/${(1.0 / v).roundToInt()} 秒" else "${plain(v)} 秒"
    }

    fun fNumber(s: String?): String? = number(s)?.takeIf { it > 0 }?.let { "f/${plain(it)}" }

    fun focalLength(s: String?, in35: String? = null): String? {
        val v = number(s)?.takeIf { it > 0 } ?: return null
        val eq = number(in35)?.takeIf { it > 0 }
        return "${plain(v)} mm" + if (eq != null && abs(eq - v) > 0.5) "(35mm換算 ${plain(eq, 0)} mm)" else ""
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
        2 -> "左右反転"
        3 -> "180度回転"
        4 -> "上下反転"
        5 -> "転置"
        6 -> "右に90度回転(縦向きで撮影)"
        7 -> "逆転置"
        8 -> "左に90度回転(縦向きで撮影)"
        else -> null
    }

    fun flash(v: Int?): String? = when {
        v == null || v < 0 -> null
        v and 1 == 1 -> "発光した"
        else -> "発光しなかった"
    }

    fun megapixels(w: Int, h: Int): String = "%.1f メガピクセル".format(w.toLong() * h / 1_000_000.0)

    /** 緯度・経度 → 「北緯 35.65875°」「東経 139.74556°」 */
    fun latitude(v: Double): String = (if (v >= 0) "北緯 " else "南緯 ") + "%.5f°".format(abs(v))

    fun longitude(v: Double): String = (if (v >= 0) "東経 " else "西経 ") + "%.5f°".format(abs(v))

    /** 地図アプリを開くための URI */
    fun geoUri(lat: Double, lon: Double): String = "geo:%.6f,%.6f?q=%.6f,%.6f".format(java.util.Locale.US, lat, lon, lat, lon)
}
