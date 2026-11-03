package local.filemanager

import local.filemanager.Plist.dict
import local.filemanager.Plist.items
import local.filemanager.Plist.str

/** Snappy の圧縮データ(枠なしの生の形)の展開。iWork の .iwa が使う。 */
object Snappy {
    fun decompress(src: ByteArray, from: Int = 0, len: Int = src.size - from): ByteArray {
        val end = from + len
        var p = from
        // 展開後の大きさ(可変長の整数)
        var size = 0L
        var shift = 0
        while (true) {
            if (p >= end || shift > 35) throw OpException("Snappyの先頭が読めません")
            val b = src[p++].toInt() and 0xFF
            size = size or ((b and 0x7F).toLong() shl shift)
            if (b < 0x80) break
            shift += 7
        }
        if (size > 512L * 1024 * 1024) throw OpException("展開後が大きすぎます")
        val out = ByteArray(size.toInt())
        var o = 0
        while (p < end && o < out.size) {
            val tag = src[p++].toInt() and 0xFF
            when (tag and 3) {
                0 -> {
                    var n = (tag shr 2) + 1
                    if (n > 60) {
                        val extra = n - 60
                        if (p + extra > end) throw OpException("Snappyの長さが足りません")
                        n = 0
                        for (i in 0 until extra) n = n or ((src[p + i].toInt() and 0xFF) shl (8 * i))
                        n += 1
                        p += extra
                    }
                    if (p + n > end || o + n > out.size) throw OpException("Snappyの文字が足りません")
                    System.arraycopy(src, p, out, o, n)
                    p += n
                    o += n
                }
                else -> {
                    val n: Int
                    val off: Int
                    when (tag and 3) {
                        1 -> {
                            if (p >= end) throw OpException("Snappyが途中で切れています")
                            n = 4 + ((tag shr 2) and 7)
                            off = ((tag shr 5) shl 8) or (src[p++].toInt() and 0xFF)
                        }
                        2 -> {
                            if (p + 2 > end) throw OpException("Snappyが途中で切れています")
                            n = 1 + (tag shr 2)
                            off = (src[p].toInt() and 0xFF) or ((src[p + 1].toInt() and 0xFF) shl 8)
                            p += 2
                        }
                        else -> {
                            if (p + 4 > end) throw OpException("Snappyが途中で切れています")
                            n = 1 + (tag shr 2)
                            off = Ole2.le32(src, p)
                            p += 4
                        }
                    }
                    if (off <= 0 || off > o || o + n > out.size) throw OpException("Snappyの参照が壊れています")
                    for (i in 0 until n) { out[o] = out[o - off]; o++ } // 重なる参照があるので 1 バイトずつ
                }
            }
        }
        if (o != out.size) throw OpException("Snappyの展開後の大きさが合いません")
        return out
    }
}

/** iWork(Pages・Numbers・Keynote)の中身。 */
class IworkInfo(
    /** 「Pages」「Numbers」「Keynote」 */
    val app: String,
    /** 古い形式(iWork '09 以前。index.xml)か */
    val legacy: Boolean,
    /** プレビュー画像の zip の中の名前(無ければ null) */
    val preview: String?,
    /** 全文の PDF(古い形式の QuickLook/Preview.pdf) */
    val pdf: String?,
    /** 「Template: …」「M14.0-…」など、作成・保存したアプリの版の履歴 */
    val versions: List<String>,
    val media: List<Pair<String, Long>>,
    val texts: List<String>,
    val textNote: String?,
)

object Iwork {
    private val PREVIEWS = listOf("preview.jpg", "preview-web.jpg", "QuickLook/Thumbnail.jpg", "preview-micro.jpg", "QuickLook/Thumbnail.png")

    fun appOf(name: String): String = when (FileOps.extensionOf(name)) {
        "pages" -> "Pages"
        "numbers" -> "Numbers"
        "key", "keynote" -> "Keynote"
        else -> "iWork"
    }

