package local.filemanager

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

// ---------------------------------------------------------------- 読み込み・保存

private sealed interface EditLoad {
    data object Busy : EditLoad

    /** stamp は読み込んだときの (大きさ, 更新日時)。保存の前に、ほかで変わっていないかの確認に使う。 */
    class Ok(val doc: EditableText, val stamp: Pair<Long, Long>?) : EditLoad
    class Fail(val message: String) : EditLoad
}

private sealed interface SaveResult {
    class Saved(val stamp: Pair<Long, Long>?) : SaveResult

    /** 読み込んだあとに、ファイルがほかで変わっていた */
    data object Changed : SaveResult
}

private fun stampOf(fs: Fs, path: String): Pair<Long, Long>? = runCatching { fs.stat(path) }.getOrNull()?.let { it.size to it.modified }

private fun loadForEdit(fs: Fs, item: FileItem): EditLoad = try {
    val stamp = stampOf(fs, item.path)
    val (bytes, truncated) = fs.open(item.path).use { TextDecode.readUpTo(it, TextFile.MAX_BYTES) }
    if (truncated) throw OpException("大きすぎて編集できません(${TextFile.MAX_BYTES / 1024 / 1024} MB まで)")
    if (Plist.isBinary(bytes)) throw OpException("文字のファイルではないので編集できません")
    EditLoad.Ok(TextFile.parse(bytes), stamp)
} catch (e: Exception) {
    EditLoad.Fail(e.message ?: "読み込めません")
}

/** 端末内は、別の名前で書いてから置き換える(途中で失敗しても元のファイルを壊さない)。NAS はそのまま上書きする。 */
private fun writeBack(fs: Fs, item: FileItem, text: String, enc: TextEnc, eol: Eol, stamp: Pair<Long, Long>?, force: Boolean): SaveResult {
    val bytes = TextFile.encode(text, enc, eol)
    if (!force && stamp != null && stampOf(fs, item.path) != stamp) return SaveResult.Changed
    if (fs.isLocal) {
        val f = File(item.path)
        val tmp = File(f.parentFile, f.name + ".filer-tmp")
        val replaced = runCatching {
            tmp.outputStream().use { it.write(bytes) }
            if (!tmp.renameTo(f)) throw java.io.IOException("rename")
        }.isSuccess
        if (!replaced) {
            runCatching { tmp.delete() }
            f.outputStream().use { it.write(bytes) }
        }
    } else {
        fs.create(item.path).use { it.write(bytes) }
    }
    return SaveResult.Saved(stampOf(fs, item.path))
}

// ---------------------------------------------------------------- 画面

/** 文字のファイルを編集する画面。画面全体に重ねて表示する。保存したら onSaved、閉じるときは onClose。 */
@Composable
internal fun TextEditorScreen(fs: Fs, item: FileItem, fontSp: Int, onFontSp: (Int) -> Unit, onSaved: () -> Unit, onClose: () -> Unit) {
    val load by produceState<EditLoad>(EditLoad.Busy, item.path) { value = withContext(Dispatchers.IO) { loadForEdit(fs, item) } }
    BackHandler(enabled = load !is EditLoad.Ok) { onClose() }
    Box(Modifier.fillMaxSize().background(Pv.bg).pointerInput(Unit) { detectTapGestures { } }) {
        when (val l = load) {
            EditLoad.Busy -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Pv.text)
            is EditLoad.Fail -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(l.message, color = Pv.text)
                TextButton(onClick = onClose) { Text("戻る") }
            }
            is EditLoad.Ok -> EditorBody(fs, item, l.doc, l.stamp, fontSp, onFontSp, onSaved, onClose)
        }
    }
}

/** 検索で見つけた所に色を付ける(文字の中身は変えない)。 */
private class MarkTransformation(private val range: IntRange?, private val bg: Color, private val fg: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val r = range
        if (r == null || r.first >= text.length) return TransformedText(text, OffsetMapping.Identity)
        val marked = buildAnnotatedString {
            append(text)
            addStyle(SpanStyle(background = bg, color = fg), r.first, (r.last + 1).coerceAtMost(text.length))
        }
        return TransformedText(marked, OffsetMapping.Identity)
    }
}

