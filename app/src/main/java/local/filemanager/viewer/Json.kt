package local.filemanager

/**
 * JSON を、Kotlin の値の木にする小さな読み取り。Android 標準の org.json は PC 上のテストで動かないので、自前で持つ。
 * 結果: オブジェクト = LinkedHashMap<String, Any?>、配列 = List<Any?>、文字 = String、数 = Long か Double、真偽 = Boolean、null = null。
 */
object Json {
    private class Bad(msg: String) : Exception(msg)

    /** 読めなければ OpException。 */
    fun parse(text: String): Any? {
        val p = Parser(text.removePrefix("﻿"))
        try {
            p.ws()
            val v = p.value(0)
            p.ws()
            if (!p.atEnd()) throw Bad("末尾に余分な文字があります")
            return v
        } catch (e: Bad) {
            throw OpException("JSONとして読めません(${e.message})")
        } catch (e: StackOverflowError) {
            throw OpException("JSONの入れ子が深すぎます")
        }
    }

    private class Parser(val s: String) {
        var i = 0
        fun atEnd() = i >= s.length

        fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun value(depth: Int): Any? {
            if (depth > 100) throw Bad("入れ子が深すぎます")
            if (i >= s.length) throw Bad("途中で終わっています")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw Bad("想定外の文字「$c」(${i}文字目)")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw Bad("想定外の語(${i}文字目)")
            i += word.length
            return v
        }

        private fun obj(depth: Int): Any {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw Bad("キーの文字列がない(${i}文字目)")
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') throw Bad("「:」がありません(${i}文字目)")
                i++
                ws()
                m[k] = value(depth + 1)
                ws()
                if (i >= s.length) throw Bad("途中で終わっています")
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return m }
                throw Bad("「,」か「}」がありません(${i}文字目)")
            }
        }

        private fun arr(depth: Int): Any {
            val l = ArrayList<Any?>()
            i++
            ws()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                ws()
                l.add(value(depth + 1))
                ws()
                if (i >= s.length) throw Bad("途中で終わっています")
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return l }
                throw Bad("「,」か「]」がありません(${i}文字目)")
            }
        }

        private fun str(): String {
            i++ // 開きの「"」
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) throw Bad("途中で終わっています")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw Bad("\\u の後ろが足りません")
                                sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: throw Bad("\\u の数が読めません"))
                                i += 4
                            }
                            else -> throw Bad("想定外のエスケープ「\\$e」")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw Bad("文字列が閉じていません")
        }

        private fun num(): Any {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(st, i)
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: throw Bad("数が読めない「$t」")
        }
    }

    // ---- 取り出しの補助

    @Suppress("UNCHECKED_CAST")
    fun obj(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun arr(v: Any?): List<Any?> = (v as? List<Any?>) ?: emptyList()

    fun str(v: Any?): String? = when (v) {
        is String -> v
        is Long -> v.toString()
        is Double -> if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()
        is Boolean -> v.toString()
        else -> null
    }
}
