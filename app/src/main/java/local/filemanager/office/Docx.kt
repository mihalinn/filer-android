package local.filemanager

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/** 文字の飾り。null は「指定なし」(元のスタイルを引き継ぐ)。 */
data class RPr(
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strike: Boolean? = null,
    /** 文字の大きさ(ポイントの 2 倍。10.5pt なら 21) */
    val sizeHp: Int? = null,
    val color: Int? = null,
    val font: String? = null,
    val highlight: Int? = null,
    /** 1=上付き、-1=下付き、0=なし */
    val vert: Int? = null,
) {
    /** this の指定を優先し、無い所は base から取る。 */
    fun over(b: RPr) = RPr(
        bold ?: b.bold, italic ?: b.italic, underline ?: b.underline, strike ?: b.strike, sizeHp ?: b.sizeHp,
        color ?: b.color, font ?: b.font, highlight ?: b.highlight, vert ?: b.vert,
    )
}

/** 段落の書式。長さは twip(1/20 ポイント)。 */
data class PPr(
    val jc: String? = null,
    val left: Int? = null,
    val firstLine: Int? = null,
    val hanging: Int? = null,
    val before: Int? = null,
    val after: Int? = null,
    /** 行の間隔(240 で 1 行分)。「固定」「最小」の指定は読まない。 */
    val line: Int? = null,
    val numId: Int? = null,
    val ilvl: Int? = null,
    val fill: Int? = null,
    val pageBreakBefore: Boolean? = null,
) {
    fun over(b: PPr) = PPr(
        jc ?: b.jc, left ?: b.left, firstLine ?: b.firstLine, hanging ?: b.hanging, before ?: b.before, after ?: b.after,
        line ?: b.line, numId ?: b.numId, ilvl ?: b.ilvl, fill ?: b.fill, pageBreakBefore ?: b.pageBreakBefore,
    )
}

internal class RB {
    var bold: Boolean? = null
    var italic: Boolean? = null
    var underline: Boolean? = null
    var strike: Boolean? = null
    var sizeHp: Int? = null
    var color: Int? = null
    var font: String? = null
    var highlight: Int? = null
    var vert: Int? = null
    fun build() = RPr(bold, italic, underline, strike, sizeHp, color, font, highlight, vert)
}

internal class PB {
    var jc: String? = null
    var left: Int? = null
    var firstLine: Int? = null
    var hanging: Int? = null
    var before: Int? = null
    var after: Int? = null
    var line: Int? = null
    var numId: Int? = null
    var ilvl: Int? = null
    var fill: Int? = null
    var pageBreakBefore: Boolean? = null
    fun build() = PPr(jc, left, firstLine, hanging, before, after, line, numId, ilvl, fill, pageBreakBefore)
}

/** 実際に表示する 1 かたまりの文字。r は、スタイルの引き継ぎまで済んだ書式。 */
class DocRun(val text: String, val r: RPr, val link: String? = null)

sealed interface DocBlock {
    class Para(
        val runs: List<DocRun>,
        /** 見出しの段(1 〜 6)。見出しでなければ 0 */
        val heading: Int,
        /** left / center / right / both(両端そろえ) */
        val align: String,
        val leftPt: Float,
        val firstLinePt: Float,
        val hangingPt: Float,
        val beforePt: Float,
        val afterPt: Float,
        /** 行の間隔の倍率(1.0 が標準) */
        val lineMult: Float,
        /** 箇条書きの記号・番号(「•」「1.」など)。箇条書きでなければ null */
        val marker: String?,
        val fill: Int?,
    ) : DocBlock {
        val text: String get() = runs.joinToString("") { it.text }
    }

    class Cell(val blocks: List<DocBlock>, val span: Int, val fill: Int?)

    /** weights: 列の幅の比。borders: 罫線を引くか。 */
    class Table(val rows: List<List<Cell>>, val weights: List<Int>, val borders: Boolean) : DocBlock

    class Image(val path: String, val widthPt: Float, val heightPt: Float) : DocBlock
}

/** 文書。pages は、ページ区切りで分けた段落・表・画像の並び。 */
class DocxDoc(val pages: List<List<DocBlock>>) {
    val blocks: List<DocBlock> get() = pages.flatten()
}

