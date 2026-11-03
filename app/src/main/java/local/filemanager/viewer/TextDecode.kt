package local.filemanager

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** テキストファイルの文字コードを見分けて文字列にする。Android に依存しないので PC 上でテストできる。 */
object TextDecode {
    data class Result(val text: String, val charsetName: String)

    private val utf8 = Charsets.UTF_8
    private val shiftJis: Charset by lazy { Charset.forName("windows-31j") }
    private val eucJp: Charset by lazy { Charset.forName("EUC-JP") }

    /**
     * BOM があればそれに従い、無ければ UTF-8 → Shift_JIS → EUC-JP の順に、エラー無しで読めたものを採用する。
     * truncated=true は、ファイルの途中で切った(末尾の文字が欠けているかもしれない)バイト列。
     */
    fun decode(bytes: ByteArray, truncated: Boolean = false): Result {
        bom(bytes)?.let { (cs, skip) ->
            return Result(String(bytes, skip, bytes.size - skip, cs), cs.name())
        }
        for (cs in listOf(utf8, shiftJis, eucJp)) {
            strict(bytes, cs, truncated)?.let { return Result(it, cs.name()) }
        }
        // どれでも読めなければ、読めない文字を「�」にして UTF-8 として見せる
        return Result(String(bytes, utf8), "UTF-8")
    }

    /** 先頭から最大 max バイトを読む。まだ続きがあれば(切った)true を一緒に返す。 */
    fun readUpTo(input: java.io.InputStream, max: Int): Pair<ByteArray, Boolean> {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0
        while (total < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - total))
            if (n < 0) return out.toByteArray() to false
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray() to (input.read() >= 0)
    }

    private fun bom(b: ByteArray): Pair<Charset, Int>? = when {
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> utf8 to 3
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> Charsets.UTF_16LE to 2
        b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> Charsets.UTF_16BE to 2
        else -> null
    }

    private fun strict(bytes: ByteArray, cs: Charset, truncated: Boolean): String? {
        val decoder = cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            // 途中で切ったデータは、末尾の欠けた文字だけがエラーの原因かもしれないので、末尾を落として試す
            if (truncated) lenientTail(bytes, cs) else null
        }
    }

    /** 切れた末尾(最大3バイト)を落としても strict に読めるなら、その結果を使う。 */
    private fun lenientTail(bytes: ByteArray, cs: Charset): String? {
        for (cut in 1..3) {
            if (bytes.size <= cut) break
            val d = cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            try {
                return d.decode(ByteBuffer.wrap(bytes, 0, bytes.size - cut)).toString()
            } catch (e: CharacterCodingException) {
                // もう少し落として試す
            }
        }
        return null
    }
}
