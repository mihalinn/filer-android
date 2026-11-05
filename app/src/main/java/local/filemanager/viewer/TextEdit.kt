package local.filemanager

import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 保存するときの文字コード。 */
enum class TextEnc(private val plain: String, val charset: Charset, val bom: ByteArray) {
    UTF8("UTF-8", Charsets.UTF_8, byteArrayOf()),
    UTF8_BOM("UTF-8", Charsets.UTF_8, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())),
    SJIS("Shift_JIS", Charset.forName("windows-31j"), byteArrayOf()),
    EUC("EUC-JP", Charset.forName("EUC-JP"), byteArrayOf()),
    UTF16LE("UTF-16 LE", Charsets.UTF_16LE, byteArrayOf(0xFF.toByte(), 0xFE.toByte())),
    UTF16BE("UTF-16 BE", Charsets.UTF_16BE, byteArrayOf(0xFE.toByte(), 0xFF.toByte()));

    /** 画面に出す名前。説明(BOM付きなど)は言語に合わせる。 */
    val label: String get() = when (this) {
        UTF8_BOM -> Txt(R.string.enc_utf8_bom)
        UTF16LE -> Txt(R.string.enc_utf16le)
        UTF16BE -> Txt(R.string.enc_utf16be)
        else -> plain
    }
}

/** 改行の形。 */
enum class Eol(private val plain: String, val seq: String) {
    CRLF("CRLF(Windows)", "\r\n"),
    LF("LF(Unix)", "\n"),
    CR("CR", "\r");

    val label: String get() = if (this == CR) Txt(R.string.eol_cr) else plain
}

/** 編集用に読み込んだ文字。text の改行は LF にそろえてある。 */
class EditableText(val text: String, val enc: TextEnc, val eol: Eol, val mixedEol: Boolean)

/** テキストファイルを、編集して元の文字コード・改行のまま書き戻すための読み書き。Android に依存しない。 */
object TextFile {
    /** 編集できる大きさの上限。これ以上は入力欄が重くなる。 */
    const val MAX_BYTES = 1024 * 1024

    fun parse(bytes: ByteArray): EditableText {
        if (bytes.size > MAX_BYTES) throw OpException(Txt(R.string.text_edit_001, MAX_BYTES / 1024 / 1024))
        val utf16 = bytes.size >= 2 && ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) || (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))
        if (!utf16 && bytes.any { it == 0.toByte() }) throw OpException(Txt(R.string.text_edit_002))
        val r = TextDecode.decode(bytes)
        val enc = when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> TextEnc.UTF8_BOM
            utf16 && bytes[0] == 0xFF.toByte() -> TextEnc.UTF16LE
            utf16 -> TextEnc.UTF16BE
            r.charsetName.equals("windows-31j", ignoreCase = true) -> TextEnc.SJIS
            r.charsetName.equals("EUC-JP", ignoreCase = true) -> TextEnc.EUC
            else -> TextEnc.UTF8
        }
        // どの文字コードでも読めなかったときは「�」が混ざる。そのまま保存すると元のバイトが失われるので、断る
        if (r.text.indexOf('�') >= 0) {
            val back = runCatching { raw(r.text, enc) }.getOrNull()
            if (back == null || !back.contentEquals(bytes)) throw OpException(Txt(R.string.text_edit_003))
        }
        var crlf = 0
        var lf = 0
        var cr = 0
        var i = 0
        val t = r.text
        while (i < t.length) {
            when (t[i]) {
                '\r' -> if (i + 1 < t.length && t[i + 1] == '\n') { crlf++; i++ } else cr++
                '\n' -> lf++
            }
            i++
        }
        val kinds = listOf(crlf, lf, cr).count { it > 0 }
        val eol = when {
            kinds == 0 -> Eol.LF
            crlf >= lf && crlf >= cr -> Eol.CRLF
            lf >= cr -> Eol.LF
            else -> Eol.CR
        }
        val normalized = if (crlf + cr == 0) t else t.replace("\r\n", "\n").replace('\r', '\n')
        return EditableText(normalized, enc, eol, kinds > 1)
    }

    /** text(改行は LF)を、指定の文字コード・改行のバイト列にする。その文字コードで表せない文字があれば OpException。 */
    fun encode(text: String, enc: TextEnc, eol: Eol): ByteArray {
        val body = if (eol == Eol.LF) text else text.replace("\n", eol.seq)
        return raw(body, enc)
    }

    private fun raw(body: String, enc: TextEnc): ByteArray {
        val encoder = enc.charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val bb = try {
            encoder.encode(CharBuffer.wrap(body))
        } catch (e: CharacterCodingException) {
            val probe = enc.charset.newEncoder()
            var bad: String? = null
            var i = 0
            while (i < body.length && bad == null) {
                val n = Character.charCount(body.codePointAt(i))
                val s = body.substring(i, i + n)
                if (!probe.canEncode(s)) bad = s
                i += n
            }
            throw OpException(Txt(R.string.text_edit_004, bad ?: "?", enc.label))
        }
        val out = ByteArray(enc.bom.size + bb.remaining())
        System.arraycopy(enc.bom, 0, out, 0, enc.bom.size)
        bb.get(out, enc.bom.size, bb.remaining())
        return out
    }
}

