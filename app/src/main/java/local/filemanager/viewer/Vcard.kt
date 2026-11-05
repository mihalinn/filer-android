package local.filemanager

import java.nio.charset.Charset
import java.util.Base64

/** 連絡先 1 件。fields は「ラベル → 値」の並び(電話・メール・住所など)。 */
class Contact(
    val name: String,
    val org: String,
    val fields: List<VField>,
    val photo: ByteArray?,
)

data class VField(val label: String, val value: String, val kind: Kind = Kind.TEXT) {
    enum class Kind { TEXT, PHONE, EMAIL, URL }
}

/** vCard(.vcf。バージョン 2.1 / 3.0 / 4.0)の読み取り。Android に依存しない。 */
object Vcard {
    fun parse(text: String): List<Contact> {
        val cards = ArrayList<Contact>()
        var cur: ArrayList<Prop>? = null
        for (line in unfold(text)) {
            if (line.isBlank()) continue
            val p = prop(line) ?: continue
            when {
                p.name == "BEGIN" && p.value.equals("VCARD", ignoreCase = true) -> cur = ArrayList()
                p.name == "END" && p.value.equals("VCARD", ignoreCase = true) -> {
                    cur?.let { cards.add(build(it)) }
                    cur = null
                }
                else -> cur?.add(p)
            }
        }
        return cards
    }

    private class Prop(val name: String, val params: Map<String, List<String>>, val value: String)

    /** 折り返された行(次の行が空白で始まる)と、QUOTED-PRINTABLE の「=」で終わる続きを、1 行につなぐ。 */
    private fun unfold(text: String): List<String> {
        val raw = text.removePrefix("﻿").split("\r\n", "\n", "\r")
        val out = ArrayList<String>()
        var i = 0
        while (i < raw.size) {
            val sb = StringBuilder(raw[i])
            i++
            while (i < raw.size && raw[i].isNotEmpty() && (raw[i][0] == ' ' || raw[i][0] == '\t')) {
                sb.append(raw[i], 1, raw[i].length)
                i++
            }
            // QUOTED-PRINTABLE(2.1)の、行末の「=」は続きの印
            val head = sb.toString()
            if (head.contains("QUOTED-PRINTABLE", ignoreCase = true)) {
                while (sb.endsWith("=") && i < raw.size) {
                    sb.setLength(sb.length - 1)
                    sb.append(raw[i])
                    i++
                }
            }
            out.add(sb.toString())
        }
        return out
    }

    private fun prop(line: String): Prop? {
        // 値の中の「:」と、引用符つきの値の中の「:」「;」に注意して、最初の「:」で分ける
        var inQuote = false
        var colon = -1
        for ((i, c) in line.withIndex()) {
            if (c == '"') inQuote = !inQuote
            if (c == ':' && !inQuote) {
                colon = i
                break
            }
        }
        if (colon <= 0) return null
        val left = line.substring(0, colon)
        val value = line.substring(colon + 1)
        val parts = splitParams(left)
        var name = parts[0].substringAfterLast('.').uppercase() // 「item1.TEL」の接頭辞を外す
        val params = LinkedHashMap<String, MutableList<String>>()
        for (seg in parts.drop(1)) {
            val eq = seg.indexOf('=')
            if (eq < 0) {
                // 2.1: 「TEL;CELL;VOICE:」のように、TYPE= を省いた書き方
                params.getOrPut("TYPE") { ArrayList() }.add(seg.uppercase())
            } else {
                val k = seg.substring(0, eq).uppercase()
                val v = seg.substring(eq + 1).trim('"').split(',').map { it.trim('"') }
                params.getOrPut(k) { ArrayList() }.addAll(v)
            }
        }
        if (name.isEmpty()) name = parts[0].uppercase()
        return Prop(name, params, value)
    }

    private fun splitParams(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var q = false
        for (c in s) {
            if (c == '"') q = !q
            if (c == ';' && !q) {
                out.add(cur.toString())
                cur.setLength(0)
            } else {
                cur.append(c)
            }
        }
        out.add(cur.toString())
        return out
    }

    private fun charsetOf(p: Prop): Charset {
        val cs = p.params["CHARSET"]?.firstOrNull() ?: return Charsets.UTF_8
        return try {
            Charset.forName(cs)
        } catch (e: Exception) {
            Charsets.UTF_8
        }
    }

