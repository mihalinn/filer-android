package local.filemanager

import kotlin.math.max

// ---------------------------------------------------------------- スライドの中身(読み取った結果)。Android に依存しない。座標は pt(1/72 インチ)。

internal sealed interface Paint {
    data object None : Paint
    class Solid(val argb: Int) : Paint
    class Grad(val stops: List<Pair<Float, Int>>, val angle: Float) : Paint
    class Blip(val path: String) : Paint
}

internal class SLine(val argb: Int, val widthPt: Float, val dash: FloatArray?, val head: Boolean, val tail: Boolean)

internal class SRun(
    val text: String,
    val sizePt: Float,
    val color: Int,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val strike: Boolean,
    val baseline: Int,
    val font: String?,
    val link: String?,
)

internal class SPara(
    val runs: List<SRun>,
    /** -1 左 / 0 中央 / 1 右 / 2 両端 */
    val algn: Int,
    val marL: Float,
    val indent: Float,
    val beforePt: Float,
    val afterPt: Float,
    val lineMult: Float?,
    val linePt: Float?,
    val marker: String?,
    val markerColor: Int?,
    val markerScale: Float,
    val sizePt: Float,
) {
    val hasText: Boolean get() = runs.any { it.text.isNotBlank() }
}

internal class STextBody(
    val paras: List<SPara>,
    /** 0 上 / 1 中央 / 2 下 */
    val anchor: Int,
    val l: Float,
    val t: Float,
    val r: Float,
    val b: Float,
    val wrap: Boolean,
    val vert: String?,
) {
    val hasText: Boolean get() = paras.any { it.hasText }
}

internal class STableCell(
    val body: STextBody,
    val fill: Int?,
    val gridSpan: Int,
    val rowSpan: Int,
    val hMerge: Boolean,
    val vMerge: Boolean,
    val left: SLine?,
    val top: SLine?,
    val right: SLine?,
    val bottom: SLine?,
)

internal class STable(val colW: List<Float>, val rowH: List<Float>, val rows: List<List<STableCell>>)

internal class SPicture(val path: String, val cropL: Float, val cropT: Float, val cropR: Float, val cropB: Float)

internal class SShape(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val rot: Float,
    val flipH: Boolean,
    val flipV: Boolean,
    val geom: Geom?,
    val fill: Paint?,
    val line: SLine?,
    val text: STextBody?,
    val picture: SPicture?,
    val table: STable?,
)

internal class SlideDoc(val number: Int, val hidden: Boolean, val background: Paint?, val shapes: List<SShape>)

// ---- 文字のコピー用(スライドの文字だけを取り出す)

internal fun STextBody.plainText(): String =
    paras.joinToString("\n") { p -> (p.marker?.let { "$it " } ?: "") + p.runs.joinToString("") { it.text } }

internal fun STable.plainText(): String =
    rows.joinToString("\n") { row -> row.joinToString("\t") { c -> CopyText.tsvCell(c.body.plainText()) } }

/** 1 枚のスライドの文字(図形の文字・表)。上から順に並んだ図形の順で。 */
internal fun SlideDoc.plainText(): String =
    shapes.mapNotNull { sh -> sh.text?.takeIf { it.hasText }?.plainText() ?: sh.table?.plainText()?.takeIf { it.isNotBlank() } }.joinToString("\n")

internal fun SlideDeck.plainText(): String = slides.joinToString("\n\n") { "スライド ${it.number}\n" + it.plainText() }

internal class SlideDeck(val widthPt: Float, val heightPt: Float, val slides: List<SlideDoc>)

// ---------------------------------------------------------------- 読み取り

internal object SlideDeckParser {
    private const val EMU = 12700f

    private class Part(val path: String, val root: XNode, val rels: Map<String, String>)

    private class Xf(val sx: Float, val sy: Float, val tx: Float, val ty: Float) {
        companion object { val ID = Xf(1f, 1f, 0f, 0f) }
    }

    private class Box(val x: Float, val y: Float, val w: Float, val h: Float, val rot: Float, val flipH: Boolean, val flipV: Boolean)

    private class PhRef(val type: String?, val idx: Int?)

    private class BodyProps(
        val anchor: String? = null, val l: Float? = null, val t: Float? = null, val r: Float? = null, val b: Float? = null,
        val wrap: String? = null, val fontScale: Float? = null, val lnRed: Float? = null, val vert: String? = null,
    ) {
        fun over(o: BodyProps?): BodyProps = if (o == null) this else BodyProps(
            o.anchor ?: anchor, o.l ?: l, o.t ?: t, o.r ?: r, o.b ?: b, o.wrap ?: wrap, o.fontScale ?: fontScale, o.lnRed ?: lnRed, o.vert ?: vert,
        )

        companion object {
            fun parse(n: XNode?): BodyProps {
                n ?: return BodyProps()
                val fit = n.child("normAutofit")
                return BodyProps(
                    n.a("anchor"),
                    n.num("lIns")?.let { (it / EMU).toFloat() }, n.num("tIns")?.let { (it / EMU).toFloat() },
                    n.num("rIns")?.let { (it / EMU).toFloat() }, n.num("bIns")?.let { (it / EMU).toFloat() },
                    n.a("wrap"),
                    fit?.num("fontScale")?.let { (it / 100000.0).toFloat() },
                    fit?.num("lnSpcReduction")?.let { (it / 100000.0).toFloat() },
                    n.a("vert"),
                )
            }
        }
    }

