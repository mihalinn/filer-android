package local.filemanager

import java.security.MessageDigest

/** APK の詳細表示で使う、画面に依存しない小さな部品。 */
object ApkFormat {
    /** 署名の証明書のハッシュ(SHA-256)を、「AB:CD:…」の形にする。 */
    fun fingerprint(certBytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(certBytes).joinToString(":") { "%02X".format(it) }

    enum class Compare { NEWER, SAME, OLDER }

    /** 入れようとしている APK の版(apk)が、いま入っているもの(installed)より新しいか。 */
    fun compare(apk: Long, installed: Long): Compare = when {
        apk > installed -> Compare.NEWER
        apk == installed -> Compare.SAME
        else -> Compare.OLDER
    }

    /** いま入っているアプリの署名と、APK の署名が同じか(同じでないと、上書きインストールはできない)。どちらかが空なら null(分からない)。 */
    fun sameSigner(apkSigners: Collection<String>, installedSigners: Collection<String>): Boolean? {
        if (apkSigners.isEmpty() || installedSigners.isEmpty()) return null
        return apkSigners.any { it in installedSigners }
    }

    /** 「android.permission.READ_CONTACTS」→「READ_CONTACTS」(短い名前)。 */
    fun shortPermission(name: String): String = name.removePrefix("android.permission.").removePrefix("com.android.launcher.permission.")

    /** SDK の番号から、Android の版の名前。 */
    fun androidVersion(sdk: Int): String = when (sdk) {
        21 -> "5.0"; 22 -> "5.1"; 23 -> "6.0"; 24 -> "7.0"; 25 -> "7.1"; 26 -> "8.0"; 27 -> "8.1"; 28 -> "9"
        29 -> "10"; 30 -> "11"; 31 -> "12"; 32 -> "12L"; 33 -> "13"; 34 -> "14"; 35 -> "15"; 36 -> "16"
        else -> ""
    }

    fun sdkText(sdk: Int): String {
        val v = androidVersion(sdk)
        return if (v.isEmpty()) "API $sdk" else "Android $v(API $sdk)"
    }
}
