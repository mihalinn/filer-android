package local.filemanager

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class ResumeStreamsTest {
    private val data = ByteArray(1_000_000) { (it * 31 + it / 7).toByte() }

    /** failAt に入っている回数目の読み取りで IOException を出す(つなぎ直しを数える)。 */
    private class Flaky(val data: ByteArray, val failOnCalls: Set<Int>) {
        var calls = 0
        var reconnects = 0
        var waits = 0
        fun readAt(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
            calls++
            if (calls in failOnCalls) throw IOException("切れた")
            if (pos >= data.size) return -1
            val n = minOf(len, (data.size - pos).toInt())
            System.arraycopy(data, pos.toInt(), buf, off, n)
            return n
        }
    }

    private fun input(f: Flaky, retries: Int = 6, chunk: Int = 64 * 1024, transient: (Exception) -> Boolean = { true }) = ResumingInput(
        chunk, retries, { f.waits++ }, transient, { it }, f::readAt, { f.reconnects++ }, {},
    )

    @Test
    fun readsEverythingInChunks() {
        val f = Flaky(data, emptySet())
        val out = input(f).use { it.readBytes() }
        assertArrayEquals(data, out)
        assertEquals(0, f.reconnects)
    }

    @Test
    fun resumesAfterDroppedConnections() {
        val f = Flaky(data, setOf(3, 4, 9, 15, 16))
        val out = input(f).use { it.readBytes() }
        assertArrayEquals("切れても同じ中身になる", data, out)
        assertEquals(5, f.reconnects)
        assertEquals(5, f.waits)
    }

    @Test
    fun givesUpAfterRetries() {
        val f = Flaky(data, (2..100).toSet())
        val i = input(f, retries = 3)
        i.read(ByteArray(10), 0, 10)
        try {
            i.read(ByteArray(10), 0, 10)
            fail()
        } catch (e: IOException) {
            assertEquals(3, f.reconnects)
        }
    }

    @Test
    fun nonTransientFailureIsNotRetried() {
        val f = Flaky(data, setOf(1))
        val i = input(f, transient = { false })
        try {
            i.read(ByteArray(10), 0, 10)
            fail()
        } catch (e: IOException) {
            assertEquals(0, f.reconnects)
        }
    }

    @Test
    fun singleByteReadsWork() {
        val f = Flaky(byteArrayOf(1, 2, 3), emptySet())
        val i = input(f)
        assertEquals(listOf(1, 2, 3, -1), listOf(i.read(), i.read(), i.read(), i.read()))
    }

    @Test
    fun outputResumesAtTheLastConfirmedPosition() {
        val sink = ByteArray(data.size)
        var calls = 0
        var reconnects = 0
        val out = ResumingOutput(
            chunk = 50_000, retries = 6, wait = {}, isTransient = { true }, fail = { it },
            writeAt = { pos, buf, off, len ->
                calls++
                if (calls == 4 || calls == 5 || calls == 12) {
                    // 半分だけ書けて切れた(書き直すと同じ内容で上書きされる)
                    System.arraycopy(buf, off, sink, pos.toInt(), len / 2)
                    throw IOException("切れた")
                }
                System.arraycopy(buf, off, sink, pos.toInt(), len)
            },
            reconnect = { reconnects++ }, onClose = {},
        )
        out.write(data, 0, 400_000)
        out.write(data, 400_000, 600_000)
        out.close()
        assertArrayEquals(data, sink)
        assertEquals(3, reconnects)
        assertEquals(1_000_000L, out.position)
    }

    @Test
    fun closeIsCalledOnce() {
        var closes = 0
        val i = ResumingInput(10, 1, {}, { true }, { it }, { _, _, _, _ -> -1 }, {}, { closes++ })
        i.close(); i.close()
        assertEquals(1, closes)
        try {
            i.read(ByteArray(1), 0, 1)
            fail()
        } catch (e: IOException) {
            assertTrue(e.message!!.isNotEmpty())
        }
    }
}
