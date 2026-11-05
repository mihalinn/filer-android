package local.filemanager

import java.io.ByteArrayInputStream
import java.util.Base64

/** プロパティリスト(.plist。Apple のアプリや設定の形式)の値。 */
sealed interface PValue {
    class Dict(val map: LinkedHashMap<String, PValue>) : PValue
    class Arr(val items: List<PValue>) : PValue
    class Str(val value: String) : PValue
    class Int(val value: Long) : PValue
    class Real(val value: Double) : PValue
    class Bool(val value: Boolean) : PValue
    class Date(val iso: String) : PValue
    class Data(val bytes: ByteArray) : PValue
    class Uid(val value: Long) : PValue
}

/** plist の読み取り(バイナリ「bplist00」と XML の両方)と、XML の文字への書き出し。Android に依存しない。 */
object Plist {
    private const val MAX_OBJECTS = 2_000_000

    fun isBinary(b: ByteArray): Boolean = b.size > 8 && b[0] == 'b'.code.toByte() && b[1] == 'p'.code.toByte() && b[2] == 'l'.code.toByte() &&
        b[3] == 'i'.code.toByte() && b[4] == 's'.code.toByte() && b[5] == 't'.code.toByte()

    fun parse(b: ByteArray): PValue {
        if (isBinary(b)) return parseBinary(b)
        return parseXml(b)
    }

    // ---------------------------------------------------------------- バイナリ

    private fun parseBinary(b: ByteArray): PValue {
        if (b.size < 40) throw OpException(Txt(R.string.plist_001))
        val t = b.size - 32
        val offSize = b[t + 6].toInt() and 0xFF
        val refSize = b[t + 7].toInt() and 0xFF
        val n = be(b, t + 8, 8)
        val top = be(b, t + 16, 8)
        val tableOff = be(b, t + 24, 8)
        if (offSize !in 1..8 || refSize !in 1..8 || n <= 0 || n > MAX_OBJECTS || tableOff < 8 || tableOff + n * offSize > b.size || top >= n) {
            throw OpException(Txt(R.string.plist_002))
        }
        val offsets = LongArray(n.toInt()) { be(b, (tableOff + it.toLong() * offSize).toInt(), offSize) }
        var visited = 0
        fun obj(idx: Long, depth: Int): PValue {
            if (idx < 0 || idx >= n || depth > 64 || ++visited > MAX_OBJECTS) throw OpException(Txt(R.string.plist_003))
            var p = offsets[idx.toInt()].toInt()
            if (p < 8 || p >= t) throw OpException(Txt(R.string.plist_004))
            val marker = b[p].toInt() and 0xFF
            val type = marker shr 4
            val info = marker and 0xF
            p++
            fun count(): Int {
                if (info != 0xF) return info
                val m = b[p].toInt() and 0xFF
                val size = 1 shl (m and 0xF)
                val v = be(b, p + 1, size)
                p += 1 + size
                return v.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            }
            return when (type) {
                0x0 -> when (info) { 0x8 -> PValue.Bool(false); 0x9 -> PValue.Bool(true); else -> PValue.Str("") }
                0x1 -> { val size = 1 shl info; PValue.Int(if (size == 8) beSigned(b, p) else be(b, p, minOf(size, 8))) }
                0x2 -> { val size = 1 shl info; PValue.Real(if (size == 4) java.lang.Float.intBitsToFloat(be(b, p, 4).toInt()).toDouble() else java.lang.Double.longBitsToDouble(beSigned(b, p))) }
                0x3 -> PValue.Date(appleDate(java.lang.Double.longBitsToDouble(beSigned(b, p))))
                0x4 -> { val c = count(); if (p + c > t) throw OpException(Txt(R.string.plist_005)); PValue.Data(b.copyOfRange(p, p + c)) }
                0x5 -> { val c = count(); if (p + c > t) throw OpException(Txt(R.string.plist_006)); PValue.Str(String(b, p, c, Charsets.ISO_8859_1)) }
                0x6 -> { val c = count(); if (p + c * 2 > t) throw OpException(Txt(R.string.plist_007)); PValue.Str(String(b, p, c * 2, Charsets.UTF_16BE)) }
                0x8 -> PValue.Uid(be(b, p, (info + 1).coerceAtMost(8)))
                0xA, 0xC -> {
                    val c = count()
                    if (p + c.toLong() * refSize > t) throw OpException(Txt(R.string.plist_008))
                    PValue.Arr(List(c) { obj(be(b, p + it * refSize, refSize), depth + 1) })
                }
                0xD -> {
                    val c = count()
                    if (p + c.toLong() * refSize * 2 > t) throw OpException(Txt(R.string.plist_009))
                    val m = LinkedHashMap<String, PValue>()
                    for (i in 0 until c) {
                        val k = obj(be(b, p + i * refSize, refSize), depth + 1)
                        val v = obj(be(b, p + (c + i) * refSize, refSize), depth + 1)
                        m[(k as? PValue.Str)?.value ?: key(k)] = v
                    }
                    PValue.Dict(m)
                }
                else -> throw OpException(Txt(R.string.plist_010, type.toString(16)))
            }
        }
        return obj(top, 0)
    }

