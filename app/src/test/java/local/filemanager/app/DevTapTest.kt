package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DevTapTest {
    @Test
    fun countsQuickTaps() {
        var c = 0
        var t = 1000L
        repeat(DevTap.NEED) {
            c = DevTap.next(c, t - 300, t)
            t += 300
        }
        assertEquals(DevTap.NEED, c)
    }

    @Test
    fun slowTapRestartsCount() {
        assertEquals(1, DevTap.next(5, 1000, 1000 + DevTap.GAP_MS + 1))
        assertEquals(6, DevTap.next(5, 1000, 1000 + DevTap.GAP_MS))
    }

    @Test
    fun firstTapIsOne() {
        assertEquals(1, DevTap.next(0, 0, 10))
    }

    @Test
    fun remainingOnlyNearTheEnd() {
        assertNull(DevTap.remaining(1))
        assertNull(DevTap.remaining(2))
        assertEquals(4, DevTap.remaining(3))
        assertEquals(1, DevTap.remaining(6))
        assertNull(DevTap.remaining(DevTap.NEED))
    }
}