// ---------------------------------------------------------------- 書式の読み取り(styles.xml / document.xml 共通)

internal object DocxProps {
    private val highlights = mapOf(
        "yellow" to 0xFFFFFF00.toInt(), "green" to 0xFF00FF00.toInt(), "cyan" to 0xFF00FFFF.toInt(), "magenta" to 0xFFFF00FF.toInt(),
        "blue" to 0xFF0000FF.toInt(), "red" to 0xFFFF0000.toInt(), "darkBlue" to 0xFF000080.toInt(), "darkCyan" to 0xFF008080.toInt(),
        "darkGreen" to 0xFF008000.toInt(), "darkMagenta" to 0xFF800080.toInt(), "darkRed" to 0xFF800000.toInt(),
        "darkYellow" to 0xFF808000.toInt(), "darkGray" to 0xFF808080.toInt(), "lightGray" to 0xFFC0C0C0.toInt(),
        "black" to 0xFF000000.toInt(), "white" to 0xFFFFFFFF.toInt(),
    )

    fun hex(v: String?): Int? {
        if (v == null || v == "auto" || v.length != 6) return null
        return v.toLongOrNull(16)?.let { (it or 0xFF000000).toInt() }
    }

    private fun on(a: Attributes): Boolean = a.getValue("w:val").let { it == null || (it != "0" && it != "false" && it != "off") }

    /** 文字の飾りの要素 1 つを、b に反映する。 */
    fun applyR(q: String, a: Attributes, b: RB) {
        when (q) {
            "w:b" -> b.bold = on(a)
            "w:i" -> b.italic = on(a)
            "w:u" -> b.underline = a.getValue("w:val").let { it != null && it != "none" }
            "w:strike", "w:dstrike" -> b.strike = on(a)
            "w:sz" -> a.getValue("w:val")?.toIntOrNull()?.let { b.sizeHp = it }
            "w:color" -> b.color = hex(a.getValue("w:val"))
            "w:rFonts" -> b.font = a.getValue("w:eastAsia") ?: a.getValue("w:ascii") ?: a.getValue("w:hAnsi") ?: b.font
            "w:highlight" -> b.highlight = highlights[a.getValue("w:val")]
            "w:vertAlign" -> b.vert = when (a.getValue("w:val")) { "superscript" -> 1; "subscript" -> -1; else -> 0 }
        }
    }

    /** 段落の書式の要素 1 つを、b に反映する。 */
    fun applyP(q: String, a: Attributes, b: PB) {
        when (q) {
            "w:jc" -> b.jc = a.getValue("w:val")
            "w:ind" -> {
                (a.getValue("w:left") ?: a.getValue("w:start"))?.toIntOrNull()?.let { b.left = it }
                a.getValue("w:firstLine")?.toIntOrNull()?.let { b.firstLine = it }
                a.getValue("w:hanging")?.toIntOrNull()?.let { b.hanging = it }
            }
            "w:spacing" -> {
                a.getValue("w:before")?.toIntOrNull()?.let { b.before = it }
                a.getValue("w:after")?.toIntOrNull()?.let { b.after = it }
                val rule = a.getValue("w:lineRule")
                if (rule == null || rule == "auto") a.getValue("w:line")?.toIntOrNull()?.let { b.line = it }
            }
            "w:ilvl" -> b.ilvl = a.getValue("w:val")?.toIntOrNull()
            "w:numId" -> b.numId = a.getValue("w:val")?.toIntOrNull()
            "w:shd" -> hex(a.getValue("w:fill"))?.let { b.fill = it }
            "w:pageBreakBefore" -> b.pageBreakBefore = on(a)
        }
    }
}

// ---------------------------------------------------------------- スタイル(styles.xml)

