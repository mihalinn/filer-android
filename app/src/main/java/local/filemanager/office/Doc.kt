package local.filemanager

import java.nio.charset.Charset

/**
 * 古い Word(.doc。Word 97〜2003)の読み取り。文字(ピース表)・段落・文字の書式(太字・斜体・下線・色・大きさなど)・見出し・表・リンク・改ページ。
 * 結果は Word(.docx)と同じ DocxDoc。Android に依存しない。画像・箇条書きの番号・脚注は読まない。
 */
object Doc {
    private val CP1252: Charset = Charset.forName("windows-1252")

    /** 読み取った文書と、画像を取り出すための関数(画像の置き場所の名前 → 中身)。 */
    class Opened(val doc: DocxDoc, val read: (String) -> ByteArray?)

    fun parse(bytes: ByteArray): DocxDoc = parse(Ole2(bytes))

    fun parse(ole: Ole2): DocxDoc = open(ole).doc

    fun open(ole: Ole2): Opened {
        val word = ole.stream("WordDocument") ?: throw OpException(Txt(R.string.doc_001))
        if (word.size < 512 || u16(word, 0) != 0xA5EC) throw OpException(Txt(R.string.doc_002))
        val flags = u16(word, 10)
        if ((flags and 0x0100) != 0) throw OpException(Txt(R.string.doc_003))
        val table = ole.stream(if ((flags and 0x0200) != 0) "1Table" else "0Table") ?: throw OpException(Txt(R.string.doc_004))
        val f = Fib(word)
        val pieces = readPieces(word, table, f)
        val text = buildText(word, pieces, f.ccpText)
        val chpx = readFkps(word, table, f.fcPlcfBteChpx, f.lcbPlcfBteChpx, papx = false)
        val papx = readFkps(word, table, f.fcPlcfBtePapx, f.lcbPlcfBtePapx, papx = true)
        val styles = readStyles(table, f.fcStshf, f.lcbStshf)
        val lists = readLists(table, f)
        val data = ole.stream("Data")
        val doc = Builder(text, pieces, chpx, papx, styles, lists, data).build()
        return Opened(doc) { name -> if (data != null && name.startsWith(PIC_PREFIX)) name.removePrefix(PIC_PREFIX).toIntOrNull()?.let { Pictures.blip(data, it) } else null }
    }

    private const val PIC_PREFIX = "doc-picture/"


    // ---------------------------------------------------------------- 画像(Data ストリームの中の OfficeArt)

    private object Pictures {
        /** 画像の大きさ(pt)。PICF の dxaGoal / dyaGoal と拡大率から。 */
        fun size(data: ByteArray, off: Int): Pair<Float, Float>? {
            if (off < 0 || off + 36 > data.size) return null
            val w = u16(data, off + 28)
            val h = u16(data, off + 30)
            val mx = u16(data, off + 32)
            val my = u16(data, off + 34)
            if (w <= 0 || h <= 0) return null
            return (w / 20f * (if (mx > 0) mx / 1000f else 1f)) to (h / 20f * (if (my > 0) my / 1000f else 1f))
        }

        /** off にある PICF の後ろにある、JPEG / PNG の画像を取り出す(EMF・WMF・DIB などは null)。 */
        fun blip(data: ByteArray, off: Int): ByteArray? {
            if (off < 0 || off + 68 > data.size) return null
            val lcb = le32(data, off)
            val cbHeader = u16(data, off + 4)
            val end = minOf(data.size.toLong(), off.toLong() + lcb).toInt()
            var p = off + cbHeader
            if (p >= end) return null
            if (u16(data, off + 6) == 0x66 && p < end) p += 1 + (data[p].toInt() and 0xFF) // 名前つき(MM_SHAPEFILE)
            return scan(data, p, end, 0)
        }

        private fun scan(d: ByteArray, from: Int, end: Int, depth: Int): ByteArray? {
            var p = from
            while (p + 8 <= end && depth < 8) {
                val vi = u16(d, p)
                val type = u16(d, p + 2)
                val len = le32(d, p + 4)
                val body = p + 8
                if (len < 0 || body.toLong() + len > end) return null
                val ver = vi and 0xF
                val inst = vi shr 4
                when {
                    type == 0xF01D || type == 0xF02A || type == 0xF01E -> {
                        val two = when (type) { 0xF01E -> inst == 0x6E1; else -> inst == 0x46B || inst == 0x6E3 }
                        val skip = 16 + (if (two) 16 else 0) + 1
                        if (len > skip) return d.copyOfRange(body + skip, body + len)
                    }
                    type == 0xF007 -> { // FBSE: 36 バイトの見出しと名前の後ろに、画像が入っている
                        if (len > 36) {
                            val nameLen = d[body + 33].toInt() and 0xFF
                            scan(d, body + 36 + nameLen, body + len, depth + 1)?.let { return it }
                        }
                    }
                    ver == 0xF -> scan(d, body, body + len, depth + 1)?.let { return it }
                }
                p = body + len
            }
            return null
        }
    }

