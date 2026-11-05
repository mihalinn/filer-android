package local.filemanager

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** 取り込みや更新を、利用者か通知の「キャンセル」で止めたときの例外。 */
class FetchCancelled : IOException(Txt(R.string.bg_tasks_001))

/**
 * コピー・移動([Ops])以外の、時間のかかるダウンロード 1 件分(アプリの更新・プレビューのための取り込み)。
 * 進み具合は画面から読める。中断は cancel() で、実行している側が check() / pump() のところで気づく。
 * title は通知の見出し(進み具合の % はあとに付く)。doneText / failText は、アプリが見えていない間に終わったときの通知。
 */
class BgTask(
    val title: String,
    val doneText: String? = null,
    val failText: String? = null,
    /** 終わった通知をタップしたとき、インストールの画面を開くか */
    val installUpdate: Boolean = false,
    /** ビューアの読み込み中の表示(LoadingPanel)に見せるもの */
    val forViewer: Boolean = false,
) {
    val id: Int = BgTasks.nextId()
    var done by mutableLongStateOf(0L)
    var total by mutableLongStateOf(0L)

    /** 作った時刻(画面を開いた時刻。取り込みを始める前の接続待ちも含めて数える)。 */
    val createdAt = System.currentTimeMillis()

    @Volatile
    var startedAt = createdAt
        internal set

    /** 取り込みの実行中か(BgTasks.run の間) */
    @Volatile
    var active = false
        internal set

    @Volatile
    var cancelled = false
        private set

    /** 通知を出して画面オフでも続けるか(小さいものは出さない) */
    @Volatile
    internal var tracked = false

    fun cancel() {
        cancelled = true
    }

    fun check() {
        if (cancelled) throw FetchCancelled()
    }

    /** 読んだ分を書き出しつつ、進み具合を更新する。中断されたら FetchCancelled。 */
    fun pump(i: InputStream, o: OutputStream) {
        val buf = ByteArray(256 * 1024)
        while (true) {
            check()
            val n = i.read(buf)
            if (n < 0) break
            o.write(buf, 0, n)
            done += n
        }
    }

    /** fs の path を file に書き出す。失敗や中断のときは、書きかけの file を残さない。 */
    fun download(fs: Fs, path: String, file: File) {
        try {
            fs.open(path).use { i -> file.outputStream().use { o -> pump(i, o) } }
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
    }
}

/** 時間のかかるダウンロードの置き場。動いている間は [TransferService] が通知を出し、画面オフでも止まらないよう守る。 */
object BgTasks {
    /** これより小さいものは数秒で終わるので、通知を出さない */
    const val SERVICE_MIN_BYTES = 4L * 1024 * 1024

    private val ids = AtomicInteger()
    private val running = CopyOnWriteArrayList<BgTask>()

    @Volatile
    private var app: Context? = null

    @Volatile
    private var warned = false

    internal fun nextId(): Int = ids.incrementAndGet()

    /** アプリの文脈を渡しておく(サービスの起動と通知に使う)。 */
    fun attach(ctx: Context) {
        app = ctx.applicationContext
    }

    fun tracked(): List<BgTask> = running.filter { it.tracked }

    fun cancel(id: Int) {
        running.firstOrNull { it.id == id }?.cancel()
    }

    /**
     * block を task として実行する。size は全体の大きさ(分からなければ 0)。
     * 大きいときは、通知とサービスで画面オフでも続ける。終わったときアプリが見えていなければ、通知で知らせる。
     */
    fun <T> run(task: BgTask, size: Long, block: () -> T): T {
        task.total = size
        task.done = 0L
        task.startedAt = System.currentTimeMillis()
        task.tracked = shouldTrack(size)
        running.add(task)
        task.active = true
        val ctx = app
        if (task.tracked && ctx != null) {
            warnIfSilent(ctx)
            Ops.startService(ctx)
        }
        try {
            val r = block()
            finished(task, null)
            return r
        } catch (e: Throwable) {
            finished(task, e)
            throw e
        } finally {
            task.active = false
            running.remove(task)
        }
    }

    /** 取り込み中のものがあるか(あれば、画面は取り込み用の表示を出すので、NAS の読み込みカードは重ねない)。 */
    fun anyActive(): Boolean = running.any { it.forViewer }

    internal fun shouldTrack(size: Long) = size <= 0L || size >= SERVICE_MIN_BYTES

    private fun finished(task: BgTask, error: Throwable?) {
        val ctx = app ?: return
        if (!task.tracked || Ops.appVisible || task.cancelled || error is FetchCancelled) return
        val text = if (error == null) task.doneText else task.failText?.let { f -> error.message?.takeIf { it.isNotBlank() }?.let { Txt(R.string.bg_tasks_002, f, it) } ?: f }
        if (text != null) TransferService.postResult(ctx, TransferService.ID_TASK_RESULT + task.id % 500, text, task.installUpdate && error == null)
    }

    /** 通知が出せない設定だと進み具合も完了も分からないので、一度だけ画面でお知らせする。 */
    private fun warnIfSilent(ctx: Context) {
        if (warned) return
        if (NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
        warned = true
        Ops.messages.tryEmit(Txt(R.string.bg_tasks_003))
    }
}

/** 通知に出す文(見出しと、大きさ・速さ・経過)。 */
fun taskNotice(title: String, done: Long, total: Long, elapsedMs: Long): NoticeContent {
    val pct = if (total > 0) (done.toDouble() / total * 100).toInt().coerceIn(0, 100) else null
    val speed = if (elapsedMs >= 1000 && done > 0) done * 1000 / elapsedMs else 0L
    val size = if (total > 0) "${FileOps.formatSize(done)} / ${FileOps.formatSize(total)}" else FileOps.formatSize(done)
    val text = size + if (speed > 0) Txt(R.string.bg_tasks_004, FileOps.formatSize(speed)) else ""
    return NoticeContent(title + (pct?.let { " $it%" } ?: ""), text, pct, text + Txt(R.string.bg_tasks_005, formatElapsed(elapsedMs)))
}

/** 画面が閉じられたら中断する BgTask(プレビューの取り込み用)。 */
@Composable
internal fun rememberBgTask(key: Any, title: String, doneText: String? = null, failText: String? = null): BgTask {
    val t = remember(key) { BgTask(title, doneText, failText, forViewer = true) }
    DisposableEffect(t) { onDispose { t.cancel() } }
    return t
}
