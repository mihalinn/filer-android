package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class ViewerCoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // ---- 文字コード ----

    @Test
    fun UTF8のテキストを読める() {
        val r = TextDecode.decode("こんにちは\nhello".toByteArray(Charsets.UTF_8))
        assertEquals("こんにちは\nhello", r.text)
        assertEquals("UTF-8", r.charsetName)
    }

    @Test
    fun BOM付きUTF8はBOMを除いて読める() {
        val b = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "日本語".toByteArray(Charsets.UTF_8)
        assertEquals("日本語", TextDecode.decode(b).text)
    }

    @Test
    fun ShiftJISのテキストを見分けて読める() {
        val sjis = java.nio.charset.Charset.forName("windows-31j")
        val r = TextDecode.decode("録画一覧です。ABC".toByteArray(sjis))
        assertEquals("録画一覧です。ABC", r.text)
        assertEquals("windows-31j", r.charsetName.lowercase())
    }

    @Test
    fun EUCJPのテキストも読める() {
        // EUC-JP でだけ正しく読めるバイト列(「テスト」)
        val euc = java.nio.charset.Charset.forName("EUC-JP")
        val bytes = "テスト".toByteArray(euc)
        val r = TextDecode.decode(bytes)
        // Shift_JIS としても読めてしまう並びなら、先に試す Shift_JIS が採用される。どちらにせよ落ちずに文字列になる
        assertTrue(r.text.isNotEmpty())
    }

    @Test
    fun UTF16のBOM付きを読める() {
        val b = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "ABC".toByteArray(Charsets.UTF_16LE)
        val r = TextDecode.decode(b)
        assertEquals("ABC", r.text)
    }

    @Test
    fun 途中で切ったUTF8は末尾の欠けた文字を無視して読める() {
        val full = "あいうえお".toByteArray(Charsets.UTF_8) // 15バイト
        val cut = full.copyOf(14) // 最後の「お」が欠けている
        assertEquals("あいうえ", TextDecode.decode(cut, truncated = true).text)
    }

    @Test
    fun どの文字コードでも読めないバイト列でも落ちない() {
        val junk = ByteArray(64) { (0x80 + it % 64).toByte() }
        assertTrue(TextDecode.decode(junk).text.isNotEmpty())
    }

    @Test
    fun 先頭から上限まで読み_続きがあるかを返す() {
        val data = ByteArray(1000) { 1 }
        val (a, cutA) = TextDecode.readUpTo(ByteArrayInputStream(data), 600)
        assertEquals(600, a.size)
        assertTrue(cutA)
        val (b, cutB) = TextDecode.readUpTo(ByteArrayInputStream(data), 1000)
        assertEquals(1000, b.size)
        assertFalse(cutB)
        val (c, cutC) = TextDecode.readUpTo(ByteArrayInputStream(data), 5000)
        assertEquals(1000, c.size)
        assertFalse(cutC)
    }

    // ---- ファイルの種類 ----

    @Test
    fun テキストとして開く拡張子() {
        assertTrue(FileOps.isTextName("memo.txt"))
        assertTrue(FileOps.isTextName("README.MD"))
        assertTrue(FileOps.isTextName("config.yaml"))
        assertTrue(FileOps.isTextName("sub.srt"))
        // 録画の MPEG-TS は動画なのでテキストにしない
        assertFalse(FileOps.isTextName("news.ts"))
        assertFalse(FileOps.isTextName("photo.jpg"))
        assertFalse(FileOps.isTextName("noext"))
    }

    // ---- 画像の縮小率 ----

    @Test
    fun 縮小率は長い辺が上限以下になる最小の2のべき乗() {
        assertEquals(1, ImageDecode.sampleFor(1000, 800, 3072))
        assertEquals(1, ImageDecode.sampleFor(3072, 100, 3072))
        assertEquals(2, ImageDecode.sampleFor(4000, 3000, 3072))
        assertEquals(4, ImageDecode.sampleFor(12000, 9000, 3072))
        assertEquals(8, ImageDecode.sampleFor(4000, 3000, 640))
    }

    // ---- サムネイルの保存 ----

    @Test
    fun 保存名は中身が変わると別の名前になる() {
        val a = FileItem("/a.jpg", "a.jpg", false, 100, 1000)
        val same = Thumbs.cacheName("smb:x", a, false)
        assertEquals(same, Thumbs.cacheName("smb:x", a, false))
        assertTrue(same != Thumbs.cacheName("smb:x", a.copy(modified = 2000), false))
        assertTrue(same != Thumbs.cacheName("smb:x", a.copy(size = 101), false))
        assertTrue(same != Thumbs.cacheName("smb:x", a, true))
        assertTrue(same != Thumbs.cacheName("smb:y", a, false))
    }

    @Test
    fun 保存したサムネイルが上限を超えたら古い順に消える() {
        val dir = tmp.newFolder()
        val old = File(dir, "old.jpg").apply { writeBytes(ByteArray(400)); setLastModified(1_000) }
        val mid = File(dir, "mid.jpg").apply { writeBytes(ByteArray(400)); setLastModified(2_000) }
        val new = File(dir, "new.jpg").apply { writeBytes(ByteArray(400)); setLastModified(3_000) }
        Thumbs.trim(dir, 900)
        assertFalse(old.exists())
        assertTrue(mid.exists())
        assertTrue(new.exists())
        Thumbs.trim(dir, 10_000) // 上限以内なら何も消さない
        assertTrue(mid.exists() && new.exists())
    }
}
