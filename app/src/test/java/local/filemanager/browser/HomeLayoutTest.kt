package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeLayoutTest {
    private val defaults = listOf("a", "b", "c", "d")

    @Test
    fun arrangeKeepsSavedOrderThenAppendsNewOnes() {
        assertEquals(listOf("c", "a", "b", "d"), HomeLayout.arrange(defaults, listOf("c", "a")))
        // 保存に無くなったもの(消えたタイル)は捨て、重複は一つに
        assertEquals(listOf("b", "a", "c", "d"), HomeLayout.arrange(defaults, listOf("b", "x", "a", "b")))
        assertEquals(defaults, HomeLayout.arrange(defaults, emptyList()))
    }

    @Test
    fun moveShiftsOneStepAndStopsAtEnds() {
        assertEquals(listOf("b", "a", "c", "d"), HomeLayout.move(defaults, 0, 1))
        assertEquals(listOf("a", "b", "d", "c"), HomeLayout.move(defaults, 3, -1))
        assertEquals(defaults, HomeLayout.move(defaults, 0, -1))
        assertEquals(defaults, HomeLayout.move(defaults, 3, 1))
        assertEquals(defaults, HomeLayout.move(defaults, 9, 1))
    }

    @Test
    fun encodeDecodeRoundTrip() {
        assertEquals(defaults, HomeLayout.decode(HomeLayout.encode(defaults)))
        assertEquals(emptyList<String>(), HomeLayout.decode(""))
        val sc = listOf(HomeShortcut("local", "/storage/emulated/0/DCIM", "写真"), HomeShortcut("smb:ab12", "/動画/2026", "動画 2026"))
        assertEquals(sc, HomeLayout.decodeShortcuts(HomeLayout.encodeShortcuts(sc)))
        assertEquals("sc:local:/storage/emulated/0/DCIM", sc[0].id)
        // 壊れた行は無視する
        assertEquals(1, HomeLayout.decodeShortcuts("local\t/x\tX\nbroken line\n").size)
    }

    @Test
    fun frameShrinksWithMoreColumns() {
        assertEquals(66, HomeLayout.frameDp(4))
        assert(HomeLayout.frameDp(3) > HomeLayout.frameDp(5))
    }
}