    /** 1 枚のスライドを描くための材料(テーマ・書式の既定・マスター側の場所) */
    private class Ctx(
        val color: ColorCtx,
        val defaults: ListStyle,
        val txStyles: Map<String, ListStyle>,
        val layoutPhs: List<XNode>,
        val masterPhs: List<XNode>,
        val slideNumber: Int,
        val tableStyles: Map<String, XNode>,
    )

    fun parse(read: (String) -> ByteArray?): SlideDeck {
        val presBytes = read("ppt/presentation.xml") ?: throw OpException("PowerPointのファイルとして読めません")
        val pres = XmlTree.parse(presBytes)
        val presRels = OfficeXml.relationships(read("ppt/_rels/presentation.xml.rels"), "ppt")
        val sz = pres.child("sldSz")
        val wPt = (sz?.num("cx") ?: 9144000.0).toFloat() / EMU
        val hPt = (sz?.num("cy") ?: 6858000.0).toFloat() / EMU
        val order = pres.child("sldIdLst")?.children("sldId")?.mapNotNull { presRels[it.rid()] } ?: emptyList()
        val defaultStyle = pres.child("defaultTextStyle")
        val tableStyles = HashMap<String, XNode>()
        read("ppt/tableStyles.xml")?.let { b ->
            runCatching { XmlTree.parse(b) }.getOrNull()?.children("tblStyle")?.forEach { s -> s.a("styleId")?.let { tableStyles[it] = s } }
        }
        val cache = HashMap<String, Part?>()
        fun load(path: String): Part? = cache.getOrPut(path) {
            val b = read(path) ?: return@getOrPut null
            val dir = path.substringBeforeLast('/', "")
            val name = path.substringAfterLast('/')
            val rels = OfficeXml.relationships(read("$dir/_rels/$name.rels"), dir)
            runCatching { Part(path, XmlTree.parse(b), rels) }.getOrNull()
        }
        val themes = HashMap<String, SlideTheme>()
        val slides = order.mapIndexed { i, path ->
            val part = load(path)
            if (part == null) SlideDoc(i + 1, false, null, emptyList()) else buildSlide(i + 1, part, ::load, themes, read, defaultStyle, tableStyles)
        }
        return SlideDeck(wPt, hPt, slides)
    }

    private fun clrMapOf(n: XNode?): Map<String, String>? {
        n ?: return null
        val m = n.attrs.filterKeys { !it.contains(':') }
        return if (m.isEmpty()) null else m
    }

    private fun buildSlide(
        number: Int,
        slide: Part,
        load: (String) -> Part?,
        themes: HashMap<String, SlideTheme>,
        read: (String) -> ByteArray?,
        defaultStyle: XNode?,
        tableStyles: Map<String, XNode>,
    ): SlideDoc {
        val layout = slide.rels.values.firstOrNull { it.startsWith("ppt/slideLayouts/") }?.let(load)
        val master = layout?.rels?.values?.firstOrNull { it.startsWith("ppt/slideMasters/") }?.let(load)
        val themePath = master?.rels?.values?.firstOrNull { it.startsWith("ppt/theme/") }
        val theme = if (themePath == null) SlideTheme.DEFAULT else themes.getOrPut(themePath) { SlideTheme.parse(read(themePath)) }
        val clrMap = clrMapOf(slide.root.child("clrMapOvr")?.child("overrideClrMapping"))
            ?: clrMapOf(layout?.root?.child("clrMapOvr")?.child("overrideClrMapping"))
            ?: clrMapOf(master?.root?.child("clrMap"))
            ?: SlideTheme.DEFAULT_CLRMAP
        val cc = ColorCtx(theme, clrMap)
        val txs = master?.root?.child("txStyles")
        val txStyles = mapOf(
            "title" to ListStyle.parse(txs?.child("titleStyle"), cc),
            "body" to ListStyle.parse(txs?.child("bodyStyle"), cc),
            "other" to ListStyle.parse(txs?.child("otherStyle"), cc),
        )
        fun phsOf(p: Part?): List<XNode> {
            val tree = p?.root?.child("cSld")?.child("spTree") ?: return emptyList()
            return tree.children("sp").filter { placeholder(it) != null }
        }
        val ctx = Ctx(cc, ListStyle.parse(defaultStyle, cc), txStyles, phsOf(layout), phsOf(master), number, tableStyles)
        val shapes = ArrayList<SShape>()
        // 下から、マスターの飾り → レイアウトの飾り → スライド(場所取りの枠は、スライドのものだけを描く)
        val slideShows = slide.root.a("showMasterSp") != "0"
        val layoutShows = layout?.root?.a("showMasterSp") != "0"
        if (slideShows && layoutShows && master != null) treeOf(master)?.let { convertTree(it, master, Xf.ID, ctx, true, shapes) }
        if (slideShows && layout != null) treeOf(layout)?.let { convertTree(it, layout, Xf.ID, ctx, true, shapes) }
        treeOf(slide)?.let { convertTree(it, slide, Xf.ID, ctx, false, shapes) }
        val bg = background(slide, ctx) ?: background(layout, ctx) ?: background(master, ctx)
        return SlideDoc(number, slide.root.a("show") == "0", bg, shapes)
    }

