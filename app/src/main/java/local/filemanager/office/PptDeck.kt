package local.filemanager

/**
 * 古い PowerPoint(.ppt)を、位置・大きさ・図形・画像・文字の書式つきのスライド(SlideDeck)にする。Android に依存しない。
 * 図形は Escher(Office Art)の入れ物から、文字は PPT のレコード(TextHeader / TextChars / StyleTextProp)と
 * マスターの文字書式(TxMasterStyle)から読む。EMF・WMF の画像、OLE の中身、アニメーションは描かない。
 */
internal object PptDeck {
    class Result(val deck: SlideDeck, val read: (String) -> ByteArray?)

    private const val UNIT = 8f // マスター単位(1/576 インチ)→ pt

    // ---------------------------------------------------------------- 色

    private val DEFAULT_SCHEME = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF808080.toInt(), 0xFF000000.toInt(),
        0xFFBBE0E3.toInt(), 0xFF333399.toInt(), 0xFF009999.toInt(), 0xFF99CC00.toInt(),
    )

    private fun schemeOf(d: ByteArray, r: Ppt.Rec): IntArray? {
        if (r.len < 32) return null
        return IntArray(8) { i ->
            val o = r.off + i * 4
            (0xFF shl 24) or ((d[o].toInt() and 0xFF) shl 16) or ((d[o + 1].toInt() and 0xFF) shl 8) or (d[o + 2].toInt() and 0xFF)
        }
    }

    /** 図形の色(Escher)。上位 1 バイトは種類(0x08 なら、赤の値が配色の番号)。 */
    private fun escherColor(raw: Int, scheme: IntArray): Int {
        val r = raw and 0xFF
        val g = (raw shr 8) and 0xFF
        val b = (raw shr 16) and 0xFF
        val f = (raw ushr 24) and 0xFF
        if (f and 0x08 != 0) return scheme[r.coerceIn(0, 7)]
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** 文字の色(PPT)。index が 0xFE なら RGB そのまま、0〜7 なら配色の番号。 */
    private fun textColor(raw: Int, scheme: IntArray): Int? {
        val idx = (raw ushr 24) and 0xFF
        if (idx == 0xFF) return null
        if (idx != 0xFE) return scheme[idx.coerceIn(0, 7)]
        val r = raw and 0xFF
        val g = (raw shr 8) and 0xFF
        val b = (raw shr 16) and 0xFF
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun withAlpha(argb: Int, opacity16: Int?): Int {
        if (opacity16 == null || opacity16 >= 0x10000) return argb
        val a = (((argb ushr 24) and 0xFF) * (opacity16.coerceAtLeast(0) / 65536f)).toInt().coerceIn(0, 255)
        return (a shl 24) or (argb and 0xFFFFFF)
    }

    // ---------------------------------------------------------------- 文字の書式

    private class Pf {
        var bullet: Boolean? = null
        var bulletChar: Int? = null
        var bulletFont: Int? = null
        var bulletSize: Int? = null
        var bulletColor: Int? = null
        var align: Int? = null
        var line: Int? = null
        var before: Int? = null
        var after: Int? = null
        var left: Int? = null
        var indent: Int? = null
    }

    private class Cf {
        var bold: Boolean? = null
        var italic: Boolean? = null
        var underline: Boolean? = null
        var font: Int? = null
        var size: Int? = null
        var color: Int? = null
        var pos: Int? = null
    }

    private fun s16(d: ByteArray, o: Int): Int = u16(d, o).toShort().toInt()
    private fun u16(d: ByteArray, o: Int): Int = Ppt.u16(d, o)

    /** TextPFException を読む。返すのは (書式, 次の位置)。 */
    private fun readPf(d: ByteArray, start: Int, end: Int): Pair<Pf, Int> {
        val pf = Pf()
        var p = start
        if (p + 4 > end) return pf to end
        val m = Ole2.le32(d, p); p += 4
        fun has(bit: Int) = m and bit != 0
        if (m and 0xF != 0 && p + 2 <= end) {
            val f = u16(d, p); p += 2
            if (has(1)) pf.bullet = f and 1 != 0
        }
        if (has(0x80) && p + 2 <= end) { pf.bulletChar = u16(d, p); p += 2 }
        if (has(0x10) && p + 2 <= end) { pf.bulletFont = u16(d, p); p += 2 }
        if (has(0x40) && p + 2 <= end) { pf.bulletSize = s16(d, p); p += 2 }
        if (has(0x20) && p + 4 <= end) { pf.bulletColor = Ole2.le32(d, p); p += 4 }
        if (has(0x800) && p + 2 <= end) { pf.align = u16(d, p); p += 2 }
        if (has(0x1000) && p + 2 <= end) { pf.line = s16(d, p); p += 2 }
        if (has(0x2000) && p + 2 <= end) { pf.before = s16(d, p); p += 2 }
        if (has(0x4000) && p + 2 <= end) { pf.after = s16(d, p); p += 2 }
        if (has(0x100) && p + 2 <= end) { pf.left = s16(d, p); p += 2 }
        if (has(0x400) && p + 2 <= end) { pf.indent = s16(d, p); p += 2 }
        if (has(0x8000) && p + 2 <= end) p += 2
        if (has(0x100000) && p + 2 <= end) { val n = u16(d, p); p += 2 + 4 * n }
        if (has(0x10000) && p + 2 <= end) p += 2
        if (m and 0xE0000 != 0 && p + 2 <= end) p += 2
        if (has(0x200000) && p + 2 <= end) p += 2
        return pf to p.coerceAtMost(end)
    }

    /** TextCFException を読む。 */
    private fun readCf(d: ByteArray, start: Int, end: Int): Pair<Cf, Int> {
        val cf = Cf()
        var p = start
        if (p + 4 > end) return cf to end
        val m = Ole2.le32(d, p); p += 4
        fun has(bit: Int) = m and bit != 0
        if (m and 0xFFFF != 0 && p + 2 <= end) {
            val f = u16(d, p); p += 2
            if (has(1)) cf.bold = f and 1 != 0
            if (has(2)) cf.italic = f and 2 != 0
            if (has(4)) cf.underline = f and 4 != 0
        }
        if (has(0x10000) && p + 2 <= end) { cf.font = u16(d, p); p += 2 }
        if (has(0x200000) && p + 2 <= end) p += 2 // 東アジアの旧字体
        if (has(0x400000) && p + 2 <= end) { val a = u16(d, p); p += 2; if (cf.font == null) cf.font = a }
        if (has(0x800000) && p + 2 <= end) p += 2 // 記号の字体
        if (has(0x20000) && p + 2 <= end) { cf.size = u16(d, p); p += 2 }
        if (has(0x40000) && p + 4 <= end) { cf.color = Ole2.le32(d, p); p += 4 }
        if (has(0x80000) && p + 2 <= end) { cf.pos = s16(d, p); p += 2 }
        return cf to p.coerceAtMost(end)
    }

    private class Lvl(val pf: Pf, val cf: Cf)

    /** TxMasterStyleAtom(文字の種類ごとの、段落の深さ別の既定の書式)。 */
    private fun readMasterStyle(d: ByteArray, r: Ppt.Rec): List<Lvl> {
        val out = ArrayList<Lvl>()
        if (r.len < 2) return out
        var p = r.off
        val n = u16(d, p).coerceAtMost(5); p += 2
        for (i in 0 until n) {
            if (r.inst >= 5) p += 2
            if (p >= r.end) break
            val (pf, p2) = readPf(d, p, r.end)
            val (cf, p3) = readCf(d, p2, r.end)
            out.add(Lvl(pf, cf))
            p = p3
        }
        return out
    }

    // ---------------------------------------------------------------- Escher の属性

    private class Opt {
        val v = HashMap<Int, Int>()
        val cx = HashMap<Int, ByteArray>()

        fun merge(d: ByteArray, r: Ppt.Rec) {
            val n = r.inst
            var q = r.off + 6 * n
            for (i in 0 until n) {
                val p = r.off + 6 * i
                if (p + 6 > r.end) break
                val id = u16(d, p)
                val pid = id and 0x3FFF
                val op = Ole2.le32(d, p + 2)
                if (id and 0x8000 != 0) {
                    if (op < 0 || q.toLong() + op > r.end) break
                    cx[pid] = d.copyOfRange(q, q + op)
                    q += op
                    v[pid] = op
                } else {
                    v[pid] = op
                }
            }
        }

        operator fun get(pid: Int): Int? = v[pid]

        /** 真偽の属性。use ビット(上位 16 ビット)が立っていなければ null。 */
        fun flag(pid: Int, bit: Int): Boolean? {
            val x = v[pid] ?: return null
            return if ((x ushr 16) and bit != 0) x and bit != 0 else null
        }
    }

    private class Info(
        val type: Int,
        val flags: Int,
        val opt: Opt,
        val client: FloatArray?,
        val child: IntArray?,
        val grp: IntArray?,
        val textbox: Ppt.Rec?,
        val placeholder: Int?,
    )

    private fun info(d: ByteArray, sp: Ppt.Rec): Info {
        var type = 0
        var flags = 0
        val opt = Opt()
        var client: FloatArray? = null
        var child: IntArray? = null
        var grp: IntArray? = null
        var tb: Ppt.Rec? = null
        var ph: Int? = null
        for (c in Ppt.children(d, sp)) when (c.type) {
            0xF00A -> if (c.len >= 8) { type = c.inst; flags = Ole2.le32(d, c.off + 4) }
            0xF00B, 0xF121, 0xF122 -> opt.merge(d, c)
            0xF009 -> if (c.len >= 16) grp = IntArray(4) { Ole2.le32(d, c.off + it * 4) }
            0xF00F -> if (c.len >= 16) child = IntArray(4) { Ole2.le32(d, c.off + it * 4) }
            0xF010 -> {
                // top, left, right, bottom(8 バイトなら 16 ビット、16 バイトなら 32 ビット)
                val vals = when {
                    c.len >= 16 -> IntArray(4) { Ole2.le32(d, c.off + it * 4) }
                    c.len >= 8 -> IntArray(4) { s16(d, c.off + it * 2) }
                    else -> null
                }
                if (vals != null) client = floatArrayOf(vals[1] / UNIT, vals[0] / UNIT, (vals[2] - vals[1]) / UNIT, (vals[3] - vals[0]) / UNIT)
            }
            0xF00D -> tb = c
            0xF011 -> for (k in Ppt.children(d, c)) if (k.type == 0x0BC3 && k.len >= 6) ph = d[k.off + 4].toInt() and 0xFF
        }
        return Info(type, flags, opt, client, child, grp, tb, ph)
    }

    private class Xf(val sx: Float, val sy: Float, val tx: Float, val ty: Float) {
        companion object { val ID = Xf(1f, 1f, 0f, 0f) }
    }

    private fun boxOf(i: Info, xf: Xf): FloatArray? {
        i.child?.let { c ->
            return floatArrayOf(c[0] * xf.sx + xf.tx, c[1] * xf.sy + xf.ty, (c[2] - c[0]) * xf.sx, (c[3] - c[1]) * xf.sy)
        }
        return i.client
    }

    // ---------------------------------------------------------------- 画像

    private class Bse(val blipType: Int, val doc: ByteArray, val body: Int, val len: Int)

    private fun blipData(bse: Bse, pics: ByteArray?): ByteArray? {
        val foDelay = Ole2.le32(bse.doc, bse.body + 28)
        val nameLen = bse.doc[bse.body + 33].toInt() and 0xFF
        val embedded = bse.body + 36 + nameLen
        val (src, at) = when {
            pics != null && foDelay >= 0 && foDelay + 8 <= pics.size -> pics to foDelay
            embedded + 8 <= bse.body + bse.len -> bse.doc to embedded
            else -> return null
        }
        val r = Ppt.rec(src, at) ?: return null
        val two = when (r.type) {
            0xF01D, 0xF02A -> r.inst == 0x46B || r.inst == 0x6E3
            0xF01E -> r.inst == 0x6E1
            0xF01F -> r.inst == 0x7A9
            else -> false
        }
        val skip = 16 + (if (two) 16 else 0) + 1
        if (r.len <= skip) return null
        val data = src.copyOfRange(r.off + skip, r.end)
        return when (r.type) {
            0xF01D, 0xF02A, 0xF01E -> data
            0xF01F -> dibToBmp(data)
            else -> null // EMF・WMF・PICT・TIFF は描けない
        }
    }

    private fun dibToBmp(dib: ByteArray): ByteArray? {
        if (dib.size < 40) return null
        val hdr = Ole2.le32(dib, 0)
        val bits = u16(dib, 14)
        val comp = Ole2.le32(dib, 16)
        var clr = Ole2.le32(dib, 32)
        if (bits <= 8 && clr == 0) clr = 1 shl bits
        var off = 14 + hdr + (if (bits <= 8) clr * 4 else 0)
        if (comp == 3 && hdr == 40) off += 12
        val out = ByteArray(14 + dib.size)
        out[0] = 'B'.code.toByte(); out[1] = 'M'.code.toByte()
        val total = out.size
        for (i in 0 until 4) { out[2 + i] = (total shr (8 * i)).toByte(); out[10 + i] = (off shr (8 * i)).toByte() }
        System.arraycopy(dib, 0, out, 14, dib.size)
        return out
    }

    // ---------------------------------------------------------------- 全体

    private class Master(
        val scheme: IntArray?,
        val styles: Map<Int, List<Lvl>>,
        val shapes: List<SShape>,
        val bg: Paint?,
        val phBoxes: Map<Int, FloatArray>,
    )

    private class Shared(
        val doc: ByteArray,
        val fonts: List<String>,
        val envStyles: Map<Int, List<Lvl>>,
        val dggDefaults: Opt,
        val bses: List<Bse>,
    )

    private class Ctx(
        val sh: Shared,
        val scheme: IntArray,
        val styles: Map<Int, List<Lvl>>,
        val masterBoxes: Map<Int, FloatArray>,
    )

    fun parse(bytes: ByteArray): Result = parse(Ole2(bytes))

    fun parse(ole: Ole2): Result {
        val doc = ole.stream("PowerPoint Document") ?: throw OpException("PowerPointの文書(PowerPoint Document)が見つかりません")
        val pics = ole.stream("Pictures")
        val persist = Ppt.readPersist(ole.stream("Current User"), doc)
        val docRec = if (persist.docOffset >= 0) Ppt.rec(doc, persist.docOffset) else null
        if (docRec == null || docRec.type != 0x03E8) throw OpException("PowerPointの文書の構造が読めません")

        var wPt = 720f
        var hPt = 540f
        val fonts = ArrayList<String>()
        val env = HashMap<Int, List<Lvl>>()
        val dgg = Opt()
        val bses = ArrayList<Bse>()
        val slideIds = ArrayList<Int>() // persist id(スライドの順)
        val masterBySlideId = HashMap<Int, Int>()
        val masterOrder = ArrayList<Int>()
        for (c in Ppt.children(doc, docRec)) when (c.type) {
            0x03E9 -> if (c.len >= 8) { wPt = Ole2.le32(doc, c.off) / UNIT; hPt = Ole2.le32(doc, c.off + 4) / UNIT }
            0x03F2 -> for (e in Ppt.children(doc, c)) {
                if (e.type == 0x07D5) for (f in Ppt.children(doc, e)) if (f.type == 0x0FB7 && f.len >= 2) fonts.add(fontName(doc, f))
                if (e.type == 0x0FA3) env[e.inst] = readMasterStyle(doc, e)
            }
            0x040B -> collectDgg(doc, c, dgg, bses)
            0x0FF0 -> for (a in Ppt.children(doc, c)) if (a.type == 0x03F3 && a.len >= 16) {
                val pid = Ole2.le32(doc, a.off)
                val sid = Ole2.le32(doc, a.off + 12)
                when (c.inst) {
                    0 -> slideIds.add(pid)
                    1 -> { masterBySlideId[sid] = pid; masterOrder.add(pid) }
                }
            }
        }
        if (slideIds.isEmpty()) throw OpException("スライドが見つかりません")
        val shared = Shared(doc, fonts, env, dgg, bses)

        val masters = HashMap<Int, Master?>()
        fun masterOf(pid: Int): Master? = masters.getOrPut(pid) {
            val off = persist.offsets[pid] ?: return@getOrPut null
            val r = Ppt.rec(doc, off) ?: return@getOrPut null
            if (r.type != 0x03F8) null else runCatching { readMaster(shared, r) }.getOrNull()
        }

        val slides = ArrayList<SlideDoc>()
        for ((i, pid) in slideIds.withIndex()) {
            val off = persist.offsets[pid]
            val r = if (off != null) Ppt.rec(doc, off) else null
            if (r == null || r.type != 0x03EE) { slides.add(SlideDoc(i + 1, false, null, emptyList())); continue }
            slides.add(runCatching { readSlide(shared, r, i + 1, masterBySlideId, masterOrder, ::masterOf) }.getOrElse { SlideDoc(i + 1, false, null, emptyList()) })
        }

        val cache = HashMap<Int, ByteArray?>()
        val read: (String) -> ByteArray? = { path ->
            val n = path.removePrefix("ppt-image/").toIntOrNull()
            if (n == null || n < 1 || n > bses.size) null
            else synchronized(cache) { cache.getOrPut(n) { runCatching { blipData(bses[n - 1], pics) }.getOrNull() } }
        }
        return Result(SlideDeck(wPt, hPt, slides), read)
    }

    private fun fontName(d: ByteArray, r: Ppt.Rec): String {
        val n = minOf(64, r.len) and 1.inv()
        var end = 0
        while (end + 1 < n && (d[r.off + end].toInt() != 0 || d[r.off + end + 1].toInt() != 0)) end += 2
        return String(d, r.off, end, Charsets.UTF_16LE)
    }

    private fun collectDgg(d: ByteArray, parent: Ppt.Rec, dgg: Opt, bses: MutableList<Bse>) {
        for (c in Ppt.children(d, parent)) when {
            c.type == 0xF00B -> dgg.merge(d, c)
            c.type == 0xF007 && c.len >= 36 -> bses.add(Bse(c.inst, d, c.off, c.len))
            c.isContainer -> collectDgg(d, c, dgg, bses)
        }
    }

    private fun readMaster(sh: Shared, r: Ppt.Rec): Master {
        val d = sh.doc
        var scheme: IntArray? = null
        val styles = HashMap<Int, List<Lvl>>()
        var pp: Ppt.Rec? = null
        for (c in Ppt.children(d, r)) when (c.type) {
            0x07F0 -> if (scheme == null) scheme = schemeOf(d, c)
            0x0FA3 -> styles[c.inst] = readMasterStyle(d, c)
            0x040C -> pp = c
        }
        val ctx = Ctx(sh, scheme ?: DEFAULT_SCHEME, styles, emptyMap())
        val boxes = HashMap<Int, FloatArray>()
        val shapes = ArrayList<SShape>()
        val bg = if (pp != null) drawing(ctx, pp, shapes, boxes) else null
        return Master(scheme, styles, shapes, bg, boxes)
    }

    private fun readSlide(
        sh: Shared,
        r: Ppt.Rec,
        number: Int,
        masterBySlideId: Map<Int, Int>,
        masterOrder: List<Int>,
        masterOf: (Int) -> Master?,
    ): SlideDoc {
        val d = sh.doc
        var scheme: IntArray? = null
        var pp: Ppt.Rec? = null
        var masterRef = 0
        var flags = 7
        var hidden = false
        for (c in Ppt.children(d, r)) when (c.type) {
            0x03EF -> if (c.len >= 22) { masterRef = Ole2.le32(d, c.off + 12); flags = u16(d, c.off + 20) }
            0x03F9 -> if (c.len >= 12) hidden = u16(d, c.off + 10) and 0x4 != 0
            0x07F0 -> if (scheme == null) scheme = schemeOf(d, c)
            0x040C -> pp = c
        }
        val mpid = masterBySlideId[masterRef] ?: masterOrder.firstOrNull()
        val master = if (mpid != null) masterOf(mpid) else null
        val followScheme = flags and 2 != 0
        val sch = (if (followScheme) master?.scheme ?: scheme else scheme ?: master?.scheme) ?: DEFAULT_SCHEME
        val ctx = Ctx(sh, sch, master?.styles ?: emptyMap(), master?.phBoxes ?: emptyMap())
        val own = ArrayList<SShape>()
        val bgOwn = if (pp != null) drawing(ctx, pp, own, null) else null
        val shapes = ArrayList<SShape>()
        if (flags and 1 != 0 && master != null) shapes.addAll(master.shapes)
        shapes.addAll(own)
        val bg = if (flags and 4 != 0) master?.bg ?: bgOwn else bgOwn ?: master?.bg
        return SlideDoc(number, hidden, bg, shapes)
    }

    // ---------------------------------------------------------------- 図形

    /** PPDrawing の中の図形を集める。戻り値は背景。boxes が渡されたら(マスター)、場所の枠を記録して描かない。 */
    private fun drawing(ctx: Ctx, pp: Ppt.Rec, out: MutableList<SShape>, boxes: MutableMap<Int, FloatArray>?): Paint? {
        val d = ctx.sh.doc
        var bg: Paint? = null
        for (dg in Ppt.children(d, pp)) if (dg.type == 0xF002) {
            for (c in Ppt.children(d, dg)) when (c.type) {
                0xF003 -> group(ctx, c, Xf.ID, out, boxes)
                0xF004 -> {
                    val i = info(d, c)
                    if (i.flags and 0x400 != 0) bg = fillOf(ctx, i.opt, i.type)
                }
            }
        }
        return bg
    }

    private fun group(ctx: Ctx, g: Ppt.Rec, xf: Xf, out: MutableList<SShape>, boxes: MutableMap<Int, FloatArray>?) {
        val d = ctx.sh.doc
        val kids = Ppt.children(d, g)
        var inner = xf
        var start = 0
        val first = kids.firstOrNull()
        if (first != null && first.type == 0xF004) {
            start = 1
            val i = info(d, first)
            val grp = i.grp
            val b = boxOf(i, xf)
            if (i.flags and 0x4 == 0 && grp != null && b != null) {
                val cw = grp[2] - grp[0]
                val ch = grp[3] - grp[1]
                if (cw != 0 && ch != 0) {
                    val sx = b[2] / cw
                    val sy = b[3] / ch
                    inner = Xf(sx, sy, b[0] - grp[0] * sx, b[1] - grp[1] * sy)
                }
            }
        }
        for (k in kids.drop(start)) when (k.type) {
            0xF004 -> runCatching { shape(ctx, k, inner, out, boxes) }
            0xF003 -> runCatching { group(ctx, k, inner, out, boxes) }
        }
    }

    private fun phMaster(id: Int): Int = when (id) {
        13 -> 1; 14, 19, 20, 21, 22, 23, 24, 26 -> 2; 15 -> 3; 16 -> 4; else -> id
    }

    private fun prstOf(t: Int): String = when (t) {
        2 -> "roundRect"; 3 -> "ellipse"; 4, 110 -> "diamond"; 5 -> "triangle"; 6 -> "rtTriangle"
        7, 111 -> "parallelogram"; 8, 119 -> "trapezoid"; 9 -> "hexagon"; 10 -> "octagon"; 11 -> "plus"; 12 -> "star5"
        13, 14 -> "rightArrow"; 15 -> "homePlate"; 19 -> "arc"; 20 -> "line"; 23 -> "donut"
        32 -> "straightConnector1"; 33 -> "bentConnector2"; 34 -> "bentConnector3"; 35 -> "bentConnector4"; 36 -> "bentConnector5"
        37 -> "curvedConnector2"; 38 -> "curvedConnector3"; 39 -> "curvedConnector4"; 40 -> "curvedConnector5"
        55 -> "chevron"; 56 -> "pentagon"; 57 -> "noSmoking"; 58 -> "star8"; 59 -> "star16"; 60 -> "star32"
        66 -> "leftArrow"; 67 -> "downArrow"; 68 -> "upArrow"; 69 -> "leftRightArrow"; 70 -> "upDownArrow"; 74 -> "heart"
        96 -> "smileyFace"; 116 -> "flowChartTerminator"; 117 -> "flowChartPreparation"; 120 -> "flowChartConnector"
        176 -> "flowChartAlternateProcess"; 183 -> "sun"; 184 -> "moon"; 187 -> "star4"
        else -> "rect"
    }

    private fun shape(ctx: Ctx, sp: Ppt.Rec, xf: Xf, out: MutableList<SShape>, boxes: MutableMap<Int, FloatArray>?) {
        val d = ctx.sh.doc
        val i = info(d, sp)
        if (i.flags and 0x8 != 0 || i.flags and 0x4 != 0) return // 消された図形・親玉
        val opt = i.opt
        val dflt = ctx.sh.dggDefaults
        if (opt.flag(0x03BF, 0x2) == true) return // 隠れている図形
        var b = boxOf(i, xf)
        val ph = i.placeholder
        if (boxes != null) {
            // マスターの「題名・本文・日付…」の枠は、場所だけ覚えて描かない
            if (ph != null && b != null) boxes[ph] = b
            if (ph != null) return
        }
        if (b == null && ph != null) b = ctx.masterBoxes[phMaster(ph)]
        if (b == null) return
        if (b[2] < 0f || b[3] < 0f) b = floatArrayOf(b[0], b[1], kotlin.math.abs(b[2]), kotlin.math.abs(b[3]))

        val flipH = i.flags and 0x40 != 0
        val flipV = i.flags and 0x80 != 0
        val rot = (opt[0x0004] ?: 0) / 65536f

        val pib = opt[0x0104]
        val isLine = i.type == 20 || i.type in 32..40
        val geom = freeform(opt) ?: Geom(prstOf(i.type), adjOf(i.type, opt))
        val text = i.textbox?.let { textBody(ctx, it, opt, ph != null) }

        if (pib != null && pib > 0 && pib <= ctx.sh.bses.size) {
            fun frac(pid: Int) = ((opt[pid] ?: 0) / 65536f).coerceIn(0f, 0.95f)
            val pic = SPicture("ppt-image/$pib", frac(0x0102), frac(0x0100), frac(0x0103), frac(0x0101))
            out.add(SShape(b[0], b[1], b[2], b[3], rot, flipH, flipV, if (geom.prst == "rect" && geom.cust == null) null else geom, null, lineOf(ctx, opt, dflt, false), text, pic, null))
            return
        }
        val fillOn = opt.flag(0x01BF, 0x10) ?: dflt.flag(0x01BF, 0x10) ?: (i.type != 202 && !isLine)
        val fill = if (fillOn) fillOf(ctx, opt, i.type, dflt) else null
        val line = lineOf(ctx, opt, dflt, isLine)
        if (fill == null && line == null && text == null) return
        out.add(SShape(b[0], b[1], b[2], b[3], rot, flipH, flipV, geom, fill, line, text, null, null))
    }

    private fun adjOf(type: Int, o: Opt): Map<String, Float> {
        val a = o[0x0147] ?: return emptyMap()
        val v = a / 21600f * 100000f
        return when (type) {
            2, 3 -> if (type == 2) mapOf("adj" to v) else emptyMap()
            5, 7, 8, 9, 10, 11, 111, 119 -> mapOf("adj" to v)
            else -> emptyMap()
        }
    }

    private fun fillOf(ctx: Ctx, o: Opt, type: Int, dflt: Opt? = null): Paint? {
        fun get(pid: Int) = o[pid] ?: dflt?.get(pid)
        val kind = get(0x0180) ?: 0
        val color = escherColor(get(0x0181) ?: 0x00FFFFFF, ctx.scheme)
        val alpha = get(0x0182)
        when (kind) {
            3, 2 -> {
                val bi = o[0x0186]
                if (bi != null && bi > 0 && bi <= ctx.sh.bses.size) return Paint.Blip("ppt-image/$bi")
                return Paint.Solid(withAlpha(color, alpha))
            }
            4, 5, 6 -> {
                val back = escherColor(get(0x0183) ?: 0x00FFFFFF, ctx.scheme)
                val ang = (((-(get(0x018B) ?: 0)) / 65536f) % 360f + 360f) % 360f
                val stops = shadeStops(ctx, o[0x0197] ?: dflt?.get(0x0197), o.cx[0x0197])
                return Paint.Grad(stops ?: listOf(0f to withAlpha(color, alpha), 1f to withAlpha(back, alpha)), ang)
            }
            7 -> return null
        }
        return Paint.Solid(withAlpha(color, alpha))
    }

    private fun shadeStops(ctx: Ctx, @Suppress("UNUSED_PARAMETER") len: Int?, data: ByteArray?): List<Pair<Float, Int>>? {
        if (data == null || data.size < 6) return null
        val n = u16(data, 0)
        val cb = u16(data, 4)
        val elem = if (cb == 0xFFF0) 4 else cb
        if (n < 2 || elem < 8) return null
        val out = ArrayList<Pair<Float, Int>>()
        for (k in 0 until n) {
            val p = 6 + k * elem
            if (p + 8 > data.size) break
            out.add((Ole2.le32(data, p + 4) / 65536f).coerceIn(0f, 1f) to escherColor(Ole2.le32(data, p), ctx.scheme))
        }
        return if (out.size >= 2) out.sortedBy { it.first } else null
    }

    private fun lineOf(ctx: Ctx, o: Opt, dflt: Opt, isLine: Boolean): SLine? {
        val on = o.flag(0x01FF, 0x8) ?: dflt.flag(0x01FF, 0x8) ?: isLine
        if (!on) return null
        fun get(pid: Int) = o[pid] ?: dflt[pid]
        val color = escherColor(get(0x01C0) ?: 0, ctx.scheme)
        val w = ((get(0x01CB) ?: 9525) / 12700f).coerceAtLeast(0.5f)
        val dash = when (get(0x01CE) ?: 0) {
            1, 6, 10 -> floatArrayOf(w * 4, w * 3)
            2, 5, 9 -> floatArrayOf(w, w * 2)
            3, 7 -> floatArrayOf(w * 4, w * 3, w, w * 3)
            4, 8 -> floatArrayOf(w * 4, w * 3, w, w * 3, w, w * 3)
            11 -> floatArrayOf(w * 8, w * 3)
            else -> null
        }
        return SLine(withAlpha(color, get(0x01C1)), w, dash, (get(0x01D0) ?: 0) != 0, (get(0x01D1) ?: 0) != 0)
    }

    /** 自由な形(頂点と線分の並び)。 */
    private fun freeform(o: Opt): Geom? {
        val vd = o.cx[0x0145] ?: return null
        if (vd.size < 6) return null
        val n = u16(vd, 0)
        val cb = u16(vd, 4)
        val big = cb == 8
        val esz = if (cb == 0xFFF0) 4 else cb
        if (n < 2 || (esz != 4 && esz != 8)) return null
        val left = o[0x0140] ?: 0
        val top = o[0x0141] ?: 0
        val right = o[0x0142] ?: 21600
        val bottom = o[0x0143] ?: 21600
        val pts = ArrayList<Pair<Float, Float>>()
        for (k in 0 until n) {
            val p = 6 + k * esz
            if (p + esz > vd.size) break
            val x = if (big) Ole2.le32(vd, p) else s16(vd, p)
            val y = if (big) Ole2.le32(vd, p + 4) else s16(vd, p + 2)
            pts.add((x - left).toFloat() to (y - top).toFloat())
        }
        if (pts.size < 2) return null
        val cmds = ArrayList<CustCmd>()
        val sd = o.cx[0x0146]
        var vi = 0
        if (sd == null || sd.size < 6) {
            cmds.add(CustCmd.Move(pts[0].first, pts[0].second))
            for (k in 1 until pts.size) cmds.add(CustCmd.Line(pts[k].first, pts[k].second))
        } else {
            val sn = u16(sd, 0)
            loop@ for (k in 0 until sn) {
                val p = 6 + k * 2
                if (p + 2 > sd.size) break
                val seg = u16(sd, p)
                val count = seg and 0xFF
                when ((seg shr 13) and 7) {
                    0 -> repeat(count) { pts.getOrNull(vi)?.let { cmds.add(CustCmd.Line(it.first, it.second)) }; vi++ }
                    1 -> repeat(count) {
                        val a = pts.getOrNull(vi); val b = pts.getOrNull(vi + 1); val c = pts.getOrNull(vi + 2)
                        if (a != null && b != null && c != null) cmds.add(CustCmd.Cubic(a.first, a.second, b.first, b.second, c.first, c.second))
                        vi += 3
                    }
                    2 -> { pts.getOrNull(vi)?.let { cmds.add(CustCmd.Move(it.first, it.second)) }; vi++ }
                    3 -> cmds.add(CustCmd.Close)
                    4 -> {}
                    else -> break@loop
                }
            }
        }
        if (cmds.none { it !is CustCmd.Move }) return null
        return Geom("rect", cust = listOf(CustPath((right - left).toFloat(), (bottom - top).toFloat(), cmds, fill = true, stroke = true)))
    }

    // ---------------------------------------------------------------- 文字

    private class PfRun(val start: Int, val end: Int, val level: Int, val pf: Pf)
    private class CfRun(val start: Int, val end: Int, val cf: Cf)

    private fun textBody(ctx: Ctx, tb: Ppt.Rec, o: Opt, placeholder: Boolean): STextBody? {
        val d = ctx.sh.doc
        var type = if (placeholder) 1 else 4
        var text: String? = null
        var style: Ppt.Rec? = null
        for (c in Ppt.children(d, tb)) when (c.type) {
            0x0F9F -> if (c.len >= 4) type = Ole2.le32(d, c.off)
            0x0FA0, 0x0FA8 -> if (text == null) text = Ppt.textOf(d, c)
            0x0FA1 -> style = c
        }
        val s = text ?: return null
        if (s.isBlank()) return null

        val total = s.length + 1
        val pfRuns = ArrayList<PfRun>()
        val cfRuns = ArrayList<CfRun>()
        if (style != null) {
            var p = style.off
            var at = 0
            while (at < total && p + 6 <= style.end) {
                val n = Ole2.le32(d, p); val lv = u16(d, p + 4)
                val (pf, np) = readPf(d, p + 6, style.end)
                pfRuns.add(PfRun(at, at + n.coerceAtLeast(1), lv, pf)); at += n.coerceAtLeast(1); p = np
            }
            at = 0
            while (at < total && p + 4 <= style.end) {
                val n = Ole2.le32(d, p)
                val (cf, np) = readCf(d, p + 4, style.end)
                cfRuns.add(CfRun(at, at + n.coerceAtLeast(1), cf)); at += n.coerceAtLeast(1); p = np
            }
        }

        val styleSet = ctx.styles[type] ?: ctx.sh.envStyles[type]
        val other = ctx.styles[4] ?: ctx.sh.envStyles[4]
        fun <T> pick(level: Int, get: (Lvl) -> T?): T? {
            for (set in listOf(styleSet, other)) {
                if (set == null) continue
                get(set.getOrNull(level) ?: continue)?.let { return it }
                set.getOrNull(0)?.let { l -> get(l)?.let { return it } }
            }
            return null
        }
        val titleLike = type == 0 || type == 6
        val sizeDefault = if (titleLike) 44f else if (type == 4) 18f else 32f

        val paras = ArrayList<SPara>()
        var start = 0
        while (start <= s.length) {
            val cr = s.indexOf('\r', start)
            val lineEnd = if (cr < 0) s.length else cr
            if (start == s.length && s.isNotEmpty() && s.last() == '\r') break
            val pfRun = pfRuns.firstOrNull { start >= it.start && start < it.end }
            val level = (pfRun?.level ?: 0).coerceIn(0, 4)
            val pf = pfRun?.pf ?: Pf()

            val runs = ArrayList<SRun>()
            var pos = start
            while (pos < lineEnd) {
                val cr2 = cfRuns.firstOrNull { pos >= it.start && pos < it.end }
                val stop = minOf(lineEnd, cr2?.end ?: lineEnd)
                val cf = cr2?.cf ?: Cf()
                val raw = s.substring(pos, stop).replace('\u000B', '\n').filter { it == '\n' || it == '\t' || it >= ' ' }
                if (raw.isNotEmpty()) {
                    val sizePt = (cf.size ?: pick(level) { it.cf.size })?.toFloat() ?: (sizeDefault - level * 4f).coerceAtLeast(12f)
                    val colorRaw = cf.color ?: pick(level) { it.cf.color }
                    val color = (colorRaw?.let { textColor(it, ctx.scheme) }) ?: (if (titleLike) ctx.scheme[3] else ctx.scheme[1])
                    val fontRef = cf.font ?: pick(level) { it.cf.font }
                    val position = cf.pos ?: 0
                    runs.add(
                        SRun(
                            raw, sizePt, color,
                            cf.bold ?: pick(level) { it.cf.bold } ?: false,
                            cf.italic ?: pick(level) { it.cf.italic } ?: false,
                            cf.underline ?: pick(level) { it.cf.underline } ?: false,
                            false, if (position > 0) 1 else if (position < 0) -1 else 0,
                            fontRef?.let { ctx.sh.fonts.getOrNull(it) }, null,
                        ),
                    )
                }
                pos = stop
            }
            val paraSize = runs.maxOfOrNull { it.sizePt } ?: ((pick(level) { it.cf.size })?.toFloat() ?: (sizeDefault - level * 4f).coerceAtLeast(12f))
            val align = pf.align ?: pick(level) { it.pf.align } ?: 0
            val left = (pf.left ?: pick(level) { it.pf.left } ?: 0) / UNIT
            val indentAbs = (pf.indent ?: pick(level) { it.pf.indent } ?: 0) / UNIT
            val bullet = pf.bullet ?: pick(level) { it.pf.bullet } ?: false
            val hasText = runs.any { it.text.isNotBlank() }
            var marker: String? = null
            var markerColor: Int? = null
            var markerScale = 1f
            if (bullet && hasText) {
                val ch = pf.bulletChar ?: pick(level) { it.pf.bulletChar } ?: 0x2022
                val fontRef = pf.bulletFont ?: pick(level) { it.pf.bulletFont }
                marker = bulletOf(ch, fontRef?.let { ctx.sh.fonts.getOrNull(it) })
                val bc = pf.bulletColor ?: pick(level) { it.pf.bulletColor }
                markerColor = bc?.let { textColor(it, ctx.scheme) } ?: runs.firstOrNull()?.color
                val bs = pf.bulletSize ?: pick(level) { it.pf.bulletSize }
                if (bs != null && bs in 25..400) markerScale = bs / 100f
            }
            val line = pf.line ?: pick(level) { it.pf.line }
            val before = pf.before ?: pick(level) { it.pf.before }
            val after = pf.after ?: pick(level) { it.pf.after }
            fun space(v: Int?): Float = when {
                v == null || v == 0 -> 0f
                v > 0 -> paraSize * 1.2f * v / 100f
                else -> -v / UNIT
            }
            paras.add(
                SPara(
                    runs, when (align) { 1 -> 0; 2 -> 1; 3 -> 2; else -> -1 }, left, indentAbs - left,
                    space(before), space(after),
                    if (line != null && line > 0) line / 100f else null,
                    if (line != null && line < 0) -line / UNIT else null,
                    marker, markerColor, markerScale, paraSize,
                ),
            )
            start = lineEnd + 1
            if (cr < 0) break
        }
        if (paras.isEmpty()) return null

        val anchor = when (o[0x0087]) {
            0, 3, 6, 8 -> 0
            1, 4 -> 1
            2, 5, 7, 9 -> 2
            else -> if (titleLike || type == 6) 1 else 0
        }
        fun inset(pid: Int, def: Float) = (o[pid]?.let { it / 12700f }) ?: def
        return STextBody(
            paras, anchor, inset(0x0081, 7.2f), inset(0x0082, 3.6f), inset(0x0083, 7.2f), inset(0x0084, 3.6f),
            o[0x0085] != 2, null,
        )
    }

    private fun bulletOf(ch: Int, font: String?): String {
        val f = font?.lowercase() ?: ""
        val c = if (ch >= 0xF000) ch and 0xFF else ch
        if (f.contains("wingdings")) {
            return when (c) {
                0xA7, 0x6E -> "■"; 0x6C -> "●"; 0xD8 -> "➢"; 0xFC -> "✔"; 0x76 -> "❖"; 0x71 -> "❑"; 0x77 -> "◆"; 0x9F -> "•"
                else -> "•"
            }
        }
        if (f.contains("symbol") || c < 0x20) return if (c == 0xB7 || c == 0x2022 || c < 0x20) "•" else if (c in 0x20..0x7E) c.toChar().toString() else "•"
        return String(Character.toChars(c))
    }
}
