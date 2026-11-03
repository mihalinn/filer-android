package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** 読んだ位置と回数を数えられる、メモリ上の RandomSource。 */
private class CountingSource(val data: ByteArray, private val limitPerRead: Int = Int.MAX_VALUE) : RandomSource {
    override val size = data.size.toLong()
    var reads = 0
    var closed = false
    override fun readAt(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        reads++
        if (pos >= data.size) return -1
        val n = minOf(len, limitPerRead, (data.size - pos).toInt())
        System.arraycopy(data, pos.toInt(), buf, off, n)
        return n
    }

    override fun close() {
        closed = true
    }
}

class MediaSupportTest {
    private fun bytes(n: Int) = ByteArray(n) { (it % 253).toByte() }

    private fun readAll(r: ChunkedReader, step: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(step)
        while (true) {
            val n = r.read(buf, 0, buf.size)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    @Test
    fun 先頭から最後まで読める() {
        val data = bytes(2_000_000)
        val r = ChunkedReader(CountingSource(data), 0, -1)
        assertTrue(data.contentEquals(readAll(r, 64 * 1024)))
        assertEquals(0L, r.unread)
    }

    @Test
    fun 小さく刻んで読んでも_通信はまとめて行われる() {
        val data = bytes(1_500_000)
        val src = CountingSource(data)
        val r = ChunkedReader(src, 0, -1, chunkSize = 512 * 1024)
        assertTrue(data.contentEquals(readAll(r, 100)))
        assertEquals(3, src.reads) // 512KB 単位で 3 回(1.5MB 弱)
    }

    @Test
    fun 途中の位置から範囲を決めて読める() {
        val data = bytes(10_000)
        val r = ChunkedReader(CountingSource(data), 1000, 2500)
        val got = readAll(r, 700)
        assertEquals(2500, got.size)
        assertTrue(data.copyOfRange(1000, 3500).contentEquals(got))
    }

    @Test
    fun 範囲がファイルの終わりを超えても_終わりで止まる() {
        val data = bytes(1000)
        val r = ChunkedReader(CountingSource(data), 900, 5000)
        assertEquals(100, readAll(r, 64).size)
        assertEquals(-1, r.read(ByteArray(8), 0, 8))
    }

    @Test
    fun 一度に少しずつしか返さない読み元でも最後まで読める() {
        val data = bytes(50_000)
        val r = ChunkedReader(CountingSource(data, limitPerRead = 1000), 0, -1, chunkSize = 8192)
        assertTrue(data.contentEquals(readAll(r, 4096)))
    }

    @Test
    fun 読み元が途中で終わったらエラー() {
        val broken = object : RandomSource {
            override val size = 100L
            override fun readAt(pos: Long, buf: ByteArray, off: Int, len: Int): Int = -1
            override fun close() {}
        }
        try {
            ChunkedReader(broken, 0, -1).read(ByteArray(10), 0, 10)
            fail()
        } catch (e: IOException) { /* 想定どおり */ }
    }

    @Test
    fun 読み残しの量が分かる() {
        val r = ChunkedReader(CountingSource(bytes(1000)), 0, -1, chunkSize = 300)
        assertEquals(1000L, r.unread)
        r.read(ByteArray(100), 0, 100)
        assertEquals(900L, r.unread)
    }

    @Test
    fun 再生時間の表示() {
        assertEquals("0:00", formatClock(0))
        assertEquals("0:05", formatClock(5_400))
        assertEquals("1:05", formatClock(65_000))
        assertEquals("59:59", formatClock(3_599_000))
        assertEquals("1:02:03", formatClock(3_723_000))
        assertEquals("0:00", formatClock(-1))
    }

    @Test
    fun 再生できる種類() {
        assertTrue(isPlayable(FileKind.VIDEO))
        assertTrue(isPlayable(FileKind.AUDIO))
        assertFalse(isPlayable(FileKind.IMAGE))
        assertFalse(isPlayable(FileKind.DOCUMENT))
        assertTrue(FileOps.kindOf("recording.ts") == FileKind.VIDEO)
        assertTrue(FileOps.kindOf("song.flac") == FileKind.AUDIO)
    }
}