    // ---------------------------------------------------------------- 箇条書き・番号(PlfLst / PlfLfo)

    private class Level(val nfc: Int, val start: Int, val text: IntArray, val left: Int?, val first: Int?)

    private class Lists(val levels: Map<Int, List<Level>>, val lfoLsid: List<Int>)

    private fun readLists(table: ByteArray, f: Fib): Lists {
        val levels = HashMap<Int, List<Level>>()
        val lfo = ArrayList<Int>()
        try {
            if (f.lcbPlfLst >= 2 && f.fcPlfLst >= 0 && f.fcPlfLst + f.lcbPlfLst <= table.size) {
                var p = f.fcPlfLst
                val headEnd = p + f.lcbPlfLst
                val cLst = u16(table, p)
                p += 2
                val heads = ArrayList<Pair<Int, Int>>() // lsid, レベルの数
                for (i in 0 until cLst) {
                    if (p + 28 > headEnd) break
                    heads.add(le32(table, p) to (if ((table[p + 26].toInt() and 1) != 0) 1 else 9))
                    p += 28
                }
                // 各リストの段(LVL)は、PlfLst の配列(lcb の範囲)のすぐ後ろに続く
                val lvEnd = if (f.fcPlfLfo > p) minOf(f.fcPlfLfo, table.size) else table.size

                for ((lsid, n) in heads) {
                    val lv = ArrayList<Level>()
                    for (k in 0 until n) {
                        if (p + 28 > lvEnd) break
                        val start = le32(table, p)
                        val nfc = table[p + 4].toInt() and 0xFF
                        val cbChpx = table[p + 24].toInt() and 0xFF
                        val cbPapx = table[p + 25].toInt() and 0xFF
                        val papx = table.copyOfRange(p + 28, minOf(lvEnd, p + 28 + cbPapx))
                        p += 28 + cbPapx + cbChpx
                        if (p + 2 > lvEnd) break
                        val cch = u16(table, p)
                        p += 2
                        val chars = IntArray(cch.coerceAtMost((lvEnd - p) / 2)) { u16(table, p + it * 2) }
                        p += cch * 2
                        val pa = readParas(papx)
                        lv.add(Level(nfc, start, chars, pa.left, pa.firstLine))
                    }
                    levels[lsid] = lv
                }
            }
            if (f.lcbPlfLfo >= 4 && f.fcPlfLfo >= 0 && f.fcPlfLfo + f.lcbPlfLfo <= table.size) {
                val n = le32(table, f.fcPlfLfo)
                for (i in 0 until n.coerceIn(0, 4096)) {
                    val o = f.fcPlfLfo + 4 + i * 16
                    if (o + 16 > table.size) break
                    lfo.add(le32(table, o))
                }
            }
        } catch (e: Exception) {
            // 番号の定義が壊れていても、文書は読む(箇条書きの記号は出さない)
        }
        return Lists(levels, lfo)
    }

    // ---------------------------------------------------------------- FIB

    private class Fib(w: ByteArray) {
        val ccpText: Int
        val fcClx: Int
        val lcbClx: Int
        val fcStshf: Int
        val lcbStshf: Int
        val fcPlcfBteChpx: Int
        val lcbPlcfBteChpx: Int
        val fcPlcfBtePapx: Int
        val lcbPlcfBtePapx: Int
        val fcPlfLst: Int
        val lcbPlfLst: Int
        val fcPlfLfo: Int
        val lcbPlfLfo: Int

        init {
            val csw = u16(w, 32)
            val lwStart = 34 + csw * 2
            val cslw = u16(w, lwStart)
            val lwBase = lwStart + 2
            ccpText = if (cslw > 3) le32(w, lwBase + 12) else 0
            val fcStart = lwBase + cslw * 4
            val cb = u16(w, fcStart)
            val base = fcStart + 2
            fun fc(i: Int) = if (i < cb) le32(w, base + i * 8) else 0
            fun lcb(i: Int) = if (i < cb) le32(w, base + i * 8 + 4) else 0
            fcStshf = fc(1); lcbStshf = lcb(1)
            fcPlcfBteChpx = fc(12); lcbPlcfBteChpx = lcb(12)
            fcPlcfBtePapx = fc(13); lcbPlcfBtePapx = lcb(13)
            fcClx = fc(33); lcbClx = lcb(33)
            fcPlfLst = fc(73); lcbPlfLst = lcb(73)
            fcPlfLfo = fc(74); lcbPlfLfo = lcb(74)
        }
    }

    // ---------------------------------------------------------------- ピース表(文字の置き場所)

    private class Piece(val cpStart: Int, val cpEnd: Int, val fc: Int, val compressed: Boolean) {
        fun fcOf(cp: Int): Int = fc + (cp - cpStart) * (if (compressed) 1 else 2)
    }

