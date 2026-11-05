package local.filemanager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ---------------------------------------------------------------- 共通: zip の中身を読む

private class ZipBox(val names: Set<String>, val read: (String) -> ByteArray?, val close: () -> Unit)

private fun openZipBox(fs: Fs, item: FileItem, limit: Long = 64L * 1024 * 1024): ZipBox {
    val src = openSource(fs, item.path) ?: throw OpException(Txt(R.string.apple_ui_001))
    try {
        val reader = ZipReader(src)
        val byName = reader.entries.filter { !it.isDir }.associateBy { ZipTree.clean(it.name) }
        return ZipBox(byName.keys, { name ->
            byName[name]?.takeIf { it.size <= limit }?.let { e -> runCatching { reader.open(e).use { it.readBytes() } }.getOrNull() }
        }, { src.close() })
    } catch (e: Throwable) {
        src.close()
        throw e
    }
}

@Composable
private fun InfoLine(label: String, value: String, color: androidx.compose.ui.graphics.Color = Pv.text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = Pv.label, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp).width(108.dp))
        SelectionContainer { Text(value, color = color, fontSize = 14.sp, modifier = Modifier.weight(1f)) }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, color = Pv.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))
}

// ---------------------------------------------------------------- iWork

private sealed interface IworkLoad {
    data object Busy : IworkLoad
    data class Ok(val info: IworkInfo, val preview: Bitmap?) : IworkLoad
    data class Fail(val message: String) : IworkLoad
}

private fun loadIwork(fs: Fs, item: FileItem): IworkLoad {
    val box = try {
        openZipBox(fs, item, 256L * 1024 * 1024)
    } catch (e: OpException) {
        return IworkLoad.Fail(e.message ?: Txt(R.string.apple_ui_002))
    } catch (e: Exception) {
        return IworkLoad.Fail(Txt(R.string.apple_ui_003))
    }
    return try {
        val info = Iwork.parse(item.name, box.names, box.read)
        val bmp = info.preview?.let { n -> box.read(n)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
        IworkLoad.Ok(info, bmp)
    } catch (e: OpException) {
        IworkLoad.Fail(e.message ?: Txt(R.string.apple_ui_004))
    } catch (e: Exception) {
        IworkLoad.Fail(Txt(R.string.apple_ui_005, e.message ?: e.javaClass.simpleName))
    } catch (e: OutOfMemoryError) {
        IworkLoad.Fail(Txt(R.string.apple_ui_006))
    } finally {
        box.close()
    }
}

/** Pages / Numbers / Keynote の書類。プレビュー画像と、書類の情報、取り出せた文字を見せる(レイアウトの再現はしない)。 */
@Composable
internal fun IworkPane(fs: Fs, item: FileItem, onOpenInner: (FmViewModel.ViewerRequest) -> Unit, onList: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val load = produceState<IworkLoad>(IworkLoad.Busy, item.path) {
        value = withContext(Dispatchers.IO) { loadIwork(fs, item) }
    }.value
    when (load) {
        IworkLoad.Busy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Pv.text) }
        is IworkLoad.Fail -> Box(Modifier.fillMaxSize()) { Text(load.message, color = Pv.text, modifier = Modifier.align(Alignment.Center).padding(32.dp)) }
        is IworkLoad.Ok -> {
            val info = load.info
            LazyColumn(Modifier.fillMaxSize().belowBars(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp)) {
                item {
                    Text(Txt(R.string.apple_ui_007, info.app), color = Pv.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(if (info.legacy) Txt(R.string.apple_ui_008) else Txt(R.string.apple_ui_009), color = Pv.label, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp))
                    if (load.preview != null) {
                        Image(
                            load.preview.asImageBitmap(), Txt(R.string.apple_ui_010),
                            Modifier.fillMaxWidth().border(1.dp, Pv.label.copy(alpha = 0.4f)),
                            contentScale = ContentScale.FillWidth,
                        )
                        Text(Txt(R.string.apple_ui_011), color = Pv.label, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    } else {
                        Text(Txt(R.string.apple_ui_012), color = Pv.label, fontSize = 13.sp)
                    }
                    if (info.pdf != null) {
                        Text(
                            Txt(R.string.apple_ui_013), color = Pv.link, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 14.dp).clickable {
                                scope.launch {
                                    val f = runCatching { withContext(Dispatchers.IO) { extractPdf(ctx.cacheDir, fs, item, info.pdf) } }.getOrNull()
                                    if (f != null) onOpenInner(FmViewModel.ViewerRequest(LocalFs, listOf(FileItem(f.path, f.name, false, f.length(), f.lastModified())), 0))
                                }
                            },
                        )
                    }
                    Text(Txt(R.string.apple_ui_014), color = Pv.link, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp).clickable { onList() })
                    Heading(Txt(R.string.apple_ui_015))
                    InfoLine(Txt(R.string.apple_ui_016), Txt(R.string.apple_ui_017, FileOps.formatSize(item.size), FileOps.formatDate(item.modified)))
                    if (info.media.isNotEmpty()) InfoLine(Txt(R.string.apple_ui_018), Txt.n(R.plurals.apple_media_info, info.media.size, FileOps.formatSize(info.media.sumOf { it.second })))
                    for (v in info.versions.take(12)) InfoLine(Txt(R.string.apple_ui_019), v)
                }
                item { Heading(Txt.n(R.plurals.apple_texts_heading, info.texts.size)) }
                if (info.texts.isEmpty()) {
                    item { Text(info.textNote ?: Txt(R.string.apple_ui_020), color = Pv.label, fontSize = 13.sp) }
                } else {
                    item { Text(Txt(R.string.apple_ui_021), color = Pv.label, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp)) }
                    val shown = info.texts.take(2000)
                    items(shown.size) { i ->
                        SelectionContainer { Text(shown[i], color = Pv.text, fontSize = 14.sp, modifier = Modifier.padding(vertical = 2.dp)) }
                    }
                    if (info.texts.size > shown.size) item { Text(Txt.n(R.plurals.apple_texts_more, info.texts.size - shown.size), color = Pv.label, fontSize = 12.sp) }
                    if (info.textNote != null) item { Text(info.textNote, color = Pv.label, fontSize = 12.sp) }
                }
            }
        }
    }
}

