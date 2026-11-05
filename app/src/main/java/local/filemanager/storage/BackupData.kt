package local.filemanager

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/** バックアップに入れる中身。prefs は設定(名前と値。型は BackupCodec.SCHEMA で決まっている)、remotes は NAS の接続先(パスワード込み)。 */
class BackupData(val prefs: Map<String, Any>, val remotes: List<RemoteConfig>)

/** 読み込む前に見せる、中身の件数。 */
class BackupSummary(val settings: Int, val bookmarks: Int, val pinned: Int, val history: Int, val remotes: Int)

/**
 * バックアップの中身を、独自のバイナリ形式にする(JSON などの読める形ではない)。Android に依存しない。
 * ここは暗号化の前後の形だけ。暗号化は BackupFile。
 *
 * 書き出せる設定の名前と型は SCHEMA に決まっている。読み込むときも、SCHEMA に無いものは捨て、型や値が合わないものは使わない
 * (細工されたファイルから、意図しない設定を書き込まれないように)。
 */
object BackupCodec {
    enum class T(val tag: Int) { BOOL(1), INT(2), STR(3) }

    /** 画面と動作の設定 */
    val SETTINGS: Map<String, T> = mapOf(
        "sortKey" to T.STR, "sortAsc" to T.BOOL, "showHidden" to T.BOOL, "grid" to T.BOOL, "nasThumbs" to T.BOOL,
        "theme" to T.STR, "themeColor" to T.STR, "viewerTheme" to T.STR, "viewerFontSp" to T.INT, "comicRtl" to T.BOOL,
        "lang" to T.STR,
        "homeOrder" to T.STR, "homeHidden" to T.STR, "homeCols" to T.INT, "homeShortcuts" to T.STR,
        "updAuto" to T.BOOL, "updBanner" to T.BOOL,
    )

    /** 場所の一覧(1 行に「場所の種類 タブ パス」) */
    val PLACES = listOf("bookmarks", "pinned")

    /** 履歴(入れるかどうかを、書き出すときに選ぶ) */
    val HISTORY = listOf("history", "opened")

    val SCHEMA: Map<String, T> = SETTINGS + (PLACES + HISTORY).associateWith { T.STR }

    /** 決まった言葉しか入らない設定の、使える値。これ以外は読み込まない */
    private val ALLOWED: Map<String, Set<String>> = mapOf(
        "theme" to setOf("light", "dark", "system"),
        "viewerTheme" to setOf("dark", "light", "app"),
        "themeColor" to ThemeColor.entries.map { it.id }.toSet(),
        "sortKey" to SortKey.entries.map { it.name }.toSet(),
        "lang" to setOf("") + Languages.supported.map { it.tag },
    )

    private const val MAGIC = 0x46424431 // "FBD1"
    private const val MAX_STR = 1024 * 1024
    private const val MAX_PLAIN = 4 * 1024 * 1024
    private const val MAX_PREFS = 200
    private const val MAX_REMOTES = 500

    fun encode(d: BackupData): ByteArray {
        val raw = ByteArrayOutputStream()
        DataOutputStream(raw).use { o ->
            o.writeInt(MAGIC)
            val entries = d.prefs.filter { (k, v) -> SCHEMA[k]?.let { fits(it, v) } == true }
            o.writeInt(entries.size)
            for ((k, v) in entries) {
                str(o, k)
                when (v) {
                    is Boolean -> { o.writeByte(T.BOOL.tag); o.writeBoolean(v) }
                    is Int -> { o.writeByte(T.INT.tag); o.writeInt(v) }
                    else -> { o.writeByte(T.STR.tag); str(o, v as String) }
                }
            }
            o.writeInt(d.remotes.size)
            for (r in d.remotes) {
                str(o, r.id); str(o, r.name); str(o, r.host); o.writeInt(r.port); str(o, r.share)
                str(o, r.startPath); str(o, r.user); str(o, r.password); str(o, r.domain)
            }
        }
        val packed = ByteArrayOutputStream()
        DeflaterOutputStream(packed, Deflater(Deflater.BEST_COMPRESSION)).use { it.write(raw.toByteArray()) }
        return packed.toByteArray()
    }

    /** 読めない・形が違うときは null。 */
    fun decode(bytes: ByteArray): BackupData? {
        val raw = try {
            val out = ByteArrayOutputStream()
            InflaterInputStream(ByteArrayInputStream(bytes)).use { i ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_PLAIN) return null // 小さなファイルが巨大に膨らむ細工を弾く
                }
            }
            out.toByteArray()
        } catch (e: Exception) {
            return null
        }
        return try {
            val i = DataInputStream(ByteArrayInputStream(raw))
            if (i.readInt() != MAGIC) return null
            val n = i.readInt()
            if (n !in 0..MAX_PREFS) return null
            val prefs = LinkedHashMap<String, Any>()
            repeat(n) {
                val key = readStr(i)
                val value: Any = when (i.readUnsignedByte()) {
                    T.BOOL.tag -> i.readBoolean()
                    T.INT.tag -> i.readInt()
                    T.STR.tag -> readStr(i)
                    else -> return null
                }
                val type = SCHEMA[key]
                if (type != null && fits(type, value) && valueOk(key, value)) prefs[key] = value // 知らない名前・合わない型は捨てる
            }
            val m = i.readInt()
            if (m !in 0..MAX_REMOTES) return null
            val remotes = ArrayList<RemoteConfig>()
            repeat(m) {
                val id = readStr(i); val name = readStr(i); val host = readStr(i); val port = i.readInt(); val share = readStr(i)
                val start = readStr(i); val user = readStr(i); val pw = readStr(i); val domain = readStr(i)
                if (id.isNotBlank() && id.length <= 100 && host.isNotBlank() && host.length <= 255 && port in 1..65535 && share.length <= 255) {
                    remotes.add(RemoteConfig(id, name.take(200), host, port, share, start, user, pw, domain))
                }
            }
            BackupData(prefs, remotes)
        } catch (e: Exception) {
            null
        }
    }

    fun summarize(d: BackupData): BackupSummary {
        fun lines(k: String) = (d.prefs[k] as? String).orEmpty().split('\n').count { it.isNotBlank() }
        return BackupSummary(
            settings = d.prefs.keys.count { it in SETTINGS },
            bookmarks = lines("bookmarks"), pinned = lines("pinned"), history = lines("history") + lines("opened"),
            remotes = d.remotes.size,
        )
    }

    /** 場所の一覧を足し合わせる(いまのものを先に、バックアップにしかないものを後ろに。同じ行は 1 つ)。 */
    fun mergeLines(current: String, incoming: String): String =
        (current.split('\n') + incoming.split('\n')).filter { it.isNotBlank() }.distinct().joinToString("\n")

    private fun fits(t: T, v: Any) = when (t) {
        T.BOOL -> v is Boolean
        T.INT -> v is Int
        T.STR -> v is String
    }

    private fun valueOk(key: String, v: Any): Boolean {
        ALLOWED[key]?.let { return v in it }
        if (v is Int) return v in 0..10_000
        return true
    }

    private fun str(o: DataOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        o.writeInt(b.size)
        o.write(b)
    }

    private fun readStr(i: DataInputStream): String {
        val n = i.readInt()
        if (n !in 0..MAX_STR) throw java.io.IOException("bad length")
        val b = ByteArray(n)
        i.readFully(b)
        return String(b, Charsets.UTF_8)
    }
}
