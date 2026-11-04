package local.filemanager

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.ui.text.font.FontWeight
import java.io.File

private class Tile(
    val id: String,
    val label: String,
    val sub: String?,
    val framed: Boolean,
    val icon: @Composable () -> Unit,
    val removable: Boolean = false,
    val onClick: () -> Unit,
)

/** ホームの見出しごとの分け方。タイルの id から決める(並びの保存は今までと同じで、見出しの中での順になる)。 */
private enum class HomeGroup { CATEGORY, NAS, FOLDER, TOOL, NONE }

private fun groupOf(id: String): HomeGroup = when {
    id.startsWith("vol:") || id.startsWith("cat:") || id == "download" -> HomeGroup.CATEGORY
    id.startsWith("smb:") -> HomeGroup.NAS
    id == "system" || id.startsWith("sc:") -> HomeGroup.FOLDER
    id == "trash" || id == "analyze" -> HomeGroup.TOOL
    else -> HomeGroup.NONE // "network" は NAS の見出しの「管理」になる
}

@Composable
internal fun HomeScreen(vm: FmViewModel, onPending: (Pending) -> Unit) {
    val sums = vm.categorySummary
    fun catSub(c: Category): String? = sums[c]?.let { (n, bytes) -> "${FileOps.formatSize(bytes)} ($n)" }

    val all = buildList {
        vm.volumes.forEach { v ->
            add(Tile("vol:${v.dir.path}", v.label, // 狭い列(4〜5)では全体の容量を省いて、文字が欠けないようにする
                if (vm.homeCols >= 4) "空き ${FileOps.formatSize(v.free.coerceAtLeast(0))}" else "空き ${FileOps.formatSize(v.free.coerceAtLeast(0))} / ${FileOps.formatSize(v.total)}", true,
                { Badge(Icons.Default.Storage, Color(0xFF9AA3C7), 46) }) { vm.openLocal(v.dir.path) })
        }
        add(Tile("system", "システム", null, false, { Badge(Icons.Default.PhoneAndroid, Color(0xFF9AA3C7), 54) }) { vm.openLocal("/") })
        val download = vm.volumes.firstOrNull()?.dir?.let { File(it, "Download") }
        if (download != null && download.isDirectory) {
            add(Tile("download", "ダウンロード", null, true, { Badge(Icons.Default.Download, Color(0xFF7FB5E6), 46) }) { vm.openShortcut(Place("local", download.path)) })
        }
        for ((cat, label) in listOf(
            Category.IMAGE to "画像", Category.AUDIO to "オーディオ", Category.VIDEO to "動画",
            Category.DOCUMENT to "ドキュメント", Category.APK to "アプリ", Category.RECENT to "新着ファイル",
        )) {
            val (icon, tint) = categoryStyle(cat)
            add(Tile("cat:${cat.name}", label, catSub(cat), true, { Badge(icon, tint, 46) }) { vm.openShortcut(Place("cat", cat.name)) })
        }
        add(Tile("trash", "ごみ箱", if (vm.trashSize == 0L) "空" else FileOps.formatSize(vm.trashSize), true,
            { Badge(Icons.Default.Delete, Color(0xFFB3B9D6), 46) }) { vm.openTrash() })
        add(Tile("network", "ネットワーク", "(${vm.remotes.size})", true, { Badge(Icons.Default.Computer, Color(0xFF8E9AD6), 46) }) { vm.openNetwork() })
        add(Tile("analyze", "容量の分析", null, true, { Badge(Icons.Default.PieChart, Color(0xFFFFB366), 46) }) { vm.openAnalyze() })
        vm.remotes.forEach { r ->
            add(Tile("smb:${r.id}", r.name, r.host, false, { FolderGlyph(kindColor(FileKind.FOLDER), Modifier.size(58.dp)) }) { vm.openShortcut(Place("smb:${r.id}", "")) })
        }
        vm.homeShortcuts.forEach { s ->
            val where = if (s.fsId == "local") "端末" else vm.remotes.firstOrNull { "smb:${it.id}" == s.fsId }?.name
            add(Tile(s.id, s.name, where, false, { FolderGlyph(kindColor(FileKind.FOLDER), Modifier.size(58.dp)) }, removable = true) { vm.openShortcut(Place(s.fsId, s.path)) })
        }
    }
    val byId = all.associateBy { it.id }
    val order = HomeLayout.arrange(all.map { it.id }, vm.homeOrder)
    val ordered = order.mapNotNull { byId[it] }

    BackHandler(enabled = vm.homeEditing) { vm.homeEditing = false }
    if (vm.homeEditing) {
        HomeEditor(vm, ordered)
        return
    }
    val shown = ordered.filter { it.id !in vm.homeHidden }
    val frame = HomeLayout.frameDp(vm.homeCols)
    val edit = { vm.homeEditing = true }
    fun part(g: HomeGroup) = shown.filter { groupOf(it.id) == g }
    val categories = part(HomeGroup.CATEGORY)
    val nas = part(HomeGroup.NAS)
    val folders = part(HomeGroup.FOLDER)
    val tools = part(HomeGroup.TOOL)
    val manage = byId["network"]?.takeIf { it.id !in vm.homeHidden }

    // 引っ張って、ストレージの空きや件数を取り直す
    PullRefresh(onRefresh = { vm.refreshVolumesInBackground(); vm.refreshHome() }, isBusy = { false }, modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(vm.homeCols),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // アプリの更新があるとき(見つかった・ダウンロード中・ダウンロード済み)は、設定まで行かなくても分かるよう、一番上に出す
            if (vm.updater.showBanner && vm.updater.status.let { it is UpdateStatus.Available || it is UpdateStatus.Downloading || it is UpdateStatus.Ready }) {
                item(key = "update", span = { GridItemSpan(maxLineSpan) }) { UpdateBanner(vm.updater) }
            }
            if (categories.isNotEmpty()) {
                item(key = "h:category", span = { GridItemSpan(maxLineSpan) }) { HomeSectionTitle("カテゴリ") }
                items(categories.size, key = { categories[it].id }) { CardTile(categories[it], frame, onLongPress = edit) }
            }
            item(key = "h:nas", span = { GridItemSpan(maxLineSpan) }) {
                HomeSectionTitle("NAS") {
                    if (manage != null) TextButton(onClick = manage.onClick) { Text("管理", fontSize = 13.sp) }
                    IconButton(onClick = { onPending(Pending.EditRemote(null)) }) { Icon(Icons.Default.Add, "NASを追加", tint = LocalPal.current.accent) }
                }
            }
            if (vm.remotes.isEmpty()) {
                item(key = "nas:empty", span = { GridItemSpan(maxLineSpan) }) {
                    WideCard(onClick = { onPending(Pending.EditRemote(null)) }, onLongPress = edit) {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Badge(Icons.Default.Computer, Color(0xFF8E9AD6), 36) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("NASを追加", fontSize = 16.sp, color = LocalPal.current.text)
                            Text("SMB共有を、端末のフォルダと同じように操作できます", fontSize = 12.sp, color = LocalPal.current.subText)
                        }
                    }
                }
            }
            items(nas.size, key = { nas[it].id }, span = { GridItemSpan(maxLineSpan) }) { NasRow(nas[it], edit) }
            if (folders.isNotEmpty()) {
                item(key = "h:folder", span = { GridItemSpan(maxLineSpan) }) { HomeSectionTitle("フォルダ") }
                items(folders.size, key = { folders[it].id }) { CardTile(folders[it], frame, onLongPress = edit) }
            }
            if (tools.isNotEmpty()) {
                item(key = "h:tool", span = { GridItemSpan(maxLineSpan) }) { HomeSectionTitle("ツール") }
                items(tools.size, key = { tools[it].id }) { CardTile(tools[it], frame, onLongPress = edit) }
            }
        }
    }
}

