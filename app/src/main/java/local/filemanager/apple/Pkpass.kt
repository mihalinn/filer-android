package local.filemanager

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** Apple Wallet のパス(.pkpass。zip の中の pass.json と画像)の読み取り。Android に依存しない。 */
class PassField(val key: String, val label: String?, val value: String)

class PassBarcode(val format: String, val message: String, val altText: String?) {
    /** 「QR コード」「PDF417」など、人に見せる名前 */
    val formatName: String
        get() = when {
            format.contains("QR", ignoreCase = true) -> "QRコード"
            format.contains("PDF417", ignoreCase = true) -> "PDF417"
            format.contains("Aztec", ignoreCase = true) -> "Aztecコード"
            format.contains("Code128", ignoreCase = true) -> "Code 128"
            else -> format
        }
}

class Pass(
    /** eventTicket / boardingPass / coupon / storeCard / generic */
    val style: String,
    /** 搭乗券の乗り物(PKTransitTypeAir など) */
    val transit: String?,
    val organization: String?,
    val description: String?,
    val logoText: String?,
    val serial: String?,
    val bg: Int?,
    val fg: Int?,
    val labelColor: Int?,
    val header: List<PassField>,
    val primary: List<PassField>,
    val secondary: List<PassField>,
    val auxiliary: List<PassField>,
    val back: List<PassField>,
    val barcode: PassBarcode?,
    val expiration: String?,
    val relevantDate: String?,
    val voided: Boolean,
    val locations: List<String>,
    /** 画像の種類(icon・logo・strip・thumbnail・background・footer)→ zip の中の名前(@3x → @2x → 無印の順で最も大きいもの) */
    val images: Map<String, String>,
) {
    val styleName: String
        get() = when (style) {
            "eventTicket" -> "チケット"
            "boardingPass" -> "搭乗券・乗車券"
            "coupon" -> "クーポン"
            "storeCard" -> "ストアカード"
            else -> "パス"
        }

    val fieldCount: Int get() = header.size + primary.size + secondary.size + auxiliary.size + back.size
}

object Pkpass {
    private val STYLES = listOf("boardingPass", "eventTicket", "coupon", "storeCard", "generic")
    private val IMAGE_KINDS = listOf("icon", "logo", "strip", "thumbnail", "background", "footer")