    private fun readPieces(word: ByteArray, table: ByteArray, f: Fib): List<Piece> {
        if (f.lcbClx <= 0 || f.fcClx < 0 || f.fcClx + f.lcbClx > table.size) throw OpException(Txt(R.string.doc_005))
        var p = f.fcClx
        val end = f.fcClx + f.lcbClx
        while (p < end && (table[p].toInt() and 0xFF) == 1) p += 3 + u16(table, p + 1) // Prc を飛ばす
        if (p >= end || (table[p].toInt() and 0xFF) != 2) throw OpException(Txt(R.string.doc_006))
        val lcb = le32(table, p + 1)
        val plc = p + 5
        val n = (lcb - 4) / 12
        if (n <= 0 || plc + lcb > table.size) throw OpException(Txt(R.string.doc_007))
        val out = ArrayList<Piece>()
        for (i in 0 until n) {
            val cp0 = le32(table, plc + i * 4)
            val cp1 = le32(table, plc + (i + 1) * 4)
            val fcRaw = le32(table, plc + (n + 1) * 4 + i * 8 + 2)
            val compressed = (fcRaw and 0x40000000) != 0
            val fc = if (compressed) (fcRaw and 0x3FFFFFFF) / 2 else fcRaw
            if (cp1 > cp0) out.add(Piece(cp0, cp1, fc, compressed))
        }
        return out
    }

