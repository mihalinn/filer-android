package local.filemanager

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import kotlin.math.max
import kotlin.math.min

/** XML を軽い木にしたもの。名前は接頭辞(p: や a: )を外した形。属性は元の名前(r:id など)のまま。 */
internal class XNode(val name: String, val attrs: Map<String, String>) {
    val kids = ArrayList<XNode>()
    val text = StringBuilder()

    fun a(k: String): String? = attrs[k]

    /** r:id / r:embed など、関係の ID を表す属性 */
    fun rid(k: String = "id"): String? = attrs["r:$k"] ?: attrs.entries.firstOrNull { it.key.endsWith(":$k") && !it.key.startsWith("xml") }?.value

    fun child(n: String): XNode? {
        for (c in kids) if (c.name == n) return c
        return null
    }

    fun children(n: String): List<XNode> = kids.filter { it.name == n }
    fun int(k: String): Int? = attrs[k]?.trim()?.toIntOrNull()
    fun num(k: String): Double? = attrs[k]?.trim()?.toDoubleOrNull()
    fun flag(k: String): Boolean? = attrs[k]?.let { it == "1" || it == "true" || it == "on" }
}

internal object XmlTree {
    /** mixed が true のときは、文字も「#text」という子として、元の順に残す(ODF の本文のように、文字と要素が混ざるもの用)。 */
    fun parse(bytes: ByteArray, mixed: Boolean = false): XNode {
        val root = XNode("#root", emptyMap())
        val stack = ArrayList<XNode>()
        stack.add(root)
        OfficeXml.parse(
            bytes,
            object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                    val m = HashMap<String, String>()
                    for (i in 0 until a.length) m[a.getQName(i)] = a.getValue(i)
                    val n = XNode(qName.substringAfter(':'), m)
                    stack[stack.size - 1].kids.add(n)
                    stack.add(n)
                }

                override fun endElement(uri: String?, localName: String?, qName: String) {
                    if (stack.size > 1) stack.removeAt(stack.size - 1)
                }

                override fun characters(ch: CharArray, start: Int, length: Int) {
                    val cur = stack[stack.size - 1]
                    cur.text.append(ch, start, length)
                    if (mixed) {
                        val last = cur.kids.lastOrNull()
                        if (last != null && last.name == "#text") last.text.append(ch, start, length)
                        else cur.kids.add(XNode("#text", emptyMap()).also { it.text.append(ch, start, length) })
                    }
                }
            },
        )
        return root.kids.firstOrNull() ?: throw OpException("XMLが空")
    }
}

internal class ThemeFont(val latin: String?, val ea: String?)

