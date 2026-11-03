package local.filemanager

/** 部品の種類。BASE=本体、ABI=CPU の種類用、DPI=画面の細かさ用、LANG=言語用、OTHER=そのほか(追加機能など)。 */
enum class SplitKind { BASE, ABI, DPI, LANG, OTHER }

class Split(val entry: ZipReader.Entry, val kind: SplitKind, val tag: String) {
    val fileName: String get() = ZipTree.clean(entry.name).substringAfterLast('/')
}

/** 入れる部品の組み合わせ。 */
class BundlePlan(
    val base: Split?,
    /** 入れるもの(本体を含む)。 */
    val chosen: List<Split>,
    /** この端末には要らないので入れないもの(別の CPU 用など)。 */
    val skipped: List<Split>,
    /** 選んだ CPU の種類(「arm64-v8a」など)。CPU 用の部品が無い bundle では null。 */
    val abi: String?,
    /** CPU 用の部品はあるのに、この端末に合うものが無いとき true(入れても動かない可能性が高い)。 */
    val abiMismatch: Boolean,
) {
    val totalBytes: Long get() = chosen.sumOf { it.entry.size }
    fun count(kind: SplitKind) = chosen.count { it.kind == kind }
}

/** まとめのファイルの情報(アプリ名・版など)。分からない項目は null。 */
class BundleMeta(
    val name: String?,
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long?,
    val minSdk: Int?,
    val iconEntry: ZipReader.Entry?,
    val hasObb: Boolean,
    val format: String,
)

/**
 * .xapk(APKPure など)・.apkm(APKMirror)・.apks(bundletool)は、本体の APK と部品の APK を 1 つの ZIP にまとめたもの。
 * 入れるときは、この端末に合う部品だけを選んで、まとめて入れる必要がある。Android に依存しない。
 */
object ApkBundle {
    private val abiTags = setOf("arm64_v8a", "armeabi_v7a", "armeabi", "x86_64", "x86", "mips", "mips64", "riscv64")
    private val dpiTags = setOf("ldpi", "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi", "tvdpi", "nodpi", "anydpi")
    private val langRegex = Regex("^[a-z]{2,3}([_-][a-zA-Z]{2,4})?$")
    private val prefixes = listOf("split_config.", "config.", "base-", "split_")

    /** 「arm64_v8a」→「arm64-v8a」(Android の正式な書き方)。 */
    fun abiDisplay(tag: String) = when (tag) {
        "arm64_v8a" -> "arm64-v8a"
        "armeabi_v7a" -> "armeabi-v7a"
        else -> tag
    }

    /** CPU の名前を、「arm64_v8a」のそろった書き方にする(Build.SUPPORTED_ABIS の「arm64-v8a」とも比べられる)。 */
    fun normalizeAbi(s: String) = s.lowercase().replace('-', '_')

    private fun isApk(e: ZipReader.Entry): Boolean {
        if (e.isDir) return false
        val p = ZipTree.clean(e.name).lowercase()
        if (!p.endsWith(".apk")) return false
        // bundletool の「standalones/」「instant/」は、別の入れ方の完成品なので使わない
        return !(p.startsWith("standalones/") || p.contains("/standalones/") || p.startsWith("instant/") || p.contains("/instant/"))
    }

    /** 部品のファイル名から、種類と印(「arm64_v8a」「ja」など)を決める。部品でないもの(本体かもしれないもの)は null。 */
    internal fun splitTag(fileName: String): Pair<SplitKind, String>? {
        val stem = fileName.removeSuffix(".apk").removeSuffix(".APK")
        if (stem == "base" || stem == "base-master" || stem == "universal") return SplitKind.BASE to ""
        val prefix = prefixes.firstOrNull { stem.startsWith(it) } ?: return null
        val tag = stem.removePrefix(prefix)
        val n = normalizeAbi(tag)
        return when {
            n == "master" -> SplitKind.BASE to ""
            n in abiTags -> SplitKind.ABI to n
            n in dpiTags -> SplitKind.DPI to n
            langRegex.matches(tag) -> SplitKind.LANG to tag
            else -> SplitKind.OTHER to tag
        }
    }

