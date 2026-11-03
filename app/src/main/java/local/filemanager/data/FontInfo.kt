package local.filemanager

/** フォントファイル(.ttf / .otf / .ttc)の名前・対応する文字などを読む。Android に依存しない。 */
class FontInfo(
    val family: String?,
    val style: String?,
    val fullName: String?,
    val familyJa: String?,
    val version: String?,
    val copyright: String?,
    val designer: String?,
    val license: String?,
    val glyphs: Int,
    val unitsPerEm: Int,
    val weight: Int,
    val monospace: Boolean,
    val variable: Boolean,
    val cff: Boolean,
    /** .ttc のときの、フォントの数 */
    val collectionCount: Int,
    private val hasChar: (Int) -> Boolean,
) {
    fun supports(codePoint: Int): Boolean = hasChar(codePoint)

    /** 何割くらいの文字を持っているかの目安として、見本の文字がそろっているか。 */
    fun coverage(): List<Pair<String, Boolean>> = listOf(
        "英数字(ABC abc 123)" to ("AZaz09".all { supports(it.code) }),
        "ひらがな" to supports(0x3042),
        "カタカナ" to supports(0x30A2),
        "漢字(常用)" to ("日本語漢字永".all { supports(it.code) }),
        "全角記号(。、「」)" to ("。、「」".all { supports(it.code) }),
        "ハングル" to supports(0xAC00),
        "キリル文字" to supports(0x0416),
        "ギリシャ文字" to supports(0x03A9),
        "アクセントつきラテン文字(é ü ñ)" to ("éüñ".all { supports(it.code) }),
    )

    companion object {
        private fun u16(b: ByteArray, o: Int): Int = if (o < 0 || o + 2 > b.size) 0 else ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
        private fun u32(b: ByteArray, o: Int): Long = if (o < 0 || o + 4 > b.size) 0 else (u16(b, o).toLong() shl 16) or u16(b, o + 2).toLong()
        private fun tag(b: ByteArray, o: Int): String = if (o < 0 || o + 4 > b.size) "" else String(b, o, 4, Charsets.ISO_8859_1)

        /** 先頭を見て、フォントのファイルらしいか(.ttc も含む)。 */
        fun looksLikeFont(b: ByteArray): Boolean {
            if (b.size < 12) return false
            val t = tag(b, 0)
            return t == "ttcf" || t == "OTTO" || t == "true" || t == "typ1" || u32(b, 0) == 0x00010000L
        }

        fun parse(b: ByteArray, index: Int = 0): FontInfo? {
            if (!looksLikeFont(b)) return null
            var base = 0
            var count = 1
            if (tag(b, 0) == "ttcf") {
                count = u32(b, 8).toInt().coerceIn(1, 64)
                base = u32(b, 12 + 4 * index.coerceIn(0, count - 1)).toInt()
            }
            val numTables = u16(b, base + 4)
            if (numTables == 0 || numTables > 200) return null
            val tables = HashMap<String, Pair<Int, Int>>()
            for (i in 0 until numTables) {
                val r = base + 12 + i * 16
                if (r + 16 > b.size) break
                tables[tag(b, r)] = u32(b, r + 8).toInt() to u32(b, r + 12).toInt()
            }
            fun off(t: String) = tables[t]?.first?.takeIf { it in 0 until b.size }

            // ---- name ----
            val names = HashMap<Int, MutableList<Triple<Int, Int, String>>>() // nameID → (platform, lang, text)
            off("name")?.let { n ->
                val cnt = u16(b, n + 2)
                val strBase = n + u16(b, n + 4)
                for (i in 0 until cnt.coerceAtMost(2000)) {
                    val r = n + 6 + i * 12
                    if (r + 12 > b.size) break
                    val platform = u16(b, r)
                    val lang = u16(b, r + 4)
                    val id = u16(b, r + 6)
                    val len = u16(b, r + 8)
                    val so = strBase + u16(b, r + 10)
                    if (so < 0 || so + len > b.size || len == 0) continue
                    val text = when (platform) {
                        0, 3 -> String(b, so, len, Charsets.UTF_16BE)
                        1 -> String(b, so, len, Charsets.ISO_8859_1)
                        else -> continue
                    }.trim('\u0000', ' ')
                    if (text.isNotEmpty()) names.getOrPut(id) { ArrayList() }.add(Triple(platform, lang, text))
                }
            }
            fun name(id: Int): String? {
                val l = names[id] ?: return null
                return (l.firstOrNull { it.first == 3 && it.second == 0x409 } ?: l.firstOrNull { it.first == 3 && it.second < 0x800 && it.second != 0x411 }
                    ?: l.firstOrNull { it.first == 1 } ?: l.firstOrNull { it.first == 3 } ?: l.first()).third
            }
            fun nameJa(id: Int): String? = names[id]?.firstOrNull { it.first == 3 && it.second == 0x411 }?.third

            // ---- head / maxp / OS/2 / post ----
            val head = off("head")
            val maxp = off("maxp")
            val os2 = off("OS/2")
            val post = off("post")
            val unitsPerEm = if (head != null) u16(b, head + 18) else 0
            val glyphs = if (maxp != null) u16(b, maxp + 4) else 0
            val weight = if (os2 != null) u16(b, os2 + 4) else 0
            val mono = post != null && u32(b, post + 12) != 0L

            // ---- cmap ----
            val lookup = cmapLookup(b, off("cmap"))
            return FontInfo(
                name(1), name(2), name(4), nameJa(4) ?: nameJa(1), name(5), name(0), name(9), name(13),
                glyphs, unitsPerEm, weight, mono, tables.containsKey("fvar"), tables.containsKey("CFF ") || tables.containsKey("CFF2"),
                count, lookup,
            )
        }

        private fun cmapLookup(b: ByteArray, cmap: Int?): (Int) -> Boolean {
            if (cmap == null) return { false }
            val n = u16(b, cmap + 2)
            var best: Int? = null
            var bestRank = -1
            for (i in 0 until n.coerceAtMost(40)) {
                val r = cmap + 4 + i * 8
                val platform = u16(b, r)
                val enc = u16(b, r + 2)
                val sub = cmap + u32(b, r + 4).toInt()
                if (sub < 0 || sub + 4 > b.size) continue
                val format = u16(b, sub)
                if (format != 4 && format != 12) continue
                // Unicode 全体(3,10 / 0,4 / 0,6)を最優先、次に BMP(3,1 / 0,3)
                val rank = when {
                    (platform == 3 && enc == 10) || (platform == 0 && (enc == 4 || enc == 6)) -> 3
                    (platform == 3 && enc == 1) || (platform == 0 && enc in 0..3) -> 2
                    else -> 0
                }
                if (rank > bestRank) { best = sub; bestRank = rank }
            }
            val sub = best ?: return { false }
            return when (u16(b, sub)) {
                12 -> { cp -> format12(b, sub, cp) }
                else -> { cp -> format4(b, sub, cp) }
            }
        }

        private fun format12(b: ByteArray, sub: Int, cp: Int): Boolean {
            val groups = u32(b, sub + 12).toInt().coerceIn(0, 200_000)
            var lo = 0
            var hi = groups - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val g = sub + 16 + mid * 12
                val start = u32(b, g)
                val end = u32(b, g + 4)
                when {
                    cp < start -> hi = mid - 1
                    cp > end -> lo = mid + 1
                    else -> return u32(b, g + 8) + (cp - start) != 0L
                }
            }
            return false
        }

        private fun format4(b: ByteArray, sub: Int, cp: Int): Boolean {
            if (cp > 0xFFFF) return false
            val segX2 = u16(b, sub + 6)
            val seg = segX2 / 2
            val endCodes = sub + 14
            val startCodes = endCodes + segX2 + 2
            val deltas = startCodes + segX2
            val rangeOffsets = deltas + segX2
            for (i in 0 until seg) {
                if (u16(b, endCodes + i * 2) < cp) continue
                if (u16(b, startCodes + i * 2) > cp) return false
                val ro = u16(b, rangeOffsets + i * 2)
                val delta = u16(b, deltas + i * 2)
                if (ro == 0) return ((cp + delta) and 0xFFFF) != 0
                val gi = u16(b, rangeOffsets + i * 2 + ro + (cp - u16(b, startCodes + i * 2)) * 2)
                return gi != 0
            }
            return false
        }
    }
}
