package local.filemanager

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/** セルの見た目(styles.xml の cellXfs 1 つ分)。色は ARGB。分からない・指定が無いものは null。 */
class XlsxStyle(
    val numFmtId: Int,
    /** 独自の表示形式の定義(組み込みの形式なら null) */
    val code: String?,
    val bold: Boolean,
    val italic: Boolean,
    val color: Int?,
    val fill: Int?,
    val border: Boolean,
    /** left / center / right / (標準・その他は null) */
    val halign: String?,
    val wrap: Boolean,
    val sizePt: Float?,
)

class XlsxSheet(
    val name: String,
    val rows: List<List<String>>,
    val truncated: Boolean,
    private val meta: Map<Long, Int> = emptyMap(),
    /** 列の番号(0 から) → 幅(文字数) */
    val colWidths: Map<Int, Float> = emptyMap(),
    /** 結合したセル [先頭の行, 先頭の列, 終わりの行, 終わりの列] */
    val merges: List<IntArray> = emptyList(),
    /** 固定された上の行数(ウィンドウ枠の固定) */
    val frozenRows: Int = 0,
) {
    val columns: Int get() = rows.maxOfOrNull { it.size } ?: 0

    /** セルの書式の番号(XlsxFile.styles の添字)。 */
    fun styleAt(r: Int, c: Int): Int = (meta[key(r, c)] ?: 0) shr 2

    /** KIND_TEXT / KIND_NUMBER / KIND_BOOL_ERROR */
    fun kindAt(r: Int, c: Int): Int = (meta[key(r, c)] ?: 0) and 3

    companion object {
        const val KIND_TEXT = 0
        const val KIND_NUMBER = 1
        const val KIND_BOOL_ERROR = 2
        fun key(r: Int, c: Int): Long = r.toLong() * 4096 + c
    }
}

/** 表計算の本(シートの並びと、セルの書式)。Excel(.xlsx)・OpenDocument(.ods)・古い Excel(.xls)で共通。 */
interface SheetBook {
    val sheetNames: List<String>
    val styles: List<XlsxStyle>
    fun load(i: Int): XlsxSheet
}

class XlsxFile(private val read: (String) -> ByteArray?) : SheetBook {
    class Ref(val name: String, val path: String)

    // sheets の読み込み(下)で書き換えるので、先に初期値を置く(後ろに置くと、読み込み後に false に戻ってしまう)
    private var date1904 = false

    val sheets: List<Ref> = readSheetList()

