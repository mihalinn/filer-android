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
    add(Txt(R.string.audio_ui_001), tags.title?.toString())
    add(Txt(R.string.audio_ui_002), tags.artist?.toString())
    add(Txt(R.string.audio_ui_003), tags.albumTitle?.toString())
    add(Txt(R.string.audio_ui_004), tags.albumArtist?.toString())
    add(Txt(R.string.audio_ui_005), tags.composer?.toString())
    add(Txt(R.string.audio_ui_006), tags.genre?.toString())
    (tags.recordingYear ?: tags.releaseYear)?.let { add(Txt(R.string.audio_ui_007), it.toString()) }
    if (tags.trackNumber != null) add(Txt(R.string.audio_ui_008), "${tags.trackNumber}" + (tags.totalTrackCount?.let { "/$it" } ?: ""))
    if (tags.discNumber != null) add(Txt(R.string.audio_ui_009), "${tags.discNumber}" + (tags.totalDiscCount?.let { "/$it" } ?: ""))
    add(Txt(R.string.audio_ui_010), tags.description?.toString())
    val fmt = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }?.getTrackFormat(0)
    if (fmt != null) {
        add(Txt(R.string.audio_ui_011), TrackLabels.codec(fmt.sampleMimeType) ?: fmt.sampleMimeType)
        add(Txt(R.string.audio_ui_012), MusicText.bitrate(if (fmt.averageBitrate > 0) fmt.averageBitrate else fmt.bitrate))
        add(Txt(R.string.audio_ui_013), MusicText.sampleRate(fmt.sampleRate))
        add(Txt(R.string.audio_ui_014), TrackLabels.channels(fmt.channelCount))
    }
    if (durationMs > 0) add(Txt(R.string.audio_ui_015), formatClock(durationMs))
    add(Txt(R.string.audio_ui_016), item.name)
    add(Txt(R.string.audio_ui_017), FileOps.formatSize(item.size))
    if (rows.size <= 3) rows.add(0, Txt(R.string.audio_ui_018) to Txt(R.string.audio_ui_019))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Txt(R.string.audio_ui_020)) },
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
        confirmButton = { TextButton(onClick = onDismiss) { Text(Txt(R.string.audio_ui_021)) } },
    )
}
