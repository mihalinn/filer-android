package local.filemanager

import android.content.Context
import android.graphics.Typeface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private const val FONT_LIMIT = 64L * 1024 * 1024

private class FontData(val info: FontInfo, val typeface: Typeface, val file: File)

private sealed interface FontLoad {
    data object Busy : FontLoad
    data class Ok(val font: FontData) : FontLoad
    data class Fail(val message: String) : FontLoad
}

private fun openFont(ctx: Context, fs: Fs, item: FileItem, task: BgTask): FontLoad = try {
    if (item.size > FONT_LIMIT) throw OpException(Txt(R.string.font_ui_001, FONT_LIMIT / 1024 / 1024))
    val dir = File(ctx.cacheDir, "fontview").also { it.mkdirs() }
    val f = File(dir, cacheKey(fs.id, item.path, item.size, item.modified) + "." + FileOps.extensionOf(item.name).ifEmpty { "ttf" })
    if (!(f.exists() && f.length() == item.size)) {
        val part = File(dir, f.name + ".part")
        BgTasks.run(task, item.size) { task.download(fs, item.path, part) }
        f.delete()
        if (!part.renameTo(f)) throw IOException(Txt(R.string.font_ui_002))
    }
    f.setLastModified(System.currentTimeMillis())
    trimCacheTree(dir, f, 256L * 1024 * 1024, 24L * 3600 * 1000)
    val bytes = f.readBytes()
    val info = FontInfo.parse(bytes)
    if (info == null) {
        FontLoad.Fail(Txt(R.string.font_ui_003))
    } else {
        FontLoad.Ok(FontData(info, Typeface.createFromFile(f), f))
    }
} catch (e: OpException) {
    FontLoad.Fail(e.message ?: Txt(R.string.font_ui_004))
} catch (e: Exception) {
    FontLoad.Fail(Txt(R.string.font_ui_005, e.message ?: e.javaClass.simpleName))
} catch (e: OutOfMemoryError) {
    FontLoad.Fail(Txt(R.string.font_ui_006))
}

/** フォント(.ttf / .otf / .ttc)の見本と情報。好きな文字を入れて、見え方を確かめられる。 */
@Composable
internal fun FontPane(fs: Fs, item: FileItem) {
    val ctx = LocalContext.current
    val task = rememberBgTask(item.path, Txt(R.string.font_ui_007, item.name), Txt(R.string.font_ui_008, item.name), Txt(R.string.font_ui_009, item.name))
    val load = produceState<FontLoad>(FontLoad.Busy, item.path) {
        value = withContext(Dispatchers.IO) { openFont(ctx, fs, item, task) }
    }.value
    when (load) {
        FontLoad.Busy -> LoadingPanel(item.name, task, !fs.isLocal)
        is FontLoad.Fail -> Box(Modifier.fillMaxSize()) {
            Text(load.message, color = Pv.text, modifier = Modifier.align(Alignment.Center).padding(32.dp))
        }
        is FontLoad.Ok -> FontView(load.font, item)
    }
}

@Composable
private fun FontView(font: FontData, item: FileItem) {
    val family = remember(font) { FontFamily(font.typeface) }
    var sample by remember { mutableStateOf("吾輩は猫である。名前はまだ無い。\nThe quick brown fox jumps over the lazy dog.\n0123456789 ABC abc あいうえお アイウエオ 漢字") }
    val info = font.info
    Column(Modifier.fillMaxSize().belowBars().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(info.fullName ?: info.family ?: item.name, color = Pv.text, fontSize = 20.sp, fontFamily = family)
        Text(listOfNotNull(info.familyJa?.takeIf { it != info.family }, info.style).joinToString(" / "), color = Pv.dim, fontSize = 13.sp)

        OutlinedTextField(
            value = sample, onValueChange = { sample = it }, label = { Text(Txt(R.string.font_ui_010)) },
            modifier = Modifier.fillMaxWidth(), textStyle = TextStyle(fontFamily = family, fontSize = 16.sp, color = Pv.text),
        )
        for (size in listOf(14, 20, 32, 48)) {
            Column(Modifier.fillMaxWidth().background(Pv.codeBg, RoundedCornerShape(8.dp)).padding(10.dp)) {
                Text("$size", color = Pv.comment, fontSize = 11.sp)
                Text(sample, color = Pv.text, fontFamily = family, fontSize = size.sp, lineHeight = (size * 1.4f).sp)
            }
        }
        Text(Txt(R.string.font_ui_011), color = Pv.accent, fontSize = 13.sp)
        Column(Modifier.fillMaxWidth().background(Pv.codeBg, RoundedCornerShape(8.dp)).padding(10.dp)) {
            for (line in listOf(
                "あいうえお かきくけこ さしすせそ たちつてと なにぬねの はひふへほ まみむめも やゆよ らりるれろ わをん",
                "アイウエオ カキクケコ サシスセソ タチツテト ナニヌネノ ハヒフヘホ マミムメモ ヤユヨ ラリルレロ ワヲン",
                "日本語 漢字 永遠 愛 鬱 薔薇 髙 﨑 𠮷 ①②③ ㈱ ㌔",
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
                "abcdefghijklmnopqrstuvwxyz",
                "0123456789 !\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~",
                "Ààéüñß Ωαβγ Жзи 한글",
            )) Text(line, color = Pv.text, fontFamily = family, fontSize = 18.sp, lineHeight = 26.sp)
        }

        Text(Txt(R.string.font_ui_012), color = Pv.accent, fontSize = 13.sp)
        val rows = listOfNotNull(
            info.family?.let { Txt(R.string.font_ui_013) to it }, info.style?.let { Txt(R.string.font_ui_014) to it }, info.fullName?.let { Txt(R.string.font_ui_015) to it },
            info.familyJa?.let { Txt(R.string.font_ui_016) to it }, info.version?.let { Txt(R.string.font_ui_017) to it },
            Txt(R.string.font_ui_018) to "%,d".format(info.glyphs),
            Txt(R.string.font_ui_019) to (if (info.weight > 0) "${info.weight}" else Txt(R.string.font_ui_020)),
            Txt(R.string.font_ui_021) to (if (info.cff) Txt(R.string.font_ui_022) else Txt(R.string.font_ui_023)) + (if (info.variable) Txt(R.string.font_ui_024) else ""),
            Txt(R.string.font_ui_025) to if (info.monospace) Txt(R.string.font_ui_026) else Txt(R.string.font_ui_027),
            Txt(R.string.font_ui_028) to item.name, Txt(R.string.font_ui_029) to FileOps.formatSize(item.size),
            info.designer?.let { Txt(R.string.font_ui_030) to it }, info.license?.let { Txt(R.string.font_ui_031) to it.take(200) }, info.copyright?.let { Txt(R.string.font_ui_032) to it.take(200) },
        ) + (if (info.collectionCount > 1) listOf(Txt(R.string.font_ui_033) to Txt.n(R.plurals.font_collection, info.collectionCount)) else emptyList())
        SelectionContainer {
            Column {
                for ((l, v) in rows) Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(l, color = Pv.dim, fontSize = 12.sp, modifier = Modifier.width(120.dp))
                    Text(v, color = Pv.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                }
            }
        }
        Text(Txt(R.string.font_ui_034), color = Pv.accent, fontSize = 13.sp)
        for ((label, ok) in info.coverage()) {
            Row {
                Text(if (ok) "✓ " else "× ", color = if (ok) Pv.ok else Pv.bad, fontSize = 13.sp)
                Text(label, color = if (ok) Pv.text else Pv.dim, fontSize = 13.sp)
            }
        }
    }
}
