package local.filemanager

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 細かい設定の画面。よく変える設定(並べ替え・隠しファイル・テーマなど)は、一覧の右上の「表示設定」にあり、
 * こちらにはそれ以外と、このアプリについて・ライセンスを置く。
 */
@Composable
internal fun SettingsScreen(vm: FmViewModel, s: Screen.Settings) {
    when (s.page) {
        SettingsPage.LICENSES -> LicensesPage()
        SettingsPage.FORMATS -> FormatsPage()
        else -> SettingsMain(vm)
    }
}

@Composable
private fun SettingsMain(vm: FmViewModel) {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
    // 異常終了の記録を消したら、行を隠すために数え直す
    var crashStamp by remember { mutableIntStateOf(0) }
    val hasCrash = remember(crashStamp) { CrashLog.read(ctx) != null || CrashLog.abnormalExits(ctx).isNotEmpty() }
    var showCrash by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(pal.background).verticalScroll(rememberScrollState())) {
        // 一覧の右上の「表示設定」と同じ項目。ここからも変えられる
        Group("表示") {
            SegmentRow("並べ替え", SortKey.entries.map { it.label }, SortKey.entries.indexOf(vm.sortKey)) { vm.setSort(SortKey.entries[it], vm.sortAsc) }
            HorizontalDivider(color = pal.divider)
            SegmentRow("順番", listOf("昇順", "降順"), if (vm.sortAsc) 0 else 1) { vm.setSort(vm.sortKey, it == 0) }
            HorizontalDivider(color = pal.divider)
            SwitchRow("隠しファイルを表示", vm.showHidden) { vm.toggleHidden() }
            HorizontalDivider(color = pal.divider)
            SwitchRow("NASのサムネイルを表示", vm.nasThumbs) { vm.toggleNasThumbs() }
            HorizontalDivider(color = pal.divider)
            val themes = listOf("light", "dark", "system")
            SegmentRow("アプリのテーマ", listOf("ライト", "ダーク", "端末に合わせる"), themes.indexOf(vm.themeMode).coerceAtLeast(0)) { vm.changeTheme(themes[it]) }
        }
        Group("ビューア(ファイルを開いた画面)") {
            val viewerThemes = listOf("dark", "light", "app")
            SegmentRow("配色(画像・動画は黒のまま)", listOf("ダーク", "ライト", "アプリと同じ"), viewerThemes.indexOf(vm.viewerTheme).coerceAtLeast(0)) { vm.changeViewerTheme(viewerThemes[it]) }
            HorizontalDivider(color = pal.divider)
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("文字の大きさ", fontSize = 16.sp, color = pal.text)
                    Text("文書・電子書籍を開いたときの大きさ", fontSize = 12.sp, color = pal.subText)
                }
                TextButton(onClick = { vm.changeViewerFontSp(vm.viewerFontSp - 1) }, enabled = vm.viewerFontSp > VIEWER_FONT_MIN) { Text("−", fontSize = 20.sp) }
                Text("${vm.viewerFontSp}", fontSize = 16.sp, color = pal.text)
                TextButton(onClick = { vm.changeViewerFontSp(vm.viewerFontSp + 1) }, enabled = vm.viewerFontSp < VIEWER_FONT_MAX) { Text("+", fontSize = 20.sp) }
            }
            HorizontalDivider(color = pal.divider)
            SegmentRow("マンガ(.cbz など)をめくる向き", listOf("右から左", "左から右"), if (vm.comicRtl) 0 else 1) { if ((it == 0) != vm.comicRtl) vm.toggleComicRtl() }
        }
        Group("ホーム画面") {
            LinkRow("ホーム画面を編集", "並び替え・表示/非表示・1行の数・追加") { vm.goHome(); vm.homeEditing = true }
        }
        Group("場所") {
            LinkRow("NAS の接続先", if (vm.remotes.isEmpty()) "まだありません" else "${vm.remotes.size} 件") { vm.openNetwork() }
            LinkRow("ごみ箱", FileOps.formatSize(vm.trashSize)) { vm.openTrash() }
        }
        Group("このアプリについて") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("ファイラー $version", style = MaterialTheme.typography.titleMedium, color = pal.text)
                Text("広告のないファイルマネージャーです", fontSize = 13.sp, color = pal.subText)
                Text(Credits.COPYRIGHT, fontSize = 13.sp, color = pal.subText)
                if (Credits.SOURCE_URL.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text("ソースコード: ${Credits.SOURCE_URL}", fontSize = 13.sp, color = pal.text)
                }
            }
            HorizontalDivider(color = pal.divider)
            LinkRow("対応しているファイル形式", "アプリの中で開ける形式の一覧") { vm.openSettings(SettingsPage.FORMATS) }
            LinkRow("ライセンス", "使っているもの ${Credits.list.size} 件") { vm.openSettings(SettingsPage.LICENSES) }
            if (hasCrash) LinkRow("前回の異常終了の記録", "原因の記録を確認・コピーできます", warn = true) { showCrash = true }
        }
        Spacer(Modifier.height(24.dp))
    }
    if (showCrash) CrashLogDialog(onCleared = { crashStamp++ }) { showCrash = false }
}

