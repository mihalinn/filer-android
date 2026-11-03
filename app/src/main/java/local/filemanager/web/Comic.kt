package local.filemanager

/** 「page2」が「page10」より前に来る並べ方(数字の並びは、数の大きさで比べる)。大文字小文字は区別しない。 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                val da = a.substring(i, ei).trimStart('0')
                val db = b.substring(j, ej).trimStart('0')
                if (da.length != db.length) return da.length - db.length
                val c = da.compareTo(db)
                if (c != 0) return c
                // 数が同じなら、0 の数が少ないほうを先に
                val z = (ei - i) - (ej - j)
                if (z != 0) return z
                i = ei
                j = ej
            } else {
                val la = ca.lowercaseChar()
                val lb = cb.lowercaseChar()
                if (la != lb) return la.compareTo(lb)
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}

/** マンガ・コミックの書庫(.cbz / .cbr / .cb7 / .cbt)の中から、ページになる画像を選んで、読む順に並べる。 */
object ComicPages {
    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif")

    fun isPage(name: String): Boolean {
        val p = ZipTree.clean(name)
        if (p.isEmpty()) return false
        val segs = p.split('/')
        if (segs.any { it.startsWith(".") || it.equals("__MACOSX", ignoreCase = true) }) return false
        return FileOps.extensionOf(segs.last()) in imageExt
    }

    fun select(entries: List<ZipReader.Entry>): List<ZipReader.Entry> =
        entries.filter { !it.isDir && isPage(it.name) }.sortedWith { a, b -> NaturalOrder.compare(ZipTree.clean(a.name), ZipTree.clean(b.name)) }
}
