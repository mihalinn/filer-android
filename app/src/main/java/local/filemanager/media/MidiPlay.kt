package local.filemanager

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private const val MIDI_LIMIT = 32L * 1024 * 1024

fun isMidiName(name: String): Boolean = FileOps.extensionOf(name).let { it == "mid" || it == "midi" || it == "kar" || it == "rmi" }

private fun midiPath(ctx: android.content.Context, fs: Fs, item: FileItem, task: BgTask): String {
    if (fs.isLocal) return item.path
    if (item.size > MIDI_LIMIT) throw OpException(Txt(R.string.midi_play_001))
    val dir = File(ctx.cacheDir, "midiview").also { it.mkdirs() }
    val f = File(dir, cacheKey(fs.id, item.path, item.size, item.modified) + ".mid")
    if (!(f.exists() && f.length() == item.size)) {
        val part = File(dir, f.name + ".part")
        BgTasks.run(task, item.size) { task.download(fs, item.path, part) }
        f.delete()
        part.renameTo(f)
    }
    f.setLastModified(System.currentTimeMillis())
    trimCacheTree(dir, f, 64L * 1024 * 1024, 24L * 3600 * 1000)
    return f.path
}

/**
 * MIDI(.mid など)の再生画面。端末の MIDI 音源(MediaPlayer)で鳴らす。
 * 見た目と操作は、ほかの音声(PlayerScreen)と同じ部品(PlayerChrome.kt・AudioFace)で作る。
 * 同じフォルダの MIDI を前後に送れて、曲が終わると次へ進む。
 */
@Composable
internal fun MidiScreen(vm: FmViewModel, req: FmViewModel.ViewerRequest, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var index by remember { mutableIntStateOf(req.index.coerceIn(0, req.files.lastIndex)) }
    val item = req.files[index]
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var ready by remember(index) { mutableStateOf(false) }
    var playing by remember(index) { mutableStateOf(false) }
    var error by remember(index) { mutableStateOf<String?>(null) }
    var duration by remember(index) { mutableIntStateOf(0) }
    var position by remember(index) { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    val last = req.files.lastIndex

    LaunchedEffect(index) {
        val mp = MediaPlayer()
        player = mp
        val task = BgTask(Txt(R.string.midi_play_002, item.name), Txt(R.string.midi_play_003, item.name), Txt(R.string.midi_play_004, item.name))
        try {
            val path = withContext(Dispatchers.IO) { midiPath(ctx, req.fs, item, task) }
            mp.setDataSource(path)
            mp.setOnPreparedListener { duration = it.duration; ready = true; it.start(); playing = true }
            mp.setOnCompletionListener {
                playing = false
                position = 0
                if (index < last) index++
            }
            mp.setOnErrorListener { _, _, _ -> error = Txt(R.string.midi_play_005); playing = false; true }
            mp.prepareAsync()
            awaitCancellation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: Txt(R.string.midi_play_006)
        } finally {
            task.cancel()
            runCatching { mp.release() }
            if (player === mp) player = null
        }
    }
    LaunchedEffect(playing, player) {
        while (playing) {
            position = runCatching { player?.currentPosition }.getOrNull() ?: position
            delay(250)
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) { runCatching { player?.pause() }; playing = false }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    fun seekBy(deltaMs: Int) {
        if (!ready) return
        val to = (position + deltaMs).coerceIn(0, duration)
        runCatching { player?.seekTo(to) }
        position = to
    }

    // 下の一覧に触れないよう、画面全体で触れた操作を受け止める(ViewerScreen と同じ)。ダブルタップは左半分で10秒戻し、右半分で10秒送り
    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            detectTapGestures(onDoubleTap = { o -> seekBy(if (o.x < size.width / 2) -10_000 else 10_000) })
        },
    ) {
        AudioFace(null, MusicText.title(null, item.name), listOf("MIDI"))

        val msg = error
        if (msg != null) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(msg, color = Color.White, textAlign = TextAlign.Center)
                Text(Txt(R.string.midi_play_007), color = Color(0xFFCCCCCC), fontSize = 12.sp, textAlign = TextAlign.Center)
                TextButton(onClick = { vm.openItem(item, req.fs) }) { Text(Txt(R.string.midi_play_008)) }
            }
        } else if (!ready) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }

        PlayerTopBar(item.name, if (req.files.size > 1) "${index + 1} / ${req.files.size}" else null, onClose, Modifier.align(Alignment.TopCenter)) {
            Box {
                PlayerMenuButton { menu = true }
                ViewerMenu(
                    expanded = menu, dark = true, onDismiss = { menu = false },
                    onOpenExternal = { vm.openItem(item, req.fs) },
                    onShare = { vm.shareItems(listOf(item), req.fs) },
                    sections = emptyList(),
                )
            }
        }

        PlayerTransport(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 62.dp),
            multi = req.files.size > 1, playing = playing,
            onPrev = { if (index > 0) index-- },
            onBack10 = { seekBy(-10_000) },
            onToggle = {
                val p = player
                if (p != null && ready) {
                    if (playing) { runCatching { p.pause() }; playing = false } else { runCatching { p.start() }; playing = true }
                }
            },
            onFwd10 = { seekBy(10_000) },
            onNext = { if (index < last) index++ },
        )

        PlayerSeekBar(
            Modifier.align(Alignment.BottomCenter), position.toLong(), duration.toLong(),
            onSeekTo = { ms -> runCatching { player?.seekTo(ms.toInt()) }; position = ms.toInt() },
        )
    }
}
