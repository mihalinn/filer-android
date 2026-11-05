package local.filemanager

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/** RTF(.rtf)を、Word と同じ形(DocxDoc)にする。文字の飾り・段落・表・日本語(Shift_JIS など)に対応。画像は読まない。Android に依存しない。 */
object Rtf {
    fun looksLikeRtf(b: ByteArray): Boolean = b.size >= 5 && b[0] == '{'.code.toByte() && b[1] == '\\'.code.toByte() && b[2] == 'r'.code.toByte() && b[3] == 't'.code.toByte() && b[4] == 'f'.code.toByte()

    private class St(
        var bold: Boolean = false, var italic: Boolean = false, var ul: Boolean = false, var strike: Boolean = false,
        var font: Int = -1, var sizeHp: Int = 0, var color: Int = 0, var hl: Int = 0, var vert: Int = 0,
        var align: String = "left", var li: Int = 0, var fi: Int = 0, var sb: Int = 0, var sa: Int = 0, var sl: Int = 0, var slMult: Boolean = false,
        var dest: String? = null, var skip: Boolean = false, var uc: Int = 1, var link: String? = null,
    ) {
        fun copy() = St(bold, italic, ul, strike, font, sizeHp, color, hl, vert, align, li, fi, sb, sa, sl, slMult, dest, skip, uc, link)
    }

    private val charsets = mapOf(
        0 to "windows-1252", 128 to "windows-31j", 129 to "x-windows-949", 134 to "GBK", 136 to "Big5", 161 to "windows-1253",
        162 to "windows-1254", 177 to "windows-1255", 178 to "windows-1256", 186 to "windows-1257", 204 to "windows-1251", 238 to "windows-1250",
    )

