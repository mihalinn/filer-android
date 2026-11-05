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
        title = { Text(Txt(R.string.perf_log_ui_001)) },
        text = {
            Column(Modifier.heightIn(max = 460.dp)) {
                Text(
                    Txt(R.string.perf_log_ui_002),
                    fontSize = 12.sp, color = pal.subText,
                )
                Text(
                    Txt(R.string.perf_log_ui_003),
                    fontSize = 12.sp, color = pal.subText, modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(8.dp))
                if (lines.isEmpty()) {
                    Text(Txt(R.string.perf_log_ui_004), fontSize = 14.sp, color = pal.subText)
                } else {
                    Column(Modifier.fillMaxWidth().clip(shape).background(pal.surface).border(1.dp, pal.divider, shape).verticalScroll(rememberScrollState()).padding(10.dp)) {
                        SelectionContainer {
                            Text(lines.take(150).joinToString("\n"), fontSize = 10.sp, lineHeight = 13.sp, fontFamily = FontFamily.Monospace, color = pal.text)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(Txt(R.string.perf_log_ui_005)) } },
        dismissButton = {
            Row {
                TextButton(onClick = { stamp++ }) { Text(Txt(R.string.perf_log_ui_006)) }
                if (lines.isNotEmpty()) {
                    TextButton(onClick = { clip.setText(AnnotatedString(lines.joinToString("\n"))) }) { Text(Txt(R.string.perf_log_ui_007)) }
                    TextButton(onClick = { confirm = true }) { Text(Txt(R.string.perf_log_ui_008)) }
                }
            }
        },
    )
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(Txt(R.string.perf_log_ui_009)) },
            text = { Text(Txt(R.string.perf_log_ui_010)) },
            confirmButton = { TextButton(onClick = { PerfLog.clear(); stamp++; confirm = false }) { Text(Txt(R.string.perf_log_ui_011)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(Txt(R.string.perf_log_ui_012)) } },
        )
    }
}
