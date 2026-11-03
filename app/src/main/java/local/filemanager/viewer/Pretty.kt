package local.filemanager

/** JSON / XML の整形(字下げ)。形式が正しくないときは null を返す。Android に依存しない。 */
object Pretty {
    private const val MAX_DEPTH = 200
    private const val INLINE_ARRAY_MAX = 80

    private class Bad : Exception()

    // ---------------------------------------------------------------- JSON

    fun json(text: String): String? {
        val t = text.removePrefix("﻿")
        if (t.isBlank()) return null
        whole(t)?.let { return it }
        // 1 行に 1 つずつ JSON が並ぶ形式(JSON Lines)
        val lines = t.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return null
        val out = ArrayList<String>(lines.size)
        for (l in lines) out.add(whole(l) ?: return null)
        return out.joinToString("\n\n")
    }

    private fun whole(t: String): String? = try {
        val p = JsonFormatter(t)
        val r = p.value(0)
        p.skipWs()
        if (p.atEnd()) r else null
    } catch (e: Bad) {
        null
    }

    private class JsonFormatter(val s: String) {
        var i = 0

        fun atEnd() = i >= s.length

        fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        private fun indent(depth: Int) = "  ".repeat(depth)

        fun value(depth: Int): String {
            if (depth > MAX_DEPTH) throw Bad()
            skipWs()
            if (i >= s.length) throw Bad()
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> string()
                else -> scalar()
            }
        }

        private fun obj(depth: Int): String {
            i++ // {
            skipWs()
            if (i < s.length && s[i] == '}') {
                i++
                return "{}"
            }
            val members = ArrayList<String>()
            while (true) {
                skipWs()
                if (i >= s.length || s[i] != '"') throw Bad()
                val key = string()
                skipWs()
                if (i >= s.length || s[i] != ':') throw Bad()
                i++
                members.add(key + ": " + value(depth + 1))
                skipWs()
                if (i >= s.length) throw Bad()
                if (s[i] == ',') {
                    i++
                    continue
                }
                if (s[i] == '}') {
                    i++
                    break
                }
                throw Bad()
            }
            val pad = indent(depth + 1)
            return "{\n" + members.joinToString(",\n") { pad + it } + "\n" + indent(depth) + "}"
        }

        private fun arr(depth: Int): String {
            i++ // [
            skipWs()
            if (i < s.length && s[i] == ']') {
                i++
                return "[]"
            }
            val items = ArrayList<String>()
            while (true) {
                items.add(value(depth + 1))
                skipWs()
                if (i >= s.length) throw Bad()
                if (s[i] == ',') {
                    i++
                    continue
                }
                if (s[i] == ']') {
                    i++
                    break
                }
                throw Bad()
            }
            // 数や短い文字だけの配列は、1 行にまとめる
            val inline = items.none { it.contains('\n') } && items.sumOf { it.length + 2 } <= INLINE_ARRAY_MAX
            if (inline) return "[" + items.joinToString(", ") + "]"
            val pad = indent(depth + 1)
            return "[\n" + items.joinToString(",\n") { pad + it } + "\n" + indent(depth) + "]"
        }

