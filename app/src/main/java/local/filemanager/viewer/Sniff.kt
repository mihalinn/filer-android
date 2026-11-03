package local.filemanager

/** ファイルの先頭の数バイトから、種類を推測する。拡張子が無い・分からないファイルを開くために使う。Android に依存しない。 */
object Sniff {
    /** label: 画面に出す種類の名前。isText: 文字のファイル。ext: 本来の拡張子の見当(無ければ null)。 */
    data class Guess(val label: String, val isText: Boolean, val ext: String? = null)

    private fun starts(b: ByteArray, len: Int, vararg sig: Int, at: Int = 0): Boolean {
        if (len < at + sig.size) return false
        for (i in sig.indices) if ((b[at + i].toInt() and 0xFF) != sig[i]) return false
        return true
    }

    private fun ascii(b: ByteArray, len: Int, at: Int, s: String): Boolean {
        if (len < at + s.length) return false
        for (i in s.indices) if (b[at + i].toInt().toChar() != s[i]) return false
        return true
    }

    fun detect(b: ByteArray, len: Int = b.size): Guess {
        val n = minOf(len, b.size)
        if (n == 0) return Guess("空のファイル", true)
        return when {
            starts(b, n, 0x89, 0x50, 0x4E, 0x47) -> Guess("PNG画像", false, "png")
            starts(b, n, 0xFF, 0xD8, 0xFF) -> Guess("JPEG画像", false, "jpg")
            ascii(b, n, 0, "GIF8") -> Guess("GIF画像", false, "gif")
            ascii(b, n, 0, "RIFF") && ascii(b, n, 8, "WEBP") -> Guess("WebP画像", false, "webp")
            ascii(b, n, 0, "RIFF") && ascii(b, n, 8, "WAVE") -> Guess("WAV音声", false, "wav")
            ascii(b, n, 0, "RIFF") && ascii(b, n, 8, "AVI ") -> Guess("AVI動画", false, "avi")
            ascii(b, n, 0, "%PDF") -> Guess("PDF", false, "pdf")
            starts(b, n, 0x50, 0x4B, 0x03, 0x04) -> Guess("ZIP形式(zip・apk・docx・xlsx・jar など)", false, "zip")
            starts(b, n, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> Guess("7z圧縮", false, "7z")
            ascii(b, n, 0, "Rar!") -> Guess("RAR圧縮", false, "rar")
            ascii(b, n, 2, "-l") && n >= 7 && b[6].toInt() == '-'.code && (b[4].toInt().toChar() == 'h' || b[4].toInt().toChar() == 'z') -> Guess("LZH圧縮", false, "lzh")
            starts(b, n, 0x1F, 0x8B) -> Guess("gzip圧縮", false, "gz")
            ascii(b, n, 0, "BZh") -> Guess("bzip2圧縮", false, "bz2")
            starts(b, n, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) -> Guess("xz圧縮", false, "xz")
            starts(b, n, 0x7F, 0x45, 0x4C, 0x46) -> Guess("実行ファイル(ELF)", false)
            ascii(b, n, 0, "MZ") && n > 64 -> Guess("Windowsの実行ファイル", false, "exe")
            starts(b, n, 0xCA, 0xFE, 0xBA, 0xBE) -> Guess("Javaのクラスファイル", false, "class")
            ascii(b, n, 0, "SQLite format 3") -> Guess("SQLiteデータベース", false, "sqlite")
            ascii(b, n, 0, "ID3") || starts(b, n, 0xFF, 0xFB) || starts(b, n, 0xFF, 0xF3) || starts(b, n, 0xFF, 0xF2) -> Guess("MP3音声", false, "mp3")
            ascii(b, n, 0, "fLaC") -> Guess("FLAC音声", false, "flac")
            ascii(b, n, 0, "OggS") -> Guess("Ogg(音声・動画)", false, "ogg")
            starts(b, n, 0x1A, 0x45, 0xDF, 0xA3) -> Guess("Matroska / WebM 動画", false, "mkv")
            ascii(b, n, 4, "ftyp") -> {
                val brand = if (n >= 12) String(b, 8, 4, Charsets.ISO_8859_1) else ""
                when {
                    brand.startsWith("heic") || brand.startsWith("heix") || brand.startsWith("mif1") -> Guess("HEIC画像", false, "heic")
                    brand.startsWith("avif") -> Guess("AVIF画像", false, "avif")
                    brand.startsWith("M4A") -> Guess("M4A音声", false, "m4a")
                    brand.startsWith("qt") -> Guess("QuickTime動画", false, "mov")
                    else -> Guess("MP4動画", false, "mp4")
                }
            }
            n > 376 && b[0].toInt() == 0x47 && b[188].toInt() == 0x47 && b[376].toInt() == 0x47 -> Guess("MPEG-TS(録画など)", false, "ts")
            n > 392 && b[4].toInt() == 0x47 && b[196].toInt() == 0x47 && b[388].toInt() == 0x47 -> Guess("MPEG-TS(M2TS)", false, "m2ts")
            ascii(b, n, 0, "{\\rtf") -> Guess("RTF文書", true, "rtf")
            ascii(b, n, 0, "<?xml") -> Guess("XML", true, "xml")
            ascii(b, n, 0, "<!DOCTYPE html") || ascii(b, n, 0, "<html") || ascii(b, n, 0, "<!doctype html") -> Guess("HTML", true, "html")
            ascii(b, n, 0, "#!") -> Guess("スクリプト", true, "sh")
            ascii(b, n, 0, "-----BEGIN") -> Guess("鍵・証明書(PEM)", true, "pem")
            looksLikeText(b, n) -> Guess("テキスト", true, "txt")
            else -> Guess("不明なデータ", false)
        }
    }

    /** 文字のファイルらしいか。NUL や制御文字がほとんど無ければ文字とみなす(UTF-16 は BOM があれば文字)。 */
    fun looksLikeText(b: ByteArray, len: Int = b.size): Boolean {
        val n = minOf(len, b.size)
        if (n == 0) return true
        if (n >= 2 && ((b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xFF) == 0xFE || (b[0].toInt() and 0xFF) == 0xFE && (b[1].toInt() and 0xFF) == 0xFF)) return true
        var bad = 0
        for (i in 0 until n) {
            val c = b[i].toInt() and 0xFF
            if (c == 0) return false
            if (c < 0x20 && c != 0x09 && c != 0x0A && c != 0x0D && c != 0x0C && c != 0x1B) bad++
            else if (c == 0x7F) bad++
        }
        return bad * 100 <= n // 制御文字が 1% 以下
    }
}

/** 文字の中の検索(大文字小文字は区別しない)。Android に依存しない。 */
object TextSearch {
    /** text の中で query に当たる範囲。query が空なら空。 */
    fun ranges(text: String, query: String): List<IntRange> {
        if (query.isEmpty()) return emptyList()
        val out = ArrayList<IntRange>()
        var i = text.indexOf(query, 0, ignoreCase = true)
        while (i >= 0) {
            out.add(i until i + query.length)
            i = text.indexOf(query, i + query.length, ignoreCase = true)
        }
        return out
    }

    /** lines のうち query を含む行の番号。 */
    fun matchingLines(lines: List<String>, query: String): List<Int> {
        if (query.isEmpty()) return emptyList()
        val out = ArrayList<Int>()
        for ((i, l) in lines.withIndex()) if (l.contains(query, ignoreCase = true)) out.add(i)
        return out
    }
}