    /** ZIP の中の APK を、本体・部品に分ける。本体が見つからなければ、部品でない中でいちばん大きいものを本体とする。 */
    fun classify(entries: List<ZipReader.Entry>): List<Split> {
        val out = ArrayList<Split>()
        val unknown = ArrayList<ZipReader.Entry>()
        for (e in entries.filter { isApk(it) }) {
            val fn = ZipTree.clean(e.name).substringAfterLast('/')
            val t = splitTag(fn)
            if (t == null) unknown.add(e) else out.add(Split(e, t.first, t.second))
        }
        if (out.none { it.kind == SplitKind.BASE } && unknown.isNotEmpty()) {
            val big = unknown.maxByOrNull { it.size }!!
            out.add(Split(big, SplitKind.BASE, ""))
            unknown.remove(big)
        }
        // 本体の候補が複数あるとき(もう 1 つの「部品でないもの」)は、追加機能として扱う
        for (e in unknown) out.add(Split(e, SplitKind.OTHER, ZipTree.clean(e.name).substringAfterLast('/').removeSuffix(".apk")))
        return out
    }

    /**
     * 入れる組み合わせを決める。本体・画面の細かさ用・言語用・そのほかはすべて入れる(どれも小さい)。
     * CPU 用は、この端末が対応する CPU のうち、いちばん優先されるもの 1 つだけを入れる。
     * deviceAbis は Build.SUPPORTED_ABIS(優先順)。
     */
    fun plan(entries: List<ZipReader.Entry>, deviceAbis: List<String>): BundlePlan {
        val all = classify(entries)
        val base = all.firstOrNull { it.kind == SplitKind.BASE }
        val abis = all.filter { it.kind == SplitKind.ABI }
        val wanted = deviceAbis.map { normalizeAbi(it) }
        val pick = wanted.firstNotNullOfOrNull { w -> abis.firstOrNull { it.tag == w } }
        val chosen = ArrayList<Split>()
        base?.let { chosen.add(it) }
        chosen.addAll(all.filter { it.kind == SplitKind.DPI || it.kind == SplitKind.LANG || it.kind == SplitKind.OTHER })
        pick?.let { chosen.add(it) }
        val skipped = all.filter { it !in chosen && it.kind != SplitKind.BASE }
        return BundlePlan(base, chosen, skipped, pick?.tag?.let { abiDisplay(it) }, abiMismatch = abis.isNotEmpty() && pick == null)
    }

    // ---------------------------------------------------------------- まとめの情報

    /** JSON の中の、文字の値(「"key": "…"」)を取り出す。入れ子は見ない簡易版(最初に出てくるもの)。 */
    internal fun jsonString(text: String, key: String): String? {
        val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(text) ?: return null
        return unescapeJson(m.groupValues[1])
    }

    internal fun jsonNumber(text: String, key: String): Long? {
        // 数のこともあれば、"10" のように文字のこともある
        val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"?(-?\\d+)\"?").find(text) ?: return null
        return m.groupValues[1].toLongOrNull()
    }

    private fun unescapeJson(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'u' -> {
                        val h = if (i + 6 <= s.length) s.substring(i + 2, i + 6).toIntOrNull(16) else null
                        if (h != null) {
                            sb.append(h.toChar())
                            i += 6
                            continue
                        } else {
                            sb.append('u')
                        }
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

    private fun unescapeHtml(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")

    /**
     * 目録のファイル(.xapk の manifest.json、.apkm の info.json)から、アプリ名・版などを読む。
     * readText は、ZIP の中の指定した項目を文字にして返す(無ければ null)。
     */
    fun meta(bundleName: String, entries: List<ZipReader.Entry>, readText: (ZipReader.Entry) -> String?): BundleMeta {
        val byName = entries.associateBy { ZipTree.clean(it.name) }
        val ext = FileOps.extensionOf(bundleName)
        val icon = byName["icon.png"]
        val hasObb = entries.any { val p = ZipTree.clean(it.name).lowercase(); p.startsWith("android/obb/") || p.contains("/obb/") || p.endsWith(".obb") }
        val manifest = byName["manifest.json"]?.let(readText)
        val info = byName["info.json"]?.let(readText)
        return when {
            manifest != null -> BundleMeta(
                jsonString(manifest, "name"), jsonString(manifest, "package_name"), jsonString(manifest, "version_name"),
                jsonNumber(manifest, "version_code"), jsonNumber(manifest, "min_sdk_version")?.toInt(), icon, hasObb, "XAPK",
            )
            info != null -> BundleMeta(
                jsonString(info, "app_name")?.let(::unescapeHtml), jsonString(info, "pname"), jsonString(info, "release_version"),
                jsonNumber(info, "versioncode"), null, icon, hasObb, "APKM",
            )
            else -> BundleMeta(null, null, null, null, null, icon, hasObb, if (ext == "apks") "APKS" else ext.uppercase())
        }
    }
}
