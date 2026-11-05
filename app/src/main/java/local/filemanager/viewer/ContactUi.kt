package local.filemanager

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface VcardLoad {
    data object Busy : VcardLoad
    data class Ok(val cards: List<Contact>) : VcardLoad
    data class Fail(val message: String) : VcardLoad
}

private const val VCARD_MAX_BYTES = 16 * 1024 * 1024

private fun loadVcard(fs: Fs, item: FileItem): VcardLoad = try {
    if (item.size > VCARD_MAX_BYTES) {
        VcardLoad.Fail(Txt(R.string.contact_ui_001, VCARD_MAX_BYTES / 1024 / 1024))
    } else {
        val bytes = fs.open(item.path).use { it.readBytes() }
        val cards = Vcard.parse(TextDecode.decode(bytes).text)
        if (cards.isEmpty()) VcardLoad.Fail(Txt(R.string.contact_ui_002)) else VcardLoad.Ok(cards)
    }
} catch (e: Exception) {
    VcardLoad.Fail(e.message ?: Txt(R.string.contact_ui_003))
} catch (e: OutOfMemoryError) {
    VcardLoad.Fail(Txt(R.string.contact_ui_004))
}

/** 連絡先(.vcf)の表示。電話・メール・Web は、タップで電話・メール・ブラウザを開く。 */
@Composable
internal fun VcardPane(fs: Fs, item: FileItem, bars: Boolean, onTap: () -> Unit) {
    val load = produceState<VcardLoad>(VcardLoad.Busy, item.path) {
        value = withContext(Dispatchers.IO) { loadVcard(fs, item) }
    }.value
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }) {
        when (load) {
            VcardLoad.Busy -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Pv.text)
            is VcardLoad.Fail -> Text(load.message, color = Pv.text, modifier = Modifier.align(Alignment.Center).padding(32.dp))
            is VcardLoad.Ok -> {
                LazyColumn(
                    Modifier.fillMaxSize().belowBars(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(load.cards.size) { i -> ContactCard(load.cards[i]) }
                }
                if (bars) {
                    Text(
                        Txt.n(R.plurals.detail_count, load.cards.size),
                        color = Color(0xFFCCCCCC), fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ContactCard(card: Contact) {
    val uri = LocalUriHandler.current
    val photo = remember(card) { card.photo?.let { ImageDecode.decodeBytes(it, 512) } }
    Column(Modifier.fillMaxWidth().background(Pv.panel, RoundedCornerShape(12.dp)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (photo != null) {
                Image(photo.asImageBitmap(), null, Modifier.size(72.dp).clip(CircleShape), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.size(72.dp).clip(CircleShape).background(Pv.searchField), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Person, null, tint = Pv.dim, modifier = Modifier.size(42.dp))
                }
            }
            Column(Modifier.padding(start = 14.dp)) {
                Text(card.name, color = Pv.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (card.org.isNotEmpty()) Text(card.org, color = Pv.dim, fontSize = 14.sp)
            }
        }
        for (f in card.fields) {
            val link = when (f.kind) {
                VField.Kind.PHONE -> "tel:" + f.value.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
                VField.Kind.EMAIL -> "mailto:" + f.value
                VField.Kind.URL -> if (f.value.startsWith("http", ignoreCase = true)) f.value else "https://" + f.value
                VField.Kind.TEXT -> null
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp)
                    .then(if (link != null) Modifier.clickable { runCatching { uri.openUri(link) } } else Modifier),
            ) {
                Text(f.label, color = Pv.label, fontSize = 12.sp, modifier = Modifier.width(92.dp).padding(top = 2.dp))
                Text(f.value, color = if (link != null) Pv.link else Pv.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}
