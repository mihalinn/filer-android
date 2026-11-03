package local.filemanager

import java.nio.charset.Charset

/** MHTML(.mht / .mhtml。ウェブページを、画像や CSS ごと 1 つにまとめたもの)の中の 1 つ分。body は、送り方(base64 など)を元に戻した中身。 */
class MhtPart(
    val contentType: String,
    val charset: String?,
    val location: String?,
    val contentId: String?,
    val body: ByteArray,
) {
    val isHtml: Boolean get() = contentType == "text/html" || contentType == "application/xhtml+xml"
    val isCss: Boolean get() = contentType == "text/css"

    /** 文字として読むもの(HTML・CSS・JS など)の文字列。文字コードの指定が無ければ、中身から見分ける。 */
    fun text(): String {
        val cs = charset?.let { runCatching { Charset.forName(it) }.getOrNull() }
        return if (cs != null) String(body, cs).removePrefix("\uFEFF") else TextDecode.decode(body, false).text
    }
}

class MhtDoc(
    val subject: String?,
    val date: String?,
    val pageUrl: String?,
    val parts: List<MhtPart>,
) {
    /** 本文の HTML(最初の HTML) */
    val main: MhtPart? = parts.firstOrNull { it.isHtml }

    private val byUrl: Map<String, MhtPart> = HashMap<String, MhtPart>().also { m ->
        for (p in parts) p.location?.let { m.putIfAbsent(Mhtml.normalizeUrl(it), p) }
    }
    private val byCid: Map<String, MhtPart> = HashMap<String, MhtPart>().also { m ->
        for (p in parts) p.contentId?.trim('<', '>', ' ')?.let { m.putIfAbsent(it, p) }
        // Chrome(Blink)は、CSS を「Content-Location: cid:css-…」で入れる
        for (p in parts) p.location?.takeIf { it.startsWith("cid:", ignoreCase = true) }?.let { m.putIfAbsent(it.substring(4).trim('<', '>', ' '), p) }
    }

    fun findUrl(url: String): MhtPart? = byUrl[Mhtml.normalizeUrl(url)]
    fun findCid(cid: String): MhtPart? = byCid[cid.trim('<', '>', ' ')]
}