    private fun treeOf(p: Part): XNode? = p.root.child("cSld")?.child("spTree")

    private fun background(p: Part?, c: Ctx): Paint? {
        p ?: return null
        val bg = p.root.child("cSld")?.child("bg") ?: return null
        bg.child("bgPr")?.let { pr -> explicitFill(pr, p, c.color)?.let { return it } }
        bg.child("bgRef")?.let { r -> SlideColor.fromHolder(r, c.color)?.let { return Paint.Solid(it) } }
        return null
    }

    // ------------------------------------------------------------ 図形の木をたどる

    private fun convertTree(tree: XNode, part: Part, xf: Xf, c: Ctx, skipPh: Boolean, out: MutableList<SShape>) {
        for (k in tree.kids) {
            try {
                when (k.name) {
                    "sp", "cxnSp" -> convertSp(k, part, xf, c, skipPh)?.let(out::add)
                    "pic" -> convertPic(k, part, xf, c)?.let(out::add)
                    "graphicFrame" -> convertFrame(k, part, xf, c)?.let(out::add)
                    "grpSp" -> {
                        val gx = k.child("grpSpPr")?.child("xfrm")
                        val off = gx?.child("off")
                        val ext = gx?.child("ext")
                        val chOff = gx?.child("chOff")
                        val chExt = gx?.child("chExt")
                        var nx = xf
                        if (off != null && ext != null && chOff != null && chExt != null) {
                            val cw = chExt.num("cx") ?: 0.0
                            val ch = chExt.num("cy") ?: 0.0
                            val gsx = if (cw > 0.0) ((ext.num("cx") ?: cw) / cw).toFloat() else 1f
                            val gsy = if (ch > 0.0) ((ext.num("cy") ?: ch) / ch).toFloat() else 1f
                            val ox = ((off.num("x") ?: 0.0) / EMU).toFloat()
                            val oy = ((off.num("y") ?: 0.0) / EMU).toFloat()
                            val cx0 = ((chOff.num("x") ?: 0.0) / EMU).toFloat()
                            val cy0 = ((chOff.num("y") ?: 0.0) / EMU).toFloat()
                            nx = Xf(xf.sx * gsx, xf.sy * gsy, xf.tx + xf.sx * (ox - cx0 * gsx), xf.ty + xf.sy * (oy - cy0 * gsy))
                        }
                        convertTree(k, part, nx, c, skipPh, out)
                    }
                    "AlternateContent" -> k.child("Choice")?.let { convertTree(it, part, xf, c, skipPh, out) }
                }
            } catch (e: OutOfMemoryError) {
                throw e
            } catch (e: Exception) {
                // 1 つの図形が読めなくても、ほかは出す
            }
        }
    }

    private fun placeholder(sp: XNode): PhRef? {
        val ph = sp.child("nvSpPr")?.child("nvPr")?.child("ph") ?: return null
        return PhRef(ph.a("type"), ph.int("idx"))
    }

    private fun phClass(type: String?): String = when (type) {
        "title", "ctrTitle" -> "title"
        "dt", "ftr", "sldNum" -> type
        else -> "body"
    }

    private fun findPh(list: List<XNode>, ref: PhRef, byIdx: Boolean): XNode? {
        if (byIdx && ref.idx != null) {
            list.firstOrNull { placeholder(it)?.idx == ref.idx && phClass(placeholder(it)?.type) == phClass(ref.type) }?.let { return it }
            list.firstOrNull { placeholder(it)?.idx == ref.idx }?.let { return it }
        }
        return list.firstOrNull { phClass(placeholder(it)?.type) == phClass(ref.type) }
    }

    private fun readXfrm(x: XNode?): Box? {
        x ?: return null
        val off = x.child("off") ?: return null
        val ext = x.child("ext") ?: return null
        return Box(
            ((off.num("x") ?: 0.0) / EMU).toFloat(), ((off.num("y") ?: 0.0) / EMU).toFloat(),
            ((ext.num("cx") ?: 0.0) / EMU).toFloat(), ((ext.num("cy") ?: 0.0) / EMU).toFloat(),
            ((x.num("rot") ?: 0.0) / 60000.0).toFloat(), x.flag("flipH") == true, x.flag("flipV") == true,
        )
    }

    private fun place(b: Box, xf: Xf): Box = Box(xf.tx + xf.sx * b.x, xf.ty + xf.sy * b.y, b.w * xf.sx, b.h * xf.sy, b.rot, b.flipH, b.flipV)

