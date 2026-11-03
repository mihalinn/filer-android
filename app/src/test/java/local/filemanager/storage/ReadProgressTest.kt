package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

private class OneFileFs(val data: ByteArray, val failOpen: Boolean = false) : Fs {
    override val id = "test"
    override val isLocal = false
    override fun list(path: String): List<FileItem>? = null
    override fun stat(path: String): FileItem? = null
    override fun mkdir(path: String) {}
    override fun delete(path: String) {}
    override fun rename(path: String, newName: String) = path
    override fun open(path: String): InputStream {
        if (failOpen) throw OpException("開けません")
        return ByteArrayInputStream(data)
    }
    override fun create(path: String): OutputStream = throw UnsupportedOperationException()
    override fun moveInside(src: String, dst: String) = false
}

class ReadProgressTest {
    @Test
    fun trackedFsCountsBytesAndEndsOnClose() {
        val fs = TrackedFs(OneFileFs(ByteArray(1000) { it.toByte() }), mapOf("/a.bin" to 1000L))
        val inp = fs.open("/a.bin")
        val s = fs.tracker.active().single()
        assertEquals(1000L, s.total)
        assertEquals(0L, s.read)
        val buf = ByteArray(300)
        assertEquals(300, inp.read(buf))
        assertEquals(300L, s.read)
        assertEquals(1, inp.read().let { 1 })
        assertEquals(301L, s.read)
        inp.skip(100)
        assertEquals(401L, s.read)
        assertEquals(599, inp.readBytes().size)
        assertEquals(1000L, s.read)
        inp.close()
        inp.close()
        assertTrue(fs.tracker.active().isEmpty())
    }

    @Test
    fun trackedFsReleasesWhenOpenFails() {
        val fs = TrackedFs(OneFileFs(ByteArray(1), failOpen = true), emptyMap())
        try {
            fs.open("/x")
        } catch (e: OpException) {
        }
        assertTrue(fs.tracker.active().isEmpty())
    }

    @Test
    fun unknownSizeIsMinusOneAndOtherMembersAreDelegated() {
        val fs = TrackedFs(OneFileFs(ByteArray(5)), emptyMap())
        fs.open("/q").use { assertEquals(-1L, fs.tracker.active().single().total) }
        assertEquals("test", fs.id)
        assertEquals(false, fs.isLocal)
        assertEquals("/x/y", fs.child("/x", "y"))
        assertNull(fs.openRandom("/q"))
    }

    @Test
    fun speedMeterUsesRecentWindow() {
        val m = SpeedMeter(3000)
        assertEquals(0.0, m.bytesPerSec(), 0.0)
        m.add(0, 0)
        assertEquals(0.0, m.bytesPerSec(), 0.0)
        m.add(1000, 1_000_000)
        m.add(2000, 2_000_000)
        assertEquals(1_000_000.0, m.bytesPerSec(), 1.0)
        // 速さが落ちたら、古い分は窓から外れて新しい速さになる
        m.add(3000, 2_100_000)
        m.add(4000, 2_200_000)
        m.add(5000, 2_300_000)
        m.add(6000, 2_400_000)
        assertEquals(100_000.0, m.bytesPerSec(), 1.0)
        m.reset()
        assertEquals(0.0, m.bytesPerSec(), 0.0)
    }

    @Test
    fun formatting() {
        assertEquals("0 B 読み込み済み".replace("0 B", FileOps.formatSize(0)), ReadFormat.amount(0, -1))
        assertTrue(ReadFormat.amount(50, 100).endsWith("(50%)"))
        assertTrue(ReadFormat.amount(500, 100).endsWith("読み込み済み"))
        assertEquals("あと約 23 秒", ReadFormat.eta(0, 23_000_000, 1_000_000.0))
        assertEquals("あと約 2 分 5 秒", ReadFormat.eta(0, 125_000_000, 1_000_000.0))
        assertEquals("あと約 1 時間 1 分", ReadFormat.eta(0, 3_700_000_000, 1_000_000.0))
        assertNull(ReadFormat.eta(0, 100, 0.0))
        assertNull(ReadFormat.eta(100, 100, 1_000_000.0))
        assertNull(ReadFormat.eta(0, -1, 1_000_000.0))
    }

    @Test
    fun picksTheStreamWithTheMostLeft() {
        val t = ReadTracker()
        val small = t.begin("/small", 1000)
        val big = t.begin("/big", 50_000)
        val unknown = t.begin("/unknown", -1)
        assertEquals("/big", ReadFormat.pick(t.active())!!.path)
        big.read = 49_900
        assertEquals("/small", ReadFormat.pick(t.active())!!.path)
        t.end(small)
        t.end(big)
        assertEquals("/unknown", ReadFormat.pick(t.active())!!.path)
        t.end(unknown)
        assertNull(ReadFormat.pick(t.active()))
        assertNotNull(t)
    }
}
