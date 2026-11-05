package local.filemanager

/**
 * 古い Excel(.xls。Excel 97〜2003 = BIFF8)の読み取り。シート・セルの値・表示形式・書式・結合・列幅・枠の固定。
 * Excel(.xlsx)と同じ表示で出すため SheetBook を実装する。Android に依存しない。
 */
class XlsBook(private val ole: Ole2) : SheetBook {
    private val wb: ByteArray = ole.stream("Workbook") ?: ole.stream("Book") ?: throw OpException(Txt(R.string.xls_001))

    private class Sheet(val name: String, val pos: Int)

    private val sheets = ArrayList<Sheet>()
    private val sst = ArrayList<String>()
    private val formats = HashMap<Int, String>()
    private val fonts = ArrayList<FontRec>()
    private val xfs = ArrayList<XfRec>()
    private val palette = HashMap<Int, Int>()
    private var date1904 = false

    private class FontRec(val bold: Boolean, val italic: Boolean, val colorIdx: Int, val sizePt: Float)
    private class XfRec(
        val font: Int, val fmt: Int, val halign: Int, val wrap: Boolean, val border: Boolean, val pattern: Int, val fg: Int, val bg: Int,
    )

    override val sheetNames: List<String> get() = sheets.map { it.name }

    private val styleList: List<XlsxStyle> by lazy { xfs.map { toStyle(it) }.ifEmpty { listOf(XlsxStyle(0, null, false, false, null, null, false, null, false, null)) } }
    override val styles: List<XlsxStyle> get() = styleList

    init {
        if (wb.size < 8 || u16(wb, 0) != 0x0809) throw OpException(Txt(R.string.xls_002))
        val ver = u16(wb, 4)
        if (ver != 0x0600) throw OpException(Txt(R.string.xls_003))
        readGlobals()
        if (sheets.isEmpty()) throw OpException(Txt(R.string.xls_004))
    }

    // ---------------------------------------------------------------- レコード

    private class Rec(val id: Int, val off: Int, val len: Int)

    private inline fun records(start: Int, end: Int = wb.size, f: (Rec) -> Boolean) {
        var p = start
        while (p + 4 <= end) {
            val id = u16(wb, p)
            val len = u16(wb, p + 2)
            if (p + 4 + len > wb.size) break
            if (!f(Rec(id, p + 4, len))) return
            p += 4 + len
        }
    }

