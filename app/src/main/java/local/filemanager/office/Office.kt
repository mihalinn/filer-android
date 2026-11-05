package local.filemanager

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDateTime
import javax.xml.parsers.SAXParserFactory

/**
 * Office 文書(.xlsx / .docx / .pptx)から、中の文字や表を取り出す。本物の見た目の再現ではなく、「中身を読む」ための簡易表示用。
 * どれも、XML を ZIP にまとめた形式。Android に依存しない(SAX は Android にも PC にもある)。
 * read は、ZIP の中の項目(「xl/workbook.xml」など)を取り出す関数。無ければ null。
 */
internal object OfficeXml {
    /** 外部の読み込みを使わない SAX で、bytes を読む。 */
    fun parse(bytes: ByteArray, handler: DefaultHandler) {
        val f = SAXParserFactory.newInstance()
        f.isNamespaceAware = false
        for ((k, v) in listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
        )) {
            runCatching { f.setFeature(k, v) }
        }
        f.newSAXParser().parse(InputSource(ByteArrayInputStream(bytes)), handler)
    }

    /** 関係の定義(.rels)から、Id → 取り出す先の項目名(base からの相対を直す)。 */
    fun relationships(rels: ByteArray?, baseDir: String): Map<String, String> {
        if (rels == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        parse(
            rels,
            object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                    if (qName == "Relationship") {
                        val id = a.getValue("Id") ?: return
                        val target = a.getValue("Target") ?: return
                        // 外のページへのリンク(TargetMode="External")の宛先は、そのまま
                        out[id] = if (a.getValue("TargetMode") == "External") target else resolve(baseDir, target)
                    }
                }
            },
        )
        return out
    }

    /** 「xl/」からの相対の「worksheets/sheet1.xml」や、「/xl/…」(根からの絶対)を、ZIP の中の名前にする。 */
    fun resolve(baseDir: String, target: String): String {
        val raw = if (target.startsWith("/")) target.substring(1) else "$baseDir/$target"
        val out = ArrayList<String>()
        for (seg in raw.split('/')) when (seg) {
            "", "." -> {}
            ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
            else -> out.add(seg)
        }
        return out.joinToString("/")
    }
}

// ---------------------------------------------------------------- Excel(.xlsx)の表示形式(読み取りは Xlsx.kt)

internal object XlsxFormat {
    /** 「C5」→ 列の番号(0 から。C は 2)。 */
    fun columnIndex(ref: String): Int {
        var n = 0
        for (c in ref) {
            if (c !in 'A'..'Z' && c !in 'a'..'z') break
            n = n * 26 + (c.uppercaseChar() - 'A' + 1)
        }
        return (n - 1).coerceAtLeast(0)
    }

    /** 「C5」→ 行の番号(0 から。5 は 4)。 */
    fun rowIndex(ref: String): Int = (ref.dropWhile { it.isLetter() }.toIntOrNull() ?: 1) - 1

