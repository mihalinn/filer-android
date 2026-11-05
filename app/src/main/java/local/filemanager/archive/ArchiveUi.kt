package local.filemanager

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SubdirectoryArrowLeft
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

// ---------------------------------------------------------------- 共通

internal fun cacheKey(vararg parts: Any): String =
    MessageDigest.getInstance("MD5").digest(parts.joinToString("|").toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

/** 取り込んだ一時ファイルが溜まりすぎないよう、古いものから消す(keep は消さない)。 */
internal fun trimCacheTree(root: File, keep: File?, maxBytes: Long, maxAgeMs: Long) {
    val files = root.walkTopDown().filter { it.isFile && it != keep }.sortedBy { it.lastModified() }.toList()
    var total = files.sumOf { it.length() } + (keep?.length() ?: 0L)
    val now = System.currentTimeMillis()
    for (f in files) {
        if (total > maxBytes || now - f.lastModified() > maxAgeMs) {
            total -= f.length()
            f.delete()
            f.parentFile?.takeIf { it != root && it.list()?.isEmpty() == true }?.delete()
        }
    }
}

internal val rowText: Color get() = Pv.text
internal val rowDim: Color get() = Pv.dim

// ---------------------------------------------------------------- ZIP

private sealed interface ZipLoad {
    data object Busy : ZipLoad
    data class Ok(val handle: ArchiveHandle) : ZipLoad
    data class Fail(val message: String) : ZipLoad
}

private fun openZip(ctx: Context, fs: Fs, item: FileItem): ZipLoad = try {
    ZipLoad.Ok(ArchiveOpener.open(fs, item, ctx.cacheDir))
} catch (e: OpException) {
    ZipLoad.Fail(e.message ?: Txt(R.string.archive_ui_001))
} catch (e: Exception) {
    ZipLoad.Fail(e.message ?: Txt(R.string.archive_ui_002))
} catch (e: OutOfMemoryError) {
    ZipLoad.Fail(Txt(R.string.archive_ui_003))
}

private const val ZIP_OPEN_LIMIT = 256L * 1024 * 1024

/** ZIP の中の 1 つを、端末の一時領域に取り出す(中のファイルをアプリの中で開くため)。 */
private fun extractEntry(ctx: Context, zip: FileItem, reader: ArchiveSource, e: ZipReader.Entry): File {
    if (e.encrypted) throw OpException(Txt(R.string.archive_ui_004))
    if (e.size > ZIP_OPEN_LIMIT) throw OpException(Txt(R.string.archive_ui_005, ZIP_OPEN_LIMIT / 1024 / 1024))
    val root = File(ctx.cacheDir, "zipview")
    val dir = File(root, cacheKey(zip.path, zip.size, zip.modified, e.name)).also { it.mkdirs() }
    val out = File(dir, FileOps.safeFileName(ZipTree.clean(e.name).substringAfterLast('/')))
    if (!(out.exists() && out.length() == e.size)) {
        val tmp = File(dir, out.name + ".part")
        reader.open(e).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        out.delete()
        if (!tmp.renameTo(out)) throw IOException(Txt(R.string.archive_ui_006))
    }
    out.setLastModified(System.currentTimeMillis())
    trimCacheTree(root, out, 500L * 1024 * 1024, 24L * 3600 * 1000)
    return out
}

/** アプリの中のビューアで開ける(それ以外は他のアプリに任せる)か。 */
internal fun viewableInApp(item: FileItem): Boolean =
    item.kind == FileKind.IMAGE || isPlayable(item.kind) || PreviewKind.of(item.name) != null ||
        // 種類の分からないファイル(.bin など)は、一覧と同じく、中身を見て(文字なら文字、そうでなければ種類の推測を出して)開く
        item.kind == FileKind.OTHER

/**
 * ZIP の中身の一覧。フォルダをたどれて、ファイルをタップすると取り出してアプリの中で開く(画像・テキスト・PDF・動画など)。
 * 開くものは onOpenInner で、いちばん外側のビューアに渡す(上のバーより前に出すため)。
 */
@Composable
internal fun ZipPane(vm: FmViewModel, fs: Fs, item: FileItem, onOpenInner: (FmViewModel.ViewerRequest) -> Unit) {
    val ctx = LocalContext.current
    val load = produceState<ZipLoad>(ZipLoad.Busy, item.path) {
        value = withContext(Dispatchers.IO) { openZip(ctx, fs, item) }
    }.value
    when (load) {
        ZipLoad.Busy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Pv.text) }
        is ZipLoad.Fail -> Box(Modifier.fillMaxSize()) {
            Text(load.message, color = Pv.text, modifier = Modifier.align(Alignment.Center).padding(32.dp))
        }
        is ZipLoad.Ok -> {
            DisposableEffect(load) { onDispose { load.handle.close() } }
            ZipList(vm, ctx, item, load.handle.source, onOpenInner)
        }
    }
}

