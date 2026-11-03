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
 */
object CrashLog {
    private const val FILE = "last_crash.txt"

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

    fun read(context: Context): String? = File(context.filesDir, FILE).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }

    private val abnormal = setOf(
        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
    )

    /** OS が記録している、このアプリのプロセスの異常な終了(新しい順、最大 5 件)。Android 11 以降。ユーザーが閉じたものは含めない。 */
    fun abnormalExits(context: Context): List<String> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.JAPAN)
        return runCatching {
            am.getHistoricalProcessExitReasons(context.packageName, 0, 20).filter { it.reason in abnormal }.take(5).map { e ->
                val trace = if (e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || e.reason == ApplicationExitInfo.REASON_ANR) {
                    runCatching { e.traceInputStream?.bufferedReader()?.use { it.readText().take(1500) } }.getOrNull()
                } else null
                "${fmt.format(Date(e.timestamp))}  ${reasonName(e.reason)}" +
                    (e.description?.let { " / $it" } ?: "") +
                    (trace?.let { "\n$it" } ?: "")
            }
        }.getOrDefault(emptyList())
    }

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