@Composable
private fun LicensesPage() {
    val pal = LocalPal.current
    var shown by remember { mutableStateOf<Credit?>(null) }
    val open = shown
    androidx.activity.compose.BackHandler(enabled = open != null) { shown = null }
    if (open != null) {
        LicenseDetail(open)
        return
    }
    val shape = RoundedCornerShape(10.dp)
    LazyColumn(
        Modifier.fillMaxSize().background(pal.background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Text(
                "このアプリが使っているものと、そのライセンスです。押すと全文を表示します。",
                Modifier.padding(start = 6.dp, top = 2.dp, bottom = 4.dp), fontSize = 13.sp, color = pal.subText,
            )
        }
        items(Credits.list) { c ->
            Row(
                Modifier.fillMaxWidth().clip(shape).background(pal.surface).border(1.dp, pal.divider, shape)
                    .clickable { shown = c }.padding(start = 12.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, fontSize = 15.sp, color = pal.text)
                    Spacer(Modifier.height(4.dp))
                    LicenseTag(c.license)
                }
                Icon(Icons.Default.ChevronRight, null, tint = pal.subText)
            }
        }
    }
}
@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    val pal = LocalPal.current
    Text(title, Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = pal.accent)
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp).clip(shape).background(pal.surface).border(1.dp, pal.divider, shape)) { content() }
}

/** 横に並んだ選択肢から 1 つ選ぶ行(ホームの「1行に並べる数」やドロワーの切り替えと同じ見た目)。 */
@Composable
private fun SegmentRow(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val pal = LocalPal.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(title, Modifier.padding(start = 4.dp, bottom = 4.dp), fontSize = 13.sp, color = pal.subText)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(pal.tileBg).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            options.forEachIndexed { i, label ->
                val on = i == selected
                Box(
                    Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (on) pal.accent else Color.Transparent).clickable { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label, fontSize = 13.sp, maxLines = 1,
                        color = if (on) pal.onAccent else pal.text,
                        fontWeight = if (on) FontWeight.Bold else null,
                    )
                }
            }
        }
    }
}
/** 入り切りの行。 */
@Composable
private fun SwitchRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 16.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), fontSize = 16.sp, color = LocalPal.current.text)
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}
/** 押すと別の画面・ダイアログが開く行。warn は注意の色(異常終了の記録)。 */
@Composable
private fun LinkRow(title: String, subtitle: String? = null, warn: Boolean = false, onClick: () -> Unit) {
    val pal = LocalPal.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = if (warn) MaterialTheme.colorScheme.error else pal.text)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = pal.subText)
        }
        Icon(Icons.Default.ChevronRight, null, tint = pal.subText)
    }
    HorizontalDivider(color = pal.divider)
}