class DocStyles(
    private val defaultR: RPr,
    private val defaultP: PPr,
    private val styles: Map<String, Def>,
) {
    class Def(
        val id: String, val name: String, val type: String, val basedOn: String?,
        val p: PPr, val r: RPr, val tableBorders: Boolean,
    )

    private fun chain(id: String?): List<Def> {
        val out = ArrayList<Def>()
        var cur = id?.let { styles[it] }
        while (cur != null && out.size < 12) {
            out.add(0, cur) // 元になっているスタイルを先頭に
            cur = cur.basedOn?.let { styles[it] }
        }
        return out
    }

    /** 段落のスタイルの書式(文書の既定 → 元のスタイル → このスタイルの順に重ねたもの)。 */
    fun paragraph(id: String?): Pair<PPr, RPr> {
        var p = defaultP
        var r = defaultR
        for (d in chain(id ?: defaultParagraphId())) {
            p = d.p.over(p)
            r = d.r.over(r)
        }
        return p to r
    }

    /** 文字のスタイルの書式(指定した所だけ)。 */
    fun character(id: String?): RPr {
        var r = RPr()
        for (d in chain(id)) r = d.r.over(r)
        return r
    }

    private fun defaultParagraphId(): String? = styles.values.firstOrNull { it.type == "paragraph" && it.id == "Normal" }?.id

    /** 見出しの段(1 〜 6)。見出しでなければ 0。名前(heading 1 / 見出し 1 / Title)で決める。 */
    fun headingLevel(id: String?): Int {
        for (d in chain(id).asReversed()) {
            val n = d.name.lowercase()
            if (n == "title") return 1
            if (n == "subtitle") return 2
            if (n.startsWith("heading") || n.startsWith("見出し")) return n.filter { it.isDigit() }.toIntOrNull()?.coerceIn(1, 6) ?: 1
        }
        return 0
    }

    fun tableHasBorders(id: String?): Boolean = chain(id).any { it.tableBorders } || (id?.contains("Grid", ignoreCase = true) == true)

    companion object {
        val EMPTY = DocStyles(RPr(sizeHp = 21), PPr(), emptyMap())

        fun parse(bytes: ByteArray?): DocStyles {
            if (bytes == null) return EMPTY
            var defR = RPr(sizeHp = 21)
            var defP = PPr()
            val defs = LinkedHashMap<String, Def>()
            OfficeXml.parse(
                bytes,
                object : DefaultHandler() {
                    var inDefaults = false
                    var inRPrDefault = false
                    var inPPrDefault = false
                    var styleId: String? = null
                    var type = ""
                    var name = ""
                    var basedOn: String? = null
                    var borders = false
                    var inTblBorders = false
                    var inPPr = false
                    var inRPr = false
                    var pb = PB()
                    var rb = RB()

                    override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                        when (qName) {
                            "w:docDefaults" -> { inDefaults = true; pb = PB(); rb = RB() }
                            "w:rPrDefault" -> inRPrDefault = true
                            "w:pPrDefault" -> inPPrDefault = true
                            "w:style" -> {
                                styleId = a.getValue("w:styleId"); type = a.getValue("w:type") ?: ""
                                name = ""; basedOn = null; borders = false; pb = PB(); rb = RB()
                            }
                            "w:name" -> if (styleId != null) name = a.getValue("w:val") ?: ""
                            "w:basedOn" -> basedOn = a.getValue("w:val")
                            "w:pPr" -> inPPr = true
                            "w:rPr" -> inRPr = true
                            "w:tblBorders" -> inTblBorders = true
                            else -> {
                                if (inTblBorders) {
                                    val v = a.getValue("w:val")
                                    if (v != null && v != "nil" && v != "none") borders = true
                                } else if (inRPr) {
                                    DocxProps.applyR(qName, a, rb)
                                } else if (inPPr) {
                                    DocxProps.applyP(qName, a, pb)
                                }
                            }
                        }
                    }

                    override fun endElement(uri: String?, localName: String?, qName: String) {
                        when (qName) {
                            "w:pPr" -> inPPr = false
                            "w:rPr" -> inRPr = false
                            "w:tblBorders" -> inTblBorders = false
                            "w:rPrDefault" -> { inRPrDefault = false; defR = rb.build().over(defR) }
                            "w:pPrDefault" -> { inPPrDefault = false; defP = pb.build().over(defP) }
                            "w:docDefaults" -> inDefaults = false
                            "w:style" -> {
                                styleId?.let { id -> defs[id] = Def(id, name, type, basedOn, pb.build(), rb.build(), borders) }
                                styleId = null
                            }
                        }
                    }
                },
            )
            return DocStyles(defR, defP, defs)
        }
    }
}

// ---------------------------------------------------------------- 番号・箇条書き(numbering.xml)

