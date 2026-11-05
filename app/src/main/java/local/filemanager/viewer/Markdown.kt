package local.filemanager

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock

    /** 箇条書き 1 項目。level は入れ子の深さ(0 から)。marker は「•」「◦」か「1.」。checked はタスクリストの印(なければ null)。 */
    data class Item(val level: Int, val marker: String, val text: String, val checked: Boolean?) : MdBlock
    data class Code(val lang: String, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

/** 文章の中の 1 かたまり(同じ飾りで続く文字)。 */
data class Run(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val link: String? = null,
)

/** Markdown の簡易解析(見出し・箇条書き・コード・引用・表・区切り線、太字・斜体・コード・リンク)。Android に依存しない。 */
object Markdown {
    private val heading = Regex("""^ {0,3}(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val rule = Regex("""^ {0,3}([-*_])(\s*\1){2,}\s*$""")
    private val item = Regex("""^(\s*)([-*+]|\d{1,9}[.)])\s+(.*)$""")
    private val tableSep = Regex("""^\s*\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)*\|?\s*$""")
    private val setext1 = Regex("""^ {0,3}=+\s*$""")
    private val setext2 = Regex("""^ {0,3}-+\s*$""")
    private val fence = Regex("""^\s*(```+|~~~+)\s*(\S*).*$""")
    private val task = Regex("""^\[( |x|X)]\s+(.*)$""", RegexOption.DOT_MATCHES_ALL)

    fun parse(text: String): List<MdBlock> {
        val lines = text.removePrefix("﻿").split('\n').map { it.trimEnd('\r') }
        val out = ArrayList<MdBlock>()
        val para = ArrayList<String>()
        var i = 0

        fun flush() {
            if (para.isNotEmpty()) {
                out.add(MdBlock.Paragraph(para.joinToString("\n")))
                para.clear()
            }
        }

        // 先頭の --- で囲まれた部分(front matter)は、コードとして出す
        if (lines.isNotEmpty() && lines[0].trim() == "---") {
            val close = (1 until minOf(lines.size, 60)).firstOrNull { lines[it].trim() == "---" }
            if (close != null && close > 1) {
                out.add(MdBlock.Code("yaml", lines.subList(1, close).joinToString("\n")))
                i = close + 1
            }
        }

        while (i < lines.size) {
            val line = lines[i]
            val fm = fence.matchEntire(line)
            if (fm != null) {
                flush()
                val mark = fm.groupValues[1]
                val body = ArrayList<String>()
                i++
                while (i < lines.size && !(lines[i].trim().startsWith(mark.substring(0, 3)) && lines[i].trim().all { it == mark[0] })) {
                    body.add(lines[i])
                    i++
                }
                i++ // 閉じの行
                out.add(MdBlock.Code(fm.groupValues[2], body.joinToString("\n")))
                continue
            }
            if (line.isBlank()) {
                flush()
                i++
                continue
            }
            if (para.isNotEmpty() && setext1.matches(line)) {
                out.add(MdBlock.Heading(1, para.joinToString(" ")))
                para.clear()
                i++
                continue
            }
            if (para.isNotEmpty() && setext2.matches(line)) {
                out.add(MdBlock.Heading(2, para.joinToString(" ")))
                para.clear()
                i++
                continue
            }
            val hm = heading.matchEntire(line)
            if (hm != null) {
                flush()
                out.add(MdBlock.Heading(hm.groupValues[1].length, hm.groupValues[2]))
                i++
                continue
            }
            if (rule.matches(line)) {
                flush()
                out.add(MdBlock.Rule)
                i++
                continue
            }
            if (line.contains('|') && i + 1 < lines.size && tableSep.matches(lines[i + 1]) && lines[i + 1].contains('-')) {
                flush()
                val header = cells(line)
                val rows = ArrayList<List<String>>()
                i += 2
                while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
                    rows.add(cells(lines[i]))
                    i++
                }
                out.add(MdBlock.Table(header, rows))
                continue
            }
            if (line.trimStart().startsWith(">")) {
                flush()
                val q = ArrayList<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    q.add(lines[i].trimStart().removePrefix(">").removePrefix(" "))
                    i++
                }
                out.add(MdBlock.Quote(q.joinToString("\n")))
                continue
            }
            val im = item.matchEntire(line)
            if (im != null) {
                flush()
                val spaces = im.groupValues[1].replace("\t", "    ").length
                val level = (spaces / 2).coerceAtMost(6)
                val rawMarker = im.groupValues[2]
                val marker = if (rawMarker[0].isDigit()) rawMarker else if (level % 2 == 0) "•" else "◦"
                var body = im.groupValues[3]
                var checked: Boolean? = null
                task.matchEntire(body)?.let {
                    checked = it.groupValues[1] != " "
                    body = it.groupValues[2]
                }
                i++
                // 次の行が、字下げされた続き(新しい項目でない)なら、同じ項目の続きにする
                val sb = StringBuilder(body)
                while (i < lines.size && lines[i].isNotBlank() && lines[i].startsWith(" ") && item.matchEntire(lines[i]) == null) {
                    sb.append('\n').append(lines[i].trim())
                    i++
                }
                out.add(MdBlock.Item(level, marker, sb.toString(), checked))
                continue
            }
            para.add(line.trim())
            i++
        }
        flush()
        return out
    }

    private fun cells(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length && s[i + 1] == '|') {
                cur.append('|')
                i += 2
                continue
            }
            if (c == '|') {
                out.add(cur.toString().trim())
                cur.setLength(0)
            } else {
                cur.append(c)
            }
            i++
        }
        out.add(cur.toString().trim())
        return out
    }

    /** 飾りを取り除いた文字だけ(表のセルなど)。 */
    fun plain(s: String): String = inline(s).joinToString("") { it.text }

    fun inline(s: String): List<Run> {
        val out = ArrayList<Run>()
        parseInline(s, Run(""), out, 0)
        return out
    }

    private fun parseInline(s: String, base: Run, out: ArrayList<Run>, depth: Int) {
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) {
                out.add(base.copy(text = sb.toString()))
                sb.setLength(0)
            }
        }
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            // 記号の打ち消し
            if (c == '\\' && i + 1 < n && s[i + 1] in "\\`*_{}[]()#+-.!|~<>") {
                sb.append(s[i + 1])
                i += 2
                continue
            }
            if (c == '`') {
                var k = 1
                while (i + k < n && s[i + k] == '`') k++
                val mark = "`".repeat(k)
                val e = s.indexOf(mark, i + k)
                if (e > 0) {
                    flush()
                    out.add(base.copy(text = s.substring(i + k, e).trim(' '), code = true))
                    i = e + k
                    continue
                }
                sb.append(mark)
                i += k
                continue
            }
            if (depth < 8) {
                if (c == '!' && i + 1 < n && s[i + 1] == '[') {
                    val m = linkAt(s, i + 1)
                    if (m != null) {
                        flush()
                        out.add(base.copy(text = Txt(R.string.markdown_001, m.text.ifBlank { m.url }), italic = true))
                        i = m.end
                        continue
                    }
                }
                if (c == '[') {
                    val m = linkAt(s, i)
                    if (m != null) {
                        flush()
                        parseInline(m.text, base.copy(link = m.url), out, depth + 1)
                        i = m.end
                        continue
                    }
                }
                if (s.startsWith("***", i) || s.startsWith("___", i)) {
                    val e = s.indexOf(s.substring(i, i + 3), i + 3)
                    if (e > i + 3) {
                        flush()
                        parseInline(s.substring(i + 3, e), base.copy(bold = true, italic = true), out, depth + 1)
                        i = e + 3
                        continue
                    }
                }
                if (s.startsWith("**", i) || s.startsWith("__", i)) {
                    val mark = s.substring(i, i + 2)
                    val e = s.indexOf(mark, i + 2)
                    if (e > i + 2 && (mark == "**" || boundaryBefore(s, i))) {
                        flush()
                        parseInline(s.substring(i + 2, e), base.copy(bold = true), out, depth + 1)
                        i = e + 2
                        continue
                    }
                }
                if (s.startsWith("~~", i)) {
                    val e = s.indexOf("~~", i + 2)
                    if (e > i + 2) {
                        flush()
                        parseInline(s.substring(i + 2, e), base.copy(strike = true), out, depth + 1)
                        i = e + 2
                        continue
                    }
                }
                if ((c == '*' || c == '_') && i + 1 < n && !s[i + 1].isWhitespace() && s[i + 1] != c) {
                    if (c == '*' || boundaryBefore(s, i)) {
                        val e = closeSingle(s, i + 1, c)
                        if (e > i + 1) {
                            flush()
                            parseInline(s.substring(i + 1, e), base.copy(italic = true), out, depth + 1)
                            i = e + 1
                            continue
                        }
                    }
                }
                if (c == '<') {
                    val e = s.indexOf('>', i + 1)
                    if (e > i + 1) {
                        val inner = s.substring(i + 1, e)
                        if (inner.startsWith("http://") || inner.startsWith("https://") || inner.startsWith("mailto:")) {
                            flush()
                            out.add(base.copy(text = inner, link = inner))
                            i = e + 1
                            continue
                        }
                    }
                }
                if (c == 'h' && base.link == null && (s.startsWith("http://", i) || s.startsWith("https://", i)) && (i == 0 || !s[i - 1].isLetterOrDigit())) {
                    var e = i
                    while (e < n && !s[e].isWhitespace() && s[e] != '<' && s[e] != '>' && s[e] != ')' && s[e] != '"') e++
                    while (e > i && s[e - 1] in ".,;:!?'") e--
                    if (e > i + 8) {
                        flush()
                        val url = s.substring(i, e)
                        out.add(base.copy(text = url, link = url))
                        i = e
                        continue
                    }
                }
            }
            sb.append(c)
            i++
        }
        flush()
    }

    /** _ による飾りは、単語の途中(snake_case など)では使わない。 */
    private fun boundaryBefore(s: String, i: Int) = i == 0 || !s[i - 1].isLetterOrDigit()

    private fun closeSingle(s: String, from: Int, mark: Char): Int {
        var k = from
        while (k < s.length) {
            if (s[k] == '\\') {
                k += 2
                continue
            }
            if (s[k] == mark) {
                val doubled = k + 1 < s.length && s[k + 1] == mark
                if (!doubled && !s[k - 1].isWhitespace() && (mark == '*' || k + 1 >= s.length || !s[k + 1].isLetterOrDigit())) return k
                if (doubled) k++ // ** は飛ばす
            }
            k++
        }
        return -1
    }

    private class LinkMatch(val text: String, val url: String, val end: Int)

    /** s[at] が '[' のとき、[文字](url) を読む。 */
    private fun linkAt(s: String, at: Int): LinkMatch? {
        var depth = 0
        var k = at
        while (k < s.length) {
            when (s[k]) {
                '\\' -> k++
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            k++
        }
        if (k >= s.length || s[k] != ']' || k + 1 >= s.length || s[k + 1] != '(') return null
        val close = s.indexOf(')', k + 2)
        if (close < 0) return null
        val url = s.substring(k + 2, close).trim().substringBefore(' ').trim('<', '>')
        return LinkMatch(s.substring(at + 1, k), url, close + 1)
    }
}
