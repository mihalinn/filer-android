package local.filemanager

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 異常終了の記録。スマホを PC につながなくても、次に起動したときに原因を画面で見られるようにする。
 *  - Java/Kotlin の未捕捉例外: スタックトレースをファイルに保存してから、通常の終了処理に渡す
 *  - OS が記録する「プロセスが終了した理由」(クラッシュ・応答なし・メモリ不足など): ネイティブのクラッシュもこちらで分かる
 * お知らせは、同じ記録について 1 度だけ出す(seen)。「消去」すると、それより前の記録は OS の分も隠す(cleared)。
 */
object CrashLog {
    private const val FILE = "last_crash.txt"
    private const val PREFS = "crashlog"

    /** 1 件分の記録。serious は、お知らせを出す種類(クラッシュ・応答なしなど。メモリ不足で OS に終了されただけのものは含めない)。 */
    class Entry(val time: Long, val kind: String, val body: String, val serious: Boolean)

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN).format(Date())
                File(app.filesDir, FILE).writeText("$stamp  スレッド: ${thread.name}\n" + Log.getStackTraceString(error))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(context: Context): String? = File(context.filesDir, FILE).takeIf { it.exists() }?.readText()

    /** 記録を消す。アプリの例外のファイルを消し、OS が持っている分は、いまより前のものを隠す。 */
    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
        val now = System.currentTimeMillis()
        prefs(context).edit().putLong("cleared", now).putLong("seen", now).apply()
    }

    /** お知らせを出す記録がまだ見られていないか。 */
    fun hasUnseen(context: Context): Boolean {
        val seen = prefs(context).getLong("seen", 0L)
        return entries(context).any { it.serious && it.time > seen }
    }

    /** いまある記録を「見た」ことにする(お知らせを出し直さない)。 */
    fun markSeen(context: Context) {
        prefs(context).edit().putLong("seen", System.currentTimeMillis()).apply()
    }

    /** 新しい順の記録。アプリの例外(詳しいもの)と、OS の記録(最大 5 件)。同じクラッシュは 1 件にまとめる。 */
    fun entries(context: Context): List<Entry> {
        val list = ArrayList<Entry>()
        val cleared = prefs(context).getLong("cleared", 0L)
        val f = File(context.filesDir, FILE)
        val fileTime = if (f.exists()) f.lastModified() else 0L
        if (fileTime > 0L) {
            runCatching { f.readText() }.getOrNull()?.let { list.add(Entry(fileTime, "アプリの例外", it.trim(), true)) }
        }
        for (e in exits(context)) {
            if (e.time <= cleared) continue
            // 例外で落ちたときは、OS にも「クラッシュ」と記録される。上のファイルのほうが詳しいので、近い時刻の分は省く
            if (e.kind.startsWith("クラッシュ") && fileTime > 0L && Math.abs(e.time - fileTime) < 60_000L) continue
            list.add(e)
        }
        return list.sortedByDescending { it.time }
    }

    private val abnormal = setOf(
        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
    )
    private val notOnlyMemory = setOf(
        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
    )

    /** OS が記録している、このアプリのプロセスの異常な終了(新しい順、最大 5 件)。Android 11 以降。ユーザーが閉じたものは含めない。 */
    private fun exits(context: Context): List<Entry> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return runCatching {
            am.getHistoricalProcessExitReasons(context.packageName, 0, 20).filter { it.reason in abnormal }.take(5).map { e ->
                val trace = if (e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || e.reason == ApplicationExitInfo.REASON_ANR) {
                    runCatching { e.traceInputStream?.bufferedReader()?.use { it.readText().take(1500) } }.getOrNull()
                } else null
                Entry(
                    e.timestamp, reasonName(e.reason),
                    (e.description?.takeIf { it.isNotBlank() } ?: "") + (trace?.let { "\n$it" } ?: ""),
                    e.reason in notOnlyMemory,
                )
            }
        }.getOrDefault(emptyList())
    }

    /** 日時つきの 1 行目と詳しい内容(報告に添える文字用)。OS の記録だけ。消去より前のものは含めない。 */
    fun abnormalExits(context: Context): List<String> {
        val cleared = prefs(context).getLong("cleared", 0L)
        return exits(context).filter { it.time > cleared }.map { "${stamp(it.time)}  ${it.kind}" + (if (it.body.isNotBlank()) " / ${it.body.trim()}" else "") }
    }

    fun stamp(time: Long): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.JAPAN).format(Date(time))

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_CRASH -> "クラッシュ(Java/Kotlin の例外)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "ネイティブのクラッシュ"
        ApplicationExitInfo.REASON_ANR -> "応答なし(ANR)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "メモリ不足で OS が終了"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "リソース使用過多"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "初期化の失敗"
        else -> "理由コード $r"
    }
}
