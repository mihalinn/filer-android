package local.filemanager

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 動作の記録の画面。アプリが重くなったときの原因を調べるための、メモリなどの記録(新しい順)。
 * 重いと感じたときに開いてコピーし、不具合の報告に添えられる。
 */
@Composable
internal fun PerfLogDialog(dismiss: () -> Unit) {
    val pal = LocalPal.current
    var stamp by remember { mutableIntStateOf(0) }
    val lines = remember(stamp) { PerfLog.lines() }
    val clip = LocalClipboardManager.current
    var confirm by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("動作の記録") },
        text = {
            Column(Modifier.heightIn(max = 460.dp)) {
                Text(
                    "アプリが重くなったときの原因を調べるための記録です。端末の中にだけ残り、外には送りません。",
                    fontSize = 12.sp, color = pal.subText,
                )
                Text(
                    "重いと感じたら、再起動する前に開いて、コピーしてください。",
                    fontSize = 12.sp, color = pal.subText, modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(8.dp))
                if (lines.isEmpty()) {
                    Text("まだ記録がありません", fontSize = 14.sp, color = pal.subText)
                } else {
                    Column(Modifier.fillMaxWidth().clip(shape).background(pal.surface).border(1.dp, pal.divider, shape).verticalScroll(rememberScrollState()).padding(10.dp)) {
                        SelectionContainer {
                            Text(lines.take(150).joinToString("\n"), fontSize = 10.sp, lineHeight = 13.sp, fontFamily = FontFamily.Monospace, color = pal.text)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("閉じる") } },
        dismissButton = {
            Row {
                TextButton(onClick = { stamp++ }) { Text("更新") }
                if (lines.isNotEmpty()) {
                    TextButton(onClick = { clip.setText(AnnotatedString(lines.joinToString("\n"))) }) { Text("コピー") }
                    TextButton(onClick = { confirm = true }) { Text("消去") }
                }
            }
        },
    )
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("記録を消去") },
            text = { Text("動作の記録をすべて消去します。消去した記録は元に戻せません") },
            confirmButton = { TextButton(onClick = { PerfLog.clear(); stamp++; confirm = false }) { Text("消去") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("キャンセル") } },
        )
    }
}
