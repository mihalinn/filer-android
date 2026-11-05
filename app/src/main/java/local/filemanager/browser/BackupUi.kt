package local.filemanager

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 設定のバックアップの操作(書き出す・読み込む)と、その窓。状態は FmViewModel が持つ(画面を行き来しても消えないように)。
 * 暗号化は BackupFile、中身は BackupCodec、端末への出し入れは BackupManager。
 * 保存先の選び方・ファイルの選び方は、Android 標準の画面ではなく、このアプリのファイル画面を使う:
 *   書き出し → 暗号化したファイルを作り、「他のアプリから受け取った共有の保存」と同じ流れで、保存先のフォルダを開いて「ここに保存」を押す
 *   読み込み → ファイル画面で .filerbak を押す(設定の「設定を読み込む」は、その案内を出す)
 */
internal class BackupUi(
    private val app: Context,
    private val scope: CoroutineScope,
    /** お知らせを出す */
    private val say: (String) -> Unit,
    /** 作ったファイルを、保存先を選ぶ流れに渡す(ホームへ移って、「ここに保存」を待つ) */
    private val offerSave: (IncomingFile) -> Unit,
    /** 読み込むファイルを選んでもらっている間 true */
    private val pickFile: (Boolean) -> Unit,
) {
    sealed class Step {
        data object None : Step()
        data object ExportAsk : Step()
        data object Working : Step()
        class ImportAsk(val bytes: ByteArray, val error: String?) : Step()
        class ImportPreview(val data: BackupData) : Step()
    }

    var step by mutableStateOf<Step>(Step.None)

    fun startExport() { step = Step.ExportAsk }

    private fun toast(id: Int) = say(Txt(id))

    /** 書き出す: 暗号化したファイルを一時の置き場に作り、保存先を選ぶ流れに渡す。 */
    private fun export(pw: String, history: Boolean, nasPasswords: Boolean) {
        step = Step.Working
        scope.launch {
            val made = withContext(Dispatchers.Default) {
                runCatching {
                    // パスワードなしのときも、簡易な暗号化をかける。NAS のパスワードを入れるかは、書き出す人の選択
                    val file = BackupFile.pack(BackupCodec.encode(BackupManager.collect(app, history, includePasswords = nasPasswords)), pw)
                    val dir = BackupManager.tempDir(app).apply { deleteRecursively(); mkdirs() }
                    val name = "filer-backup-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".filerbak"
                    val out = File(dir, name)
                    out.writeBytes(file)
                    IncomingFile(name, file.size.toLong(), FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", out), null)
                }
            }
            step = Step.None
            made.onSuccess { offerSave(it) }.onFailure { toast(R.string.backup_export_failed) }
        }
    }

    /** 読み込む: ファイル画面で選んでもらう。選ばれたファイルは importFrom で受ける。 */
    fun startImport() { pickFile(true) }

    /** ファイル画面で .filerbak が押されたとき。中身を読んで、パスワードが要るかで分ける。 */
    fun importFrom(fs: Fs, item: FileItem) {
        pickFile(false)
        step = Step.Working
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    fs.open(item.path).use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        while (out.size() <= BackupFile.MAX_FILE_BYTES) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                        out.toByteArray()
                    }
                }.getOrNull()
            }
            when {
                bytes == null -> { step = Step.None; toast(R.string.backup_err_read) }
                else -> when (BackupFile.peek(bytes)) {
                    BackupFile.Kind.TOO_BIG -> { step = Step.None; toast(R.string.backup_err_too_big) }
                    BackupFile.Kind.NOT_BACKUP -> { step = Step.None; toast(R.string.backup_err_not_backup) }
                    BackupFile.Kind.TOO_NEW -> { step = Step.None; toast(R.string.backup_err_too_new) }
                    BackupFile.Kind.SIMPLE -> tryOpen(Step.ImportAsk(bytes, null), "") // パスワードなしのファイルは、そのまま開く
                    BackupFile.Kind.ENCRYPTED -> step = Step.ImportAsk(bytes, null)
                }
            }
        }
    }
    private fun tryOpen(ask: Step.ImportAsk, pw: String) {
        step = Step.Working
        scope.launch {
            when (val r = withContext(Dispatchers.Default) { BackupFile.open(ask.bytes, pw) }) {
                is BackupFile.Opened.Ok -> {
                    val data = withContext(Dispatchers.Default) { BackupCodec.decode(r.plain) }
                    if (data == null) { step = Step.None; toast(R.string.backup_err_bad) } else step = Step.ImportPreview(data)
                }
                is BackupFile.Opened.Fail -> when (r.why) {
                    BackupFile.Failure.PASSWORD_OR_CORRUPT ->
                        if (BackupFile.peek(ask.bytes) == BackupFile.Kind.SIMPLE) { step = Step.None; toast(R.string.backup_err_bad) } // パスワードなしのファイルは、壊れているだけ
                        else step = Step.ImportAsk(ask.bytes, Txt(R.string.backup_err_password))
                    BackupFile.Failure.NOT_BACKUP -> { step = Step.None; toast(R.string.backup_err_not_backup) }
                    BackupFile.Failure.TOO_NEW -> { step = Step.None; toast(R.string.backup_err_too_new) }
                    BackupFile.Failure.TOO_BIG -> { step = Step.None; toast(R.string.backup_err_too_big) }
                }
            }
        }
    }

    private fun applyImport(data: BackupData, replace: Boolean) {
        step = Step.Working
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { BackupManager.apply(app, data, replace) }.isSuccess }
            if (!ok) { step = Step.None; toast(R.string.backup_err_bad); return@launch }
            toast(R.string.backup_imported)
            delay(700)
            BackupManager.restart(app)
        }
    }

    @Composable
    fun Dialogs() {
        when (val s = step) {
            Step.None -> {}
            Step.ExportAsk -> ExportDialog()
            Step.Working -> AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.width(28.dp).height(28.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(16.dp))
                        Text(Txt(R.string.backup_working))
                    }
                },
            )
            is Step.ImportAsk -> ImportPasswordDialog(s)
            is Step.ImportPreview -> PreviewDialog(s.data)
        }
    }

    @Composable
    private fun ExportDialog() {
        var pw by remember { mutableStateOf("") }
        var pw2 by remember { mutableStateOf("") }
        var history by remember { mutableStateOf(false) }
        var nasChoice by remember { mutableStateOf<Boolean?>(null) } // 選ぶまでは、パスワードがあるときだけ入れる
        val short = pw.isNotEmpty() && pw.length < BackupFile.MIN_PASSWORD
        val mismatch = pw2.isNotEmpty() && pw != pw2
        // パスワードは任意。決めるなら 8 文字以上で、2 回同じに入れる。決めないなら両方空
        val noPassword = pw.isEmpty() && pw2.isEmpty()
        val ready = noPassword || (pw.length >= BackupFile.MIN_PASSWORD && pw == pw2)
        val withNas = nasChoice ?: !noPassword
        AlertDialog(
            onDismissRequest = { step = Step.None },
            title = { Text(Txt(R.string.backup_export_title)) },
            text = {
                Column {
                    Text(Txt(R.string.backup_export_help), fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        pw, { pw = it }, singleLine = true, label = { Text(Txt(R.string.backup_password)) },
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = short, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        pw2, { pw2 = it }, singleLine = true, label = { Text(Txt(R.string.backup_password_repeat)) },
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = mismatch, supportingText = { if (mismatch) Text(Txt(R.string.backup_password_mismatch)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (noPassword) {
                        Spacer(Modifier.height(4.dp))
                        Text(Txt(R.string.backup_no_password_note), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                    Row(Modifier.fillMaxWidth().clickable { nasChoice = !withNas }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(withNas, { nasChoice = it })
                        Text(Txt(R.string.backup_include_nas_passwords), fontSize = 14.sp)
                    }
                    if (noPassword && withNas) {
                        Text(Txt(R.string.backup_nas_plain_warning), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                    Row(Modifier.fillMaxWidth().clickable { history = !history }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(history, { history = it })
                        Text(Txt(R.string.backup_include_history), fontSize = 14.sp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { export(pw, history, withNas) }, enabled = ready) { Text(Txt(R.string.backup_choose_place)) } },
            dismissButton = { TextButton(onClick = { step = Step.None }) { Text(Txt(R.string.backup_cancel)) } },
        )
    }

    @Composable
    private fun ImportPasswordDialog(ask: Step.ImportAsk) {
        var pw by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { step = Step.None },
            title = { Text(Txt(R.string.backup_import_title)) },
            text = {
                Column {
                    Text(Txt(R.string.backup_import_help), fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        pw, { pw = it }, singleLine = true, label = { Text(Txt(R.string.backup_password_plain)) },
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = ask.error != null, supportingText = { ask.error?.let { Text(it) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { tryOpen(ask, pw) }, enabled = pw.isNotEmpty()) { Text(Txt(R.string.backup_import_go)) } },
            dismissButton = { TextButton(onClick = { step = Step.None }) { Text(Txt(R.string.backup_cancel)) } },
        )
    }

    @Composable
    private fun PreviewDialog(data: BackupData) {
        var replace by remember { mutableStateOf(false) }
        val sum = BackupCodec.summarize(data)
        val lines = buildList {
            if (sum.settings > 0) add(Txt.n(R.plurals.backup_sum_settings, sum.settings))
            if (sum.bookmarks > 0) add(Txt.n(R.plurals.backup_sum_bookmarks, sum.bookmarks))
            if (sum.pinned > 0) add(Txt.n(R.plurals.backup_sum_pinned, sum.pinned))
            if (sum.history > 0) add(Txt.n(R.plurals.backup_sum_history, sum.history))
            if (sum.remotes > 0) add(Txt.n(R.plurals.backup_sum_remotes, sum.remotes))
        }
        AlertDialog(
            onDismissRequest = { step = Step.None },
            title = { Text(Txt(R.string.backup_preview_title)) },
            text = {
                Column {
                    if (lines.isEmpty()) Text(Txt(R.string.backup_sum_none)) else lines.forEach { Text("・$it", fontSize = 14.sp) }
                    if (data.remotes.isNotEmpty() && data.remotes.all { it.password.isEmpty() }) {
                        Text(Txt(R.string.backup_nas_no_password), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(12.dp))
                    ModeRow(!replace, Txt(R.string.backup_mode_merge), Txt(R.string.backup_mode_merge_help)) { replace = false }
                    ModeRow(replace, Txt(R.string.backup_mode_replace), Txt(R.string.backup_mode_replace_help)) { replace = true }
                    Spacer(Modifier.height(8.dp))
                    Text(Txt(R.string.backup_restart_note), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { applyImport(data, replace) }, enabled = lines.isNotEmpty()) { Text(Txt(R.string.backup_import_do)) } },
            dismissButton = { TextButton(onClick = { step = Step.None }) { Text(Txt(R.string.backup_cancel)) } },
        )
    }

    @Composable
    private fun ModeRow(selected: Boolean, title: String, help: String, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.Start) {
            RadioButton(selected, onClick)
            Column(Modifier.padding(top = 12.dp)) {
                Text(title, fontSize = 14.sp)
                Text(help, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
