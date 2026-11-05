package local.filemanager

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 拡張子から種類が分からないファイル。先頭を見て、文字ならそのまま文字として見せる。
 * 文字でなければ、種類の推測と大きさを出して、他のアプリで開くボタンを置く。
 */
@Composable
internal fun UnknownPane(
    fs: Fs, item: FileItem, fontSp: Int, bars: Boolean, onTap: () -> Unit, onExternal: () -> Unit,
    query: String, hit: Int, onHits: (Int) -> Unit, copy: CopyHolder? = null,
) {
    val head = produceState<Pair<ByteArray, Boolean>?>(null, item.path) {
        value = withContext(Dispatchers.IO) { runCatching { fs.open(item.path).use { TextDecode.readUpTo(it, 64 * 1024) } }.getOrNull() ?: (ByteArray(0) to false) }
    }.value
    if (head == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Pv.text) }
        return
    }
    val guess = Sniff.detect(head.first)
    if (guess.isText) {
        DocPane(fs, item, PreviewKind.TEXT, fontSp, bars, false, true, onTap, query, hit, onHits, copy)
        return
    }
    Column(Modifier.fillMaxSize().belowBars()) {
        Column(Modifier.fillMaxWidth().padding(12.dp).background(Pv.panel, RoundedCornerShape(10.dp)).padding(14.dp)) {
            Text(Txt(R.string.unknown_ui_001, guess.label), color = Pv.text, fontSize = 15.sp)
            Text(
                Txt(R.string.unknown_ui_002, FileOps.formatSize(item.size)) + (guess.ext?.let { Txt(R.string.unknown_ui_003, it) } ?: ""),
                color = Pv.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = onExternal) { Text(Txt(R.string.unknown_ui_004)) }
        }
    }
}