@Composable
private fun ZipList(vm: FmViewModel, ctx: Context, item: FileItem, reader: ArchiveSource, onOpenInner: (FmViewModel.ViewerRequest) -> Unit) {
    var prefix by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val rows = remember(reader, prefix) { ZipTree.list(reader.entries, prefix) }
    val totals = remember(reader) { ZipTree.totals(reader.entries) }

    // 「戻る」は、まず 1 つ上のフォルダへ(いちばん上なら、ビューアを閉じる)
    BackHandler(enabled = prefix.isNotEmpty() && busy == null) { prefix = ZipTree.parent(prefix) }

    fun openEntry(row: ZipTree.Row) {
        val e = row.entry ?: return
        scope.launch {
            busy = row.name
            val r = runCatching { withContext(Dispatchers.IO) { extractEntry(ctx, item, reader, e) } }
            busy = null
            r.onSuccess { f ->
                val fi = FileItem(f.path, f.name, false, f.length(), f.lastModified())
                if (viewableInApp(fi)) onOpenInner(FmViewModel.ViewerRequest(LocalFs, listOf(fi), 0)) else vm.openRequests.tryEmit(f)
            }.onFailure { message = it.message ?: Txt(R.string.archive_ui_007) }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().belowBars()) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("/" + prefix, color = Pv.label, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text(
                        Txt.n(R.plurals.archive_total, totals.first, FileOps.formatSize(totals.second)),
                        color = rowDim, fontSize = 12.sp,
                    )
                }
            }
            if (prefix.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { prefix = ZipTree.parent(prefix) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.SubdirectoryArrowLeft, null, tint = rowDim, modifier = Modifier.size(28.dp))
                        Text(Txt(R.string.archive_ui_008), color = rowText, fontSize = 15.sp, modifier = Modifier.padding(start = 14.dp))
                    }
                }
            }
            items(rows.size) { i ->
                val r = rows[i]
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = busy == null) { if (r.isDir) prefix = r.path else openEntry(r) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (r.isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile, null,
                        tint = if (r.isDir) kindColor(FileKind.FOLDER) else kindColor(FileOps.kindOf(r.name)), modifier = Modifier.size(30.dp),
                    )
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(r.name, color = rowText, fontSize = 15.sp, maxLines = 2)
                        val date = if (r.modified > 0) FileOps.formatDate(r.modified) else ""
                        val sub = if (r.isDir) Txt.n(R.plurals.archive_dir_info, r.count, FileOps.formatSize(r.size)) else FileOps.formatSize(r.size)
                        Text(listOf(sub, date).filter { it.isNotEmpty() }.joinToString(Txt(R.string.sep_dot)), color = rowDim, fontSize = 12.sp)
                    }
                    if (r.entry?.encrypted == true) Icon(Icons.Default.Lock, Txt(R.string.archive_ui_009), tint = rowDim, modifier = Modifier.size(18.dp))
                }
            }
        }
        busy?.let { name ->
            Box(Modifier.fillMaxSize().background(Color(0x88000000)).clickable(enabled = true) {}, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Text(Txt(R.string.archive_ui_010, name), color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
        message?.let { m ->
            AlertDialog(
                onDismissRequest = { message = null },
                confirmButton = { TextButton(onClick = { message = null }) { Text(Txt(R.string.archive_ui_011)) } },
                text = { Text(m) },
            )
        }
    }
}