    private fun buildText(word: ByteArray, pieces: List<Piece>, ccpText: Int): String {
        val total = pieces.last().cpEnd
        val limit = if (ccpText in 1..total) ccpText else total
        val sb = StringBuilder(limit)
        for (pc in pieces) {
            if (pc.cpStart >= limit) break
            val n = minOf(pc.cpEnd, limit) - pc.cpStart
            val bytes = if (pc.compressed) n else n * 2
            if (pc.fc < 0 || pc.fc + bytes > word.size) break
            sb.append(if (pc.compressed) String(word, pc.fc, bytes, CP1252) else String(word, pc.fc, bytes, Charsets.UTF_16LE))
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- 書式の表(FKP)

    private class Fkp(val starts: IntArray, val ends: IntArray, val data: Array<ByteArray?>, val istd: IntArray) {
        fun find(fc: Int): Int {
            var lo = 0
            var hi = starts.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                when {
                    fc < starts[mid] -> hi = mid - 1
                    fc >= ends[mid] -> lo = mid + 1
                    else -> return mid
                }
            }
            return -1
        }
    }

    private fun readFkps(word: ByteArray, table: ByteArray, fc: Int, lcb: Int, papx: Boolean): Fkp {
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        val data = ArrayList<ByteArray?>()
        val istds = ArrayList<Int>()
        if (lcb >= 8 && fc >= 0 && fc + lcb <= table.size) {
            val n = (lcb - 4) / 8
            for (i in 0 until n) {
                val pn = le32(table, fc + (n + 1) * 4 + i * 4) and 0x3FFFFF
                val page = pn.toLong() * 512
                if (page + 512 > word.size) continue
                val pg = page.toInt()
                val crun = word[pg + 511].toInt() and 0xFF
                for (j in 0 until crun) {
                    val s = le32(word, pg + j * 4)
                    val e = le32(word, pg + (j + 1) * 4)
                    var grp: ByteArray? = null
                    var istd = 0
                    if (papx) {
                        val b = word[pg + (crun + 1) * 4 + j * 13].toInt() and 0xFF
                        if (b != 0) {
                            val o = pg + b * 2
                            var cb = word[o].toInt() and 0xFF
                            var from = o + 1
                            val len: Int
                            if (cb == 0) { cb = word[o + 1].toInt() and 0xFF; from = o + 2; len = cb * 2 } else len = cb * 2 - 1
                            if (len >= 2 && from + len <= word.size) {
                                istd = u16(word, from)
                                grp = word.copyOfRange(from + 2, from + len)
                            }
                        }
                    } else {
                        val b = word[pg + (crun + 1) * 4 + j].toInt() and 0xFF
                        if (b != 0) {
                            val o = pg + b * 2
                            val cb = word[o].toInt() and 0xFF
                            if (o + 1 + cb <= word.size) grp = word.copyOfRange(o + 1, o + 1 + cb)
                        }
                    }
                    starts.add(s); ends.add(e); data.add(grp); istds.add(istd)
                }
            }
        }
        // fc の順に並べる(FKP は fc の順に並んでいるはずだが、念のため)
        val order = starts.indices.sortedBy { starts[it] }
        return Fkp(
            IntArray(order.size) { starts[order[it]] }, IntArray(order.size) { ends[order[it]] },
            Array(order.size) { data[order[it]] }, IntArray(order.size) { istds[order[it]] },
        )
    }

    // ---------------------------------------------------------------- 書式の指定(sprm)

    /** 文字の書式。null は指定なし。 */
    private class Ch {
        var bold: Boolean? = null
        var italic: Boolean? = null
        var strike: Boolean? = null
        var vanish: Boolean? = null
        var underline: Boolean? = null
        var hps: Int? = null
        var ico: Int? = null
        var cv: Int? = null
        var highlight: Int? = null
        var iss: Int? = null
        var istd: Int? = null
        var pic: Int? = null
        var spec: Boolean? = null
        var font: Int? = null

        fun over(b: Ch): Ch {
            val r = Ch()
            r.bold = bold ?: b.bold; r.italic = italic ?: b.italic; r.strike = strike ?: b.strike; r.vanish = vanish ?: b.vanish
            r.underline = underline ?: b.underline; r.hps = hps ?: b.hps; r.ico = ico ?: b.ico; r.cv = cv ?: b.cv
            r.highlight = highlight ?: b.highlight; r.iss = iss ?: b.iss; r.istd = istd ?: b.istd; r.pic = pic ?: b.pic
            r.spec = spec ?: b.spec; r.font = font ?: b.font
            return r
        }
    }

    private class Pa {
        var istd: Int? = null
        var jc: Int? = null
        var inTable: Boolean? = null
        var ttp: Boolean? = null
        var left: Int? = null
        var firstLine: Int? = null
        var before: Int? = null
        var after: Int? = null
        var line: Int? = null
        var lineMult: Boolean? = null
        var pageBreak: Boolean? = null
        var ilfo: Int? = null
        var ilvl: Int? = null
        var cells: IntArray? = null // 表の列の境界(rgdxaCenter)
        var tcFlags: IntArray? = null

        fun over(b: Pa): Pa {
            val r = Pa()
            r.istd = istd ?: b.istd; r.jc = jc ?: b.jc; r.inTable = inTable ?: b.inTable; r.ttp = ttp ?: b.ttp; r.left = left ?: b.left
            r.firstLine = firstLine ?: b.firstLine; r.before = before ?: b.before; r.after = after ?: b.after; r.line = line ?: b.line
            r.lineMult = lineMult ?: b.lineMult; r.pageBreak = pageBreak ?: b.pageBreak; r.ilfo = ilfo ?: b.ilfo; r.ilvl = ilvl ?: b.ilvl
            r.cells = cells ?: b.cells; r.tcFlags = tcFlags ?: b.tcFlags
            return r
        }
    }

    private fun sprmSize(op: Int, g: ByteArray, p: Int): Int {
        return when (op ushr 13) {
            0, 1 -> 1
            2, 4, 5 -> 2
            3 -> 4
            7 -> 3
            else -> when (op) {
                0xD608 -> if (p + 2 <= g.size) u16(g, p) + 1 else g.size - p // 表の定義は、先頭の 2 バイトが大きさ
                0xC615 -> if (p < g.size) { val cb = g[p].toInt() and 0xFF; if (cb == 255) g.size - p else cb + 1 } else 0
                else -> if (p < g.size) (g[p].toInt() and 0xFF) + 1 else 0
            }
        }
    }

    private fun toggle(v: Int): Boolean? = when (v and 0xFF) { 0 -> false; 1 -> true; else -> null }

    private fun readChars(g: ByteArray?, into: Ch = Ch()): Ch {
        g ?: return into
        var p = 0
        while (p + 2 <= g.size) {
            val op = u16(g, p)
            p += 2
            val sz = sprmSize(op, g, p)
            if (p + sz > g.size) break
            val b0 = if (sz >= 1) g[p].toInt() and 0xFF else 0
            when (op) {
                0x0835 -> into.bold = toggle(b0)
                0x0836 -> into.italic = toggle(b0)
                0x0837 -> into.strike = toggle(b0)
                0x083C -> into.vanish = toggle(b0)
                0x2A3E -> into.underline = b0 != 0
                0x2A42 -> into.ico = b0
                0x4A43 -> into.hps = u16(g, p)
                0x2A48 -> into.iss = b0
                0x2A0C -> into.highlight = b0
                0x6870 -> if (sz >= 4) into.cv = if ((g[p + 3].toInt() and 0xFF) == 0xFF) null else (0xFF shl 24) or (b0 shl 16) or ((g[p + 1].toInt() and 0xFF) shl 8) or (g[p + 2].toInt() and 0xFF)
                0x4A30 -> into.istd = u16(g, p)
                0x6A03 -> into.pic = le32(g, p)
                0x0855 -> into.spec = toggle(b0)
                0x4A4F -> into.font = u16(g, p)
            }
            p += sz
        }
        return into
    }

    private fun readParas(g: ByteArray?, into: Pa = Pa()): Pa {
        g ?: return into
        var p = 0
        while (p + 2 <= g.size) {
            val op = u16(g, p)
            p += 2
            val sz = sprmSize(op, g, p)
            if (p + sz > g.size) break
            val b0 = if (sz >= 1) g[p].toInt() and 0xFF else 0
            fun s16() = u16(g, p).let { if (it >= 0x8000) it - 0x10000 else it }
            when (op) {
                0x4600 -> into.istd = u16(g, p)
                0x2403, 0x2461 -> into.jc = b0
                0x2416 -> into.inTable = b0 != 0
                0x2417 -> into.ttp = b0 != 0
                0x840F, 0x845E -> into.left = s16()
                0x8411, 0x8460 -> into.firstLine = s16()
                0xA413 -> into.before = u16(g, p)
                0xA414 -> into.after = u16(g, p)
                0x6412 -> if (sz >= 4) { into.line = s16(); into.lineMult = u16(g, p + 2) == 1 }
                0x2407 -> into.pageBreak = b0 != 0
                0x460B -> into.ilfo = u16(g, p)
                0x260A -> into.ilvl = b0
                0xD608 -> if (sz >= 4) {
                    val n = g[p + 2].toInt() and 0xFF
                    if (n in 1..63 && p + 3 + (n + 1) * 2 <= g.size) {
                        into.cells = IntArray(n + 1) { i -> u16(g, p + 3 + i * 2).let { v -> if (v >= 0x8000) v - 0x10000 else v } }
                        val tc = p + 3 + (n + 1) * 2
                        if (tc + n * 20 <= p + sz) into.tcFlags = IntArray(n) { i -> u16(g, tc + i * 20) }
                    }
                }
            }
            p += sz
        }
        return into
    }

    // ---------------------------------------------------------------- スタイル

    private class Style(val sti: Int, val base: Int, val sgc: Int, val chp: Ch, val pap: Pa)

    private fun readStyles(table: ByteArray, fc: Int, lcb: Int): Map<Int, Style> {
        val out = HashMap<Int, Style>()
        if (lcb < 20 || fc < 0 || fc + lcb > table.size) return out
        val cbStshi = u16(table, fc)
        val cstd = u16(table, fc + 2)
        val cbBase = u16(table, fc + 4)
        var p = fc + 2 + cbStshi
        val end = fc + lcb
        for (i in 0 until cstd) {
            if (p + 2 > end) break
            val cb = u16(table, p)
            p += 2
            if (cb == 0) continue
            val stdEnd = p + cb
            if (stdEnd > end || cbBase < 10 || cbBase > cb) { p = stdEnd; continue }
            val w0 = u16(table, p)
            val w1 = u16(table, p + 2)
            val w2 = u16(table, p + 4)
            val sti = w0 and 0x0FFF
            val sgc = w1 and 0x0F
            val base = (w1 shr 4) and 0x0FFF
            val cupx = w2 and 0x0F
            var q = p + cbBase
            if (q + 2 > stdEnd) { p = stdEnd; continue }
            val nameLen = u16(table, q)
            q += 2 + (nameLen + 1) * 2
            if (q % 2 != 0) q++
            var chp = Ch()
            var pap = Pa()
            for (k in 0 until cupx) {
                if (q + 2 > stdEnd) break
                val cbUpx = u16(table, q)
                q += 2
                if (q + cbUpx > stdEnd) break
                val upx = table.copyOfRange(q, q + cbUpx)
                if (sgc == 1) {
                    if (k == 0 && upx.size >= 2) pap = readParas(upx.copyOfRange(2, upx.size))
                    if (k == 1) chp = readChars(upx)
                } else if (sgc == 2 && k == 0) {
                    chp = readChars(upx)
                }
                q += cbUpx
                if (q % 2 != 0) q++
            }
            out[i] = Style(sti, if (base == 0x0FFF) -1 else base, sgc, chp, pap)
            p = stdEnd
        }
        return out
    }

    // ---------------------------------------------------------------- 組み立て

    private class CharRun(val cp0: Int, val cp1: Int, val grp: ByteArray?)

    private class Builder(
        val text: String,
        val pieces: List<Piece>,
        val chpx: Fkp,
        val papx: Fkp,
        val styles: Map<Int, Style>,
        val lists: Lists,
        val data: ByteArray?,
    ) {
        val pages = ArrayList<ArrayList<DocBlock>>().also { it.add(ArrayList()) }
        private val runsList: List<CharRun> = buildCharRuns()

        private fun buildCharRuns(): List<CharRun> {
            val out = ArrayList<CharRun>()
            for (pc in pieces) {
                if (pc.cpStart >= text.length) break
                val cpEnd = minOf(pc.cpEnd, text.length)
                var cp = pc.cpStart
                while (cp < cpEnd) {
                    val fc = pc.fcOf(cp)
                    val idx = chpx.find(fc)
                    val unit = if (pc.compressed) 1 else 2
                    if (idx < 0) {
                        // 書式の表に無い所: 次の表の始まりまで(無ければ、ピースの終わりまで)
                        val next = chpx.starts.firstOrNull { it > fc }
                        val stop = if (next == null) cpEnd else minOf(cpEnd, cp + ((next - fc + unit - 1) / unit).coerceAtLeast(1))
                        out.add(CharRun(cp, stop, null))
                        cp = stop
                    } else {
                        val runEndFc = chpx.ends[idx]
                        val n = ((runEndFc - fc + unit - 1) / unit).coerceAtLeast(1)
                        val stop = minOf(cpEnd, cp + n)
                        out.add(CharRun(cp, stop, chpx.data[idx]))
                        cp = stop
                    }
                }
            }
            return out
        }

        private fun fcAt(cp: Int): Int {
            var lo = 0
            var hi = pieces.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val pc = pieces[mid]
                when {
                    cp < pc.cpStart -> hi = mid - 1
                    cp >= pc.cpEnd -> lo = mid + 1
                    else -> return pc.fcOf(cp)
                }
            }
            return -1
        }

        private fun styleChp(istd: Int?, seen: Int = 0): Ch {
            if (istd == null || seen > 12) return Ch()
            val s = styles[istd] ?: return Ch()
            val parent = if (s.base >= 0 && s.base != istd) styleChp(s.base, seen + 1) else Ch()
            return s.chp.over(parent)
        }

        private fun stylePap(istd: Int?, seen: Int = 0): Pa {
            if (istd == null || seen > 12) return Pa()
            val s = styles[istd] ?: return Pa()
            val parent = if (s.base >= 0 && s.base != istd) stylePap(s.base, seen + 1) else Pa()
            return s.pap.over(parent)
        }

        private fun toRpr(c: Ch): RPr {
            val color = c.cv ?: c.ico?.let { icoColor(it) }
            return RPr(
                bold = c.bold, italic = c.italic, underline = c.underline, strike = c.strike, sizeHp = c.hps,
                color = color, font = null, highlight = c.highlight?.let { icoColor(it) },
                vert = when (c.iss) { 1 -> 1; 2 -> -1; else -> null },
            )
        }

        private fun icoColor(i: Int): Int? = when (i) {
            1 -> 0xFF000000.toInt(); 2 -> 0xFF0000FF.toInt(); 3 -> 0xFF00FFFF.toInt(); 4 -> 0xFF00FF00.toInt(); 5 -> 0xFFFF00FF.toInt()
            6 -> 0xFFFF0000.toInt(); 7 -> 0xFFFFFF00.toInt(); 8 -> 0xFFFFFFFF.toInt(); 9 -> 0xFF000080.toInt(); 10 -> 0xFF008080.toInt()
            11 -> 0xFF008000.toInt(); 12 -> 0xFF800080.toInt(); 13 -> 0xFF800000.toInt(); 14 -> 0xFF808000.toInt(); 15 -> 0xFF808080.toInt()
            16 -> 0xFFC0C0C0.toInt()
            else -> null
        }

        private class FieldState(val instr: StringBuilder = StringBuilder(), var inInstr: Boolean = true, var link: String? = null)

        // 表を組み立てている途中
        private var tableRows = ArrayList<List<DocBlock.Cell>>()
        private var rowCells = ArrayList<DocBlock.Cell>()
        private var cellBlocks = ArrayList<DocBlock>()
        private var inTableNow = false
        private val pendingPics = ArrayList<Int>()
        private val counters = HashMap<Long, Int>()

        fun build(): DocxDoc {
            val runs = ArrayList<DocRun>()
            val fields = ArrayList<FieldState>()
            var paraStart = 0
            var runIdx = 0
            val n = text.length

            fun currentLink(): String? = fields.lastOrNull { !it.inInstr }?.link
            fun inInstruction(): Boolean = fields.any { it.inInstr }

            fun flushRun(cp0: Int, cp1: Int, direct: Ch, paraStyle: Int) {
                // cp0 〜 cp1 の文字から、見えるものだけを runs に足す
                val sb = StringBuilder()
                for (cp in cp0 until cp1) {
                    val ch = text[cp]
                    when (ch.code) {
                        0x13 -> { fields.add(FieldState()); continue }
                        0x14 -> {
                            fields.lastOrNull()?.let { f ->
                                f.inInstr = false
                                val s = f.instr.toString().trim()
                                if (s.startsWith("HYPERLINK", ignoreCase = true)) {
                                    Regex("\"([^\"]+)\"").find(s)?.let { f.link = it.groupValues[1] }
                                }
                            }
                            continue
                        }
                        0x15 -> { if (fields.isNotEmpty()) fields.removeAt(fields.size - 1); continue }
                    }
                    if (inInstruction()) { fields.last { it.inInstr }.instr.append(ch); continue }
                    when (ch.code) {
                        0x0D, 0x07, 0x0C -> {}
                        0x0B -> sb.append('\n')
                        0x01 -> if (direct.spec == true && direct.pic != null) pendingPics.add(direct.pic!!)
                        0x08, 0x02 -> {}
                        0x1E -> sb.append('-')
                        0x1F -> {}
                        0x00 -> {}
                        else -> if (ch.code >= 0x20 || ch == '\t') sb.append(ch)
                    }
                }
                if (sb.isEmpty() || direct.vanish == true) return
                val styleC = styleChp(direct.istd).over(styleChp(paraStyle))
                runs.add(DocRun(sb.toString(), toRpr(direct.over(styleC)), currentLink()))
            }

            var i = 0
            while (i < n) {
                val c = text[i]
                if (c == '\r' || c == '\u0007' || c == '\u000C') {
                    val endCp = i
                    // 段落の書式: 段落記号の fc にある PAPX
                    val fc = fcAt(endCp)
                    val pidx = if (fc >= 0) papx.find(fc) else -1
                    val direct = if (pidx >= 0) readParas(papx.data[pidx]) else Pa()
                    val istd = if (pidx >= 0) papx.istd[pidx] else 0
                    val ps = stylePap(istd)
                    val pa = direct.over(ps)
                    // この段落の文字(paraStart 〜 endCp)
                    runs.clear()
                    var cp = paraStart
                    while (runIdx > 0 && runsList[runIdx].cp0 > cp) runIdx--
                    while (runIdx < runsList.size && runsList[runIdx].cp1 <= cp) runIdx++
                    var k = runIdx
                    while (cp < endCp && k < runsList.size) {
                        val r = runsList[k]
                        if (r.cp1 <= cp) { k++; continue }
                        if (r.cp0 > cp) { // 書式の表に無い隙間
                            val gapEnd = minOf(endCp, r.cp0)
                            flushRun(cp, gapEnd, Ch(), istd)
                            cp = gapEnd
                            continue
                        }
                        val b = minOf(endCp, r.cp1)
                        flushRun(cp, b, readChars(r.grp), istd)
                        cp = b
                    }
                    runIdx = k.coerceAtMost((runsList.size - 1).coerceAtLeast(0))
                    if (cp < endCp) flushRun(cp, endCp, Ch(), istd)
                    paragraphEnded(c, runs, pa, istd, endCp)
                    paraStart = i + 1
                }
                i++
            }
            if (paraStart < n) {
                runs.clear()
                flushRun(paraStart, n, Ch(), 0)
                if (runs.isNotEmpty()) paragraphEnded('\r', runs, Pa(), 0, n)
            }
            finishTable()
            val out = pages.filter { it.isNotEmpty() }
            return DocxDoc(if (out.isEmpty()) listOf(emptyList()) else out)
        }

        private fun headingLevel(istd: Int): Int {
            val s = styles[istd] ?: return 0
            return when (s.sti) { in 1..9 -> s.sti; 62 -> 0; else -> 0 }
        }

        private fun takePics(): List<DocBlock.Image> {
            if (pendingPics.isEmpty()) return emptyList()
            val out = ArrayList<DocBlock.Image>()
            val d = data
            if (d != null) for (off in pendingPics) {
                if (Pictures.blip(d, off) == null) continue
                val (w, h) = Pictures.size(d, off) ?: (200f to 150f)
                out.add(DocBlock.Image(PIC_PREFIX + off, w, h))
            }
            pendingPics.clear()
            return out
        }

        private fun paragraphEnded(end: Char, runs: List<DocRun>, pa: Pa, istd: Int, cp: Int) {
            val hasPageBreakChar = end == '\u000C'
            val isCell = end == '\u0007'
            val pics = takePics()
            if (isCell) {
                val rowEnd = pa.ttp == true || pa.cells != null
                if (rowEnd) {
                    finishRow(pa)
                } else {
                    cellBlocks.add(makePara(runs, pa, istd))
                    cellBlocks.addAll(pics)
                    rowCells.add(DocBlock.Cell(ArrayList(cellBlocks), 1, null))
                    cellBlocks = ArrayList()
                    inTableNow = true
                }
                return
            }
            if (pa.inTable == true && end == '\r') {
                // 表の中の、セル内の 2 つ目以降の段落
                cellBlocks.add(makePara(runs, pa, istd))
                cellBlocks.addAll(pics)
                inTableNow = true
                return
            }
            finishTable()
            if (hasPageBreakChar) {
                if (runs.isNotEmpty()) pages.last().add(makePara(runs, pa, istd))
                pages.last().addAll(pics)
                if (pages.last().isNotEmpty()) pages.add(ArrayList())
                return
            }
            if (pa.pageBreak == true && pages.last().isNotEmpty()) pages.add(ArrayList())
            pages.last().add(makePara(runs, pa, istd))
            pages.last().addAll(pics)
        }

        // ---- 箇条書き・番号

        private fun number(nfc: Int, n: Int): String = when (nfc) {
            0 -> n.toString()
            1 -> SlideText.autoNumber("romanUcPeriod", n).dropLast(1)
            2 -> SlideText.autoNumber("romanLcPeriod", n).dropLast(1)
            3 -> SlideText.autoNumber("alphaUcPeriod", n).dropLast(1)
            4 -> SlideText.autoNumber("alphaLcPeriod", n).dropLast(1)
            5 -> n.toString() + (if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" })
            22 -> n.toString().padStart(2, '0')
            else -> n.toString()
        }

        private class ListMark(val text: String?, val left: Int?, val first: Int?)

        private fun listMarker(pa: Pa): ListMark? {
            val ilfo = pa.ilfo ?: return null
            if (ilfo <= 0 || ilfo > lists.lfoLsid.size) return null
            val lsid = lists.lfoLsid[ilfo - 1]
            val lv = lists.levels[lsid]?.takeIf { it.isNotEmpty() } ?: return null
            val lvl = (pa.ilvl ?: 0).coerceIn(0, lv.size - 1)
            val me = lv.getOrNull(lvl) ?: return null
            // この段の数を 1 つ進め、それより深い段は 0 に戻す
            val key = lsid.toLong() * 16
            val cur = (counters[key + lvl] ?: (me.start - 1)) + 1
            counters[key + lvl] = cur
            for (d in lvl + 1 until 9) counters.remove(key + d)
            if (me.nfc == 255) return ListMark(null, me.left, me.first)
            if (me.nfc == 23) {
                val c = me.text.firstOrNull() ?: 0x2022
                val s = when {
                    c in 0xF000..0xF0FF -> when (c) { 0xF0B7 -> "•"; 0xF0A7 -> "■"; 0xF076 -> "❖"; 0xF0D8 -> "➢"; 0xF0FC -> "✓"; 0xF0A8 -> "□"; else -> "•" }
                    c < 0x20 -> "•"
                    else -> c.toChar().toString()
                }
                return ListMark(s, me.left, me.first)
            }
            val sb = StringBuilder()
            for (c in me.text) {
                if (c < 9) {
                    val l = lv.getOrNull(c) ?: continue
                    val v = if (c == lvl) cur else (counters[key + c] ?: l.start)
                    sb.append(number(l.nfc, v))
                } else {
                    sb.append(c.toChar())
                }
            }
            return ListMark(sb.toString(), me.left, me.first)
        }

        private fun makePara(runs: List<DocRun>, pa: Pa, istd: Int): DocBlock.Para {
            val heading = headingLevel(istd)
            val mark = listMarker(pa)
            val left = (pa.left ?: mark?.left ?: 0).coerceAtLeast(0)
            val first = pa.firstLine ?: mark?.first ?: 0
            val mult = when {
                pa.line == null || pa.line == 0 -> 1f
                pa.lineMult == true -> pa.line!! / 240f
                else -> 1f
            }
            val rs = if (runs.isEmpty()) listOf(DocRun("", RPr())) else ArrayList(runs)
            return DocBlock.Para(
                runs = rs, heading = heading,
                align = when (pa.jc) { 1 -> "center"; 2 -> "right"; 3, 4 -> "both"; else -> "left" },
                leftPt = left / 20f, firstLinePt = if (first > 0) first / 20f else 0f, hangingPt = if (first < 0) -first / 20f else 0f,
                beforePt = (pa.before ?: 0) / 20f, afterPt = (pa.after ?: 0) / 20f, lineMult = mult,
                marker = mark?.text, fill = null,
            )
        }

        // ---- 表

        private class RowDef(val cells: List<DocBlock.Cell>, val bounds: IntArray?, val tc: IntArray?)

        private val rowDefs = ArrayList<RowDef>()

        private fun finishRow(pa: Pa) {
            if (rowCells.isNotEmpty()) rowDefs.add(RowDef(ArrayList(rowCells), pa.cells, pa.tcFlags))
            rowCells = ArrayList()
            cellBlocks = ArrayList()
        }

        private fun finishTable() {
            if (rowCells.isNotEmpty()) finishRow(Pa())
            if (rowDefs.isNotEmpty()) {
                // 行ごとに列の境界(twip)が違ってもよいので、全部の境界を集めて、共通の格子にする
                val all = ArrayList<Int>()
                for (r in rowDefs) r.bounds?.let { b -> if (b.size == r.cells.size + 1) b.forEach { all.add(it) } }
                all.sort()
                val grid = ArrayList<Int>()
                for (x in all) if (grid.isEmpty() || x - grid.last() > 12) grid.add(x)
                val useGrid = grid.size >= 2 && grid.size <= 65
                fun idx(x: Int): Int {
                    var best = 0
                    for (i in grid.indices) if (kotlin.math.abs(grid[i] - x) < kotlin.math.abs(grid[best] - x)) best = i
                    return best
                }
                val rows = ArrayList<List<DocBlock.Cell>>()
                var ncols = 0
                for (r in rowDefs) {
                    val b = r.bounds
                    val out = ArrayList<DocBlock.Cell>()
                    if (useGrid && b != null && b.size == r.cells.size + 1) {
                        var k = 0
                        while (k < r.cells.size) {
                            var endK = k
                            val tc = r.tc
                            if (tc != null && tc.size == r.cells.size && (tc[k] and 1) != 0) {
                                while (endK + 1 < r.cells.size && (tc[endK + 1] and 2) != 0) endK++ // 結合した続きのセル
                            }
                            val span = (idx(b[endK + 1]) - idx(b[k])).coerceAtLeast(1)
                            out.add(DocBlock.Cell(r.cells[k].blocks, span, null))
                            k = endK + 1
                        }
                    } else {
                        out.addAll(r.cells)
                    }
                    rows.add(out)
                    ncols = maxOf(ncols, out.sumOf { it.span })
                }
                val weights = if (useGrid && grid.size - 1 == ncols) List(ncols) { (grid[it + 1] - grid[it]).coerceAtLeast(1) } else List(ncols.coerceAtLeast(1)) { 1000 }
                pages.last().add(DocBlock.Table(rows, weights, true))
                rowDefs.clear()
            }
            inTableNow = false
        }
    }
    private fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, o: Int): Int = Ole2.le32(b, o)
}
