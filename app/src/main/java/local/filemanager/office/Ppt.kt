package local.filemanager

import java.nio.charset.Charset

/**
 * 古い PowerPoint(.ppt。PowerPoint 97〜2003)から、スライドごとの文字を取り出す。
 * 図形の位置・画像・書式は読まない(文字だけのスライド一覧で見せる)。Android に依存しない。
 */
object Ppt {
    private val CP1252: Charset = Charset.forName("windows-1252")

    internal class Rec(val ver: Int, val inst: Int, val type: Int, val off: Int, val len: Int) {
        val end: Int get() = off + len
        val isContainer: Boolean get() = ver == 0xF
    }

    fun parse(bytes: ByteArray): List<Slide> = parse(Ole2(bytes))

    fun parse(ole: Ole2): List<Slide> {
        val doc = ole.stream("PowerPoint Document") ?: throw OpException("PowerPointの文書(PowerPoint Document)が見つかりません")
        val persist = readPersist(ole.stream("Current User"), doc)
        val docOff = persist.docOffset
        // スライドの並び(SlideListWithText の SlidePersistAtom)
        val order = ArrayList<Int>()
        val placeholderTexts = HashMap<Int, ArrayList<Pair<Int, String>>>()
        if (docOff >= 0) {
            val d = rec(doc, docOff)
            if (d != null && d.type == 0x03E8) {
                for (c in children(doc, d)) {
                    if (c.type == 0x0FF0 && c.inst == 0) readSlideList(doc, c, order, placeholderTexts)
                }
            }
        }
        val slides = ArrayList<Slide>()
        if (order.isEmpty()) {
            // 目録が読めないときは、ファイル全体から Slide コンテナを順に探す
            var n = 0
            scanForSlides(doc) { texts -> n++; slides.add(makeSlide(n, texts)) }
            if (slides.isEmpty()) throw OpException("スライドが見つかりません")
            return slides
        }
        for ((i, pid) in order.withIndex()) {
            val off = persist.offsets[pid]
            val texts = ArrayList<Pair<Int, String>>()
            if (off != null) {
                val r = rec(doc, off)
                if (r != null && r.type == 0x03EE) collectTexts(doc, r, texts)
            }
            // 文字がスライド側に無いときは、スライド一覧に入っていた文字を使う
            placeholderTexts[pid]?.let { ph -> for (t in ph) if (texts.none { it.second == t.second }) texts.add(t) }
            slides.add(makeSlide(i + 1, texts))
        }
        return slides
    }

    private fun makeSlide(number: Int, texts: List<Pair<Int, String>>): Slide {
        var title: String? = null
        val lines = ArrayList<String>()
        for ((type, text) in texts) {
            val paras = text.split('\r', '\u000B', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (paras.isEmpty()) continue
            if (title == null && (type == 0 || type == 6)) {
                title = paras.joinToString(" ")
            } else {
                lines.addAll(paras)
            }
        }
        // 題名の枠が無いスライドは、最初の短い文を題名にする
        if (title == null && lines.size >= 2 && lines[0].length <= 60) title = lines.removeAt(0)
        return Slide(number, title, lines)
    }

    // ---------------------------------------------------------------- レコード

    internal fun rec(d: ByteArray, off: Int): Rec? {
        if (off < 0 || off + 8 > d.size) return null
        val vi = u16(d, off)
        val len = Ole2.le32(d, off + 4)
        if (len < 0 || off.toLong() + 8 + len > d.size) return null
        return Rec(vi and 0xF, vi shr 4, u16(d, off + 2), off + 8, len)
    }

    internal fun children(d: ByteArray, parent: Rec): List<Rec> {
        val out = ArrayList<Rec>()
        var p = parent.off
        while (p + 8 <= parent.end) {
            val r = rec(d, p) ?: break
            out.add(r)
            p = r.end
        }
        return out
    }

    internal class Persist(val docOffset: Int, val offsets: Map<Int, Int>)

    internal fun readPersist(cur: ByteArray?, d: ByteArray): Persist {
        val offsets = HashMap<Int, Int>()
        var docId = -1
        var editOff = if (cur != null && cur.size >= 20) Ole2.le32(cur, 16) else -1
        var guard = 0
        var firstEdit = true
        while (editOff >= 0 && guard++ < 1000) {
            val r = rec(d, editOff) ?: break
            if (r.type != 0x0FF5 || r.len < 24) break
            val offsetLastEdit = Ole2.le32(d, r.off + 8)
            val offsetPersistDir = Ole2.le32(d, r.off + 12)
            val docPersistIdRef = Ole2.le32(d, r.off + 16)
            if (firstEdit) { docId = docPersistIdRef; firstEdit = false }
            val pd = rec(d, offsetPersistDir)
            if (pd != null && pd.type == 0x1772) {
                var p = pd.off
                while (p + 4 <= pd.end) {
                    val head = Ole2.le32(d, p)
                    val first = head and 0xFFFFF
                    val count = (head ushr 20) and 0xFFF
                    p += 4
                    for (k in 0 until count) {
                        if (p + 4 > pd.end) break
                        offsets.putIfAbsent(first + k, Ole2.le32(d, p)) // 新しい編集の分を優先
                        p += 4
                    }
                }
            }
            if (offsetLastEdit <= 0 || offsetLastEdit == editOff) break
            editOff = offsetLastEdit
        }
        val docOff = if (docId >= 0) offsets[docId] ?: -1 else -1
        return Persist(docOff, offsets)
    }

    private fun readSlideList(d: ByteArray, slwt: Rec, order: MutableList<Int>, texts: MutableMap<Int, ArrayList<Pair<Int, String>>>) {
        var cur = -1
        var textType = 1
        for (c in children(d, slwt)) {
            when (c.type) {
                0x03F3 -> if (c.len >= 4) { cur = Ole2.le32(d, c.off); order.add(cur) }
                0x0F9F -> if (c.len >= 4) textType = Ole2.le32(d, c.off)
                0x0FA0, 0x0FA8 -> if (cur >= 0) texts.getOrPut(cur) { ArrayList() }.add(textType to textOf(d, c))
            }
        }
    }

    internal fun textOf(d: ByteArray, r: Rec): String =
        if (r.type == 0x0FA0) String(d, r.off, r.len and 1.inv(), Charsets.UTF_16LE) else String(d, r.off, r.len, CP1252)

    /** コンテナの中を順にたどって、TextHeaderAtom(種類)+ TextCharsAtom / TextBytesAtom の文字を集める。 */
    private fun collectTexts(d: ByteArray, parent: Rec, out: MutableList<Pair<Int, String>>) {
        var type = 4
        for (c in children(d, parent)) {
            when {
                c.isContainer -> collectTexts(d, c, out)
                c.type == 0x0F9F && c.len >= 4 -> type = Ole2.le32(d, c.off)
                c.type == 0x0FA0 || c.type == 0x0FA8 -> { out.add(type to textOf(d, c)); type = 4 }
            }
        }
    }

    private fun scanForSlides(d: ByteArray, onSlide: (List<Pair<Int, String>>) -> Unit) {
        var p = 0
        while (p + 8 <= d.size) {
            val r = rec(d, p) ?: break
            if (r.type == 0x03EE && r.isContainer) {
                val t = ArrayList<Pair<Int, String>>()
                collectTexts(d, r, t)
                onSlide(t)
                p = r.end
            } else if (r.isContainer) {
                p = r.off // 入れ物の中へ
            } else {
                p = r.end
            }
        }
    }

    internal fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
}