/** ホームの一番上に出す、アプリの更新のお知らせ。「後で更新する」を押しても、更新するまで残る。押すと更新の窓(ダウンロード済みならインストール)が開く。 */
@Composable
private fun UpdateBanner(u: UpdateManager) {
    val pal = LocalPal.current
    val shape = RoundedCornerShape(10.dp)
    val st = u.status
    val (title, sub) = when (st) {
        is UpdateStatus.Available -> "新しいバージョン ${st.info.version} があります" to
            "いまのバージョン ${u.currentVersion}" + (if (st.info.apkSize > 0) "・約 ${FileOps.formatSize(st.info.apkSize)}" else "") + "。押すと更新できます"
        is UpdateStatus.Downloading -> "新しいバージョン ${st.info.version} をダウンロードしています" to
            FileOps.formatSize(st.done) + (if (st.total > 0) " / ${FileOps.formatSize(st.total)}" else "")
        is UpdateStatus.Ready -> "新しいバージョン ${st.info.version} をインストールできます" to "ダウンロードは終わっています。押すとインストールの画面が開きます"
        else -> return
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp).clip(shape).background(pal.accent.copy(alpha = 0.12f)).border(1.dp, pal.accent, shape)
            .clickable { if (st is UpdateStatus.Ready) u.install() else u.showPrompt() }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.SystemUpdate, null, tint = pal.accent, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = pal.text)
            Text(sub, fontSize = 12.sp, color = pal.subText)
        }
        Icon(Icons.Default.ChevronRight, null, tint = pal.accent)
    }
}