class DocNumbering private constructor(
    private val levels: Map<Int, Map<Int, Level>>, // abstractNumId → ilvl → 設定
    private val nums: Map<Int, Int>, // numId → abstractNumId
    private val overrides: Map<Int, Map<Int, Int>>, // numId → ilvl → 数え直しの開始値(lvlOverride/startOverride)
) {
    class Level(val start: Int, val fmt: String, val text: String, val left: Int?, val hanging: Int?)

    /** 番号ごとの数え方の途中経過。段落を上から順に読みながら使う。 */
    inner class Counter {
        private val counts = HashMap<Int, IntArray>() // abstractNumId → 各段の数
        private val seen = HashSet<Int>() // 使い始めた numId(「数え直し」は、最初に使うときに効く)

        /** 次の記号・番号の文字。 */
        fun next(numId: Int, ilvl: Int): String? {
            val abs = nums[numId] ?: return null
            val lv = levels[abs] ?: return null
            val level = lv[ilvl] ?: return null
            val c = counts.getOrPut(abs) { IntArray(9) { -1 } }
            if (seen.add(numId)) {
                // 同じ番号の定義を使う別の番号は、数えが続く。「数え直し」の指定があれば、その開始値から
                overrides[numId]?.forEach { (lv, start) -> if (lv in 0..8) c[lv] = start - 1 }
            }
            val i = ilvl.coerceIn(0, 8)
            c[i] = if (c[i] < 0) level.start else c[i] + 1
            for (k in i + 1 until 9) c[k] = -1 // 深い段は、数え直し
            if (level.fmt == "bullet") return bulletChar(level.text)
            var t = level.text
            for (k in 0..8) {
                val tag = "%${k + 1}"
                if (t.contains(tag)) {
                    val lvK = lv[k]
                    val n = if (c[k] < 0) (lvK?.start ?: 1) else c[k]
                    t = t.replace(tag, format(n, lvK?.fmt ?: "decimal"))
                }
            }
            return t
        }
    }

    fun counter() = Counter()

    /** その番号の段の字下げ(left, hanging。twip)。 */
    fun indent(numId: Int, ilvl: Int): Pair<Int?, Int?> {
        val l = levels[nums[numId]]?.get(ilvl) ?: return null to null
        return l.left to l.hanging
    }

    companion object {
        val EMPTY = DocNumbering(emptyMap(), emptyMap(), emptyMap())

        fun bulletChar(raw: String): String {
            if (raw.isEmpty()) return "•"
            val c = raw[0]
            return when {
                c.code in 0xF000..0xF0FF -> when (c.code) {
                    0xF0A7 -> "▪"
                    0xF0D8 -> "▸"
                    0xF0FC -> "✓"
                    else -> "•"
                }
                raw == "o" -> "◦"
                raw == "-" -> "–"
                else -> raw
            }
        }

        private val kanji = listOf("〇", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十")

        fun format(n: Int, fmt: String): String = when (fmt) {
            "decimalZero" -> "%02d".format(n)
            "upperRoman" -> roman(n)
            "lowerRoman" -> roman(n).lowercase()
            "upperLetter" -> letter(n).uppercase()
            "lowerLetter" -> letter(n)
            "japaneseCounting", "ideographDigital", "chineseCounting", "japaneseDigitalTenThousand" -> if (n in 0..10) kanji[n] else n.toString()
            "decimalEnclosedCircle", "decimalEnclosedCircleChinese" -> if (n in 1..20) (0x2460 + n - 1).toChar().toString() else "($n)"
            else -> n.toString()
        }

        private fun letter(n: Int): String {
            var x = n
            val sb = StringBuilder()
            while (x > 0) {
                x--
                sb.append('a' + x % 26)
                x /= 26
            }
            return sb.reverse().toString()
        }

        private fun roman(n: Int): String {
            val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
            val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
            var x = n.coerceIn(1, 3999)
            val sb = StringBuilder()
            for (i in v.indices) while (x >= v[i]) { sb.append(s[i]); x -= v[i] }
            return sb.toString()
        }

        fun parse(bytes: ByteArray?): DocNumbering {
            if (bytes == null) return EMPTY
            val levels = HashMap<Int, HashMap<Int, Level>>()
            val nums = HashMap<Int, Int>()
            val overrides = HashMap<Int, HashMap<Int, Int>>()
            OfficeXml.parse(
                bytes,
                object : DefaultHandler() {
                    var absId = -1
                    var numId = -1
                    var ilvl = -1
                    var start = 1
                    var fmt = "decimal"
                    var text = ""
                    var left: Int? = null
                    var hanging: Int? = null
                    var inLvl = false
                    var overrideLvl = 0

                    override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                        when (qName) {
                            "w:abstractNum" -> absId = a.getValue("w:abstractNumId")?.toIntOrNull() ?: -1
                            "w:lvl" -> {
                                inLvl = true
                                ilvl = a.getValue("w:ilvl")?.toIntOrNull() ?: 0
                                start = 1; fmt = "decimal"; text = ""; left = null; hanging = null
                            }
                            "w:start" -> if (inLvl) start = a.getValue("w:val")?.toIntOrNull() ?: 1
                            "w:numFmt" -> if (inLvl) fmt = a.getValue("w:val") ?: "decimal"
                            "w:lvlText" -> if (inLvl) text = a.getValue("w:val") ?: ""
                            "w:ind" -> if (inLvl) {
                                left = (a.getValue("w:left") ?: a.getValue("w:start"))?.toIntOrNull()
                                hanging = a.getValue("w:hanging")?.toIntOrNull()
                            }
                            "w:num" -> numId = a.getValue("w:numId")?.toIntOrNull() ?: -1
                            "w:lvlOverride" -> overrideLvl = a.getValue("w:ilvl")?.toIntOrNull() ?: 0
                            "w:startOverride" -> if (numId >= 0) overrides.getOrPut(numId) { HashMap() }[overrideLvl] = a.getValue("w:val")?.toIntOrNull() ?: 1
                            "w:abstractNumId" -> if (numId >= 0) nums[numId] = a.getValue("w:val")?.toIntOrNull() ?: -1
                        }
                    }

                    override fun endElement(uri: String?, localName: String?, qName: String) {
                        when (qName) {
                            "w:lvl" -> {
                                inLvl = false
                                if (absId >= 0) levels.getOrPut(absId) { HashMap() }[ilvl] = Level(start, fmt, text, left, hanging)
                            }
                            "w:abstractNum" -> absId = -1
                            "w:num" -> numId = -1
                        }
                    }
                },
            )
            return DocNumbering(levels, nums, overrides)
        }
    }
}

