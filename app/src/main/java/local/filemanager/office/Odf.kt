package local.filemanager

import kotlin.math.roundToInt

/** OpenDocument(.odt / .ods / .odp)の共通部品: 長さ・色の読み取りと、スタイルの引き継ぎ。Android に依存しない。 */
internal object Odf {
    private val lenRe = Regex("^(-?[0-9]*\\.?[0-9]+)\\s*(cm|mm|in|pt|pc|px)?$")

    /** 「2.5cm」「10mm」「1in」「12pt」「16px」などを pt にする。読めなければ null(「%」も null)。 */
    fun len(s: String?): Float? {
        val m = lenRe.find(s?.trim() ?: return null) ?: return null
        val v = m.groupValues[1].toFloatOrNull() ?: return null
        return when (m.groupValues[2]) {
            "cm" -> v * 28.3465f
            "mm" -> v * 2.83465f
            "in" -> v * 72f
            "pc" -> v * 12f
            "px" -> v * 0.75f
            else -> v
        }
    }

    /** 「#rrggbb」→ ARGB。「transparent」などは null。 */
    fun color(s: String?): Int? {
        val t = s?.trim() ?: return null
        if (!t.startsWith("#") || t.length != 7) return null
        return t.substring(1).toIntOrNull(16)?.let { it or (0xFF shl 24) }
    }

    /** 「80%」→ 0.8 */
    fun percent(s: String?): Float? = s?.trim()?.takeIf { it.endsWith("%") }?.dropLast(1)?.toFloatOrNull()?.div(100f)

    fun xml(read: (String) -> ByteArray?, name: String): XNode? = read(name)?.let { runCatching { XmlTree.parse(it, mixed = true) }.getOrNull() }

    /** 文書の中の、色・太さなどの指定の名前。プロパティの種類ごとに、先頭の印をつけて 1 つの表にする。 */
    private val propPrefix = mapOf(
        "text-properties" to "t", "paragraph-properties" to "p", "table-cell-properties" to "c", "table-column-properties" to "col",
        "table-row-properties" to "row", "table-properties" to "tb", "graphic-properties" to "g", "drawing-page-properties" to "d",
        "section-properties" to "sec",
    )

    /** style:style(や default-style)1 つの中のプロパティを、「印|属性名」 → 値 の表にする。 */
    internal fun propsOf(style: XNode): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for ((k, v) in style.attrs) if (k == "style:list-style-name" || k == "style:master-page-name" || k == "style:data-style-name") m["@$k"] = v
        for (k in style.kids) {
            val pre = propPrefix[k.name] ?: continue
            for ((a, v) in k.attrs) m["$pre|$a"] = v
        }
        return m
    }

    /** 「0.05pt solid #000000」のような罫線の指定が、実際に線を引くものか(none や太さ 0 は引かない)。 */
    fun hasBorder(v: String?): Boolean {
        val s = v?.trim() ?: return false
        if (s == "none" || s.isEmpty()) return false
        return (len(s.split(Regex("\\s+"))[0]) ?: 1f) > 0f
    }

    /** 名前の「_20_」(空白)などの符号を元に戻さずに、そのまま比べる。 */
    fun key(family: String, name: String) = "$family/$name"
}

/** スタイル(content.xml と styles.xml にある、名前つきの書式)の表。親のスタイルをたどって、プロパティをまとめて返す。 */
internal class OdfStyles(vararg roots: XNode?) {
    private class Def(val parent: String?, val props: Map<String, String>)

    private val defs = HashMap<String, Def>()
    private val defaults = HashMap<String, Map<String, String>>()
    val listStyles = HashMap<String, XNode>()

    init {
        for (root in roots) {
            root ?: continue
            for (box in root.kids) {
                if (box.name != "automatic-styles" && box.name != "styles" && box.name != "master-styles") continue
                for (st in box.kids) {
                    when (st.name) {
                        "style" -> {
                            val fam = st.a("style:family") ?: continue
                            val name = st.a("style:name") ?: continue
                            defs[Odf.key(fam, name)] = Def(st.a("style:parent-style-name"), Odf.propsOf(st))
                        }
                        "default-style" -> st.a("style:family")?.let { defaults[it] = Odf.propsOf(st) }
                        "list-style" -> st.a("style:name")?.let { listStyles[it] = st }
                    }
                }
            }
        }
    }

    /** family: paragraph / text / table-cell / table-column / graphic / presentation / drawing-page など。親 → 子の順に重ねる。 */
    fun props(family: String, name: String?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        defaults[family]?.let { out.putAll(it) }
        if (name == null) return out
        val chain = ArrayList<Def>()
        var cur: String? = name
        var guard = 0
        while (cur != null && guard++ < 12) {
            val d = defs[Odf.key(family, cur)] ?: break
            chain.add(d)
            cur = d.parent
        }
        for (d in chain.asReversed()) out.putAll(d.props)
        return out
    }

    fun hasStyle(family: String, name: String?): Boolean = name != null && defs.containsKey(Odf.key(family, name))
}

/** 文字の書式(ODF のプロパティの表から)を、Word の書式の形(RPr)にする。 */
internal object OdfText {
    fun rpr(p: Map<String, String>, baseSizePt: Float? = null): RPr {
        val size = Odf.len(p["t|fo:font-size"]) ?: Odf.percent(p["t|fo:font-size"])?.let { r -> baseSizePt?.let { it * r } }
        val under = p["t|style:text-underline-style"]
        val strike = p["t|style:text-line-through-style"]
        val pos = p["t|style:text-position"]?.trim()
        val vert = when {
            pos == null -> null
            pos.startsWith("super") || (pos.firstOrNull()?.isDigit() == true && (pos.substringBefore('%').toIntOrNull() ?: 0) > 0) -> 1
            pos.startsWith("sub") || pos.startsWith("-") -> -1
            else -> null
        }
        return RPr(
            bold = p["t|fo:font-weight"]?.let { it == "bold" || (it.toIntOrNull() ?: 400) >= 600 },
            italic = p["t|fo:font-style"]?.let { it == "italic" || it == "oblique" },
            underline = under?.let { it != "none" },
            strike = strike?.let { it != "none" },
            sizeHp = size?.let { (it * 2f).roundToInt() },
            color = Odf.color(p["t|fo:color"]),
            font = p["t|style:font-name"] ?: p["t|fo:font-family"]?.trim('\'', '"'),
            highlight = Odf.color(p["t|fo:background-color"]),
            vert = vert,
        )
    }
}