    private fun geomOf(spPr: XNode?): Geom? {
        spPr ?: return null
        spPr.child("prstGeom")?.let { g ->
            val adj = HashMap<String, Float>()
            g.child("avLst")?.children("gd")?.forEach { gd ->
                val f = gd.a("fmla") ?: return@forEach
                val v = f.removePrefix("val ").trim().toFloatOrNull() ?: return@forEach
                gd.a("name")?.let { adj[it] = v }
            }
            return Geom(g.a("prst") ?: "rect", adj)
        }
        spPr.child("custGeom")?.let { g ->
            val paths = g.child("pathLst")?.children("path")?.map { p ->
                val cmds = ArrayList<CustCmd>()
                fun pt(n: XNode?): Pair<Float, Float> = ((n?.num("x") ?: 0.0).toFloat()) to ((n?.num("y") ?: 0.0).toFloat())
                for (k in p.kids) when (k.name) {
                    "moveTo" -> pt(k.child("pt")).let { cmds.add(CustCmd.Move(it.first, it.second)) }
                    "lnTo" -> pt(k.child("pt")).let { cmds.add(CustCmd.Line(it.first, it.second)) }
                    "cubicBezTo" -> {
                        val ps = k.children("pt").map { pt(it) }
                        if (ps.size >= 3) cmds.add(CustCmd.Cubic(ps[0].first, ps[0].second, ps[1].first, ps[1].second, ps[2].first, ps[2].second))
                    }
                    "quadBezTo" -> {
                        val ps = k.children("pt").map { pt(it) }
                        if (ps.size >= 2) cmds.add(CustCmd.Quad(ps[0].first, ps[0].second, ps[1].first, ps[1].second))
                    }
                    "arcTo" -> cmds.add(CustCmd.Arc((k.num("wR") ?: 0.0).toFloat(), (k.num("hR") ?: 0.0).toFloat(), ((k.num("stAng") ?: 0.0) / 60000.0).toFloat(), ((k.num("swAng") ?: 0.0) / 60000.0).toFloat()))
                    "close" -> cmds.add(CustCmd.Close)
                }
                CustPath((p.num("w") ?: 0.0).toFloat(), (p.num("h") ?: 0.0).toFloat(), cmds, p.a("fill") != "none", p.a("stroke") != "0" && p.a("stroke") != "false")
            } ?: emptyList()
            return Geom("cust", emptyMap(), paths)
        }
        return null
    }

    // ------------------------------------------------------------ 塗り・線

    private fun explicitFill(n: XNode?, part: Part, cc: ColorCtx): Paint? {
        n ?: return null
        for (k in n.kids) {
            when (k.name) {
                "noFill" -> return Paint.None
                "solidFill" -> SlideColor.fromHolder(k, cc)?.let { return Paint.Solid(it) }
                "gradFill" -> {
                    val stops = k.child("gsLst")?.children("gs")?.mapNotNull { gs ->
                        val col = SlideColor.fromHolder(gs, cc) ?: return@mapNotNull null
                        ((gs.int("pos") ?: 0) / 100000f) to col
                    } ?: emptyList()
                    if (stops.isEmpty()) continue
                    if (stops.size == 1) return Paint.Solid(stops[0].second)
                    val ang = k.child("lin")?.num("ang")?.let { (it / 60000.0).toFloat() } ?: 90f
                    return Paint.Grad(stops, ang)
                }
                "blipFill" -> {
                    val path = k.child("blip")?.rid("embed")?.let { part.rels[it] }
                    if (path != null) return Paint.Blip(path)
                }
                "pattFill" -> SlideColor.fromHolder(k.child("fgClr"), cc)?.let { return Paint.Solid(it) }
            }
        }
        return null
    }

    private fun dashOf(name: String?): FloatArray? = when (name) {
        "dash", "sysDash" -> floatArrayOf(4f, 3f)
        "dot", "sysDot" -> floatArrayOf(1f, 3f)
        "lgDash" -> floatArrayOf(8f, 3f)
        "dashDot", "sysDashDot" -> floatArrayOf(4f, 3f, 1f, 3f)
        "lgDashDot" -> floatArrayOf(8f, 3f, 1f, 3f)
        "lgDashDotDot", "sysDashDotDot" -> floatArrayOf(8f, 3f, 1f, 3f, 1f, 3f)
        else -> null
    }

    /** ln(線)と、スタイルの lnRef から、線を決める。無い(線なし)ときは null。 */
    private fun lineOf(ln: XNode?, lnRef: XNode?, part: Part, cc: ColorCtx): SLine? {
        var color: Int? = null
        var width: Float? = null
        if (lnRef != null) {
            val idx = lnRef.int("idx") ?: 0
            if (idx > 0) {
                color = SlideColor.fromHolder(lnRef, cc)
                width = cc.theme.lineWidths.getOrNull(idx - 1) ?: 1f
            }
        }
        var head = false
        var tail = false
        var dash: FloatArray? = null
        if (ln != null) {
            ln.num("w")?.let { width = (it / EMU).toFloat() }
            for (k in ln.kids) when (k.name) {
                "noFill" -> return null
                "solidFill" -> SlideColor.fromHolder(k, cc)?.let { color = it }
                "gradFill" -> (explicitFill(ln, part, cc) as? Paint.Grad)?.let { color = it.stops.first().second }
                "prstDash" -> dash = dashOf(k.a("val"))
                "headEnd" -> head = k.a("type").let { it != null && it != "none" }
                "tailEnd" -> tail = k.a("type").let { it != null && it != "none" }
            }
        }
        val col = color ?: return null
        val w = width ?: 0.75f
        val d = dash?.let { a -> FloatArray(a.size) { a[it] * max(w, 1f) } }
        return SLine(col, w, d, head, tail)
    }

