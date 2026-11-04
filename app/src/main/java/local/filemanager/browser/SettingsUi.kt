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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
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
import androidx.compose.runtime.mutableLongStateOf
import android.widget.Toast
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
        SettingsPage.DEVELOPER -> DeveloperPage(vm)
        else -> SettingsMain(vm)
    }
}

/** 開発者向けオプションの画面(Android の同名の画面に合わせた作り)。一番上のスイッチで、中の機能をまとめて入り切りできる。 */
@Composable
private fun DeveloperPage(vm: FmViewModel) {
    val pal = LocalPal.current
    val on = vm.devMaster
    var showPerf by remember { mutableStateOf(false) }
    var stamp by remember { mutableIntStateOf(0) }
    val logLines = remember(showPerf, vm.perfLogActive, stamp) { PerfLog.lines(2400) }
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxSize().background(pal.background).verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp).clip(shape)
                .background(if (on) pal.accent else pal.surface).border(1.dp, if (on) pal.accent else pal.divider, shape)
                .clickable { vm.changeDevMaster(!on) }.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (on) "オン" else "オフ", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (on) pal.onAccent else pal.text)
            Switch(checked = on, onCheckedChange = { vm.changeDevMaster(it) })
        }
        Group("デバッグ") {
            SwitchRow(
                "動作の記録", vm.perfLogOn, enabled = on,
                subtitle = "重くなったときの原因調べ用です。オンの間だけ、メモリなどを端末の中に記録します",
            ) { vm.setPerfLog(!vm.perfLogOn) }
            HorizontalDivider(color = pal.divider)
            LinkRow(
                "動作の記録を見る",
                if (logLines.isEmpty()) "記録はありません" else "${logLines.size} 行" + (PerfFormat.lastStamp(logLines)?.let { "・最後は $it" } ?: ""),
                enabled = on,
            ) { showPerf = true }
        }
        Group("テスト機能") {
            Text("まだありません", Modifier.padding(horizontal = 16.dp, vertical = 12.dp), fontSize = 14.sp, color = pal.subText)
        }
        Spacer(Modifier.height(24.dp))
    }
    if (showPerf) PerfLogDialog { showPerf = false; stamp++ }
}

@Composable
private fun SettingsMain(vm: FmViewModel) {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
    // 異常終了の記録を見た・消したら、行の表示を数え直す
    var crashStamp by remember { mutableIntStateOf(0) }
    val crashRows = remember(crashStamp) { CrashLog.entries(ctx) }
    val crashUnseen = remember(crashStamp) { CrashLog.hasUnseen(ctx) }
    var showCrash by remember { mutableStateOf(false) }
    // バージョンを続けて押した回数(開発者向けオプションを出すため)
    var tapCount by remember { mutableIntStateOf(0) }
    var tapAt by remember { mutableLongStateOf(0L) }
    var report by remember { mutableStateOf<Boolean?>(null) }
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
            HorizontalDivider(color = pal.divider)
            ThemeColorRow(vm.themeColor) { vm.changeThemeColor(it) }
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
        Group("アプリの更新") { UpdateSettings(vm.updater) }
        Group("不具合・要望・記録") {
            LinkRow("不具合を報告", "動かない・表示がおかしいなど。GitHub に報告します") { report = true }
            LinkRow("要望を送る", "こんな機能がほしい、など") { report = false }
            LinkRow("報告の一覧を見る", "GitHub で、これまでの報告と対応を見られます") { vm.openUrl("${vm.updater.repoUrl}/issues") }
            LinkRow(
                "異常終了の記録",
                if (crashRows.isEmpty()) "記録はありません" else "${crashRows.size} 件・最後は ${CrashLog.stamp(crashRows.first().time)}" + if (crashUnseen) "(まだ見ていません)" else "",
                warn = crashUnseen,
            ) { showCrash = true }
        }
        Group("このアプリについて") {
            Column(
                Modifier.fillMaxWidth().clickable {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (vm.devMode) {
                        Toast.makeText(ctx, "開発者向けオプションは、すでに表示されています", Toast.LENGTH_SHORT).show()
                        return@clickable
                    }
                    tapCount = DevTap.next(tapCount, tapAt, now)
                    tapAt = now
                    if (tapCount >= DevTap.NEED) {
                        tapCount = 0
                        vm.changeDevMode(true)
                        Toast.makeText(ctx, "開発者向けオプションを表示しました", Toast.LENGTH_SHORT).show()
                    } else {
                        DevTap.remaining(tapCount)?.let { Toast.makeText(ctx, "あと $it 回押すと、開発者向けオプションが表示されます", Toast.LENGTH_SHORT).show() }
                    }
                }.padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
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
            if (vm.devMode) LinkRow("開発者向けオプション", "デバッグ・テスト用の機能") { vm.openSettings(SettingsPage.DEVELOPER) }
        }
        Spacer(Modifier.height(24.dp))
    }
    if (showCrash) CrashLogDialog(onCleared = { crashStamp++ }) { showCrash = false; crashStamp++ }
    report?.let { bug -> ReportDialog(vm, bug) { report = null } }
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
/** テーマカラーを選ぶ行。丸い見本(バーの色と差し色)を並べ、選んでいるものに枠を付ける。 */
@Composable
private fun ThemeColorRow(selected: String, onSelect: (String) -> Unit) {
    val pal = LocalPal.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text("テーマカラー", Modifier.padding(start = 4.dp, bottom = 6.dp), fontSize = 13.sp, color = pal.subText)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ThemeColor.entries.forEach { c ->
                val on = c.id == selected
                // 見本はライトの色で描く(ダークでも、どんな色か分かるように)
                val sample = palOf(c, false)
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { onSelect(c.id) }.padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            .border(if (on) 3.dp else 1.dp, if (on) pal.accent else pal.divider, androidx.compose.foundation.shape.CircleShape),
                    ) {
                        Box(Modifier.weight(1f).fillMaxSize().background(sample.topBar))
                        Box(Modifier.weight(1f).fillMaxSize().background(sample.accent))
                    }
                    Text(
                        c.label, Modifier.padding(top = 4.dp), fontSize = 12.sp, maxLines = 1,
                        color = if (on) pal.accent else pal.text, fontWeight = if (on) FontWeight.Bold else null,
                    )
                }
            }
        }
    }
}

/** 入り切りの行。 */
@Composable
internal fun SwitchRow(label: String, checked: Boolean, subtitle: String? = null, enabled: Boolean = true, onToggle: () -> Unit) {
    val pal = LocalPal.current
    Row(
        Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f).clickable(enabled = enabled, onClick = onToggle)
            .padding(start = 16.dp, end = 12.dp, top = if (subtitle == null) 2.dp else 8.dp, bottom = if (subtitle == null) 2.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(label, fontSize = 16.sp, color = pal.text)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = pal.subText)
        }
        Switch(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
    }
}
/** 押すと別の画面・ダイアログが開く行。warn は注意の色(異常終了の記録)。enabled が false のときは灰色で押せない。 */
@Composable
private fun LinkRow(title: String, subtitle: String? = null, warn: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val pal = LocalPal.current
    Row(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f).clickable(enabled = enabled, onClick = onClick).padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = if (warn) MaterialTheme.colorScheme.error else pal.text)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = pal.subText)
        }
        Icon(Icons.Default.ChevronRight, null, tint = pal.subText)
    }
    HorizontalDivider(color = pal.divider)
}
