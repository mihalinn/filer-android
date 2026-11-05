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

private fun sizeText(bytes: Long) = if (bytes > 0) Txt(R.string.update_ui_001, FileOps.formatSize(bytes)) else ""

/** 「アプリの更新があります」などの窓。確認で見つかったとき・ダウンロード中・終わったとき・失敗したときに出る。 */
@Composable
internal fun UpdateDialog(u: UpdateManager) {
    if (!u.promptVisible) return
    when (val st = u.status) {
        is UpdateStatus.Available -> AlertDialog(
            onDismissRequest = u::later,
            title = { Text(Txt(R.string.update_ui_002)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(Txt(R.string.update_ui_003, st.info.version), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        Txt(R.string.update_ui_004, u.currentVersion) + sizeText(st.info.apkSize).let { if (it.isEmpty()) "" else Txt(R.string.update_ui_005, it) },
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
                    TextButton(onClick = u::download) { Text(Txt(R.string.update_ui_006)) }
                    TextButton(onClick = u::later) { Text(Txt(R.string.update_ui_007)) }
                    TextButton(onClick = u::openGitHub) { Text(Txt(R.string.update_ui_008)) }
                }
            },
        )

        is UpdateStatus.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text(Txt(R.string.update_ui_009)) },
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
            confirmButton = { TextButton(onClick = u::cancel) { Text(Txt(R.string.update_ui_010)) } },
        )

        is UpdateStatus.Ready -> AlertDialog(
            onDismissRequest = u::closePrompt,
            title = { Text(Txt(R.string.update_ui_011)) },
            text = { Text(Txt(R.string.update_ui_012)) },
            confirmButton = { TextButton(onClick = u::install) { Text(Txt(R.string.update_ui_013)) } },
            dismissButton = { TextButton(onClick = u::closePrompt) { Text(Txt(R.string.update_ui_014)) } },
        )

        is UpdateStatus.Failed -> AlertDialog(
            onDismissRequest = u::closePrompt,
            title = { Text(Txt(R.string.update_ui_015)) },
            text = { Text(st.message) },
            confirmButton = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                    if (st.info != null) TextButton(onClick = u::download) { Text(Txt(R.string.update_ui_016)) }
                    TextButton(onClick = u::openGitHub) { Text(Txt(R.string.update_ui_017)) }
                    TextButton(onClick = u::closePrompt) { Text(Txt(R.string.update_ui_018)) }
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
        Text(Txt(R.string.update_ui_019), fontSize = 13.sp, color = pal.subText)
        Text(u.currentVersion.ifEmpty { Txt(R.string.update_ui_020) }, fontSize = 16.sp, color = pal.text)
        Spacer(Modifier.height(10.dp))
        when (val st = u.status) {
            UpdateStatus.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = pal.accent)
                Spacer(Modifier.width(10.dp))
                Text(Txt(R.string.update_ui_021), fontSize = 14.sp, color = pal.subText)
            }

            is UpdateStatus.Available, is UpdateStatus.Ready -> {
                val info = (st as? UpdateStatus.Available)?.info ?: (st as UpdateStatus.Ready).info
                val shape = RoundedCornerShape(10.dp)
                Column(
                    Modifier.fillMaxWidth().clip(shape).background(pal.accent.copy(alpha = 0.12f)).border(1.dp, pal.accent.copy(alpha = 0.45f), shape)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(Txt(R.string.update_ui_022), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = pal.accent)
                    Text(
                        "${info.version}" + sizeText(info.apkSize).let { if (it.isEmpty()) "" else Txt(R.string.update_ui_023, it) },
                        fontSize = 14.sp, color = pal.text, modifier = Modifier.padding(top = 2.dp),
                    )
                    if (info.notes.isNotEmpty()) Text(info.notes, fontSize = 12.sp, color = pal.subText, modifier = Modifier.padding(top = 6.dp))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (st is UpdateStatus.Ready) Button(onClick = u::install, shape = RoundedCornerShape(10.dp)) { Text(Txt(R.string.update_ui_024)) }
                    else Button(onClick = u::download, shape = RoundedCornerShape(10.dp)) { Text(Txt(R.string.update_ui_025)) }
                    OutlinedButton(onClick = u::openGitHub, shape = RoundedCornerShape(10.dp)) { Text(Txt(R.string.update_ui_026)) }
                }
            }

            is UpdateStatus.Downloading -> {
                LinearProgressIndicator(progress = { if (st.total > 0) (st.done.toFloat() / st.total).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
                Text(
                    Txt(R.string.update_ui_027) + FileOps.formatSize(st.done) + if (st.total > 0) " / ${FileOps.formatSize(st.total)}" else "",
                    fontSize = 13.sp, color = pal.subText, modifier = Modifier.padding(top = 6.dp),
                )
                TextButton(onClick = u::cancel) { Text(Txt(R.string.update_ui_028)) }
            }

            is UpdateStatus.Failed -> {
                Text(st.message, fontSize = 14.sp, color = pal.text)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    if (st.info != null) Button(onClick = u::download, shape = RoundedCornerShape(10.dp)) { Text(Txt(R.string.update_ui_029)) }
                    else Button(onClick = { u.check(true) }, shape = RoundedCornerShape(10.dp)) { Text(Txt(R.string.update_ui_030)) }
                    OutlinedButton(onClick = u::openGitHub, shape = RoundedCornerShape(10.dp)) { Text(Txt(R.string.update_ui_031)) }
                }
            }

            else -> {
                val checked = if (u.lastChecked == 0L) Txt(R.string.update_ui_032) else Txt(R.string.update_ui_033, FileOps.formatDate(u.lastChecked))
                Text(if (st is UpdateStatus.UpToDate) Txt(R.string.update_ui_034) else checked, fontSize = 14.sp, color = pal.text)
                if (st is UpdateStatus.UpToDate) Text(checked, fontSize = 12.sp, color = pal.subText)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    OutlinedButton(onClick = { u.check(true) }, shape = RoundedCornerShape(10.dp)) { Text(Txt(R.string.update_ui_035)) }
                    OutlinedButton(onClick = u::openGitHub, shape = RoundedCornerShape(10.dp)) { Text(Txt(R.string.update_ui_036)) }
                }
            }
        }
    }
    HorizontalDivider(color = pal.divider)
    SwitchRow(Txt(R.string.update_ui_037), u.autoCheck) { u.changeAutoCheck(!u.autoCheck) }
    Text(
        Txt(R.string.update_ui_038),
        fontSize = 12.sp, color = pal.subText, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
    )
    HorizontalDivider(color = pal.divider)
    SwitchRow(Txt(R.string.update_ui_039), u.showBanner, subtitle = Txt(R.string.update_ui_040)) { u.changeShowBanner(!u.showBanner) }
}