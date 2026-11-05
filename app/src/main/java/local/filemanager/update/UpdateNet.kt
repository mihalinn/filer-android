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
                throw IOException(Txt(R.string.update_net_001))
            }
            if (code == 404) return null
            if (code != 200) throw IOException(Txt(R.string.update_net_002, code))
            val text = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return UpdateLogic.parseLatest(text, repoUrl)
        } finally {
            c.disconnect()
        }
    }

    /** やり直しても直らない失敗(404 など、中止、SHA-256 の不一致)。 */
    private class Fatal(message: String) : IOException(message)

    /**
     * APK を dest にダウンロードする(途中は dest.part)。大きさと、説明に SHA-256 があればそれも確かめる。
     * 画面オフなどで通信が切れたときは、届いた所から続きを取り直す(maxAttempts 回まで、retryDelayMs ずつ待つ)。
     * onProgress(読んだ量, 全体)。isCancelled が true になったら中断して IOException。
     */
    fun download(
        info: ReleaseInfo, dest: File, onProgress: (Long, Long) -> Unit, isCancelled: () -> Boolean,
        maxAttempts: Int = 8, retryDelayMs: Long = 3_000L,
    ) {
        val part = File(dest.path + ".part")
        part.delete()
        try {
            var failures = 0
            while (true) {
                val before = part.length()
                try {
                    fetchRange(info, part, onProgress, isCancelled)
                    break
                } catch (e: Fatal) {
                    throw e
                } catch (e: IOException) {
                    if (isCancelled()) throw Fatal(Txt(R.string.update_net_003))
                    // 少しでも進んだなら、続きを取り直す回数は数え直す
                    if (part.length() > before) failures = 0
                    if (++failures >= maxAttempts) throw e
                    var waited = 0L
                    while (waited < retryDelayMs) {
                        if (isCancelled()) throw Fatal(Txt(R.string.update_net_004))
                        Thread.sleep(minOf(250L, retryDelayMs - waited))
                        waited += 250L
                    }
                }
            }
            if (info.apkSize > 0 && part.length() != info.apkSize) throw IOException(Txt(R.string.update_net_005))
            val sha = sha256Of(part)
            if (info.sha256 != null && !info.sha256.equals(sha, ignoreCase = true)) throw Fatal(Txt(R.string.update_net_006))
            dest.delete()
            if (!part.renameTo(dest)) throw IOException(Txt(R.string.update_net_007))
        } catch (e: Exception) {
            part.delete()
            throw e
        }
    }

    /** part に今ある分の続きを 1 回取る。サーバーが続きの指定(Range)に応じなければ、最初から取り直す。 */
    private fun fetchRange(info: ReleaseInfo, part: File, onProgress: (Long, Long) -> Unit, isCancelled: () -> Boolean) {
        var have = part.length()
        val c = open(info.apkUrl, "application/octet-stream")
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = try {
                c.responseCode
            } catch (e: IOException) {
                throw IOException(Txt(R.string.update_net_008))
            }
            if (code == 416) {
                // 続きの位置が合わない。最初から取り直す
                part.delete()
                throw IOException(Txt(R.string.update_net_009))
            }
            if (code != 200 && code != 206) {
                val msg = Txt(R.string.update_net_010, code)
                throw if (code in 400..499) Fatal(msg) else IOException(msg)
            }
            val append = code == 206 && have > 0
            if (!append) have = 0
            val total = if (append) have + c.contentLengthLong.coerceAtLeast(0) else c.contentLengthLong.takeIf { it > 0 } ?: info.apkSize
            var done = have
            c.inputStream.use { input ->
                java.io.FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) throw Fatal(Txt(R.string.update_net_011))
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            if (total > 0 && done < total) throw IOException(Txt(R.string.update_net_012))
        } finally {
            c.disconnect()
        }
    }

    private fun sha256Of(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}