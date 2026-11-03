package local.filemanager

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.IOException

/**
 * RandomSource(NAS 上のファイルなど)の指定した位置から順に読む。細かい読み出しのたびに通信しないよう、
 * まとめて(既定は 512KB)読んで渡す。Android に依存しないので PC 上でテストできる。
 * length が負のときは、ファイルの終わりまで。
 */
class ChunkedReader(
    private val src: RandomSource,
    start: Long,
    length: Long,
    chunkSize: Int = 512 * 1024,
) : ShimReader {
    private var pos = start

    /** まだ src から取り出していない分 */
    private var fetchRemaining = if (length >= 0) minOf(length, maxOf(0L, src.size - start)) else maxOf(0L, src.size - start)
    private val buf = ByteArray(chunkSize)
    private var bufPos = 0
    private var bufLen = 0

    /** 読み残し(取り出し済みで渡していない分も含む) */
    val unread: Long get() = fetchRemaining + (bufLen - bufPos)

    /** 読めたバイト数。終わりなら -1。 */
    override fun read(out: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (bufPos >= bufLen) {
            if (fetchRemaining <= 0) return -1
            val n = src.readAt(pos, buf, 0, minOf(buf.size.toLong(), fetchRemaining).toInt())
            if (n <= 0) throw IOException("ファイルが途中までしか読めませんでした")
            pos += n
            fetchRemaining -= n
            bufPos = 0
            bufLen = n
        }
        val n = minOf(len, bufLen - bufPos)
        System.arraycopy(buf, bufPos, out, off, n)
        bufPos += n
        return n
    }
}

/** 再生エンジンに NAS 上のファイルを渡すための窓口。URI の path を、Fs 上のパスとして開く。 */
@androidx.annotation.OptIn(UnstableApi::class)
class RandomSourceDataSource(private val opener: (String) -> RandomSource?) : BaseDataSource(false) {
    private var uri: Uri? = null
    private var src: RandomSource? = null
    private var reader: ShimReader? = null
    private var started = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val path = dataSpec.uri.path ?: throw IOException("場所が分かりません")
        val s = opener(path) ?: throw IOException("ファイルを開けません")
        src = s
        // .m2ts(192 バイト単位)や .aiff は、再生エンジンが読める形(188 バイトの TS / WAV)に見せかける
        val shim = if (Shims.needed(path)) Shims.detect(path, s) else null
        val total = shim?.size ?: s.size
        if (dataSpec.position > total) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        val length = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, total - dataSpec.position) else total - dataSpec.position
        reader = shim?.reader(dataSpec.position, length) ?: ChunkedReader(s, dataSpec.position, length)
        started = true
        transferStarted(dataSpec)
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val n = reader?.read(buffer, offset, length) ?: return C.RESULT_END_OF_INPUT
        if (n < 0) return C.RESULT_END_OF_INPUT
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        reader = null
        src?.close()
        src = null
        if (started) {
            started = false
            transferEnded()
        }
    }

    class Factory(private val opener: (String) -> RandomSource?) : DataSource.Factory {
        override fun createDataSource(): DataSource = RandomSourceDataSource(opener)
    }
}

/** 再生時間の表示(1:05 / 1:02:03)。 */
fun formatClock(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000)
    val h = s / 3600
    val m = s / 60 % 60
    val sec = s % 60
    return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(java.util.Locale.US, "%d:%02d", m, sec)
}

/** 動画・音声として再生してよいか。 */
fun isPlayable(kind: FileKind): Boolean = kind == FileKind.VIDEO || kind == FileKind.AUDIO

/** 音声・字幕の切り替えの一覧に出す名前。Android に依存しない。 */
object TrackLabels {
    /** 「ja」「jpn」→「日本語」。分からなければ(言語の指定が無い・不明)null。 */
    fun languageName(tag: String?): String? {
        if (tag.isNullOrBlank() || tag == "und" || tag == "zxx") return null
        val name = java.util.Locale.forLanguageTag(tag).getDisplayLanguage(java.util.Locale.JAPANESE)
        return name.takeIf { it.isNotBlank() && !it.equals(tag, ignoreCase = true) } ?: tag
    }

    fun channels(n: Int): String? = when {
        n <= 0 -> null
        n == 1 -> "モノラル"
        n == 2 -> "ステレオ"
        n == 6 -> "5.1ch"
        n == 8 -> "7.1ch"
        else -> "${n}ch"
    }

    fun codec(mime: String?): String? = when (mime?.lowercase()) {
        null -> null
        "audio/mp4a-latm" -> "AAC"
        "audio/ac3" -> "AC3"
        "audio/eac3", "audio/eac3-joc" -> "E-AC3"
        "audio/vnd.dts", "audio/vnd.dts.hd" -> "DTS"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/flac" -> "FLAC"
        "audio/mpeg" -> "MP3"
        "audio/mpeg-l2" -> "MP2"
        "audio/raw" -> "PCM"
        "application/x-subrip" -> "SRT"
        "text/x-ssa" -> "ASS"
        "text/vtt" -> "WebVTT"
        "application/cea-608", "application/cea-708" -> "CC"
        "application/x-quicktime-tx3g", "text/3gpp-tt" -> "tx3g"
        "application/dvbsubs", "application/pgs", "application/vobsub" -> "画像字幕"
        else -> null
    }

    private fun join(vararg parts: String?) = parts.filterNotNull().filter { it.isNotBlank() }.joinToString("・")

    /** 例: 「音声2: 日本語・ステレオ・AAC」。ordinal は 1 から。 */
    fun audio(ordinal: Int, language: String?, label: String?, channelCount: Int, mime: String?): String {
        val lang = languageName(language)
        val body = join(lang, label?.takeIf { it != lang }, channels(channelCount), codec(mime))
        return "音声$ordinal" + if (body.isEmpty()) "" else ": $body"
    }

    /** 例: 「字幕1: 日本語・ASS」。 */
    fun text(ordinal: Int, language: String?, label: String?, mime: String?): String {
        val lang = languageName(language)
        val body = join(lang, label?.takeIf { it != lang }, codec(mime))
        return "字幕$ordinal" + if (body.isEmpty()) "" else ": $body"
    }
}

/** 動画の隣に置かれた字幕ファイルの選び方。Android に依存しない。 */
object SubtitleFiles {
    class Found(val name: String, val ext: String, val language: String?)

    val extensions = setOf("srt", "ass", "ssa", "vtt")

    /**
     * 動画のファイル名(videoName)と、同じフォルダのファイル名から、字幕を選ぶ。
     * 「動画名.srt」「動画名.ja.srt」「動画名.jpn.ass」のように、動画名で始まって字幕の拡張子で終わるもの。
     * 言語の指定が無いものを先に、あとは名前順。多すぎないよう最大 8 つまで。
     */
    fun pick(videoName: String, siblings: List<String>): List<Found> {
        val base = videoName.substringBeforeLast('.', videoName)
        if (base.isEmpty()) return emptyList()
        val out = ArrayList<Found>()
        for (n in siblings) {
            val ext = n.substringAfterLast('.', "").lowercase()
            if (ext !in extensions || !n.startsWith("$base.", ignoreCase = true)) continue
            // 「動画名.srt」は言語の指定なし、「動画名.ja.srt」は言語 ja
            val end = n.length - ext.length - 1
            val middle = if (end > base.length) n.substring(base.length + 1, end) else ""
            out.add(Found(n, ext, middle.ifEmpty { null }))
        }
        return out.sortedWith(compareBy<Found>({ it.language != null }, { it.name.lowercase() })).take(8)
    }
}