/**
 * 元に戻す・やり直す。続けて打った文字(間が gapMs 以内)は 1 回分にまとめる。
 * 一度に大きく変わったとき(貼り付けや置換)は、まとめずにそれだけで 1 回分にする。
 */
class UndoHistory(private val maxCount: Int = 200, private val maxChars: Long = 4_000_000, private val gapMs: Long = 800) {
    data class Snap(val text: String, val start: Int, val end: Int)

    private val past = ArrayDeque<Snap>()
    private val future = ArrayDeque<Snap>()
    private var lastAt = Long.MIN_VALUE / 2
    private var chars = 0L

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    /** 文字が変わったとき。prev は変わる前の状態。 */
    fun changed(prev: Snap, now: Long, own: Boolean = false) {
        if (own || past.isEmpty() || now - lastAt > gapMs) push(prev)
        // 大きな変更の直後に打った文字は、その変更と別の 1 回分にする
        lastAt = if (own) Long.MIN_VALUE / 2 else now
        future.clear()
    }

    fun undo(cur: Snap): Snap? {
        val p = past.removeLastOrNull() ?: return null
        chars -= p.text.length
        future.addLast(cur)
        lastAt = Long.MIN_VALUE / 2
        return p
    }

    fun redo(cur: Snap): Snap? {
        val n = future.removeLastOrNull() ?: return null
        push(cur)
        lastAt = Long.MIN_VALUE / 2
        return n
    }

    private fun push(s: Snap) {
        past.addLast(s)
        chars += s.text.length
        while (past.size > maxCount || (chars > maxChars && past.size > 1)) chars -= past.removeFirst().text.length
    }
}

/** 検索と置換(大文字小文字は区別しない)。 */
object TextFind {
    /** from 以降で最初に見つかる位置。無ければ先頭から探し直す。見つからなければ -1。 */
    fun next(text: String, query: String, from: Int): Int {
        if (query.isEmpty()) return -1
        val i = text.indexOf(query, from.coerceIn(0, text.length), ignoreCase = true)
        return if (i >= 0) i else text.indexOf(query, 0, ignoreCase = true)
    }

    /** before より前で最後に見つかる位置。無ければ末尾から探し直す。見つからなければ -1。 */
    fun prev(text: String, query: String, before: Int): Int {
        if (query.isEmpty() || text.isEmpty()) return -1
        val i = if (before <= 0) -1 else text.lastIndexOf(query, before - 1, ignoreCase = true)
        return if (i >= 0) i else text.lastIndexOf(query, text.length, ignoreCase = true)
    }

    fun count(text: String, query: String): Int {
        if (query.isEmpty()) return 0
        var n = 0
        var i = text.indexOf(query, 0, ignoreCase = true)
        while (i >= 0) {
            n++
            i = text.indexOf(query, i + query.length, ignoreCase = true)
        }
        return n
    }

    /** 全部置き換える。置き換えた数と一緒に返す。 */
    fun replaceAll(text: String, query: String, with: String): Pair<String, Int> {
        val n = count(text, query)
        if (n == 0) return text to 0
        return Regex(Regex.escape(query), RegexOption.IGNORE_CASE).replace(text, Regex.escapeReplacement(with)) to n
    }
}