    private fun readSheetList(): List<Ref> {
        val wb = read("xl/workbook.xml") ?: throw OpException(Txt(R.string.xlsx_001))
        val rels = OfficeXml.relationships(read("xl/_rels/workbook.xml.rels"), "xl")
        val out = ArrayList<Ref>()
        OfficeXml.parse(
            wb,
            object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                    if (qName == "workbookPr") date1904 = a.getValue("date1904").let { it == "1" || it == "true" }
                    if (qName == "sheet") {
                        val name = a.getValue("name") ?: return
                        val rid = a.getValue("r:id")
                        val path = rid?.let { rels[it] } ?: "xl/worksheets/sheet${out.size + 1}.xml"
                        out.add(Ref(name, path))
                    }
                }
            },
        )
        if (out.isEmpty()) throw OpException(Txt(R.string.xlsx_002))
        return out
    }

    /** 共有の文字列(セルの文字は、ここに集められていて、番号で参照される)。 */
    private val shared: List<String> by lazy { readShared() }

    private fun readShared(): List<String> {
        val bytes = read("xl/sharedStrings.xml") ?: return emptyList()
        val out = ArrayList<String>()
        OfficeXml.parse(
            bytes,
            object : DefaultHandler() {
                val sb = StringBuilder()
                var inSi = false
                var inT = false
                var phonetic = 0 // ふりがなの <rPh> の中の <t> は含めない
                override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                    when (qName) {
                        "si" -> { inSi = true; sb.setLength(0) }
                        "rPh" -> phonetic++
                        "t" -> inT = inSi && phonetic == 0
                    }
                }

                override fun endElement(uri: String?, localName: String?, qName: String) {
                    when (qName) {
                        "t" -> inT = false
                        "rPh" -> phonetic--
                        "si" -> { out.add(sb.toString()); inSi = false }
                    }
                }

                override fun characters(ch: CharArray, start: Int, length: Int) {
                    if (inT) sb.append(ch, start, length)
                }
            },
        )
        return out
    }

    // ---------------------------------------------------------------- 色(テーマ・インデックス・濃淡)

    /** テーマの色(Excel の番号順: 0=背景1, 1=文字1, 2=背景2, 3=文字2, 4〜9=アクセント1〜6, 10=リンク, 11=訪問済みリンク)。 */
    private val themeColors: List<Int> by lazy { readTheme() }

    private fun readTheme(): List<Int> {
        val bytes = read("xl/theme/theme1.xml") ?: return DEFAULT_THEME
        val byName = HashMap<String, Int>()
        OfficeXml.parse(
            bytes,
            object : DefaultHandler() {
                var cur: String? = null
                override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                    when (qName) {
                        "a:dk1", "a:lt1", "a:dk2", "a:lt2", "a:accent1", "a:accent2", "a:accent3", "a:accent4", "a:accent5", "a:accent6", "a:hlink", "a:folHlink" -> cur = qName.removePrefix("a:")
                        "a:srgbClr" -> cur?.let { n -> DocxProps.hex(a.getValue("val"))?.let { byName[n] = it } }
                        "a:sysClr" -> cur?.let { n -> DocxProps.hex(a.getValue("lastClr"))?.let { byName[n] = it } }
                    }
                }

                override fun endElement(uri: String?, localName: String?, qName: String) {
                    if (qName.startsWith("a:") && qName.removePrefix("a:") == cur) cur = null
                }
            },
        )
        val order = listOf("lt1", "dk1", "lt2", "dk2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink")
        return order.mapIndexed { i, n -> byName[n] ?: DEFAULT_THEME[i] }
    }

    private fun colorOf(a: Attributes): Int? {
        a.getValue("rgb")?.let { v ->
            if (v.length == 8) return v.toLongOrNull(16)?.toInt()?.let { (it.toLong() or 0xFF000000L).toInt() }
            if (v.length == 6) return DocxProps.hex(v)
        }
        a.getValue("theme")?.toIntOrNull()?.let { t ->
            val base = themeColors.getOrNull(t) ?: return null
            return XlsxColors.tint(base, a.getValue("tint")?.toDoubleOrNull() ?: 0.0)
        }
        a.getValue("indexed")?.toIntOrNull()?.let { return XlsxColors.indexed(it) }
        return null
    }

    // ---------------------------------------------------------------- セルの書式(styles.xml)

    /** セルの書式の番号(cellXfs の順)→ 見た目。 */
    override val styles: List<XlsxStyle> by lazy { readStyles() }
    override val sheetNames: List<String> get() = sheets.map { it.name }
    override fun load(i: Int): XlsxSheet = load(i, 50_000, 256)

    private class FontInfo(val bold: Boolean, val italic: Boolean, val color: Int?, val size: Float?)

    private fun readStyles(): List<XlsxStyle> {
        val bytes = read("xl/styles.xml") ?: return emptyList()
        val custom = HashMap<Int, String>()
        val fonts = ArrayList<FontInfo>()
        val fills = ArrayList<Int?>()
        val borders = ArrayList<Boolean>()
        val out = ArrayList<XlsxStyle>()
        OfficeXml.parse(
            bytes,
            object : DefaultHandler() {
                var inFonts = false
                var inFills = false
                var inBorders = false
                var inXfs = false
                var fb = false
                var fi = false
                var fc: Int? = null
                var fsz: Float? = null
                var pat: String? = null
                var fg: Int? = null
                var anyBorder = false
                var xfNum = 0
                var xfFont = 0
                var xfFill = 0
                var xfBorder = 0
                var xfH: String? = null
                var xfWrap = false

                private fun on(a: Attributes) = a.getValue("val").let { it == null || (it != "0" && it != "false") }

                override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                    when (qName) {
                        "numFmt" -> {
                            val id = a.getValue("numFmtId")?.toIntOrNull()
                            val code = a.getValue("formatCode")
                            if (id != null && code != null) custom[id] = code
                        }
                        "fonts" -> inFonts = true
                        "fills" -> inFills = true
                        "borders" -> inBorders = true
                        "cellXfs" -> inXfs = true
                        "font" -> if (inFonts) { fb = false; fi = false; fc = null; fsz = null }
                        "b" -> if (inFonts) fb = on(a)
                        "i" -> if (inFonts) fi = on(a)
                        "sz" -> if (inFonts) fsz = a.getValue("val")?.toFloatOrNull()
                        "color" -> if (inFonts) fc = colorOf(a)
                        "fill" -> if (inFills) { pat = null; fg = null }
                        "patternFill" -> if (inFills) pat = a.getValue("patternType")
                        "fgColor" -> if (inFills) fg = colorOf(a)
                        "border" -> if (inBorders) anyBorder = false
                        "left", "right", "top", "bottom" -> if (inBorders && !a.getValue("style").isNullOrEmpty()) anyBorder = true
                        "xf" -> if (inXfs) {
                            xfNum = a.getValue("numFmtId")?.toIntOrNull() ?: 0
                            xfFont = a.getValue("fontId")?.toIntOrNull() ?: 0
                            xfFill = a.getValue("fillId")?.toIntOrNull() ?: 0
                            xfBorder = a.getValue("borderId")?.toIntOrNull() ?: 0
                            xfH = null
                            xfWrap = false
                        }
                        "alignment" -> if (inXfs) {
                            xfH = a.getValue("horizontal")
                            xfWrap = a.getValue("wrapText").let { it == "1" || it == "true" }
                        }
                    }
                }

                override fun endElement(uri: String?, localName: String?, qName: String) {
                    when (qName) {
                        "fonts" -> inFonts = false
                        "fills" -> inFills = false
                        "borders" -> inBorders = false
                        "cellXfs" -> inXfs = false
                        "font" -> if (inFonts) fonts.add(FontInfo(fb, fi, fc, fsz))
                        "fill" -> if (inFills) fills.add(if (pat == "solid") fg else null)
                        "border" -> if (inBorders) borders.add(anyBorder)
                        "xf" -> if (inXfs) {
                            val f = fonts.getOrNull(xfFont)
                            out.add(
                                XlsxStyle(
                                    xfNum, custom[xfNum], f?.bold ?: false, f?.italic ?: false, f?.color, fills.getOrNull(xfFill),
                                    borders.getOrNull(xfBorder) ?: false, xfH?.takeIf { it == "left" || it == "center" || it == "right" }, xfWrap, f?.size,
                                ),
                            )
                        }
                    }
                }
            },
        )
        return out
    }

    // ---------------------------------------------------------------- シート

    /** i 番目のシートを読む。行・列が多すぎるときは、先頭だけ(truncated)。 */
    fun load(i: Int, maxRows: Int = 50_000, maxCols: Int = 256): XlsxSheet {
        val ref = sheets[i]
        val bytes = read(ref.path) ?: throw OpException(Txt(R.string.xlsx_003, ref.name))
        val rows = ArrayList<ArrayList<String>>()
        val meta = HashMap<Long, Int>()
        val widths = HashMap<Int, Float>()
        val merges = ArrayList<IntArray>()
        var frozen = 0
        var truncated = false
        OfficeXml.parse(
            bytes,
            object : DefaultHandler() {
                var rowIdx = 0
                var colIdx = 0
                var type: String? = null
                var style = 0
                var inV = false
                var inIs = false
                var inT = false
                val v = StringBuilder()
                val isText = StringBuilder()

                override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                    when (qName) {
                        "row" -> {
                            rowIdx = (a.getValue("r")?.toIntOrNull() ?: (rowIdx + 1)) - 1
                            colIdx = 0
                        }
                        "c" -> {
                            val ref2 = a.getValue("r")
                            if (ref2 != null) colIdx = XlsxFormat.columnIndex(ref2)
                            type = a.getValue("t")
                            style = a.getValue("s")?.toIntOrNull() ?: 0
                            v.setLength(0)
                            isText.setLength(0)
                        }
                        "v" -> inV = true
                        "is" -> inIs = true
                        "t" -> inT = inIs
                        "col" -> {
                            val mn = a.getValue("min")?.toIntOrNull() ?: return
                            val mx = (a.getValue("max")?.toIntOrNull() ?: mn).coerceAtMost(maxCols)
                            val w = a.getValue("width")?.toFloatOrNull() ?: return
                            if (a.getValue("hidden") == "1") return
                            for (c in mn..mx) widths[c - 1] = w
                        }
                        "mergeCell" -> a.getValue("ref")?.let { r ->
                            val parts = r.split(':')
                            if (parts.size == 2) {
                                merges.add(
                                    intArrayOf(XlsxFormat.rowIndex(parts[0]), XlsxFormat.columnIndex(parts[0]), XlsxFormat.rowIndex(parts[1]), XlsxFormat.columnIndex(parts[1])),
                                )
                            }
                        }
                        "pane" -> if (a.getValue("state").let { it == "frozen" || it == "frozenSplit" }) {
                            frozen = a.getValue("ySplit")?.toFloatOrNull()?.toInt() ?: 0
                        }
                    }
                }

                override fun endElement(uri: String?, localName: String?, qName: String) {
                    when (qName) {
                        "v" -> inV = false
                        "t" -> inT = false
                        "is" -> inIs = false
                        "c" -> {
                            val text = cellText()
                            if (text.isNotEmpty() || style != 0) {
                                val kind = when (type) {
                                    "s", "inlineStr", "str", "d" -> XlsxSheet.KIND_TEXT
                                    "b", "e" -> XlsxSheet.KIND_BOOL_ERROR
                                    else -> XlsxSheet.KIND_NUMBER
                                }
                                put(rowIdx, colIdx, text, style, kind)
                            }
                            colIdx++
                        }
                    }
                }

                override fun characters(ch: CharArray, start: Int, length: Int) {
                    if (inV) v.append(ch, start, length) else if (inT) isText.append(ch, start, length)
                }

                fun cellText(): String {
                    val raw = v.toString().trim()
                    return when (type) {
                        "s" -> raw.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                        "inlineStr" -> isText.toString()
                        "str", "d" -> raw
                        "b" -> if (raw.isEmpty()) "" else if (raw == "1") "TRUE" else "FALSE"
                        "e" -> raw
                        else -> {
                            val st = styles.getOrNull(style)
                            when {
                                raw.isEmpty() -> ""
                                st != null && XlsxFormat.isDateFormat(st.numFmtId, st.code) -> XlsxFormat.dateText(raw, date1904)
                                st != null -> XlsxFormat.formatNumber(raw, st.code ?: XlsxFormat.builtinCode(st.numFmtId))
                                else -> XlsxFormat.numberText(raw)
                            }
                        }
                    }
                }

                fun put(r: Int, c: Int, text: String, st: Int, kind: Int) {
                    if (r >= maxRows || c >= maxCols) {
                        if (text.isNotEmpty()) truncated = true
                        return
                    }
                    while (rows.size <= r) rows.add(ArrayList())
                    val row = rows[r]
                    while (row.size <= c) row.add("")
                    row[c] = text
                    val m = (st shl 2) or kind
                    if (m != 0) meta[XlsxSheet.key(r, c)] = m
                }
            },
        )
        // 最後の空の行は落とす(書式だけの行は、書式つきのセルがあれば残す)
        while (rows.isNotEmpty() && rows.last().all { it.isEmpty() } && (0 until rows.last().size).none { c -> (meta[XlsxSheet.key(rows.size - 1, c)] ?: 0) shr 2 != 0 }) {
            rows.removeAt(rows.size - 1)
        }
        return XlsxSheet(ref.name, rows, truncated, meta, widths, merges, frozen)
    }

    companion object {
        /** Office 2013 以降の標準のテーマの色(theme1.xml が無いとき用)。 */
        val DEFAULT_THEME = listOf(
            0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFE7E6E6.toInt(), 0xFF44546A.toInt(),
            0xFF4472C4.toInt(), 0xFFED7D31.toInt(), 0xFFA5A5A5.toInt(), 0xFFFFC000.toInt(), 0xFF5B9BD5.toInt(), 0xFF70AD47.toInt(),
            0xFF0563C1.toInt(), 0xFF954F72.toInt(),
        )
    }
}

