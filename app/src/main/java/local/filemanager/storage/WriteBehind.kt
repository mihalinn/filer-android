package local.filemanager

import java.io.IOException
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ResumingOutput の並列版。chunk 分たまるごとに、返事を待たずに次々と送り出す(同時に depth 個まで)。
 * 1 つずつ送って返事を待つと、通信の往復時間の分だけ毎回止まり、回線の速さを使い切れない。
 *
 * - 呼ぶのは 1 つのスレッドだけ。書き込み自体は別のスレッドで動き、writeAt は同時に呼ばれる。
 * - 失敗は、次に待つとき(または close)に分かる。そのとき、動いている書き込みが全部終わるのを待ってから reconnect し、
 *   まだ確認できていない分を同じ位置に書き直す(同じ位置への書き直しは結果が変わらない)。
 * - 書き込みが全部終わるまで close は返らない。
 * Android にも SMB にも依存しない。
 */
internal class WriteBehind(
    private val chunk: Int,
    private val depth: Int,
    private val retries: Int,
    private val wait: (attempt: Int) -> Unit,
    private val isTransient: (Exception) -> Boolean,
    private val fail: (Exception) -> Exception,
    /** pos から len バイトを書く。別のスレッドから同時に呼ばれる。 */
    private val writeAt: (pos: Long, buf: ByteArray, off: Int, len: Int) -> Unit,
    private val reconnect: () -> Unit,
    private val onClose: () -> Unit,
) : OutputStream() {
    private class Piece(val pos: Long, val buf: ByteArray, val n: Int) {
        lateinit var future: Future<*>
    }

    private val inflight = ArrayDeque<Piece>()
    private var stage = ByteArray(chunk)
    private var staged = 0
    private var attempt = 0
    private var closed = false
    private var failed = false

    /** 受け取った(送り出しを始めたものを含む)バイト数。 */
    var position = 0L
        private set

    /** 送り出し済みの先頭位置 = 次の piece の位置。 */
    private var sentEnd = 0L

    private val one = ByteArray(1)

    override fun write(b: Int) {
        one[0] = b.toByte()
        write(one, 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (closed) throw IOException(Txt(R.string.write_behind_001))
        if (failed) throw IOException(Txt(R.string.write_behind_002))
        var done = 0
        while (done < len) {
            val n = minOf(chunk - staged, len - done)
            System.arraycopy(b, off + done, stage, staged, n)
            staged += n
            done += n
            position += n
            if (staged == chunk) submitStage()
        }
    }

    /** たまった分を送り出し、全部の書き込みが終わるのを待つ。 */
    override fun flush() {
        if (closed || failed) return
        guard {
            if (staged > 0) submitStage()
            while (inflight.isNotEmpty()) awaitOldest()
        }
    }

    override fun close() {
        if (closed) return
        try {
            if (!failed) flush()
        } finally {
            closed = true
            // 失敗で抜けたときも、動いている書き込みが終わってから閉じる
            drain()
            onClose()
        }
    }

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            failed = true
            cancelAll()
            throw e
        }
    }

    private fun submitStage() {
        val piece = Piece(sentEnd, stage, staged)
        stage = ByteArray(chunk)
        staged = 0
        sentEnd += piece.n
        // 先に空きを作ってから送り出す
        guard {
            while (inflight.size >= depth) awaitOldest()
            piece.future = POOL.submit { writeAt(piece.pos, piece.buf, 0, piece.n) }
            inflight.addLast(piece)
        }
    }

    private fun awaitOldest() {
        while (true) {
            val p = inflight.first()
            try {
                p.future.get()
                inflight.removeFirst()
                attempt = 0
                return
            } catch (e: ExecutionException) {
                recover((e.cause as? Exception) ?: e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException(Txt(R.string.write_behind_003))
            }
        }
    }

    /** 失敗したとき。動いている書き込みを終わらせ、つなぎ直して、確認できていない分を書き直す。 */
    private fun recover(e: Exception) {
        drain()
        if (!isTransient(e) || attempt >= retries) throw fail(e)
        attempt++
        reconnect()
        wait(attempt)
        for (p in inflight) p.future = POOL.submit { writeAt(p.pos, p.buf, 0, p.n) }
    }

    /** 動いている書き込みが全部終わる(失敗でもよい)まで待つ。 */
    private fun drain() {
        for (p in inflight) {
            try {
                p.future.get()
            } catch (_: ExecutionException) {
            } catch (_: java.util.concurrent.CancellationException) {
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun cancelAll() {
        inflight.forEach { it.future.cancel(true) }
        drain()
        inflight.clear()
    }

    companion object {
        private val count = AtomicInteger()

        /** 書き込みを動かすスレッド。使っていないときは自然に消える。 */
        private val POOL = ThreadPoolExecutor(
            6, 6, 20, TimeUnit.SECONDS, LinkedBlockingQueue(),
            ThreadFactory { r -> Thread(r, "filer-writebehind-${count.incrementAndGet()}").apply { isDaemon = true } },
        ).also { it.allowCoreThreadTimeOut(true) }
    }
}
