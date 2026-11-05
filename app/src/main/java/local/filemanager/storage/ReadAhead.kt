package local.filemanager

import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 前から順に読むときの先読み。次の chunk を、返事を待たずに depth 個まで先に要求しておく
 * (1 つずつ要求→返事待ちを繰り返すと、通信の往復時間の分だけ毎回待つことになり、回線の速さを使い切れない)。
 *
 * - 最初の 1 回と、飛んだ位置の読みは、先読みせずに 1 回だけ読む(先頭の数バイトを見るだけの使い方で無駄に読まない)。
 * - 続きの位置を読むとき、先読みを始める。
 * - 1 回の読みが chunk より短く返ったときは、その大きさに合わせて先読みしなおす。
 * - 失敗は、読もうとした位置のところで投げる(つなぎ直して読み直すのは、呼ぶ側 = ResumingInput の役目)。
 * Android にも SMB にも依存しない。
 */
internal class ReadAhead(
    private val chunk: Int,
    private val depth: Int,
    /** pos から最大 len バイトを読む。終わりなら 0 か -1。別のスレッドから同時に呼ばれる。 */
    private val readOnce: (pos: Long, buf: ByteArray, off: Int, len: Int) -> Int,
) {
    private class Piece(val pos: Long, val buf: ByteArray, val n: Int)

    private class Job(val pos: Long, val future: Future<Piece>)

    private val pending = ArrayDeque<Job>()
    private var cur: Piece? = null
    private var chunkLen = chunk
    private var scheduledEnd = 0L
    private var closed = false

    @Synchronized
    fun readAt(pos: Long, out: ByteArray, off: Int, len: Int): Int {
        if (closed) return -1
        cur?.let { c ->
            if (pos >= c.pos && pos < c.pos + c.n) return copyFrom(c, pos, out, off, len)
        }
        val piece = fetch(pos)
        if (piece.n <= 0) {
            dropPending()
            return -1
        }
        cur = piece
        return copyFrom(piece, pos, out, off, len)
    }

    private fun copyFrom(c: Piece, pos: Long, out: ByteArray, off: Int, len: Int): Int {
        val start = (pos - c.pos).toInt()
        val n = minOf(len, c.n - start)
        System.arraycopy(c.buf, start, out, off, n)
        return n
    }

    private fun fetch(pos: Long): Piece {
        val previous = cur
        val sequential = previous != null && pos == previous.pos + previous.n
        if (pending.isNotEmpty() && pending.first().pos != pos) dropPending()
        if (pending.isEmpty()) {
            // 続きの位置なら先読みを始める。そうでなければ、この 1 回だけ読む
            if (!sequential || depth <= 1) {
                val p = load(pos, chunkLen)
                if (p.n in 1 until chunkLen) chunkLen = p.n
                return p
            }
            scheduledEnd = pos
        }
        topUp()
        val job = pending.removeFirst()
        val piece = try {
            job.future.get()
        } catch (e: ExecutionException) {
            dropPending()
            throw (e.cause as? Exception) ?: e
        } catch (e: InterruptedException) {
            dropPending()
            Thread.currentThread().interrupt()
            throw java.io.InterruptedIOException(Txt(R.string.read_ahead_001))
        }
        // 短く返った(サーバーの上限や、終わり)。先読みの位置がずれるので、やり直す
        if (piece.n in 1 until chunkLen) {
            dropPending()
            chunkLen = piece.n
        } else {
            topUp()
        }
        return piece
    }

    private fun topUp() {
        while (pending.size < depth) {
            val p = scheduledEnd
            val size = chunkLen
            pending.addLast(Job(p, POOL.submit<Piece> { load(p, size) }))
            scheduledEnd = p + size
        }
    }

    private fun load(pos: Long, size: Int): Piece {
        val b = ByteArray(size)
        val n = readOnce(pos, b, 0, size)
        return Piece(pos, b, if (n < 0) 0 else n)
    }

    private fun dropPending() {
        pending.forEach { it.future.cancel(true) }
        pending.clear()
    }

    /** つなぎ直す前に呼ぶ。先読み中のものを捨てる。 */
    @Synchronized
    fun reset() {
        dropPending()
    }

    @Synchronized
    fun close() {
        closed = true
        dropPending()
        cur = null
    }

    private companion object {
        private val count = AtomicInteger()

        /** 先読みの読み取りを動かすスレッド。使っていないときは自然に消える。 */
        val POOL = ThreadPoolExecutor(
            6, 6, 20, TimeUnit.SECONDS, LinkedBlockingQueue(),
            ThreadFactory { r -> Thread(r, "filer-readahead-${count.incrementAndGet()}").apply { isDaemon = true } },
        ).also { it.allowCoreThreadTimeOut(true) }
    }
}
