package local.filemanager

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList

/** いま読んでいるファイルの進み具合を、ビューアの下に出すための記録。NAS のファイルを開くときに使う。 */
class ReadTracker {
    class Stream(val path: String, val total: Long) {
        val startMs = System.currentTimeMillis()

        @Volatile var read = 0L

        @Volatile var lastMs = startMs
    }

    private val streams = CopyOnWriteArrayList<Stream>()

    fun begin(path: String, total: Long): Stream = Stream(path, total).also { streams.add(it) }
    fun end(s: Stream) {
        streams.remove(s)
    }

    fun active(): List<Stream> = streams.toList()
}

/**
 * 読んだバイト数を数える Fs。open で返す入力だけを数える(動画のように好きな位置から読むものは数えない)。
 * sizes は、開く前に分かっているファイルの大きさ(分からないものは割合を出さない)。
 */
class TrackedFs(private val base: Fs, private val sizes: Map<String, Long> = emptyMap(), val tracker: ReadTracker = ReadTracker()) : Fs by base {
    constructor(base: Fs, files: List<FileItem>) : this(base, files.associate { it.path to it.size })

    override fun open(path: String): InputStream {
        // 接続や認証に時間がかかるときも「読み込み中」と分かるよう、開く前から記録を始める
        val s = tracker.begin(path, sizes[path] ?: -1L)
        return try {
            CountingInput(base.open(path), s, tracker)
        } catch (e: Throwable) {
            tracker.end(s)
            throw e
        }
    }
}

private class CountingInput(input: InputStream, private val s: ReadTracker.Stream, private val tracker: ReadTracker) : FilterInputStream(input) {
    private var closed = false

    private fun bump(n: Long) {
        s.read += n
        s.lastMs = System.currentTimeMillis()
    }

    override fun read(): Int = super.read().also { if (it >= 0) bump(1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) bump(it.toLong()) }
    override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) bump(it) }
    override fun close() {
        try {
            super.close()
        } finally {
            if (!closed) {
                closed = true
                tracker.end(s)
            }
        }
    }
}

/** 直近の数秒の増え方から、1 秒あたりの速さを出す。 */
class SpeedMeter(private val windowMs: Long = 3000) {
    private val times = ArrayList<Long>()
    private val reads = ArrayList<Long>()

    fun reset() {
        times.clear()
        reads.clear()
    }

    fun add(nowMs: Long, read: Long) {
        times.add(nowMs)
        reads.add(read)
        while (times.size > 2 && nowMs - times[1] >= windowMs) {
            times.removeAt(0)
            reads.removeAt(0)
        }
    }

    fun bytesPerSec(): Double {
        if (times.size < 2) return 0.0
        val dt = times.last() - times.first()
        if (dt <= 0) return 0.0
        return (reads.last() - reads.first()).coerceAtLeast(0L) * 1000.0 / dt
    }
}

object ReadFormat {
    /** 画面に出す1本を選ぶ: 残りがいちばん大きいもの(同じなら先に始めたもの)。大きさ不明のものは後回し。 */
    fun pick(list: List<ReadTracker.Stream>): ReadTracker.Stream? =
        list.maxWithOrNull(compareBy<ReadTracker.Stream> { if (it.total > 0) (it.total - it.read).coerceAtLeast(0L) else -1L }.thenByDescending { it.startMs })

    /** 「42.3 MB / 120 MB(35%)」。大きさが分からないか、超えたときは「42.3 MB 読み込み済み」。 */
    fun amount(read: Long, total: Long): String =
        if (total > 0 && read <= total) "${FileOps.formatSize(read)} / ${FileOps.formatSize(total)}(${(read * 100 / total).coerceIn(0, 100)}%)"
        else "${FileOps.formatSize(read)} 読み込み済み"

    fun speed(bytesPerSec: Double): String = "${FileOps.formatSize(bytesPerSec.toLong())}/秒"

    /** 「あと約 23 秒」。速さが分からない(ほぼ 0)ときは null。 */
    fun eta(read: Long, total: Long, bytesPerSec: Double): String? {
        if (total <= 0 || read >= total || bytesPerSec < 1024) return null
        val sec = Math.ceil((total - read) / bytesPerSec).toLong()
        return when {
            sec < 60 -> "あと約 ${sec} 秒"
            sec < 3600 -> "あと約 ${sec / 60} 分 ${sec % 60} 秒"
            else -> "あと約 ${sec / 3600} 時間 ${sec % 3600 / 60} 分"
        }
    }
}

private class ReadInfo(val name: String, val read: Long, val total: Long, val bytesPerSec: Double, val others: Int, val waitingMs: Long)

/** 時間がかかる読み込みのときだけ(0.7 秒以上)、ビューアの中央の下に出す。量・速さ・残り時間を見せる。 */
@Composable
internal fun ReadProgressCard(tracker: ReadTracker, modifier: Modifier = Modifier) {
    var info by remember(tracker) { mutableStateOf<ReadInfo?>(null) }
    LaunchedEffect(tracker) {
        val meter = SpeedMeter()
        var cur: ReadTracker.Stream? = null
        while (true) {
            delay(250)
            val now = System.currentTimeMillis()
            val list = tracker.active()
            val s = ReadFormat.pick(list)
            if (s == null) {
                cur = null
                info = null
                continue
            }
            if (now - s.startMs < 700) continue
            if (s !== cur) {
                cur = s
                meter.reset()
            }
            meter.add(now, s.read)
            info = ReadInfo(s.path.substringAfterLast('/'), s.read, s.total, meter.bytesPerSec(), list.size - 1, now - maxOf(s.lastMs, s.startMs))
        }
    }
    val i = info ?: return
    Surface(
        modifier = modifier.offset(y = 96.dp).padding(horizontal = 24.dp).widthIn(max = 420.dp).fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Pv.bar,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            val title = when {
                i.read == 0L -> "NAS に接続して、読み込みを始めています…"
                i.waitingMs > 4000 -> "NAS からの返事を待っています…"
                else -> "NAS から読み込み中" + if (i.others > 0) "(ほか ${i.others} 件)" else ""
            }
            Text(title, color = Pv.barText, fontSize = 14.sp)
            Text(i.name, color = Pv.barSub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            if (i.total > 0 && i.read in 1..i.total) {
                LinearProgressIndicator(progress = { (i.read.toFloat() / i.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(4.dp), color = Pv.accent)
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp), color = Pv.accent)
            }
            Spacer(Modifier.height(6.dp))
            if (i.read > 0) Text(ReadFormat.amount(i.read, i.total), color = Pv.barText, fontSize = 13.sp)
            val speedText = if (i.bytesPerSec >= 1024) ReadFormat.speed(i.bytesPerSec) else null
            val etaText = ReadFormat.eta(i.read, i.total, i.bytesPerSec)
            val tail = listOfNotNull(speedText, etaText).joinToString("・")
            if (tail.isNotEmpty()) Text(tail, color = Pv.barSub, fontSize = 12.sp)
        }
    }
}