    /** 「rgb(255, 128, 0)」→ ARGB。 */
    fun color(s: String?): Int? {
        val m = Regex("rgba?\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)").find(s ?: return null) ?: return null
        val (r, g, b) = m.destructured
        return (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
    }

    /** pass.strings(「"key" = "value";」の並び。UTF-16 か UTF-8)を読む。 */
    fun strings(bytes: ByteArray): Map<String, String> {
        val text = TextDecode.decode(bytes, false).text.removePrefix("\uFEFF")
        val out = HashMap<String, String>()
        val re = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*;")
        for (m in re.findAll(text)) out[unescape(m.groupValues[1])] = unescape(m.groupValues[2])
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
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'U', 'u' -> {
                        val hex = s.substring(i + 2, minOf(s.length, i + 6))
                        val v = hex.toIntOrNull(16)
                        if (v != null && hex.length == 4) { sb.append(v.toChar()); i += 4 } else sb.append(n)
                    }
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

    /** 日付(ISO 8601)を「2026/11/03 19:00」の形に。読めなければそのまま。 */
    fun dateText(s: String): String {
        val out = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
        return try {
            when {
                s.length <= 10 -> s.replace('-', '/')
                s.endsWith("Z") || Regex("[+-]\\d\\d:?\\d\\d$").containsMatchIn(s) -> {
                    val fixed = if (Regex("[+-]\\d\\d\\d\\d$").containsMatchIn(s)) s.substring(0, s.length - 2) + ":" + s.substring(s.length - 2) else s
                    // 秒が無い「2026-11-03T19:00-08:00」も読めるように、日時と時差の部分に分ける
                    val m = Regex("^(\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d)(:\\d\\d)?(\\.\\d+)?(Z|[+-]\\d\\d:\\d\\d)$").find(fixed)
                    if (m != null) {
                        val dt = LocalDateTime.parse(m.groupValues[1] + (m.groupValues[2].ifEmpty { ":00" }))
                        dt.format(out) + (if (m.groupValues[4] == "Z") " UTC" else "")
                    } else OffsetDateTime.parse(fixed).format(out)
                }
                else -> LocalDateTime.parse(s).format(out)
            }
        } catch (e: Exception) {
            s
        }
    }

    private fun value(f: Map<String, Any?>, tr: Map<String, String>): String {
        val raw = f["value"]
        val text = Json.str(raw) ?: ""
        val s = tr[text] ?: text
        val cur = Json.str(f["currencyCode"])
        return when {
            cur != null && raw is Number -> "$cur ${Json.str(raw)}"
            f["dateStyle"] != null || f["timeStyle"] != null -> if (s.contains('T') || Regex("^\\d{4}-\\d\\d-\\d\\d").containsMatchIn(s)) dateText(s) else s
            else -> s
        }
    }

    private fun fields(a: Any?, tr: Map<String, String>): List<PassField> = Json.arr(a).mapNotNull { f ->
        val m = Json.obj(f) ?: return@mapNotNull null
        val label = Json.str(m["label"])?.let { tr[it] ?: it }?.takeIf { it.isNotEmpty() }
        PassField(Json.str(m["key"]) ?: "", label, value(m, tr))
    }

    /**
     * names: zip の中の名前の一覧。read: 名前 → 中身。locales: 優先する言語(「ja」「en」)。
     */
    fun parse(names: Collection<String>, read: (String) -> ByteArray?, locales: List<String> = listOf("ja", "en")): Pass {
        val bytes = read("pass.json") ?: throw OpException("パス(pass.json)が見つかりません")
        val root = Json.obj(Json.parse(String(bytes, Charsets.UTF_8))) ?: throw OpException("pass.json の形が違う")
        // 言語ごとの文字(ja.lproj/pass.strings など)
        var tr: Map<String, String> = emptyMap()
        for (loc in locales) {
            val n = names.firstOrNull { it.equals("$loc.lproj/pass.strings", ignoreCase = true) }
            if (n != null) { tr = read(n)?.let { runCatching { strings(it) }.getOrNull() } ?: emptyMap(); break }
        }
        val style = STYLES.firstOrNull { root.containsKey(it) } ?: "generic"
        val body = Json.obj(root[style]) ?: emptyMap()
        val barcodeSrc = Json.arr(root["barcodes"]).firstNotNullOfOrNull { Json.obj(it) } ?: Json.obj(root["barcode"])
        val barcode = barcodeSrc?.let { b ->
            val msg = Json.str(b["message"])
            if (msg == null) null else PassBarcode(Json.str(b["format"]) ?: "", msg, Json.str(b["altText"]))
        }
        val locs = Json.arr(root["locations"]).mapNotNull { Json.obj(it) }.map { l ->
            (Json.str(l["relevantText"])?.let { tr[it] ?: it } ?: "") + " (${Json.str(l["latitude"])}, ${Json.str(l["longitude"])})"
        }
        // 画像: 同じ種類の @3x → @2x → 無印 の順に選ぶ。
        val images = LinkedHashMap<String, String>()
        for (kind in IMAGE_KINDS) {
            val pick = listOf("$kind@3x.png", "$kind@2x.png", "$kind.png").firstOrNull { c -> names.any { it == c } }
            if (pick != null) images[kind] = pick
        }
        return Pass(
            style = style,
            transit = Json.str(body["transitType"]),
            organization = Json.str(root["organizationName"])?.let { tr[it] ?: it },
            description = Json.str(root["description"])?.let { tr[it] ?: it },
            logoText = Json.str(root["logoText"])?.let { tr[it] ?: it },
            serial = Json.str(root["serialNumber"]),
            bg = color(Json.str(root["backgroundColor"])),
            fg = color(Json.str(root["foregroundColor"])),
            labelColor = color(Json.str(root["labelColor"])),
            header = fields(body["headerFields"], tr),
            primary = fields(body["primaryFields"], tr),
            secondary = fields(body["secondaryFields"], tr),
            auxiliary = fields(body["auxiliaryFields"], tr),
            back = fields(body["backFields"], tr),
            barcode = barcode,
            expiration = Json.str(root["expirationDate"])?.let { dateText(it) },
            relevantDate = Json.str(root["relevantDate"])?.let { dateText(it) },
            voided = root["voided"] == true,
            locations = locs,
            images = images,
        )
    }
}
