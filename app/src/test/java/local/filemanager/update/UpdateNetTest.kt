package local.filemanager

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/** APK のダウンロード(UpdateNet.download)の、途中で切れたときの取り直しなどを、手元の小さなサーバーで確かめる。 */
class UpdateNetTest {
    /** 1 回の要求。range は Range ヘッダーの値(なければ null)、hit は何回目の要求か。 */
    private class Req(val range: String?, val hit: Int, val out: OutputStream)

    private lateinit var socket: ServerSocket
    private lateinit var dir: File
    private val body = ByteArray(300_000) { (it * 31 + 7).toByte() }
    private val hits = AtomicInteger()
    private val ranges = java.util.Collections.synchronizedList(ArrayList<String?>())

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("updnet").toFile()
        socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
    }

    @After
    fun tearDown() {
        runCatching { socket.close() }
        dir.deleteRecursively()
    }

    /** 要求を 1 件ずつ handler に渡す(1 回答えたら接続を閉じる)。 */
    private fun serve(handler: (Req) -> Unit) {
        Thread {
            while (!socket.isClosed) {
                try {
                    val s = socket.accept()
                    s.use {
                        val input = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        var range: String? = null
                        input.readLine() // 要求の 1 行目
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
                        }
                        val n = hits.incrementAndGet()
                        ranges.add(range)
                        runCatching { handler(Req(range, n, it.getOutputStream())) }
                    }
                } catch (e: IOException) {
                    // 閉じられた
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun info(sha: String? = sha(body)) = ReleaseInfo(
        tag = "v9.9.9", version = "9.9.9", title = "t", notes = "", pageUrl = "", apkName = "a.apk",
        apkUrl = "http://127.0.0.1:${socket.localPort}/a.apk", apkSize = body.size.toLong(), sha256 = sha,
    )

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun head(out: OutputStream, status: String, length: Int, extra: String = "") {
        out.write("HTTP/1.1 $status\r\nContent-Length: $length\r\n${extra}Connection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
    }

    private fun full(r: Req) {
        head(r.out, "200 OK", body.size)
        r.out.write(body)
        r.out.flush()
    }

    /** 全体の長さを知らせたうえで、途中までしか送らずに切る。 */
    private fun cutAfter(r: Req, bytes: Int) {
        head(r.out, "200 OK", body.size)
        r.out.write(body, 0, bytes)
        r.out.flush()
    }

    private fun range(r: Req) {
        val from = r.range!!.removePrefix("bytes=").removeSuffix("-").toInt()
        head(r.out, "206 Partial Content", body.size - from, "Content-Range: bytes $from-${body.size - 1}/${body.size}\r\n")
        r.out.write(body, from, body.size - from)
        r.out.flush()
    }

    @Test
    fun resumesFromWhereItStopped() {
        serve { r -> if (r.range != null) range(r) else if (r.hit == 1) cutAfter(r, body.size / 2) else full(r) }
        val dest = File(dir, "a.apk")
        var lastDone = 0L
        UpdateNet.download(info(), dest, { done, _ -> lastDone = done }, { false }, maxAttempts = 3, retryDelayMs = 10)
        assertArrayEquals(body, dest.readBytes())
        assertEquals(body.size.toLong(), lastDone)
        assertEquals(2, hits.get())
        assertNull(ranges[0])
        assertTrue(ranges[1]!!.startsWith("bytes=") && ranges[1] != "bytes=0-")
        assertFalse(File(dir, "a.apk.part").exists())
    }

    @Test
    fun restartsWhenServerIgnoresRange() {
        serve { r -> if (r.hit == 1) cutAfter(r, 1000) else full(r) } // 2 回目は Range を無視して全部返す
        val dest = File(dir, "a.apk")
        UpdateNet.download(info(), dest, { _, _ -> }, { false }, maxAttempts = 3, retryDelayMs = 10)
        assertArrayEquals(body, dest.readBytes())
        assertEquals(2, hits.get())
    }

    @Test
    fun givesUpAfterMaxAttempts() {
        serve { r -> head(r.out, "200 OK", body.size); r.out.flush() } // 本体を送らずに切る
        val dest = File(dir, "a.apk")
        try {
            UpdateNet.download(info(), dest, { _, _ -> }, { false }, maxAttempts = 3, retryDelayMs = 10)
            fail("失敗するはず")
        } catch (e: IOException) {
            // ok
        }
        assertEquals(3, hits.get())
        assertFalse(dest.exists())
        assertFalse(File(dir, "a.apk.part").exists())
    }

    @Test
    fun notFoundIsNotRetried() {
        serve { r -> head(r.out, "404 Not Found", 0); r.out.flush() }
        try {
            UpdateNet.download(info(), File(dir, "a.apk"), { _, _ -> }, { false }, maxAttempts = 5, retryDelayMs = 10)
            fail("失敗するはず")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("404"))
        }
        assertEquals(1, hits.get())
    }

    @Test
    fun wrongShaIsNotKept() {
        serve { r -> full(r) }
        val dest = File(dir, "a.apk")
        try {
            UpdateNet.download(info(sha = "0".repeat(64)), dest, { _, _ -> }, { false }, maxAttempts = 3, retryDelayMs = 10)
            fail("失敗するはず")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("SHA-256"))
        }
        assertEquals(1, hits.get())
        assertFalse(dest.exists())
    }

    @Test
    fun cancelStopsWithoutRetry() {
        serve { r -> full(r) }
        var cancelled = false
        try {
            UpdateNet.download(info(), File(dir, "a.apk"), { done, _ -> if (done > 1000) cancelled = true }, { cancelled }, maxAttempts = 5, retryDelayMs = 10)
            fail("中断されるはず")
        } catch (e: IOException) {
            assertEquals("中止しました", e.message)
        }
        assertEquals(1, hits.get())
        assertFalse(File(dir, "a.apk.part").exists())
    }
}