    internal fun qpDecode(s: String, cs: Charset): String {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '=' && i + 2 < s.length) {
                val h = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (h != null) {
                    out.write(h)
                    i += 3
                    continue
                }
            }
            val b = c.toString().toByteArray(Charsets.ISO_8859_1)
            out.write(b, 0, b.size)
            i++
        }
        return String(out.toByteArray(), cs)
    }

    /** 値を文字にする(QUOTED-PRINTABLE の復号)。「\n」などの打ち消しは、分けたあとで外す。 */
    private fun decoded(p: Prop): String {
        val enc = p.params["ENCODING"]?.firstOrNull()?.uppercase()
        return if (enc == "QUOTED-PRINTABLE" || p.params["TYPE"]?.any { it == "QUOTED-PRINTABLE" } == true) qpDecode(p.value, charsetOf(p)) else p.value
    }

    /** 「;」で区切られた複数の部分に分ける(「\;」は区切りにしない)。 */
    private fun parts(v: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < v.length) {
            val c = v[i]
            if (c == '\\' && i + 1 < v.length) {
                cur.append(c).append(v[i + 1])
                i += 2
                continue
            }
            if (c == ';') {
                out.add(unescape(cur.toString()))
                cur.setLength(0)
            } else {
                cur.append(c)
            }
            i++
        }
        out.add(unescape(cur.toString()))
        return out
    }

    private fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n', 'N' -> sb.append('\n')
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun typeLabels() = mapOf(
        "CELL" to Txt(R.string.vcard_001), "HOME" to Txt(R.string.vcard_002), "WORK" to Txt(R.string.vcard_003), "FAX" to "FAX", "VOICE" to "", "PREF" to "", "INTERNET" to "",
        "MSG" to "", "PAGER" to Txt(R.string.vcard_004), "VIDEO" to Txt(R.string.vcard_005), "TEXT" to "",
    )

    private fun typeText(p: Prop): String {
        val labels = typeLabels()
        return (p.params["TYPE"] ?: emptyList()).map { labels[it.uppercase()] ?: it }.filter { it.isNotEmpty() }.distinct().joinToString(Txt(R.string.sep_dot))
    }

    private fun build(props: List<Prop>): Contact {
        var fn = ""
        var nameParts: List<String> = emptyList()
        var org = ""
        var title = ""
        var photo: ByteArray? = null
        val fields = ArrayList<VField>()
        fun label(base: String, p: Prop): String = typeText(p).let { if (it.isEmpty()) base else "$base($it)" }

        for (p in props) {
            val v = decoded(p)
            when (p.name) {
                "FN" -> fn = unescape(v).trim()
                "N" -> nameParts = parts(v)
                "ORG" -> org = parts(v).filter { it.isNotBlank() }.joinToString(" ")
                "TITLE" -> title = unescape(v).trim()
                "TEL" -> unescape(v).trim().takeIf { it.isNotEmpty() }?.let { fields.add(VField(label(Txt(R.string.vcard_006), p), it.removePrefix("tel:"), VField.Kind.PHONE)) }
                "EMAIL" -> unescape(v).trim().takeIf { it.isNotEmpty() }?.let { fields.add(VField(label(Txt(R.string.vcard_007), p), it.removePrefix("mailto:"), VField.Kind.EMAIL)) }
                "URL" -> unescape(v).trim().takeIf { it.isNotEmpty() }?.let { fields.add(VField(label("Web", p), it, VField.Kind.URL)) }
                "ADR" -> {
                    // 私書箱;拡張;番地;市区町村;都道府県;郵便番号;国 → 日本の並び(〒、国、都道府県、市区町村、番地)
                    val a = parts(v).let { l -> List(7) { l.getOrElse(it) { "" }.trim() } }
                    val zip = if (a[5].isNotEmpty()) Txt(R.string.vcard_008, a[5]) else ""
                    val body = listOf(a[6], a[4], a[3], a[2], a[1], a[0]).filter { it.isNotEmpty() }.joinToString(" ")
                    if (zip.isNotEmpty() || body.isNotEmpty()) fields.add(VField(label(Txt(R.string.vcard_009), p), (zip + body).trim()))
                }
                "BDAY" -> unescape(v).trim().takeIf { it.isNotEmpty() }?.let { fields.add(VField(Txt(R.string.vcard_010), it)) }
                "NOTE" -> unescape(v).trim().takeIf { it.isNotEmpty() }?.let { fields.add(VField(Txt(R.string.vcard_011), it)) }
                "NICKNAME" -> unescape(v).trim().takeIf { it.isNotEmpty() }?.let { fields.add(VField(Txt(R.string.vcard_012), it)) }
                "PHOTO", "LOGO" -> if (p.name == "PHOTO" && photo == null) photo = photoBytes(p)
                else -> {}
            }
        }
        if (title.isNotEmpty()) fields.add(0, VField(Txt(R.string.vcard_013), title))
        val name = fn.ifEmpty {
            // 姓 名 の順(日本語の並び)。「N」は 姓;名;ミドル;敬称(前);敬称(後)
            val l = List(5) { nameParts.getOrElse(it) { "" }.trim() }
            listOf(l[3], l[0], l[1], l[2], l[4]).filter { it.isNotEmpty() }.joinToString(" ")
        }.ifEmpty { org }.ifEmpty { Txt(R.string.vcard_014) }
        return Contact(name, org, fields, photo)
    }

    private fun photoBytes(p: Prop): ByteArray? {
        val v = p.value.trim()
        if (v.isEmpty()) return null
        return try {
            when {
                v.startsWith("data:", ignoreCase = true) -> Base64.getMimeDecoder().decode(v.substringAfter("base64,"))
                v.startsWith("http", ignoreCase = true) -> null // 外のサーバーからは取らない
                else -> Base64.getMimeDecoder().decode(v)
            }
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
