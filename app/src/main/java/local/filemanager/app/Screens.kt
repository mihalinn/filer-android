package local.filemanager

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.File

/** Activity 側でしかできない操作(他のアプリで開く・共有)。 */
class Actions(
    val openFile: (File) -> Unit,
    val share: (List<File>) -> Unit,
    val openUrl: (String) -> Unit,
)

@Composable
fun AppRoot(vm: FmViewModel, hasAccess: Boolean, onRequestAccess: () -> Unit, actions: Actions) {
    if (hasAccess) MainScreen(vm, actions) else PermissionScreen(onRequestAccess)
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    val pal = LocalPal.current
    Surface(Modifier.fillMaxSize(), color = pal.background) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FolderGlyph(kindColor(FileKind.FOLDER), Modifier.size(72.dp))
            Spacer(Modifier.height(24.dp))
            Text("すべてのファイルへのアクセスが必要です", style = MaterialTheme.typography.titleMedium, color = pal.text)
            Spacer(Modifier.height(8.dp))
            Text(
                "ファイルを表示・操作するため、設定で「ファイルへのアクセスを許可」をオンにしてください",
                style = MaterialTheme.typography.bodyMedium,
                color = pal.subText,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRequest) { Text("設定を開く") }
        }
    }
}

@Composable
private fun MainScreen(vm: FmViewModel, actions: Actions) {
    val pal = LocalPal.current
    val snackbar = remember { SnackbarHostState() }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pending?>(null) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    val appCtx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        if (CrashLog.read(appCtx) != null) vm.messages.emit("前回、アプリが異常終了しました。原因の記録は、「設定」の「このアプリについて」で確認できます")
    }
    LaunchedEffect(Unit) { vm.openRequests.collect { actions.openFile(it) } }
    LaunchedEffect(Unit) { vm.updater.urlRequests.collect { actions.openUrl(it) } }
    LaunchedEffect(Unit) { vm.updater.checkOnStart() }
    LaunchedEffect(Unit) { vm.shareRequests.collect { actions.share(it) } }
    LaunchedEffect(Unit) { vm.refreshHome() }
    BackHandler(enabled = vm.canGoBack) { vm.onBack() }
    // あとから登録したものが優先されるので、ドロワーが開いている間はこちらが先に閉じる
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

    Box(Modifier.fillMaxSize()) {
    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            AppDrawer(vm, close = { scope.launch { drawer.close() } }, onPending = { pending = it })
        },
    ) {
        Scaffold(
            containerColor = pal.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = { TopBar(vm, onMenu = { scope.launch { drawer.open() } }) { pending = it } },
            bottomBar = { BottomBar(vm, actions) { pending = it } },
            floatingActionButton = {
                if (vm.screen is Screen.Dir && vm.selected.isEmpty() && vm.searchQuery == null && vm.clip == null) {
                    FloatingActionButton(
                        onClick = { pending = Pending.NewFolder },
                        containerColor = pal.accent,
                        contentColor = pal.onAccent,
                    ) { Icon(Icons.Default.Add, "新しいフォルダ") }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (val s = vm.screen) {
                    Screen.Home -> HomeScreen(vm) { pending = it }
                    Screen.Network -> NetworkScreen(vm) { pending = it }
                    is Screen.Analyze -> AnalyzeScreen(vm)
                    is Screen.Settings -> SettingsScreen(vm, s)
                    else -> ListScreen(vm, s, actions)
                }
                val busy = vm.busy
                val pv = vm.progressView
                when {
                    busy == null -> {}
                    pv == null -> BusyOverlay(busy, onCancel = vm::cancelOp)
                    vm.progressHidden -> Box(Modifier.align(Alignment.BottomCenter)) { HiddenProgressBar(pv, vm::showProgress) }
                    else -> ProgressDialog(pv, onHide = vm::hideProgress, onCancel = vm::cancelOp)
                }
            }
        }
    }
    PendingDialogs(pending, vm) { pending = null }
    UpdateDialog(vm.updater)
    vm.conflictPrompt?.let { ConflictDialog(vm, it) }
    // 画像・テキストのビューアは、一覧の上に重ねて表示する
    vm.viewer?.let { ViewerScreen(vm, it) }
    }
}