    /** 列の番号 → 「A」「B」…「AA」。 */
    fun columnName(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            n--
            sb.append('A' + n % 26)
            n /= 26
        }
        return sb.reverse().toString()
    }

    /** 「標準」の書式のように、有効数字 15 桁までで、余分な 0 と指数表記をなくす。 */
    fun numberText(raw: String): String = try {
        BigDecimal(raw).round(MathContext(15)).stripTrailingZeros().let { if (it.signum() == 0) "0" else it.toPlainString() }
    } catch (e: NumberFormatException) {
        raw
    }

    /** 組み込みの表示形式の番号 → 書式の定義(日付以外の主なもの)。 */
    fun builtinCode(id: Int): String? = when (id) {
        1 -> "0"
        2 -> "0.00"
        3 -> "#,##0"
        4 -> "#,##0.00"
        9 -> "0%"
        10 -> "0.00%"
        11 -> "0.00E+00"
        37, 38 -> "#,##0;(#,##0)"
        39, 40 -> "#,##0.00;(#,##0.00)"
        else -> null
    }

    /** 書式の定義を、「;」で区切った部分(正・負・ゼロ・文字)に分ける。引用符や [ ] の中の「;」は区切りにしない。 */
    internal fun splitSections(code: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quote = false
        var bracket = false
        for (c in code) {
            when {
                c == '"' && !bracket -> { quote = !quote; cur.append(c) }
                c == '[' && !quote -> { bracket = true; cur.append(c) }
                c == ']' && !quote -> { bracket = false; cur.append(c) }
                c == ';' && !quote && !bracket -> { out.add(cur.toString()); cur.setLength(0) }
                else -> cur.append(c)
            }
        }
        out.add(cur.toString())
        return out
    }

    /**
     * 数に、Excel の表示形式(例: #,##0 / 0.00 / 0% / "¥"#,##0 / #,##0"円" / [$¥-411]#,##0 / 0.00E+00 / #,##0;(#,##0))を当てはめる。
     * よく使う形だけを扱い、読み取れない形のときは「標準」の表示にする。
     */
    fun formatNumber(raw: String, code: String?): String {
        if (code == null || code.isBlank() || code.equals("General", ignoreCase = true) || code == "@") return numberText(raw)
        val v = try { BigDecimal(raw) } catch (e: NumberFormatException) { return raw }
        val sections = splitSections(code)
        val neg = v.signum() < 0
        val section = when {
            neg && sections.size >= 2 -> sections[1]
            v.signum() == 0 && sections.size >= 3 -> sections[2]
            else -> sections[0]
        }
        val signInSection = neg && sections.size >= 2 // 負の数の部分に、符号の書き方が含まれている
        return applySection(if (signInSection) v.abs() else v, section, showMinus = neg && !signInSection) ?: numberText(raw)
    }

    private fun applySection(value: BigDecimal, s: String, showMinus: Boolean): String? {
        val prefix = StringBuilder()
        val suffix = StringBuilder()
        val pattern = StringBuilder()
        var phase = 0 // 0: 数の前、1: 数、2: 数の後
        var percent = false
        var sciDigits = -1
        var i = 0
        fun literal(text: String) {
            if (phase == 0) prefix.append(text) else { phase = 2; suffix.append(text) }
        }
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> {
                    val j = s.indexOf('"', i + 1).let { if (it < 0) s.length else it }
                    literal(s.substring(i + 1, j))
                    i = j + 1
                }
                c == '\\' -> { if (i + 1 < s.length) literal(s[i + 1].toString()); i += 2 }
                c == '[' -> {
                    val j = s.indexOf(']', i).let { if (it < 0) s.length - 1 else it }
                    val inner = s.substring(i + 1, j)
                    if (inner.startsWith("$")) literal(inner.substring(1).substringBefore('-')) // [$¥-411] → ¥
                    i = j + 1
                }
                c == '_' -> { literal(" "); i += 2 }
                c == '*' -> i += 2
                c == '%' -> { percent = true; literal("%"); i++ }
                (c == 'E' || c == 'e') && i + 1 < s.length && (s[i + 1] == '+' || s[i + 1] == '-') && phase == 1 -> {
                    var j = i + 2
                    var zeros = 0
                    while (j < s.length && s[j] == '0') { zeros++; j++ }
                    sciDigits = zeros.coerceAtLeast(1)
                    phase = 2
                    i = j
                }
                c == '0' || c == '#' || c == '?' -> { if (phase == 2) literal(c.toString()) else { pattern.append(c); phase = 1 }; i++ }
                (c == '.' || c == ',') && phase != 2 && (phase == 1 || (i + 1 < s.length && s[i + 1] in "0#?")) -> { pattern.append(c); phase = 1; i++ }
                else -> { literal(c.toString()); i++ }
            }
        }
        if (pattern.isEmpty()) return prefix.toString() + suffix.toString() // 数を出さない形(文字だけ)
        val pat = pattern.toString().trimEnd(',') // 末尾の「,」は、千単位への縮小(読み飛ばす)
        val dot = pat.indexOf('.')
        val intPat = if (dot < 0) pat else pat.substring(0, dot)
        val fracPat = if (dot < 0) "" else pat.substring(dot + 1)
        val minInt = intPat.count { it == '0' }
        val grouping = intPat.contains(',')
        val minFrac = fracPat.count { it == '0' }
        val maxFrac = fracPat.count { it == '0' || it == '#' || it == '?' }
        var abs = value.abs()
        if (percent) abs = abs.multiply(BigDecimal(100))

        val body: String
        if (sciDigits > 0) {
            if (abs.signum() == 0) {
                body = "0" + (if (maxFrac > 0) "." + "0".repeat(minFrac) else "") + "E+" + "0".repeat(sciDigits)
            } else {
                var exp = abs.precision() - abs.scale() - 1
                var mant = abs.movePointLeft(exp).setScale(maxFrac, java.math.RoundingMode.HALF_UP)
                if (mant >= BigDecimal(10)) { exp++; mant = abs.movePointLeft(exp).setScale(maxFrac, java.math.RoundingMode.HALF_UP) }
                body = mant.toPlainString() + "E" + (if (exp < 0) "-" else "+") + Math.abs(exp).toString().padStart(sciDigits, '0')
            }
        } else {
            val rounded = abs.setScale(maxFrac, java.math.RoundingMode.HALF_UP)
            var intStr = rounded.toBigInteger().toString()
            if (intStr == "0" && minInt == 0 && maxFrac > 0) intStr = ""
            if (intStr.length < minInt) intStr = intStr.padStart(minInt, '0')
            if (grouping && intStr.length > 3) intStr = intStr.reversed().chunked(3).joinToString(",").reversed()
            var frac = if (maxFrac > 0) rounded.toPlainString().substringAfter('.', "") else ""
            while (frac.length > minFrac && frac.endsWith("0")) frac = frac.dropLast(1)
            body = intStr + if (frac.isNotEmpty()) ".$frac" else ""
        }
        return (if (showMinus && abs.signum() != 0) "-" else "") + prefix + body + suffix
    }

    private val builtinDate = (14..22).toSet() + (27..36).toSet() + (45..47).toSet() + (50..58).toSet()

    /** 書式の番号(と、独自の書式の定義)から、日付・時刻の書式か。 */
    fun isDateFormat(id: Int, code: String?): Boolean {
        if (id in builtinDate) return true
        if (code == null) return false
        // 引用符の中・[色]や[$¥-411]・\ で打ち消した文字を除いて、y m d h s があれば日付・時刻
        val stripped = code.replace(Regex("\"[^\"]*\""), "").replace(Regex("\\[[^\\]]*]"), "").replace(Regex("\\\\."), "")
        return stripped.any { it.lowercaseChar() in "ymdhs" } && !stripped.contains("General", ignoreCase = true)
    }

    /** シリアル値(1900 年からの日数)→「2026/10/24」「2026/10/24 13:05」「13:05:00」。 */
    fun dateText(raw: String, date1904: Boolean): String {
        val serial = raw.toDoubleOrNull() ?: return raw
        if (serial < 0 || serial > 2_958_465) return numberText(raw)
        val days = Math.floor(serial).toLong()
        val secs = Math.round((serial - days) * 86400.0).toInt().coerceAtMost(86399)
        val time = "%02d:%02d:%02d".format(secs / 3600, secs / 60 % 60, secs % 60)
        if (days == 0L) return time
        // Excel は 1900 年を閏年と数える誤りがあり、3 月 1 日より前(シリアル値 60 まで)は、基準が 1 日ずれる
        val epoch = when {
            date1904 -> LocalDateTime.of(1904, 1, 1, 0, 0)
            days < 61 -> LocalDateTime.of(1899, 12, 31, 0, 0)
            else -> LocalDateTime.of(1899, 12, 30, 0, 0)
        }
        val d = epoch.plusDays(days)
        val date = "%04d/%02d/%02d".format(d.year, d.monthValue, d.dayOfMonth)
        return if (secs == 0) date else date + " " + time.substring(0, 5)
    }
}

