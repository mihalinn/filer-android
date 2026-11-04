package local.filemanager

import android.content.Context
import android.content.SharedPreferences
import android.os.Debug
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 1 回分の測定値。画面に出す 1 行にするのは [PerfFormat]。 */
class PerfSample(
    val visible: Boolean,
    val javaUsedMb: Long,
    val javaMaxMb: Long,
    val nativeMb: Long,
    val threads: Int,
    val fds: Int,
    val gcCount: Long,
    val gcBlockMs: Long,
    val imageMb: Long,
    val frames: Int,
    val slowFrames: Int,
    val worstFrameMs: Long,
)

/** 記録の 1 行の作り方と、ファイルが大きくなりすぎたときの切り詰め。Android に依存しないので PC のテストで確かめる。 */
object PerfFormat {
    fun line(stamp: String, s: PerfSample): String {
        val sb = StringBuilder()
        sb.append(stamp).append(if (s.visible) "  前面" else "  裏")
        sb.append("  Java ${s.javaUsedMb}/${s.javaMaxMb}MB")
        sb.append("  ネイティブ ${s.nativeMb}MB")
        sb.append("  スレッド ${s.threads}")
        sb.append("  FD ${s.fds}")
        sb.append("  GC +${s.gcCount}回 +${s.gcBlockMs}ms")
        sb.append("  画像 ${s.imageMb}MB")
        if (s.frames > 0) sb.append("  遅れ ${s.slowFrames}/${s.frames}枚 最大 ${s.worstFrameMs}ms")
        if (s.javaMaxMb > 0 && s.javaUsedMb * 100 / s.javaMaxMb >= 80) sb.append("  【メモリ多】")
        return sb.toString()
    }

    /** 新しい順の行のうち、いちばん新しい「MM-dd HH:mm:ss」の時刻。無ければ null。 */
    fun lastStamp(newestFirst: List<String>): String? =
        newestFirst.firstNotNullOfOrNull { Regex("^\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d").find(it)?.value }

    /** 行の数が max を超えたら、新しい側の半分だけ残す(古い行から捨てる)。 */
    fun trim(lines: List<String>, max: Int): List<String> = if (lines.size <= max) lines else lines.takeLast(max / 2)

    /** /proc/self/status の「Threads:」の数。読めなければ -1。 */
    fun threadsFromStatus(status: String): Int =
        status.lineSequence().firstOrNull { it.startsWith("Threads:") }?.substringAfter(':')?.trim()?.toIntOrNull() ?: -1
}

/**
 * アプリが重くなったときの原因を調べるための、動作の記録。設定の「開発者向け」でオンにしたときだけ取る(初期値はオフ)。
 * 10 秒ごと(画面が見えていないときは 60 秒ごと)に、メモリ・スレッド・開いているファイル・GC・画面の動きの遅れを、
 * 端末の中のファイルに書き足す。アプリを再起動しても残るので、「重くなったあと再起動した」ときの記録も見られる。端末の外には出さない。
 */
object PerfLog {
    private const val FILE = "perf_log.txt"
    private const val KEY = "perfLog"
    private const val MAX_LINES = 2400
    private const val MB = 1024L * 1024L

    private val lock = Any()
    private val day = SimpleDateFormat("MM-dd HH:mm:ss", Locale.JAPAN)

    @Volatile
    private var file: File? = null

    private var prefs: SharedPreferences? = null
    private var version = ""

    /** 記録を取るか(開発者向けの設定)。オフの間は、計測も書き込みも一切しない。 */
    @Volatile
    var enabled = false
        private set

    /** 記録のオンオフが変わったとき(画面の遅れの計測を付けたり外したりするため)に呼ばれる。 */
    @Volatile
    var onChange: (() -> Unit)? = null

    @Volatile
    private var sampler: Thread? = null

    private val frames = AtomicInteger()
    private val slow = AtomicInteger()
    private val worst = AtomicLong()
    private var lastGcCount = 0L
    private var lastGcBlockMs = 0L

