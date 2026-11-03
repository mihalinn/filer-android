package local.filemanager

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger

class ReadAheadTest {
    private val chunk = 256 * 1024

    /** 遅れ(latencyMs)のある、メモリ上のファイル。maxPer は 1 回に返す上限(サーバーの上限の真似)。 */
    private class Source(val data: ByteArray, val latencyMs: Long = 0, val maxPer: Int = Int.MAX_VALUE) {
        val calls = AtomicInteger()
        var failAt = -1
        fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
            val n = calls.incrementAndGet()
            if (latencyMs > 0) Thread.sleep(latencyMs)
            if (n == failAt) throw IOException("切れた")
            if (pos >= data.size) return -1
            val k = minOf(len, maxPer, data.size - pos.toInt())
            System.arraycopy(data, pos.toInt(), buf, off, k)
            return k
        }
    }

    private fun bytes(n: Int) = ByteArray(n).also { Random(42).nextBytes(it) }

    private fun readAll(ra: ReadAhead, bufSize: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(bufSize)
        var pos = 0L
        while (true) {
            val n = ra.readAt(pos, buf, 0, buf.size)
            if (n < 0) break
            out.write(buf, 0, n)
            pos += n
        }
        return out.toByteArray()
    }

    @Test
    fun readsTheWholeFileInOrderWhateverTheBufferSize() {
        val data = bytes(5 * chunk + 12345)
        for (size in listOf(1000, 8192, chunk, 700_000)) {
            val s = Source(data)
            val ra = ReadAhead(chunk, 4, s::read)
            assertArrayEquals("buf=$size", data, readAll(ra, size))
            ra.close()
        }
    }

    @Test
    fun copesWithServersThatReturnLessThanAsked() {
        val data = bytes(3 * chunk + 777)
        val s = Source(data, maxPer = 65536)
        val ra = ReadAhead(chunk, 4, s::read)
        assertArrayEquals(data, readAll(ra, 50_000))
    }

    @Test
    fun emptyAndExactMultipleFiles() {
        assertEquals(0, readAll(ReadAhead(chunk, 4, Source(ByteArray(0))::read), 4096).size)
        val exact = bytes(2 * chunk)
        assertArrayEquals(exact, readAll(ReadAhead(chunk, 4, Source(exact)::read), 4096))
    }

    @Test
    fun peekingAtTheStartReadsOnlyOnce() {
        val s = Source(bytes(10 * chunk))
        val ra = ReadAhead(chunk, 4, s::read)
        val b = ByteArray(2)
        assertEquals(2, ra.readAt(0, b, 0, 2))
        ra.close()
        assertEquals(1, s.calls.get())
    }

    @Test
    fun aFailureIsRecoveredByTheResumingInputOnTheSamePosition() {
        val data = bytes(6 * chunk + 5)
        val s = Source(data).also { it.failAt = 4 }
        val ra = ReadAhead(chunk, 4, s::read)
        val input = ResumingInput(
            chunk = chunk, retries = 3, wait = {}, isTransient = { true }, fail = { it },
            readAt = { pos, buf, off, len -> ra.readAt(pos, buf, off, len) },
            reconnect = { ra.reset() }, onClose = { ra.close() },
        )
        val got = input.use { it.readBytes() }
        assertArrayEquals(data, got)
    }

    @Test
    fun readingAheadIsMuchFasterWhenEachRequestHasToWaitForAnAnswer() {
        val data = bytes(24 * chunk)
        fun timeIt(depth: Int): Long {
            val ra = ReadAhead(chunk, depth, Source(data, latencyMs = 20)::read)
            val t = System.nanoTime()
            val got = readAll(ra, chunk)
            val ms = (System.nanoTime() - t) / 1_000_000
            assertEquals(data.size, got.size)
            ra.close()
            return ms
        }
        val serial = timeIt(1)
        val ahead = timeIt(4)
        assertTrue("先読みなし ${serial}ms / あり ${ahead}ms", ahead < serial * 0.6)
    }
}
