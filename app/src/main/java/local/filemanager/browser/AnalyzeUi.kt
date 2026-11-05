package local.filemanager

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
internal fun AnalyzeScreen(vm: FmViewModel) {
    val s = vm.screen as? Screen.Analyze ?: return
    if (s.fsId == null) AnalyzeChooser(vm) else AnalyzeResult(vm)
}

// ---------------------------------------------------------------- 場所の選択

@Composable
private fun AnalyzeChooser(vm: FmViewModel) {
    val pal = LocalPal.current
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(Txt(R.string.analyze_ui_001), color = pal.subText, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
        }
        items(vm.volumes, key = { it.dir.path }) { v ->
            val used = (v.total - v.free).coerceAtLeast(0)
            Column(Modifier.fillMaxWidth().clickable { vm.openAnalyze("local", v.dir.path) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Storage, null, tint = Color(0xFF9E9E9E), modifier = Modifier.size(40.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(v.label, fontSize = 16.sp, color = pal.text)
                        Text(Txt(R.string.analyze_volume, FileOps.formatSize(used), FileOps.formatSize(v.total), FileOps.formatSize(v.free)), fontSize = 13.sp, color = pal.subText)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Bar(if (v.total > 0) used.toFloat() / v.total else 0f)
            }
            HorizontalDivider(color = pal.divider)
        }
        items(vm.remotes, key = { it.id }) { r ->
            Row(
                Modifier.fillMaxWidth().clickable { vm.openAnalyze("smb:${r.id}", r.startDir) }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Computer, null, tint = Color(0xFF455A64), modifier = Modifier.size(40.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.name, fontSize = 16.sp, color = pal.text)
                    Text(Txt(R.string.analyze_nas_note, r.host, r.share), fontSize = 13.sp, color = pal.subText)
                }
            }
            HorizontalDivider(color = pal.divider)
        }
    }
}

// ---------------------------------------------------------------- 結果

@Composable
private fun AnalyzeResult(vm: FmViewModel) {
    val pal = LocalPal.current
    val a = vm.analysis
    when {
        vm.analyzing -> Scanning(vm)
        vm.analysisError != null -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(vm.analysisError ?: "", color = pal.text, textAlign = TextAlign.Center)
            TextButton(onClick = vm::rescan) { Text(Txt(R.string.analyze_ui_002)) }
        }
        a != null -> ResultBody(vm, a)
    }
}

@Composable
private fun Scanning(vm: FmViewModel) {
    val pal = LocalPal.current
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(300)
            tick++
        }
    }
    @Suppress("UNUSED_VARIABLE") val readTick = tick // 一定間隔で読み直すため
    val p = vm.scanProgress
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = pal.accent)
        Spacer(Modifier.height(20.dp))
        Text(Txt(R.string.analyze_ui_003), fontSize = 18.sp, color = pal.text)
        Spacer(Modifier.height(6.dp))
        Text(Txt.n(R.plurals.analyze_files_size, p.files, FileOps.formatSize(p.bytes)), fontSize = 15.sp, color = pal.text)
        Spacer(Modifier.height(6.dp))
        Text(p.current, fontSize = 12.sp, color = pal.subText, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { vm.openAnalyze() }) { Text(Txt(R.string.analyze_ui_004)) }
    }
}

@Composable
private fun ResultBody(vm: FmViewModel, a: Analysis) {
    val pal = LocalPal.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val trail = vm.analysisTrail.ifEmpty { listOf(a.root.path) }
    val currentPath = trail.last()
    val current: DiskNode = if (trail.size == 1) a.root
    else a.children[trail[trail.size - 2]]?.firstOrNull { it.path == currentPath } ?: a.root
    val atRoot = trail.size == 1
    val fsId = (vm.screen as? Screen.Analyze)?.fsId

    Column(Modifier.fillMaxSize()) {
        // 見出し: いまのフォルダの合計
        Column(Modifier.fillMaxWidth().background(pal.surface).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(FileOps.formatSize(current.size), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = pal.text)
            Text(Txt.n(R.plurals.analyze_files, current.files), fontSize = 13.sp, color = pal.subText)
            val space = vm.analysisSpace
            if (atRoot && space != null) {
                val (total, free) = space
                Spacer(Modifier.height(6.dp))
                Text(Txt(R.string.analyze_storage_total, FileOps.formatSize((total - free).coerceAtLeast(0)), FileOps.formatSize(total), FileOps.formatSize(free)), fontSize = 13.sp, color = pal.text)
                Spacer(Modifier.height(4.dp))
                Bar(if (total > 0) (total - free).toFloat() / total else 0f)
            }
            if (atRoot && fsId == "local") {
                Spacer(Modifier.height(4.dp))
                Text(Txt(R.string.analyze_ui_005), fontSize = 11.sp, color = pal.subText)
            }
        }
        // パンくず(分析した場所からいまのフォルダまで)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState(), reverseScrolling = true).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            trail.forEachIndexed { i, p ->
                if (i > 0) Icon(Icons.Default.ChevronRight, null, tint = pal.subText, modifier = Modifier.size(20.dp))
                val last = i == trail.lastIndex
                val label = if (i == 0) a.root.name else p.trimEnd('/').substringAfterLast('/')
                Text(
                    label, fontSize = 14.sp, maxLines = 1,
                    fontWeight = if (last) FontWeight.Bold else FontWeight.Normal,
                    color = if (last) pal.text else pal.subText,
                    modifier = Modifier.clickable(enabled = !last) { vm.drillTo(i) }.padding(horizontal = 6.dp, vertical = 10.dp),
                )
            }
        }
        TabRow(selectedTabIndex = tab, containerColor = pal.surface, contentColor = pal.accent) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(Txt(R.string.analyze_ui_006)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(Txt(R.string.analyze_ui_007)) })
        }
        if (tab == 0) FolderTab(vm, a, current, currentPath) else BigFilesTab(vm, a, currentPath)
    }
}