/** テーマ(色の組・書体・線の太さ) */
internal class SlideTheme(
    val colors: Map<String, Int>,
    val major: ThemeFont,
    val minor: ThemeFont,
    val lineWidths: List<Float>,
) {
    /** tx1・bg1・accent1 などの名前を、色(RGB)にする。clrMap は、tx1→dk1 のような対応。 */
    fun scheme(name: String, clrMap: Map<String, String>): Int {
        val real = clrMap[name] ?: name
        return colors[real] ?: colors[name] ?: 0
    }

    /** 「+mj-lt」(見出しの欧文)などの指定を、実際の書体名にする。 */
    fun font(spec: String?): String? {
        if (spec == null) return null
        val s = spec.trim()
        if (s.isEmpty()) return null
        if (!s.startsWith("+")) return s
        return when {
            s.startsWith("+mj-lt") -> major.latin
            s.startsWith("+mn-lt") -> minor.latin
            s.startsWith("+mj-ea") -> major.ea
            s.startsWith("+mn-ea") -> minor.ea
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    companion object {
        val DEFAULT_COLORS: Map<String, Int> = mapOf(
            "dk1" to 0x000000, "lt1" to 0xFFFFFF, "dk2" to 0x44546A, "lt2" to 0xE7E6E6,
            "accent1" to 0x4472C4, "accent2" to 0xED7D31, "accent3" to 0xA5A5A5, "accent4" to 0xFFC000,
            "accent5" to 0x5B9BD5, "accent6" to 0x70AD47, "hlink" to 0x0563C1, "folHlink" to 0x954F72,
        )
        val DEFAULT_CLRMAP: Map<String, String> = mapOf(
            "bg1" to "lt1", "tx1" to "dk1", "bg2" to "lt2", "tx2" to "dk2",
            "accent1" to "accent1", "accent2" to "accent2", "accent3" to "accent3", "accent4" to "accent4",
            "accent5" to "accent5", "accent6" to "accent6", "hlink" to "hlink", "folHlink" to "folHlink",
        )
        val DEFAULT = SlideTheme(DEFAULT_COLORS, ThemeFont(null, null), ThemeFont(null, null), listOf(0.5f, 1f, 1.5f))

        fun parse(bytes: ByteArray?): SlideTheme {
            if (bytes == null) return DEFAULT
            return try {
                val root = XmlTree.parse(bytes)
                val els = root.child("themeElements") ?: return DEFAULT
                val colors = HashMap<String, Int>(DEFAULT_COLORS)
                els.child("clrScheme")?.kids?.forEach { c ->
                    val inner = c.kids.firstOrNull() ?: return@forEach
                    val v = when (inner.name) {
                        "srgbClr" -> inner.a("val")?.toIntOrNull(16)
                        "sysClr" -> inner.a("lastClr")?.toIntOrNull(16) ?: if (inner.a("val") == "window") 0xFFFFFF else 0
                        else -> null
                    }
                    if (v != null) colors[c.name] = v and 0xFFFFFF
                }
                fun font(n: XNode?) = ThemeFont(n?.child("latin")?.a("typeface"), n?.child("ea")?.a("typeface"))
                val fs = els.child("fontScheme")
                val widths = els.child("fmtScheme")?.child("lnStyleLst")?.children("ln")?.map { (it.num("w") ?: 12700.0).toFloat() / 12700f }
                SlideTheme(colors, font(fs?.child("majorFont")), font(fs?.child("minorFont")), if (widths.isNullOrEmpty()) DEFAULT.lineWidths else widths)
            } catch (e: Exception) {
                DEFAULT
            }
        }
    }
}

internal class ColorCtx(val theme: SlideTheme, val clrMap: Map<String, String>, val phClr: Int? = null)

internal object SlideColor {
    private val named = mapOf(
        "black" to 0x000000, "white" to 0xFFFFFF, "red" to 0xFF0000, "green" to 0x008000, "blue" to 0x0000FF,
        "yellow" to 0xFFFF00, "gray" to 0x808080, "grey" to 0x808080, "orange" to 0xFFA500, "purple" to 0x800080,
        "cyan" to 0x00FFFF, "magenta" to 0xFF00FF, "lime" to 0x00FF00, "silver" to 0xC0C0C0, "lightGray" to 0xD3D3D3,
        "darkGray" to 0xA9A9A9, "dkGray" to 0xA9A9A9, "ltGray" to 0xD3D3D3, "darkBlue" to 0x00008B, "darkRed" to 0x8B0000,
        "darkGreen" to 0x006400, "brown" to 0xA52A2A, "pink" to 0xFFC0CB, "navy" to 0x000080, "gold" to 0xFFD700,
    )

    /** solidFill など、色の要素を子に持つ要素から、色(ARGB)を取り出す。 */
    fun fromHolder(holder: XNode?, ctx: ColorCtx): Int? {
        holder ?: return null
        for (k in holder.kids) {
            val c = colorElement(k, ctx)
            if (c != null) return c
        }
        return null
    }

    fun colorElement(n: XNode, ctx: ColorCtx): Int? {
        var alpha = 255
        var rgb: Int = when (n.name) {
            "srgbClr" -> n.a("val")?.toIntOrNull(16) ?: return null
            "sysClr" -> n.a("lastClr")?.toIntOrNull(16) ?: if (n.a("val") == "window") 0xFFFFFF else 0
            "prstClr" -> named[n.a("val")] ?: 0
            "schemeClr" -> {
                val v = n.a("val") ?: return null
                if (v == "phClr") {
                    val ph = ctx.phClr ?: return null
                    alpha = (ph ushr 24) and 0xFF
                    ph and 0xFFFFFF
                } else {
                    ctx.theme.scheme(v, ctx.clrMap)
                }
            }
            else -> return null
        }
        rgb = rgb and 0xFFFFFF
        for (t in n.kids) {
            val v = t.num("val") ?: continue
            val f = (v / 100000.0).toFloat()
            when (t.name) {
                "lumMod" -> rgb = withHsl(rgb) { h, s, l -> Triple(h, s, (l * f).coerceIn(0f, 1f)) }
                "lumOff" -> rgb = withHsl(rgb) { h, s, l -> Triple(h, s, (l + f).coerceIn(0f, 1f)) }
                "satMod" -> rgb = withHsl(rgb) { h, s, l -> Triple(h, (s * f).coerceIn(0f, 1f), l) }
                "satOff" -> rgb = withHsl(rgb) { h, s, l -> Triple(h, (s + f).coerceIn(0f, 1f), l) }
                "tint" -> rgb = mix(rgb, 0xFFFFFF, f)
                "shade" -> rgb = mix(rgb, 0x000000, f)
                "alpha" -> alpha = (f * 255f).toInt().coerceIn(0, 255)
            }
        }
        return (alpha shl 24) or rgb
    }

    /** f の割合で c、残りが target */
    private fun mix(c: Int, target: Int, f: Float): Int {
        fun ch(shift: Int): Int {
            val a = (c shr shift) and 0xFF
            val b = (target shr shift) and 0xFF
            return (a * f + b * (1f - f)).toInt().coerceIn(0, 255)
        }
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun withHsl(rgb: Int, f: (Float, Float, Float) -> Triple<Float, Float, Float>): Int {
        val r = ((rgb shr 16) and 0xFF) / 255f
        val g = ((rgb shr 8) and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2f
        val d = mx - mn
        var h = 0f
        var s = 0f
        if (d > 0f) {
            s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
            h = when (mx) {
                r -> ((g - b) / d + (if (g < b) 6f else 0f))
                g -> ((b - r) / d + 2f)
                else -> ((r - g) / d + 4f)
            } / 6f
        }
        val (h2, s2, l2) = f(h, s, l)
        if (s2 <= 0f) {
            val v = (l2 * 255f + 0.5f).toInt().coerceIn(0, 255)
            return (v shl 16) or (v shl 8) or v
        }
        val q = if (l2 < 0.5f) l2 * (1f + s2) else l2 + s2 - l2 * s2
        val p = 2f * l2 - q
        fun hue(tt: Float): Float {
            var t = tt
            if (t < 0f) t += 1f
            if (t > 1f) t -= 1f
            return when {
                t < 1f / 6f -> p + (q - p) * 6f * t
                t < 0.5f -> q
                t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
                else -> p
            }
        }
        fun to(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (to(hue(h2 + 1f / 3f)) shl 16) or (to(hue(h2)) shl 8) or to(hue(h2 - 1f / 3f))
    }

    /** 色の不透明度(0〜255)がある色を、RGB だけにする(読みやすい比較用)。 */
    fun rgb(argb: Int) = argb and 0xFFFFFF
}

// ---------------------------------------------------------------- 文字・段落の書式(引き継ぎの部品)

internal class RunProps(
    val sizePt: Float? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strike: Boolean? = null,
    val baseline: Int? = null,
    val color: Int? = null,
    val latin: String? = null,
    val ea: String? = null,
) {
    /** top の書いてある項目で上書きした、新しい書式 */
    fun over(top: RunProps?): RunProps = if (top == null) this else RunProps(
        top.sizePt ?: sizePt, top.bold ?: bold, top.italic ?: italic, top.underline ?: underline,
        top.strike ?: strike, top.baseline ?: baseline, top.color ?: color, top.latin ?: latin, top.ea ?: ea,
    )
}

internal class ParaProps(
    val marL: Float? = null,
    val indent: Float? = null,
    val algn: String? = null,
    val lnPct: Float? = null,
    val lnPts: Float? = null,
    val befPct: Float? = null,
    val befPts: Float? = null,
    val aftPct: Float? = null,
    val aftPts: Float? = null,
    /** "none" / "char" / "auto" */
    val bu: String? = null,
    val buChar: String? = null,
    val buAuto: String? = null,
    val buStart: Int? = null,
    val buFont: String? = null,
    val buClr: Int? = null,
    val buSz: Float? = null,
    val def: RunProps = RunProps(),
) {
    fun over(t: ParaProps?): ParaProps {
        if (t == null) return this
        val ln = t.lnPct != null || t.lnPts != null
        val bef = t.befPct != null || t.befPts != null
        val aft = t.aftPct != null || t.aftPts != null
        val bu2 = t.bu != null
        return ParaProps(
            t.marL ?: marL, t.indent ?: indent, t.algn ?: algn,
            if (ln) t.lnPct else lnPct, if (ln) t.lnPts else lnPts,
            if (bef) t.befPct else befPct, if (bef) t.befPts else befPts,
            if (aft) t.aftPct else aftPct, if (aft) t.aftPts else aftPts,
            if (bu2) t.bu else bu, if (bu2) t.buChar else buChar, if (bu2) t.buAuto else buAuto, if (bu2) t.buStart else buStart,
            t.buFont ?: buFont, t.buClr ?: buClr, t.buSz ?: buSz,
            def.over(t.def),
        )
    }
}

/** 段の深さ(0〜8)ごとの書式 */
internal class ListStyle(val lvls: Array<ParaProps?>) {
    fun at(lvl: Int): ParaProps? = lvls.getOrNull(lvl)

    companion object {
        val EMPTY = ListStyle(arrayOfNulls(9))

        fun parse(n: XNode?, c: ColorCtx): ListStyle {
            if (n == null) return EMPTY
            val base = n.child("defPPr")?.let { SlideText.para(it, c) }
            val arr = arrayOfNulls<ParaProps>(9)
            for (i in 0 until 9) {
                val node = n.child("lvl${i + 1}pPr")
                val p = node?.let { SlideText.para(it, c) }
                arr[i] = if (base != null) base.over(p) else p
            }
            return ListStyle(arr)
        }
    }
}

internal object SlideText {
    private fun yes(s: String?): Boolean? = s?.let { it == "1" || it == "true" }

    fun run(n: XNode?, c: ColorCtx): RunProps {
        n ?: return RunProps()
        val th = c.theme
        val color = n.child("solidFill")?.let { SlideColor.fromHolder(it, c) }
        return RunProps(
            sizePt = n.int("sz")?.let { it / 100f },
            bold = yes(n.a("b")),
            italic = yes(n.a("i")),
            underline = n.a("u")?.let { it != "none" },
            strike = n.a("strike")?.let { it != "noStrike" },
            baseline = n.int("baseline")?.let { if (it > 0) 1 else if (it < 0) -1 else 0 },
            color = color,
            latin = th.font(n.child("latin")?.a("typeface")),
            ea = th.font(n.child("ea")?.a("typeface")),
        )
    }

    fun para(n: XNode?, c: ColorCtx): ParaProps {
        n ?: return ParaProps()
        var lnPct: Float? = null
        var lnPts: Float? = null
        var befPct: Float? = null
        var befPts: Float? = null
        var aftPct: Float? = null
        var aftPts: Float? = null
        var bu: String? = null
        var buChar: String? = null
        var buAuto: String? = null
        var buStart: Int? = null
        var buClr: Int? = null
        var buSz: Float? = null
        var buFont: String? = null
        var def = RunProps()
        for (k in n.kids) when (k.name) {
            "lnSpc" -> { k.child("spcPct")?.int("val")?.let { lnPct = it / 100000f }; k.child("spcPts")?.int("val")?.let { lnPts = it / 100f } }
            "spcBef" -> { k.child("spcPct")?.int("val")?.let { befPct = it / 100000f }; k.child("spcPts")?.int("val")?.let { befPts = it / 100f } }
            "spcAft" -> { k.child("spcPct")?.int("val")?.let { aftPct = it / 100000f }; k.child("spcPts")?.int("val")?.let { aftPts = it / 100f } }
            "buNone" -> bu = "none"
            "buChar" -> { bu = "char"; buChar = k.a("char") }
            "buAutoNum" -> { bu = "auto"; buAuto = k.a("type"); buStart = k.int("startAt") }
            "buClr" -> buClr = SlideColor.fromHolder(k, c)
            "buSzPct" -> buSz = (k.int("val") ?: 100000) / 100000f
            "buFont" -> buFont = k.a("typeface")
            "defRPr" -> def = run(k, c)
        }
        return ParaProps(
            marL = n.num("marL")?.let { (it / 12700.0).toFloat() },
            indent = n.num("indent")?.let { (it / 12700.0).toFloat() },
            algn = n.a("algn"),
            lnPct = lnPct, lnPts = lnPts, befPct = befPct, befPts = befPts, aftPct = aftPct, aftPts = aftPts,
            bu = bu, buChar = buChar, buAuto = buAuto, buStart = buStart, buFont = buFont, buClr = buClr, buSz = buSz,
            def = def,
        )
    }

    fun isCjk(s: String): Boolean = s.any { it.code >= 0x2E80 }

    /** 自動の番号の文字列(arabicPeriod → 「1.」など) */
    fun autoNumber(type: String?, n: Int): String {
        val t = type ?: "arabicPeriod"
        fun alpha(upper: Boolean): String {
            var v = n - 1
            val sb = StringBuilder()
            do {
                sb.insert(0, ('a' + v % 26))
                v = v / 26 - 1
            } while (v >= 0)
            val s = sb.toString()
            return if (upper) s.uppercase() else s
        }
        fun roman(upper: Boolean): String {
            val vals = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
            val syms = arrayOf("m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i")
            var v = n.coerceIn(1, 3999)
            val sb = StringBuilder()
            for (i in vals.indices) while (v >= vals[i]) { sb.append(syms[i]); v -= vals[i] }
            return if (upper) sb.toString().uppercase() else sb.toString()
        }
        val core = when {
            t.startsWith("arabic") -> n.toString()
            t.startsWith("alphaLc") -> alpha(false)
            t.startsWith("alphaUc") -> alpha(true)
            t.startsWith("romanLc") -> roman(false)
            t.startsWith("romanUc") -> roman(true)
            t.startsWith("circleNum") -> if (n in 1..20) (0x2460 + n - 1).toChar().toString() else n.toString()
            t.startsWith("ea1Jpn") || t.startsWith("ea1Chs") -> n.toString().map { (it.code - '0'.code + 0xFF10).toChar() }.joinToString("")
            else -> n.toString()
        }
        return when {
            t.endsWith("ParenBoth") -> "($core)"
            t.endsWith("ParenR") -> "$core)"
            t.endsWith("Period") -> "$core."
            t.startsWith("circleNum") -> core
            else -> core
        }
    }

    /** 箇条書きの記号の文字(Wingdings などの記号の文字を、普通の文字にする) */
    fun bulletChar(ch: String?, font: String?): String {
        val c = ch?.takeIf { it.isNotEmpty() } ?: return "•"
        val f = (font ?: "").lowercase()
        if (f.startsWith("wingdings")) {
            return when (c[0]) {
                '§', '' -> "▪"
                'Ø', '' -> "➢"
                'ü', '' -> "✓"
                'q', '' -> "❑"
                'l', '' -> "●"
                'n', '' -> "■"
                'v', '' -> "❖"
                'u', '' -> "◆"
                'p', '' -> "■"
                else -> "•"
            }
        }
        if (f == "symbol") return if (c[0] == '·' || c[0] == '') "•" else c
        return when (c[0]) {
            '' -> "•"
            '' -> "▪"
            '' -> "➢"
            '' -> "✓"
            else -> c
        }
    }
}
