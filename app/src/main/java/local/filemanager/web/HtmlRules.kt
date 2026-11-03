package local.filemanager

/** HTML の表示で、ページから読み込める場所と、返す種類(MIME)を決める規則。Android に依存しない。 */
object HtmlRules {
    /** ページのあるフォルダの 1 つ上までは、画像・CSS・フォントに限って読ませる(「../css/style.css」のような参照のため)。 */
    private val staticExt = setOf("css", "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp", "avif", "woff", "woff2", "ttf", "otf")

    /** 文字コードをそろえて(UTF-8 にして)返すもの。 */
    private val textual = setOf("html", "htm", "css", "js", "mjs")

    private val mimes = mapOf(
        "html" to "text/html", "htm" to "text/html", "css" to "text/css", "js" to "application/javascript",
        "mjs" to "application/javascript", "json" to "application/json", "svg" to "image/svg+xml", "xml" to "text/xml",
        "txt" to "text/plain", "ico" to "image/x-icon", "woff" to "font/woff", "woff2" to "font/woff2",
        "ttf" to "font/ttf", "otf" to "font/otf", "mp4" to "video/mp4", "webm" to "video/webm", "mp3" to "audio/mpeg",
    )

    /** 「.」「..」をたたみ、必ず「/」から始まる形にする。 */
    fun normalize(path: String): String {
        val out = ArrayList<String>()
        for (seg in path.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(seg)
            }
        }
        return "/" + out.joinToString("/")
    }

    fun dirOf(path: String): String {
        val n = normalize(path)
        val i = n.lastIndexOf('/')
        return if (i <= 0) "/" else n.substring(0, i)
    }

    private fun under(path: String, dir: String): Boolean = dir == "/" || path == dir || path.startsWith(dir.trimEnd('/') + "/")

    /** htmlPath のページが、requestPath を読んでよいか。 */
    fun isAllowed(htmlPath: String, requestPath: String): Boolean {
        val dir = dirOf(htmlPath)
        val req = normalize(requestPath)
        if (under(req, dir)) return true
        val parent = dirOf(dir)
        return under(req, parent) && FileOps.extensionOf(req) in staticExt
    }

    fun isTextual(ext: String) = ext in textual

    fun mimeFor(ext: String): String = mimes[ext] ?: FileOps.mimeOf("x.$ext")

    /** ページを置く、見かけだけのアドレス。ここへの読み込みは、アプリが Fs から返す(外のサーバーには出ない)。 */
    const val HOST = "filer.local"

    /**
     * 読み込みを止めるか。止めるのは、外のサーバー(http / https で、HOST 以外)への通信だけ。
     * ページ本体(data:)や、ページに埋め込まれた画像(data:)などは止めない。
     */
    fun shouldBlock(scheme: String?, host: String?, networkAllowed: Boolean): Boolean {
        val s = scheme?.lowercase()
        if (s != "http" && s != "https") return false
        if (host == HOST) return false
        return !networkAllowed
    }

    /** ブラウザで開いてよい外部リンクか。 */
    fun isExternalLink(url: String): Boolean {
        val u = url.lowercase()
        return u.startsWith("http://") || u.startsWith("https://") || u.startsWith("mailto:") || u.startsWith("tel:")
    }
}
