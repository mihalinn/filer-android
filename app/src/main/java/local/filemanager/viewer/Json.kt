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
            if (!p.atEnd()) throw Bad(Txt(R.string.json_001))
            return v
        } catch (e: Bad) {
            throw OpException(Txt(R.string.json_002, e.message))
        } catch (e: StackOverflowError) {
            throw OpException(Txt(R.string.json_003))
        }
    }

    private class Parser(val s: String) {
        var i = 0
        fun atEnd() = i >= s.length

        fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun value(depth: Int): Any? {
            if (depth > 100) throw Bad(Txt(R.string.json_004))
            if (i >= s.length) throw Bad(Txt(R.string.json_005))
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw Bad(Txt(R.string.json_bad_char, c, i))
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw Bad(Txt(R.string.json_bad_word, i))
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
                if (i >= s.length || s[i] != '"') throw Bad(Txt(R.string.json_no_key, i))
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') throw Bad(Txt(R.string.json_no_colon, i))
                i++
                ws()
                m[k] = value(depth + 1)
                ws()
                if (i >= s.length) throw Bad(Txt(R.string.json_006))
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return m }
                throw Bad(Txt(R.string.json_no_comma_or_brace, i))
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
                if (i >= s.length) throw Bad(Txt(R.string.json_007))
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return l }
                throw Bad(Txt(R.string.json_no_comma_or_bracket, i))
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
                        if (i >= s.length) throw Bad(Txt(R.string.json_008))
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw Bad(Txt(R.string.json_009))
                                sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: throw Bad(Txt(R.string.json_010)))
                                i += 4
                            }
                            else -> throw Bad(Txt(R.string.json_011, e))
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw Bad(Txt(R.string.json_012))
        }

        private fun num(): Any {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(st, i)
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: throw Bad(Txt(R.string.json_013, t))
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
