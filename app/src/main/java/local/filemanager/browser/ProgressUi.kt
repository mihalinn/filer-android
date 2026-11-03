package local.filemanager

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale

private fun percent(f: Float) = String.format(Locale.US, "%.2f%%", f * 100)

private fun fromLabel(verb: String) = if (verb == "コピー" || verb == "移動") "${verb}元" else "元"

private fun toLabel(verb: String) = if (verb == "コピー" || verb == "移動") "${verb}先" else "先"

/** コピー・移動・ダウンロードなどの進捗。割合、元と先、転送した量と速度、経過時間を出す。 */
@Composable
internal fun ProgressDialog(p: ProgressView, onHide: () -> Unit, onCancel: () -> Unit) {
    val pal = LocalPal.current
    Dialog(onDismissRequest = onHide, properties = DialogProperties(dismissOnClickOutside = false)) {
        Surface(shape = RoundedCornerShape(12.dp), color = pal.surface, tonalElevation = 6.dp) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${p.verb}しています", fontSize = 22.sp, color = pal.text, modifier = Modifier.weight(1f))
                    p.fraction?.let { Text(percent(it), fontSize = 18.sp, color = pal.text) }
                }
                Spacer(Modifier.height(14.dp))
                if (p.from.isNotEmpty()) {
                    Text("${fromLabel(p.verb)}:${p.from}", fontSize = 13.sp, color = pal.subText, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                }
                if (p.to.isNotEmpty()) {
                    Text("${toLabel(p.verb)}:${p.to}", fontSize = 13.sp, color = pal.subText, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(10.dp))
                Row {
                    val size = if (p.totalBytes > 0) "${FileOps.formatSize(p.doneBytes)} / ${FileOps.formatSize(p.totalBytes)}" else FileOps.formatSize(p.doneBytes)
                    Text(size, fontSize = 14.sp, color = pal.subText, modifier = Modifier.weight(1f))
                    Text("${FileOps.formatSize(p.bytesPerSecond)}/s", fontSize = 14.sp, color = pal.subText)
                }
                Spacer(Modifier.height(8.dp))
                val f = p.fraction
                if (f != null) {
                    LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().height(4.dp), color = pal.accent)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp), color = pal.accent)
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    Text("項目 ${p.itemsDone} / ${p.itemsTotal}", fontSize = 14.sp, color = pal.subText, modifier = Modifier.weight(1f))
                    Text(formatElapsed(p.elapsedMs), fontSize = 14.sp, color = pal.subText)
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onHide) { Text("非表示にする") }
                    TextButton(onClick = onCancel) { Text("キャンセル") }
                }
            }
        }
    }
}

/** 「非表示にする」を押したあと、画面の下に出す小さな進捗。タップで元の窓に戻る。 */
@Composable
internal fun HiddenProgressBar(p: ProgressView, onShow: () -> Unit) {
    val pal = LocalPal.current
    Surface(
        modifier = Modifier.padding(12.dp).clickable(onClick = onShow),
        shape = RoundedCornerShape(8.dp),
        color = pal.actionBar,
        tonalElevation = 6.dp,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${p.verb}中", color = pal.onBar, fontSize = 14.sp)
                Spacer(Modifier.width(12.dp))
                Text(
                    p.fraction?.let { percent(it) } ?: FileOps.formatSize(p.doneBytes),
                    color = pal.onBar, fontSize = 14.sp,
                )
                Spacer(Modifier.width(12.dp))
                Text("タップで表示", color = pal.onBar.copy(alpha = 0.7f), fontSize = 12.sp)
            }
            Spacer(Modifier.height(6.dp))
            val f = p.fraction
            if (f != null) {
                LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().height(3.dp), color = pal.actionAccent)
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp), color = pal.actionAccent)
            }
        }
    }
}
