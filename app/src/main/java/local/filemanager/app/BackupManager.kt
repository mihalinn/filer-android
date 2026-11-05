package local.filemanager

import android.content.Context

/**
 * 設定のバックアップを、この端末の SharedPreferences と NAS の接続先から集める・書き戻す。
 * 暗号化は BackupFile、中身の形は BackupCodec(どちらも Android に依存しない)。
 */
object BackupManager {
    private const val PREFS = "fm"

    /**
     * 書き出す中身を集める。includeHistory が false なら、履歴と最近開いた場所は入れない。
     * includePasswords が false なら、NAS のパスワードは空にする(暗号化しないファイルには、パスワードを入れない)。
     */
    fun collect(ctx: Context, includeHistory: Boolean, includePasswords: Boolean): BackupData {
        val all = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
        val out = LinkedHashMap<String, Any>()
        for ((key, type) in BackupCodec.SCHEMA) {
            if (!includeHistory && key in BackupCodec.HISTORY) continue
            val v = all[key] ?: continue
            val ok = when (type) {
                BackupCodec.T.BOOL -> v is Boolean
                BackupCodec.T.INT -> v is Int
                BackupCodec.T.STR -> v is String
            }
            if (ok) out[key] = v
        }
        val remotes = RemoteStore.load(ctx).map { if (includePasswords) it else it.copy(password = "") }
        return BackupData(out, remotes)
    }

    /**
     * 読み込んだ中身を書き戻す。replace が true なら、場所の一覧と NAS の接続先を置き換え、false なら足す。
     * そのほかの設定は、どちらでもバックアップの値にする。書き込みは待ってから戻る(直後にアプリを再起動するため)。
     */
    fun apply(ctx: Context, data: BackupData, replace: Boolean) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ed = sp.edit()
        for ((key, v) in data.prefs) {
            when (v) {
                is Boolean -> ed.putBoolean(key, v)
                is Int -> ed.putInt(key, v)
                is String -> ed.putString(key, if (!replace && key in BackupCodec.PLACES + BackupCodec.HISTORY) BackupCodec.mergeLines(sp.getString(key, "").orEmpty(), v) else v)
            }
        }
        ed.commit()
        val current = RemoteStore.load(ctx)
        // パスワードの入っていないバックアップで、同じ接続先のパスワードを消さないよう、いまのパスワードを残す
        val incoming = data.remotes.map { r -> if (r.password.isEmpty()) r.copy(password = current.firstOrNull { it.id == r.id }?.password.orEmpty()) else r }
        val next = if (replace) incoming else current.filter { c -> incoming.none { it.id == c.id } } + incoming
        RemoteStore.save(ctx, next)
    }

    /** 書き出したファイルを、保存先に移すまで置いておく場所(保存が終わったら消す)。 */
    fun tempDir(ctx: Context): java.io.File = java.io.File(ctx.cacheDir, "backup")

    fun clearTemp(ctx: Context) { tempDir(ctx).deleteRecursively() }

    /** アプリを再起動する(読み込んだ設定を、画面の状態にも反映するため)。 */
    fun restart(ctx: Context) {
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ctx.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }
}