    fun parse(bytes: ByteArray): DocxDoc {
        if (!looksLikeRtf(bytes)) throw OpException(Txt(R.string.rtf_001))
        val s = String(bytes, Charsets.ISO_8859_1)
        val fonts = HashMap<Int, Pair<String, Int>>() // 番号 → (名前, 文字セット)
        val colors = ArrayList<Int>()
        var ansiCp = "windows-1252"
        val stack = ArrayList<St>()
        var st = St()
        val pages = ArrayList<ArrayList<DocBlock>>().also { it.add(ArrayList()) }
        val runs = ArrayList<DocRun>()
        val text = StringBuilder()
        val pending = ByteArrayOutputStream()
        var pendingCharset = ansiCp

        // 表
        var inTable = false
        val tableRows = ArrayList<List<DocBlock.Cell>>()
        var rowCells = ArrayList<DocBlock.Cell>()
        var cellBlocks = ArrayList<DocBlock>()
        var cellX = ArrayList<Int>()
        var tableWidths = ArrayList<Int>()
        var curTable = false // いまの段落が表の中か

        // 文字表(fonttbl / colortbl)の解読用
        var fontEntry = -1
        var fontCharset = 0
        val fontName = StringBuilder()
        var red = -1; var green = -1; var blue = -1
        val fldInst = StringBuilder()

        fun charsetFor(font: Int): String = fonts[font]?.second?.let { charsets[it] } ?: ansiCp

        fun target(): MutableList<DocBlock> = if (curTable) cellBlocks else pages.last()

        fun flushPending() {
            if (pending.size() == 0) return
            val cs = runCatching { Charset.forName(pendingCharset) }.getOrDefault(Charsets.ISO_8859_1)
            text.append(String(pending.toByteArray(), cs))
            pending.reset()
        }

        fun rpr(): RPr = RPr(
            bold = if (st.bold) true else null, italic = if (st.italic) true else null, underline = if (st.ul) true else null, strike = if (st.strike) true else null,
            sizeHp = st.sizeHp.takeIf { it > 0 }, color = colors.getOrNull(st.color)?.takeIf { st.color > 0 },
            font = fonts[st.font]?.first, highlight = colors.getOrNull(st.hl)?.takeIf { st.hl > 0 }, vert = st.vert.takeIf { it != 0 },
        )

        fun flushText() {
            flushPending()
            if (text.isNotEmpty()) {
                runs.add(DocRun(text.toString(), rpr(), st.link))
                text.setLength(0)
            }
        }

        fun endCellOrTable() {
            if (inTable && !curTable && tableRows.isNotEmpty()) {
                val ws = tableWidths.ifEmpty { listOf(1) }
                pages.last().add(DocBlock.Table(ArrayList(tableRows), ws.map { it.coerceAtLeast(1) }, true))
                tableRows.clear()
                inTable = false
            }
        }

        fun endParagraph(force: Boolean) {
            flushText()
            if (runs.isEmpty() && !force) return
            val size = st.sizeHp
            if (!curTable) endCellOrTable()
            val rs = if (runs.isEmpty()) listOf(DocRun("", rpr())) else ArrayList(runs)
            target().add(
                DocBlock.Para(
                    runs = rs, heading = 0, align = st.align, leftPt = st.li / 20f,
                    firstLinePt = if (st.fi > 0) st.fi / 20f else 0f, hangingPt = if (st.fi < 0) -st.fi / 20f else 0f,
                    beforePt = st.sb / 20f, afterPt = st.sa / 20f,
                    lineMult = if (st.slMult && st.sl > 0) st.sl / 240f else 1f, marker = null, fill = null,
                ),
            )
            runs.clear()
            if (size < 0) return
        }

        fun cutDestinationsText(dest: String?) = dest == "fonttbl" || dest == "colortbl" || dest == "stylesheet"

        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            when (c) {
                '{' -> { flushText(); stack.add(st.copy()); i++ }
                '}' -> {
                    // 文字表の 1 件の終わり
                    if (st.dest == "fonttbl" && fontEntry >= 0 && fontName.isNotEmpty()) {
                        fonts[fontEntry] = fontName.toString().trim().trimEnd(';') to fontCharset
                    }
                    flushText()
                    if (stack.isNotEmpty()) st = stack.removeAt(stack.size - 1)
                    i++
                }
                '\\' -> {
                    i++
                    if (i >= n) break
                    val d = s[i]
                    when {
                        d == '\\' || d == '{' || d == '}' -> { if (!st.skip && !cutDestinationsText(st.dest)) { flushPending(); text.append(d) }; i++ }
                        d == '\'' -> {
                            val hex = if (i + 2 < n + 0) s.substring(i + 1, minOf(n, i + 3)) else ""
                            val v = hex.toIntOrNull(16)
                            if (v != null && !st.skip && !cutDestinationsText(st.dest)) {
                                val cs = charsetFor(st.font)
                                if (pending.size() > 0 && cs != pendingCharset) flushPending()
                                pendingCharset = cs
                                pending.write(v)
                            }
                            i += 3
                        }
                        d == '*' -> { st.skip = true; i++ }
                        d == '~' -> { if (!st.skip) { flushPending(); text.append(' ') }; i++ }
                        d == '_' -> { if (!st.skip) { flushPending(); text.append('‑') }; i++ }
                        d == '-' || d == '|' -> i++
                        d == '\n' || d == '\r' -> { if (!st.skip) endParagraph(true); i++ }
                        d.isLetter() -> {
                            var j = i
                            while (j < n && s[j].isLetter()) j++
                            val word = s.substring(i, j)
                            var k = j
                            var neg = false
                            if (k < n && s[k] == '-') { neg = true; k++ }
                            val ds = k
                            while (k < n && s[k].isDigit()) k++
                            val param: Int? = if (k > ds) s.substring(ds, k).toIntOrNull()?.let { if (neg) -it else it } else null
                            if (k < n && s[k] == ' ') k++ // 区切りの空白
                            i = k
                            if (word != "u" && word != "par" && word != "line" && word != "tab") flushPending()
                            when (word) {
                                "ansicpg" -> param?.let { ansiCp = if (it == 932) "windows-31j" else "windows-$it" }
                                "fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer", "headerl", "headerr", "headerf", "footerl", "footerr", "footerf",
                                "footnote", "listtable", "listoverridetable", "generator", "latentstyles", "rsidtbl", "themedata", "colorschememapping", "datastore", "xmlnstbl", "fldinst" -> {
                                    if (word == "fonttbl" || word == "colortbl" || word == "fldinst") st.dest = word else st.skip = true
                                    if (word == "colortbl") { colors.clear(); red = -1; green = -1; blue = -1 }
                                    if (word == "fldinst") fldInst.setLength(0)
                                }
                                "fldrslt" -> {
                                    val m = Regex("HYPERLINK\\s+\"([^\"]+)\"").find(fldInst.toString())
                                    if (m != null) st.link = m.groupValues[1]
                                }
                                "f" -> if (st.dest == "fonttbl") {
                                    if (fontEntry >= 0 && fontName.isNotEmpty()) fonts[fontEntry] = fontName.toString().trim().trimEnd(';') to fontCharset
                                    fontEntry = param ?: 0; fontName.setLength(0); fontCharset = 0
                                } else param?.let { st.font = it }
                                "fcharset" -> if (st.dest == "fonttbl") fontCharset = param ?: 0
                                "red" -> red = param ?: 0
                                "green" -> green = param ?: 0
                                "blue" -> blue = param ?: 0
                                "b" -> st.bold = param != 0
                                "i" -> st.italic = param != 0
                                "ul", "uld", "uldb", "ulw" -> st.ul = param != 0
                                "ulnone" -> st.ul = false
                                "strike", "striked" -> st.strike = param != 0
                                "fs" -> st.sizeHp = param ?: 0
                                "cf" -> st.color = param ?: 0
                                "highlight", "cb" -> if (word == "highlight") st.hl = param ?: 0
                                "super" -> st.vert = 1
                                "sub" -> st.vert = -1
                                "nosupersub" -> st.vert = 0
                                "plain" -> { st.bold = false; st.italic = false; st.ul = false; st.strike = false; st.sizeHp = 0; st.color = 0; st.hl = 0; st.vert = 0 }
                                "pard" -> { st.align = "left"; st.li = 0; st.fi = 0; st.sb = 0; st.sa = 0; st.sl = 0; st.slMult = false; curTable = false }
                                "ql" -> st.align = "left"
                                "qc" -> st.align = "center"
                                "qr" -> st.align = "right"
                                "qj" -> st.align = "both"
                                "li" -> st.li = param ?: 0
                                "fi" -> st.fi = param ?: 0
                                "sb" -> st.sb = param ?: 0
                                "sa" -> st.sa = param ?: 0
                                "sl" -> st.sl = param ?: 0
                                "slmult" -> st.slMult = param == 1
                                "uc" -> st.uc = param ?: 1
                                "u" -> if (!st.skip && !cutDestinationsText(st.dest)) {
                                    flushPending()
                                    val code = (param ?: 0).let { if (it < 0) it + 65536 else it }
                                    text.append(code.toChar())
                                    // 代わりの文字(\uc の数だけ)を読み飛ばす
                                    var skipN = st.uc
                                    while (skipN > 0 && i < n) {
                                        if (s[i] == '\\' && i + 1 < n && s[i + 1] == '\'') i += 4 else if (s[i] == '\\' || s[i] == '{' || s[i] == '}') break else i++
                                        skipN--
                                    }
                                }
                                "par" -> { if (!st.skip) endParagraph(true) }
                                "line" -> if (!st.skip) { flushPending(); text.append('\n') }
                                "tab" -> if (!st.skip) { flushPending(); text.append('\t') }
                                "emdash" -> if (!st.skip) text.append('—')
                                "endash" -> if (!st.skip) text.append('–')
                                "bullet" -> if (!st.skip) text.append('•')
                                "lquote" -> if (!st.skip) text.append('‘')
                                "rquote" -> if (!st.skip) text.append('’')
                                "ldblquote" -> if (!st.skip) text.append('“')
                                "rdblquote" -> if (!st.skip) text.append('”')
                                "page", "sect" -> if (!st.skip) { endParagraph(false); if (pages.last().isNotEmpty()) pages.add(ArrayList()) }
                                "trowd" -> { inTable = true; rowCells = ArrayList(); cellX = ArrayList(); cellBlocks = ArrayList() }
                                "intbl" -> curTable = true
                                "cellx" -> param?.let { cellX.add(it) }
                                "cell" -> {
                                    endParagraph(false)
                                    val width = cellX.getOrNull(rowCells.size)?.let { x -> x - (cellX.getOrNull(rowCells.size - 1) ?: 0) } ?: 1000
                                    rowCells.add(DocBlock.Cell(if (cellBlocks.isEmpty()) listOf(DocBlock.Para(listOf(DocRun("", RPr())), 0, "left", 0f, 0f, 0f, 0f, 0f, 1f, null, null)) else ArrayList(cellBlocks), 1, null))
                                    if (tableWidths.size < rowCells.size) tableWidths.add(width.coerceAtLeast(1)) else tableWidths[rowCells.size - 1] = width.coerceAtLeast(1)
                                    cellBlocks = ArrayList()
                                }
                                "row" -> {
                                    tableRows.add(ArrayList(rowCells))
                                    rowCells = ArrayList()
                                    curTable = false
                                }
                            }
                        }
                        else -> i++
                    }
                }
                '\r', '\n' -> i++ // 改行は無視(RTF の中の改行は、意味を持たない)
                else -> {
                    // 文字
                    if (st.dest == "fonttbl") {
                        fontName.append(c)
                    } else if (st.dest == "colortbl") {
                        if (c == ';') {
                            colors.add(if (red < 0) 0xFF000000.toInt() else ((0xFF shl 24) or (red.coerceIn(0, 255) shl 16) or (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)))
                            red = -1; green = -1; blue = -1
                        }
                    } else if (st.dest == "fldinst") {
                        fldInst.append(c)
                    } else if (!st.skip) {
                        // 1 バイトずつの 16 進の続き(2 バイト文字)と区別するため、直前の文字を先に確定
                        flushPending()
                        text.append(c)
                    }
                    i++
                }
            }
        }
        flushText()
        endParagraph(false)
        // 色の表は、番号が 1 から(0 は自動の色)なので、最初の空の色を除く
        endCellOrTable()
        if (inTable && tableRows.isNotEmpty()) {
            pages.last().add(DocBlock.Table(ArrayList(tableRows), tableWidths.ifEmpty { listOf(1) }, true))
        }
        val out = pages.filter { it.isNotEmpty() }
        return DocxDoc(if (out.isEmpty()) listOf(emptyList()) else out)
    }
}
