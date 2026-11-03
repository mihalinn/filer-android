package local.filemanager

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger

class WriteBehindTest {
    private val chunk = 256 * 1024

    /** 遅れ(latencyMs)のある、メモリ上のファイル。failOn に入っている番号の書き込みは、半分だけ書けて切れる。 */
    private class Sink(size: Int, val latencyMs: Long = 0, val failOn: Set<Int> = emptySet()) {
        val data = ByteArray(size)
        val calls = AtomicInteger()
        val running = AtomicInteger()
        val maxRunning = AtomicInteger()
        fun write(pos: Long, buf: ByteArray, off: Int, len: Int) {
            val n = calls.incrementAndGet()
            val r = running.incrementAndGet()
            maxRunning.accumulateAndGet(r) { a, b -> maxOf(a, b) }
            try {
                if (latencyMs > 0) Thread.sleep(latencyMs)
                if (n in failOn) {
                    System.arraycopy(buf, off, data, pos.toInt(), len / 2)
                    throw IOException("切れた")
                }
                System.arraycopy(buf, off, data, pos.toInt(), len)
            } finally {
                running.decrementAndGet()
            }
        }
    }

    private fun bytes(n: Int) = ByteArray(n).also { Random(7).nextBytes(it) }

    private fun out(
        s: Sink, depth: Int = 4, retries: Int = 6, transient: Boolean = true,
        reconnect: () -> Unit = {}, closed: () -> Unit = {},
    ) = WriteBehind(
        chunk = chunk, depth = depth, retries = retries, wait = {}, isTransient = { transient }, fail = { it },
        writeAt = s::write, reconnect = reconnect, onClose = closed,
    )

    @Test
    fun writesEverythingInOrderWhateverTheWriteSize() {
        val data = bytes(5 * chunk + 12345)
        for (size in listOf(1, 1000, 8192, chunk, 700_000)) {
            val s = Sink(data.size)
            val o = out(s)
            var pos = 0
            if (size == 1) {
                // 1 バイトずつは遅いので、先頭の少しだけ 1 バイトで書く
                for (i in 0 until 3000) o.write(data[i].toInt())
                pos = 3000
            }
            while (pos < data.size) {
                val n = minOf(size, data.size - pos)
                o.write(data, pos, n)
                pos += n
            }
            assertEquals(data.size.toLong(), o.position)
            o.close()
            assertArrayEquals("書き方 $size", data, s.data)
        }
    }

    @Test
    fun closeWaitsForTheLastWrites() {
        val data = bytes(3 * chunk + 5)
        val s = Sink(data.size, latencyMs = 60)
        val o = out(s)
        o.write(data, 0, data.size)
        o.close()
        assertArrayEquals(data, s.data)
        assertEquals(0, s.running.get())
    }

    @Test
    fun severalWritesRunAtOnceAndNeverMoreThanDepth() {
        val data = bytes(12 * chunk)
        val s = Sink(data.size, latencyMs = 30)
        val o = out(s, depth = 4)
        o.write(data, 0, data.size)
        o.close()
        assertArrayEquals(data, s.data)
        assertTrue("同時に動いた数: ${s.maxRunning.get()}", s.maxRunning.get() in 2..4)
    }

    @Test
    fun depthOneWritesOneAtATime() {
        val data = bytes(6 * chunk)
        val s = Sink(data.size, latencyMs = 10)
        val o = out(s, depth = 1)
        o.write(data, 0, data.size)
        o.close()
        assertArrayEquals(data, s.data)
        assertEquals(1, s.maxRunning.get())
    }

    @Test
    fun isFasterThanOneAtATimeWhenTheLineIsSlowToAnswer() {
        val data = bytes(16 * chunk)
        fun time(depth: Int): Long {
            val s = Sink(data.size, latencyMs = 40)
            val t = System.nanoTime()
            out(s, depth = depth).use { it.write(data, 0, data.size) }
            assertArrayEquals(data, s.data)
            return (System.nanoTime() - t) / 1_000_000
        }
        val one = time(1)
        val four = time(4)
        assertTrue("1: ${one}ms / 4: ${four}ms", four * 2 < one)
    }

    @Test
    fun resumesAfterBreaksAndTheFileComesOutRight() {
        val data = bytes(10 * chunk + 999)
        // 途中の書き込みが何度か切れる(書き直すと同じ内容で上書きされる)
        val s = Sink(data.size, latencyMs = 5, failOn = setOf(2, 3, 9, 15))
        var reconnects = 0
        val o = out(s, reconnect = { reconnects++ })
        o.write(data, 0, 400_000)
        o.write(data, 400_000, data.size - 400_000)
        o.close()
        assertArrayEquals(data, s.data)
        assertTrue("つなぎ直した回数: $reconnects", reconnects in 1..4)
        assertEquals(0, s.running.get())
    }

    @Test
    fun givesUpAfterTheRetriesAreUsedUp() {
        val data = bytes(4 * chunk)
        val s = Sink(data.size, failOn = (1..100).toSet())
        var closes = 0
        val o = out(s, retries = 2, closed = { closes++ })
        try {
            o.write(data, 0, data.size)
            o.close()
            fail()
        } catch (e: IOException) {
            assertEquals("切れた", e.message)
        }
        o.close()
        assertEquals(1, closes)
        assertEquals(0, s.running.get())
    }

    @Test
    fun aFailureThatRetryingCannotFixStopsAtOnce() {
        val data = bytes(4 * chunk)
        val s = Sink(data.size, failOn = setOf(1))
        var reconnects = 0
        val o = out(s, transient = false, reconnect = { reconnects++ })
        try {
            o.write(data, 0, data.size)
            o.close()
            fail()
        } catch (e: IOException) {
            assertEquals("切れた", e.message)
        }
        assertEquals(0, reconnects)
        try {
            o.write(1)
            fail()
        } catch (e: IOException) {
            assertTrue(e.message!!.isNotEmpty())
        }
    }

    @Test
    fun emptyFileAndDoubleCloseAreFine() {
        val s = Sink(0)
        var closes = 0
        val o = out(s, closed = { closes++ })
        o.close()
        o.close()
        assertEquals(1, closes)
        assertEquals(0, s.calls.get())
    }
}
