package local.filemanager

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 動画・音声・MIDI の再生画面で共通の操作部品。見た目をそろえるため、ここだけで持つ。

/** 上のバー: 戻る・名前(複数あれば「何番目 / 全部」)・右端の actions(メニューなど)。 */
@Composable
internal fun PlayerTopBar(title: String, counter: String?, onClose: () -> Unit, modifier: Modifier = Modifier, actions: @Composable () -> Unit) {
    Row(
        modifier.fillMaxWidth().background(Color(0x99000000)).statusBarsPadding().height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "閉じる", tint = Color.White) }
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (counter != null) Text(counter, color = Color(0xFFCCCCCC), fontSize = 12.sp)
        }
        actions()
    }
}

/** 右上の「︙」ボタン。メニュー本体(ViewerMenu。プレビューと同じ見た目)は呼ぶ側が Box で隣に置く。 */
@Composable
internal fun PlayerMenuButton(onOpen: () -> Unit) {
    IconButton(onClick = onOpen) { Icon(Icons.Default.MoreVert, "メニュー", tint = Color.White) }
}

/** 前へ・10秒戻し・再生/一時停止・10秒送り・次へ。multi が false のとき、前へ・次へは押せない。 */
@Composable
internal fun PlayerTransport(
    modifier: Modifier, multi: Boolean, playing: Boolean,
    onPrev: () -> Unit, onBack10: () -> Unit, onToggle: () -> Unit, onFwd10: () -> Unit, onNext: () -> Unit,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(onClick = onPrev, enabled = multi) {
            Icon(Icons.Default.SkipPrevious, "前へ", tint = Color.White.copy(alpha = if (multi) 1f else 0.3f), modifier = Modifier.size(32.dp))
        }
        IconButton(onClick = onBack10) {
            Icon(Icons.Default.Replay10, "10秒戻す", tint = Color.White, modifier = Modifier.size(34.dp))
        }
        IconButton(onClick = onToggle, modifier = Modifier.size(64.dp).clip(CircleShape).background(Color(0x66000000))) {
            Icon(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                if (playing) "一時停止" else "再生", tint = Color.White, modifier = Modifier.size(40.dp),
            )
        }
        IconButton(onClick = onFwd10) {
            Icon(Icons.Default.Forward10, "10秒送る", tint = Color.White, modifier = Modifier.size(34.dp))
        }
        IconButton(onClick = onNext, enabled = multi) {
            Icon(Icons.Default.SkipNext, "次へ", tint = Color.White.copy(alpha = if (multi) 1f else 0.3f), modifier = Modifier.size(32.dp))
        }
    }
}

/** 下の「時間 ─●─ 長さ」。つまみを離したときに onSeekTo(ミリ秒)を呼ぶ。onTouch はつまみを動かしている間に呼ぶ。 */
@Composable
internal fun PlayerSeekBar(modifier: Modifier, positionMs: Long, durationMs: Long, onSeekTo: (Long) -> Unit, onTouch: () -> Unit = {}) {
    var seeking by remember { mutableStateOf(false) }
    var seekFraction by remember { mutableFloatStateOf(0f) }
    val fraction = if (seeking) seekFraction else if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Row(
        modifier.fillMaxWidth().background(Color(0x99000000)).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(formatClock(if (seeking) (seekFraction * durationMs).toLong() else positionMs), color = Color.White, fontSize = 12.sp)
        Slider(
            value = fraction,
            onValueChange = { seeking = true; seekFraction = it; onTouch() },
            onValueChangeFinished = {
                onSeekTo((seekFraction * durationMs).toLong())
                seeking = false
            },
            enabled = durationMs > 0,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White, activeTrackColor = Color(0xFF9FA8DA), inactiveTrackColor = Color(0x66FFFFFF),
                disabledThumbColor = Color(0x66FFFFFF), disabledActiveTrackColor = Color(0x66FFFFFF),
            ),
        )
        Text(if (durationMs > 0) formatClock(durationMs) else "--:--", color = Color.White, fontSize = 12.sp)
    }
}
