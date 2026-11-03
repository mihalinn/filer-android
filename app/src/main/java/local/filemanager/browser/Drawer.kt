package local.filemanager

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun AppDrawer(vm: FmViewModel, close: () -> Unit, onPending: (Pending) -> Unit) {
    val pal = LocalPal.current
    var tab by remember { mutableIntStateOf(0) }
    ModalDrawerSheet(
        modifier = Modifier.width(304.dp),
        drawerShape = RectangleShape,
        drawerContainerColor = pal.background,
        drawerTonalElevation = 0.dp,
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FolderGlyph(kindColor(FileKind.FOLDER), Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Text("ファイラー", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = pal.text)
        }
        // 場所 / ブックマーク / 履歴 の切り替え(文字のつまみ)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp).clip(RoundedCornerShape(8.dp)).background(pal.tileBg).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            listOf("場所", "ブックマーク", "履歴").forEachIndexed { i, label ->
                val on = tab == i
                Box(
                    Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (on) pal.accent else Color.Transparent).clickable { tab = i },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label, fontSize = 14.sp, maxLines = 1,
                        color = if (on) pal.onAccent else pal.text,
                        fontWeight = if (on) FontWeight.Bold else null,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            when (tab) {
                0 -> LocationsTab(vm, close)
                1 -> PlacesTab(vm, vm.bookmarks, "ブックマークはまだありません。\nフォルダを長押しして、「その他」から追加できます。", close, removable = true)
                else -> {
                    if (vm.history.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                            TextButton(onClick = vm::clearHistory) { Text("履歴を消去") }
                        }
                    }
                    PlacesTab(vm, vm.history, "履歴はありません", close, removable = false)
                }
            }
        }
    }
}

@Composable
private fun DrawerRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    extra: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val pal = LocalPal.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = pal.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = pal.subText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            extra?.invoke()
        }
        trailing?.invoke()
    }
}

/** 淡い色の角丸の台に、濃い同系色の記号を置いた、場所のアイコン。 */
@Composable
internal fun Badge(icon: ImageVector, color: Color, size: Int = 34) {
    // 淡い色の台に、同じ色合いの濃い記号(ダークでは、台を暗くして、記号は元の色)
    val dark = LocalPal.current.dark
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.3f).dp)).background(color.copy(alpha = if (dark) 0.26f else 0.34f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (dark) color else color.ink(0.5f), modifier = Modifier.size((size * 0.58f).dp))
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(text, Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LocalPal.current.accent)
}

@Composable
private fun LocationsTab(vm: FmViewModel, close: () -> Unit) {
    val pal = LocalPal.current
    DrawerRow(icon = { Badge(Icons.Default.Home, Color(0xFF8E9CF0)) }, title = "ホーム") {
        close(); vm.goHome()
    }
    vm.volumes.forEach { v ->
        val used = (v.total - v.free).coerceAtLeast(0)
        val frac = if (v.total > 0) used.toFloat() / v.total else 0f
        DrawerRow(
            icon = { Badge(Icons.Default.Storage, Color(0xFF9AA3C7)) },
            title = v.label,
            extra = {
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(pal.divider)) {
                    Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(pal.accent))
                }
                Spacer(Modifier.height(3.dp))
                Text("使用 ${FileOps.formatSize(used)} / ${FileOps.formatSize(v.total)}", fontSize = 11.sp, color = pal.subText, maxLines = 1)
            },
        ) { close(); vm.openLocal(v.dir.path) }
    }
    DrawerRow(icon = { Badge(Icons.Default.PhoneAndroid, Color(0xFF9AA3C7)) }, title = "システム") { close(); vm.openLocal("/") }
    DrawerRow(
        icon = { Badge(Icons.Default.Delete, Color(0xFFB3B9D6)) },
        title = "ごみ箱",
        trailing = { Text(FileOps.formatSize(vm.trashSize), fontSize = 13.sp, color = pal.subText, modifier = Modifier.padding(end = 8.dp)) },
    ) { close(); vm.openTrash() }
    DrawerRow(
        icon = { Badge(Icons.Default.Settings, Color(0xFFB3B9D6)) }, title = "設定",
        trailing = if (vm.updater.status is UpdateStatus.Available || vm.updater.status is UpdateStatus.Ready) {
            { Text("更新あり", fontSize = 13.sp, color = pal.accent, modifier = Modifier.padding(end = 8.dp)) }
        } else null,
    ) { close(); vm.openSettings() }
    if (vm.pinned.isNotEmpty()) SectionHeading("固定した場所")
    // 固定した場所(ピンのアイコン)が上、そのあとに最近開いた場所(⋮ から固定・履歴から削除)
    vm.pinned.forEach { p ->
        DrawerRow(
            icon = { PlaceIcon(p) },
            title = vm.placeLabel(p),
            subtitle = vm.placeSubtitle(p),
            trailing = {
                IconButton(onClick = { vm.unpin(p) }) {
                    Icon(Icons.Default.PushPin, "固定を外す", tint = pal.accent, modifier = Modifier.size(22.dp))
                }
            },
        ) { close(); vm.openShortcut(p) }
    }
    if (vm.opened.isNotEmpty()) SectionHeading("最近開いた場所")
    vm.opened.forEach { p ->
        var menu by remember { mutableStateOf(false) }
        DrawerRow(
            icon = { PlaceIcon(p) },
            title = vm.placeLabel(p),
            subtitle = vm.placeSubtitle(p),
            trailing = {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "メニュー", tint = pal.text) }
                    ListMenu(
                        menu, pal.dark, { menu = false },
                        listOf(
                            VMenuItem.Action(Icons.Default.PushPin, "固定する") { vm.pin(p) },
                            VMenuItem.Action(Icons.Default.Delete, "履歴から削除") { vm.removeOpened(p) },
                        ),
                    )
                }
            },
        ) { close(); vm.openShortcut(p) }
    }
}

