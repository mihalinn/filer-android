package local.filemanager

import java.net.URLEncoder

/** GitHub の Issue の入力ページを、バージョンなどを入れた状態で開くための URL を作る。Android に依存しない。 */
object ReportLogic {
    /** ブラウザや GitHub が受け付ける長さの目安(これを超える分の長い文字は、うしろを省く)。 */
    const val MAX_URL = 7000

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /**
     * repoUrl/issues/new?template=... の URL。fields は入力欄の id と中身(空のものは付けない)。
     * 長くなるときは、longKey の欄(異常終了の記録など)を後ろから省く。
     */
    fun issueUrl(repoUrl: String, template: String, fields: Map<String, String>, longKey: String? = null): String {
        val base = StringBuilder("$repoUrl/issues/new?template=${enc(template)}")
        for ((k, v) in fields) if (k != longKey && v.isNotBlank()) base.append('&').append(enc(k)).append('=').append(enc(v))
        var long = longKey?.let { fields[it] }.orEmpty()
        if (long.isBlank()) return base.toString()
        var trimmed = false
        fun make(): String = base.toString() + "&" + enc(longKey!!) + "=" + enc(long + if (trimmed) "\n…(長いので省略)" else "")
        var url = make()
        while (url.length > MAX_URL && long.isNotEmpty()) {
            trimmed = true
            long = long.take(long.length * 8 / 10)
            url = make()
        }
        return url
    }

    /** 端末の情報(1 行)。 */
    fun deviceLine(manufacturer: String, model: String, androidRelease: String, sdk: Int): String =
        "${manufacturer.trim()} ${model.trim()} / Android $androidRelease(API $sdk)".trim()
}