    // ------------------------------------------------------------ sp / cxnSp

    private fun convertSp(sp: XNode, part: Part, xf: Xf, c: Ctx, skipPh: Boolean): SShape? {
        val ph = placeholder(sp)
        if (ph != null && skipPh) return null
        val chain = ArrayList<XNode>()
        chain.add(sp)
        var layoutSp: XNode? = null
        var masterSp: XNode? = null
        if (ph != null) {
            layoutSp = findPh(c.layoutPhs, ph, true)
            masterSp = findPh(c.masterPhs, layoutSp?.let { placeholder(it) } ?: ph, false)
            layoutSp?.let(chain::add)
            masterSp?.let(chain::add)
        }
        val box = place(chain.firstNotNullOfOrNull { readXfrm(it.child("spPr")?.child("xfrm")) } ?: return null, xf)
        val geom = chain.firstNotNullOfOrNull { geomOf(it.child("spPr")) } ?: Geom(if (sp.name == "cxnSp") "line" else "rect")
        val style = sp.child("style")
        val cc = c.color
        var fill: Paint? = null
        for (n in chain) {
            fill = explicitFill(n.child("spPr"), part, cc)
            if (fill != null) break
        }
        if (fill == null && style != null) {
            val fr = style.child("fillRef")
            if ((fr?.int("idx") ?: 0) > 0) SlideColor.fromHolder(fr, cc)?.let { fill = Paint.Solid(it) }
        }
        val lnNode = chain.firstNotNullOfOrNull { it.child("spPr")?.child("ln") }
        val line = lineOf(lnNode, style?.child("lnRef"), part, cc)
        var fontColor: Int? = null
        style?.child("fontRef")?.let { fontColor = SlideColor.fromHolder(it, cc) }
        val text = sp.child("txBody")?.let { tx ->
            val body = listOfNotNull(masterSp, layoutSp, sp)
            var bp = BodyProps()
            for (n in body) bp = bp.over(BodyProps.parse(n.child("txBody")?.child("bodyPr")))
            val lsts = body.map { ListStyle.parse(it.child("txBody")?.child("lstStyle"), cc) }
            val base = ph?.let { c.txStyles[phClass(it.type).let { k -> if (k == "title" || k == "body") k else "other" }] }
            textBody(tx, c, part, lsts, base, fontColor?.let { RunProps(color = it) }, bp, true)
        }
        val hasFill = fill != null && fill !is Paint.None
        if (!hasFill && line == null && text == null) return null
        return SShape(box.x, box.y, box.w, box.h, box.rot, box.flipH, box.flipV, geom, fill, line, text, null, null)
    }

    // ------------------------------------------------------------ 文字

    private val EMPTY_LST = ListStyle.EMPTY