// ---------------------------------------------------------------- PowerPoint(.pptx)

class Slide(val number: Int, val title: String?, val lines: List<String>)

object Pptx {
    fun parse(read: (String) -> ByteArray?): List<Slide> {
        val pres = read("ppt/presentation.xml") ?: throw OpException(Txt(R.string.office_001))
        val rels = OfficeXml.relationships(read("ppt/_rels/presentation.xml.rels"), "ppt")
        val order = ArrayList<String>()
        OfficeXml.parse(
            pres,
            object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                    if (qName == "p:sldId") a.getValue("r:id")?.let { id -> rels[id]?.let { order.add(it) } }
                }
            },
        )
        return order.mapIndexed { i, path ->
            val bytes = read(path)
            if (bytes == null) Slide(i + 1, null, emptyList()) else slide(i + 1, bytes)
        }
    }

    internal fun slide(number: Int, bytes: ByteArray): Slide {
        var title: String? = null
        val lines = ArrayList<String>()
        OfficeXml.parse(
            bytes,
            object : DefaultHandler() {
                var inSp = false
                var isTitle = false
                var para = StringBuilder()
                var inT = false
                var inTable = false
                var cellParts = ArrayList<String>()
                var rowCells = ArrayList<String>()

                override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                    when (qName) {
                        "p:sp" -> { inSp = true; isTitle = false }
                        "p:ph" -> { val t = a.getValue("type"); if (t == "title" || t == "ctrTitle") isTitle = true }
                        "a:tbl" -> inTable = true
                        "a:tr" -> rowCells = ArrayList()
                        "a:tc" -> cellParts = ArrayList()
                        "a:p" -> para = StringBuilder()
                        "a:t" -> inT = true
                        "a:br" -> para.append('\n')
                    }
                }

                override fun endElement(uri: String?, localName: String?, qName: String) {
                    when (qName) {
                        "a:t" -> inT = false
                        "a:p" -> {
                            val s = para.toString().trim()
                            if (inTable) {
                                if (s.isNotEmpty()) cellParts.add(s)
                            } else if (s.isNotEmpty()) {
                                if (isTitle && title == null) title = s.replace('\n', ' ') else lines.add(s)
                            }
                        }
                        "a:tc" -> rowCells.add(cellParts.joinToString(" "))
                        "a:tr" -> lines.add(rowCells.joinToString(" | "))
                        "a:tbl" -> inTable = false
                        "p:sp" -> inSp = false
                    }
                }

                override fun characters(ch: CharArray, start: Int, length: Int) {
                    if (inT) para.append(ch, start, length)
                }
            },
        )
        return Slide(number, title, lines)
    }
}
