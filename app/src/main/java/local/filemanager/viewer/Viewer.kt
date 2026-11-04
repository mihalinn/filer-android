package local.filemanager

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.InvertColorsOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pageview
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Javascript
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 画像・テキストを、アプリの中で開く画面。画面全体に重ねて表示し、「戻る」で閉じる。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ViewerScreen(
    vm: FmViewModel,
    req: FmViewModel.ViewerRequest,
    onClose: () -> Unit = { vm.closeViewer() },
    /** ZIP の中から開いたもの(端末の一時ファイル)。「ここに展開」などは出さない */
    nested: Boolean = false,
) {
    // 動画・音声は専用のプレイヤー画面で開く。画面を消しても再生は続くが、画面を閉じる(×・戻る)ときは再生も終える
    val playable = isPlayable(req.files[req.index].kind)
    val close: () -> Unit = if (playable) ({ PlaybackHolder.stop(); onClose() }) else onClose
    BackHandler { close() }
    if (playable) {
        // MIDI は ExoPlayer で鳴らせないので、端末の MediaPlayer で鳴らす専用の画面
        if (isMidiName(req.files[req.index].name)) MidiScreen(vm, req, close) else PlayerScreen(vm, req, close)
        return
    }
    val isImage = req.files[req.index].kind == FileKind.IMAGE
    val kb = rememberKeyboardAvoid()
    val appCtx = androidx.compose.ui.platform.LocalContext.current
    // 「全文をコピー」の中身。画面(文字・表・Word・スライド)が、読み込んだあとに入れる
    val copyHolder = remember(req.files[req.index].path) { CopyHolder() }
    var bars by remember { mutableStateOf(true) }
    var fontSp by remember { mutableIntStateOf(vm.viewerFontSp) }
    var raw by remember { mutableStateOf(false) }
    var headerOn by remember { mutableStateOf(true) }
    var gotoDialog by remember { mutableStateOf(false) }
    var layersDialog by remember { mutableStateOf(false) }
    var inner by remember { mutableStateOf<FmViewModel.ViewerRequest?>(null) }
    var bundleList by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var hit by remember { mutableIntStateOf(0) }
    var hitCount by remember { mutableIntStateOf(0) }
    var htmlJs by remember { mutableStateOf(false) }
    // PDF・ウェブページ・図面は、暗い配色のとき色を反転して見せる。「元の色で表示」で戻せる
    var keepColors by remember { mutableStateOf(false) }
    var htmlNet by remember { mutableStateOf(false) }
    var mhtWide by remember { mutableStateOf(true) }
    // 文字のファイルの編集。保存したら reloadTick を進めて、表示を読み直す
    var editing by remember { mutableStateOf(false) }
    // CSV・TSV を、表(セルを選んで書き換える)で編集する画面
    var editingGrid by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableIntStateOf(0) }
    val pager = rememberPagerState(initialPage = req.index) { req.files.size }
    val current = req.files[if (isImage) pager.currentPage.coerceIn(0, req.files.lastIndex) else 0]
    // SVG は絵で見せるが、メニューでソース(XML)にも切り替えられる
    val currentIsSvg = isImage && FileOps.extensionOf(current.name) == "svg"
    val svgSource = currentIsSvg && raw
    val showImage = isImage && !svgSource
    var infoFor by remember { mutableStateOf<FileItem?>(null) }
    // 拡張子「.key」は、Keynote の書類(zip)のほかに、暗号の鍵ファイル(文字)にも使われる。zip でなければ文字として見せる
    val extKey = FileOps.extensionOf(current.name) == "key"
    val keyIsZip by produceState(true, current.path, extKey) {
        value = if (!extKey) true else withContext(Dispatchers.IO) {
            runCatching { req.fs.open(current.path).use { s -> val b = ByteArray(2); s.read(b) == 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() } }.getOrDefault(true)
        }
    }
    val kindByName = if (showImage) null
    else PreviewKind.of(current.name) ?: if (current.kind == FileKind.OTHER) PreviewKind.UNKNOWN else PreviewKind.TEXT
    val kind = if (kindByName == PreviewKind.IWORK && extKey && !keyIsZip) PreviewKind.TEXT else kindByName
    // CAD のうち DXF だけは文字で書かれているので、メニューで「テキスト」にも切り替えられる
    val cadIsText = kind == PreviewKind.CAD && FileOps.extensionOf(current.name) == "dxf"
    // 検索できるのは、行が並んで出る表示のとき
    val searchable = when (kind) {
        PreviewKind.TEXT, PreviewKind.CODE, PreviewKind.JSON, PreviewKind.XML, PreviewKind.UNKNOWN -> true
        PreviewKind.MARKDOWN, PreviewKind.CSV, PreviewKind.HTML, PreviewKind.MHTML, PreviewKind.WEBARCHIVE, PreviewKind.VCARD -> raw
        else -> false
    }
    val activeQuery = if (searching && searchable) query else ""
    // 編集できるのは文字のファイル。書庫の中から開いたもの(一時ファイル)は、書き戻す先が無いので除く
    val editable = !nested && when (kind) {
        PreviewKind.TEXT, PreviewKind.CODE, PreviewKind.JSON, PreviewKind.XML, PreviewKind.MARKDOWN, PreviewKind.CSV, PreviewKind.HTML, PreviewKind.VCARD, PreviewKind.UNKNOWN -> true
        else -> false
    }
    // 画像は黒背景のまま。Office は白い紙の見た目なので、配色の切り替えは出さない
    val themed = !showImage && kind != PreviewKind.COMIC
    val officeKind = kind?.isOffice == true
    val barBg = if (themed) Pv.bar else Color(0xCC000000)
    val barFg = if (themed) Pv.barText else Color.White
    val barSub = if (themed) Pv.barSub else Color(0xFFCCCCCC)
    // 明るい背景のときは、状態バー・ナビゲーションバーの文字を暗くする(閉じたら元に戻す)
    val lightBars = themed && !Pv.dark && !officeKind
    val view = LocalView.current
    DisposableEffect(lightBars) {
        val window = (view.context as? android.app.Activity)?.window
        val ctl = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        val oldStatus = ctl?.isAppearanceLightStatusBars
        val oldNav = ctl?.isAppearanceLightNavigationBars
        // 入れ子(書庫の中のファイル)で開いたときも、そのファイルの背景に合わせる
        ctl?.isAppearanceLightStatusBars = lightBars
        ctl?.isAppearanceLightNavigationBars = lightBars
        onDispose {
            if (oldStatus != null) ctl.isAppearanceLightStatusBars = oldStatus
            if (oldNav != null) ctl.isAppearanceLightNavigationBars = oldNav
        }
    }

    // 下の一覧に触れないよう、画面全体で触れた操作を受け止める
    CompositionLocalProvider(LocalViewerClose provides onClose) {
    Box(Modifier.fillMaxSize().then(kb.container).background(if (themed && !officeKind) Pv.bg else Color.Black).pointerInput(Unit) { detectTapGestures { } }) {
        if (showImage) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { req.files[it].path }) { page ->
                ZoomableImage(req.fs, req.files[page], active = pager.currentPage == page, onTap = { bars = !bars })
            }
        } else if (kind == PreviewKind.PDF) {
            PdfPane(req.fs, current, bars, gotoDialog, { gotoDialog = false }, onTap = { bars = !bars }, dark = Pv.dark && !keepColors, copy = copyHolder)
        } else if (kind == PreviewKind.DRAWIO) {
            DrawioPane(req.fs, current, bars, onTap = { bars = !bars }, dark = Pv.dark && !keepColors)
        } else if (kind == PreviewKind.STL || kind == PreviewKind.MODEL) {
            StlPane(req.fs, current, bars, onTap = { bars = !bars })
        } else if (kind == PreviewKind.CAD && !(raw && cadIsText)) {
            CadPane(req.fs, current, bars, layersDialog, { layersDialog = false }, onTap = { bars = !bars })
        } else if (kind == PreviewKind.ZIP || kind == PreviewKind.ARCHIVE || ((kind == PreviewKind.BUNDLE || kind == PreviewKind.COMIC || kind == PreviewKind.EPUB || kind == PreviewKind.IWORK || kind == PreviewKind.IPA) && bundleList)) {
            ZipPane(vm, req.fs, current) { inner = it }
        } else if (kind == PreviewKind.COMIC) {
            ComicPane(req.fs, current, vm.comicRtl, bars, onTap = { bars = !bars })
        } else if (kind == PreviewKind.EPUB) {
            EpubPane(req.fs, current, bars, fontSp, onTap = { bars = !bars })
        } else if (kind == PreviewKind.BUNDLE) {
            BundlePane(req.fs, current, onInstall = { vm.installBundle(req.fs, current); onClose() }, onList = { bundleList = true })
        } else if (kind != null && kind.isOffice) {
            OfficePane(req.fs, current, kind, fontSp, copyHolder)
        } else if (kind == PreviewKind.APK) {
            ApkPane(req.fs, current) {
                if (req.fs.isLocal) {
                    vm.openRequests.tryEmit(File(current.path))
                } else {
                    // 情報を見るときに取り込み済みなら、それをそのままインストールに渡す(もう一度ダウンロードしない)
                    val cached = cachedApk(view.context, req.fs, current)
                    if (cached != null) vm.openRequests.tryEmit(cached) else vm.openItem(current, req.fs)
                }
            }
        } else if ((kind == PreviewKind.MHTML || kind == PreviewKind.WEBARCHIVE) && !raw) {
            MhtmlPane(req.fs, current, htmlJs, mhtWide, web = kind == PreviewKind.WEBARCHIVE, dark = Pv.dark && !keepColors)
        } else if (kind == PreviewKind.HTML && !raw) {
            key(reloadTick) { HtmlPane(req.fs, current, htmlJs, htmlNet, dark = Pv.dark && !keepColors) }
        } else if (kind == PreviewKind.VCARD && !raw) {
            key(reloadTick) { VcardPane(req.fs, current, bars, onTap = { bars = !bars }) }
        } else if (kind == PreviewKind.PKPASS) {
            PkpassPane(req.fs, current)
        } else if (kind == PreviewKind.IWORK) {
            IworkPane(req.fs, current, onOpenInner = { inner = it }, onList = { bundleList = true })
        } else if (kind == PreviewKind.IPA) {
            IpaPane(req.fs, current, onList = { bundleList = true })
        } else if (kind == PreviewKind.SQLITE) {
            SqlitePane(req.fs, current, fontSp)
        } else if (kind == PreviewKind.FONT) {
            FontPane(req.fs, current)
        } else if (kind == PreviewKind.UNKNOWN) {
            key(reloadTick) {
                UnknownPane(
                    req.fs, current, fontSp, bars, onTap = { bars = !bars }, onExternal = { vm.openItem(current, req.fs) },
                    query = activeQuery, hit = hit, onHits = { hitCount = it }, copy = copyHolder,
                )
            }
        } else {
            key(reloadTick) {
                DocPane(
                    req.fs, current, if (kind == PreviewKind.HTML) PreviewKind.CODE else if (kind == PreviewKind.MHTML) PreviewKind.TEXT else if (kind == PreviewKind.WEBARCHIVE) PreviewKind.XML else if (kind == PreviewKind.CAD) PreviewKind.TEXT else kind ?: PreviewKind.TEXT, fontSp, bars, raw, headerOn,
                    onTap = { bars = !bars }, query = activeQuery, hit = hit, onHits = { hitCount = it }, copy = copyHolder,
                )
            }
        }
        if (bars) {
            var menu by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().background(if (officeKind) Color(0xCC000000) else barBg).statusBarsPadding()) {
                val fg = if (officeKind) Color.White else barFg
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "閉じる", tint = fg) }
                    Column(Modifier.weight(1f)) {
                        Text(current.name, color = fg, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (isImage && req.files.size > 1) {
                            Text("${pager.currentPage + 1} / ${req.files.size}", color = barSub, fontSize = 12.sp)
                        }
                    }
                    // 文字のファイルは、すぐ編集に入れる(種類が分からないファイルは、メニューの「編集」から)
                    // CSV・TSV は、表で見ているときは表で編集する(テキストで見ているときは文字として編集する)
                    val gridEdit = kind == PreviewKind.CSV && !raw
                    if (editable && kind != PreviewKind.UNKNOWN) IconButton(onClick = { if (gridEdit) editingGrid = true else editing = true }) { Icon(Icons.Default.Edit, "編集", tint = fg) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "メニュー", tint = fg) }
                        // 並びは固定: 開く・共有(最上段)→ 表示 → ファイル。種類によって変わるのは各まとまりの中身だけ
                        val modes = ArrayList<VMenuItem>()
                        val toggles = ArrayList<VMenuItem>()
                        var sizeRow = false
                        fun mode(label: String, a: String, b: String, second: Boolean, set: (Boolean) -> Unit) {
                            modes.add(VMenuItem.Segment(label, listOf(a, b), if (second) 1 else 0) { set(it == 1) })
                        }
                        if (currentIsSvg && !svgSource) mode("表示のしかた", "絵", "ソース(XML)", false) { raw = it }
                        when (kind) {
                            null, PreviewKind.PDF, PreviewKind.DRAWIO, PreviewKind.STL, PreviewKind.MODEL, PreviewKind.APK, PreviewKind.FONT, PreviewKind.PKPASS,
                            PreviewKind.ZIP, PreviewKind.ARCHIVE, PreviewKind.BUNDLE,
                            -> {}
                            PreviewKind.CAD -> {
                                if (cadIsText) mode("表示のしかた", "図面", "テキスト", raw) { raw = it }
                                if (raw && cadIsText) sizeRow = true
                            }
                            PreviewKind.COMIC -> {
                                mode("表示のしかた", "マンガ", "ファイル一覧", bundleList) { bundleList = it }
                                if (!bundleList) {
                                    modes.add(
                                        VMenuItem.Segment("めくる向き", listOf("右から左(マンガ)", "左から右"), if (vm.comicRtl) 0 else 1) { i ->
                                            if ((i == 0) != vm.comicRtl) vm.toggleComicRtl()
                                        },
                                    )
                                }
                            }
                            PreviewKind.IWORK, PreviewKind.IPA -> mode("表示のしかた", "情報", "ファイル一覧", bundleList) { bundleList = it }
                            PreviewKind.EPUB -> {
                                mode("表示のしかた", "本", "ファイル一覧", bundleList) { bundleList = it }
                                if (!bundleList) sizeRow = true
                            }
                            PreviewKind.HTML, PreviewKind.MHTML, PreviewKind.WEBARCHIVE -> {
                                mode("表示のしかた", "ページ", if (kind == PreviewKind.WEBARCHIVE) "構造" else "ソース", raw) { raw = it }
                                if (!raw) {
                                    toggles.add(VMenuItem.Toggle(Icons.Default.Javascript, "JavaScript を許可", htmlJs) { htmlJs = it })
                                    if (kind == PreviewKind.HTML) toggles.add(VMenuItem.Toggle(Icons.Default.Public, "外部の読み込みを許可", htmlNet) { htmlNet = it })
                                    else toggles.add(VMenuItem.Toggle(Icons.Default.Computer, "パソコン向けの幅で表示", mhtWide) { mhtWide = it })
                                } else sizeRow = true
                            }
                            PreviewKind.VCARD -> {
                                mode("表示のしかた", "連絡先", "テキスト", raw) { raw = it }
                                if (raw) sizeRow = true
                            }
                            else -> {
                                when (kind) {
                                    PreviewKind.MARKDOWN -> mode("表示のしかた", "整形", "テキスト", raw) { raw = it }
                                    PreviewKind.CSV -> mode("表示のしかた", "表", "テキスト", raw) { raw = it }
                                    PreviewKind.JSON, PreviewKind.XML ->
                                        if (svgSource) mode("表示のしかた", "絵", "ソース(XML)", true) { raw = it }
                                        else mode("表示のしかた", "整形", "テキスト", raw) { raw = it }
                                    else -> {}
                                }
                                if (kind == PreviewKind.CSV && !raw) toggles.add(VMenuItem.Toggle(Icons.Default.TableRows, "1行目を見出しにする", headerOn) { headerOn = it })
                                sizeRow = true
                            }
                        }
                        if (themed) {
                            modes.add(VMenuItem.Segment("配色", listOf("ライト", "ダーク"), if (Pv.dark) 1 else 0) { vm.changeViewerTheme(if (it == 1) "dark" else "light") })
                        }
                        if (Pv.dark && (kind == PreviewKind.PDF || kind == PreviewKind.DRAWIO || ((kind == PreviewKind.HTML || kind == PreviewKind.MHTML || kind == PreviewKind.WEBARCHIVE) && !raw))) {
                            toggles.add(VMenuItem.Toggle(Icons.Default.InvertColorsOff, "元の色で表示", keepColors) { keepColors = it })
                        }
                        val viewItems = ArrayList<VMenuItem>().apply {
                            addAll(modes); addAll(toggles)
                            if (sizeRow) add(VMenuItem.TextSize(fontSp, VIEWER_FONT_MIN, VIEWER_FONT_MAX) { fontSp = it })
                        }
                        val fileItems = ArrayList<VMenuItem>()
                        if (editable) {
                            if (kind == PreviewKind.CSV && !raw) {
                                fileItems.add(VMenuItem.Action(Icons.Default.Edit, "表で編集") { editingGrid = true })
                                fileItems.add(VMenuItem.Action(Icons.Default.Edit, "テキストとして編集") { editing = true })
                            } else {
                                fileItems.add(VMenuItem.Action(Icons.Default.Edit, "編集") { editing = true })
                            }
                        }
                        if (searchable) fileItems.add(VMenuItem.Action(Icons.Default.Search, "検索") { searching = true })
                        val copyAct = copyHolder.action
                        val copyProv = copyHolder.provider
                        if (copyAct != null) fileItems.add(VMenuItem.Action(Icons.Default.ContentCopy, copyHolder.label) { copyAct() })
                        else if (copyProv != null) fileItems.add(VMenuItem.Action(Icons.Default.ContentCopy, copyHolder.label) { copyToClipboard(appCtx, copyProv()) })
                        if (kind == PreviewKind.PDF) fileItems.add(VMenuItem.Action(Icons.Default.Pageview, "ページ移動…") { gotoDialog = true })
                        if (kind == PreviewKind.CAD && !(raw && cadIsText)) fileItems.add(VMenuItem.Action(Icons.Default.Layers, "レイヤー…") { layersDialog = true })
                        if (isImage && !currentIsSvg) fileItems.add(VMenuItem.Action(Icons.Default.Info, "写真の情報") { infoFor = current })
                        val canUnpack = kind == PreviewKind.COMIC || kind == PreviewKind.IWORK || kind == PreviewKind.IPA ||
                            kind == PreviewKind.ZIP || kind == PreviewKind.ARCHIVE || kind == PreviewKind.BUNDLE
                        if (canUnpack && !nested) fileItems.add(VMenuItem.Action(Icons.Default.Unarchive, "ここに展開") { vm.unzipItem(current); onClose() })
                        ViewerMenu(
                            expanded = menu,
                            dark = if (themed) Pv.dark else true,
                            onDismiss = { menu = false },
                            onOpenExternal = { vm.openItem(current, req.fs) },
                            onShare = { vm.shareItems(listOf(current), req.fs) },
                            sections = listOf(VMenuSection("表示", viewItems), VMenuSection("ファイル", fileItems)),
                        )
                    }
                }
            }
        }
        if (searching && searchable) {
            SearchBar(
                query, { query = it; hit = 0 }, hit, hitCount,
                onPrev = { if (hitCount > 0) hit = (hit - 1 + hitCount) % hitCount },
                onNext = { if (hitCount > 0) hit = (hit + 1) % hitCount },
                onClose = { searching = false },
                bottomPad = kb.bottom,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        // NAS のファイルの読み込みに時間がかかるとき、量・速さ・残り時間を出す(中央のぐるぐるの下)
        (req.fs as? TrackedFs)?.let { ReadProgressCard(it.tracker, Modifier.align(Alignment.Center)) }
        infoFor?.let { f -> PhotoInfoDialog(req.fs, f) { infoFor = null } }
        // ZIP の中から開いたもの。上のバーよりも前に出す
        inner?.let { r -> ViewerScreen(vm, r, onClose = { inner = null }, nested = true) }
        if (editingGrid) {
            CsvEditorScreen(
                req.fs, current, headerOn, fontSp,
                onSaved = { reloadTick++; vm.reload() },
                onClose = { editingGrid = false },
            )
        }
        if (editing) {
            TextEditorScreen(
                req.fs, current, fontSp, { fontSp = it },
                onSaved = { reloadTick++; vm.reload() },
                onClose = { editing = false },
            )
        }
    }
    }
}