@Composable
private fun EditorBody(
    fs: Fs, item: FileItem, doc: EditableText, stamp0: Pair<Long, Long>?,
    fontSp: Int, onFontSp: (Int) -> Unit, onSaved: () -> Unit, onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var tf by remember { mutableStateOf(TextFieldValue(doc.text)) }
    // 最後に保存した(読み込んだ)内容。これと違えば「変更あり」
    var baseText by remember { mutableStateOf(doc.text) }
    var baseEnc by remember { mutableStateOf(doc.enc) }
    var baseEol by remember { mutableStateOf(doc.eol) }
    var enc by remember { mutableStateOf(doc.enc) }
    var eol by remember { mutableStateOf(doc.eol) }
    var stamp by remember { mutableStateOf(stamp0) }
    val history = remember { UndoHistory() }
    // 元に戻す・やり直しの有効・無効を描き直すための合図
    var rev by remember { mutableIntStateOf(0) }
    val modified = tf.text != baseText || enc != baseEnc || eol != baseEol
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var askExit by remember { mutableStateOf(false) }
    var overwriteClose by remember { mutableStateOf<Boolean?>(null) }
    var encDialog by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    var finding by remember { mutableStateOf(false) }
    var findText by remember { mutableStateOf("") }
    var replText by remember { mutableStateOf("") }
    var findNote by remember { mutableStateOf("") }
    var matchRange by remember { mutableStateOf<IntRange?>(null) }
    var jump by remember { mutableIntStateOf(0) }
    val layout = remember { arrayOfNulls<TextLayoutResult>(1) }
    val scroll = rememberScrollState()

    fun snap(v: TextFieldValue) = UndoHistory.Snap(v.text, v.selection.start, v.selection.end)

    fun restore(s: UndoHistory.Snap) {
        tf = TextFieldValue(s.text, TextRange(s.start.coerceAtMost(s.text.length), s.end.coerceAtMost(s.text.length)))
        matchRange = null
        rev++
    }

    /** 検索・置換など、プログラムから文字を入れ替える。それだけで元に戻す 1 回分にする。 */
    fun replaceWhole(text: String, cursor: Int) {
        history.changed(snap(tf), System.currentTimeMillis(), own = true)
        tf = TextFieldValue(text, TextRange(cursor.coerceIn(0, text.length)))
        matchRange = null
        rev++
    }

    fun select(i: Int) {
        if (i < 0) {
            findNote = "見つかりません"
            matchRange = null
            return
        }
        findNote = ""
        matchRange = i until i + findText.length
        tf = tf.copy(selection = TextRange(i, i + findText.length))
        jump++
    }

    fun save(thenClose: Boolean, force: Boolean = false) {
        if (saving) return
        saving = true
        val text = tf.text
        val e = enc
        val l = eol
        val st = stamp
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { writeBack(fs, item, text, e, l, st, force) } }
            saving = false
            r.onSuccess { res ->
                when (res) {
                    is SaveResult.Saved -> {
                        baseText = text
                        baseEnc = e
                        baseEol = l
                        stamp = res.stamp
                        onSaved()
                        if (thenClose) onClose()
                    }
                    SaveResult.Changed -> overwriteClose = thenClose
                }
            }.onFailure { message = "保存できなかった\n" + (it.message ?: it.javaClass.simpleName) }
        }
    }

    fun requestClose() {
        if (saving) return
        if (finding) {
            finding = false
            matchRange = null
        } else if (modified) {
            askExit = true
        } else {
            onClose()
        }
    }
    BackHandler { requestClose() }

    // 見つけた所までスクロールする(入力欄に触れていないときは、自動では動かないため)
    LaunchedEffect(jump) {
        val l = layout[0]
        val r = matchRange
        if (jump > 0 && l != null && r != null) {
            val line = l.getLineForOffset(r.first.coerceIn(0, l.layoutInput.text.length))
            scroll.scrollTo((l.getLineTop(line) - 160f).toInt().coerceAtLeast(0))
        }
    }

    val mark = remember(matchRange, Pv.dark) { MarkTransformation(matchRange, Color(0xCCFFB300), Color.Black) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        // 上のバー
        Row(Modifier.fillMaxWidth().background(Pv.bar).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { requestClose() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る", tint = Pv.barText) }
            Column(Modifier.weight(1f)) {
                Text(item.name, color = Pv.barText, fontSize = 16.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(if (modified) "未保存の変更あり" else "編集中", color = if (modified) Pv.warn else Pv.barSub, fontSize = 12.sp)
            }
            val canUndo = rev >= 0 && history.canUndo
            val canRedo = rev >= 0 && history.canRedo
            IconButton(onClick = { history.undo(snap(tf))?.let { restore(it) } }, enabled = canUndo) {
                Icon(Icons.AutoMirrored.Filled.Undo, "元に戻す", tint = if (canUndo) Pv.barText else Pv.barSub.copy(alpha = 0.4f))
            }
            IconButton(onClick = { history.redo(snap(tf))?.let { restore(it) } }, enabled = canRedo) {
                Icon(Icons.AutoMirrored.Filled.Redo, "やり直す", tint = if (canRedo) Pv.barText else Pv.barSub.copy(alpha = 0.4f))
            }
            IconButton(onClick = { save(false) }, enabled = modified && !saving) {
                Icon(Icons.Default.Save, "保存", tint = if (modified && !saving) Pv.accent else Pv.barSub.copy(alpha = 0.4f))
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "メニュー", tint = Pv.barText) }
                ViewerMenu(
                    expanded = menu, dark = Pv.dark, onDismiss = { menu = false }, onOpenExternal = {}, onShare = {}, tiles = false,
                    sections = listOf(
                        VMenuSection(
                            "編集",
                            listOf(
                                VMenuItem.Action(Icons.Default.Search, "検索・置換") { finding = true },
                                VMenuItem.Action(Icons.Default.Translate, "文字コード: ${enc.label}") { encDialog = true },
                                VMenuItem.Segment("改行コード", Eol.entries.map { it.name }, eol.ordinal) { eol = Eol.entries[it] },
                            ),
                        ),
                        VMenuSection("表示", listOf(VMenuItem.TextSize(fontSp, VIEWER_FONT_MIN, VIEWER_FONT_MAX, onFontSp))),
                    ),
                )
            }
        }
        // 入力欄(余白も押すと入力できるよう、見えている高さ以上に広げる)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val minH = maxHeight
            Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
                BasicTextField(
                    value = tf,
                    onValueChange = { nv ->
                        if (nv.text != tf.text) {
                            history.changed(snap(tf), System.currentTimeMillis(), own = abs(nv.text.length - tf.text.length) > 30)
                            rev++
                            matchRange = null
                        }
                        tf = nv
                    },
                    textStyle = TextStyle(color = Pv.text, fontSize = fontSp.sp, fontFamily = FontFamily.Monospace, lineHeight = (fontSp * 1.45f).sp),
                    cursorBrush = SolidColor(Pv.accent),
                    visualTransformation = mark,
                    onTextLayout = { layout[0] = it },
                    keyboardOptions = KeyboardOptions(autoCorrect = false),
                    modifier = Modifier.fillMaxWidth().heightIn(min = minH),
                    decorationBox = { inner -> Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 72.dp)) { inner() } },
                )
            }
        }
        // 検索・置換
        if (finding) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            val total = remember(tf.text, findText) { TextFind.count(tf.text, findText) }
            Column(Modifier.fillMaxWidth().background(Pv.searchBg).padding(horizontal = 8.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FindField(
                        findText, "検索する言葉", { findText = it; findNote = ""; matchRange = null },
                        Modifier.weight(1f).focusRequester(focus), ImeAction.Search,
                    ) { select(TextFind.next(tf.text, findText, tf.selection.max)) }
                    Text(
                        if (findText.isEmpty()) "" else findNote.ifEmpty { "${total}件" },
                        color = Pv.barText, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    IconButton(onClick = { select(TextFind.prev(tf.text, findText, tf.selection.min)) }) { Icon(Icons.Default.KeyboardArrowUp, "前へ", tint = Pv.barText) }
                    IconButton(onClick = { select(TextFind.next(tf.text, findText, tf.selection.max)) }) { Icon(Icons.Default.KeyboardArrowDown, "次へ", tint = Pv.barText) }
                    IconButton(onClick = { finding = false; matchRange = null }) { Icon(Icons.Default.Close, "閉じる", tint = Pv.barText) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FindField(replText, "置き換える言葉", { replText = it }, Modifier.weight(1f), ImeAction.Done) {}
                    TextButton(
                        enabled = findText.isNotEmpty(),
                        onClick = {
                            val r = matchRange
                            if (r != null && r.last < tf.text.length && tf.text.regionMatches(r.first, findText, 0, findText.length, ignoreCase = true)) {
                                val nt = tf.text.replaceRange(r.first, r.last + 1, replText)
                                val at = r.first + replText.length
                                replaceWhole(nt, at)
                                select(TextFind.next(nt, findText, at))
                            } else {
                                select(TextFind.next(tf.text, findText, tf.selection.min))
                            }
                        },
                    ) { Text("置換", color = Pv.barText) }
                    TextButton(
                        enabled = findText.isNotEmpty(),
                        onClick = {
                            val (nt, n) = TextFind.replaceAll(tf.text, findText, replText)
                            if (n == 0) {
                                findNote = "見つかりません"
                            } else {
                                replaceWhole(nt, tf.selection.start)
                                findNote = "${n}件置換しました"
                            }
                        },
                    ) { Text("すべて", color = Pv.barText) }
                }
            }
        }
        // 今の位置・文字コード・改行
        val text = tf.text
        val totalLines = remember(text) { 1 + text.count { it == '\n' } }
        val pos = tf.selection.start.coerceIn(0, text.length)
        var lineNo = 1
        for (i in 0 until pos) if (text[i] == '\n') lineNo++
        val col = pos - (text.lastIndexOf('\n', pos - 1) + 1) + 1
        val tail = if (doc.mixedEol && eol == baseEol) "・改行がまざっていました(そろえて保存します)" else ""
        Text(
            "行 $lineNo / $totalLines・桁 $col・${enc.label}・${eol.name}$tail",
            color = Pv.barSub, fontSize = 12.sp, maxLines = 1,
            modifier = Modifier.fillMaxWidth().background(Pv.bar).padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }

    if (askExit) {
        AlertDialog(
            onDismissRequest = { askExit = false },
            title = { Text("保存していない変更があります") },
            confirmButton = { TextButton(onClick = { askExit = false; save(true) }) { Text("保存して閉じる") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { askExit = false; onClose() }) { Text("保存しません") }
                    TextButton(onClick = { askExit = false }) { Text("続ける") }
                }
            },
        )
    }
    if (encDialog) {
        AlertDialog(
            onDismissRequest = { encDialog = false },
            title = { Text("保存するときの文字コード") },
            confirmButton = { TextButton(onClick = { encDialog = false }) { Text("閉じる") } },
            text = {
                Column {
                    for (e in TextEnc.entries) {
                        Row(Modifier.fillMaxWidth().clickable { enc = e; encDialog = false }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = enc == e, onClick = null)
                            Spacer(Modifier.width(10.dp))
                            Text(e.label)
                        }
                    }
                    Text("読み込んだときは ${baseEnc.label}。変えて保存すると、そのコードでファイルが書き換わります", fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
            },
        )
    }
    overwriteClose?.let { thenClose ->
        AlertDialog(
            onDismissRequest = { overwriteClose = null },
            title = { Text("ファイルが変わっています") },
            text = { Text("読み込んだあとに、ほかでこのファイルが書き換えられました。上書きすると、その変更は消えます") },
            confirmButton = { TextButton(onClick = { overwriteClose = null; save(thenClose, force = true) }) { Text("上書きする") } },
            dismissButton = { TextButton(onClick = { overwriteClose = null }) { Text("やめる") } },
        )
    }
    message?.let { m ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(m) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("閉じる") } },
        )
    }
}

@Composable
private fun FindField(value: String, hint: String, onChange: (String) -> Unit, modifier: Modifier, ime: ImeAction, onAction: () -> Unit) {
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = TextStyle(color = Pv.barText, fontSize = 16.sp), cursorBrush = SolidColor(Pv.barText),
        keyboardOptions = KeyboardOptions(imeAction = ime), keyboardActions = KeyboardActions(onSearch = { onAction() }, onDone = { onAction() }),
        modifier = modifier.background(Pv.searchField, RoundedCornerShape(6.dp)).padding(10.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) Text(hint, color = Color.Gray, fontSize = 16.sp)
            inner()
        },
    )
}
