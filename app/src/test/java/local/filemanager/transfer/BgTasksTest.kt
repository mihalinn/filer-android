package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BgTasksTest {
    @Test
    fun smallOnesAreNotTracked() {
        assertFalse(BgTasks.shouldTrack(1))
        assertFalse(BgTasks.shouldTrack(BgTasks.SERVICE_MIN_BYTES - 1))
        assertTrue(BgTasks.shouldTrack(BgTasks.SERVICE_MIN_BYTES))
        // 大きさが分からないときは、念のため通知を出す
        assertTrue(BgTasks.shouldTrack(0))
    }

    @Test
    fun noticeShowsPercentAndSpeed() {
        val n = taskNotice("ダウンロードしています", 5L * 1024 * 1024, 10L * 1024 * 1024, 5_000)
        assertEquals("ダウンロードしています 50%", n.title)
        assertEquals(50, n.percent)
        assertTrue(n.text.contains("/s"))
        assertTrue(n.detail.contains("経過 0:00:05"))
    }

    @Test
    fun noticeWithoutTotalHasNoPercent() {
        val n = taskNotice("取り込んでいます", 1024, 0, 0)
        assertEquals("取り込んでいます", n.title)
        assertNull(n.percent)
        assertFalse(n.text.contains("/s"))
    }

    @Test
    fun pumpCopiesAndCountsProgress() {
        val data = ByteArray(700_000) { it.toByte() }
        val t = BgTask("t")
        val out = ByteArrayOutputStream()
        t.pump(ByteArrayInputStream(data), out)
        assertEquals(data.size.toLong(), t.done)
        assertTrue(out.toByteArray().contentEquals(data))
    }

    @Test
    fun pumpStopsWhenCancelled() {
        val t = BgTask("t")
        t.cancel()
        try {
            t.pump(ByteArrayInputStream(ByteArray(10)), ByteArrayOutputStream())
            fail("中断されるはず")
        } catch (e: FetchCancelled) {
            assertEquals("中止しました", e.message)
        }
    }

    @Test
    fun activeOnlyWhileRunning_andViewerTasksAreCounted() {
        val viewer = BgTask("v", forViewer = true)
        val other = BgTask("o")
        assertFalse(viewer.active)
        BgTasks.run(viewer, 10) {
            assertTrue(viewer.active)
            assertTrue(BgTasks.anyActive())
        }
        assertFalse(viewer.active)
        BgTasks.run(other, 10) {
            assertTrue(other.active)
            // 更新などのダウンロードでは、ビューアの読み込みカードを隠さない
            assertFalse(BgTasks.anyActive())
        }
    }

    @Test
    fun runResetsProgressAndPassesResult() {
        val t = BgTask("t")
        t.done = 99
        val r = BgTasks.run(t, 10) { t.done }
        assertEquals(0L, r)
        assertEquals(10L, t.total)
        assertTrue(BgTasks.tracked().isEmpty())
    }
}
