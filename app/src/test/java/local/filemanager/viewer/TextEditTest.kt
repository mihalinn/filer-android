package local.filemanager

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TextEditTest {
    private val sjis = java.nio.charset.Charset.forName("windows-31j")
    private val euc = java.nio.charset.Charset.forName("EUC-JP")

    // ---------------------------------------------------------------- 読み込み

    @Test
    fun utf8WithCrlfKeepsItsFormAndIsEditedAsLf() {
        val e = TextFile.parse("あいう\r\nえお\r\n".toByteArray())
        assertEquals("あいう\nえお\n", e.text)
        assertEquals(TextEnc.UTF8, e.enc)
        assertEquals(Eol.CRLF, e.eol)
        assertFalse(e.mixedEol)
    }

    @Test
    fun shiftJisEucJpAndBomAreRecognised() {
        val s = "日本語のテキストです。ソフト\\表"
        assertEquals(TextEnc.SJIS, TextFile.parse(s.toByteArray(sjis)).enc)
        assertEquals(TextEnc.EUC, TextFile.parse("日本語のテキストです。ファイルの編集".toByteArray(euc)).enc)
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "あ".toByteArray()
        val e = TextFile.parse(bom)
        assertEquals(TextEnc.UTF8_BOM, e.enc)
        assertEquals("あ", e.text)
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "あA".toByteArray(Charsets.UTF_16LE)
        assertEquals(TextEnc.UTF16LE, TextFile.parse(le).enc)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "あA".toByteArray(Charsets.UTF_16BE)
        assertEquals("あA", TextFile.parse(be).text)
        assertEquals(TextEnc.UTF16BE, TextFile.parse(be).enc)
    }

    @Test
    fun lineEndingsAreDetectedByMajorityAndMixedIsFlagged() {
        assertEquals(Eol.LF, TextFile.parse("a\nb\nc".toByteArray()).eol)
        assertEquals(Eol.CR, TextFile.parse("a\rb\rc".toByteArray()).eol)
        assertEquals(Eol.LF, TextFile.parse("abc".toByteArray()).eol)
        val m = TextFile.parse("a\r\nb\r\nc\nd".toByteArray())
        assertEquals(Eol.CRLF, m.eol)
        assertTrue(m.mixedEol)
        assertEquals("a\nb\nc\nd", m.text)
    }

    @Test
    fun emptyFileIsEditable() {
        val e = TextFile.parse(ByteArray(0))
        assertEquals("", e.text)
        assertEquals(TextEnc.UTF8, e.enc)
    }

    @Test
    fun binaryTooBigAndUnknownCodesAreRefused() {
        for (bad in listOf(byteArrayOf(1, 2, 0, 3), ByteArray(TextFile.MAX_BYTES + 1) { 'a'.code.toByte() }, byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x41, 0x80.toByte(), 0xFE.toByte()))) {
            try {
                TextFile.parse(bad)
                fail()
            } catch (e: OpException) {
                assertTrue(e.message!!.isNotEmpty())
            }
        }
    }

    // ---------------------------------------------------------------- 書き戻し

    @Test
    fun savingAnUntouchedFileGivesTheSameBytes() {
        val samples = listOf(
            "あいう\r\nえお\r\n".toByteArray(),
            "line1\nline2".toByteArray(),
            "日本語です。\r\n全角ＡＢＣ①\r\n".toByteArray(sjis),
            "日本語のテキストです。ファイルの編集".toByteArray(euc),
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "BOM付き\n".toByteArray(),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "あい\r\n".toByteArray(Charsets.UTF_16LE),
            ByteArray(0),
            "絵文字😀あり\n".toByteArray(),
        )
        for (b in samples) {
            val e = TextFile.parse(b)
            assertArrayEquals(e.text, b, TextFile.encode(e.text, e.enc, e.eol))
        }
    }

    @Test
    fun changingTheEncodingOrLineEndingRewritesTheBytes() {
        val text = "あ\nい"
        assertArrayEquals("あ\r\nい".toByteArray(sjis), TextFile.encode(text, TextEnc.SJIS, Eol.CRLF))
        assertArrayEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "あ\rい".toByteArray(), TextFile.encode(text, TextEnc.UTF8_BOM, Eol.CR))
        assertArrayEquals(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE), TextFile.encode(text, TextEnc.UTF16BE, Eol.LF))
    }

    @Test
    fun aCharacterThatTheEncodingCannotHoldIsReported() {
        try {
            TextFile.encode("abc😀def", TextEnc.SJIS, Eol.LF)
            fail()
        } catch (e: OpException) {
            assertTrue(e.message!!, e.message!!.contains("😀") && e.message!!.contains("Shift_JIS"))
        }
        // UTF-8 なら保存できる
        TextFile.encode("abc😀def", TextEnc.UTF8, Eol.LF)
    }

    @Test
    fun editedTextRoundTrips() {
        val e = TextFile.parse("一行目です\r\nsecond\r\n".toByteArray(sjis))
        val edited = e.text.replace("second", "二行目")
        val bytes = TextFile.encode(edited, e.enc, e.eol)
        val again = TextFile.parse(bytes)
        assertEquals(edited, again.text)
        assertEquals(TextEnc.SJIS, again.enc)
        assertEquals(Eol.CRLF, again.eol)
    }

    // ---------------------------------------------------------------- 元に戻す

    private fun snap(t: String, c: Int = t.length) = UndoHistory.Snap(t, c, c)

    @Test
    fun quickTypingIsOneStepAndSlowTypingIsSeparate() {
        val h = UndoHistory(gapMs = 800)
        h.changed(snap(""), 1000)
        h.changed(snap("a"), 1100)
        h.changed(snap("ab"), 1200)
        h.changed(snap("abc"), 3000) // 間が空いたので別の 1 回分
        h.changed(snap("abcd"), 3100)
        assertEquals("abc", h.undo(snap("abcde"))!!.text)
        assertEquals("", h.undo(snap("abc"))!!.text)
        assertNull(h.undo(snap("")))
    }

    @Test
    fun undoThenRedoComesBack() {
        val h = UndoHistory()
        h.changed(snap("a"), 0)
        val back = h.undo(snap("ab"))!!
        assertEquals("a", back.text)
        assertTrue(h.canRedo)
        assertEquals("ab", h.redo(snap("a"))!!.text)
        assertFalse(h.canRedo)
        assertTrue(h.canUndo)
    }

    @Test
    fun aNewChangeClearsTheRedoList() {
        val h = UndoHistory()
        h.changed(snap("a"), 0)
        h.undo(snap("ab"))
        h.changed(snap("a"), 10_000)
        assertFalse(h.canRedo)
    }

    @Test
    fun aBigChangeIsItsOwnStepEvenRightAfterTyping() {
        val h = UndoHistory(gapMs = 800)
        h.changed(snap(""), 1000)
        h.changed(snap("x"), 1100)
        h.changed(snap("xy"), 1200, own = true) // 貼り付け
        h.changed(snap("xyPASTED"), 1300) // 貼り付けの直後に打った分は別の 1 回分
        assertEquals("xyPASTED", h.undo(snap("xyPASTEDz"))!!.text)
        assertEquals("xy", h.undo(snap("xyPASTED"))!!.text)
        assertEquals("", h.undo(snap("xy"))!!.text)
    }

    @Test
    fun historyIsCappedByCountAndSize() {
        val h = UndoHistory(maxCount = 3, maxChars = 1_000_000, gapMs = 0)
        for (i in 0 until 10) h.changed(snap("v$i"), i * 10_000L)
        var n = 0
        var cur = snap("end")
        while (true) {
            val p = h.undo(cur) ?: break
            cur = p
            n++
        }
        assertEquals(3, n)
        val big = UndoHistory(maxCount = 100, maxChars = 100, gapMs = 0)
        for (i in 0 until 10) big.changed(snap("x".repeat(40)), i * 10_000L)
        var m = 0
        var c2 = snap("")
        while (true) {
            c2 = big.undo(c2) ?: break
            m++
        }
        assertTrue("$m", m in 1..3)
    }

    // ---------------------------------------------------------------- 検索・置換

    @Test
    fun findNextAndPreviousWrapAround() {
        val t = "abc ABC abc"
        assertEquals(0, TextFind.next(t, "abc", 0))
        assertEquals(4, TextFind.next(t, "abc", 1))
        assertEquals(8, TextFind.next(t, "abc", 5))
        assertEquals(0, TextFind.next(t, "abc", 9)) // 末尾を越えたら先頭から
        assertEquals(-1, TextFind.next(t, "zzz", 0))
        assertEquals(-1, TextFind.next(t, "", 0))
        assertEquals(4, TextFind.prev(t, "abc", 8))
        assertEquals(0, TextFind.prev(t, "abc", 4))
        assertEquals(8, TextFind.prev(t, "abc", 0)) // 先頭より前なら末尾から
        assertEquals(-1, TextFind.prev(t, "zzz", 5))
    }

    @Test
    fun countAndReplaceAllIgnoreCaseAndTreatSymbolsLiterally() {
        assertEquals(3, TextFind.count("abc ABC abc", "abc"))
        assertEquals(0, TextFind.count("abc", ""))
        assertEquals(2, TextFind.count("aaaa", "aa")) // 重ならない数え方
        val (r, n) = TextFind.replaceAll("a.c A.C abc", "a.c", "[$1\\]")
        assertEquals(2, n)
        assertEquals("[$1\\] [$1\\] abc", r)
        assertEquals("x" to 0, TextFind.replaceAll("x", "y", "z"))
        assertEquals("ここに" to 1, TextFind.replaceAll("ここを", "を", "に"))
    }
}
