package local.filemanager

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------- 読み込み

private sealed interface CsvLoad {
    data object Busy : CsvLoad
    class Ok(val doc: EditableText, val table: Csv.Table, val stamp: Pair<Long, Long>?) : CsvLoad
    class Fail(val message: String) : CsvLoad
}

/** 文字のファイルとして読み込み(文字コード・改行も覚える)、表に分ける。表で編集できない大きさ・形のときは Fail。 */
private fun loadCsv(fs: Fs, item: FileItem): CsvLoad = when (val l = loadForEdit(fs, item)) {
    is EditLoad.Ok -> {
        val ext = FileOps.extensionOf(item.name)
        val table = Csv.parse(l.doc.text, Csv.detectDelimiter(l.doc.text, ext))
        if (table.truncated) CsvLoad.Fail("行が多すぎて、表では編集できません(5万行まで)。「テキストとして編集」を使ってください")
        else CsvLoad.Ok(l.doc, table, l.stamp)
    }
    is EditLoad.Fail -> CsvLoad.Fail(l.message)
    EditLoad.Busy -> CsvLoad.Busy
}

private fun delimiterLabel(d: Char) = when (d) {
    ',' -> "カンマ区切り"
    '\t' -> "タブ区切り"
    ';' -> "セミコロン区切り"
    '|' -> "縦線区切り"
    else -> "区切り「$d」"
}

// ---------------------------------------------------------------- 画面

/**
 * CSV・TSV を、Excel のような表で編集する画面(超簡易版)。セルを選んで、上の入力欄で書き換える。
 * 行・列の挿入と削除、元に戻す・やり直し、保存ができる。文字コード・改行・区切り文字は、読み込んだときのまま保存する。
 */
@Composable
internal fun CsvEditorScreen(fs: Fs, item: FileItem, headerOn: Boolean, fontSp: Int, onSaved: () -> Unit, onClose: () -> Unit) {
    val load by produceState<CsvLoad>(CsvLoad.Busy, item.path) { value = withContext(Dispatchers.IO) { loadCsv(fs, item) } }
    BackHandler(enabled = load !is CsvLoad.Ok) { onClose() }
    Box(Modifier.fillMaxSize().background(Pv.bg).pointerInput(Unit) { detectTapGestures { } }) {
        when (val l = load) {
            CsvLoad.Busy -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Pv.text)
            is CsvLoad.Fail -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(l.message, color = Pv.text)
                TextButton(onClick = onClose) { Text("戻る") }
            }
            is CsvLoad.Ok -> CsvEditorBody(fs, item, l.doc, l.table, l.stamp, headerOn, fontSp, onSaved, onClose)
        }
    }
}

