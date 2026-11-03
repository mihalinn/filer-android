package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicInfoTest {
    @Test
    fun 曲名はタグが先_無ければファイル名() {
        assertEquals("ドレミの旅", MusicText.title(" ドレミの旅 ", "x.mp3"))
        assertEquals("01 sample", MusicText.title(null, "01 sample.mp3"))
        assertEquals("a.b", MusicText.title("", "a.b.flac"))
        assertEquals("noext", MusicText.title(null, "noext"))
        assertEquals(".hidden", MusicText.title(null, ".hidden"))
    }

    @Test
    fun 曲名の下の行() {
        assertEquals(
            listOf("ファイラー楽団", "テスト用アルバム(2026)", "トラック 3/12"),
            MusicText.lines("ファイラー楽団", null, "テスト用アルバム", 2026, 3, 12, null, null),
        )
        // アーティストが無ければ、アルバムのアーティスト
        assertEquals(listOf("合唱団"), MusicText.lines(null, "合唱団", null, null, null, null, null, null))
        // 年だけ
        assertEquals(listOf("1999 年"), MusicText.lines(null, null, null, 1999, null, null, null, null))
        // 何も無ければ空
        assertEquals(emptyList<String>(), MusicText.lines(" ", null, "", null, null, null, null, null))
        // ディスク(1 枚だけのときは出さない)
        assertEquals(listOf("トラック 1"), MusicText.lines(null, null, null, null, 1, null, 1, 1))
        assertEquals(listOf("トラック 5/10・ディスク 2/2"), MusicText.lines(null, null, null, null, 5, 10, 2, 2))
        // おかしな年は出さない
        assertEquals(listOf("A"), MusicText.lines("A", null, null, 12, null, null, null, null))
    }

    @Test
    fun 番号の読み取り() {
        assertEquals(3 to 12, MusicText.numberOf("3/12"))
        assertEquals(7 to null, MusicText.numberOf("7"))
        assertEquals(null to null, MusicText.numberOf("abc"))
        assertEquals(null to null, MusicText.numberOf(null))
        assertEquals(2 to null, MusicText.numberOf(" 2 / x "))
    }

    @Test
    fun ビットレートとサンプリング周波数() {
        assertEquals("320 kbps", MusicText.bitrate(320_000))
        assertEquals("128 kbps", MusicText.bitrate(127_600))
        assertNull(MusicText.bitrate(0))
        assertEquals("44.1 kHz", MusicText.sampleRate(44100))
        assertEquals("48 kHz", MusicText.sampleRate(48000))
        assertNull(MusicText.sampleRate(-1))
    }

    @Test
    fun フォルダのジャケット探し() {
        assertEquals("cover.jpg", CoverFiles.pick(listOf("a.mp3", "cover.jpg", "folder.jpg")))
        assertEquals("Folder.PNG", CoverFiles.pick(listOf("a.mp3", "Folder.PNG")))
        assertEquals("cover.png", CoverFiles.pick(listOf("cover.png", "front.jpg")))
        assertEquals("ジャケット.jpeg", CoverFiles.pick(listOf("ジャケット.jpeg", "memo.txt")))
        assertNull(CoverFiles.pick(listOf("a.mp3", "photo.jpg", "cover.txt")))
        assertNull(CoverFiles.pick(emptyList()))
    }
}