private fun extractPdf(cache: File, fs: Fs, item: FileItem, entry: String): File {
    val box = openZipBox(fs, item, 256L * 1024 * 1024)
    try {
        val bytes = box.read(entry) ?: throw OpException(Txt(R.string.apple_ui_022))
        val dir = File(File(cache, "zipview"), cacheKey(item.path, item.size, item.modified, entry)).also { it.mkdirs() }
        val out = File(dir, FileOps.safeFileName(item.name.substringBeforeLast('.') + ".pdf"))
        out.writeBytes(bytes)
        return out
    } finally {
        box.close()
    }
}

// ---------------------------------------------------------------- iPhone アプリ(.ipa)

private sealed interface IpaLoad {
    data object Busy : IpaLoad
    data class Ok(val info: IpaInfo, val fileCount: Int) : IpaLoad
    data class Fail(val message: String) : IpaLoad
}

private fun loadIpa(fs: Fs, item: FileItem): IpaLoad {
    val box = try {
        openZipBox(fs, item)
    } catch (e: OpException) {
        return IpaLoad.Fail(e.message ?: Txt(R.string.apple_ui_023))
    } catch (e: Exception) {
        return IpaLoad.Fail(Txt(R.string.apple_ui_024))
    }
    return try {
        IpaLoad.Ok(Ipa.parse(box.names, box.read), box.names.size)
    } catch (e: OpException) {
        IpaLoad.Fail(e.message ?: Txt(R.string.apple_ui_025))
    } catch (e: Exception) {
        IpaLoad.Fail(Txt(R.string.apple_ui_026, e.message ?: e.javaClass.simpleName))
    } finally {
        box.close()
    }
}

/** iPhone アプリ(.ipa)の情報。Android には入れられないので、中身(名前・版・必要な許可)を見るだけ。 */
@Composable
internal fun IpaPane(fs: Fs, item: FileItem, onList: () -> Unit) {
    val load = produceState<IpaLoad>(IpaLoad.Busy, item.path) {
        value = withContext(Dispatchers.IO) { loadIpa(fs, item) }
    }.value
    when (load) {
        IpaLoad.Busy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Pv.text) }
        is IpaLoad.Fail -> Box(Modifier.fillMaxSize()) { Text(load.message, color = Pv.text, modifier = Modifier.align(Alignment.Center).padding(32.dp)) }
        is IpaLoad.Ok -> {
            val d = load.info
            LazyColumn(Modifier.fillMaxSize().belowBars(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp)) {
                item {
                    Text(d.name ?: item.name, color = Pv.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(d.bundleId ?: "", color = Pv.label, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 10.dp))
                    Text(Txt(R.string.apple_ui_027), color = Pv.label, fontSize = 12.sp)
                    InfoLine(Txt(R.string.apple_ui_028), listOfNotNull(d.version, d.build?.let { "($it)" }).joinToString(" "))
                    InfoLine(Txt(R.string.apple_ui_029), d.minOs?.let { Txt(R.string.apple_ui_030, it) } ?: Txt(R.string.apple_ui_031))
                    InfoLine(Txt(R.string.apple_ui_032), d.devices.joinToString(Txt(R.string.sep_dot)).ifEmpty { Txt(R.string.apple_ui_033) })
                    InfoLine(Txt(R.string.apple_ui_034), d.executable ?: Txt(R.string.apple_ui_035))
                    InfoLine(Txt(R.string.apple_ui_036), if (d.signed) Txt(R.string.apple_ui_037) else Txt(R.string.apple_ui_038))
                    if (d.provision != null) InfoLine(Txt(R.string.apple_ui_039), d.provision)
                    InfoLine(Txt(R.string.apple_ui_040), Txt.n(R.plurals.apple_ipa_file, load.fileCount, FileOps.formatSize(item.size)))
                    if (d.backgroundModes.isNotEmpty()) InfoLine(Txt(R.string.apple_ui_041), d.backgroundModes.joinToString(Txt(R.string.sep_dot)))
                    if (d.urlSchemes.isNotEmpty()) InfoLine(Txt(R.string.apple_ui_042), d.urlSchemes.joinToString("\n"))
                    Text(Txt(R.string.apple_ui_043), color = Pv.link, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp).clickable { onList() })
                    Heading(Txt(R.string.apple_ui_044, d.permissions.size))
                    if (d.permissions.isEmpty()) Text(Txt(R.string.apple_ui_045), color = Pv.label, fontSize = 13.sp)
                }
                items(d.permissions.size) { i ->
                    val (n, why) = d.permissions[i]
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(n, color = Pv.text, fontSize = 14.sp)
                        if (why.isNotEmpty()) Text(why, color = Pv.label, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
