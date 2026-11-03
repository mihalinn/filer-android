package local.filemanager

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.media3.common.MediaMetadata
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Tracks

/** 曲のジャケットと、曲名・アーティスト・アルバムなど。ジャケットが無いときは、音符の絵。 */
@Composable
internal fun AudioFace(cover: ImageBitmap?, title: String, lines: List<String>) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(top = 64.dp, bottom = 150.dp, start = 24.dp, end = 24.dp).navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val side = minOf(maxWidth, maxHeight, 340.dp)
            if (cover != null) {
                Image(cover, null, Modifier.size(side).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
            } else {
                val s = minOf(side, 170.dp)
                Box(Modifier.size(s).clip(CircleShape).background(Color(0xFF263238)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.MusicNote, null, tint = Color(0xFF80CBC4), modifier = Modifier.size(s * 0.57f))
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        for (l in lines) Text(l, color = Color(0xFFCCCCCC), fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
    }
}

/** 曲のフォルダにある cover.jpg などを、ジャケットとして読む(無ければ null)。 */
internal fun findFolderCover(fs: Fs, item: FileItem): ImageBitmap? = try {
    val dir = fs.parent(item.path)
    val list = if (dir == null) null else fs.list(dir)
    val files = list?.filter { !it.isDir }
    val name = files?.map { it.name }?.let { CoverFiles.pick(it) }
    val f = files?.firstOrNull { it.name == name }
    if (f == null || f.size !in 1..(8L * 1024 * 1024)) null
    else fs.open(f.path).use { it.readBytes() }.let { ImageDecode.decodeBytes(it, 1024) }?.asImageBitmap()
} catch (e: Throwable) {
    null
}

/** 曲のタグと、音の形式(ファイルの中身から読んだもの)。 */
@Composable
internal fun SongInfoDialog(item: FileItem, tags: MediaMetadata, tracks: Tracks, durationMs: Long, onDismiss: () -> Unit) {
    val rows = ArrayList<Pair<String, String>>()
    fun add(label: String, v: String?) {
        if (!v.isNullOrBlank()) rows.add(label to v)
    }
    add("曲名", tags.title?.toString())
    add("アーティスト", tags.artist?.toString())
    add("アルバム", tags.albumTitle?.toString())
    add("アルバムのアーティスト", tags.albumArtist?.toString())
    add("作曲", tags.composer?.toString())
    add("ジャンル", tags.genre?.toString())
    (tags.recordingYear ?: tags.releaseYear)?.let { add("年", it.toString()) }
    if (tags.trackNumber != null) add("トラック", "${tags.trackNumber}" + (tags.totalTrackCount?.let { "/$it" } ?: ""))
    if (tags.discNumber != null) add("ディスク", "${tags.discNumber}" + (tags.totalDiscCount?.let { "/$it" } ?: ""))
    add("説明", tags.description?.toString())
    val fmt = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }?.getTrackFormat(0)
    if (fmt != null) {
        add("形式", TrackLabels.codec(fmt.sampleMimeType) ?: fmt.sampleMimeType)
        add("ビットレート", MusicText.bitrate(if (fmt.averageBitrate > 0) fmt.averageBitrate else fmt.bitrate))
        add("サンプリング周波数", MusicText.sampleRate(fmt.sampleRate))
        add("チャンネル", TrackLabels.channels(fmt.channelCount))
    }
    if (durationMs > 0) add("長さ", formatClock(durationMs))
    add("ファイル", item.name)
    add("ファイルの大きさ", FileOps.formatSize(item.size))
    if (rows.size <= 3) rows.add(0, "タグ" to "この曲には、曲名などのタグが入っていません")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("曲の情報") },
        text = {
            SelectionContainer {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    for ((label, value) in rows) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.width(112.dp))
                            Text(value, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}