@Composable
private fun HomeSectionTitle(text: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, top = 4.dp).height(36.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text, color = LocalPal.current.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

/** カードの外枠。白い地に細い線。押すと onClick、長押しでホームの編集。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun WideCard(onClick: () -> Unit, onLongPress: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val pal = LocalPal.current
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(pal.surface).border(1.dp, pal.divider, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** タイルの絵を、dp 四方の枠に収める(絵は 66dp 基準で作ってあるので縮尺する)。 */
@Composable
private fun TileIcon(t: Tile, dp: Int) {
    Box(Modifier.size(dp.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.requiredSize(66.dp).scale(dp / 66f), contentAlignment = Alignment.Center) { t.icon() }
    }
}

/** NAS: フォルダの絵、名前、場所(ホスト名)、右に矢印。 */
@Composable
private fun NasRow(t: Tile, onLongPress: () -> Unit) {
    val pal = LocalPal.current
    WideCard(onClick = t.onClick, onLongPress = onLongPress) {
        TileIcon(t, 38)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.label, fontSize = 16.sp, color = pal.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.sub ?: "", fontSize = 12.sp, color = pal.subText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.ChevronRight, null, tint = pal.subText)
    }
}

/** 格子に並べるカード: 絵、名前、補足(件数や容量)。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CardTile(t: Tile, frameDp: Int, onLongPress: () -> Unit) {
    val pal = LocalPal.current
    val k = frameDp / 66f
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(pal.surface).border(1.dp, pal.divider, shape)
            .combinedClickable(onClick = t.onClick, onLongClick = onLongPress).padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TileIcon(t, (frameDp * 0.7f).toInt())
        Spacer(Modifier.height(2.dp))
        Text(t.label, fontSize = (13 + (k - 1f) * 3).coerceIn(11f, 16f).sp, color = pal.text, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(t.sub ?: " ", fontSize = if (frameDp < 60) 10.sp else 11.sp, color = pal.subText, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** ホームの編集: 列の数・並べ替え・表示/非表示・フォルダの追加。 */
