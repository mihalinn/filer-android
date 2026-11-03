package local.filemanager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface PassLoad {
    data object Busy : PassLoad
    data class Ok(val pass: Pass, val images: Map<String, Bitmap>) : PassLoad
    data class Fail(val message: String) : PassLoad
}

private fun loadPass(fs: Fs, item: FileItem): PassLoad {
    val src = try {
        openSource(fs, item.path)
    } catch (e: Exception) {
        null
    } ?: return PassLoad.Fail("このファイルを開けません")
    return try {
        val reader = ZipReader(src)
        val byName = reader.entries.associateBy { ZipTree.clean(it.name) }
        val read: (String) -> ByteArray? = { name ->
            byName[name]?.takeIf { it.size <= 8L * 1024 * 1024 }?.let { e -> runCatching { reader.open(e).use { it.readBytes() } }.getOrNull() }
        }
        val pass = Pkpass.parse(byName.keys, read)
        val images = HashMap<String, Bitmap>()
        for ((kind, path) in pass.images) {
            if (kind == "background") continue // 背景は大きいので、使わない(カードの色で見せる)
            val bytes = read(path) ?: continue
            val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let { images[kind] = it }
        }
        PassLoad.Ok(pass, images)
    } catch (e: OpException) {
        PassLoad.Fail(e.message ?: "読み込めません")
    } catch (e: Exception) {
        PassLoad.Fail("パスとして読めません(${e.message ?: e.javaClass.simpleName})")
    } finally {
        src.close()
    }
}

/** Apple Wallet のパス(.pkpass)を、カードの形で表示する。バーコードの絵は描かず、読み取り用の文字を見せる。 */
@Composable
internal fun PkpassPane(fs: Fs, item: FileItem) {
    val load = produceState<PassLoad>(PassLoad.Busy, item.path) {
        value = withContext(Dispatchers.IO) { loadPass(fs, item) }
    }.value
    when (load) {
        PassLoad.Busy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Pv.text) }
        is PassLoad.Fail -> Box(Modifier.fillMaxSize()) { Text(load.message, color = Pv.text, modifier = Modifier.align(Alignment.Center).padding(32.dp)) }
        is PassLoad.Ok -> PassView(load.pass, load.images)
    }
}

@Composable
private fun PassView(p: Pass, images: Map<String, Bitmap>) {
    val bg = p.bg?.let { Color(it) } ?: Color(0xFFFFFFFF)
    val fg = p.fg?.let { Color(it) } ?: Color(0xFF000000)
    val labelColor = p.labelColor?.let { Color(it) } ?: fg.copy(alpha = 0.7f)
    LazyColumn(Modifier.fillMaxSize().belowBars(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp)) {
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg).padding(16.dp)) {
                // 上: ロゴ・ロゴの文字・見出しの項目
                Row(verticalAlignment = Alignment.CenterVertically) {
                    (images["logo"] ?: images["icon"])?.let { Image(it.asImageBitmap(), null, Modifier.height(36.dp), contentScale = ContentScale.Fit) }
                    if (p.logoText != null) Text(p.logoText, color = fg, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp).weight(1f))
                    else Text(p.organization ?: "", color = fg, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp).weight(1f), maxLines = 1)
                    for (f in p.header) FieldBlock(f, fg, labelColor, big = false, align = TextAlign.End, modifier = Modifier.padding(start = 12.dp))
                }
                // 帯の画像
                images["strip"]?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().padding(top = 10.dp), contentScale = ContentScale.FillWidth) }
                // 主な項目
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        for (f in p.primary) FieldBlock(f, fg, labelColor, big = true, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    images["thumbnail"]?.let { Image(it.asImageBitmap(), null, Modifier.height(72.dp), contentScale = ContentScale.Fit) }
                }
                FieldRow(p.secondary, fg, labelColor)
                FieldRow(p.auxiliary, fg, labelColor)
                // バーコード
                p.barcode?.let { b ->
                    Column(
                        Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(10.dp)).background(Color.White).padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("${b.formatName}(絵は出さず、読み取り用の文字を表示)", color = Color(0xFF666666), fontSize = 11.sp)
                        SelectionContainer {
                            Text(b.message, color = Color.Black, fontSize = 13.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                        }
                        if (!b.altText.isNullOrEmpty()) Text(b.altText, color = Color(0xFF444444), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(top = 14.dp)) {
                Text("${p.styleName}${if (p.voided) "(無効)" else ""}", color = if (p.voided) Pv.danger else Pv.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                if (!p.description.isNullOrEmpty()) Info("説明", p.description)
                if (!p.organization.isNullOrEmpty()) Info("発行元", p.organization)
                if (p.transit != null) Info("乗り物", p.transit.removePrefix("PKTransitType"))
                if (!p.serial.isNullOrEmpty()) Info("シリアル番号", p.serial)
                if (p.relevantDate != null) Info("表示する日時", p.relevantDate)
                if (p.expiration != null) Info("有効期限", p.expiration)
                if (p.locations.isNotEmpty()) Info("場所", p.locations.joinToString("\n"))
            }
        }
        if (p.back.isNotEmpty()) {
            item { Text("裏面の項目", color = Pv.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp)) }
            items(p.back.size) { i ->
                val f = p.back[i]
                Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    if (!f.label.isNullOrEmpty()) Text(f.label, color = Pv.label, fontSize = 12.sp)
                    SelectionContainer { Text(f.value, color = Pv.text, fontSize = 14.sp) }
                }
            }
        }
    }
}

@Composable
private fun FieldRow(fields: List<PassField>, fg: Color, labelColor: Color) {
    if (fields.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (f in fields) FieldBlock(f, fg, labelColor, big = false, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun FieldBlock(f: PassField, fg: Color, labelColor: Color, big: Boolean, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start) {
    Column(modifier) {
        if (!f.label.isNullOrEmpty()) Text(f.label, color = labelColor, fontSize = 11.sp, textAlign = align)
        Text(f.value, color = fg, fontSize = if (big) 24.sp else 15.sp, fontWeight = if (big) FontWeight.Bold else FontWeight.Normal, textAlign = align)
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = Pv.label, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp).width(96.dp), maxLines = 1)
        SelectionContainer { Text(value, color = Pv.text, fontSize = 14.sp, modifier = Modifier.weight(1f)) }
    }
}