    fun parse(fileName: String, names: Collection<String>, read: (String) -> ByteArray?): IworkInfo {
        val lower = names.associateBy { it.lowercase() }
        val iwa = names.filter { it.lowercase().startsWith("index/") && it.lowercase().endsWith(".iwa") }
        val legacy = iwa.isEmpty() && lower.keys.any { it == "index.xml" || it == "index.apxl" }
        if (iwa.isEmpty() && !legacy && PREVIEWS.none { lower.containsKey(it.lowercase()) }) throw OpException("iWorkの書類として読めません(Index/*.iwa も index.xml もプレビューもありません)")
        val preview = PREVIEWS.firstNotNullOfOrNull { lower[it.lowercase()] }
        val pdf = lower["quicklook/preview.pdf"]
        val versions = ArrayList<String>()
        lower["metadata/buildversionhistory.plist"]?.let { n ->
            read(n)?.let { b ->
                runCatching { Plist.parse(b) }.getOrNull()?.let { v -> if (v is PValue.Arr) v.items.forEach { (it as? PValue.Str)?.let { s -> versions.add(s.value) } } }
            }
        }
        lower["metadata/properties.plist"]?.let { n ->
            read(n)?.let { b ->
                runCatching { Plist.parse(b) }.getOrNull()?.let { p ->
                    p.str("fileFormatVersion")?.let { versions.add("ファイル形式の版: $it") }
                }
            }
        }
        val media = names.filter { it.startsWith("Data/") && !it.endsWith("/") }.mapNotNull { n -> read(n)?.let { n to it.size.toLong() } }
        var note: String? = null
        val texts = ArrayList<String>()
        try {
            if (legacy) {
                val xml = read(lower["index.xml"] ?: lower["index.apxl"]!!)
                if (xml != null && xml.size <= 24 * 1024 * 1024) texts.addAll(legacyTexts(xml))
                else note = "本文のXMLが大きすぎて、文字は取り出していません"
            } else if (iwa.isNotEmpty()) {
                texts.addAll(Iwa.texts(iwa.sorted().mapNotNull { n -> read(n)?.let { n to it } }))
            }
        } catch (e: Exception) {
            note = "文字を取り出せなかった(${e.message ?: e.javaClass.simpleName})"
        }
        if (texts.isEmpty() && note == null) note = "文字は見つかりませんでした"
        return IworkInfo(appOf(fileName), legacy, preview, pdf, versions, media, texts, note)
    }

    /** iWork '09 以前の index.xml から、段落(sf:p)とセル(sf:t)の文字を拾う。 */
    internal fun legacyTexts(xml: ByteArray): List<String> {
        val text = String(xml, Charsets.UTF_8)
        val out = ArrayList<String>()
        val re = Regex("<sf:(?:p|t)\\b[^>]*>(.*?)</sf:(?:p|t)>", RegexOption.DOT_MATCHES_ALL)
        for (m in re.findAll(text)) {
            val inner = m.groupValues[1].replace(Regex("<[^>]*>"), "")
            val s = unescapeXml(inner).trim()
            if (s.isNotEmpty()) out.add(s)
            if (out.size >= 20_000) break
        }
        return out
    }

    private fun unescapeXml(s: String): String = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}

/** iWork '13 以降の .iwa(Snappy で包んだ protobuf の並び)から、文字を拾う。 */
object Iwa {
    class Msg(val type: Int, val payload: ByteArray)