    private fun textBody(
        tx: XNode,
        c: Ctx,
        part: Part,
        lsts: List<ListStyle>,
        base: ListStyle?,
        extra: RunProps?,
        bp: BodyProps,
        isShape: Boolean,
    ): STextBody? {
        val scale = bp.fontScale ?: 1f
        val counters = IntArray(9)
        val paras = ArrayList<SPara>()
        for (p in tx.children("p")) {
            val pPr = p.child("pPr")
            val lvl = (pPr?.int("lvl") ?: 0).coerceIn(0, 8)
            var props = (c.defaults.at(lvl) ?: ParaProps())
            if (extra != null) props = props.over(ParaProps(def = extra))
            props = props.over(base?.at(lvl))
            for (l in lsts) props = props.over(l.at(lvl))
            props = props.over(SlideText.para(pPr, c.color))
            val runs = ArrayList<SRun>()
            for (k in p.kids) {
                val isBr = k.name == "br"
                if (k.name != "r" && k.name != "fld" && !isBr) continue
                val rPr = k.child("rPr")
                var t = if (isBr) "\n" else (k.child("t")?.text?.toString() ?: "")
                if (k.name == "fld" && (k.a("type") ?: "").startsWith("slidenum")) t = c.slideNumber.toString()
                if (t.isEmpty()) continue
                val def = props.def.over(SlideText.run(rPr, c.color))
                val link = rPr?.child("hlinkClick")?.rid("id")?.let { part.rels[it] }?.takeIf { it.startsWith("http") || it.startsWith("mailto:") }
                val size = (def.sizePt ?: 18f) * scale
                val color = if (link != null && def.color == null) (c.color.theme.colors["hlink"] ?: 0x0563C1) or (0xFF shl 24) else (def.color ?: ((c.color.theme.scheme("tx1", c.color.clrMap)) or (0xFF shl 24)))
                val font = if (SlideText.isCjk(t)) (def.ea ?: def.latin) else (def.latin ?: def.ea)
                runs.add(SRun(t, size, color, def.bold == true, def.italic == true, def.underline == true || link != null, def.strike == true, def.baseline ?: 0, font, link))
            }
            val hasText = runs.any { it.text.isNotBlank() }
            val endSize = (SlideText.run(p.child("endParaRPr"), c.color).sizePt ?: props.def.sizePt ?: 18f) * scale
            val size = runs.maxOfOrNull { it.sizePt } ?: endSize
            // 箇条書き
            var marker: String? = null
            if (hasText && props.bu == "char") {
                marker = SlideText.bulletChar(props.buChar, props.buFont)
            } else if (hasText && props.bu == "auto") {
                counters[lvl] = if (counters[lvl] == 0) (props.buStart ?: 1) else counters[lvl] + 1
                for (j in lvl + 1 until 9) counters[j] = 0
                marker = SlideText.autoNumber(props.buAuto, counters[lvl])
            } else {
                for (j in lvl until 9) counters[j] = 0
            }
            val red = bp.lnRed ?: 0f
            val lnMult = props.lnPct?.let { it * (1f - red) } ?: if (red > 0f) (1f - red) else null
            val before = props.befPts ?: props.befPct?.let { it * size * 1.2f } ?: 0f
            val after = props.aftPts ?: props.aftPct?.let { it * size * 1.2f } ?: 0f
            paras.add(
                SPara(
                    runs, when (props.algn) { "ctr" -> 0; "r" -> 1; "just", "dist" -> 2; else -> -1 },
                    props.marL ?: 0f, props.indent ?: 0f, before, after, lnMult, props.lnPts,
                    marker, if (marker != null) (props.buClr ?: runs.firstOrNull()?.color) else null, props.buSz ?: 1f, size,
                ),
            )
        }
        if (paras.none { it.hasText }) return null
        val anchor = when (bp.anchor) { "ctr" -> 1; "b" -> 2; else -> 0 }
        return STextBody(paras, anchor, bp.l ?: 7.2f, bp.t ?: 3.6f, bp.r ?: 7.2f, bp.b ?: 3.6f, bp.wrap != "none", bp.vert)
    }

    // ------------------------------------------------------------ 画像・表・グラフ

    private fun convertPic(pic: XNode, part: Part, xf: Xf, c: Ctx): SShape? {
        val spPr = pic.child("spPr")
        val box = place(readXfrm(spPr?.child("xfrm")) ?: return null, xf)
        val bf = pic.child("blipFill")
        val path = bf?.child("blip")?.rid("embed")?.let { part.rels[it] }
        val src = bf?.child("srcRect")
        fun f(k: String) = (src?.num(k) ?: 0.0).toFloat() / 100000f
        val line = lineOf(spPr?.child("ln"), null, part, c.color)
        val geom = geomOf(spPr) ?: Geom("rect")
        val picture = if (path != null) SPicture(path, f("l"), f("t"), f("r"), f("b")) else null
        return SShape(box.x, box.y, box.w, box.h, box.rot, box.flipH, box.flipV, geom, if (picture == null) Paint.Solid(0xFFE8E8E8.toInt()) else null, line, null, picture, null)
    }

    private fun convertFrame(frame: XNode, part: Part, xf: Xf, c: Ctx): SShape? {
        val box = place(readXfrm(frame.child("xfrm")) ?: return null, xf)
        val data = frame.child("graphic")?.child("graphicData")
        val tbl = data?.child("tbl")
        if (tbl != null) {
            val t = table(tbl, c, part)
            return SShape(box.x, box.y, box.w, box.h, 0f, false, false, null, null, null, null, null, t)
        }
        val uri = data?.a("uri") ?: ""
        val label = when {
            uri.contains("chart") -> "グラフ(この表示では描けません)"
            uri.contains("diagram") -> "図表(SmartArt。この表示では描けません)"
            else -> "埋め込みの内容(この表示では描けません)"
        }
        val run = SRun(label, 14f, 0xFF7F7F7F.toInt(), false, false, false, false, 0, null, null)
        val para = SPara(listOf(run), 0, 0f, 0f, 0f, 0f, null, null, null, null, 1f, 14f)
        return SShape(
            box.x, box.y, box.w, box.h, 0f, false, false, Geom("rect"), Paint.Solid(0xFFF3F3F3.toInt()),
            SLine(0xFFBBBBBB.toInt(), 1f, floatArrayOf(4f, 3f), false, false),
            STextBody(listOf(para), 1, 7.2f, 3.6f, 7.2f, 3.6f, true, null), null, null,
        )
    }

    private class TblPart(val fill: Int?, val bdr: Map<String, SLine?>, val bold: Boolean?, val italic: Boolean?, val color: Int?)

