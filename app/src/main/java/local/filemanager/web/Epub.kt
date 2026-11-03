package local.filemanager

/** 目次の 1 行。spineIndex は、読む順の何番目の章か。anchor は、章の中の位置(あれば)。 */
class TocEntry(val title: String, val spineIndex: Int, val anchor: String?, val depth: Int)

class EpubBook(
    val title: String?,
    val creators: List<String>,
    val publisher: String?,
    val language: String?,
    val date: String?,
    val identifier: String?,
    /** 読む順の章(ZIP の中のパス) */
    val spine: List<String>,
    val toc: List<TocEntry>,
    val coverPath: String?,
    /** 右から左へ読む本(縦書きの日本語など) */
    val rtl: Boolean,
    /** 本に含まれる CSS(ZIP の中のパス)。縦書きかどうかを調べるのに使う */
    val cssPaths: List<String> = emptyList(),
)

/** EPUB(電子書籍)の目録と目次を読む。ZIP の中の項目を取り出す関数 read を使う(無ければ null)。Android に依存しない。 */
object Epub {
    /** 「%20」「%E3%81%82」のような、URL の % 表記を元の文字にする。 */
    fun percentDecode(s: String): String {
        if (!s.contains('%')) return s
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        val bytes = s.toByteArray(Charsets.UTF_8)
        while (i < bytes.size) {
            val b = bytes[i].toInt()
            if (b == '%'.code && i + 2 < bytes.size) {
                val h = Character.digit(bytes[i + 1].toInt().toChar(), 16)
                val l = Character.digit(bytes[i + 2].toInt().toChar(), 16)
                if (h >= 0 && l >= 0) {
                    out.write(h * 16 + l)
                    i += 3
                    continue
                }
            }
            out.write(b)
            i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** 外部の定義(DOCTYPE)は読まない。あると読み込みに失敗するので、先に取り除く。 */
    fun stripDoctype(xml: String): String = xml.replace(Regex("<!DOCTYPE[^>\\[]*(\\[[^\\]]*\\])?\\s*>", RegexOption.IGNORE_CASE), "")

    private fun tree(bytes: ByteArray): XNode {
        val text = TextDecode.decode(bytes, false).text.removePrefix("\uFEFF")
        return XmlTree.parse(stripDoctype(text).toByteArray(Charsets.UTF_8))
    }

    fun dirOf(path: String): String = path.substringBeforeLast('/', "")

    /** href(章の中の相対パス)を、ZIP の中のパスと、# 以降の位置に分ける。 */
    fun resolve(baseDir: String, href: String): Pair<String, String?> {
        val frag = href.substringAfter('#', "").takeIf { it.isNotEmpty() }?.let { percentDecode(it) }
        val file = percentDecode(href.substringBefore('#').substringBefore('?'))
        return OfficeXml.resolve(baseDir, file) to frag
    }

    fun parse(read: (String) -> ByteArray?): EpubBook {
        val container = read("META-INF/container.xml") ?: throw OpException("EPUBとして読めません(META-INF/container.xml がありません)")
        val rootfile = tree(container).let { c ->
            fun find(n: XNode): String? = if (n.name == "rootfile") n.a("full-path") else n.kids.firstNotNullOfOrNull { find(it) }
            find(c)
        } ?: throw OpException("EPUBとして読めません(本の目録が見つかりません)")
        val opfPath = OfficeXml.resolve("", percentDecode(rootfile))
        val opf = tree(read(opfPath) ?: throw OpException("EPUBとして読めません(目録 $opfPath がありません)"))
        val base = dirOf(opfPath)

        val meta = opf.child("metadata")
        fun texts(name: String) = meta?.children(name)?.map { it.text.toString().trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        class Item(val id: String, val path: String, val mime: String, val props: Set<String>)
        val items = LinkedHashMap<String, Item>()
        opf.child("manifest")?.children("item")?.forEach { n ->
            val id = n.a("id") ?: return@forEach
            val href = n.a("href") ?: return@forEach
            items[id] = Item(id, resolve(base, href).first, n.a("media-type") ?: "", (n.a("properties") ?: "").split(' ').filter { it.isNotEmpty() }.toSet())
        }
        val spineNode = opf.child("spine")
        val spine = spineNode?.children("itemref")?.mapNotNull { r -> items[r.a("idref")]?.path } ?: emptyList()
        val spineIndex = HashMap<String, Int>()
        spine.forEachIndexed { i, p -> spineIndex.putIfAbsent(p, i) }

        // 表紙
        val coverId = meta?.children("meta")?.firstOrNull { it.a("name") == "cover" }?.a("content")
        val cover = items.values.firstOrNull { "cover-image" in it.props }?.path ?: coverId?.let { items[it]?.path }

        // 目次: EPUB3 の nav → 無ければ EPUB2 の NCX
        var toc: List<TocEntry> = emptyList()
        items.values.firstOrNull { "nav" in it.props }?.let { nav ->
            val html = read(nav.path)?.let { TextDecode.decode(it, false).text }
            if (html != null) toc = parseNav(html, dirOf(nav.path), spineIndex)
        }
        if (toc.isEmpty()) {
            val ncx = spineNode?.a("toc")?.let { items[it] } ?: items.values.firstOrNull { it.mime == "application/x-dtbncx+xml" }
            if (ncx != null) read(ncx.path)?.let { b -> toc = runCatching { parseNcx(tree(b), dirOf(ncx.path), spineIndex) }.getOrDefault(emptyList()) }
        }
        val rtl = spineNode?.a("page-progression-direction") == "rtl"
        return EpubBook(
            texts("title").firstOrNull(), texts("creator"), texts("publisher").firstOrNull(), texts("language").firstOrNull(),
            texts("date").firstOrNull(), texts("identifier").firstOrNull(), spine, toc, cover, rtl,
            items.values.filter { it.mime == "text/css" }.map { it.path },
        )
    }

    private fun cleanText(s: String): String =
        s.replace(Regex("<[^>]*>"), "").replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: "" }
            .replace(Regex("\\s+"), " ").trim()

    /** EPUB3 の目次(nav)。HTML を、ごく簡単に読む。入れ子の ol / ul の深さを、字下げにする。 */
    fun parseNav(html: String, navDir: String, spineIndex: Map<String, Int>): List<TocEntry> {
        val navBlock = Regex("<nav\\b[^>]*epub:type\\s*=\\s*[\"'][^\"']*toc[^\"']*[\"'][^>]*>(.*?)</nav>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(html)?.groupValues?.get(1)
            ?: Regex("<nav\\b[^>]*>(.*?)</nav>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(html)?.groupValues?.get(1)
            ?: return emptyList()
        val out = ArrayList<TocEntry>()
        var depth = 0
        val tag = Regex("<(/?)(ol|ul)\\b[^>]*>|<a\\b([^>]*)>(.*?)</a>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        for (m in tag.findAll(navBlock)) {
            if (m.groupValues[2].isNotEmpty()) {
                if (m.groupValues[1].isEmpty()) depth++ else depth = (depth - 1).coerceAtLeast(0)
                continue
            }
            val href = Regex("href\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(m.groupValues[3])?.groupValues?.get(1) ?: continue
            val (path, frag) = resolve(navDir, href)
            val idx = spineIndex[path] ?: continue
            val title = cleanText(m.groupValues[4])
            if (title.isNotEmpty()) out.add(TocEntry(title, idx, frag, (depth - 1).coerceAtLeast(0)))
        }
        return out
    }

    /** EPUB2 の目次(NCX)。 */
    internal fun parseNcx(root: XNode, ncxDir: String, spineIndex: Map<String, Int>): List<TocEntry> {
        val out = ArrayList<TocEntry>()
        fun walk(n: XNode, depth: Int) {
            for (p in n.children("navPoint")) {
                val title = p.child("navLabel")?.child("text")?.text?.toString()?.trim().orEmpty()
                val src = p.child("content")?.a("src")
                if (src != null && title.isNotEmpty()) {
                    val (path, frag) = resolve(ncxDir, src)
                    spineIndex[path]?.let { out.add(TocEntry(title, it, frag, depth)) }
                }
                walk(p, depth + 1)
            }
        }
        root.child("navMap")?.let { walk(it, 0) }
        return out
    }

    /** CSS の中に、縦書き(writing-mode: vertical-rl / vertical-lr)の指定があるか。 */
    fun isVerticalCss(css: String): Boolean = Regex("writing-mode\\s*:\\s*vertical-(rl|lr)", RegexOption.IGNORE_CASE).containsMatchIn(css)

    /** 縦書きの本で、WebView の高さ(CSS の px)を、列の長さとして章へ教えるスタイル。 */
    fun verticalCss(heightPx: Int): String = "<style>html{height:${heightPx}px !important}body{height:100% !important;box-sizing:border-box}</style>"

    /** 暗い配色のときに、章の HTML へ差し込むスタイル。本の色指定より優先する。 */
    const val DARK_CSS = "<style>html,body{background:#121212 !important;color:#e0e0e0 !important}" +
        "*{color:inherit !important;background-color:transparent !important;border-color:#666 !important}" +
        "a,a *{color:#82b1ff !important}</style>"

    /** HTML の <head> の直後へ、スタイルを差し込む(head が無ければ先頭へ)。 */
    fun injectHead(html: String, css: String): String {
        val m = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE).find(html)
        return if (m != null) html.substring(0, m.range.last + 1) + css + html.substring(m.range.last + 1) else css + html
    }

    fun mimeFor(path: String): String = when (val ext = FileOps.extensionOf(path)) {
        "xhtml", "html", "htm" -> "text/html"
        "ncx", "opf" -> "text/xml"
        else -> HtmlRules.mimeFor(ext)
    }
}