    /** rec の後ろに続く CONTINUE(0x3C)の中身を集める。 */
    private fun segments(rec: Rec): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        out.add(wb.copyOfRange(rec.off, rec.off + rec.len))
        var p = rec.off + rec.len
        while (p + 4 <= wb.size && u16(wb, p) == 0x003C) {
            val len = u16(wb, p + 2)
            if (p + 4 + len > wb.size) break
            out.add(wb.copyOfRange(p + 4, p + 4 + len))
            p += 4 + len
        }
        return out
    }

    private fun readGlobals() {
        var depth = 0
        records(0) { r ->
            when (r.id) {
                0x0809 -> depth++
                0x000A -> { depth--; if (depth <= 0) return@records false }
                0x0022 -> if (r.len >= 2) date1904 = u16(wb, r.off) == 1
                0x0085 -> readBoundSheet(r)
                0x00FC -> readSst(r)
                0x041E -> readFormat(r)
                0x0031 -> readFont(r)
                0x00E0 -> readXf(r)
                0x0092 -> readPalette(r)
            }
            true
        }
    }

    private fun readBoundSheet(r: Rec) {
        if (r.len < 8) return
        val pos = Ole2.le32(wb, r.off)
        val type = wb[r.off + 5].toInt() and 0xFF
        val cch = wb[r.off + 6].toInt() and 0xFF
        val wide = (wb[r.off + 7].toInt() and 1) != 0
        val n = if (wide) cch * 2 else cch
        if (r.off + 8 + n > wb.size) return
        val name = if (wide) String(wb, r.off + 8, n, Charsets.UTF_16LE) else String(wb, r.off + 8, n, Charsets.ISO_8859_1)
        if (type == 0) sheets.add(Sheet(name, pos))
    }

    private fun readFormat(r: Rec) {
        if (r.len < 5) return
        val idx = u16(wb, r.off)
        val s = Segs(listOf(wb.copyOfRange(r.off + 2, r.off + r.len)))
        formats[idx] = s.unicode(2)
    }

    private fun readFont(r: Rec) {
        if (r.len < 14) return
        val h = u16(wb, r.off)
        val flags = u16(wb, r.off + 2)
        val color = u16(wb, r.off + 4)
        val weight = u16(wb, r.off + 6)
        // 組み込みの 4 番(番号が飛ぶ)に合わせ、番号 4 は空の FONT を入れて詰める
        if (fonts.size == 4) fonts.add(FontRec(false, false, 0x7FFF, 10f))
        fonts.add(FontRec(weight >= 700, (flags and 0x0002) != 0, color, h / 20f))
    }

    private fun readXf(r: Rec) {
        if (r.len < 20) return
        val font = u16(wb, r.off)
        val fmt = u16(wb, r.off + 2)
        val al = wb[r.off + 6].toInt() and 0xFF
        val lines = u16(wb, r.off + 10)
        val area = Ole2.le32(wb, r.off + 14)
        val colors = u16(wb, r.off + 18)
        xfs.add(
            XfRec(
                font, fmt, al and 7, (al and 8) != 0, (lines and 0xFFFF) != 0, (area ushr 26) and 0x3F, colors and 0x7F, (colors shr 7) and 0x7F,
            ),
        )
    }

    private fun readPalette(r: Rec) {
        if (r.len < 2) return
        val n = u16(wb, r.off)
        for (i in 0 until n) {
            val o = r.off + 2 + i * 4
            if (o + 4 > r.off + r.len) break
            palette[8 + i] = (0xFF shl 24) or ((wb[o].toInt() and 0xFF) shl 16) or ((wb[o + 1].toInt() and 0xFF) shl 8) or (wb[o + 2].toInt() and 0xFF)
        }
    }

    private fun readSst(r: Rec) {
        val segs = Segs(segments(r))
        segs.u32() // 参照の総数
        val unique = segs.u32()
        for (i in 0 until unique) {
            if (segs.atEnd()) break
            sst.add(segs.unicode(2))
        }
    }

    // ---------------------------------------------------------------- 書式

    private fun color(idx: Int): Int? {
        if (idx == 0x7FFF || idx >= 64) return null
        palette[idx]?.let { return it }
        return DEFAULT_PALETTE.getOrNull(idx)
    }

    private fun toStyle(x: XfRec): XlsxStyle {
        val f = fonts.getOrNull(x.font)
        val fill = when {
            x.pattern == 1 -> color(x.fg)
            x.pattern > 1 -> color(x.fg)?.let { fg -> color(x.bg)?.let { bg -> mix(fg, bg) } ?: fg }
            else -> null
        }
        val white = 0xFFFFFFFF.toInt()
        return XlsxStyle(
            x.fmt, formats[x.fmt], f?.bold ?: false, f?.italic ?: false, f?.let { color(it.colorIdx) },
            fill?.takeIf { it != white }, x.border,
            when (x.halign) { 1 -> "left"; 2 -> "center"; 3 -> "right"; else -> null }, x.wrap, f?.sizePt,
        )
    }

    private fun mix(a: Int, b: Int): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) + ((b shr s) and 0xFF)) / 2
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    // ---------------------------------------------------------------- シート

    override fun load(i: Int): XlsxSheet = load(i, 50_000, 256)

    fun load(i: Int, maxRows: Int, maxCols: Int): XlsxSheet {
        val sh = sheets[i]
        val rows = ArrayList<ArrayList<String>>()
        val meta = HashMap<Long, Int>()
        val widths = HashMap<Int, Float>()
        val merges = ArrayList<IntArray>()
        var truncated = false
        var frozen = 0
        var frozenFlag = false
        var paneY = 0
        var pendingFormula = -1L // 直後の STRING レコードに入る文字列の置き場所(行 * 4096 + 列)

        fun put(r: Int, c: Int, text: String, xf: Int, kind: Int) {
            if (r >= maxRows || c >= maxCols) {
                if (text.isNotEmpty()) truncated = true
                return
            }
            while (rows.size <= r) rows.add(ArrayList())
            val row = rows[r]
            while (row.size <= c) row.add("")
            row[c] = text
            val m = (xf shl 2) or kind
            if (m != 0) meta[XlsxSheet.key(r, c)] = m
        }

        fun numText(v: Double, xf: Int): String {
            if (v.isNaN() || v.isInfinite()) return "#NUM!"
            val st = styles.getOrNull(xf)
            val raw = java.math.BigDecimal(v).toString().let { if (it.length > 40) v.toString() else it }
            return when {
                st != null && XlsxFormat.isDateFormat(st.numFmtId, st.code) -> XlsxFormat.dateText(v.toString(), date1904)
                st != null -> XlsxFormat.formatNumber(v.toString(), st.code ?: XlsxFormat.builtinCode(st.numFmtId))
                else -> XlsxFormat.numberText(raw)
            }
        }

        var depth = 0
        records(sh.pos) { r ->
            when (r.id) {
                0x0809 -> depth++
                0x000A -> { depth--; if (depth <= 0) return@records false }
                else -> if (depth == 1) when (r.id) {
                    0x00FD -> if (r.len >= 10) { // LABELSST
                        val s = sst.getOrNull(Ole2.le32(wb, r.off + 6)) ?: ""
                        put(u16(wb, r.off), u16(wb, r.off + 2), s, u16(wb, r.off + 4), XlsxSheet.KIND_TEXT)
                    }
                    0x0204 -> if (r.len >= 8) { // LABEL
                        val seg = Segs(listOf(wb.copyOfRange(r.off + 6, r.off + r.len)))
                        put(u16(wb, r.off), u16(wb, r.off + 2), seg.unicode(2), u16(wb, r.off + 4), XlsxSheet.KIND_TEXT)
                    }
                    0x00D6 -> if (r.len >= 8) { // RSTRING
                        val seg = Segs(listOf(wb.copyOfRange(r.off + 6, r.off + r.len)))
                        put(u16(wb, r.off), u16(wb, r.off + 2), seg.unicode(2), u16(wb, r.off + 4), XlsxSheet.KIND_TEXT)
                    }
                    0x0203 -> if (r.len >= 14) { // NUMBER
                        val xf = u16(wb, r.off + 4)
                        put(u16(wb, r.off), u16(wb, r.off + 2), numText(java.lang.Double.longBitsToDouble(le64(wb, r.off + 6)), xf), xf, XlsxSheet.KIND_NUMBER)
                    }
                    0x027E -> if (r.len >= 10) { // RK
                        val xf = u16(wb, r.off + 4)
                        put(u16(wb, r.off), u16(wb, r.off + 2), numText(rk(Ole2.le32(wb, r.off + 6)), xf), xf, XlsxSheet.KIND_NUMBER)
                    }
                    0x00BD -> if (r.len >= 6) { // MULRK
                        val row = u16(wb, r.off)
                        val first = u16(wb, r.off + 2)
                        val n = (r.len - 6) / 6
                        for (k in 0 until n) {
                            val o = r.off + 4 + k * 6
                            val xf = u16(wb, o)
                            put(row, first + k, numText(rk(Ole2.le32(wb, o + 2)), xf), xf, XlsxSheet.KIND_NUMBER)
                        }
                    }
                    0x0201 -> if (r.len >= 6) { // BLANK(書式だけ)
                        val xf = u16(wb, r.off + 4)
                        if (xf != 0) put(u16(wb, r.off), u16(wb, r.off + 2), "", xf, 0)
                    }
                    0x00BE -> if (r.len >= 6) { // MULBLANK
                        val row = u16(wb, r.off)
                        val first = u16(wb, r.off + 2)
                        val n = (r.len - 6) / 2
                        for (k in 0 until n) {
                            val xf = u16(wb, r.off + 4 + k * 2)
                            if (xf != 0) put(row, first + k, "", xf, 0)
                        }
                    }
                    0x0205 -> if (r.len >= 8) { // BOOLERR
                        val v = wb[r.off + 6].toInt() and 0xFF
                        val isErr = (wb[r.off + 7].toInt() and 0xFF) != 0
                        put(u16(wb, r.off), u16(wb, r.off + 2), if (isErr) errorText(v) else if (v != 0) "TRUE" else "FALSE", u16(wb, r.off + 4), XlsxSheet.KIND_BOOL_ERROR)
                    }
                    0x0006 -> if (r.len >= 20) { // FORMULA(最後に計算した結果だけを見せる)
                        val row = u16(wb, r.off)
                        val col = u16(wb, r.off + 2)
                        val xf = u16(wb, r.off + 4)
                        val resTail = u16(wb, r.off + 12)
                        if (resTail == 0xFFFF) {
                            when (wb[r.off + 6].toInt() and 0xFF) {
                                0 -> { pendingFormula = row.toLong() * 4096 + col; put(row, col, "", xf, XlsxSheet.KIND_TEXT) }
                                1 -> put(row, col, if ((wb[r.off + 8].toInt() and 0xFF) != 0) "TRUE" else "FALSE", xf, XlsxSheet.KIND_BOOL_ERROR)
                                2 -> put(row, col, errorText(wb[r.off + 8].toInt() and 0xFF), xf, XlsxSheet.KIND_BOOL_ERROR)
                                else -> put(row, col, "", xf, 0)
                            }
                        } else {
                            put(row, col, numText(java.lang.Double.longBitsToDouble(le64(wb, r.off + 6)), xf), xf, XlsxSheet.KIND_NUMBER)
                        }
                    }
                    0x0207 -> if (pendingFormula >= 0) { // STRING(直前の数式の文字の結果)
                        val segs = Segs(segments(r))
                        val text = segs.unicode(2)
                        val row = (pendingFormula / 4096).toInt()
                        val col = (pendingFormula % 4096).toInt()
                        if (row < maxRows && col < maxCols && row < rows.size && col < rows[row].size) rows[row][col] = text
                        pendingFormula = -1
                    }
                    0x00E5 -> if (r.len >= 2) { // MERGEDCELLS
                        val n = u16(wb, r.off)
                        for (k in 0 until n) {
                            val o = r.off + 2 + k * 8
                            if (o + 8 > r.off + r.len) break
                            merges.add(intArrayOf(u16(wb, o), u16(wb, o + 4), u16(wb, o + 2), u16(wb, o + 6)))
                        }
                    }
                    0x007D -> if (r.len >= 12) { // COLINFO
                        val first = u16(wb, r.off)
                        val last = u16(wb, r.off + 2)
                        val w = u16(wb, r.off + 4) / 256f
                        val hidden = (u16(wb, r.off + 8) and 1) != 0
                        for (c in first..minOf(last, maxCols - 1)) widths[c] = if (hidden) 0f else w
                    }
                    0x0041 -> if (r.len >= 10) paneY = u16(wb, r.off + 2) // PANE
                    0x023E -> if (r.len >= 2) frozenFlag = (u16(wb, r.off) and 0x0008) != 0 // WINDOW2
                }
            }
            true
        }
        if (frozenFlag) frozen = paneY
        // 最後の何もない行は落とす
        while (rows.isNotEmpty() && rows.last().all { it.isEmpty() } && (0 until rows.last().size).none { c -> (meta[XlsxSheet.key(rows.size - 1, c)] ?: 0) shr 2 != 0 }) {
            rows.removeAt(rows.size - 1)
        }
        return XlsxSheet(sh.name, rows, truncated, meta, widths.filterValues { it > 0f }, merges, frozen)
    }

    private fun errorText(code: Int): String = when (code) {
        0x00 -> "#NULL!"
        0x07 -> "#DIV/0!"
        0x0F -> "#VALUE!"
        0x17 -> "#REF!"
        0x1D -> "#NAME?"
        0x24 -> "#NUM!"
        0x2A -> "#N/A"
        else -> "#ERR"
    }

    private fun rk(v: Int): Double {
        val d: Double = if ((v and 2) != 0) (v shr 2).toDouble() else java.lang.Double.longBitsToDouble((v.toLong() and 0xFFFFFFFCL) shl 32)
        return if ((v and 1) != 0) d / 100.0 else d
    }

    /** BIFF8 の文字列を、CONTINUE をまたいで読む。 */
    internal class Segs(private val segs: List<ByteArray>) {
        private var si = 0
        private var pos = 0

        fun atEnd(): Boolean {
            var s = si
            var p = pos
            while (s < segs.size) {
                if (p < segs[s].size) return false
                s++
                p = 0
            }
            return true
        }

        private fun nextSeg(): Boolean {
            if (si + 1 >= segs.size) return false
            si++
            pos = 0
            return true
        }

        fun u8(): Int {
            while (pos >= segs[si].size) if (!nextSeg()) return 0
            return segs[si][pos++].toInt() and 0xFF
        }

        fun u16(): Int = u8() or (u8() shl 8)
        fun u32(): Int = u16() or (u16() shl 16)

        fun skip(n: Int) {
            var k = n
            while (k > 0) {
                if (pos >= segs[si].size) if (!nextSeg()) return
                val t = minOf(k, segs[si].size - pos)
                pos += t
                k -= t
            }
        }

        /** 長さ(lenBytes = 1 か 2 バイト)・フラグ・文字・(装飾・ふりがなの付属データは飛ばす)。 */
        fun unicode(lenBytes: Int): String {
            val cch = if (lenBytes == 1) u8() else u16()
            val flags = u8()
            var wide = (flags and 1) != 0
            val rich = (flags and 8) != 0
            val ext = (flags and 4) != 0
            val runs = if (rich) u16() else 0
            val extLen = if (ext) u32() else 0
            val sb = StringBuilder(minOf(cch, 4096))
            var left = cch
            while (left > 0) {
                if (pos >= segs[si].size) {
                    if (!nextSeg()) break
                    wide = (u8() and 1) != 0 // 続きの先頭には、文字の幅を表すフラグが 1 バイト入る
                    continue
                }
                val unit = if (wide) 2 else 1
                val avail = (segs[si].size - pos) / unit
                if (avail == 0) { pos = segs[si].size; continue }
                val take = minOf(left, avail)
                val b = segs[si]
                for (k in 0 until take) {
                    if (wide) sb.append((((b[pos + 1].toInt() and 0xFF) shl 8) or (b[pos].toInt() and 0xFF)).toChar()) else sb.append((b[pos].toInt() and 0xFF).toChar())
                    pos += unit
                }
                left -= take
            }
            if (runs > 0) skip(runs * 4)
            if (extLen > 0) skip(extLen)
            return sb.toString()
        }
    }

    companion object {
        private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        private fun le64(b: ByteArray, o: Int): Long = (Ole2.le32(b, o).toLong() and 0xFFFFFFFFL) or (Ole2.le32(b, o + 4).toLong() shl 32)

        fun open(bytes: ByteArray): XlsBook = XlsBook(Ole2(bytes))

        private val DEFAULT_PALETTE: IntArray = run {
            val rgb = intArrayOf(
                0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
                0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
                0x800000, 0x008000, 0x000080, 0x808000, 0x800080, 0x008080, 0xC0C0C0, 0x808080,
                0x9999FF, 0x993366, 0xFFFFCC, 0xCCFFFF, 0x660066, 0xFF8080, 0x0066CC, 0xCCCCFF,
                0x000080, 0xFF00FF, 0xFFFF00, 0x00FFFF, 0x800080, 0x800000, 0x008080, 0x0000FF,
                0x00CCFF, 0xCCFFFF, 0xCCFFCC, 0xFFFF99, 0x99CCFF, 0xFF99CC, 0xCC99FF, 0xFFCC99,
                0x3366FF, 0x33CCCC, 0x99CC00, 0xFFCC00, 0xFF9900, 0xFF6600, 0x666699, 0x969696,
                0x003366, 0x339966, 0x003300, 0x333300, 0x993300, 0x993366, 0x333399, 0x333333,
            )
            IntArray(rgb.size) { (0xFF shl 24) or rgb[it] }
        }
    }
}