@Composable
private fun CsvEditorBody(
    fs: Fs, item: FileItem, doc: EditableText, table: Csv.Table, stamp0: Pair<Long, Long>?,
    headerOn: Boolean, fontSp: Int, onSaved: () -> Unit, onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheet = remember { CsvSheet(table.rows) }
    val delimiter = table.delimiter
    // 元のファイルが最後に改行で終わっていたか(終わっていなければ、保存でも付けない)
    val trailingEol = remember { doc.text.endsWith("\n") }
    // 表が変わるたびに進める(描き直しの合図)
    var rev by remember { mutableIntStateOf(0) }
    // 最後に保存した(読み込んだ)ときの表。これと違えば「変更あり」
    var savedRows by remember { mutableStateOf(sheet.rows) }
    var stamp by remember { mutableStateOf(stamp0) }
    var sel by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var draft by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var askExit by remember { mutableStateOf(false) }
    var overwriteClose by remember { mutableStateOf<Boolean?>(null) }

    val pending = sel?.let { draft != sheet.cell(it.first, it.second) } == true
    val modified = rev >= 0 && (sheet.rows !== savedRows || pending)

    /** 入力欄で書き換えた中身を、表に反映する。 */
    fun commit() {
        val s = sel ?: return
        if (sheet.setCell(s.first, s.second, draft)) rev++
    }

    fun select(r: Int, c: Int) {
        commit()
        sel = r to c
        draft = sheet.cell(r, c)
    }

    /** 行・列が増減したあと、選んでいるセルを範囲内に戻して、入力欄を合わせる。 */
    fun refreshSel(r: Int, c: Int) {
        val rr = r.coerceIn(0, sheet.rowCount - 1)
        val cc = c.coerceIn(0, sheet.columnCount - 1)
        sel = rr to cc
        draft = sheet.cell(rr, cc)
        rev++
    }

    fun save(thenClose: Boolean, force: Boolean = false) {
        commit()
        if (saving) return
        saving = true
        val rows = sheet.rows
        val text = Csv.write(rows, delimiter).let { if (trailingEol) it else it.removeSuffix("\n") }
        val st = stamp
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { writeBack(fs, item, text, doc.enc, doc.eol, st, force) } }
            saving = false
            r.onSuccess { res ->
                when (res) {
                    is SaveResult.Saved -> {
                        savedRows = rows
                        stamp = res.stamp
                        rev++
                        onSaved()
                        if (thenClose) onClose()
                    }
                    SaveResult.Changed -> overwriteClose = thenClose
                }
            }.onFailure { message = "保存できませんでした\n" + (it.message ?: it.javaClass.simpleName) }
        }
    }

    fun requestClose() {
        if (saving) return
        commit()
        if (sheet.rows !== savedRows) askExit = true else onClose()
    }
    BackHandler { requestClose() }

    val model = remember(rev, headerOn) { CsvGridModel(Csv.Table(sheet.rows, delimiter, false), headerOn) }
    val kb = rememberKeyboardAvoid()
    Column(Modifier.fillMaxSize().then(kb.container).statusBarsPadding().padding(bottom = kb.bottom)) {
        // 上のバー
        Row(Modifier.fillMaxWidth().background(Pv.bar).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { requestClose() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る", tint = Pv.barText) }
            Column(Modifier.weight(1f)) {
                Text(item.name, color = Pv.barText, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (modified) "未保存の変更あり" else "表で編集中", color = if (modified) Pv.warn else Pv.barSub, fontSize = 12.sp)
            }
            val canUndo = rev >= 0 && (sheet.canUndo || pending)
            val canRedo = rev >= 0 && sheet.canRedo
            IconButton(
                onClick = {
                    commit()
                    if (sheet.undo()) refreshSel(sel?.first ?: 0, sel?.second ?: 0)
                },
                enabled = canUndo,
            ) { Icon(Icons.AutoMirrored.Filled.Undo, "元に戻す", tint = if (canUndo) Pv.barText else Pv.barSub.copy(alpha = 0.4f)) }
            IconButton(
                onClick = {
                    commit()
                    if (sheet.redo()) refreshSel(sel?.first ?: 0, sel?.second ?: 0)
                },
                enabled = canRedo,
            ) { Icon(Icons.AutoMirrored.Filled.Redo, "やり直す", tint = if (canRedo) Pv.barText else Pv.barSub.copy(alpha = 0.4f)) }
            IconButton(onClick = { save(false) }, enabled = modified && !saving) {
                Icon(Icons.Default.Save, "保存", tint = if (modified && !saving) Pv.accent else Pv.barSub.copy(alpha = 0.4f))
            }
        }

        // 入力欄(選んだセルの位置と中身)。決定すると、ひとつ下のセルへ進む
        Row(Modifier.fillMaxWidth().background(Pv.bar).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val s = sel
            Text(
                if (s == null) "" else XlsxFormat.columnName(s.second) + (s.first + 1),
                color = Pv.barText, fontSize = 14.sp, maxLines = 1,
                modifier = Modifier.width(52.dp).background(Pv.searchField, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 10.dp),
            )
            Box(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    enabled = s != null,
                    singleLine = !draft.contains('\n'),
                    textStyle = TextStyle(color = Pv.barText, fontSize = 16.sp),
                    cursorBrush = SolidColor(Pv.barText),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrect = false),
                    keyboardActions = KeyboardActions(onDone = { s?.let { select(minOf(it.first + 1, sheet.rowCount - 1), it.second) } }),
                    modifier = Modifier.fillMaxWidth().background(Pv.searchField, RoundedCornerShape(6.dp)).padding(10.dp),
                    decorationBox = { inner ->
                        Box {
                            if (s == null) Text("セルを選んでください", color = Color.Gray, fontSize = 16.sp)
                            inner()
                        }
                    },
                )
            }
            IconButton(onClick = { commit() }, enabled = s != null && pending) {
                Icon(Icons.Default.Check, "確定", tint = if (s != null && pending) Pv.accent else Pv.barSub.copy(alpha = 0.4f))
            }
        }

        // 表
        SheetGrid(
            model, fontSp, Modifier.weight(1f).fillMaxWidth(), Pv.dark,
            selected = sel, onCellTap = { r, c -> select(r, c) },
        )

        // 行・列の操作
        val s = sel
        Row(Modifier.fillMaxWidth().background(Pv.bar).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
            val on = s != null
            @Composable
            fun Op(label: String, enabled: Boolean = on, action: (Int, Int) -> Unit) {
                TextButton(
                    enabled = enabled,
                    onClick = {
                        commit()
                        val cur = sel ?: return@TextButton
                        action(cur.first, cur.second)
                    },
                ) { Text(label, color = if (enabled) Pv.barText else Pv.barSub.copy(alpha = 0.4f), fontSize = 13.sp) }
            }
            Op("上に行を足す") { r, c -> sheet.insertRow(r); refreshSel(r, c) }
            Op("下に行を足す") { r, c -> sheet.insertRow(r + 1); refreshSel(r + 1, c) }
            Op("左に列を足す") { r, c -> sheet.insertColumn(c); refreshSel(r, c) }
            Op("右に列を足す") { r, c -> sheet.insertColumn(c + 1); refreshSel(r, c + 1) }
            Op("行を消す", on && sheet.rowCount > 1) { r, c -> if (sheet.deleteRow(r)) refreshSel(r, c) }
            Op("列を消す", on && sheet.columnCount > 1) { r, c -> if (sheet.deleteColumn(c)) refreshSel(r, c) }
        }
        Text(
            "${sheet.rowCount} 行 × ${sheet.columnCount} 列・${delimiterLabel(delimiter)}・${doc.enc.label}・${doc.eol.name}" +
                if (doc.mixedEol) "・改行がまざっていました(そろえて保存します)" else "",
            color = Pv.barSub, fontSize = 12.sp, maxLines = 1,
            modifier = Modifier.fillMaxWidth().background(Pv.bar).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp),
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
