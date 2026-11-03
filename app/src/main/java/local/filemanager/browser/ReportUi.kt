package local.filemanager

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 不具合・要望の報告。GitHub の Issue の入力ページを、バージョンや端末の情報を入れた状態でブラウザに開く。
 * ここでは何も送らない。内容は GitHub のページで確かめてから、自分で投稿する。
 */
@Composable
internal fun ReportDialog(vm: FmViewModel, bug: Boolean, onDismiss: () -> Unit) {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val version = vm.updater.currentVersion
    val device = remember { ReportLogic.deviceLine(Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT) }
    val crash = remember {
        buildString {
            CrashLog.read(ctx)?.let { append(it.trim()) }
            val exits = CrashLog.abnormalExits(ctx)
            if (exits.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(exits.joinToString("\n\n"))
            }
        }
    }
    var withLog by remember { mutableStateOf(crash.isNotEmpty()) }
    var showLog by remember { mutableStateOf(false) }

    fun fields(): Map<String, String> = buildMap {
        put("version", version)
        if (bug) {
            put("device", device)
            if (withLog && crash.isNotEmpty()) put("log", crash)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (bug) "不具合を報告" else "要望を送る") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "GitHub の入力ページをブラウザで開きます。内容はそこで確かめてから、自分で投稿してください。この画面からは何も送りません。",
                    fontSize = 13.sp, color = pal.subText,
                )
                Spacer(Modifier.height(10.dp))
                Text("入れる情報", fontSize = 13.sp, color = pal.accent)
                Text("アプリのバージョン: $version", fontSize = 14.sp)
                if (bug) Text("端末: $device", fontSize = 14.sp)
                if (bug) {
                    Spacer(Modifier.height(8.dp))
                    if (crash.isEmpty()) {
                        Text("異常終了の記録はありません", fontSize = 13.sp, color = pal.subText)
                    } else {
                        Row(Modifier.fillMaxWidth().clickable { withLog = !withLog }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = withLog, onCheckedChange = { withLog = it })
                            Text("異常終了の記録も入れる", fontSize = 14.sp)
                        }
                        Text(
                            "記録にファイルの名前などが入っていることがあります。内容を確かめてください",
                            fontSize = 12.sp, color = pal.subText,
                        )
                        TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "記録を隠す" else "記録を見る") }
                        if (showLog) {
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(pal.tileBg).padding(8.dp)) {
                                Text(crash.take(1500) + if (crash.length > 1500) "\n…" else "", fontSize = 10.sp, lineHeight = 13.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "パスワード、NAS の名前や IP アドレス、個人のファイル名は、書かないでください(誰でも読めます)",
                    fontSize = 12.sp, color = pal.subText,
                )
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                TextButton(onClick = {
                    vm.openUrl(ReportLogic.issueUrl(vm.updater.repoUrl, if (bug) "bug_report.yml" else "feature_request.yml", fields(), longKey = "log"))
                    onDismiss()
                }) { Text("GitHubで報告する") }
                TextButton(onClick = {
                    val f = fields()
                    val text = buildString {
                        append("アプリのバージョン: ").append(f["version"]).append('\n')
                        f["device"]?.let { append("端末: ").append(it).append('\n') }
                        f["log"]?.let { append("\n異常終了の記録:\n").append(it).append('\n') }
                    }
                    clip.setText(AnnotatedString(text))
                    onDismiss()
                }) { Text("内容をコピー") }
                TextButton(onClick = onDismiss) { Text("キャンセル") }
            }
        },
    )
}