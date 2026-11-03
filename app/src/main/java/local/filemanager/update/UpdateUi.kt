package local.filemanager

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun sizeText(bytes: Long) = if (bytes > 0) "約 ${FileOps.formatSize(bytes)}" else ""

/** 「アプリの更新があります」などの窓。確認で見つかったとき・ダウンロード中・終わったとき・失敗したときに出る。 */
@Composable
internal fun UpdateDialog(u: UpdateManager) {
    if (!u.promptVisible) return
    when (val st = u.status) {
        is UpdateStatus.Available -> AlertDialog(
            onDismissRequest = u::later,
            title = { Text("アプリの更新があります") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text("ファイラー ${st.info.version}", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "いまのバージョン ${u.currentVersion}" + sizeText(st.info.apkSize).let { if (it.isEmpty()) "" else "・$it" },
                        fontSize = 13.sp, color = LocalPal.current.subText,
                    )
                    if (st.info.notes.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(st.info.notes, fontSize = 13.sp)
                    }
                }
            },
            // ボタンは縦に並べる(長い名前でも折り返さない)
            confirmButton = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                    TextButton(onClick = u::download) { Text("更新する") }
                    TextButton(onClick = u::later) { Text("後で更新する") }
                    TextButton(onClick = u::openGitHub) { Text("GitHubから更新") }
                }
            },
        )

        is UpdateStatus.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("ダウンロードしています") },
            text = {
                Column {
                    if (st.total > 0) LinearProgressIndicator(progress = { (st.done.toFloat() / st.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(
                        FileOps.formatSize(st.done) + if (st.total > 0) " / ${FileOps.formatSize(st.total)}" else "",
                        fontSize = 13.sp, color = LocalPal.current.subText,
                    )
                }
            },
            confirmButton = { TextButton(onClick = u::cancel) { Text("キャンセル") } },
        )

        is UpdateStatus.Ready -> AlertDialog(
            onDismissRequest = u::closePrompt,
            title = { Text("ダウンロードが終わりました") },
            text = { Text("インストールの画面で「更新」を押してください") },
            confirmButton = { TextButton(onClick = u::install) { Text("インストール") } },
            dismissButton = { TextButton(onClick = u::closePrompt) { Text("閉じる") } },
        )

        is UpdateStatus.Failed -> AlertDialog(
            onDismissRequest = u::closePrompt,
            title = { Text("更新できませんでした") },
            text = { Text(st.message) },
            confirmButton = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                    if (st.info != null) TextButton(onClick = u::download) { Text("もう一度") }
                    TextButton(onClick = u::openGitHub) { Text("GitHubから更新") }
                    TextButton(onClick = u::closePrompt) { Text("閉じる") }
                }
            },
        )

        else -> {}
    }
}

/** 設定の「アプリの更新」グループの中身。後で更新するを選んだ場合も、ここから更新できる。 */
@Composable
internal fun UpdateSettings(u: UpdateManager) {
    val pal = LocalPal.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text("いまのバージョン", fontSize = 13.sp, color = pal.subText)
        Text(u.currentVersion.ifEmpty { "不明" }, fontSize = 16.sp, color = pal.text)
        Spacer(Modifier.height(10.dp))
        when (val st = u.status) {
            UpdateStatus.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = pal.accent)
                Spacer(Modifier.width(10.dp))
                Text("確認しています", fontSize = 14.sp, color = pal.subText)
            }

            is UpdateStatus.Available, is UpdateStatus.Ready -> {
                val info = (st as? UpdateStatus.Available)?.info ?: (st as UpdateStatus.Ready).info
                val shape = RoundedCornerShape(10.dp)
                Column(
                    Modifier.fillMaxWidth().clip(shape).background(pal.accent.copy(alpha = 0.12f)).border(1.dp, pal.accent.copy(alpha = 0.45f), shape)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text("新しいバージョンがあります", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = pal.accent)
                    Text(
                        "${info.version}" + sizeText(info.apkSize).let { if (it.isEmpty()) "" else "・$it" },
                        fontSize = 14.sp, color = pal.text, modifier = Modifier.padding(top = 2.dp),
                    )
                    if (info.notes.isNotEmpty()) Text(info.notes, fontSize = 12.sp, color = pal.subText, modifier = Modifier.padding(top = 6.dp))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (st is UpdateStatus.Ready) Button(onClick = u::install, shape = RoundedCornerShape(10.dp)) { Text("インストール") }
                    else Button(onClick = u::download, shape = RoundedCornerShape(10.dp)) { Text("更新する") }
                    OutlinedButton(onClick = u::openGitHub, shape = RoundedCornerShape(10.dp)) { Text("GitHubから更新") }
                }
            }

            is UpdateStatus.Downloading -> {
                LinearProgressIndicator(progress = { if (st.total > 0) (st.done.toFloat() / st.total).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
                Text(
                    "ダウンロードしています  " + FileOps.formatSize(st.done) + if (st.total > 0) " / ${FileOps.formatSize(st.total)}" else "",
                    fontSize = 13.sp, color = pal.subText, modifier = Modifier.padding(top = 6.dp),
                )
                TextButton(onClick = u::cancel) { Text("キャンセル") }
            }

            is UpdateStatus.Failed -> {
                Text(st.message, fontSize = 14.sp, color = pal.text)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    if (st.info != null) Button(onClick = u::download, shape = RoundedCornerShape(10.dp)) { Text("もう一度") }
                    else Button(onClick = { u.check(true) }, shape = RoundedCornerShape(10.dp)) { Text("もう一度確認") }
                    OutlinedButton(onClick = u::openGitHub, shape = RoundedCornerShape(10.dp)) { Text("GitHubから更新") }
                }
            }

            else -> {
                val checked = if (u.lastChecked == 0L) "まだ確認していません" else "最後の確認 ${FileOps.formatDate(u.lastChecked)}"
                Text(if (st is UpdateStatus.UpToDate) "最新のバージョンです" else checked, fontSize = 14.sp, color = pal.text)
                if (st is UpdateStatus.UpToDate) Text(checked, fontSize = 12.sp, color = pal.subText)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    OutlinedButton(onClick = { u.check(true) }, shape = RoundedCornerShape(10.dp)) { Text("更新を確認") }
                    OutlinedButton(onClick = u::openGitHub, shape = RoundedCornerShape(10.dp)) { Text("GitHubを開く") }
                }
            }
        }
    }
    HorizontalDivider(color = pal.divider)
    SwitchRow("起動時に更新を確認する", u.autoCheck) { u.changeAutoCheck(!u.autoCheck) }
    Text(
        "確認するときだけ GitHub(github.com)に接続します。端末の情報は送りません",
        fontSize = 12.sp, color = pal.subText, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
    )
}