    private fun key(v: PValue): String = when (v) {
        is PValue.Int -> v.value.toString()
        is PValue.Real -> v.value.toString()
        is PValue.Uid -> "uid:${v.value}"
        else -> "?"
    }

    private fun be(b: ByteArray, o: Int, size: Int): Long {
        var v = 0L
        for (i in 0 until size) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }

    private fun beSigned(b: ByteArray, o: Int): Long = be(b, o, 8)

    private fun appleDate(seconds: Double): String {
        if (seconds.isNaN() || seconds.isInfinite()) return "?"
        val epoch = 978307200L // 2001-01-01T00:00:00Z
        return java.time.Instant.ofEpochSecond(epoch + Math.floor(seconds).toLong()).toString()
    }

    // ---------------------------------------------------------------- XML

    private fun parseXml(b0: ByteArray): PValue {
        // 先頭の空白・BOM を除き、DOCTYPE(外部の DTD の参照)は読まない
        var text = TextDecode.decode(b0, false).text.removePrefix("﻿")
        text = Regex("(?is)<!DOCTYPE[^>]*>").replace(text, "")
        val root = try {
            XmlTree.parse(text.toByteArray(Charsets.UTF_8))
        } catch (e: OpException) {
            throw e
        } catch (e: Exception) {
            throw OpException(Txt(R.string.plist_011))
        }
        val top = if (root.name == "plist") root.kids.firstOrNull() else root
        top ?: throw OpException(Txt(R.string.plist_012))
        return fromNode(top, 0)
    }

    private fun fromNode(n: XNode, depth: Int): PValue {
        if (depth > 64) throw OpException(Txt(R.string.plist_013))
        return when (n.name) {
            "dict" -> {
                val m = LinkedHashMap<String, PValue>()
                var k: String? = null
                for (c in n.kids) {
                    if (c.name == "key") { k = c.text.toString(); continue }
                    if (k != null) { m[k] = fromNode(c, depth + 1); k = null }
                }
                PValue.Dict(m)
            }
            "array" -> PValue.Arr(n.kids.map { fromNode(it, depth + 1) })
            "string" -> PValue.Str(n.text.toString())
            "integer" -> n.text.toString().trim().toLongOrNull()?.let { PValue.Int(it) } ?: PValue.Str(n.text.toString().trim())
            "real" -> n.text.toString().trim().toDoubleOrNull()?.let { PValue.Real(it) } ?: PValue.Str(n.text.toString().trim())
            "true" -> PValue.Bool(true)
            "false" -> PValue.Bool(false)
            "date" -> PValue.Date(n.text.toString().trim())
            "data" -> PValue.Data(runCatching { Base64.getMimeDecoder().decode(n.text.toString().trim()) }.getOrDefault(ByteArray(0)))
            else -> PValue.Str(n.text.toString())
        }
    }

