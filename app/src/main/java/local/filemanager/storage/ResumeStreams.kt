package local.filemanager

import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream

/**
 * 通信が途中で切れても、切れた所から続きを読む入力(NAS をモバイル回線や VPN で読むときの対策)。
 * 1 回に読むのは chunk バイトまで。読み取りに失敗したら、つなぎ直して同じ位置からやり直す(最大 retries 回)。
 * 一時的ではない失敗(権限など)は、すぐ投げる。Android にも SMB にも依存しない。
 */
internal class ResumingInput(
    private val chunk: Int,
    private val retries: Int,
    private val wait: (attempt: Int) -> Unit,
    private val isTransient: (Exception) -> Boolean,
    private val fail: (Exception) -> Exception,
    /** pos から最大 len バイトを読む。終わりなら -1。 */
    private val readAt: (pos: Long, buf: ByteArray, off: Int, len: Int) -> Int,
    private val reconnect: () -> Unit,
    private val onClose: () -> Unit,
) : InputStream() {
    var position = 0L
        private set
    private var closed = false
    private val one = ByteArray(1)

    override fun read(): Int {
        val n = read(one, 0, 1)
        return if (n < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed) throw IOException("閉じています")
        if (len == 0) return 0
        val want = minOf(len, chunk)
        var attempt = 0
        while (true) {
            try {
                val n = readAt(position, b, off, want)
                if (n <= 0) return -1
                position += n
                return n
            } catch (e: Exception) {
                if (!isTransient(e)) throw fail(e)
                if (attempt >= retries) throw fail(e)
                attempt++
                reconnect()
                wait(attempt)
            }
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            onClose()
        }
    }
}

/** ResumingInput の書き込み版。失敗したら、書き終えた位置からやり直す(同じ位置への書き直しは結果が変わらない)。 */
internal class ResumingOutput(
    private val chunk: Int,
    private val retries: Int,
    private val wait: (attempt: Int) -> Unit,
    private val isTransient: (Exception) -> Boolean,
    private val fail: (Exception) -> Exception,
    private val writeAt: (pos: Long, buf: ByteArray, off: Int, len: Int) -> Unit,
    private val reconnect: () -> Unit,
    private val onClose: () -> Unit,
) : OutputStream() {
    var position = 0L
        private set
    private var closed = false
    private val one = ByteArray(1)

    override fun write(b: Int) {
        one[0] = b.toByte()
        write(one, 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (closed) throw IOException("閉じています")
        var done = 0
        while (done < len) {
            val n = minOf(chunk, len - done)
            var attempt = 0
            while (true) {
                try {
                    writeAt(position, b, off + done, n)
                    position += n
                    done += n
                    break
                } catch (e: Exception) {
                    if (!isTransient(e)) throw fail(e)
                    if (attempt >= retries) throw fail(e)
                    attempt++
                    reconnect()
                    wait(attempt)
                }
            }
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            onClose()
        }
    }

    companion object {
        /** 待ち時間: 1 回目 1.5 秒、2 回目 3 秒… 最大 7.5 秒。 */
        fun sleepFor(attempt: Int) {
            try {
                Thread.sleep(minOf(attempt, 5) * 1500L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("中断されました")
            }
        }
    }
}