    /** .iwa 1 つを、ArchiveInfo と中身の組に分ける。 */
    fun messages(file: ByteArray): List<Msg> {
        // 枠: 0 を 1 バイト + 圧縮データの長さ(3 バイト)+ Snappy
        val data = java.io.ByteArrayOutputStream()
        var p = 0
        while (p + 4 <= file.size) {
            if (file[p].toInt() != 0) break
            val n = (file[p + 1].toInt() and 0xFF) or ((file[p + 2].toInt() and 0xFF) shl 8) or ((file[p + 3].toInt() and 0xFF) shl 16)
            p += 4
            if (n < 0 || p + n > file.size) break
            val chunk = Snappy.decompress(file, p, n)
            data.write(chunk, 0, chunk.size)
            p += n
            if (data.size() > 256 * 1024 * 1024) break
        }
        val b = data.toByteArray()
        val out = ArrayList<Msg>()
        val r = Pb(b)
        while (!r.atEnd()) {
            val infoLen = r.varint().toInt()
            if (infoLen <= 0 || r.pos + infoLen > b.size) break
            val info = Pb(b, r.pos, r.pos + infoLen)
            r.pos += infoLen
            val infos = ArrayList<Pair<Int, Int>>() // type, length
            while (!info.atEnd()) {
                val tag = info.varint()
                val field = (tag shr 3).toInt()
                val wire = (tag and 7).toInt()
                if (field == 2 && wire == 2) {
                    val l = info.varint().toInt()
                    val mi = Pb(b, info.pos, info.pos + l)
                    info.pos += l
                    var type = 0
                    var length = 0
                    while (!mi.atEnd()) {
                        val t = mi.varint()
                        val f = (t shr 3).toInt()
                        when ((t and 7).toInt()) {
                            0 -> { val v = mi.varint(); if (f == 1) type = v.toInt() else if (f == 3) length = v.toInt() }
                            2 -> mi.pos += mi.varint().toInt()
                            1 -> mi.pos += 8
                            5 -> mi.pos += 4
                            else -> mi.pos = mi.end
                        }
                    }
                    infos.add(type to length)
                } else {
                    info.skip(wire)
                }
            }
            for ((type, len) in infos) {
                if (len < 0 || r.pos + len > b.size) return out
                out.add(Msg(type, b.copyOfRange(r.pos, r.pos + len)))
                r.pos += len
            }
        }
        return out
    }

    /** TSWP.StorageArchive(本文・図形の中の文字。type 2001 の field 3)と、TST.TableDataList(Numbers の表の文字。type 6005)の文字列。 */
    fun texts(files: List<Pair<String, ByteArray>>): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        fun add(s: String) {
            for (line in s.split('\n', '\r', ' ', ' ')) {
                val t = line.replace("￼", "").trim()
                if (t.isNotEmpty() && out.size < 50_000 && (t.length > 1 || seen.add(t))) out.add(t)
            }
        }
        for ((_, bytes) in files) {
            val msgs = try { messages(bytes) } catch (e: OpException) { continue }
            for (m in msgs) {
                when (m.type) {
                    2001 -> strings(m.payload, 3).forEach { add(it) }
                    6005 -> {
                        // TableDataList: entries(field 3)の中の string(field 3)
                        val r = Pb(m.payload)
                        while (!r.atEnd()) {
                            val tag = r.varint()
                            if ((tag shr 3).toInt() == 3 && (tag and 7).toInt() == 2) {
                                val l = r.varint().toInt()
                                val sub = m.payload.copyOfRange(r.pos, minOf(m.payload.size, r.pos + l))
                                r.pos += l
                                strings(sub, 3).forEach { add(it) }
                            } else r.skip((tag and 7).toInt())
                        }
                    }
                }
            }
        }
        return out
    }

    /** payload の最上位で、番号 field の文字列(長さ付き)を全部返す。UTF-8 として読めるものだけ。 */
    private fun strings(payload: ByteArray, field: Int): List<String> {
        val out = ArrayList<String>()
        val r = Pb(payload)
        while (!r.atEnd()) {
            val tag = r.varint()
            val wire = (tag and 7).toInt()
            if ((tag shr 3).toInt() == field && wire == 2) {
                val l = r.varint().toInt()
                if (l < 0 || r.pos + l > payload.size) break
                out.add(String(payload, r.pos, l, Charsets.UTF_8))
                r.pos += l
            } else r.skip(wire)
        }
        return out
    }

    internal class Pb(val b: ByteArray, var pos: Int = 0, val end: Int = b.size) {
        fun atEnd() = pos >= end

        fun varint(): Long {
            var v = 0L
            var shift = 0
            while (pos < end && shift < 64) {
                val x = b[pos++].toInt() and 0xFF
                v = v or ((x and 0x7F).toLong() shl shift)
                if (x < 0x80) return v
                shift += 7
            }
            pos = end
            return v
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> pos += varint().toInt().coerceAtLeast(0)
                5 -> pos += 4
                else -> pos = end
            }
            if (pos > end) pos = end
        }
    }
}

// ---------------------------------------------------------------- .ipa(iPhone アプリ)