internal object XlsxColors {
    /** 古い形式の「インデックス色」のうち、よく使う先頭の 16 色。 */
    private val indexedBasic = intArrayOf(
        0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFF00.toInt(), 0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(),
        0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFF00.toInt(), 0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(),
    )

    fun indexed(i: Int): Int? = when {
        i in indexedBasic.indices -> indexedBasic[i]
        i == 64 -> null // システムの文字色(自動)
        else -> null
    }

    /** Excel の「濃淡(tint)」。正で明るく、負で暗くする(明るさ L を調整する)。 */
    fun tint(rgb: Int, tint: Double): Int {
        if (tint == 0.0) return rgb
        val r = ((rgb shr 16) and 0xFF) / 255.0
        val g = ((rgb shr 8) and 0xFF) / 255.0
        val b = (rgb and 0xFF) / 255.0
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        var h = 0.0
        var s = 0.0
        var l = (max + min) / 2
        val d = max - min
        if (d != 0.0) {
            s = if (l > 0.5) d / (2 - max - min) else d / (max + min)
            h = when (max) {
                r -> ((g - b) / d + (if (g < b) 6 else 0))
                g -> ((b - r) / d + 2)
                else -> ((r - g) / d + 4)
            } / 6
        }
        l = if (tint < 0) l * (1 + tint) else l * (1 - tint) + tint
        l = l.coerceIn(0.0, 1.0)
        fun hue(p: Double, q: Double, tt: Double): Double {
            var t = tt
            if (t < 0) t += 1
            if (t > 1) t -= 1
            return when {
                t < 1.0 / 6 -> p + (q - p) * 6 * t
                t < 0.5 -> q
                t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
                else -> p
            }
        }
        val rr: Double
        val gg: Double
        val bb: Double
        if (s == 0.0) {
            rr = l; gg = l; bb = l
        } else {
            val q = if (l < 0.5) l * (1 + s) else l + s - l * s
            val p = 2 * l - q
            rr = hue(p, q, h + 1.0 / 3); gg = hue(p, q, h); bb = hue(p, q, h - 1.0 / 3)
        }
        return (0xFF shl 24) or (Math.round(rr * 255).toInt() shl 16) or (Math.round(gg * 255).toInt() shl 8) or Math.round(bb * 255).toInt()
    }
}
