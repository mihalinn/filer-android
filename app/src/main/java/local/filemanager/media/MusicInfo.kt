package local.filemanager

/** 音楽ファイルのタグ(曲名・アーティスト・アルバムなど)を、画面に出す文字にする。Android に依存しない。 */
object MusicText {
    /** 曲名。タグに無ければ、ファイル名(拡張子なし)。 */
    fun title(tagTitle: String?, fileName: String): String {
        val t = tagTitle?.trim()
        if (!t.isNullOrEmpty()) return t
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) fileName.substring(0, dot) else fileName
    }

    /** 曲名の下に並べる行: アーティスト、アルバム(年)、トラック・ディスクの番号。無いものは出さない。 */
    fun lines(
        artist: String?, albumArtist: String?, album: String?, year: Int?,
        track: Int?, trackTotal: Int?, disc: Int?, discTotal: Int?,
    ): List<String> {
        val out = ArrayList<String>()
        val who = artist?.trim()?.takeIf { it.isNotEmpty() } ?: albumArtist?.trim()?.takeIf { it.isNotEmpty() }
        if (who != null) out.add(who)
        val al = album?.trim()?.takeIf { it.isNotEmpty() }
        val y = year?.takeIf { it in 1000..9999 }
        if (al != null) out.add(if (y != null) "$al($y)" else al) else if (y != null) out.add("$y 年")
        val parts = ArrayList<String>()
        if (track != null && track > 0) parts.add("トラック $track" + if (trackTotal != null && trackTotal > 0) "/$trackTotal" else "")
        if (disc != null && disc > 0 && (discTotal == null || discTotal > 1 || disc > 1)) parts.add("ディスク $disc" + if (discTotal != null && discTotal > 0) "/$discTotal" else "")
        if (parts.isNotEmpty()) out.add(parts.joinToString("・"))
        return out
    }

    /** 「3/12」「3」のような値から、(番号, 全部の数) を取り出す。 */
    fun numberOf(s: String?): Pair<Int?, Int?> {
        val t = s?.trim() ?: return null to null
        val slash = t.indexOf('/')
        return if (slash >= 0) t.substring(0, slash).trim().toIntOrNull() to t.substring(slash + 1).trim().toIntOrNull() else t.toIntOrNull() to null
    }

    /** ビットレート(bps)→「320 kbps」 */
    fun bitrate(bps: Int): String? = if (bps > 0) "${(bps + 500) / 1000} kbps" else null

    /** サンプリング周波数(Hz)→「44.1 kHz」 */
    fun sampleRate(hz: Int): String? = if (hz > 0) (if (hz % 1000 == 0) "${hz / 1000} kHz" else "%.1f kHz".format(hz / 1000.0)) else null
}

/** 曲のフォルダにある、アルバムの絵(cover.jpg など)を探す。 */
object CoverFiles {
    private val names = listOf("cover", "folder", "front", "album", "albumart", "albumartsmall", "artwork", "jacket", "ジャケット", "カバー")
    private val exts = listOf("jpg", "jpeg", "png", "webp")

    /** 候補の順(名前→拡張子)で、いちばん先頭のもの。無ければ null。名前は大文字小文字を区別しない。 */
    fun pick(files: List<String>): String? {
        for (n in names) for (e in exts) {
            files.firstOrNull { it.equals("$n.$e", ignoreCase = true) }?.let { return it }
        }
        return null
    }
}