class IpaInfo(
    val name: String?,
    val bundleId: String?,
    val version: String?,
    val build: String?,
    val minOs: String?,
    val devices: List<String>,
    val permissions: List<Pair<String, String>>,
    val backgroundModes: List<String>,
    val urlSchemes: List<String>,
    val executable: String?,
    val provision: String?,
    val appDir: String,
    val signed: Boolean,
)

object Ipa {
    private val PERMISSION_NAMES = mapOf(
        "NSCameraUsageDescription" to "カメラ", "NSMicrophoneUsageDescription" to "マイク", "NSPhotoLibraryUsageDescription" to "写真",
        "NSPhotoLibraryAddUsageDescription" to "写真の追加", "NSLocationWhenInUseUsageDescription" to "位置情報(使用中)",
        "NSLocationAlwaysUsageDescription" to "位置情報(常に)", "NSLocationAlwaysAndWhenInUseUsageDescription" to "位置情報(常に・使用中)",
        "NSContactsUsageDescription" to "連絡先", "NSCalendarsUsageDescription" to "カレンダー", "NSRemindersUsageDescription" to "リマインダー",
        "NSBluetoothAlwaysUsageDescription" to "Bluetooth", "NSBluetoothPeripheralUsageDescription" to "Bluetooth(周辺機器)",
        "NSFaceIDUsageDescription" to "Face ID", "NSMotionUsageDescription" to "モーション", "NSHealthShareUsageDescription" to "ヘルスケア(読み取り)",
        "NSHealthUpdateUsageDescription" to "ヘルスケア(書き込み)", "NSSpeechRecognitionUsageDescription" to "音声認識",
        "NSLocalNetworkUsageDescription" to "ローカルネットワーク", "NSUserTrackingUsageDescription" to "トラッキング",
        "NSAppleMusicUsageDescription" to "ミュージック", "NSSiriUsageDescription" to "Siri", "NSHomeKitUsageDescription" to "HomeKit",
    )

    fun parse(names: Collection<String>, read: (String) -> ByteArray?): IpaInfo {
        val plistName = names.filter { Regex("^Payload/[^/]+\\.app/Info\\.plist$").matches(it) }.minOrNull()
            ?: throw OpException("iPhoneアプリ(Payload/〜.app/Info.plist)が見つかりません")
        val appDir = plistName.removeSuffix("/Info.plist")
        val p = Plist.parse(read(plistName) ?: throw OpException("Info.plist を読めません"))
        fun s(k: String) = p.str(k)
        val devices = p.items("UIDeviceFamily").mapNotNull { (it as? PValue.Int)?.value }.map { when (it) { 1L -> "iPhone"; 2L -> "iPad"; 3L -> "Apple TV"; 4L -> "Apple Watch"; else -> "種類$it" } }
        val perms = (p as? PValue.Dict)?.map?.entries?.filter { it.key.startsWith("NS") && it.key.endsWith("UsageDescription") }
            ?.map { (PERMISSION_NAMES[it.key] ?: it.key.removePrefix("NS").removeSuffix("UsageDescription")) to ((it.value as? PValue.Str)?.value ?: "") } ?: emptyList()
        val schemes = p.items("CFBundleURLTypes").flatMap { t -> t.items("CFBundleURLSchemes").mapNotNull { (it as? PValue.Str)?.value } }
        val modes = p.items("UIBackgroundModes").mapNotNull { (it as? PValue.Str)?.value }
        val provision = names.firstOrNull { it == "$appDir/embedded.mobileprovision" }?.let { n ->
            read(n)?.let { b -> Plist.embeddedXml(b)?.let { x -> runCatching { Plist.parse(x.toByteArray()) }.getOrNull() } }
        }?.let { pr ->
            listOfNotNull(pr.str("Name"), pr.str("TeamName")?.let { "チーム: $it" }, (pr.dict("ExpirationDate") as? PValue.Date)?.let { "期限: ${it.iso.substringBefore('T')}" }).joinToString(" / ")
        }
        return IpaInfo(
            s("CFBundleDisplayName") ?: s("CFBundleName"), s("CFBundleIdentifier"), s("CFBundleShortVersionString"), s("CFBundleVersion"),
            s("MinimumOSVersion"), devices, perms, modes, schemes, s("CFBundleExecutable"), provision, appDir,
            names.any { it.startsWith("$appDir/_CodeSignature/") },
        )
    }
}