    /** アプリの起動時に 1 度だけ呼ぶ。保存してあるオンオフを読み、オンなら記録を始める。 */
    fun start(ctx: Context) {
        if (file != null) return
        file = File(ctx.filesDir, FILE)
        prefs = ctx.getSharedPreferences("fm", Context.MODE_PRIVATE)
        version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()
        enabled = prefs?.getBoolean(KEY, false) == true
        if (enabled) {
            append("── 起動 バージョン $version ──")
            startSampler()
        }
    }

    /** オンオフを切り替えて保存する。 */
    fun setEnabled(on: Boolean) {
        if (file == null || enabled == on) return
        enabled = on
        prefs?.edit()?.putBoolean(KEY, on)?.apply()
        if (on) {
            append("── 記録を始めました バージョン $version ──")
            startSampler()
        } else {
            append("── 記録を止めました ──")
            frames.set(0); slow.set(0); worst.set(0)
        }
        onChange?.invoke()
    }

    @Synchronized
    private fun startSampler() {
        if (sampler?.isAlive == true) return
        // 測り始めの GC の数は「ここまでの合計」なので、差を取る基準を今に合わせる
        lastGcCount = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: 0L
        lastGcBlockMs = Debug.getRuntimeStat("art.gc.blocking-gc-time")?.toLongOrNull() ?: 0L
        sampler = Thread({
            while (enabled) {
                runCatching { Thread.sleep(if (Ops.appVisible) 10_000L else 60_000L) }
                if (enabled) runCatching { sample() }
            }
        }, "filer-perflog").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    /** 画面の 1 コマを描くのにかかった時間(ナノ秒)。画面が見えている間に呼ばれる。 */
    fun frame(totalNanos: Long) {
        if (!enabled) return
        frames.incrementAndGet()
        val ms = totalNanos / 1_000_000L
        if (ms > 34) slow.incrementAndGet()
        while (true) {
            val w = worst.get()
            if (ms <= w || worst.compareAndSet(w, ms)) break
        }
    }

    /** 記録に残したい出来事(フォルダを開いた・メモリの警告など)を 1 行足す。 */
    fun event(text: String) {
        if (!enabled) return
        append("${stamp()}  ▶ $text")
    }

    private fun stamp(): String = synchronized(lock) { day.format(Date()) }

    private fun sample() {
        val rt = Runtime.getRuntime()
        val gc = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: 0L
        val gcBlock = Debug.getRuntimeStat("art.gc.blocking-gc-time")?.toLongOrNull() ?: 0L
        val s = PerfSample(
            visible = Ops.appVisible,
            javaUsedMb = (rt.totalMemory() - rt.freeMemory()) / MB,
            javaMaxMb = rt.maxMemory() / MB,
            nativeMb = Debug.getNativeHeapAllocatedSize() / MB,
            threads = runCatching { PerfFormat.threadsFromStatus(File("/proc/self/status").readText()) }.getOrDefault(-1),
            fds = File("/proc/self/fd").list()?.size ?: -1,
            gcCount = (gc - lastGcCount).coerceAtLeast(0L),
            gcBlockMs = (gcBlock - lastGcBlockMs).coerceAtLeast(0L),
            imageMb = (imageCacheBytes() + pdfBitmapBytes()) / MB,
            frames = frames.getAndSet(0),
            slowFrames = slow.getAndSet(0),
            worstFrameMs = worst.getAndSet(0),
        )
        lastGcCount = gc
        lastGcBlockMs = gcBlock
        append(PerfFormat.line(stamp(), s))
    }

    private fun append(line: String) {
        val f = file ?: return
        synchronized(lock) {
            runCatching {
                f.appendText(line + "\n")
                // 行の数を数えるのは、ファイルがある程度大きくなってからだけ
                if (f.length() > 400_000L) {
                    val lines = f.readLines()
                    if (lines.size > MAX_LINES) f.writeText(PerfFormat.trim(lines, MAX_LINES).joinToString("\n") + "\n")
                }
            }
        }
    }

    /** 新しい順の行(最大 limit 行)。 */
    fun lines(limit: Int = 400): List<String> {
        val f = file ?: return emptyList()
        return synchronized(lock) { runCatching { f.readLines() }.getOrDefault(emptyList()) }.takeLast(limit).reversed()
    }

    fun clear() {
        val f = file ?: return
        synchronized(lock) { runCatching { f.delete() } }
        append("── 記録を消去しました ──")
    }
}
