package local.filemanager

import kotlin.math.max
import kotlin.math.min

/** PDF のページの文字 1 つ。位置は、ページの左上を原点にした点(pt)。lineEnd は、行の終わり。 */
internal class PdfChar(val s: String, val x: Float, var y: Float, val w: Float, var h: Float, var lineEnd: Boolean = false)

/**
 * PDF の 1 ページ分の、文字と位置。画面の上で文字を選ぶ(長押し・つまみ)ための、PDFium に依存しない部分。
 * width / height は、ページの大きさ(pt)。
 */
internal class PdfPageText(val width: Float, val height: Float, val chars: List<PdfChar>) {
    private fun distSq(c: PdfChar, px: Float, py: Float): Float {
        val dx = if (px < c.x) c.x - px else if (px > c.x + c.w) px - (c.x + c.w) else 0f
        val dy = if (py < c.y) c.y - py else if (py > c.y + c.h) py - (c.y + c.h) else 0f
        return dx * dx + dy * dy
    }

    /** 点 (px, py) に重なる(近い)文字の番号。無ければ -1。 */
    fun hit(px: Float, py: Float): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for ((i, c) in chars.withIndex()) {
            val pad = max(c.h, 4f) * 0.5f
            val d = distSq(c, px, py)
            if (d <= pad * pad && d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /** 点にいちばん近い文字の番号(つまみを動かすときに使う)。文字が無ければ -1。 */
    fun nearest(px: Float, py: Float): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for ((i, c) in chars.withIndex()) {
            val d = distSq(c, px, py)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    private fun kind(s: String): Int {
        val c = s.firstOrNull() ?: return 0
        return when {
            c.isLetterOrDigit() && c.code < 0x80 -> 1
            c in '\u3040'..'\u309F' -> 2
            c in '\u30A0'..'\u30FF' -> 3
            c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF' -> 4
            c.isLetterOrDigit() -> 5
            else -> 0
        }
    }

    /** 長押しした文字を含む、同じ種類(英数字・ひらがな・カタカナ・漢字)の続き。同じ行の中だけ。 */
    fun wordAround(i: Int): IntRange {
        if (i !in chars.indices) return IntRange.EMPTY
        val k = kind(chars[i].s)
        if (k == 0) return i..i
        var a = i
        var b = i
        while (a > 0 && !breaksBetween(chars[a - 1], chars[a]) && kind(chars[a - 1].s) == k) a--
        while (b < chars.lastIndex && !breaksBetween(chars[b], chars[b + 1]) && kind(chars[b + 1].s) == k) b++
        return a..b
    }

    /** 行が変わる・単語の間に隙間がある所は、同じ単語にしない。 */
    private fun breaksBetween(p: PdfChar, c: PdfChar) = p.lineEnd || c.x - (p.x + p.w) > p.h * 0.3f

    private fun wide(s: String) = (s.firstOrNull()?.code ?: 0) >= 0x2E80

    /** 範囲の文字を、コピーできる文字にする(行の終わりは改行。英数字の間の隙間は空白)。 */
    fun textOf(r: IntRange): String {
        val sb = StringBuilder()
        for (i in r) {
            val c = chars.getOrNull(i) ?: continue
            if (i > r.first) {
                val p = chars[i - 1]
                if (p.lineEnd) sb.append('\n')
                else if (!wide(p.s) && !wide(c.s) && c.x - (p.x + p.w) > p.h * 0.3f) sb.append(' ')
            }
            sb.append(c.s)
        }
        return sb.toString()
    }

    /** 範囲の囲み(左, 上, 右, 下)。 */
    fun boundsOf(r: IntRange): FloatArray? {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var rr = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var any = false
        for (i in r) {
            val c = chars.getOrNull(i) ?: continue
            any = true
            l = min(l, c.x); t = min(t, c.y); rr = max(rr, c.x + c.w); b = max(b, c.y + c.h)
        }
        return if (any) floatArrayOf(l, t, rr, b) else null
    }
}

/**
 * 文字の枠は文字ごとの大きさ(「あ」と「-」で高さが違う)なので、そのままだと選んだ所の色がでこぼこになる。
 * 同じ行(lineEnd まで)の文字は、行全体の上下にそろえる。縦書きのように 1 行が縦に長い所は、そろえない。
 */
internal fun unifyLineBands(chars: List<PdfChar>) {
    var start = 0
    while (start < chars.size) {
        var end = start
        while (end < chars.lastIndex && !chars[end].lineEnd) end++
        val hs = FloatArray(end - start + 1) { chars[start + it].h }.also { it.sort() }
        val median = hs[hs.size / 2]
        var top = Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (i in start..end) { top = min(top, chars[i].y); bottom = max(bottom, chars[i].y + chars[i].h) }
        if (median > 0f && bottom - top <= median * 2.2f) {
            for (i in start..end) { chars[i].y = top; chars[i].h = bottom - top }
        }
        start = end + 1
    }
}