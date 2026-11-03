package local.filemanager

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** GitHub への接続(最新のリリースの確認と、APK のダウンロード)。確認のときだけ github.com に接続する。 */
object UpdateNet {
    private const val TIMEOUT_MS = 15_000

    private fun open(url: String, accept: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("User-Agent", "Filer-Android")
        return c
    }

    /** 最新のリリース。まだリリースが無いときは null。つながらないときは IOException(そのまま出せる文)。 */
    fun fetchLatest(repoUrl: String): ReleaseInfo? {
        val path = repoUrl.removePrefix("https://github.com/")
        val c = open("https://api.github.com/repos/$path/releases/latest", "application/vnd.github+json")
        try {
            val code = try {
                c.responseCode
            } catch (e: IOException) {
                throw IOException("GitHub に接続できませんでした。通信の状態を確認してください")
            }
            if (code == 404) return null
            if (code != 200) throw IOException("GitHub が応答しませんでした($code)。しばらくしてから、もう一度試してください")
            val text = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return UpdateLogic.parseLatest(text, repoUrl)
        } finally {
            c.disconnect()
        }
    }

    /**
     * APK を dest にダウンロードする(途中は dest.part)。大きさと、説明に SHA-256 があればそれも確かめる。
     * onProgress(読んだ量, 全体)。isCancelled が true になったら中断して IOException。
     */
    fun download(info: ReleaseInfo, dest: File, onProgress: (Long, Long) -> Unit, isCancelled: () -> Boolean) {
        val part = File(dest.path + ".part")
        part.delete()
        val c = open(info.apkUrl, "application/octet-stream")
        try {
            val code = try {
                c.responseCode
            } catch (e: IOException) {
                throw IOException("ダウンロードできませんでした。通信の状態を確認してください")
            }
            if (code != 200) throw IOException("ダウンロードできませんでした($code)")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: info.apkSize
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) throw IOException("中止しました")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            if (info.apkSize > 0 && done != info.apkSize) throw IOException("ダウンロードが途中で止まりました。もう一度試してください")
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            if (info.sha256 != null && info.sha256 != sha) throw IOException("ダウンロードしたファイルが、公開されている SHA-256 と違います。インストールを中止しました")
            dest.delete()
            if (!part.renameTo(dest)) throw IOException("ダウンロードしたファイルを保存できませんでした")
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            c.disconnect()
        }
    }
}