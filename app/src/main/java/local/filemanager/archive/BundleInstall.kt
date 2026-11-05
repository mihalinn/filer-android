package local.filemanager

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast

/** インストールの結果(Android からの知らせの番号)を、読める文にする。 */
object InstallStatusText {
    fun of(status: Int, message: String?): String {
        val why = when (status) {
            PackageInstaller.STATUS_SUCCESS -> return Txt(R.string.bundle_install_001)
            PackageInstaller.STATUS_FAILURE_ABORTED -> Txt(R.string.bundle_install_002)
            PackageInstaller.STATUS_FAILURE_BLOCKED -> Txt(R.string.bundle_install_003)
            PackageInstaller.STATUS_FAILURE_CONFLICT -> Txt(R.string.bundle_install_004)
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> Txt(R.string.bundle_install_005)
            PackageInstaller.STATUS_FAILURE_INVALID -> Txt(R.string.bundle_install_006)
            PackageInstaller.STATUS_FAILURE_STORAGE -> Txt(R.string.bundle_install_007)
            else -> Txt(R.string.bundle_install_008)
        }
        return Txt(R.string.bundle_install_009, why) + if (!message.isNullOrBlank()) "\n($message)" else ""
    }
}

/** 本体と部品(.xapk / .apkm / .apks)を、Android の標準の仕組み(PackageInstaller)で、まとめて入れる。 */
object BundleInstaller {
    const val ACTION = "local.filemanager.INSTALL_RESULT"

    /**
     * 入れる部品をインストールの受付(セッション)に書き込み、確認画面を出すところまで進める(最終の「インストール」は端末の確認画面で押す)。
     * 結果の文を返す。失敗したときは受付を取り消して例外にする。
     */
    fun install(ctx: Context, fs: Fs, item: FileItem, check: () -> Unit, progress: Progress): String {
        val pm = ctx.packageManager
        if (!pm.canRequestPackageInstalls()) {
            // 「提供元不明のアプリ」の許可の画面を開く
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return Txt(R.string.bundle_install_010)
        }
        val src = openSource(fs, item.path) ?: throw OpException(Txt(R.string.bundle_install_011))
        try {
            val reader = ZipReader(src)
            val plan = ApkBundle.plan(reader.entries, Build.SUPPORTED_ABIS.toList())
            if (plan.base == null) throw OpException(Txt(R.string.bundle_install_012))
            if (plan.abiMismatch) throw OpException(Txt(R.string.bundle_install_013))
            progress.totalBytes = plan.totalBytes
            progress.itemsTotal = plan.chosen.size

            val installer = pm.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setSize(plan.totalBytes)
            val id = installer.createSession(params)
            val session = installer.openSession(id)
            try {
                for ((i, s) in plan.chosen.withIndex()) {
                    check()
                    progress.begin(item.name, s.fileName)
                    // 同じ名前が重ならないよう、番号を付けて書く
                    session.openWrite("%02d_%s".format(i, FileOps.safeFileName(s.fileName)), 0, s.entry.size).use { out ->
                        reader.open(s.entry).use { input -> Transfer.pump(input, out, check, progress) }
                        session.fsync(out)
                    }
                    progress.itemsDone = i + 1
                }
                // 受け取る窓口を名指しする(アクション名と自分のアプリ名だけでは、受け取る側の設定が無いと届かない)
                val intent = Intent(ctx, InstallResultReceiver::class.java).setAction(ACTION)
                val pi = PendingIntent.getBroadcast(ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(pi.intentSender)
            } catch (e: Throwable) {
                runCatching { session.abandon() }
                throw e
            } finally {
                runCatching { session.close() }
            }
            return Txt.n(R.plurals.bundle_prepared, plan.chosen.size)
        } finally {
            src.close()
        }
    }
}

/** インストールの途中経過・結果を受け取る。確認が必要なときは確認画面を開き、終わったら結果を知らせる。 */
class InstallResultReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirm == null) {
                Toast.makeText(context, Txt(R.string.bundle_install_014), Toast.LENGTH_LONG).show()
                return
            }
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(confirm)
            } catch (e: Exception) {
                Toast.makeText(context, Txt(R.string.bundle_install_015, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
            }
            return
        }
        Toast.makeText(context, InstallStatusText.of(status, message), Toast.LENGTH_LONG).show()
    }
}
