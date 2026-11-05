package local.filemanager

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.BoxScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 上から下へ引っ張って更新する。更新が終わるまで(最短0.4秒)くるくるを出す。
 * isBusy が true の間は終わるのを待つ(一覧の読み込み中など)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PullRefresh(
    onRefresh: () -> Unit,
    isBusy: () -> Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    if (state.isRefreshing) {
        LaunchedEffect(true) {
            onRefresh()
            delay(400)
            snapshotFlow { isBusy() }.first { !it }
            state.endRefresh()
        }
    }
    // くるくるは普段は枠の上にはみ出して隠れている部品なので、枠の外を切らないと、上のパンくずの上に丸が残って見える
    Box(modifier.clipToBounds().nestedScroll(state.nestedScrollConnection)) {
        content()
        PullToRefreshContainer(state = state, modifier = Modifier.align(Alignment.TopCenter))
    }
}

@Composable
internal fun ListScreen(vm: FmViewModel, screen: Screen, actions: Actions) {
    val pal = LocalPal.current
    val inSearch = vm.searchQuery != null
    val selecting = vm.selected.isNotEmpty()
    val list = vm.visible
    val dir = screen as? Screen.Dir
    val local = dir == null || dir.fsId == "local"
    // NAS のサムネイルは、設定で切っていないときだけ読み込む
    val thumbFs: Fs? = if (!local && vm.nasThumbs) vm.listFs else null

    Column(Modifier.fillMaxSize()) {
        if (dir != null && !inSearch) Breadcrumb(vm, dir)
        // 読み込み中の細いバーは、出ていないときも場所を空けておく(出し入れで一覧が上下にずれて、引っ張る動きがカクつくため)
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (vm.loading || vm.searching) {
                LinearProgressIndicator(Modifier.fillMaxSize(), color = pal.accent, trackColor = Color.Transparent)
            }
        }
        PullRefresh(
            onRefresh = vm::refreshList,
            isBusy = { vm.loading },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            val clickOf: (FileItem) -> Unit = { item ->
                when {
                    selecting || screen == Screen.Trash -> vm.toggleSelect(item)
                    item.isDir && dir != null -> vm.openDir(dir.fsId, item.path)
                    item.isDir -> vm.openLocal(item.path)
                    else -> vm.openOrView(item)
                }
            }
            if (vm.gridView && screen != Screen.Trash) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(list, key = { it.path }) { item ->
                        GridCell(
                            item = item,
                            local = local,
                            thumbFs = thumbFs,
                            selected = item.path in vm.selected,
                            onClick = { clickOf(item) },
                            onLongClick = { vm.toggleSelect(item) },
                        )
                    }
                }
            } else LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.path }) { item ->
                    // 検索・分類の一覧は場所がばらばらなので、元のフォルダも表示する
                    val extra = when {
                        inSearch && dir != null -> item.path.substringBeforeLast('/').removePrefix(dir.path.trimEnd('/')).trimStart('/').ifEmpty { "/" }
                        screen is Screen.Cat -> item.path.substringBeforeLast('/')
                        screen == Screen.Trash -> vm.trashOrigin(item)
                        else -> null
                    }
                    FileRow(
                        item = item,
                        local = local,
                        thumbFs = thumbFs,
                        selected = item.path in vm.selected,
                        extraLine = extra,
                        onClick = { clickOf(item) },
                        onLongClick = { vm.toggleSelect(item) },
                    )
                }
            }
            if (list.isEmpty()) {
                val text = when {
                    inSearch && vm.searchQuery.isNullOrBlank() -> Txt(R.string.list_ui_001)
                    inSearch -> if (vm.searching) Txt(R.string.list_ui_002) else Txt(R.string.list_ui_003)
                    vm.loading -> Txt(R.string.list_ui_004)
                    vm.listError != null -> vm.listError!!
                    screen == Screen.Trash -> Txt(R.string.list_ui_005)
                    else -> Txt(R.string.list_ui_006)
                }
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(text, color = pal.subText)
                }
            }
        }
    }
}

/** 上のパンくず。先頭の家のアイコンでホームへ、そのあとストレージ(NAS)名とフォルダ名が続く。 */
@Composable
private fun Breadcrumb(vm: FmViewModel, dir: Screen.Dir) {
    val pal = LocalPal.current
    val crumbs = remember(dir) { vm.crumbs(dir) }
    // reverseScrolling: 深い階層でも現在地(右端)が見える位置から始まる
    Row(
        Modifier.fillMaxWidth().background(pal.surface).heightIn(min = 46.dp)
            .horizontalScroll(rememberScrollState(), reverseScrolling = true)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Home, Txt(R.string.list_ui_007), tint = pal.accent,
            modifier = Modifier.size(34.dp).clickable { vm.goHome() }.padding(4.dp),
        )
        crumbs.forEachIndexed { i, c ->
            Icon(Icons.Default.ChevronRight, null, tint = pal.subText, modifier = Modifier.size(22.dp))
            val last = i == crumbs.lastIndex
            Text(
                c.label,
                fontSize = 15.sp,
                maxLines = 1,
                fontWeight = if (last) FontWeight.Bold else FontWeight.Normal,
                color = if (last) pal.text else pal.subText,
                modifier = Modifier.clickable(enabled = !last) { vm.openDir(dir.fsId, c.path, track = false) }.padding(horizontal = 6.dp, vertical = 10.dp),
            )
        }
    }
    HorizontalDivider(color = pal.divider)
}

/** グリッド表示の1マス。大きなサムネイルと、その下に名前(2行まで)と大きさ。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridCell(
    item: FileItem,
    local: Boolean,
    thumbFs: Fs?,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val pal = LocalPal.current
    val count = rememberChildCount(item, local)
    val info = if (item.isDir) (count?.let { Txt.n(R.plurals.item_count, it) } ?: "") else FileOps.formatSize(item.size)
    val textColor = if (selected) Color.Black else pal.text
    Column(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (selected) pal.selectedRow else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            FileIcon(item, local, Modifier.fillMaxSize().padding(if (item.isDir) 8.dp else 0.dp), big = true, remoteFs = thumbFs)
            if (selected) {
                Icon(
                    Icons.Default.CheckCircle, null, tint = pal.accent,
                    modifier = Modifier.align(Alignment.TopStart).padding(2.dp).size(22.dp).background(Color.White, CircleShape),
                )
            }
        }
        Text(
            item.name, fontSize = 13.sp, color = textColor, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
        )
        Text(info, fontSize = 11.sp, color = if (selected) pal.text else pal.subText, maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    item: FileItem,
    local: Boolean,
    thumbFs: Fs?,
    selected: Boolean,
    extraLine: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val pal = LocalPal.current
    val count = rememberChildCount(item, local)
    val info = when {
        item.isDir -> if (count != null) Txt.n(R.plurals.item_count, count) else ""
        else -> FileOps.formatSize(item.size)
    }
    val date = remember(item.modified) { FileOps.formatDay(item.modified) }
    Column(Modifier.background(if (selected) pal.selectedRow else Color.Transparent)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileIcon(item, local, Modifier.size(44.dp), remoteFs = thumbFs)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name, fontSize = 16.sp, color = if (selected) Color.Black else pal.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(info, fontSize = 13.sp, color = if (selected) pal.text else pal.subText, maxLines = 1, modifier = Modifier.weight(1f))
                    Text(date, fontSize = 13.sp, color = if (selected) pal.text else pal.subText, maxLines = 1)
                }
                if (extraLine != null) {
                    Text(extraLine, fontSize = 12.sp, color = if (selected) pal.text else pal.subText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        HorizontalDivider(color = pal.divider)
    }
}