    // ---------------------------------------------------------------- 書き出し(XML の文字)

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) when (c) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            else -> if (c.code >= 0x20 || c == '\t' || c == '\n' || c == '\r') sb.append(c)
        }
        return sb.toString()
    }

    /** 大きなデータは、全部は書かない(画面で重くなるため)。 */
    private const val DATA_SHOWN = 96

    fun toXml(v: PValue): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<plist version=\"1.0\">\n")
        write(sb, v, 0)
        sb.append("</plist>\n")
        return sb.toString()
    }

    private fun write(sb: StringBuilder, v: PValue, depth: Int) {
        val ind = "\t".repeat(depth)
        when (v) {
            is PValue.Dict -> {
                if (v.map.isEmpty()) { sb.append(ind).append("<dict/>\n"); return }
                sb.append(ind).append("<dict>\n")
                for ((k, x) in v.map) {
                    sb.append(ind).append("\t<key>").append(esc(k)).append("</key>\n")
                    write(sb, x, depth + 1)
                }
                sb.append(ind).append("</dict>\n")
            }
            is PValue.Arr -> {
                if (v.items.isEmpty()) { sb.append(ind).append("<array/>\n"); return }
                sb.append(ind).append("<array>\n")
                for (x in v.items) write(sb, x, depth + 1)
                sb.append(ind).append("</array>\n")
            }
            is PValue.Str -> sb.append(ind).append("<string>").append(esc(v.value)).append("</string>\n")
            is PValue.Int -> sb.append(ind).append("<integer>").append(v.value).append("</integer>\n")
            is PValue.Real -> sb.append(ind).append("<real>").append(v.value).append("</real>\n")
            is PValue.Bool -> sb.append(ind).append(if (v.value) "<true/>\n" else "<false/>\n")
            is PValue.Date -> sb.append(ind).append("<date>").append(esc(v.iso)).append("</date>\n")
            is PValue.Uid -> sb.append(ind).append("<dict><key>CF\$UID</key><integer>").append(v.value).append("</integer></dict>\n")
            is PValue.Data -> {
                val shown = v.bytes.copyOf(minOf(v.bytes.size, DATA_SHOWN))
                val b64 = Base64.getEncoder().encodeToString(shown)
                sb.append(ind).append("<data>").append(b64)
                if (v.bytes.size > DATA_SHOWN) sb.append(Txt(R.string.plist_014)).append(v.bytes.size).append(Txt(R.string.plist_015))
                sb.append("</data>\n")
            }
        }
    }

    /** .mobileprovision のように、署名(CMS)で包まれた plist から、中の XML plist を取り出す。 */
    fun embeddedXml(b: ByteArray): String? {
        val s = String(b, Charsets.ISO_8859_1)
        val a = s.indexOf("<?xml")
        val z = s.indexOf("</plist>", a)
        if (a < 0 || z < 0) return null
        return String(b, a, z + 8 - a, Charsets.UTF_8)
    }

    /** 表示用の文字。バイナリ plist・webarchive・署名つきの mobileprovision は XML の文字に直す。そのままの XML は null(呼び出し側がそのまま出す)。 */
    fun displayText(name: String, b: ByteArray): Pair<String, String>? {
        val ext = FileOps.extensionOf(name)
        if (isBinary(b)) return runCatching { toXml(parseBinary(b)) }.getOrNull()?.let { it to Txt(R.string.plist_016) }
        if (ext == "mobileprovision") return embeddedXml(b)?.let { it to Txt(R.string.plist_017) }
        if (b.size > 1 && b[0] == 0x30.toByte() && embeddedXml(b) != null) return embeddedXml(b)?.let { it to Txt(R.string.plist_018) }
        return null
    }

    // ---------------------------------------------------------------- 取り出しの補助

    fun PValue?.dict(key: String): PValue? = (this as? PValue.Dict)?.map?.get(key)
    fun PValue?.str(key: String): String? = (dict(key) as? PValue.Str)?.value
    fun PValue?.items(key: String): List<PValue> = (dict(key) as? PValue.Arr)?.items ?: emptyList()

    /** Safari の「ウェブアーカイブ」(.webarchive)を、MHTML と同じ形(MhtDoc)にする。 */
    fun webArchive(b: ByteArray): MhtDoc? {
        val root = parse(b)
        val main = root.dict("WebMainResource") ?: return null
        val parts = ArrayList<MhtPart>()
        fun add(res: PValue?) {
            res ?: return
            val data = (res.dict("WebResourceData") as? PValue.Data)?.bytes ?: return
            val mime = res.str("WebResourceMIMEType") ?: "application/octet-stream"
            parts.add(MhtPart(mime.lowercase(), res.str("WebResourceTextEncodingName"), res.str("WebResourceURL"), null, data))
        }
        add(main)
        for (r in root.items("WebSubresources")) add(r)
        // 入れ子の枠(iframe)の分も、画像などを引けるように足す
        fun frames(a: PValue?) {
            for (f in a.items("WebSubframeArchives")) {
                add(f.dict("WebMainResource"))
                for (r in f.items("WebSubresources")) add(r)
                frames(f)
            }
        }
        frames(root)
        val url = main.let { it.str("WebResourceURL") }
        return MhtDoc(null, null, url, parts)
    }

    @Suppress("unused")
    private fun stream(b: ByteArray) = ByteArrayInputStream(b)
}
