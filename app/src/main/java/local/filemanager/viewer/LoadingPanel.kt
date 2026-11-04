package local.filemanager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** ビューアを閉じる動き。取り込み中の「キャンセル」で使う(ViewerScreen が渡す)。 */
internal val LocalViewerClose = staticCompositionLocalOf<() -> Unit> { {} }

private class PanelInfo(val read: Long, val total: Long, val bytesPerSec: Double, val stalledMs: Long, val active: Boolean)

/**
 * ビューアの読み込み中の表示。ファイル名・状態・バー・量と速さと残り時間・「画面を消しても続きます」・キャンセルを、1 枚にまとめる。
 * 0.7 秒未満で終わるときは何も出さない。NAS のファイルの取り込み(task)の進み具合を見せる。端末内のファイルは「開いています」だけ。
 */
@Composable
internal fun LoadingPanel(name: String, task: BgTask, remote: Boolean) {
    val close = LocalViewerClose.current
    var info by remember(task) { mutableStateOf<PanelInfo?>(null) }
    LaunchedEffect(task) {
        val meter = SpeedMeter()
        var lastRead = -1L
        var lastChange = System.currentTimeMillis()
        delay((700 - (System.currentTimeMillis() - task.createdAt)).coerceAtLeast(0))
        while (true) {
            val now = System.currentTimeMillis()
            val read = task.done
            if (read != lastRead) {
                lastRead = read
                lastChange = now
            }
            meter.add(now, read)
            info = PanelInfo(read, task.total, meter.bytesPerSec(), now - lastChange, task.active)
            delay(250)
        }
    }
    val i = info ?: return
    val fetching = remote && i.active
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 420.dp).fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = Pv.bar,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                val title = when {
                    !fetching -> "ファイルを開いています…"
                    i.read == 0L -> "NAS に接続して、読み込みを始めています…"
                    i.stalledMs > 4000 -> "NAS からの返事を待っています…"
                    else -> "NAS から読み込み中"
                }
                Text(title, color = Pv.barText, fontSize = 14.sp)
                Text(name, color = Pv.barSub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                if (fetching && i.total > 0 && i.read in 1..i.total) {
                    LinearProgressIndicator(progress = { (i.read.toFloat() / i.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(4.dp), color = Pv.accent)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp), color = Pv.accent)
                }
                if (fetching) {
                    Spacer(Modifier.height(8.dp))
                    if (i.read > 0) Text(ReadFormat.amount(i.read, i.total), color = Pv.barText, fontSize = 13.sp)
                    val speedText = if (i.bytesPerSec >= 1024) ReadFormat.speed(i.bytesPerSec) else null
                    val etaText = ReadFormat.eta(i.read, i.total, i.bytesPerSec)
                    val tail = listOfNotNull(speedText, etaText).joinToString("・")
                    if (tail.isNotEmpty()) Text(tail, color = Pv.barSub, fontSize = 12.sp)
                    // 通知を出して続けるのは、大きいときだけ
                    if (task.tracked) Text("画面を消しても、読み込みは続きます", color = Pv.barSub, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { task.cancel(); close() }) { Text("キャンセル", color = Pv.accent) }
                    }
                }
            }
        }
    }
}
