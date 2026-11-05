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
            Text(Txt(R.string.screens_001), style = MaterialTheme.typography.titleMedium, color = pal.text)
            Spacer(Modifier.height(8.dp))
            Text(
                Txt(R.string.screens_002),
                style = MaterialTheme.typography.bodyMedium,
                color = pal.subText,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRequest) { Text(Txt(R.string.screens_003)) }
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
        // 同じ記録のお知らせは 1 度だけ(出したら「見た」ことにする)。記録は「設定」の「異常終了の記録」で見られる
        val unseen = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CrashLog.hasUnseen(appCtx) }
        if (unseen) {
            CrashLog.markSeen(appCtx)
            vm.messages.emit(Txt(R.string.screens_004))
        }
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
                    ) { Icon(Icons.Default.Add, Txt(R.string.screens_005)) }
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
                        Text(Txt.n(R.plurals.selection_count, vm.selected.size), fontSize = 18.sp, maxLines = 1)
                        Text(Txt.n(R.plurals.selection_total, vm.visible.size), fontSize = 12.sp, color = pal.onBar.copy(alpha = 0.65f), maxLines = 1)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = vm::clearSelection) { Icon(Icons.Default.Close, Txt(R.string.screens_006)) }
                },
                actions = {
                    IconButton(onClick = vm::selectAll) { Icon(Icons.Default.SelectAll, Txt(R.string.screens_007)) }
                    IconButton(onClick = vm::invertSelection) { Icon(Icons.Default.FlipToBack, Txt(R.string.screens_008)) }
                },
                colors = barColors(pal.selectBar, pal.onBar),
            )

        query != null -> {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            TopAppBar(
                title = {
                    Box {
                        if (query.isEmpty()) Text(Txt(R.string.screens_009), color = pal.onBar.copy(alpha = 0.6f))
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
                    IconButton(onClick = vm::closeSearch) { Icon(Icons.Default.Close, Txt(R.string.screens_010)) }
                },
                actions = {
                    if (query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Default.Clear, Txt(R.string.screens_011)) }
                },
                colors = barColors(pal.topBar, pal.onBar),
            )
        }

        else -> TopAppBar(
            title = { Text(vm.titleOf(screen), maxLines = 1) },
            navigationIcon = { IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, Txt(R.string.screens_012)) } },
            actions = {
                if (vm.canSearch) IconButton(onClick = vm::startSearch) { Icon(Icons.Default.Search, Txt(R.string.screens_013)) }
                val gridToggle: @Composable () -> Unit = {
                    IconButton(onClick = vm::toggleGrid) {
                        if (vm.gridView) Icon(Icons.Default.ViewList, Txt(R.string.screens_014)) else Icon(Icons.Default.GridView, Txt(R.string.screens_015))
                    }
                }
                when (screen) {
                    Screen.Trash -> if (vm.items.isNotEmpty()) {
                        IconButton(onClick = { onPending(Pending.EmptyTrash) }) { Icon(Icons.Default.DeleteForever, Txt(R.string.screens_016)) }
                    }
                    Screen.Network -> IconButton(onClick = { onPending(Pending.EditRemote(null)) }) { Icon(Icons.Default.Add, Txt(R.string.screens_017)) }
                    Screen.Home -> {
                        if (!vm.homeEditing) IconButton(onClick = { vm.homeEditing = true }) { Icon(Icons.Default.Edit, Txt(R.string.screens_018)) }
                        IconButton(onClick = { onPending(Pending.Display) }) { Icon(Icons.Default.Tune, Txt(R.string.screens_019)) }
                    }
                    is Screen.Cat -> gridToggle()
                    is Screen.Settings -> {}
                    is Screen.Analyze -> if (screen.fsId != null && !vm.analyzing) {
                        IconButton(onClick = vm::rescan) { Icon(Icons.Default.Refresh, Txt(R.string.screens_020)) }
                    }
                    else -> {
                        if (screen is Screen.Dir) gridToggle()
                        IconButton(onClick = { onPending(Pending.Display) }) { Icon(Icons.Default.Tune, Txt(R.string.screens_021)) }
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
            if (incoming.size > 1) Txt.n(R.plurals.hint_save_more, incoming.size - 1, incoming.first().name)
            else Txt(R.string.hint_save_one, incoming.first().name)
        count == 0 && clip != null -> {
            val first = clip.items.first().name
            val others = clip.items.size - 1
            when {
                clip.move && others > 0 -> Txt.n(R.plurals.hint_paste_move_more, others, first)
                clip.move -> Txt(R.string.hint_paste_move_one, first)
                others > 0 -> Txt.n(R.plurals.hint_paste_copy_more, others, first)
                else -> Txt(R.string.hint_paste_copy_one, first)
            }
        }
        else -> null
    }
    Column(Modifier.fillMaxWidth().background(pal.surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(pal.divider))
        if (message != null) {
            Text(message, color = pal.subText, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 4.dp)) {
            if (count > 0 && vm.screen == Screen.Trash) {
                ActionButton(Icons.Default.RestoreFromTrash, Txt(R.string.screens_022), Modifier.weight(1f)) { vm.restoreSelected() }
                ActionButton(Icons.Default.DeleteForever, Txt(R.string.screens_023), Modifier.weight(1f), danger = true) { onPending(Pending.Delete(count, false)) }
            } else if (count > 0) {
                var more by remember { mutableStateOf(false) }
                ActionButton(Icons.Default.ContentCopy, Txt(R.string.screens_024), Modifier.weight(1f)) { vm.copySelected() }
                ActionButton(Icons.Default.DriveFileMove, Txt(R.string.screens_025), Modifier.weight(1f)) { vm.cutSelected() }
                ActionButton(Icons.Default.Share, Txt(R.string.screens_026), Modifier.weight(1f)) { vm.shareSelected() }
                ActionButton(Icons.Default.Delete, Txt(R.string.screens_027), Modifier.weight(1f), danger = true) { onPending(Pending.Delete(count, vm.deletesToTrash)) }
                Box(Modifier.weight(1f)) {
                    ActionButton(Icons.Default.MoreVert, Txt(R.string.screens_028), Modifier.fillMaxWidth()) { more = true }
                    MoreMenu(vm, more, { more = false }, actions, onPending)
                }
            } else if (showIncoming) {
                ActionButton(Icons.Default.Close, Txt(R.string.screens_029), Modifier.weight(1f)) { vm.cancelIncoming() }
                ActionButton(Icons.Default.Save, Txt(R.string.screens_030), Modifier.weight(1f), enabled = vm.screen is Screen.Dir) { vm.saveIncoming() }
            } else if (clip != null) {
                ActionButton(Icons.Default.Close, Txt(R.string.screens_031), Modifier.weight(1f)) { vm.cancelClip() }
                ActionButton(Icons.Default.ContentPaste, Txt(R.string.screens_032), Modifier.weight(1f), enabled = vm.canPaste) { vm.paste() }
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
    if (single != null) list.add(VMenuItem.Action(Icons.Default.DriveFileRenameOutline, Txt(R.string.screens_033)) { onPending(Pending.Rename(single)) })
    if (vm.screen is Screen.Dir) {
        list.add(
            VMenuItem.Action(Icons.Default.FolderZip, Txt(R.string.screens_034)) {
                val suggested = if (single != null) single.name.substringBeforeLast('.', single.name) + ".zip" else Txt(R.string.screens_035)
                onPending(Pending.Zip(suggested))
            },
        )
    }
    if (vm.screen is Screen.Dir && single != null && !single.isDir && (single.name.endsWith(".zip", ignoreCase = true) || Archives.isOtherArchive(single.name))) {
        list.add(VMenuItem.Action(Icons.Default.Unarchive, if (single.name.endsWith(".zip", ignoreCase = true)) Txt(R.string.screens_036) else Txt(R.string.screens_037)) { vm.unzipSelected() })
    }
    if (single != null && single.isDir) {
        list.add(VMenuItem.Action(Icons.Default.BookmarkAdd, Txt(R.string.screens_038)) { vm.addBookmark(single) })
        list.add(VMenuItem.Action(Icons.Default.Home, Txt(R.string.screens_039)) { vm.addHomeShortcutOf(single) })
        (vm.screen as? Screen.Dir)?.let { d ->
            list.add(VMenuItem.Action(Icons.Default.PieChart, Txt(R.string.screens_040)) { vm.openAnalyze(d.fsId, single.path) })
        }
    }
    list.add(VMenuItem.Action(Icons.Default.Info, Txt(R.string.screens_041)) { onPending(Pending.Details(items)) })
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
                TextButton(onClick = onCancel) { Text(Txt(R.string.screens_042)) }
            }
        }
    }
}