// ---------------------------------------------------------------- 上部バー

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun barColors(bg: Color, on: Color) = TopAppBarDefaults.topAppBarColors(
    containerColor = bg,
    titleContentColor = on,
    navigationIconContentColor = on,
    actionIconContentColor = on,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(vm: FmViewModel, onMenu: () -> Unit, onPending: (Pending) -> Unit) {
    val pal = LocalPal.current
    val query = vm.searchQuery
    val screen = vm.screen
    when {
        vm.selected.isNotEmpty() -> TopAppBar(
                title = {
                    Column {
                        Text("${vm.selected.size}件を選択", fontSize = 18.sp, maxLines = 1)
                        Text("全${vm.visible.size}件", fontSize = 12.sp, color = pal.onBar.copy(alpha = 0.65f), maxLines = 1)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = vm::clearSelection) { Icon(Icons.Default.Close, "選択を解除") }
                },
                actions = {
                    IconButton(onClick = vm::selectAll) { Icon(Icons.Default.SelectAll, "すべて選択") }
                    IconButton(onClick = vm::invertSelection) { Icon(Icons.Default.FlipToBack, "選択を反転") }
                },
                colors = barColors(pal.selectedRow, pal.onBar),
            )

        query != null -> {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            TopAppBar(
                title = {
                    Box {
                        if (query.isEmpty()) Text("このフォルダ内を検索", color = pal.onBar.copy(alpha = 0.6f))
                        BasicTextField(
                            value = query,
                            onValueChange = vm::setQuery,
                            singleLine = true,
                            textStyle = TextStyle(color = pal.onBar, fontSize = 18.sp),
                            cursorBrush = SolidColor(pal.onBar),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = vm::closeSearch) { Icon(Icons.Default.Close, "検索を閉じる") }
                },
                actions = {
                    if (query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Default.Clear, "消去") }
                },
                colors = barColors(pal.topBar, pal.onBar),
            )
        }

        else -> TopAppBar(
            title = { Text(vm.titleOf(screen), maxLines = 1) },
            navigationIcon = { IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "メニュー") } },
            actions = {
                if (vm.canSearch) IconButton(onClick = vm::startSearch) { Icon(Icons.Default.Search, "検索") }
                val gridToggle: @Composable () -> Unit = {
                    IconButton(onClick = vm::toggleGrid) {
                        if (vm.gridView) Icon(Icons.Default.ViewList, "一覧表示にする") else Icon(Icons.Default.GridView, "グリッド表示にする")
                    }
                }
                when (screen) {
                    Screen.Trash -> if (vm.items.isNotEmpty()) {
                        IconButton(onClick = { onPending(Pending.EmptyTrash) }) { Icon(Icons.Default.DeleteForever, "ごみ箱を空にする") }
                    }
                    Screen.Network -> IconButton(onClick = { onPending(Pending.EditRemote(null)) }) { Icon(Icons.Default.Add, "NASを追加") }
                    Screen.Home -> {
                        if (!vm.homeEditing) IconButton(onClick = { vm.homeEditing = true }) { Icon(Icons.Default.Edit, "ホームを編集") }
                        IconButton(onClick = { onPending(Pending.Display) }) { Icon(Icons.Default.Tune, "表示設定") }
                    }
                    is Screen.Cat -> gridToggle()
                    is Screen.Settings -> {}
                    is Screen.Analyze -> if (screen.fsId != null && !vm.analyzing) {
                        IconButton(onClick = vm::rescan) { Icon(Icons.Default.Refresh, "調べ直す") }
                    }
                    else -> {
                        if (screen is Screen.Dir) gridToggle()
                        IconButton(onClick = { onPending(Pending.Display) }) { Icon(Icons.Default.Tune, "表示設定") }
                    }
                }
            },
            colors = barColors(pal.topBar, pal.onBar),
        )
    }
}

// ---------------------------------------------------------------- 下部バー

@Composable
private fun ActionButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val pal = LocalPal.current
    val alpha = if (enabled) 1f else 0.38f
    val iconTint = (if (danger) Color(0xFFE57373) else pal.accent).copy(alpha = alpha)
    Column(
        modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(24.dp))
        Text(label, color = pal.text.copy(alpha = alpha), fontSize = 11.sp, maxLines = 1, textAlign = TextAlign.Center)
    }
}