@Composable
private fun FolderTab(vm: FmViewModel, a: Analysis, current: DiskNode, currentPath: String) {
    val pal = LocalPal.current
    val nodes = a.children[currentPath]
    if (nodes == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Txt(R.string.analyze_ui_008), color = pal.subText, textAlign = TextAlign.Center)
            TextButton(onClick = { vm.openAnalyzed(current) }) { Text(Txt(R.string.analyze_ui_009)) }
        }
        return
    }
    if (nodes.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(Txt(R.string.analyze_ui_010), color = pal.subText) }
        return
    }
    val total = current.size
    LazyColumn(Modifier.fillMaxSize()) {
        items(nodes, key = { it.path }) { n ->
            NodeRow(
                node = n,
                fraction = if (total > 0) n.size.toFloat() / total else 0f,
                subtitle = if (n.isDir) Txt.n(R.plurals.analyze_files, n.files) else FileOps.formatSize(n.size),
                onClick = { if (n.isDir) vm.drillInto(n.path) else vm.openAnalyzed(n) },
                onOpen = { vm.openAnalyzed(n) },
            )
        }
        a.omitted[currentPath]?.let { (count, bytes) ->
            item {
                Text(
                    Txt.n(R.plurals.analyze_others, count, FileOps.formatSize(bytes)),
                    fontSize = 13.sp, color = pal.subText, modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun BigFilesTab(vm: FmViewModel, a: Analysis, currentPath: String) {
    val pal = LocalPal.current
    val list = a.biggestFiles.filter { Nav.isUnder(currentPath, it.path) }
    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(Txt(R.string.analyze_no_big, Analyzer.BIGGEST), color = pal.subText, textAlign = TextAlign.Center)
        }
        return
    }
    val biggest = list.first().size
    LazyColumn(Modifier.fillMaxSize()) {
        items(list, key = { it.path }) { n ->
            NodeRow(
                node = n,
                fraction = if (biggest > 0) n.size.toFloat() / biggest else 0f,
                subtitle = FileOps.formatSize(n.size) + Txt(R.string.sep_dot) + n.path.substringBeforeLast('/').removePrefix(currentPath.trimEnd('/')).ifEmpty { "/" },
                onClick = { vm.openAnalyzed(n) },
                onOpen = { vm.openAnalyzed(n) },
            )
        }
    }
}

@Composable
private fun NodeRow(node: DiskNode, fraction: Float, subtitle: String, onClick: () -> Unit, onOpen: () -> Unit) {
    val pal = LocalPal.current
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 8.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (node.isDir) FolderGlyph(kindColor(FileKind.FOLDER), Modifier.size(40.dp))
            else FileIcon(FileItem(node.path, node.name, false, node.size, 0L), local = false, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(node.name, fontSize = 15.sp, color = pal.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 12.sp, color = pal.subText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(FileOps.formatSize(node.size), fontSize = 14.sp, color = pal.text)
                Text("%.1f%%".format(fraction * 100), fontSize = 11.sp, color = pal.subText)
            }
            IconButton(onClick = onOpen) { Icon(Icons.AutoMirrored.Filled.OpenInNew, Txt(R.string.analyze_ui_011), tint = pal.subText, modifier = Modifier.size(20.dp)) }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.padding(end = 16.dp)) { Bar(fraction) }
    }
    HorizontalDivider(color = pal.divider)
}

/** 横長の棒グラフ(0〜1)。 */
@Composable
private fun Bar(fraction: Float) {
    val pal = LocalPal.current
    Box(Modifier.fillMaxWidth().height(5.dp).background(pal.divider)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(pal.accent))
    }
}