/** 文字の中の検索のバー(画面の下)。当たった数と、前へ・次へのボタン。 */
@Composable
private fun SearchBar(
    query: String, onQuery: (String) -> Unit, hit: Int, count: Int,
    onPrev: () -> Unit, onNext: () -> Unit, onClose: () -> Unit, bottomPad: androidx.compose.ui.unit.Dp, modifier: Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        // 余白(キーボードか下のバーの分)は背景の外に出す。背景の内側に入れると、灰色の板が伸びて文字が透ける
        modifier.fillMaxWidth().padding(bottom = bottomPad).background(Pv.searchBg).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = query, onValueChange = onQuery, singleLine = true,
            textStyle = TextStyle(color = Pv.barText, fontSize = 16.sp), cursorBrush = SolidColor(Pv.barText),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onNext() }),
            modifier = Modifier.weight(1f).focusRequester(focus).background(Pv.searchField, RoundedCornerShape(6.dp)).padding(10.dp),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) Text("検索する言葉", color = Color.Gray, fontSize = 16.sp)
                    inner()
                }
            },
        )
        Text(
            if (query.isEmpty()) "" else if (count == 0) "0件" else "${hit + 1}/$count",
            color = Pv.barText, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp),
        )
        IconButton(onClick = onPrev) { Icon(Icons.Default.KeyboardArrowUp, "前へ", tint = Pv.barText) }
        IconButton(onClick = onNext) { Icon(Icons.Default.KeyboardArrowDown, "次へ", tint = Pv.barText) }
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "閉じる", tint = Pv.barText) }
    }
}
