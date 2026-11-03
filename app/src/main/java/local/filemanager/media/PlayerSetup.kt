package local.filemanager

import android.content.Context
import android.media.MediaCodecList
import android.net.Uri
import android.os.Handler
import android.util.Base64
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegVideoRenderer
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import java.io.File

/** 動画の隣にある字幕ファイル 1 つ分(UTF-8 の文字にしたもの)。 */
internal class SideSub(val label: String, val mime: String, val text: String, val language: String?)

/**
 * 動画の隣にある字幕(.srt / .ass / .ssa / .vtt。「動画名.ja.srt」のように言語つきも)を探す。無ければ空。通信があるので裏で呼ぶ。
 */
internal fun findSubtitles(fs: Fs, video: FileItem): List<SideSub> {
    if (video.kind != FileKind.VIDEO) return emptyList()
    val dir = fs.parent(video.path) ?: return emptyList()
    val siblings = runCatching { fs.list(dir) }.getOrNull() ?: return emptyList()
    val sizes = siblings.filter { !it.isDir }.associate { it.name to it.size }
    val out = ArrayList<SideSub>()
    for (f in SubtitleFiles.pick(video.name, sizes.keys.toList())) {
        if ((sizes[f.name] ?: Long.MAX_VALUE) > 2 * 1024 * 1024) continue
        val bytes = runCatching { fs.open(fs.child(dir, f.name)).use { it.readBytes() } }.getOrNull() ?: continue
        val mime = when (f.ext) {
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "vtt" -> MimeTypes.TEXT_VTT
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        out.add(SideSub(f.name, mime, TextDecode.decode(bytes).text, f.language))
    }
    return out
}

private fun subtitleUri(mime: String, text: String): Uri =
    Uri.parse("data:$mime;base64," + Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
/**
 * 端末のデコーダーが対応していない形式(放送録画の MPEG-2 など)は、FFmpeg のソフトウェア解読で再生する。
 * 端末のデコーダーが先に選ばれ、FFmpeg はその後ろに置く(端末で再生できるものは今までどおり端末に任せる)。
 */
@androidx.annotation.OptIn(UnstableApi::class)
private class FfmpegRenderersFactory(ctx: Context) : DefaultRenderersFactory(ctx) {
    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        super.buildVideoRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
            eventHandler, eventListener, allowedVideoJoiningTimeMs, out,
        )
        runCatching {
            out.add(FfmpegVideoRenderer(allowedVideoJoiningTimeMs, eventHandler, eventListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY))
        }
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
            audioSink, eventHandler, eventListener, out,
        )
        runCatching { out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink)) }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun createPlayer(ctx: Context, req: FmViewModel.ViewerRequest, subs: List<SideSub>): ExoPlayer {
    val fs = req.fs
    // 「filer」の宛先(NAS のファイル、と、変換が要る端末内のファイル)は、RandomSourceDataSource で読む
    val upstream: DataSource.Factory = DefaultDataSource.Factory(ctx, RandomSourceDataSource.Factory { path -> openSource(fs, path) })
    // 放送を録画した TS でも、途中の位置から正しく始められるように
    val extractors = DefaultExtractorsFactory()
        .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
        .setConstantBitrateSeekingEnabled(true)
    val renderers = FfmpegRenderersFactory(ctx).setEnableDecoderFallback(true)
    val player = ExoPlayer.Builder(ctx, renderers)
        .setMediaSourceFactory(DefaultMediaSourceFactory(upstream, extractors))
        .build()
    val items = req.files.mapIndexed { i, f ->
        val uri = if (fs.isLocal && !Shims.needed(f.name)) Uri.fromFile(File(f.path)) else Uri.Builder().scheme("filer").authority("nas").path(f.path).build()
        MediaItem.Builder().setUri(uri).setMediaId(f.path).apply {
            if (i == req.index && subs.isNotEmpty()) {
                setSubtitleConfigurations(
                    subs.mapIndexed { k, s ->
                        MediaItem.SubtitleConfiguration.Builder(subtitleUri(s.mime, s.text))
                            .setMimeType(s.mime).setLanguage(s.language ?: "ja").setLabel(s.label)
                            .setSelectionFlags(if (k == 0) C.SELECTION_FLAG_DEFAULT else 0).build()
                    },
                )
            }
        }.build()
    }
    player.setMediaItems(items, req.index, 0L)
    player.prepare()
    player.playWhenReady = true
    return player
}

/** この端末が、その形式の映像を再生できるデコーダーの名前(上限サイズつき)。無ければ空。 */
private fun deviceDecoders(mime: String): List<String> = runCatching {
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
        .map { info ->
            val v = runCatching { info.getCapabilitiesForType(mime).videoCapabilities }.getOrNull()
            if (v != null) "${info.name}(最大 ${v.supportedWidths.upper}x${v.supportedHeights.upper})" else info.name
        }
}.getOrDefault(emptyList())

/**
 * 映像があるのに再生できる状態になっていないとき、その理由を文にして返す(音だけ流れて映像が出ない場合の手がかり)。
 * 問題が無ければ null。
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun videoProblem(tracks: Tracks): String? {
    val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
    if (groups.isEmpty() || groups.any { g -> (0 until g.length).any { g.isTrackSelected(it) } }) return null
    val g = groups.first()
    val f = g.getTrackFormat(0)
    val mime = f.sampleMimeType ?: "不明"
    val why = when (g.getTrackSupport(0)) {
        C.FORMAT_UNSUPPORTED_TYPE -> "この形式を再生できるデコーダーがありません"
        C.FORMAT_EXCEEDS_CAPABILITIES -> "デコーダーの上限(大きさなど)を超えています"
        C.FORMAT_UNSUPPORTED_SUBTYPE -> "この形式の細かい種類に対応していません"
        else -> "再生できる状態になっていません"
    }
    val size = if (f.width > 0 && f.height > 0) " ${f.width}x${f.height}" else ""
    val dec = deviceDecoders(mime)
    return "映像($mime$size)を再生できない\n$why\nこの端末のデコーダー: " + if (dec.isEmpty()) "なし" else dec.joinToString(", ")
}
