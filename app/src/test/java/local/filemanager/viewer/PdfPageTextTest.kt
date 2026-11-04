package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfPageTextTest {
    // 1 文字 10pt 四方。1 行目「Hello World」は x=0 から、2 行目「日本語の文」は y=20 から
    private fun page(): PdfPageText {
        val l = ArrayList<PdfChar>()
        var x = 0f
        for (ch in "Hello") { l.add(PdfChar(ch.toString(), x, 0f, 10f, 10f)); x += 10f }
        x += 6f // 単語の間の隙間
        for (ch in "World") { l.add(PdfChar(ch.toString(), x, 0f, 10f, 10f)); x += 10f }
        l.last().lineEnd = true
        x = 0f
        for (ch in "日本語の文") { l.add(PdfChar(ch.toString(), x, 20f, 10f, 10f)); x += 10f }
        l.last().lineEnd = true
        return PdfPageText(200f, 100f, l)
    }

    @Test
    fun 文字の上を押すと_その文字の番号が返る() {
        val p = page()
        assertEquals(0, p.hit(5f, 5f))
        assertEquals(9, p.hit(101f, 5f))                     // 「d」
        assertEquals(10, p.hit(3f, 25f))                     // 「日」
        assertEquals(-1, p.hit(150f, 80f))                   // 何も無い所
    }

    @Test
    fun 単語は同じ種類の続きで_行をまたがない() {
        val p = page()
        assertEquals(0..4, p.wordAround(2))     // Hello
        assertEquals(5..9, p.wordAround(7))     // World
        assertEquals(10..12, p.wordAround(11))  // 日本語(漢字の続き)
        assertEquals(13..13, p.wordAround(13))  // の(ひらがな)
        assertEquals(14..14, p.wordAround(14))  // 文
    }

    @Test
    fun コピーする文字は_行の終わりで改行し_英語の隙間は空白にする() {
        val p = page()
        assertEquals("Hello World\n日本語の文", p.textOf(0..14))
        assertEquals("日本語", p.textOf(10..12))
        assertEquals("", p.textOf(IntRange.EMPTY))
    }

    @Test
    fun 近い文字と囲み() {
        val p = page()
        assertEquals(14, p.nearest(45f, 500f))
        val b = p.boundsOf(0..4)!!
        assertEquals(0f, b[0]); assertEquals(0f, b[1]); assertEquals(50f, b[2]); assertEquals(10f, b[3])
        assertTrue(p.boundsOf(IntRange.EMPTY) == null)
    }

    @Test
    fun 行の文字の高さは_行の上下にそろえる() {
        // 「A」は高さ 10、「-」は低くて下寄り(y=6, h=2)。同じ行なので、どちらも y=0..10 になる
        val l = listOf(PdfChar("A", 0f, 0f, 10f, 10f), PdfChar("-", 10f, 6f, 6f, 2f, lineEnd = true), PdfChar("B", 0f, 20f, 10f, 10f))
        unifyLineBands(l)
        assertEquals(0f, l[1].y); assertEquals(10f, l[1].h)
        assertEquals(20f, l[2].y); assertEquals(10f, l[2].h)
    }

    @Test
    fun 縦書きのように1行が縦に長いときは_そろえない() {
        // 行の終わりが無いまま縦に 5 文字並ぶ(縦書きの 1 列)。全部を 1 つの帯にしてしまわない
        val l = List(5) { PdfChar("あ", 0f, it * 12f, 10f, 10f) }
        unifyLineBands(l)
        assertEquals(12f, l[1].y); assertEquals(10f, l[1].h)
    }}