    private fun tblPart(n: XNode?, part: Part, cc: ColorCtx): TblPart? {
        n ?: return null
        val tx = n.child("tcTxStyle")
        val st = n.child("tcStyle")
        val fill = (explicitFill(st?.child("fill"), part, cc) as? Paint.Solid)?.argb
        val bdr = HashMap<String, SLine?>()
        st?.child("tcBdr")?.kids?.forEach { s -> bdr[s.name] = lineOf(s.child("ln"), null, part, cc) }
        return TblPart(fill, bdr, tx?.a("b")?.let { it == "on" }, tx?.a("i")?.let { it == "on" }, SlideColor.fromHolder(tx, cc))
    }

    /** よく使われる「中間スタイル 2」系を、ファイルに定義が無いときの代わりに使う。 */
    private fun builtinStyle(id: String?, part: Part): XNode? {
        val accent = when (id) {
            "{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}" -> "accent1"
            "{21E4AEA4-8DFA-4A89-87EB-49C32662AFE0}" -> "accent2"
            "{F5AB1C69-6EDB-4FF4-983F-18BD219EF322}" -> "accent3"
            "{00A15C55-8517-42AA-B614-E9B94910E393}" -> "accent4"
            "{7DF18680-E054-41AD-8BC1-D1AEF772440D}" -> "accent5"
            "{93296810-A885-4BE3-A3E7-6D5BEEA58F35}" -> "accent6"
            else -> null
        }
        if (accent != null) {
            fun clr(c: String, extra: String = "") = "<a:schemeClr val=\"$c\">$extra</a:schemeClr>"
            fun ln(w: Int) = "<a:ln w=\"$w\"><a:solidFill>${clr("lt1")}</a:solidFill></a:ln>"
            val xml = "<a:tblStyle xmlns:a=\"x\">" +
                "<a:wholeTbl><a:tcTxStyle>${clr("dk1")}</a:tcTxStyle><a:tcStyle><a:tcBdr>" +
                "<a:left>${ln(12700)}</a:left><a:right>${ln(12700)}</a:right><a:top>${ln(12700)}</a:top><a:bottom>${ln(12700)}</a:bottom>" +
                "<a:insideH>${ln(12700)}</a:insideH><a:insideV>${ln(12700)}</a:insideV></a:tcBdr>" +
                "<a:fill><a:solidFill>${clr(accent, "<a:tint val=\"20000\"/>")}</a:solidFill></a:fill></a:tcStyle></a:wholeTbl>" +
                "<a:band1H><a:tcStyle><a:tcBdr/><a:fill><a:solidFill>${clr(accent, "<a:tint val=\"40000\"/>")}</a:solidFill></a:fill></a:tcStyle></a:band1H>" +
                "<a:band1V><a:tcStyle><a:tcBdr/><a:fill><a:solidFill>${clr(accent, "<a:tint val=\"40000\"/>")}</a:solidFill></a:fill></a:tcStyle></a:band1V>" +
                "<a:lastCol><a:tcTxStyle b=\"on\">${clr("lt1")}</a:tcTxStyle><a:tcStyle><a:tcBdr/><a:fill><a:solidFill>${clr(accent)}</a:solidFill></a:fill></a:tcStyle></a:lastCol>" +
                "<a:firstCol><a:tcTxStyle b=\"on\">${clr("lt1")}</a:tcTxStyle><a:tcStyle><a:tcBdr/><a:fill><a:solidFill>${clr(accent)}</a:solidFill></a:fill></a:tcStyle></a:firstCol>" +
                "<a:lastRow><a:tcTxStyle b=\"on\">${clr("lt1")}</a:tcTxStyle><a:tcStyle><a:tcBdr><a:top>${ln(38100)}</a:top></a:tcBdr><a:fill><a:solidFill>${clr(accent)}</a:solidFill></a:fill></a:tcStyle></a:lastRow>" +
                "<a:firstRow><a:tcTxStyle b=\"on\">${clr("lt1")}</a:tcTxStyle><a:tcStyle><a:tcBdr><a:bottom>${ln(38100)}</a:bottom></a:tcBdr><a:fill><a:solidFill>${clr(accent)}</a:solidFill></a:fill></a:tcStyle></a:firstRow>" +
                "</a:tblStyle>"
            return runCatching { XmlTree.parse(xml.toByteArray()) }.getOrNull()
        }
        if (id == "{5940675A-B579-460E-94D1-54222C63F5DA}") {
            val l = "<a:ln w=\"12700\"><a:solidFill><a:schemeClr val=\"tx1\"/></a:solidFill></a:ln>"
            val xml = "<a:tblStyle xmlns:a=\"x\"><a:wholeTbl><a:tcStyle><a:tcBdr><a:left>$l</a:left><a:right>$l</a:right><a:top>$l</a:top><a:bottom>$l</a:bottom><a:insideH>$l</a:insideH><a:insideV>$l</a:insideV></a:tcBdr></a:tcStyle></a:wholeTbl></a:tblStyle>"
            return runCatching { XmlTree.parse(xml.toByteArray()) }.getOrNull()
        }
        return null
    }

    private fun table(tbl: XNode, c: Ctx, part: Part): STable {
        val pr = tbl.child("tblPr")
        val firstRow = pr?.flag("firstRow") == true
        val lastRow = pr?.flag("lastRow") == true
        val firstCol = pr?.flag("firstCol") == true
        val lastCol = pr?.flag("lastCol") == true
        val bandRow = pr?.flag("bandRow") == true
        val bandCol = pr?.flag("bandCol") == true
        val styleId = pr?.child("tableStyleId")?.text?.toString()?.trim()
        val styleNode = (if (styleId != null) c.tableStyles[styleId] else null) ?: builtinStyle(styleId, part)
        val cc = c.color
        val parts: Map<String, TblPart?> = if (styleNode == null) emptyMap() else
            listOf("wholeTbl", "band1H", "band2H", "band1V", "band2V", "firstCol", "lastCol", "firstRow", "lastRow").associateWith { tblPart(styleNode.child(it), part, cc) }
        val colW = tbl.child("tblGrid")?.children("gridCol")?.map { ((it.num("w") ?: 0.0) / EMU).toFloat() } ?: emptyList()
        val trs = tbl.children("tr")
        val rowH = trs.map { ((it.num("h") ?: 0.0) / EMU).toFloat() }
        val nRows = trs.size
        val rows = trs.mapIndexed { ri, tr ->
            val tcs = tr.children("tc")
            tcs.mapIndexed { ci, tc ->
                val applied = ArrayList<TblPart>()
                parts["wholeTbl"]?.let(applied::add)
                if (bandRow && ri >= (if (firstRow) 1 else 0)) parts[if ((ri - (if (firstRow) 1 else 0)) % 2 == 0) "band1H" else "band2H"]?.let(applied::add)
                if (bandCol && ci >= (if (firstCol) 1 else 0)) parts[if ((ci - (if (firstCol) 1 else 0)) % 2 == 0) "band1V" else "band2V"]?.let(applied::add)
                if (firstCol && ci == 0) parts["firstCol"]?.let(applied::add)
                if (lastCol && ci == tcs.size - 1) parts["lastCol"]?.let(applied::add)
                if (firstRow && ri == 0) parts["firstRow"]?.let(applied::add)
                if (lastRow && ri == nRows - 1) parts["lastRow"]?.let(applied::add)
                var fill: Int? = null
                var bold: Boolean? = null
                var italic: Boolean? = null
                var color: Int? = null
                val sides = HashMap<String, SLine?>()
                for (a in applied) {
                    if (a.fill != null) fill = a.fill
                    a.bold?.let { bold = it }
                    a.italic?.let { italic = it }
                    a.color?.let { color = it }
                    for (side in listOf("left", "right", "top", "bottom")) {
                        val inner = when (side) {
                            "left" -> ci > 0
                            "right" -> ci < tcs.size - 1
                            "top" -> ri > 0
                            else -> ri < nRows - 1
                        }
                        val ins = if (side == "left" || side == "right") "insideV" else "insideH"
                        val key = if (a === parts["wholeTbl"]) (if (inner) ins else side) else if (a.bdr.containsKey(side)) side else if (inner) ins else side
                        if (a.bdr.containsKey(key)) sides[side] = a.bdr[key]
                    }
                }
                val tcPr = tc.child("tcPr")
                if (tcPr != null) {
                    when (val f = explicitFill(tcPr, part, cc)) {
                        is Paint.Solid -> fill = f.argb
                        Paint.None -> fill = null
                        else -> {}
                    }
                    for ((tag, side) in listOf("lnL" to "left", "lnR" to "right", "lnT" to "top", "lnB" to "bottom")) {
                        tcPr.child(tag)?.let { sides[side] = lineOf(it, null, part, cc) }
                    }
                }
                val extra = RunProps(bold = bold, italic = italic, color = color)
                val bp = BodyProps(
                    anchor = tcPr?.a("anchor"),
                    l = tcPr?.num("marL")?.let { (it / EMU).toFloat() } ?: 7.2f, r = tcPr?.num("marR")?.let { (it / EMU).toFloat() } ?: 7.2f,
                    t = tcPr?.num("marT")?.let { (it / EMU).toFloat() } ?: 3.6f, b = tcPr?.num("marB")?.let { (it / EMU).toFloat() } ?: 3.6f,
                )
                val body = tc.child("txBody")?.let { textBody(it, c, part, listOf(EMPTY_LST), null, extra, bp, false) }
                    ?: STextBody(emptyList(), if (bp.anchor == "ctr") 1 else if (bp.anchor == "b") 2 else 0, bp.l ?: 7.2f, bp.t ?: 3.6f, bp.r ?: 7.2f, bp.b ?: 3.6f, true, null)
                STableCell(
                    body,
                    fill, tc.int("gridSpan") ?: 1, tc.int("rowSpan") ?: 1, tc.flag("hMerge") == true, tc.flag("vMerge") == true,
                    sides["left"], sides["top"], sides["right"], sides["bottom"],
                )
            }
        }
        return STable(colW, rowH, rows)
    }
}