// ---------------------------------------------------------------- 文書本体(word/document.xml)

object Docx {
    fun parse(read: (String) -> ByteArray?): DocxDoc {
        val bytes = read("word/document.xml") ?: throw OpException("Wordのファイルとして読めません")
        val styles = DocStyles.parse(read("word/styles.xml"))
        val numbering = DocNumbering.parse(read("word/numbering.xml"))
        val rels = OfficeXml.relationships(read("word/_rels/document.xml.rels"), "word")
        val handler = DocHandler(styles, numbering, rels)
        OfficeXml.parse(bytes, handler)
        return DocxDoc(handler.finish())
    }

    /** 見出しの書式名(「Heading1」「heading 2」「見出し 3」「Title」)→ 段の番号。見出しでなければ 0。 */
    internal fun headingLevel(style: String?): Int {
        if (style == null) return 0
        val s = style.lowercase()
        if (s == "title") return 1
        if (s == "subtitle") return 2
        if (s.startsWith("heading") || s.startsWith("見出し")) return s.filter { it.isDigit() }.toIntOrNull()?.coerceIn(1, 6) ?: 1
        return 0
    }

    private class TableB(val weights: ArrayList<Int>, val borders: Boolean) {
        val rows = ArrayList<List<DocBlock.Cell>>()
        var cells = ArrayList<DocBlock.Cell>()
    }

