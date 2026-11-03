package local.filemanager

/** GitHub のリリース 1 つ分(最新のもの)。 */
class ReleaseInfo(
    val tag: String,
    /** 「v」を取った数字の並び(0.43.2 など) */
    val version: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    /** リリースの説明にある SHA-256(なければ null) */
    val sha256: String?,
)

/** 更新の確認の、ネットワークに依存しない部分。Android にも依存しないので PC の単体テストで確かめる。 */
object UpdateLogic {
    private val shaRegex = Regex("""SHA-?256[^0-9A-Fa-f]{0,20}([0-9A-Fa-f]{64})""", RegexOption.IGNORE_CASE)
    private val versionRegex = Regex("""^[vV]?(\d+(?:\.\d+)*)""")

    /** 「v0.43.2」「0.43.2-beta」などから、数字の並び [0, 43, 2] を取り出す。読めなければ null。 */
    fun versionParts(v: String): List<Int>? {
        val m = versionRegex.find(v.trim()) ?: return null
        val parts = m.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
        return parts
    }

    /** a が b より新しければ正、同じなら 0、古ければ負。どちらかが読めなければ null。 */
    fun compare(a: String, b: String): Int? {
        val x = versionParts(a) ?: return null
        val y = versionParts(b) ?: return null
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    fun isNewer(current: String, latest: String): Boolean = (compare(latest, current) ?: 0) > 0

    fun sha256Of(body: String): String? = shaRegex.find(body)?.groupValues?.get(1)?.lowercase()

    /** リリースの説明を、画面に出しやすい短い文字にする(見出しの記号や ` を外し、先頭から数行だけ)。 */
    fun shortNotes(body: String, maxLines: Int = 14): String {
        val lines = body.replace("\r\n", "\n").lines()
            .map { it.trim().trimStart('#').trim().replace("`", "") }
            .map { if (it.startsWith("- ") || it.startsWith("* ")) "・" + it.drop(2) else it }
            .dropWhile { it.isEmpty() }
        return lines.take(maxLines).joinToString("\n").trim()
    }

    /**
     * GitHub の「最新のリリース」の応答から、更新の情報を取り出す。
     * 下書き・先行公開・APK が無いもの・このリポジトリ以外を指す URL は無視する(null)。
     */
    fun parseLatest(json: String, repoUrl: String): ReleaseInfo? {
        val root = Json.obj(runCatching { Json.parse(json) }.getOrNull()) ?: return null
        if (root["draft"] == true || root["prerelease"] == true) return null
        val tag = Json.str(root["tag_name"]) ?: return null
        val version = versionParts(tag)?.joinToString(".") ?: return null
        val asset = Json.arr(root["assets"]).mapNotNull { Json.obj(it) }.firstOrNull { a ->
            val name = Json.str(a["name"]).orEmpty()
            val url = Json.str(a["browser_download_url"]).orEmpty()
            name.endsWith(".apk", ignoreCase = true) && url.startsWith("$repoUrl/releases/download/")
        } ?: return null
        val body = Json.str(root["body"]).orEmpty()
        val page = Json.str(root["html_url"])?.takeIf { it.startsWith("$repoUrl/") } ?: "$repoUrl/releases/tag/$tag"
        return ReleaseInfo(
            tag = tag, version = version,
            title = Json.str(root["name"])?.takeIf { it.isNotBlank() } ?: tag,
            notes = shortNotes(body), pageUrl = page,
            apkName = Json.str(asset["name"])!!, apkUrl = Json.str(asset["browser_download_url"])!!,
            apkSize = (asset["size"] as? Number)?.toLong() ?: 0L,
            sha256 = sha256Of(body),
        )
    }
}