        private fun string(): String {
            val start = i
            i++ // "
            while (i < s.length) {
                val c = s[i]
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == '"') {
                    i++
                    return s.substring(start, i)
                }
                if (c == '\n') throw Bad()
                i++
            }
            throw Bad()
        }

        private fun scalar(): String {
            for (lit in arrayOf("true", "false", "null")) {
                if (s.startsWith(lit, i)) {
                    i += lit.length
                    return lit
                }
            }
            val start = i
            while (i < s.length && s[i] in "+-0123456789.eE") i++
            if (i == start) throw Bad()
            val num = s.substring(start, i)
            if (!num.any { it.isDigit() }) throw Bad()
            return num
        }
    }

    // ---------------------------------------------------------------- XML

    private class XNode(val open: String, val name: String) {
        /** 子(XNode か、文字列=テキスト/コメント/処理命令) */
        val kids = ArrayList<Any>()
        var close: String = ""
    }

    fun xml(text: String): String? {
        val t = text.removePrefix("﻿")
        if (t.isBlank()) return null
        return try {
            val nodes = parseXml(t)
            if (nodes.none { it is XNode }) throw Bad() // タグが 1 つも無い文章は XML ではない
            format(nodes)
        } catch (e: Bad) {
            null
        }
    }

    private fun parseXml(s: String): List<Any> {
        val root = XNode("", "")
        val stack = ArrayList<XNode>()
        stack.add(root)
        var i = 0
        val n = s.length
        while (i < n) {
            if (s[i] != '<') {
                val e = s.indexOf('<', i).let { if (it < 0) n else it }
                val txt = s.substring(i, e).trim()
                if (txt.isNotEmpty()) stack.last().kids.add(txt)
                i = e
                continue
            }
            when {
                s.startsWith("<!--", i) -> {
                    val e = s.indexOf("-->", i + 4)
                    if (e < 0) throw Bad()
                    stack.last().kids.add(s.substring(i, e + 3))
                    i = e + 3
                }
                s.startsWith("<![CDATA[", i) -> {
                    val e = s.indexOf("]]>", i + 9)
                    if (e < 0) throw Bad()
                    stack.last().kids.add(s.substring(i, e + 3))
                    i = e + 3
                }
                s.startsWith("<?", i) -> {
                    val e = s.indexOf("?>", i + 2)
                    if (e < 0) throw Bad()
                    stack.last().kids.add(s.substring(i, e + 2))
                    i = e + 2
                }
                s.startsWith("<!", i) -> {
                    // DOCTYPE など。[ ] の入れ子を考えて閉じの > を探す
                    var depth = 0
                    var j = i + 2
                    while (j < n) {
                        val c = s[j]
                        if (c == '[') depth++ else if (c == ']') depth-- else if (c == '>' && depth <= 0) break
                        j++
                    }
                    if (j >= n) throw Bad()
                    stack.last().kids.add(s.substring(i, j + 1))
                    i = j + 1
                }
                s.startsWith("</", i) -> {
                    val e = s.indexOf('>', i)
                    if (e < 0) throw Bad()
                    val name = s.substring(i + 2, e).trim()
                    val top = stack.last()
                    if (stack.size <= 1 || top.name != name) throw Bad()
                    top.close = "</$name>"
                    stack.removeAt(stack.size - 1)
                    i = e + 1
                }
                else -> {
                    // 開始タグ。属性の引用符の中の > は無視して閉じを探す
                    var j = i + 1
                    var q = 0.toChar()
                    while (j < n) {
                        val c = s[j]
                        if (q != 0.toChar()) {
                            if (c == q) q = 0.toChar()
                        } else if (c == '"' || c == '\'') {
                            q = c
                        } else if (c == '>') {
                            break
                        }
                        j++
                    }
                    if (j >= n) throw Bad()
                    val tag = s.substring(i, j + 1)
                    val name = tag.substring(1).takeWhile { !it.isWhitespace() && it != '/' && it != '>' }
                    if (name.isEmpty()) throw Bad()
                    val node = XNode(tag, name)
                    stack.last().kids.add(node)
                    if (tag.endsWith("/>")) {
                        // 中身なし
                    } else {
                        stack.add(node)
                        if (stack.size > MAX_DEPTH) throw Bad()
                    }
                    i = j + 1
                }
            }
        }
        if (stack.size != 1) throw Bad()
        return root.kids
    }

    private fun format(nodes: List<Any>): String {
        val sb = StringBuilder()
        for (k in nodes) emit(k, 0, sb)
        return sb.toString().trimEnd('\n')
    }

    private fun emit(node: Any, depth: Int, sb: StringBuilder) {
        val pad = "  ".repeat(depth)
        if (node is String) {
            for (line in node.lines()) sb.append(pad).append(line.trim()).append('\n')
            return
        }
        node as XNode
        if (node.close.isEmpty()) {
            sb.append(pad).append(node.open).append('\n') // <a/> など
            return
        }
        val onlyText = node.kids.size <= 1 && node.kids.all { it is String && !it.startsWith("<!--") }
        if (node.kids.isEmpty() || onlyText) {
            val inner = node.kids.firstOrNull() as String? ?: ""
            if (!inner.contains('\n')) {
                sb.append(pad).append(node.open).append(inner).append(node.close).append('\n')
                return
            }
        }
        sb.append(pad).append(node.open).append('\n')
        for (k in node.kids) emit(k, depth + 1, sb)
        sb.append(pad).append(node.close).append('\n')
    }
}
