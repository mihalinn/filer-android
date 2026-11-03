package local.filemanager

/** SQLite の表示まわりの、Android に依存しない部品(値の見せ方・安全な検索文・名前の引用)。 */
object SqlFormat {
    /** 識別子(表の名前・列の名前)を、SQL に書ける形にする("" で囲み、中の " は二重にする)。 */
    fun quote(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    /** 先頭のコメント・空白を除いた、最初の単語(大文字)。 */
    private fun firstWord(sql: String): String {
        var s = sql.trimStart()
        while (true) {
            if (s.startsWith("--")) {
                val nl = s.indexOf('\n')
                s = if (nl < 0) "" else s.substring(nl + 1).trimStart()
            } else if (s.startsWith("/*")) {
                val e = s.indexOf("*/")
                s = if (e < 0) "" else s.substring(e + 2).trimStart()
            } else {
                break
            }
        }
        return Regex("^[A-Za-z]+").find(s)?.value?.uppercase() ?: ""
    }

    /** 読むだけの検索文か(SELECT / WITH / VALUES / EXPLAIN / PRAGMA)。複数の文は受けつけない。 */
    fun isReadOnlyQuery(sql: String): Boolean {
        val body = sql.trim().trimEnd(';').trim()
        if (body.isEmpty()) return false
        if (firstWord(body) !in setOf("SELECT", "WITH", "VALUES", "EXPLAIN", "PRAGMA")) return false
        return !hasSecondStatement(body)
    }

    /** 文字列・引用符・コメントの外に「;」があるか(2 つ目の文がある)。 */
    fun hasSecondStatement(sql: String): Boolean {
        var i = 0
        var quote: Char? = null
        while (i < sql.length) {
            val c = sql[i]
            when {
                quote != null -> if (c == quote) {
                    if (i + 1 < sql.length && sql[i + 1] == quote) i++ else quote = null
                }
                c == '\'' || c == '"' || c == '`' -> quote = c
                c == '[' -> {
                    val e = sql.indexOf(']', i)
                    if (e < 0) return false
                    i = e
                }
                c == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> {
                    val e = sql.indexOf('\n', i)
                    if (e < 0) return false
                    i = e
                }
                c == '/' && i + 1 < sql.length && sql[i + 1] == '*' -> {
                    val e = sql.indexOf("*/", i + 2)
                    if (e < 0) return false
                    i = e + 1
                }
                c == ';' -> return sql.substring(i + 1).isNotBlank()
            }
            i++
        }
        return false
    }

    /** バイナリ(BLOB)の見せ方: 「BLOB 1,234 バイト(89 50 4E 47 …)」 */
    fun blob(bytes: ByteArray): String {
        val head = bytes.take(8).joinToString(" ") { "%02X".format(it) }
        return "BLOB ${"%,d".format(bytes.size)} バイト" + if (bytes.isNotEmpty()) "($head${if (bytes.size > 8) " …" else ""})" else ""
    }

    /** 実数の見せ方(1.0 → 1、1.5 → 1.5)。 */
    fun real(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return d.toString()
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return "%.0f".format(d)
        return d.toString()
    }

    /** 1 つ前・次のページの先頭の行(0 から)。範囲を超えない。 */
    fun pageStart(current: Int, pageSize: Int, total: Long, forward: Boolean): Int {
        val next = if (forward) current + pageSize else current - pageSize
        return next.coerceAtLeast(0).coerceAtMost(if (total <= 0) 0 else ((total - 1) / pageSize * pageSize).toInt())
    }
}