/** 場所の種類ごとのアイコン(分類は色つきの記号、フォルダや NAS は琥珀色のフォルダ)。 */
@Composable
internal fun PlaceIcon(p: Place) {
    val cat = if (p.fsId == "cat") runCatching { Category.valueOf(p.path) }.getOrNull() else null
    if (cat != null) {
        val (icon, tint) = categoryStyle(cat)
        Badge(icon, tint)
    } else {
        Badge(Icons.Default.Folder, kindColor(FileKind.FOLDER))
    }
}

internal fun categoryStyle(c: Category): Pair<ImageVector, Color> = when (c) {
    Category.IMAGE -> Icons.Default.Image to kindColor(FileKind.IMAGE)
    Category.VIDEO -> Icons.Default.Movie to kindColor(FileKind.VIDEO)
    Category.AUDIO -> Icons.Default.MusicNote to kindColor(FileKind.AUDIO)
    Category.DOCUMENT -> Icons.Default.Description to kindColor(FileKind.DOCUMENT)
    Category.APK -> Icons.Default.Android to kindColor(FileKind.APK)
    Category.RECENT -> Icons.Default.Schedule to Color(0xFF9AA3CC)
}

@Composable
private fun PlacesTab(vm: FmViewModel, places: List<Place>, empty: String, close: () -> Unit, removable: Boolean) {
    val pal = LocalPal.current
    if (places.isEmpty()) {
        Text(empty, Modifier.padding(24.dp), color = pal.subText, fontSize = 14.sp)
    }
    places.forEach { p ->
        var menu by remember { mutableStateOf(false) }
        DrawerRow(
            icon = { Badge(Icons.Default.Folder, kindColor(FileKind.FOLDER)) },
            title = vm.placeLabel(p),
            subtitle = p.path,
            trailing = if (removable) {
                { IconButton(onClick = { vm.removeBookmark(p) }) { Icon(Icons.Default.Close, "削除", tint = pal.subText) } }
            } else {
                {
                    // 履歴の行: 固定する / 履歴から削除
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "メニュー", tint = pal.text) }
                        ListMenu(
                            menu, pal.dark, { menu = false },
                            listOf(
                                VMenuItem.Action(Icons.Default.PushPin, "固定する") { vm.pin(p) },
                                VMenuItem.Action(Icons.Default.Delete, "履歴から削除") { vm.removeHistory(p) },
                            ),
                        )
                    }
                }
            },
        ) { close(); vm.openDir(p.fsId, p.path) }
    }
}