    private class DocHandler(
        val styles: DocStyles,
        val numbering: DocNumbering,
        val rels: Map<String, String>,
    ) : DefaultHandler() {
        val pages = ArrayList<ArrayList<DocBlock>>().apply { add(ArrayList()) }
        val counter = numbering.counter()

        var skip = 0 // mc:Fallback の中(同じ内容の二重)は読まない

        // 段落
        var inP = false
        var inPPr = false
        var pStyle: String? = null
        var pb = PB()
        var runs = ArrayList<DocRun>()
        var pageBreakAfter = false
        val images = ArrayList<DocBlock.Image>()

        // 文字の連なり
        var inRun = false
        var inRPr = false
        var rb = RB()
        var rStyle: String? = null
        var inT = false
        val t = StringBuilder()
        var link: String? = null

        // 図
        var inDrawing = false
        var extW = 0f
        var extH = 0f
        var blipId: String? = null

        // 表
        var table: TableB? = null
        var tableDepth = 0
        var inTblPr = false
        var inTcPr = false
        var inTblBorders = false
        var tblBorders = false
        var tblStyle: String? = null
        var cellBlocks = ArrayList<DocBlock>()
        var cellSpan = 1
        var cellFill: Int? = null

        private fun target(): ArrayList<DocBlock> = if (tableDepth > 0) cellBlocks else pages.last()

        fun finish(): List<List<DocBlock>> {
            // 終わりの空の段落は落とす。空のページは落とす
            for (p in pages) while (p.isNotEmpty() && (p.last() as? DocBlock.Para)?.let { it.text.isEmpty() } == true) p.removeAt(p.size - 1)
            val out = pages.filter { it.isNotEmpty() }
            return if (out.isEmpty()) listOf(emptyList()) else out
        }

        private fun flushRun() {
            if (t.isEmpty()) return
            val (_, paraR) = styles.paragraph(pStyle)
            val eff = rb.build().over(styles.character(rStyle).over(paraR))
            val text = t.toString()
            t.setLength(0)
            val last = runs.lastOrNull()
            if (last != null && last.r == eff && last.link == link) runs[runs.size - 1] = DocRun(last.text + text, eff, link) else runs.add(DocRun(text, eff, link))
        }

        override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
            if (qName == "mc:Fallback") skip++
            if (skip > 0) return
            when (qName) {
                "w:tbl" -> {
                    if (tableDepth == 0) {
                        tblBorders = false
                        tblStyle = null
                        table = TableB(ArrayList(), false)
                    }
                    tableDepth++
                }
                "w:tblPr" -> if (tableDepth == 1) inTblPr = true
                "w:tblStyle" -> if (inTblPr) tblStyle = a.getValue("w:val")
                "w:tblBorders" -> if (inTblPr) inTblBorders = true
                "w:gridCol" -> if (tableDepth == 1) a.getValue("w:w")?.toIntOrNull()?.let { table?.weights?.add(it) }
                "w:tr" -> if (tableDepth == 1) table?.cells = ArrayList()
                "w:tc" -> if (tableDepth == 1) { cellBlocks = ArrayList(); cellSpan = 1; cellFill = null }
                "w:tcPr" -> if (tableDepth == 1) inTcPr = true
                "w:gridSpan" -> if (inTcPr) cellSpan = a.getValue("w:val")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                "w:shd" -> {
                    if (inTcPr && !inPPr) cellFill = DocxProps.hex(a.getValue("w:fill"))
                    else if (inPPr && !inRPr) DocxProps.applyP(qName, a, pb)
                }
                "w:p" -> {
                    inP = true; runs = ArrayList(); pb = PB(); pStyle = null; pageBreakAfter = false; link = null
                }
                "w:pPr" -> inPPr = true
                "w:pStyle" -> if (inPPr && !inRPr) pStyle = a.getValue("w:val")
                "w:hyperlink" -> { flushRun(); link = a.getValue("r:id")?.let { rels[it] } }
                "w:r" -> { flushRun(); rb = RB(); rStyle = null; inRun = true }
                "w:rPr" -> inRPr = true
                "w:rStyle" -> if (inRPr && !inPPr) rStyle = a.getValue("w:val")
                "w:t" -> inT = true
                // 段落の設定の中の「w:tab」(タブ位置の定義)は文字ではない。文字の連なり(w:r)の中だけ
                "w:tab" -> if (inRun && !inRPr) t.append('\t')
                "w:br", "w:cr" -> if (inRun) {
                    if (a.getValue("w:type") == "page") pageBreakAfter = true else t.append('\n')
                }
                "w:drawing" -> { inDrawing = true; extW = 0f; extH = 0f; blipId = null }
                "wp:extent" -> if (inDrawing) {
                    extW = (a.getValue("cx")?.toFloatOrNull() ?: 0f) / 12700f
                    extH = (a.getValue("cy")?.toFloatOrNull() ?: 0f) / 12700f
                }
                "a:blip" -> if (inDrawing) blipId = a.getValue("r:embed")
                else -> {
                    if (inTblBorders) {
                        val v = a.getValue("w:val")
                        if (v != null && v != "nil" && v != "none") tblBorders = true
                    } else if (inRPr && !inPPr) {
                        DocxProps.applyR(qName, a, rb)
                    } else if (inPPr && !inRPr) {
                        DocxProps.applyP(qName, a, pb)
                    }
                }
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            if (skip > 0) {
                if (qName == "mc:Fallback") skip--
                return
            }
            when (qName) {
                "w:t" -> inT = false
                "w:rPr" -> inRPr = false
                "w:pPr" -> inPPr = false
                "w:tblPr" -> inTblPr = false
                "w:tblBorders" -> inTblBorders = false
                "w:tcPr" -> inTcPr = false
                "w:hyperlink" -> { flushRun(); link = null }
                "w:r" -> { flushRun(); inRun = false }
                "w:drawing" -> {
                    inDrawing = false
                    val path = blipId?.let { rels[it] }
                    if (path != null && FileOps.extensionOf(path) in setOf("png", "jpg", "jpeg", "gif", "bmp", "webp") && extW > 0f && extH > 0f) {
                        images.add(DocBlock.Image(path, extW, extH))
                    }
                }
                "w:p" -> endParagraph()
                "w:tc" -> if (tableDepth == 1) {
                    table?.cells?.add(DocBlock.Cell(cellBlocks, cellSpan, cellFill))
                }
                "w:tr" -> if (tableDepth == 1) table?.let { it.rows.add(it.cells) }
                "w:tbl" -> {
                    tableDepth--
                    if (tableDepth == 0) {
                        val tb = table
                        table = null
                        if (tb != null && tb.rows.isNotEmpty()) {
                            val borders = tblBorders || styles.tableHasBorders(tblStyle)
                            val maxCols = tb.rows.maxOf { r -> r.sumOf { it.span } }
                            val weights = if (tb.weights.size >= maxCols) tb.weights.take(maxCols) else List(maxCols) { tb.weights.getOrElse(it) { 1000 } }
                            pages.last().add(DocBlock.Table(tb.rows, weights, borders))
                        }
                    }
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (skip == 0 && inT) t.append(ch, start, length)
        }

        private fun endParagraph() {
            flushRun()
            inP = false
            val (styleP, _) = styles.paragraph(pStyle)
            var p = pb.build().over(styleP)
            // 箇条書き・番号
            var marker: String? = null
            val numId = p.numId
            if (numId != null && numId != 0) {
                val lvl = p.ilvl ?: 0
                marker = counter.next(numId, lvl)
                val (nl, nh) = numbering.indent(numId, lvl)
                // 字下げの優先順位: 段落自身の指定 > 番号の段の字下げ > 段落のスタイル(Word と同じ)
                p = p.copy(left = pb.left ?: nl ?: styleP.left, hanging = pb.hanging ?: nh ?: styleP.hanging)
            }
            if (p.pageBreakBefore == true && tableDepth == 0 && pages.last().isNotEmpty()) pages.add(ArrayList())
            val heading = styles.headingLevel(pStyle)
            val hanging = (p.hanging ?: 0) / 20f
            val para = DocBlock.Para(
                runs = ArrayList(runs),
                heading = heading,
                align = when (p.jc) { "center" -> "center"; "right", "end" -> "right"; "both", "distribute" -> "both"; else -> "left" },
                leftPt = (p.left ?: 0) / 20f,
                firstLinePt = (p.firstLine ?: 0) / 20f,
                hangingPt = hanging,
                beforePt = (p.before ?: 0) / 20f,
                afterPt = (p.after ?: 0) / 20f,
                lineMult = ((p.line ?: 240) / 240f).coerceIn(0.8f, 3f),
                marker = marker,
                fill = p.fill,
            )
            target().add(para)
            for (im in images) target().add(im)
            images.clear()
            if (pageBreakAfter && tableDepth == 0) pages.add(ArrayList())
            pageBreakAfter = false
        }
    }
}
