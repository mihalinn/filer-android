package local.filemanager

import android.os.Process
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * サムネイルやアイコンなど、画面の動きより後回しでよい読み込み用のスレッド置き場。
 * 優先度を下げてあるので、フォルダを開いた直後に読み込みが重なっても、画面の描画を邪魔しにくい。
 */
internal fun backgroundPool(threads: Int, name: String): ExecutorService {
    val count = AtomicInteger()
    return Executors.newFixedThreadPool(threads) { r ->
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            r.run()
        }, "$name-${count.incrementAndGet()}").apply { isDaemon = true }
    }
}
