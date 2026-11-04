package local.filemanager

import android.app.Application
import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class FmViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("fm", Context.MODE_PRIVATE)

    var screen by mutableStateOf<Screen>(Screen.Home)
        private set
    var volumes by mutableStateOf<List<Volume>>(emptyList())
        private set
    var remotes by mutableStateOf(RemoteStore.load(app))
        private set
    var items by mutableStateOf<List<FileItem>>(emptyList())
        private set
    var listError by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var sortKey by mutableStateOf(readSortKey())
        private set
    var sortAsc by mutableStateOf(prefs.getBoolean("sortAsc", true))
        private set
    var showHidden by mutableStateOf(prefs.getBoolean("showHidden", false))
        private set
    var themeMode by mutableStateOf(prefs.getString("theme", "light") ?: "light")
        private set
    /** ビューア(開いた画面)の配色: "dark"(既定)/ "light" / "app"(アプリのテーマに合わせる) */
    var viewerTheme by mutableStateOf((prefs.getString("viewerTheme", "dark") ?: "dark").also { Pv.mode = it })
        private set
    /** マンガ(.cbz など)を、右から左へめくって読むか(既定は日本のマンガに合わせて、右から左) */
    var comicRtl by mutableStateOf(prefs.getBoolean("comicRtl", true))
        private set
    var selected by mutableStateOf<Set<String>>(emptySet())
        private set
    var clip by mutableStateOf<Clip?>(null)
        private set
    var searchQuery by mutableStateOf<String?>(null)
        private set
    var results by mutableStateOf<List<FileItem>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    // 実行中の操作は画面を閉じても続くので、状態はアプリ全体の Ops が持つ
    val busy: String? get() = Ops.busy

    /** コピー・移動などの進み具合(進捗を出さない操作では null)。 */
    val progressView: ProgressView? get() = Ops.progressView

    /** 「非表示にする」を押したあと、進捗の窓を隠している間 true。 */
    val progressHidden: Boolean get() = Ops.progressHidden
    var history by mutableStateOf(readPlaces("history"))
        private set
    var bookmarks by mutableStateOf(readPlaces("bookmarks"))
        private set

    /** ドロワーのごみ箱の下に並ぶ場所。pinned は固定したもの、opened は最近開いたもの(固定していないもの)。 */
    var pinned by mutableStateOf(readPlaces("pinned") { remotes.map { Place("smb:${it.id}", "") } })
        private set
    var opened by mutableStateOf(
        readPlaces("opened") {
            listOf(
                Place("local", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path),
                Place("cat", Category.AUDIO.name),
                Place("cat", Category.RECENT.name),
            )
        },
    )
        private set
    var categorySummary by mutableStateOf<Map<Category, Pair<Int, Long>>>(emptyMap())
        private set

    // ---- ホーム画面の並べ方 ----

    /** ホームのタイルの並び(タイルの id)。保存が無い・新しく増えたタイルは、初期の並びで後ろに付く。 */
    var homeOrder by mutableStateOf(HomeLayout.decode(prefs.getString("homeOrder", "") ?: ""))
        private set
    var homeHidden by mutableStateOf(HomeLayout.decode(prefs.getString("homeHidden", "") ?: "").toSet())
        private set
    var homeCols by mutableStateOf((prefs.getInt("homeCols", 4)).coerceIn(HomeLayout.MIN_COLS, HomeLayout.MAX_COLS))
        private set
    var homeShortcuts by mutableStateOf(HomeLayout.decodeShortcuts(prefs.getString("homeShortcuts", "") ?: ""))
        private set
    var homeEditing by mutableStateOf(false)

    fun changeHomeCols(n: Int) {
        homeCols = n.coerceIn(HomeLayout.MIN_COLS, HomeLayout.MAX_COLS)
        prefs.edit().putInt("homeCols", homeCols).apply()
    }

    /** ids は、いま画面に出ている全タイルの並び(隠しているものも含む)。index の項目を delta だけ動かして保存する。 */
    fun moveHomeTile(ids: List<String>, index: Int, delta: Int) {
        homeOrder = HomeLayout.move(ids, index, delta)
        prefs.edit().putString("homeOrder", HomeLayout.encode(homeOrder)).apply()
    }

    fun setHomeTileHidden(id: String, hidden: Boolean) {
        homeHidden = if (hidden) homeHidden + id else homeHidden - id
        prefs.edit().putString("homeHidden", HomeLayout.encode(homeHidden.toList())).apply()
    }

    fun addHomeShortcut(fsId: String, path: String, name: String) {
        val s = HomeShortcut(fsId, path, name)
        if (homeShortcuts.none { it.id == s.id }) {
            homeShortcuts = homeShortcuts + s
            prefs.edit().putString("homeShortcuts", HomeLayout.encodeShortcuts(homeShortcuts)).apply()
        }
        messages.tryEmit("ホームに追加しました")
    }

    /** いま開いているフォルダの中のフォルダ item を、ホームに置く。 */
    fun addHomeShortcutOf(item: FileItem) {
        if (!item.isDir) return
        addHomeShortcut((screen as? Screen.Dir)?.fsId ?: "local", item.path, item.name)
        selected = emptySet()
    }

    fun removeHomeShortcut(id: String) {
        homeShortcuts = homeShortcuts.filter { it.id != id }
        homeOrder = homeOrder - id
        homeHidden = homeHidden - id
        prefs.edit().putString("homeShortcuts", HomeLayout.encodeShortcuts(homeShortcuts))
            .putString("homeOrder", HomeLayout.encode(homeOrder)).putString("homeHidden", HomeLayout.encode(homeHidden.toList())).apply()
    }

    /** 並び・隠したタイル・列の数を初期に戻す(追加したフォルダのタイルは残す)。 */
    fun resetHome() {
        homeOrder = emptyList()
        homeHidden = emptySet()
        homeCols = 4
        prefs.edit().remove("homeOrder").remove("homeHidden").putInt("homeCols", 4).apply()
    }
    var trashSize by mutableStateOf(0L)
        private set

    /**
     * ホームやドロワーのショートカットから開いた端末内のフォルダ(ダウンロードなど)。
     * この中にいる間は、ここを最上位として扱い、戻る・上へでホームに戻る(ストレージの親へは行かない)。
     */
    private var anchor by mutableStateOf<String?>(null)

    /** 画面下のメッセージ(スナックバー)に出す文。 */
    val messages: MutableSharedFlow<String> get() = Ops.messages

    /** 開く・共有するファイル(NAS のものは一度端末に取り込んだもの)を Activity に渡す。 */
    val openRequests = MutableSharedFlow<File>(extraBufferCapacity = 4)
    val shareRequests = MutableSharedFlow<List<File>>(extraBufferCapacity = 4)

    /** アプリの更新(GitHub のリリース)。確認・ダウンロード・インストールの画面への受け渡し。 */
    // ダウンロードは画面(ViewModel)より長く生きる必要があるので、アプリ全体の Ops.scope で動かす
    val updater = UpdateManager(app, prefs, Ops.scope) { openRequests.tryEmit(it) }

    /** ブラウザで URL を開く(Activity 側が受け取る)。 */
    fun openUrl(url: String) { updater.urlRequests.tryEmit(url) }

    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var homeJob: Job? = null
    // 一覧の読み込み(裏のスレッド)と画面操作(メイン)の両方から触るため、並行して使える Map にする
    private val smbCache = java.util.concurrent.ConcurrentHashMap<String, SmbFs>()

    @Volatile
    private var trashOrigins: Map<String, String> = emptyMap()

    init {
        refreshVolumes()
        if (Ops.busy != null) {
            // アプリを閉じている間に始めた操作がまだ続いている。終わったら一覧を取り直す
            viewModelScope.launch {
                snapshotFlow { Ops.busy }.first { it == null }
                reload()
                refreshVolumes()
            }
        } else {
            // 前回までに取り込んだ NAS のファイルの一時コピーを片付ける(操作中は、使っているものを消さない)
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { File(app.cacheDir, "remote").takeIf { it.isDirectory }?.let { FileOps.delete(it) } }
            }
        }
    }

    val visible: List<FileItem> get() = if (searchQuery != null) results else items
    val canGoBack: Boolean get() = selected.isNotEmpty() || searchQuery != null || screen != Screen.Home

    /** いま一覧に出ているファイルを操作する Fs。分類・ごみ箱の画面は端末内のファイルだけ。 */
    private val itemFs: Fs get() = (screen as? Screen.Dir)?.let { fsOf(it.fsId) } ?: LocalFs

    private fun readSortKey(): SortKey =
        runCatching { SortKey.valueOf(prefs.getString("sortKey", "NAME")!!) }.getOrDefault(SortKey.NAME)

    /** まだ保存されていない(初めての)ときだけ initial の内容を使う。 */
    private fun readPlaces(key: String, initial: () -> List<Place> = { emptyList() }): List<Place> {
        if (!prefs.contains(key)) return initial()
        return (prefs.getString(key, "") ?: "").split('\n').mapNotNull {
            val p = it.split('\t')
            if (p.size == 2) Place(p[0], p[1]) else null
        }
    }

    private fun writePlaces(key: String, list: List<Place>) {
        prefs.edit().putString(key, list.joinToString("\n") { "${it.fsId}\t${it.path}" }).apply()
    }

    fun fsOf(id: String): Fs = when {
        id == "local" -> LocalFs
        id.startsWith("smb:") -> smbCache.getOrPut(id) {
            val cfg = remotes.firstOrNull { "smb:${it.id}" == id } ?: throw OpException("接続先が見つかりません")
            SmbFs(cfg)
        }
        else -> throw OpException("場所が不明です")
    }

    // ---- ストレージ ----

    fun refreshVolumes() {
        volumes = readVolumes(getApplication())
    }

    private fun readVolumes(ctx: Context): List<Volume> {
        val sm = ctx.getSystemService(StorageManager::class.java)
        return sm.storageVolumes
            .filter { it.state == Environment.MEDIA_MOUNTED || it.state == Environment.MEDIA_MOUNTED_READ_ONLY }
            .mapNotNull { v ->
                val d = v.directory ?: return@mapNotNull null
                val label = if (v.isPrimary) "メインストレージ" else (v.getDescription(ctx) ?: d.name)
                Volume(label, d, d.totalSpace, d.freeSpace)
            }
    }

    private fun rootOfLocal(path: String): File =
        volumes.map { it.dir }
            .filter { path == it.path || path.startsWith(it.path + "/") }
            .maxByOrNull { it.path.length } ?: File("/")

    /** 最上位の表示名。ダウンロードは日本語で、そのほかのフォルダは名前、ストレージはその名前。 */
    private fun rootLabelOf(root: String): String {
        if (volumes.none { it.dir.path == root } && root != "/") {
            if (root == downloadPath()) return "ダウンロード"
            return File(root).name
        }
        return labelOfRoot(File(root))
    }

    private fun downloadPath(): String = File(volumes.firstOrNull()?.dir ?: File("/storage/emulated/0"), "Download").path

    fun labelOfRoot(root: File): String =
        if (root.path == "/") "システム" else volumes.firstOrNull { it.dir.path == root.path }?.label ?: root.name

    private fun rootPath(d: Screen.Dir): String =
        if (d.fsId == "local") Nav.effectiveRoot(d.path, anchor, rootOfLocal(d.path).path) else "/"

    private fun sameDir(a: String, b: String) = Nav.same(a, b)

    /** パンくず。先頭がストレージ(または NAS)で、そのあとフォルダ名が続く。 */
    fun crumbs(d: Screen.Dir): List<Crumb> {
        val fs = fsOf(d.fsId)
        val root = rootPath(d)
        val out = ArrayList<Crumb>()
        var cur: String? = d.path
        while (cur != null && !sameDir(cur, root)) {
            out.add(Crumb(cur.trimEnd('/').substringAfterLast('/'), cur))
            cur = fs.parent(cur)
        }
        val rootLabel = if (d.fsId == "local") rootLabelOf(root) else remoteName(d.fsId)
        out.add(Crumb(rootLabel, root))
        return out.reversed()
    }

    fun remoteName(fsId: String): String = remotes.firstOrNull { "smb:${it.id}" == fsId }?.name ?: "NAS"

    /** 画面上部に出す題名。 */
    fun titleOf(s: Screen): String = when (s) {
        Screen.Home -> "ファイラー"
        is Screen.Cat -> s.cat.label
        Screen.Trash -> "ごみ箱"
        Screen.Network -> "ネットワーク"
        is Screen.Analyze -> "容量の分析"
        is Screen.Settings -> when (s.page) { SettingsPage.LICENSES -> "ライセンス"; SettingsPage.FORMATS -> "対応しているファイル形式"; SettingsPage.DEVELOPER -> "開発者向けオプション"; else -> "設定" }
        is Screen.Dir -> if (sameDir(s.path, rootPath(s))) {
            if (s.fsId == "local") rootLabelOf(rootPath(s)) else remoteName(s.fsId)
        } else s.path.trimEnd('/').substringAfterLast('/')
    }

    fun placeLabel(p: Place): String = when {
        p.fsId == "cat" -> runCatching { Category.valueOf(p.path).label }.getOrDefault(p.path)
        p.fsId == "local" -> File(p.path).name.ifEmpty { "システム" }
        p.path.isEmpty() -> remoteName(p.fsId)
        else -> "${remoteName(p.fsId)}: ${p.path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }}"
    }

    /** ドロワーの2行目に出す文(NAS は「/共有名/フォルダ」、フォルダは場所)。分類は無し。 */
    fun placeSubtitle(p: Place): String? = when {
        p.fsId == "cat" -> null
        p.fsId.startsWith("smb:") && p.path.isEmpty() ->
            remotes.firstOrNull { "smb:${it.id}" == p.fsId }?.let { "/${it.share}${it.startDir.trimEnd('/')}" }
        else -> p.path
    }

    /** 場所を開く。NAS の p.path が空なら、その接続先の「最初のフォルダ」を開く。 */
    fun openPlace(p: Place) {
        when {
            p.fsId == "cat" -> runCatching { Category.valueOf(p.path) }.getOrNull()?.let { openCategory(it) }
            p.fsId.startsWith("smb:") && p.path.isEmpty() ->
                remotes.firstOrNull { "smb:${it.id}" == p.fsId }?.let { openRemote(it) }
            else -> openDir(p.fsId, p.path)
        }
    }

    /** ホーム・ドロワーのショートカットから開く。「最近開いた場所」の先頭に入る。 */
    fun openShortcut(p: Place) {
        touchOpened(p)
        openPlace(p)
        // 端末内のフォルダ(ダウンロードなど)は、そこを最上位にする。NAS は共有の直下まで上がれるままにする。
        // 一覧をたどって開いた深いフォルダ(最近開いた場所から開くもの)は、親へ上がれるよう最上位にしない
        if (p.fsId == "local" && (Nav.depth(rootOfLocal(p.path).path, p.path) <= 1 || isHomeShortcut(p) || p in pinned)) anchor = p.path
    }

    private fun isHomeShortcut(p: Place) = homeShortcuts.any { it.fsId == p.fsId && Nav.same(it.path, p.path) }

    /** 「最近開いた場所」の先頭に入れる(固定した場所は入れない)。NAS の最初のフォルダは、接続先の項目と同じ扱いにする。最大 12 件。 */
    private fun touchOpened(p: Place) {
        val q = if (p.fsId.startsWith("smb:") && remotes.firstOrNull { "smb:${it.id}" == p.fsId }?.startDir?.let { Nav.same(it, p.path) } == true) Place(p.fsId, "") else p
        if (q in pinned) return
        opened = (listOf(q) + opened.filter { it != q }).take(12)
        writePlaces("opened", opened)
    }

    fun pin(p: Place) {
        if (p !in pinned) pinned = pinned + p
        opened = opened - p
        writePlaces("pinned", pinned)
        writePlaces("opened", opened)
    }

    /** 固定を外す。最近開いた場所の先頭に戻る。 */
    fun unpin(p: Place) {
        pinned = pinned - p
        opened = (listOf(p) + opened.filter { it != p }).take(12)
        writePlaces("pinned", pinned)
        writePlaces("opened", opened)
    }

    fun removeOpened(p: Place) {
        opened = opened - p
        writePlaces("opened", opened)
    }

    fun removeHistory(p: Place) {
        history = history - p
        writePlaces("history", history)
    }

    // ---- 画面遷移 ----

    private fun resetView() {
        analysisJob?.cancel()
        analyzing = false
        analysis = null
        selected = emptySet()
        closeSearch()
        loadJob?.cancel()
        items = emptyList()
        listError = null
    }

    fun openDir(fsId: String, path: String, track: Boolean = true) {
        // 履歴・ブックマークなどで anchor の外のフォルダへ移ったら、普通のストレージ直下を最上位に戻す
        if (fsId != "local" || anchor?.let { Nav.isUnder(it, path) } != true) anchor = null
        resetView()
        screen = Screen.Dir(fsId, path)
        reload()
        if (track) {
            val p = Place(fsId, path)
            history = (listOf(p) + history.filter { it != p }).take(30)
            writePlaces("history", history)
            touchOpened(p)
        }
    }

    fun openLocal(path: String) = openDir("local", path)

    fun openRemote(r: RemoteConfig) = openDir("smb:${r.id}", r.startDir)

    fun openCategory(cat: Category) {
        resetView()
        screen = Screen.Cat(cat)
        reload()
    }

    fun openTrash() {
        resetView()
        screen = Screen.Trash
        reload()
    }

    fun openNetwork() {
        resetView()
        screen = Screen.Network
    }

    fun openSettings(page: SettingsPage = SettingsPage.MAIN) {
        anchor = null
        resetView()
        screen = Screen.Settings(page)
    }

    fun goHome() {
        anchor = null
        resetView()
        screen = Screen.Home
        refreshVolumes()
        refreshHome()
    }

    fun up() {
        val d = screen as? Screen.Dir ?: return
        val target = Nav.up(d.path, rootPath(d), fsOf(d.fsId).parent(d.path))
        if (target == null) goHome() else openDir(d.fsId, target, track = false)
    }

    /** 戻る操作を処理したら true。false ならアプリを閉じてよい。 */
    fun onBack(): Boolean = when {
        selected.isNotEmpty() -> { selected = emptySet(); true }
        searchQuery != null -> { closeSearch(); true }
        screen is Screen.Analyze -> { analyzeBack(); true }
        (screen as? Screen.Settings)?.page.let { it != null && it != SettingsPage.MAIN } -> { openSettings(); true }
        screen is Screen.Dir -> { up(); true }
        screen != Screen.Home -> { goHome(); true }
        else -> false
    }

    // ---- 容量の分析 ----

    var analysis by mutableStateOf<Analysis?>(null)
        private set

    /** 調べている最中か。 */
    var analyzing by mutableStateOf(false)
        private set
    var analysisError by mutableStateOf<String?>(null)
        private set

    /** 分析した場所のある記憶領域の (全体, 空き)。分からなければ null。 */
    var analysisSpace by mutableStateOf<Pair<Long, Long>?>(null)
        private set

    /** いま見ているフォルダまでの道のり。先頭が分析した場所で、最後がいま表示しているフォルダ。 */
    var analysisTrail by mutableStateOf<List<String>>(emptyList())
        private set

    /** 調べている最中の進み具合(画面が一定間隔で読む)。 */
    var scanProgress = ScanProgress()
        private set
    private var analysisJob: Job? = null

    /** fsId が null なら分析する場所の選択画面、指定があればその場所を調べ始める。 */
    fun openAnalyze(fsId: String? = null, path: String? = null) {
        anchor = null
        resetView()
        screen = Screen.Analyze(fsId, path)
        if (fsId != null && path != null) startAnalysis(fsId, path)
    }

    fun rescan() {
        val s = screen as? Screen.Analyze ?: return
        if (s.fsId != null && s.path != null) startAnalysis(s.fsId, s.path)
    }

    private fun startAnalysis(fsId: String, path: String) {
        analysisJob?.cancel()
        analysis = null
        analysisError = null
        analysisSpace = null
        analysisTrail = listOf(path)
        analyzing = true
        val progress = ScanProgress().also { scanProgress = it }
        val name = if (fsId == "local") rootLabelOf(path) else remoteName(fsId) + path.trimEnd('/').let { if (it.isEmpty()) "" else ": " + it.substringAfterLast('/') }
        analysisJob = viewModelScope.launch {
            try {
                val fs = fsOf(fsId)
                val result = withContext(Dispatchers.IO) {
                    val a = Analyzer.scan(
                        fs, path, name, check = { ensureActive() }, progress = progress,
                        isLink = if (fs.isLocal) { p -> java.nio.file.Files.isSymbolicLink(java.nio.file.Paths.get(p)) } else { _ -> false },
                    )
                    a to runCatching { fs.space(path) }.getOrNull()
                }
                analysis = result.first
                analysisSpace = result.second
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                analysisError = e.message ?: "調べられませんでした"
            } finally {
                analyzing = false
            }
        }
    }

    fun drillInto(path: String) {
        analysisTrail = analysisTrail + path
    }

    /** パンくずから、その深さまで戻る。 */
    fun drillTo(index: Int) {
        analysisTrail = analysisTrail.take(index + 1)
    }

    /** 戻る操作。1つ上のフォルダへ、いちばん上ならホームへ。 */
    private fun analyzeBack() {
        if (analysisTrail.size > 1) analysisTrail = analysisTrail.dropLast(1)
        else goHome()
    }

    /** 分析結果のフォルダ(またはファイルのある場所)を、ファイル一覧で開く。 */
    fun openAnalyzed(node: DiskNode) {
        val s = screen as? Screen.Analyze ?: return
        val fsId = s.fsId ?: return
        val target = if (node.isDir) node.path else fsOf(fsId).parent(node.path) ?: return
        openDir(fsId, target)
    }

    /** ホームに出す件数・容量(画像 1934 件 など)を裏で数える。 */
    fun refreshHome() {
        homeJob?.cancel()
        val ctx = getApplication<Application>()
        homeJob = viewModelScope.launch {
            val dirs = volumes.map { it.dir }
            val trash = withContext(Dispatchers.IO) { runCatching { Trash.totalSize(dirs) }.getOrDefault(0L) }
            trashSize = trash
            val map = HashMap<Category, Pair<Int, Long>>()
            for (cat in Category.entries) {
                map[cat] = withContext(Dispatchers.IO) { runCatching { Media.summary(ctx, cat) }.getOrDefault(0 to 0L) }
                categorySummary = HashMap(map)
            }
        }
    }

    /** 引っ張って更新: 一覧を読み直し、ストレージの空き容量も取り直す。 */
    fun refreshList() {
        reload()
        refreshVolumesInBackground()
    }

    /** 空き容量の取得(ストレージへの問い合わせ)は画面を止めうるので、引っ張る動きの最中は裏で行う。 */
    fun refreshVolumesInBackground() {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { readVolumes(ctx) }.getOrNull() }
            if (list != null) volumes = list
        }
    }

    fun reload() {
        val s = screen
        loadJob?.cancel()
        if (s == Screen.Home || s == Screen.Network || s is Screen.Analyze || s is Screen.Settings) return
        loading = true
        val key = sortKey
        val asc = sortAsc
        val hidden = showHidden
        val ctx = getApplication<Application>()
        val dirs = volumes.map { it.dir }
        val startedMs = android.os.SystemClock.elapsedRealtime()
        loadJob = viewModelScope.launch {
            try {
                val (list, error) = withContext(Dispatchers.IO) {
                    try {
                        val l: List<FileItem>? = when (s) {
                            is Screen.Dir -> fsOf(s.fsId).list(s.path)
                                ?.filter { hidden || !it.name.startsWith(".") }
                                ?.let { FileOps.sort(it, key, asc) }
                            is Screen.Cat -> Media.query(ctx, s.cat)
                            Screen.Trash -> Trash.list(dirs).also { entries ->
                                trashOrigins = entries.associate { it.item.path to File(it.origin).parent.orEmpty() }
                            }.map { it.item }
                            else -> emptyList()
                        }
                        l to (if (l == null) "このフォルダは開けません" else null)
                    } catch (e: OpException) {
                        null to e.message
                    } catch (e: Exception) {
                        null to "読み込めませんでした: ${e.message}"
                    }
                }
                items = list ?: emptyList()
                listError = error
                // 動作の記録に、一覧を読むのにかかった時間を残す(場所の名前は残さない)
                val where = when (s) {
                    is Screen.Dir -> if (s.fsId == "local") "端末" else "NAS"
                    is Screen.Cat -> "分類"
                    else -> "ごみ箱"
                }
                PerfLog.event("一覧($where) ${items.size} 件 ${android.os.SystemClock.elapsedRealtime() - startedMs}ms")
            } finally {
                loading = false
            }
        }
    }

    /** ごみ箱の一覧に出す「元の場所」。 */
    fun trashOrigin(item: FileItem): String? = trashOrigins[item.path]

    // ---- 表示設定 ----

    fun setSort(key: SortKey, asc: Boolean) {
        sortKey = key
        sortAsc = asc
        prefs.edit().putString("sortKey", key.name).putBoolean("sortAsc", asc).apply()
        if (screen is Screen.Dir) items = FileOps.sort(items, key, asc)
    }

    /** 一覧をグリッド(サムネイルの並び)で表示するか。全フォルダ共通。 */
    var gridView by mutableStateOf(prefs.getBoolean("grid", false))
        private set

    /** NAS の画像・動画のサムネイルを読み込むか(通信が重いと感じるときに切れるようにしてある)。 */
    var nasThumbs by mutableStateOf(prefs.getBoolean("nasThumbs", true))
        private set

    /** 設定に「開発者向けオプション」を出しているか。バージョンを何回か押すと出る。 */
    var devMode by mutableStateOf(prefs.getBoolean("devMode", false))
        private set

    fun changeDevMode(on: Boolean) {
        devMode = on
        prefs.edit().putBoolean("devMode", on).apply()
    }

    /** 開発者向けオプションの一番上のスイッチ。オフの間は、中の機能がすべて止まる(各スイッチの選びは残す)。 */
    var devMaster by mutableStateOf(prefs.getBoolean("devMaster", true))
        private set

    /** 動作の記録のスイッチの選び。実際に記録するのは、これと一番上のスイッチが両方オンのとき。 */
    var perfLogOn by mutableStateOf(prefs.getBoolean("perfLogWanted", PerfLog.enabled))
        private set

    /** 動作の記録が、いま実際に動いているか。 */
    val perfLogActive: Boolean get() = devMaster && perfLogOn

    fun changeDevMaster(on: Boolean) {
        devMaster = on
        prefs.edit().putBoolean("devMaster", on).apply()
        PerfLog.setEnabled(perfLogActive)
    }

    fun setPerfLog(on: Boolean) {
        perfLogOn = on
        prefs.edit().putBoolean("perfLogWanted", on).apply()
        PerfLog.setEnabled(perfLogActive)
    }

    fun toggleNasThumbs() {
        nasThumbs = !nasThumbs
        prefs.edit().putBoolean("nasThumbs", nasThumbs).apply()
    }

    fun toggleGrid() {
        gridView = !gridView
        prefs.edit().putBoolean("grid", gridView).apply()
    }

    fun toggleHidden() {
        showHidden = !showHidden
        prefs.edit().putBoolean("showHidden", showHidden).apply()
        reload()
        searchQuery?.let { setQuery(it) }
    }

    /** 文書・電子書籍などを開いたときの文字の大きさ(sp)。開いたあとはビューアのメニューで変えられる。 */
    var viewerFontSp by mutableStateOf(prefs.getInt("viewerFontSp", 14).coerceIn(VIEWER_FONT_MIN, VIEWER_FONT_MAX))
        private set

    fun changeViewerFontSp(sp: Int) {
        viewerFontSp = sp.coerceIn(VIEWER_FONT_MIN, VIEWER_FONT_MAX)
        prefs.edit().putInt("viewerFontSp", viewerFontSp).apply()
    }

    fun toggleComicRtl() {
        comicRtl = !comicRtl
        prefs.edit().putBoolean("comicRtl", comicRtl).apply()
    }

    fun changeViewerTheme(mode: String) {
        viewerTheme = mode
        Pv.mode = mode
        prefs.edit().putString("viewerTheme", mode).apply()
    }

    fun changeTheme(mode: String) {
        themeMode = mode
        prefs.edit().putString("theme", mode).apply()
    }

    // ---- 選択 ----

    fun toggleSelect(item: FileItem) {
        selected = if (item.path in selected) selected - item.path else selected + item.path
    }

    fun selectAll() {
        selected = visible.map { it.path }.toSet()
    }

    fun invertSelection() {
        selected = visible.map { it.path }.toSet() - selected
    }

    fun clearSelection() {
        selected = emptySet()
    }

    /** 選択中の行。一覧の中身から拾うだけなので、毎回の描画で呼んでも軽い。 */
    fun selectedItems(): List<FileItem> = if (selected.isEmpty()) emptyList() else visible.filter { it.path in selected }

    // ---- 検索 ----

    val canSearch: Boolean get() = screen is Screen.Dir

    fun startSearch() {
        if (!canSearch) return
        selected = emptySet()
        searchQuery = ""
        results = emptyList()
    }

    fun closeSearch() {
        searchJob?.cancel()
        searchQuery = null
        results = emptyList()
        searching = false
    }

    fun setQuery(q: String) {
        searchQuery = q
        searchJob?.cancel()
        val d = screen as? Screen.Dir ?: return
        if (q.isBlank()) {
            results = emptyList()
            searching = false
            return
        }
        val hidden = showHidden
        searchJob = viewModelScope.launch {
            delay(300)
            searching = true
            try {
                results = withContext(Dispatchers.IO) {
                    try {
                        Transfer.search(fsOf(d.fsId), d.path, q, hidden, 500) { ensureActive() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        messages.tryEmit(e.message ?: "検索できませんでした")
                        emptyList()
                    }
                }
            } finally {
                searching = false
            }
        }
    }

    // ---- コピー・移動・貼り付け ----

    fun copySelected() = setClip(move = false)

    fun cutSelected() = setClip(move = true)

    private fun setClip(move: Boolean) {
        val its = selectedItems()
        if (its.isEmpty()) return
        val fsId = (screen as? Screen.Dir)?.fsId ?: "local"
        clip = Clip(fsId, its, move)
        selected = emptySet()
        val verb = if (move) "移動" else "コピー"
        messages.tryEmit("${its.size}件を選びました。${verb}先のフォルダを開いて、「貼り付け」を押してください")
    }

    fun cancelClip() {
        clip = null
    }

    /** いまのフォルダに貼り付けられるか(同じフォルダへの移動は不可)。 */
    val canPaste: Boolean
        get() {
            val c = clip ?: return false
            val d = screen as? Screen.Dir ?: return false
            if (c.move && c.fsId == d.fsId) {
                return c.items.any { fsOf(c.fsId).parent(it.path)?.let { p -> !sameDir(p, d.path) } ?: true }
            }
            return true
        }

    /** 貼り付け先に同名があるとき、ユーザーに選んでもらう途中の状態。conflicts の先頭が、いま聞いているもの。 */
    data class ConflictPrompt(
        val clip: Clip,
        val dest: Screen.Dir,
        val conflicts: List<Transfer.Conflict>,
        val decided: Map<String, ConflictAction>,
    )

    var conflictPrompt by mutableStateOf<ConflictPrompt?>(null)
        private set
    private var checkingConflicts = false

    fun paste() {
        val c = clip ?: return
        val d = screen as? Screen.Dir ?: return
        if (Ops.busy != null) {
            messages.tryEmit("別の処理が終わるまでお待ちください")
            return
        }
        if (checkingConflicts || conflictPrompt != null) return
        checkingConflicts = true
        // 同名の確認は、NAS だと通信が要るので裏で調べる
        viewModelScope.launch {
            try {
                val checked = withContext(Dispatchers.IO) {
                    runCatching { Transfer.conflicts(fsOf(c.fsId), c.items, fsOf(d.fsId), d.path) }
                }
                // 調べられなかったときは、何も壊さない「両方残す」で進める(黙って進めず、そのことを知らせる)
                checked.exceptionOrNull()?.let { messages.tryEmit("同じ名前のファイルを確認できませんでした。同じ名前のものは「名前 (1)」の形で貼り付けます") }
                val found = checked.getOrDefault(emptyList())
                if (found.isEmpty()) startPaste(c, d, emptyMap()) else conflictPrompt = ConflictPrompt(c, d, found, emptyMap())
            } finally {
                checkingConflicts = false
            }
        }
    }

    /** 同名の確認への答え。all=true なら、残りの同名すべてに同じ答えを使う。 */
    fun answerConflict(action: ConflictAction, all: Boolean) {
        val p = conflictPrompt ?: return
        val now = if (all) p.conflicts else listOf(p.conflicts.first())
        val decided = p.decided + now.associate { it.src.path to action }
        val rest = p.conflicts.drop(now.size)
        if (rest.isEmpty()) {
            conflictPrompt = null
            startPaste(p.clip, p.dest, decided)
        } else {
            conflictPrompt = p.copy(conflicts = rest, decided = decided)
        }
    }

    fun cancelConflict() {
        conflictPrompt = null
    }

    /** decisions: 同名があったものごとの答え(無いものは、あれば「両方残す」と同じ扱い)。 */
    private fun startPaste(c: Clip, d: Screen.Dir, decisions: Map<String, ConflictAction>) {
        val verb = if (c.move) "移動" else "コピー"
        val items = c.items.filter { decisions[it.path] != ConflictAction.SKIP }
        val skipped = c.items.size - items.size
        if (items.isEmpty()) {
            messages.tryEmit("すべてスキップしました")
            clip = null
            return
        }
        val progress = Progress(verb)
        // コピーも移動も、貼り付けが終わったら貼り付けバーを消す(続けて貼りたいときはもう一度コピーする)
        runOp("${verb}中…", onDone = { clip = null }, progress = progress) { check ->
            val src = fsOf(c.fsId)
            val dst = fsOf(d.fsId)
            progress.itemsTotal = items.size
            // 同じストレージ内の移動は名前を付け替えるだけで一瞬なので、全体の大きさは数えない
            val crossing = c.fsId != d.fsId || (src.isLocal && items.any { rootOfLocal(it.path).path != rootOfLocal(d.path).path })
            if (!c.move || crossing) progress.totalBytes = Transfer.stats(src, items, check).bytes
            val err = forEachSafe(items, progress) { item ->
                val overwrite = decisions[item.path] == ConflictAction.OVERWRITE
                if (c.move) Transfer.move(src, item, dst, d.path, check, progress, overwrite)
                else Transfer.copy(src, item, dst, d.path, check, progress, overwrite)
            }
            err ?: ("${items.size}件を${verb}しました" + if (skipped > 0) "(${skipped}件スキップ)" else "")
        }
    }

    // ---- 削除・ごみ箱 ----

    /** 端末内のファイルは「ごみ箱へ」、NAS やごみ箱の中のものは完全に削除する。 */
    val deletesToTrash: Boolean get() = itemFs.isLocal && screen != Screen.Trash

    fun deleteSelected() {
        val its = selectedItems()
        if (its.isEmpty()) return
        val s = screen
        val fs = itemFs
        val toTrash = deletesToTrash
        // ごみ箱へは名前の付け替えで一瞬。完全削除(NAS など)は時間がかかることがあるので守る
        runOp(if (toTrash) "ごみ箱へ移動中…" else "削除中…", keepAlive = !toTrash) { check ->
            val err = forEachSafe(its) {
                check()
                when {
                    s == Screen.Trash -> Trash.deletePermanently(File(it.path))
                    toTrash && !Trash.isInTrash(it.path) -> {
                        val root = rootOfLocal(it.path)
                        if (root.path == "/") FileOps.delete(File(it.path)) else Trash.moveToTrash(root, File(it.path))
                    }
                    else -> fs.delete(it.path)
                }
            }
            err ?: "${its.size}件を${if (toTrash) "ごみ箱に移動" else "削除"}しました"
        }
    }

    fun restoreSelected() {
        val its = selectedItems()
        if (its.isEmpty()) return
        runOp("元に戻しています…") { check ->
            val err = forEachSafe(its) { check(); Trash.restore(File(it.path)) }
            err ?: "${its.size}件を元に戻しました"
        }
    }

    fun emptyTrash() {
        val dirs = volumes.map { it.dir }
        runOp("ごみ箱を空にしています…", keepAlive = true) { Trash.empty(dirs); "ごみ箱を空にしました" }
    }

    // ---- 名前・フォルダ・ZIP ----

    fun renameSelected(newName: String) {
        val item = selectedItems().singleOrNull() ?: return
        val fs = itemFs
        runOp("名前を変更中…") { fs.rename(item.path, newName.trim()); "名前を変更しました" }
    }

    fun newFolder(name: String) {
        val d = screen as? Screen.Dir ?: return
        val fs = fsOf(d.fsId)
        runOp("作成中…") {
            val n = name.trim()
            FileOps.validateName(n)?.let { throw OpException(it) }
            val p = fs.child(d.path, n)
            if (fs.exists(p)) throw OpException("同じ名前のファイルがすでにあります")
            fs.mkdir(p)
            "フォルダを作成しました"
        }
    }

    fun zipSelected(name: String) {
        val its = selectedItems()
        val d = screen as? Screen.Dir
        if (its.isEmpty() || d == null) return
        val n = name.trim().let { if (it.endsWith(".zip", ignoreCase = true)) it else "$it.zip" }
        val progress = Progress("圧縮")
        val fs = fsOf(d.fsId)
        runOp("圧縮中…", progress = progress) { check ->
            FileOps.validateName(n)?.let { throw OpException(it) }
            progress.itemsTotal = its.size
            progress.totalBytes = Transfer.stats(fs, its, check).bytes
            if (fs.isLocal) FileOps.zip(its.map { File(it.path) }, File(d.path, n), check, progress)
            else Transfer.zip(fs, its, fs.child(d.path, n), check, progress)
            progress.itemsDone = its.size
            "ZIPを作成しました"
        }
    }

    /** .xapk / .apkm / .apks を、この端末に合う部品を選んで、まとめてインストールする(確認画面まで進める)。 */
    fun installBundle(fs: Fs, item: FileItem) {
        val progress = Progress("インストールの準備").also { it.itemsTotal = 1 }
        runOp("インストールの準備中…", progress = progress, keepAlive = true) { check ->
            BundleInstaller.install(getApplication(), fs, item, check, progress)
        }
    }

    fun unzipSelected() {
        selectedItems().singleOrNull()?.let { unzipItem(it) }
    }

    /** ZIP を、同じ場所の「拡張子を除いた名前」のフォルダに展開する。 */
    fun unzipItem(z: FileItem) {
        val fs = itemFs
        val progress = Progress("展開").also { it.itemsTotal = 1 }
        runOp("展開中…", progress = progress) { check ->
            val outName = if (Archives.isOtherArchive(z.name)) Transfer.extractArchive(fs, z, getApplication<Application>().cacheDir, check, progress).substringAfterLast('/')
            else if (fs.isLocal) FileOps.unzip(File(z.path), check, progress).name
            else Transfer.unzip(fs, z, check, progress).substringAfterLast('/')
            progress.itemsDone = 1
            "「$outName」に展開しました"
        }
    }

    // ---- 他のアプリから受け取った共有の保存 ----

    /** 他のアプリの「共有」から受け取って、保存先を選んでもらっているもの。 */
    var incoming by mutableStateOf<List<IncomingFile>?>(null)
        private set

    fun receiveShared(files: List<IncomingFile>) {
        incoming = files.takeIf { it.isNotEmpty() }
    }

    fun cancelIncoming() {
        incoming = null
    }

    /** いま開いているフォルダに、受け取ったものを保存する。 */
    fun saveIncoming() {
        val files = incoming ?: return
        val d = screen as? Screen.Dir ?: run {
            messages.tryEmit("保存先のフォルダを開いてください")
            return
        }
        if (Ops.busy != null) {
            messages.tryEmit("別の処理が終わるまでお待ちください")
            return
        }
        incoming = null
        val progress = Progress("保存").also {
            it.itemsTotal = files.size
            // 大きさが1つでも分からないときは、全体の割合を出さない(動き続けるバーにする)
            if (files.all { f -> f.size >= 0 }) it.totalBytes = files.sumOf { f -> f.size }
        }
        val cr = getApplication<Application>().contentResolver
        runOp("保存中…", progress = progress) { check ->
            val fs = fsOf(d.fsId)
            // 共有元から渡されたアクセス権は、アプリを閉じると取り消されることがある。先に全部開いておけば、開いたものは読み続けられる
            val opened = files.map { f ->
                f to runCatching {
                    if (f.text != null) java.io.ByteArrayInputStream(f.text.toByteArray()) else cr.openInputStream(f.uri!!)
                }.getOrNull()
            }
            try {
                var failed = 0
                var first: String? = null
                for ((f, input) in opened) {
                    try {
                        check()
                        if (input == null) throw OpException("「${f.name}」を開けませんでした")
                        input.use { Transfer.importStream(fs, d.path, f.name, it, check, progress) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed++
                        if (first == null) first = e.message ?: f.name
                    }
                    progress.itemsDone++
                }
                if (failed == 0) "${files.size}件を保存しました" else "${failed}件失敗しました: $first"
            } finally {
                opened.forEach { runCatching { it.second?.close() } }
            }
        }
    }

    // ---- 開く・共有 ----

    /** アプリ内ビューアで開いているもの。files のうち index 番目を表示する(画像は左右にめくれる)。 */
    data class ViewerRequest(val fs: Fs, val files: List<FileItem>, val index: Int)

    var viewer by mutableStateOf<ViewerRequest?>(null)
        private set

    /** 一覧に出ているファイルの Fs(サムネイルの読み込み用)。 */
    val listFs: Fs get() = itemFs

    /** NAS のファイルは、読んだ量を数える Fs に包んで渡す(ビューアが「読み込み中 ○○ / ○○」を出せる)。端末内はそのまま。 */
    private fun request(fs: Fs, files: List<FileItem>, index: Int) =
        ViewerRequest(if (fs.isLocal) fs else TrackedFs(fs, files), files, index)

    /** 画像とテキストはアプリ内のビューアで、それ以外は従来どおり他のアプリで開く。 */
    fun openOrView(item: FileItem) {
        val fs = itemFs
        when {
            item.kind == FileKind.IMAGE -> {
                // いま一覧に出ている画像を、並び順のまま左右にめくれるようにする
                val images = visible.filter { !it.isDir && it.kind == FileKind.IMAGE }
                val i = images.indexOfFirst { it.path == item.path }
                if (i >= 0) viewer = request(fs, images, i) else openItem(item)
            }
            isPlayable(item.kind) -> {
                // 同じ種類(動画は動画、音声は音声)を、並び順のまま前後に送れるようにする
                // MIDI(端末の別の再生エンジンで鳴らす)と、それ以外は、別々の再生リストにする
                val same = visible.filter { !it.isDir && it.kind == item.kind && isMidiName(it.name) == isMidiName(item.name) }
                val i = same.indexOfFirst { it.path == item.path }
                if (i >= 0) viewer = request(fs, same, i) else openItem(item)
            }
            PreviewKind.of(item.name) != null -> viewer = request(fs, listOf(item), 0)
            // 種類の分からないファイルは、中身を見て(文字なら文字で、そうでなければ種類の推測を出して)開く
            item.kind == FileKind.OTHER -> viewer = request(fs, listOf(item), 0)
            else -> openItem(item)
        }
    }

    fun closeViewer() {
        viewer = null
    }

    /** fsOverride: 一覧とは別の場所(ZIP から取り出した一時ファイルなど)のファイルを開くとき。 */
    fun openItem(item: FileItem, fsOverride: Fs? = null) {
        val fs = fsOverride ?: itemFs
        if (fs.isLocal) {
            openRequests.tryEmit(File(item.path))
            return
        }
        val progress = Progress("ダウンロード").also { it.itemsTotal = 1; it.totalBytes = item.size }
        runOp("ダウンロード中…", progress = progress) { check ->
            openRequests.tryEmit(download(fs, item, check, progress))
            progress.itemsDone = 1
            null
        }
    }

    fun shareSelected() = shareItems(selectedItems())

    fun shareItems(items: List<FileItem>, fsOverride: Fs? = null) {
        val fs = fsOverride ?: itemFs
        val files = items.filter { !it.isDir }
        if (files.isEmpty()) {
            messages.tryEmit("フォルダは共有できません。ZIPに圧縮してから共有してください")
            return
        }
        if (fs.isLocal) {
            shareRequests.tryEmit(files.map { File(it.path) })
            return
        }
        val progress = Progress("ダウンロード").also { it.itemsTotal = files.size; it.totalBytes = files.sumOf { f -> f.size } }
        runOp("ダウンロード中…", progress = progress) { check ->
            shareRequests.tryEmit(files.map { download(fs, it, check, progress).also { progress.itemsDone++ } })
            null
        }
    }

    /** NAS のファイルを、他のアプリに渡せるよう端末の一時領域に取り込む。 */
    private fun download(fs: Fs, item: FileItem, check: () -> Unit, progress: Progress): File {
        val dir = File(getApplication<Application>().cacheDir, "remote/${UUID.randomUUID()}")
        if (!dir.mkdirs()) throw OpException("一時フォルダを作成できませんでした")
        val out = File(dir, item.name)
        progress.begin(item.path, out.path)
        fs.open(item.path).use { i -> out.outputStream().use { o -> Transfer.pump(i, o, check, progress) } }
        if (item.modified > 0) out.setLastModified(item.modified)
        return out
    }

    fun statsOf(its: List<FileItem>): Stats = Transfer.stats(itemFs, its)

    // ---- ブックマーク ----

    fun addBookmark(item: FileItem) {
        if (!item.isDir) return
        val fsId = (screen as? Screen.Dir)?.fsId ?: "local"
        val p = Place(fsId, item.path)
        if (p !in bookmarks) {
            bookmarks = bookmarks + p
            writePlaces("bookmarks", bookmarks)
        }
        messages.tryEmit("ブックマークに追加しました")
        selected = emptySet()
    }

    fun removeBookmark(p: Place) {
        bookmarks = bookmarks - p
        writePlaces("bookmarks", bookmarks)
    }

    fun clearHistory() {
        history = emptyList()
        writePlaces("history", history)
    }

    // ---- NAS の接続先 ----

    fun newRemoteId(): String = UUID.randomUUID().toString().take(8)

    fun saveRemote(cfg: RemoteConfig) {
        val isNew = remotes.none { it.id == cfg.id }
        remotes = if (isNew) remotes + cfg else remotes.map { if (it.id == cfg.id) cfg else it }
        // 新しく追加した接続先は、ドロワーに固定して見つけやすくする(不要なら固定を外せる)
        if (isNew) pin(Place("smb:${cfg.id}", ""))
        RemoteStore.save(getApplication(), remotes)
        smbCache.remove("smb:${cfg.id}")?.close()
    }

    fun deleteRemote(id: String) {
        remotes = remotes.filter { it.id != id }
        RemoteStore.save(getApplication(), remotes)
        smbCache.remove("smb:$id")?.close()
        val fsId = "smb:$id"
        bookmarks = bookmarks.filter { it.fsId != fsId }
        homeShortcuts.filter { it.fsId == fsId }.forEach { removeHomeShortcut(it.id) }
        history = history.filter { it.fsId != fsId }
        pinned = pinned.filter { it.fsId != fsId }
        opened = opened.filter { it.fsId != fsId }
        writePlaces("bookmarks", bookmarks)
        writePlaces("history", history)
        writePlaces("pinned", pinned)
        writePlaces("opened", opened)
    }

    /** アプリが裏から戻ったとき。使っていない NAS 接続は切れていることが多いので捨てて、次の操作でつなぎ直す。 */
    fun recycleConnections() {
        if (Ops.busy != null) return
        smbCache.values.forEach { it.markStale() }
    }

    /** 保存前の接続確認。成功すれば共有直下の項目数を返す(失敗は OpException)。 */
    fun testRemote(cfg: RemoteConfig): Int {
        val fs = SmbFs(cfg)
        try {
            return fs.test()
        } finally {
            fs.close()
        }
    }

    // ---- 共通 ----

    fun cancelOp() {
        Ops.cancel()
    }

    private fun forEachSafe(files: List<FileItem>, progress: Progress? = null, action: (FileItem) -> Unit): String? {
        var failed = 0
        var first: String? = null
        for (f in files) {
            try {
                action(f)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                if (first == null) first = e.message ?: f.name
            }
            if (progress != null) progress.itemsDone++
        }
        return if (failed == 0) null else "${failed}件失敗しました: $first"
    }

    fun hideProgress() {
        Ops.progressHidden = true
    }

    fun showProgress() {
        Ops.progressHidden = false
    }

    /**
     * 重い処理を裏で実行する。block が返した文が結果メッセージになる。
     * progress を渡すと、4分の1秒ごとに進み具合(割合・速度・経過時間)を progressView に反映する。
     * 実行は画面ではなく Ops の中で行うので、アプリを閉じても続く。keepAlive のときは、通知付きのサービスで OS に止められないよう守る。
     */
    private fun runOp(
        msg: String,
        onDone: () -> Unit = {},
        progress: Progress? = null,
        keepAlive: Boolean = progress != null,
        block: (check: () -> Unit) -> String?,
    ) {
        if (Ops.busy != null) {
            messages.tryEmit("別の処理が終わるまでお待ちください")
            return
        }
        Ops.busy = msg
        Ops.progressHidden = false
        Ops.lastResult = null
        Ops.progressView = progress?.let { Ops.snapshot(it, 0L) }
        val ticker = progress?.let { p ->
            Ops.scope.launch {
                var lastTime = System.currentTimeMillis()
                var lastDone = 0L
                var speed = 0.0
                while (true) {
                    delay(250)
                    val now = System.currentTimeMillis()
                    val done = p.doneBytes
                    val instant = (done - lastDone) * 1000.0 / maxOf(1L, now - lastTime)
                    // 瞬間の値は揺れるので、直前の値と混ぜてなだらかにする
                    speed = if (speed == 0.0) instant else speed * 0.7 + instant * 0.3
                    lastTime = now
                    lastDone = done
                    Ops.progressView = Ops.snapshot(p, speed.toLong())
                }
            }
        }
        if (keepAlive) Ops.startService(getApplication())
        Ops.job = Ops.scope.launch {
            var outcome: String? = null
            try {
                outcome = withContext(Dispatchers.IO) { block { ensureActive() } }
            } catch (e: CancellationException) {
                outcome = "中止しました"
            } catch (e: OpException) {
                outcome = e.message ?: "失敗しました"
            } catch (e: Exception) {
                outcome = "失敗しました: ${e.message}"
            } finally {
                ticker?.cancel()
                outcome?.let { messages.tryEmit(it) }
                Ops.lastResult = outcome
                Ops.progressView = null
                Ops.progressHidden = false
                onDone()
                selected = emptySet()
                reload()
                refreshVolumes()
                Ops.busy = null
                Ops.runIdleHooks()
            }
        }
    }

    override fun onCleared() {
        // 画面を閉じたあとも続いている操作が NAS 接続を使っているので、終わるまで閉じない
        Ops.whenIdle { smbCache.values.forEach { it.close() } }
        super.onCleared()
    }
}
