package local.filemanager

import android.app.Application
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data class UpToDate(val version: String) : UpdateStatus
    data class Available(val info: ReleaseInfo) : UpdateStatus
    data class Downloading(val info: ReleaseInfo, val done: Long, val total: Long) : UpdateStatus
    data class Ready(val info: ReleaseInfo, val file: File) : UpdateStatus
    data class Failed(val message: String, val info: ReleaseInfo?) : UpdateStatus
}

/**
 * アプリの更新(GitHub のリリース)。起動時に確認し、あれば知らせ、アプリの中でダウンロードしてインストールの画面に渡す。
 * 「後で更新する」を選んだ版は、もう自動では知らせず、設定から更新できる。
 */
class UpdateManager(
    private val app: Application,
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
    private val openFile: (File) -> Unit,
) {
    var status by mutableStateOf<UpdateStatus>(UpdateStatus.Idle)
        private set

    /** 「更新があります」などの窓を出しているか */
    var promptVisible by mutableStateOf(false)
        private set

    var autoCheck by mutableStateOf(prefs.getBoolean(KEY_AUTO, true))
        private set

    var lastChecked by mutableLongStateOf(prefs.getLong(KEY_LAST, 0L))
        private set

    /** ブラウザで開いてほしい URL(Activity 側が受け取る) */
    val urlRequests = MutableSharedFlow<String>(extraBufferCapacity = 2)

    val repoUrl: String get() = Credits.SOURCE_URL

    val currentVersion: String =
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()

    private var job: Job? = null

    fun changeAutoCheck(on: Boolean) {
        autoCheck = on
        prefs.edit().putBoolean(KEY_AUTO, on).apply()
    }

    /** 起動時。自動の確認がオンで、前の確認から 12 時間以上たっているときだけ確認する。 */
    fun checkOnStart() {
        if (!autoCheck || repoUrl.isBlank()) return
        if (System.currentTimeMillis() - lastChecked < AUTO_INTERVAL_MS) return
        check(manual = false)
    }

    /** manual = 設定の「更新を確認」。自動のときは、失敗しても何も出さず、知らせるのは新しいものがあるときだけ。 */
    fun check(manual: Boolean) {
        if (status is UpdateStatus.Checking || status is UpdateStatus.Downloading) return
        if (repoUrl.isBlank()) return
        status = UpdateStatus.Checking
        job = scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { UpdateNet.fetchLatest(repoUrl) } }
            val now = System.currentTimeMillis()
            lastChecked = now
            prefs.edit().putLong(KEY_LAST, now).apply()
            result.onSuccess { info ->
                if (info != null && UpdateLogic.isNewer(currentVersion, info.version)) {
                    status = UpdateStatus.Available(info)
                    if (!manual && prefs.getString(KEY_LATER, "") != info.tag) promptVisible = true
                } else {
                    status = UpdateStatus.UpToDate(currentVersion)
                }
            }.onFailure { e ->
                status = if (manual) UpdateStatus.Failed(e.message ?: "更新を確認できませんでした", null) else UpdateStatus.Idle
            }
        }
    }

    /** 「後で更新する」。この版は、もう自動では知らせない(設定から更新できる)。 */
    fun later() {
        (status as? UpdateStatus.Available)?.let { prefs.edit().putString(KEY_LATER, it.info.tag).apply() }
        promptVisible = false
    }

    fun closePrompt() {
        promptVisible = false
        if (status is UpdateStatus.Failed) status = UpdateStatus.Idle
    }

    fun openGitHub() {
        val info = when (val s = status) {
            is UpdateStatus.Available -> s.info
            is UpdateStatus.Downloading -> s.info
            is UpdateStatus.Ready -> s.info
            is UpdateStatus.Failed -> s.info
            else -> null
        }
        urlRequests.tryEmit(info?.pageUrl ?: "$repoUrl/releases/latest")
        promptVisible = false
    }

    /** 「更新する」。ダウンロードして、確かめて、インストールの画面を開く。 */
    fun download() {
        val info = when (val s = status) {
            is UpdateStatus.Available -> s.info
            is UpdateStatus.Failed -> s.info
            else -> null
        } ?: return
        promptVisible = true
        status = UpdateStatus.Downloading(info, 0, info.apkSize)
        job = scope.launch {
            val dir = File(app.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val dest = File(dir, info.apkName)
            val me = coroutineContext[Job]
            var lastTick = 0L
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    UpdateNet.download(info, dest, { done, total ->
                        val t = System.nanoTime()
                        if (t - lastTick > 150_000_000L) {
                            lastTick = t
                            status = UpdateStatus.Downloading(info, done, total)
                        }
                    }, { me?.isActive != true })
                    verify(dest)
                }
            }
            result.onSuccess {
                status = UpdateStatus.Ready(info, dest)
                openFile(dest)
            }.onFailure { e ->
                dest.delete()
                status = UpdateStatus.Failed(e.message ?: "更新できませんでした", info)
            }
        }
    }

    /** ダウンロード中のキャンセル。 */
    fun cancel() {
        val info = (status as? UpdateStatus.Downloading)?.info
        job?.cancel()
        status = if (info != null) UpdateStatus.Available(info) else UpdateStatus.Idle
        promptVisible = false
    }

    /** ダウンロード済みのファイルで、インストールの画面をもう一度開く。 */
    fun install() {
        (status as? UpdateStatus.Ready)?.let { openFile(it.file) }
    }

    /** ダウンロードしたファイルが、このアプリの新しいバージョンで、署名が同じかを確かめる。 */
    private fun verify(file: File) {
        val pm = app.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: throw IOException("ダウンロードしたファイルを読めませんでした")
        if (archive.packageName != app.packageName) throw IOException("このアプリのファイルではありませんでした。インストールを中止しました")
        val installed = pm.getPackageInfo(app.packageName, flags)
        if (archive.longVersionCode <= installed.longVersionCode) throw IOException("いまより新しいバージョンではありませんでした")
        if (signers(archive) != signers(installed)) throw IOException("署名がいまのアプリと違うため、上書きで更新できません。GitHub から更新する場合は、先にアンインストールが必要です")
    }

    private fun signers(pi: PackageInfo): Set<String> {
        val si = pi.signingInfo ?: return emptySet()
        val certs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        return certs.map { c -> MessageDigest.getInstance("SHA-256").digest(c.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    private companion object {
        const val KEY_AUTO = "updAuto"
        const val KEY_LAST = "updLast"
        const val KEY_LATER = "updLaterTag"
        const val AUTO_INTERVAL_MS = 12L * 60 * 60 * 1000
    }
}