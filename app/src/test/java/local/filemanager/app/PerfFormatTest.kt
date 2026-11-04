package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfFormatTest {
    private fun sample(used: Long = 85, max: Long = 512, frames: Int = 0) = PerfSample(
        visible = true, javaUsedMb = used, javaMaxMb = max, nativeMb = 120, threads = 63, fds = 210,
        gcCount = 2, gcBlockMs = 34, imageMb = 28, frames = frames, slowFrames = 3, worstFrameMs = 120,
    )

    @Test
    fun lineHasAllValues() {
        val l = PerfFormat.line("11-04 14:52:10", sample(frames = 820))
        assertEquals(
            "11-04 14:52:10  前面  Java 85/512MB  ネイティブ 120MB  スレッド 63  FD 210  GC +2回 +34ms  画像 28MB  遅れ 3/820枚 最大 120ms",
            l,
        )
    }

    @Test
    fun noFrameInfoWhenNoFramesDrawn() {
        assertFalse(PerfFormat.line("t", sample(frames = 0)).contains("遅れ"))
    }

    @Test
    fun warnsWhenJavaHeapIsNearlyFull() {
        assertFalse(PerfFormat.line("t", sample(used = 300)).contains("メモリ多"))
        assertTrue(PerfFormat.line("t", sample(used = 420)).contains("【メモリ多】"))
    }

    @Test
    fun trimKeepsNewestHalfOnlyWhenOverLimit() {
        val lines = (1..10).map { "l$it" }
        assertEquals(lines, PerfFormat.trim(lines, 10))
        assertEquals(listOf("l7", "l8", "l9", "l10"), PerfFormat.trim(lines, 8))
    }

    @Test
    fun lastStampSkipsMarkerLines() {
        assertEquals("11-04 16:08:55", PerfFormat.lastStamp(listOf("── 記録を止めました ──", "11-04 16:08:55  前面  Java 1/2MB", "11-04 16:08:45  x")))
        assertEquals(null, PerfFormat.lastStamp(listOf("── 記録を止めました ──")))
        assertEquals(null, PerfFormat.lastStamp(emptyList()))
    }

    @Test
    fun threadsFromStatus() {
        assertEquals(63, PerfFormat.threadsFromStatus("Name:\tfiler\nThreads:\t63\nVmRSS:\t1 kB\n"))
        assertEquals(-1, PerfFormat.threadsFromStatus("Name:\tfiler\n"))
    }
}
