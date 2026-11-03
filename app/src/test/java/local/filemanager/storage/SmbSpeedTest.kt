package local.filemanager

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 実際の SMB サーバーからの読み込み速度を、先読みなし(1)と先読みあり(既定)で比べる。
 * 環境変数 FILER_SMB_HOST が無いときは何もしない。FILER_SMB_SHARE / FILER_SMB_USER / FILER_SMB_PASS はスモークテストと同じ。
 * FILER_SMB_BIG=読むファイル(共有の中のパス)。省略すると、共有の中から 40MB 以上のファイルを探す。
 * 結果は FILER_SMB_REPORT(省略時は build/smb_speed.txt)に書く。
 */
class SmbSpeedTest {
    private fun fs(): SmbFs {
        val host = System.getenv("FILER_SMB_HOST")
        assumeTrue(host != null)
        return SmbFs(
            RemoteConfig(
                id = "speed", name = "speed", host = host!!, share = System.getenv("FILER_SMB_SHARE") ?: "storage",
                user = System.getenv("FILER_SMB_USER") ?: "", password = System.getenv("FILER_SMB_PASS") ?: "",
            ),
        )
    }

    private fun findBig(fs: SmbFs): FileItem? {
        val queue = ArrayDeque<String>().apply { addLast("") }
        var seen = 0
        while (queue.isNotEmpty() && seen < 400) {
            val dir = queue.removeFirst()
            for (it in runCatching { fs.list(dir) }.getOrNull().orEmpty()) {
                seen++
                if (it.isDir) queue.addLast(it.path) else if (it.size >= 40L * 1024 * 1024) return it
            }
        }
        return null
    }

    private fun measure(fs: SmbFs, item: FileItem, depth: Int, limit: Long): Pair<Double, Long> {
        fs.readAheadDepth = depth
        val buf = ByteArray(256 * 1024)
        var total = 0L
        val t = System.nanoTime()
        fs.open(item.path).use { s ->
            while (total < limit) {
                val n = s.read(buf)
                if (n < 0) break
                total += n
            }
        }
        val sec = (System.nanoTime() - t) / 1e9
        return (total / 1048576.0 / sec) to total
    }

    /** 一時ファイルを書いて測り、すぐ消す。FILER_SMB_WRITE=1 のときだけ動く。 */
    private fun measureWrite(fs: SmbFs, depth: Int, bytes: Long): Double {
        fs.writeBehindDepth = depth
        val dir = System.getenv("FILER_SMB_WRITE_DIR")?.trim('/')?.let { "$it/" } ?: ""
        val path = "${dir}filer_speedtest_${System.nanoTime()}.tmp"
        val buf = ByteArray(1024 * 1024) { (it * 31).toByte() }
        val t = System.nanoTime()
        try {
            fs.create(path).use { o ->
                var done = 0L
                while (done < bytes) {
                    o.write(buf, 0, buf.size)
                    done += buf.size
                }
            }
            val sec = (System.nanoTime() - t) / 1e9
            check(fs.stat(path)?.size == bytes) { "書いた大きさが違う" }
            return bytes / 1048576.0 / sec
        } finally {
            runCatching { fs.delete(path) }
        }
    }

    @Test
    fun writeSpeedWithAndWithoutWriteBehind() {
        assumeTrue(System.getenv("FILER_SMB_WRITE") == "1")
        val fs = fs()
        val bytes = 64L * 1024 * 1024
        val sb = StringBuilder("書き込み ${bytes / 1048576} MB\n")
        measureWrite(fs, 1, 8L * 1024 * 1024)
        repeat(2) { round ->
            for (depth in listOf(1, 2, 4, 6)) {
                val mbps = measureWrite(fs, depth, bytes)
                sb.append("  ${round + 1}回目 並列 $depth: %.1f MB/s(%.0f Mbps)\n".format(mbps, mbps * 8))
            }
        }
        val out = File(System.getenv("FILER_SMB_REPORT_WRITE") ?: "build/smb_speed_write.txt")
        out.parentFile?.mkdirs()
        out.writeText(sb.toString())
        println(sb)
        fs.close()
    }

    @Test
    fun readSpeedWithAndWithoutReadAhead() {
        val fs = fs()
        val item = System.getenv("FILER_SMB_BIG")?.let { fs.stat(it) } ?: findBig(fs)
        assumeTrue("大きいファイルが見つかりません", item != null)
        val limit = minOf(item!!.size, 96L * 1024 * 1024)
        val sb = StringBuilder("ファイル: ${item.path}(${item.size / 1048576} MB)。先頭 ${limit / 1048576} MB を読む\n")
        // 1 回目は接続の準備や NAS のキャッシュの影響があるので、捨てる
        measure(fs, item, 1, 8L * 1024 * 1024)
        repeat(2) { round ->
            for (depth in listOf(1, 2, 4, 6)) {
                val (mbps, bytes) = measure(fs, item, depth, limit)
                sb.append("  ${round + 1}回目 先読み $depth: %.1f MB/s(%.0f Mbps)  %d MB\n".format(mbps, mbps * 8, bytes / 1048576))
            }
        }
        val out = File(System.getenv("FILER_SMB_REPORT") ?: "build/smb_speed.txt")
        out.parentFile?.mkdirs()
        out.writeText(sb.toString())
        println(sb)
        fs.close()
    }
}