@Composable
private fun BottomBar(vm: FmViewModel, actions: Actions, onPending: (Pending) -> Unit) {
    val pal = LocalPal.current
    val count = vm.selected.size
    val clip = vm.clip
    val incoming = vm.incoming
    val showIncoming = count == 0 && incoming != null
    if (!showIncoming && count == 0 && (clip == null || vm.screen !is Screen.Dir || vm.searchQuery != null)) return
    val message = when {
        showIncoming && incoming != null ->
            "「${incoming.first().name + if (incoming.size > 1) " ほか${incoming.size - 1}件" else ""}」の保存先を開いて、「ここに保存」を押してください"
        count == 0 && clip != null ->
            "「${clip.items.first().name + if (clip.items.size > 1) " ほか${clip.items.size - 1}件" else ""}」を${if (clip.move) "移動" else "コピー"}する場所を開いて、「貼り付け」を押してください"
        else -> null
    }
    Column(Modifier.fillMaxWidth().background(pal.surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(pal.divider))
        if (message != null) {
            Text(message, color = pal.subText, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 4.dp)) {
            if (count > 0 && vm.screen == Screen.Trash) {
                ActionButton(Icons.Default.RestoreFromTrash, "元に戻す", Modifier.weight(1f)) { vm.restoreSelected() }
                ActionButton(Icons.Default.DeleteForever, "完全に削除", Modifier.weight(1f), danger = true) { onPending(Pending.Delete(count, false)) }
            } else if (count > 0) {
                var more by remember { mutableStateOf(false) }
                ActionButton(Icons.Default.ContentCopy, "コピー", Modifier.weight(1f)) { vm.copySelected() }
                ActionButton(Icons.Default.DriveFileMove, "移動", Modifier.weight(1f)) { vm.cutSelected() }
                ActionButton(Icons.Default.Share, "共有", Modifier.weight(1f)) { vm.shareSelected() }
                ActionButton(Icons.Default.Delete, "削除", Modifier.weight(1f), danger = true) { onPending(Pending.Delete(count, vm.deletesToTrash)) }
                Box(Modifier.weight(1f)) {
                    ActionButton(Icons.Default.MoreVert, "その他", Modifier.fillMaxWidth()) { more = true }
                    MoreMenu(vm, more, { more = false }, actions, onPending)
                }
            } else if (showIncoming) {
                ActionButton(Icons.Default.Close, "キャンセル", Modifier.weight(1f)) { vm.cancelIncoming() }
                ActionButton(Icons.Default.Save, "ここに保存", Modifier.weight(1f), enabled = vm.screen is Screen.Dir) { vm.saveIncoming() }
            } else if (clip != null) {
                ActionButton(Icons.Default.Close, "キャンセル", Modifier.weight(1f)) { vm.cancelClip() }
                ActionButton(Icons.Default.ContentPaste, "貼り付け", Modifier.weight(1f), enabled = vm.canPaste) { vm.paste() }
            }
        }
    }
}
@Composable
private fun MoreMenu(vm: FmViewModel, expanded: Boolean, dismiss: () -> Unit, actions: Actions, onPending: (Pending) -> Unit) {
    val items = vm.selectedItems()
    val single = items.singleOrNull()
    if (!expanded) return
    val list = ArrayList<VMenuItem.Action>()
    if (single != null) list.add(VMenuItem.Action(Icons.Default.DriveFileRenameOutline, "名前の変更") { onPending(Pending.Rename(single)) })
    if (vm.screen is Screen.Dir) {
        list.add(
            VMenuItem.Action(Icons.Default.FolderZip, "ZIPに圧縮") {
                val suggested = if (single != null) single.name.substringBeforeLast('.', single.name) + ".zip" else "アーカイブ.zip"
                onPending(Pending.Zip(suggested))
            },
        )
    }
    if (vm.screen is Screen.Dir && single != null && !single.isDir && (single.name.endsWith(".zip", ignoreCase = true) || Archives.isOtherArchive(single.name))) {
        list.add(VMenuItem.Action(Icons.Default.Unarchive, if (single.name.endsWith(".zip", ignoreCase = true)) "ZIPを展開" else "展開") { vm.unzipSelected() })
    }
    if (single != null && single.isDir) {
        list.add(VMenuItem.Action(Icons.Default.BookmarkAdd, "ブックマークに追加") { vm.addBookmark(single) })
        list.add(VMenuItem.Action(Icons.Default.Home, "ホームに追加") { vm.addHomeShortcutOf(single) })
        (vm.screen as? Screen.Dir)?.let { d ->
            list.add(VMenuItem.Action(Icons.Default.PieChart, "容量を分析") { vm.openAnalyze(d.fsId, single.path) })
        }
    }
    list.add(VMenuItem.Action(Icons.Default.Info, "詳細") { onPending(Pending.Details(items)) })
    ListMenu(expanded, LocalPal.current.dark, dismiss, list, above = true)
}

@Composable
private fun BusyOverlay(message: String, onCancel: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0x66000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(10.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(message)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onCancel) { Text("中止") }
            }
        }
    }
}