/** MHTML の読み取り。Chrome・Edge の「ページを保存」(Blink)や、IE・Firefox の拡張などで作られたもの。Android に依存しない。 */
object Mhtml {
    private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())

    /** 「#」以降を除く。 */
    fun normalizeUrl(u: String): String = u.trim().substringBefore('#')

    /** viewport の指定(幅 = 画面の幅 など)を取り除く。パソコンの画面で保存したページを、パソコン向けの幅(広い画面を縮めて見る形)で表示するため。 */
    fun stripViewportMeta(html: String): String =
        Regex("(?is)<meta\\b[^>]*\\bname\\s*=\\s*[\"']?viewport[\"']?[^>]*>").replace(html, "")

    /** HTML・CSS の中の「cid:xxx」を、アプリが返せるアドレスに書き換える。 */
    fun rewriteCid(text: String, prefix: String): String =
        if (!text.contains("cid:", ignoreCase = true)) text else Regex("(?i)cid:([^\"'\\s)>]+)").replace(text) { prefix + it.groupValues[1] }

    private class Raw(val headers: Map<String, String>, val body: ByteArray)

    private fun indexOf(h: ByteArray, n: ByteArray, from: Int): Int {
        outer@ for (i in from..h.size - n.size) {
            for (j in n.indices) if (h[i + j] != n[j]) continue@outer
            return i
        }
        return -1
    }

    /** ヘッダーと本文に分ける(空の行が境)。 */
    private fun split(b: ByteArray, start: Int, end: Int): Raw? {
        var i = start
        var hdrEnd = -1
        var bodyStart = -1
        while (i < end) {
            val nl = findLineEnd(b, i, end)
            if (nl < 0) break
            val lineLen = nl - i - (if (nl > i && b[nl - 1] == '\r'.code.toByte()) 1 else 0)
            if (lineLen == 0) { hdrEnd = i; bodyStart = nl + 1; break }
            i = nl + 1
        }
        if (hdrEnd < 0) return null
        val headers = parseHeaders(String(b, start, hdrEnd - start, Charsets.ISO_8859_1))
        return Raw(headers, b.copyOfRange(bodyStart.coerceAtMost(end), end))
    }

    private fun findLineEnd(b: ByteArray, from: Int, end: Int): Int {
        for (i in from until end) if (b[i] == '\n'.code.toByte()) return i
        return -1
    }

    private fun parseHeaders(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var key: String? = null
        for (raw in text.split('\n')) {
            val line = raw.trimEnd('\r')
            if (line.isEmpty()) continue
            if ((line[0] == ' ' || line[0] == '\t') && key != null) {
                out[key] = out[key] + " " + line.trim()
            } else {
                val c = line.indexOf(':')
                if (c > 0) {
                    key = line.substring(0, c).trim().lowercase()
                    out.putIfAbsent(key, line.substring(c + 1).trim())
                }
            }
        }
        return out
    }

    private fun param(h: String?, name: String): String? {
        h ?: return null
        val m = Regex("(?i)(?:^|[;\\s])" + name + "\\s*=\\s*(\"([^\"]*)\"|[^;\\s]+)").find(h) ?: return null
        return m.groupValues[2].ifEmpty { m.groupValues[1].trim('"') }
    }

    /** =?utf-8?Q?...?= / =?utf-8?B?...?= の連なりを、文字にする。 */
    fun decodeWords(s: String?): String? {
        s ?: return null
        val word = Regex("=\\?([^?]+)\\?([QqBb])\\?([^?]*)\\?=")
        if (!word.containsMatchIn(s)) return s
        // 隣り合う符号化された語の間の空白は、取り除く
        val joined = s.replace(Regex("(\\?=)\\s+(=\\?)"), "$1$2")
        return word.replace(joined) { m ->
            val cs = runCatching { Charset.forName(m.groupValues[1]) }.getOrDefault(Charsets.UTF_8)
            val bytes = if (m.groupValues[2].equals("B", ignoreCase = true)) decodeBase64(m.groupValues[3].toByteArray(Charsets.ISO_8859_1)) else decodeQp(m.groupValues[3].replace('_', ' ').toByteArray(Charsets.ISO_8859_1), false)
            String(bytes, cs)
        }
    }

    fun decodeBase64(b: ByteArray): ByteArray = try {
        java.util.Base64.getMimeDecoder().decode(b)
    } catch (e: IllegalArgumentException) {
        ByteArray(0)
    }

    /** quoted-printable。soft = 行末の「=」で、改行をつなぐ。 */
    fun decodeQp(b: ByteArray, soft: Boolean = true): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            val c = b[i].toInt()
            if (c == '='.code) {
                if (soft && i + 1 < b.size && (b[i + 1] == '\r'.code.toByte() || b[i + 1] == '\n'.code.toByte())) {
                    i += if (b[i + 1] == '\r'.code.toByte() && i + 2 < b.size && b[i + 2] == '\n'.code.toByte()) 3 else 2
                    continue
                }
                if (i + 2 < b.size) {
                    val h = Character.digit(b[i + 1].toInt().toChar(), 16)
                    val l = Character.digit(b[i + 2].toInt().toChar(), 16)
                    if (h >= 0 && l >= 0) {
                        out.write(h * 16 + l)
                        i += 3
                        continue
                    }
                }
            }
            out.write(c)
            i++
        }
        return out.toByteArray()
    }
    private fun decodeBody(body: ByteArray, encoding: String?): ByteArray = when (encoding?.lowercase()) {
        "base64" -> decodeBase64(body)
        "quoted-printable" -> decodeQp(body)
        else -> body // binary / 7bit / 8bit
    }

    fun parse(bytes: ByteArray): MhtDoc? {
        val top = split(bytes, 0, bytes.size) ?: return null
        val ct = top.headers["content-type"] ?: return null
        if (!ct.lowercase().startsWith("multipart/")) {
            // 1 つだけのもの(まれ)
            val p = single(top) ?: return null
            return MhtDoc(decodeWords(top.headers["subject"]), top.headers["date"], top.headers["snapshot-content-location"] ?: p.location, listOf(p))
        }
        val parts = ArrayList<MhtPart>()
        collect(top, parts, 0)
        if (parts.isEmpty()) return null
        return MhtDoc(decodeWords(top.headers["subject"]), top.headers["date"], top.headers["snapshot-content-location"], parts)
    }

    private fun single(r: Raw): MhtPart? {
        val ct = r.headers["content-type"] ?: "application/octet-stream"
        return MhtPart(
            ct.substringBefore(';').trim().lowercase(), param(ct, "charset"),
            r.headers["content-location"]?.trim(), r.headers["content-id"]?.trim(), decodeBody(r.body, r.headers["content-transfer-encoding"]),
        )
    }

    private fun collect(container: Raw, out: MutableList<MhtPart>, depth: Int) {
        val ct = container.headers["content-type"] ?: return
        val boundary = param(ct, "boundary") ?: return
        val body = container.body
        val delim = ("--$boundary").toByteArray(Charsets.ISO_8859_1)
        // 区切りは、行の先頭の「--境界」
        val starts = ArrayList<Int>()
        var i = 0
        while (true) {
            val at = indexOf(body, delim, i)
            if (at < 0) break
            if (at == 0 || body[at - 1] == '\n'.code.toByte()) starts.add(at)
            i = at + delim.size
        }
        for (k in starts.indices) {
            val s = starts[k] + delim.size
            if (s + 1 < body.size && body[s] == '-'.code.toByte() && body[s + 1] == '-'.code.toByte()) break // 終わり
            // 区切りの行の終わりまで読み飛ばす
            var ps = s
            while (ps < body.size && body[ps] != '\n'.code.toByte()) ps++
            ps++
            var pe = if (k + 1 < starts.size) starts[k + 1] else body.size
            // 次の区切りの前の改行は、区切りの一部
            if (pe > ps && body[pe - 1] == '\n'.code.toByte()) pe--
            if (pe > ps && body[pe - 1] == '\r'.code.toByte()) pe--
            if (ps > pe) continue
            val raw = split(body, ps, pe) ?: continue
            val pct = raw.headers["content-type"]?.lowercase() ?: ""
            if (pct.startsWith("multipart/") && depth < 4) collect(raw, out, depth + 1) else single(raw)?.let { out.add(it) }
        }
    }
}
