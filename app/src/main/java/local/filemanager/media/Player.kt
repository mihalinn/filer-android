package local.filemanager

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.media3.common.MediaMetadata
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** 再生速度の選択肢(倍)。メニューの並びと同じ。 */
private val PLAY_SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** 音声・字幕の切り替えの選択肢 1 つ。 */
private class TrackOption(val group: Tracks.Group, val index: Int, val label: String, val selected: Boolean)

private sealed interface SubState {
    data object Looking : SubState
    data class Done(val subs: List<SideSub>) : SubState
}

/** 動画・音声を、アプリの中で再生する画面。同じフォルダの同じ種類のファイルを、前後に送れる。 */
@Composable
internal fun PlayerScreen(vm: FmViewModel, req: FmViewModel.ViewerRequest, onClose: () -> Unit) {
    val sub by produceState<SubState>(SubState.Looking, req) {
        value = SubState.Done(withContext(Dispatchers.IO) { findSubtitles(req.fs, req.files[req.index]) })
    }
    when (val s = sub) {
        SubState.Looking -> Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { } }, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        is SubState.Done -> PlayerBody(vm, req, s.subs, onClose)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun PlayerBody(vm: FmViewModel, req: FmViewModel.ViewerRequest, subs: List<SideSub>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.findActivity() }
    val isAudio = req.files[req.index].kind == FileKind.AUDIO
    // 再生エンジンは PlaybackHolder が持つ(画面を消しても続く)。同じ依頼なら、続いている再生にそのままつなぐ
    val player = remember { PlaybackHolder.acquire(ctx, req, subs) }

    var wantPlay by remember { mutableStateOf(player.playWhenReady) }
    var state by remember { mutableIntStateOf(player.playbackState) }
    var index by remember { mutableIntStateOf(player.currentMediaItemIndex.coerceIn(0, req.files.lastIndex)) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var cues by remember { mutableStateOf("") }
    var videoNote by remember { mutableStateOf<String?>(null) }
    var speed by remember { mutableFloatStateOf(player.playbackParameters.speed) }
    var subsOn by remember { mutableStateOf(true) }
    var ratio by remember { mutableFloatStateOf(16f / 9f) }
    var controls by remember { mutableStateOf(true) }
    var touched by remember { mutableIntStateOf(0) }
    var landscape by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var trackInfo by remember { mutableStateOf(player.currentTracks) }
    var tags by remember { mutableStateOf(player.mediaMetadata) }
    var songInfo by remember { mutableStateOf(false) }
    val current = req.files[index.coerceIn(0, req.files.lastIndex)]
    // 曲に埋め込まれたジャケット。無ければ、同じフォルダの cover.jpg などを探す
    val embedded = remember(tags.artworkData) { tags.artworkData?.let { b -> ImageDecode.decodeBytes(b, 1024)?.asImageBitmap() } }
    val folderCover by produceState<ImageBitmap?>(null, current.path, isAudio) {
        value = if (!isAudio) null else withContext(Dispatchers.IO) { findFolderCover(req.fs, current) }
    }
    val cover = embedded ?: folderCover

    // 画素の縦横比(放送の 1440x1080 は横に縮めて記録され、16:9 で表示する)。端末のデコーダー経由では
    // 映像サイズに含まれるが、FFmpeg 経由では含まれないので、入力側の形式に書かれた値で補う
    fun updateRatio(videoSize: VideoSize) {
        if (videoSize.width <= 0 || videoSize.height <= 0) return
        val par = if (videoSize.pixelWidthHeightRatio != 1f) videoSize.pixelWidthHeightRatio
        else player.videoFormat?.pixelWidthHeightRatio?.takeIf { it > 0f } ?: 1f
        ratio = videoSize.width * par / videoSize.height
    }

    // 音声・字幕の切り替えの選択肢(対応しているものだけ)
    fun options(type: Int): List<TrackOption> {
        val out = ArrayList<TrackOption>()
        var n = 0
        for (g in trackInfo.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                n++
                val f = g.getTrackFormat(i)
                val label = if (type == C.TRACK_TYPE_AUDIO) TrackLabels.audio(n, f.language, f.label, f.channelCount, f.sampleMimeType)
                else TrackLabels.text(n, f.language, f.label, f.sampleMimeType)
                out.add(TrackOption(g, i, label, g.isTrackSelected(i)))
            }
        }
        return out
    }
    val audioOptions = options(C.TRACK_TYPE_AUDIO)
    val textOptions = options(C.TRACK_TYPE_TEXT)

    fun pickTrack(o: TrackOption) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(o.group.type, false)
            .setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, o.index))
            .build()
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                wantPlay = playWhenReady
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                state = playbackState
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                tags = mediaMetadata
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                index = player.currentMediaItemIndex
                tags = MediaMetadata.EMPTY
                error = null
                cues = ""
                videoNote = null
            }

            override fun onTracksChanged(tracks: Tracks) {
                trackInfo = tracks
                videoNote = if (isAudio) null else videoProblem(tracks)
            }

            override fun onPlayerError(e: PlaybackException) {
                error = Txt(R.string.player_001, e.errorCodeName)
            }

            override fun onCues(cueGroup: CueGroup) {
                cues = cueGroup.cues.mapNotNull { it.text?.toString() }.joinToString("\n")
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                updateRatio(videoSize)
            }

            override fun onRenderedFirstFrame() {
                updateRatio(player.videoSize)
            }
        }
        player.addListener(l)
        // 続いている再生につないだときのために、いまの状態を取り込む
        PlaybackHolder.attachUi()
        trackInfo = player.currentTracks
        tags = player.mediaMetadata
        videoNote = if (isAudio) null else videoProblem(player.currentTracks)
        updateRatio(player.videoSize)
        onDispose {
            player.removeListener(l)
            // 再生は止めない(止めるのは、画面を閉じたときの PlaybackHolder.stop)
            PlaybackHolder.detachUi()
            // 全画面にしていたら、向きを元に戻す
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // アプリが見えなくなっても再生は続ける。動画は、映像を作らなくてよいぶん電池を使わないよう、音だけにする
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, player) {
        fun videoOff(off: Boolean) {
            if (isAudio) return
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, off).build()
        }
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) videoOff(true) else if (e == Lifecycle.Event.ON_START) videoOff(false)
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0L
            delay(250)
        }
    }
    // 動画は、再生中に少し経つと操作パネルを隠す(音声は出したまま)
    // メニューを開いている間は隠さない(隠すとメニューだけ残り、次のタップで勝手に開き直ってしまう)
    LaunchedEffect(controls, wantPlay, touched, menu) {
        if (!isAudio && controls && wantPlay && error == null && !menu) {
            delay(3500)
            controls = false
        }
    }

    fun touch() {
        touched++
    }

    val showControls = isAudio || controls || error != null || !wantPlay || state == Player.STATE_ENDED || menu

    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            detectTapGestures(
                onTap = {
                    // メニューを閉じるためのタップは、操作パネルの開閉に使わない
                    if (menu) {
                        menu = false
                    } else {
                        controls = !controls
                    }
                    touch()
                },
                onDoubleTap = { o ->
                    // 画面の左半分は10秒戻し、右半分は10秒送り
                    val target = if (o.x < size.width / 2) player.currentPosition - 10_000 else player.currentPosition + 10_000
                    player.seekTo(target.coerceAtLeast(0))
                    touch()
                },
            )
        },
    ) {
        if (isAudio) {
            val year = tags.recordingYear ?: tags.releaseYear
            AudioFace(
                cover,
                MusicText.title(tags.title?.toString(), current.name),
                MusicText.lines(
                    tags.artist?.toString(), tags.albumArtist?.toString(), tags.albumTitle?.toString(), year,
                    tags.trackNumber, tags.totalTrackCount, tags.discNumber, tags.totalDiscCount,
                ),
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AndroidView(
                    factory = { c ->
                        SurfaceView(c).also {
                            it.keepScreenOn = true
                            player.setVideoSurfaceView(it)
                        }
                    },
                    onRelease = { player.clearVideoSurfaceView(it) },
                    modifier = Modifier.aspectRatio(ratio),
                )
            }
            if (subsOn && cues.isNotBlank()) {
                Text(
                    cues, color = Color.White, fontSize = 17.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp, start = 24.dp, end = 24.dp)
                        .background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }

        if (state == Player.STATE_BUFFERING && error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }
        videoNote?.let { note ->
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = 24.dp).background(Color(0xCC000000)).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(note, color = Color(0xFFFFE082), fontSize = 13.sp, textAlign = TextAlign.Center)
                Text(Txt(R.string.player_002), color = Color(0xFFCCCCCC), fontSize = 12.sp, textAlign = TextAlign.Center)
                TextButton(onClick = { vm.openItem(current, req.fs) }) { Text(Txt(R.string.player_003)) }
            }
        }
        error?.let { msg ->
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(msg, color = Color.White, textAlign = TextAlign.Center)
                Text(Txt(R.string.player_004), color = Color(0xFFCCCCCC), fontSize = 12.sp, textAlign = TextAlign.Center)
                TextButton(onClick = { vm.openItem(current, req.fs) }) { Text(Txt(R.string.player_005)) }
            }
        }

        if (showControls) {
            PlayerTopBar(current.name, if (req.files.size > 1) "${index + 1} / ${req.files.size}" else null, onClose, Modifier.align(Alignment.TopCenter)) {
                Box {
                    PlayerMenuButton { menu = true }
                    // プレビューの右上メニュー(ViewerMenu)と同じ見た目。並びも同じで、開く・共有 → 再生 → 表示 → ファイル
                    val playItems = ArrayList<VMenuItem>()
                    playItems.add(
                        VMenuItem.Segment(Txt(R.string.player_006), PLAY_SPEEDS.map { "%.2g".format(it) }, PLAY_SPEEDS.indexOf(speed)) { i ->
                            speed = PLAY_SPEEDS[i]
                            player.setPlaybackSpeed(speed)
                        },
                    )
                    if (audioOptions.size > 1) {
                        playItems.add(
                            VMenuItem.Choice(Icons.Default.Audiotrack, Txt(R.string.player_007), audioOptions.map { it.label }, audioOptions.indexOfFirst { it.selected }) { i ->
                                pickTrack(audioOptions[i])
                            },
                        )
                    }
                    if (textOptions.isNotEmpty()) {
                        playItems.add(
                            VMenuItem.Choice(
                                Icons.Default.Subtitles, Txt(R.string.player_008), listOf(Txt(R.string.player_009)) + textOptions.map { it.label },
                                if (!subsOn) 0 else textOptions.indexOfFirst { it.selected } + 1,
                            ) { i ->
                                if (i == 0) {
                                    subsOn = false
                                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                                } else {
                                    subsOn = true
                                    pickTrack(textOptions[i - 1])
                                }
                            },
                        )
                    }
                    val viewItems = ArrayList<VMenuItem>()
                    if (!isAudio) {
                        viewItems.add(
                            VMenuItem.Toggle(Icons.Default.ScreenRotation, Txt(R.string.player_010), landscape) {
                                landscape = it
                                activity?.requestedOrientation =
                                    if (it) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            },
                        )
                    }
                    val fileItems = ArrayList<VMenuItem>()
                    if (isAudio) fileItems.add(VMenuItem.Action(Icons.Default.Info, Txt(R.string.player_011)) { songInfo = true })
                    ViewerMenu(
                        expanded = menu, dark = true, onDismiss = { menu = false },
                        onOpenExternal = { vm.openItem(current, req.fs) },
                        onShare = { vm.shareItems(listOf(current), req.fs) },
                        sections = listOf(VMenuSection(Txt(R.string.player_012), playItems), VMenuSection(Txt(R.string.player_013), viewItems), VMenuSection(Txt(R.string.player_014), fileItems)),
                    )
                }
            }

            PlayerTransport(
                (if (isAudio) Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 62.dp) else Modifier.align(Alignment.Center)),
                multi = req.files.size > 1, playing = wantPlay && state != Player.STATE_ENDED,
                onPrev = { player.seekToPreviousMediaItem(); touch() },
                onBack10 = { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)); touch() },
                onToggle = {
                    if (state == Player.STATE_ENDED) player.seekToDefaultPosition(0)
                    if (error != null) {
                        error = null
                        player.prepare()
                    }
                    if (wantPlay && state != Player.STATE_ENDED) player.pause() else player.play()
                    touch()
                },
                onFwd10 = { player.seekTo(player.currentPosition + 10_000); touch() },
                onNext = { player.seekToNextMediaItem(); touch() },
            )

            PlayerSeekBar(Modifier.align(Alignment.BottomCenter), position, duration, onSeekTo = { player.seekTo(it) }, onTouch = { touch() })
        }
        if (songInfo) SongInfoDialog(current, tags, trackInfo, duration) { songInfo = false }
    }
}
