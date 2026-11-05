package local.filemanager

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * 実行中の操作(コピー・移動・圧縮など)の状態。画面(ViewModel)ではなくアプリ全体で1つだけ持つ。
 * 戻るボタンでアプリを閉じても、操作は止まらずここで続き、フォアグラウンドサービス([TransferService])が
 * 通知で進み具合を見せつつ、OS に止められないよう守る。
 */
object Ops {
    /** 操作が走る場所。画面が閉じても生き続ける。 */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** いま実行中の操作の名前(「コピー中…」など)。無ければ null。 */
    var busy by mutableStateOf<String?>(null)

    /** コピー・移動などの進み具合(進捗を出さない操作では null)。 */
    var progressView by mutableStateOf<ProgressView?>(null)

    /** 「非表示にする」を押したあと、進捗の窓を隠している間 true。 */
    var progressHidden by mutableStateOf(false)

    var job: Job? = null

    /** 画面下のメッセージ(スナックバー)に出す文。 */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** アプリの画面が見えている間 true。見えていないときだけ、終わったことを通知で知らせる。 */
    @Volatile
    var appVisible = false

    /** 直前の操作の結果の文(完了の通知に使う)。 */
    @Volatile
    var lastResult: String? = null

    private val idleHooks = ArrayList<() -> Unit>()

    fun cancel() {
        job?.cancel()
    }

    /** 操作が終わってから実行する後始末。いま操作が無ければすぐ実行する。 */
    fun whenIdle(action: () -> Unit) {
        if (busy == null) action() else idleHooks.add(action)
    }

    fun runIdleHooks() {
        val hooks = idleHooks.toList()
        idleHooks.clear()
        hooks.forEach { runCatching(it) }
    }

    /** 通知付きのサービスを起動する。起動できなくても操作自体は続く(その場合は従来どおり画面が閉じるまで)。 */
    fun startService(ctx: Context) {
        runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, TransferService::class.java)) }
    }

    fun snapshot(p: Progress, speed: Long) = ProgressView(
        kind = p.kind, from = p.from, to = p.to, doneBytes = p.doneBytes, totalBytes = p.totalBytes,
        bytesPerSecond = speed, itemsDone = p.itemsDone, itemsTotal = p.itemsTotal,
        elapsedMs = System.currentTimeMillis() - p.startedAt,
    )
}

/** 通知に出す文。画面に依存しないので、そのままテストできる。 */
data class NoticeContent(
    val title: String,
    val text: String,
    /** 0〜100。全体の大きさが分からないときは null(動き続けるバーにする)。 */
    val percent: Int?,
    val detail: String,
)

fun noticeContent(busy: String?, p: ProgressView?): NoticeContent {
    if (p == null) return NoticeContent(busy ?: Txt(R.string.ops_001), "", null, "")
    val pct = p.fraction?.let { (it * 100).toInt() }
    val title = Txt(p.kind.title) + (pct?.let { " $it%" } ?: "")
    val size = if (p.totalBytes > 0) "${FileOps.formatSize(p.doneBytes)} / ${FileOps.formatSize(p.totalBytes)}" else FileOps.formatSize(p.doneBytes)
    val speed = if (p.bytesPerSecond > 0) Txt(R.string.ops_002, FileOps.formatSize(p.bytesPerSecond)) else ""
    val text = size + speed
    val lines = ArrayList<String>()
    lines.add(text)
    if (p.itemsTotal > 0) lines.add(Txt(R.string.progress_items, p.itemsDone, p.itemsTotal))
    lines.add(Txt(R.string.progress_elapsed, formatElapsed(p.elapsedMs)))
    return NoticeContent(title, text, pct, lines.joinToString("\n"))
}
