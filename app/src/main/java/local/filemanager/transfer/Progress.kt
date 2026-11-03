package local.filemanager

import java.util.concurrent.atomic.AtomicLong

/**
 * 進行中の操作(コピー・移動・ダウンロードなど)の進み具合。
 * 操作する側(裏のスレッド)が更新し、画面側が一定間隔で読むだけなので、各項目は別スレッドから触っても安全にしてある。
 */
class Progress(val verb: String) {
    /** 全体の大きさ(バイト)。0 のときは不明(名前の付け替えだけで終わる移動など)。 */
    @Volatile
    var totalBytes = 0L

    @Volatile
    var from = ""

    @Volatile
    var to = ""

    @Volatile
    var itemsDone = 0

    @Volatile
    var itemsTotal = 0

    private val done = AtomicLong()
    val startedAt = System.currentTimeMillis()
    val doneBytes: Long get() = done.get()

    fun add(n: Int) {
        done.addAndGet(n.toLong())
    }

    /** 次のファイルに取りかかったことを知らせる(画面の「コピー元/コピー先」に出る)。 */
    fun begin(from: String, to: String) {
        this.from = from
        this.to = to
    }
}

/** 画面に見せる、ある時点の進み具合。 */
data class ProgressView(
    val verb: String,
    val from: String,
    val to: String,
    val doneBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val itemsDone: Int,
    val itemsTotal: Int,
    val elapsedMs: Long,
) {
    /** 0〜1。全体の大きさが分からないときは null。 */
    val fraction: Float? get() = if (totalBytes > 0) (doneBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat() else null
}

fun formatElapsed(ms: Long): String {
    val s = ms / 1000
    return String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
}