@Composable
private fun HomeEditor(vm: FmViewModel, tiles: List<Tile>) {
    val pal = LocalPal.current
    var addDialog by remember { mutableStateOf(false) }
    val ids = tiles.map { it.id }
    LazyColumn(Modifier.fillMaxSize().background(pal.surface), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ホームの編集", fontSize = 20.sp, color = pal.text)
                    Text("▲▼で並べ替え、スイッチで表示・非表示を切り替えます", fontSize = 12.sp, color = pal.subText)
                }
                TextButton(onClick = { vm.homeEditing = false }) { Text("完了", fontSize = 16.sp) }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("1行に並べる数", fontSize = 13.sp, color = pal.subText)
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(8.dp)).background(pal.tileBg).padding(3.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp),
                ) {
                    for (n in HomeLayout.MIN_COLS..HomeLayout.MAX_COLS) {
                        val on = vm.homeCols == n
                        Box(
                            Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(6.dp))
                                .background(if (on) pal.accent else Color.Transparent).clickable { vm.changeHomeCols(n) },
                            contentAlignment = Alignment.Center,
                        ) { Text("$n", fontSize = 16.sp, color = if (on) pal.onAccent else pal.text, fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.Bold else null) }
                    }
                }
            }
            HorizontalDivider(color = pal.divider)
        }
        items(tiles.size, key = { tiles[it].id }) { i ->
            val t = tiles[i]
            val hidden = t.id in vm.homeHidden
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.requiredSize(66.dp).scale(0.62f).let { if (hidden) it.alpha(0.35f) else it }, contentAlignment = Alignment.Center) { t.icon() }
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.label, fontSize = 16.sp, color = if (hidden) pal.subText else pal.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (hidden) Text("非表示", fontSize = 11.sp, color = pal.subText)
                }
                IconButton(onClick = { vm.moveHomeTile(ids, i, -1) }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, "上へ") }
                IconButton(onClick = { vm.moveHomeTile(ids, i, 1) }, enabled = i < tiles.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, "下へ") }
                if (t.removable) {
                    IconButton(onClick = { vm.removeHomeShortcut(t.id) }) { Icon(Icons.Default.Close, "ホームから外す") }
                } else {
                    Switch(checked = !hidden, onCheckedChange = { vm.setHomeTileHidden(t.id, !it) }, modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
            HorizontalDivider(color = pal.divider)
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
                TextButton(onClick = { addDialog = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("ブックマークからフォルダを追加") }
            }
            Text(
                "フォルダを選んで「その他」→「ホームに追加」からも追加できます",
                fontSize = 12.sp, color = pal.subText, modifier = Modifier.padding(horizontal = 16.dp),
            )
            TextButton(onClick = vm::resetHome, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) { Text("並びと表示を初期に戻す") }
        }
    }
    if (addDialog) {
        val have = vm.homeShortcuts.map { it.id }.toSet()
        val cand = vm.bookmarks.filter { it.fsId != "cat" && HomeShortcut(it.fsId, it.path, "").id !in have }
        AlertDialog(
            onDismissRequest = { addDialog = false },
            title = { Text("追加するフォルダ") },
            text = {
                if (cand.isEmpty()) Text("追加できるブックマークがありません。フォルダを選んで、「その他」から追加してください。")
                else LazyColumn {
                    items(cand.size) { i ->
                        val p = cand[i]
                        val name = p.path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty {
                            vm.remotes.firstOrNull { "smb:${it.id}" == p.fsId }?.name ?: "/"
                        }
                        Column(Modifier.fillMaxWidth().clickable { vm.addHomeShortcut(p.fsId, p.path, name); addDialog = false }.padding(vertical = 8.dp)) {
                            Text(name, fontSize = 16.sp)
                            Text(p.path.ifEmpty { "(共有の最上位)" }, fontSize = 12.sp, color = pal.subText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { addDialog = false }) { Text("閉じる") } },
        )
    }
}
/** 保存した NAS の一覧と、追加・編集・削除。 */
@Composable
internal fun NetworkScreen(vm: FmViewModel, onPending: (Pending) -> Unit) {
    val pal = LocalPal.current
    if (vm.remotes.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.Computer, null, tint = pal.subText, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(12.dp))
            Text("NAS(SMB共有)を追加すると、端末のフォルダと同じようにファイルを操作できます", color = pal.subText, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { onPending(Pending.EditRemote(null)) }) { Text("NASを追加") }
        }
    } else LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(vm.remotes, key = { it.id }) { r ->
            var menu by remember { mutableStateOf(false) }
            WideCard(onClick = { vm.openShortcut(Place("smb:${r.id}", "")) }, onLongPress = { menu = true }) {
                FolderGlyph(kindColor(FileKind.FOLDER), Modifier.size(38.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.name, fontSize = 16.sp, color = pal.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("\\\\${r.host}\\${r.share}", fontSize = 12.sp, color = pal.subText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.MoreVert, "メニュー", tint = pal.subText) }
                    ListMenu(
                        menu, pal.dark, { menu = false },
                        listOf(
                            VMenuItem.Action(Icons.Default.Edit, "編集") { onPending(Pending.EditRemote(r)) },
                            VMenuItem.Action(Icons.Default.Delete, "削除") { onPending(Pending.DeleteRemote(r)) },
                        ),
                    )
                }
            }
        }
    }
}
