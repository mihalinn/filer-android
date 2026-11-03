package local.filemanager

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------- 共通

/**
 * ビューア(開いた画面)の配色。mode は "dark"(既定)/ "light" / "app"(アプリ全体のテーマに合わせる)。
 * 状態として持つので、変えると、読んでいる画面が自動で描き直される(文字列に色を埋め込んで覚えている所は、dark をキーに含める)。
 * 画像・動画・音声は黒背景のままにするので、ここには含めない。Office は白い紙の見た目なので、これも使わない。
 */
internal object Pv {
    var mode by mutableStateOf("dark")
    var appDark by mutableStateOf(false)
    val dark: Boolean get() = when (mode) { "light" -> false; "app" -> appDark; else -> true }

    private fun pick(d: Long, l: Long) = Color(if (dark) d else l)

    val bg get() = pick(0xFF000000, 0xFFFAFAFA)
    val text get() = pick(0xFFE6E6E6, 0xFF212121)
    val dim get() = pick(0xFFB0B7BD, 0xFF5F6B73)
    val keyword get() = pick(0xFFC792EA, 0xFF7B3FA0)
    val string get() = pick(0xFFC3E88D, 0xFF2E7D32)
    val comment get() = pick(0xFF7F8C98, 0xFF6A737D)
    val number get() = pick(0xFFF78C6C, 0xFFC2410C)
    val link get() = pick(0xFF82B1FF, 0xFF1565C0)
    val codeBg get() = pick(0xFF1E252B, 0xFFEEF1F4)
    val codeInline get() = pick(0x33FFFFFF, 0x14000000)
    val accent get() = pick(0xFF9FA8DA, 0xFF3949AB)
    val line get() = pick(0x33FFFFFF, 0x33000000)
    val zebra get() = pick(0x14FFFFFF, 0x0F000000)
    val tableHeader get() = pick(0xFF263640, 0xFFDCE6EC)
    val panel get() = pick(0xFF263238, 0xFFE8EEF1)
    val warn get() = pick(0xFFFFCC66, 0xFF9A5B00)
    val pdfBackdrop get() = pick(0xFF2B2B2B, 0xFFD0D0D0)
    val modelBg get() = pick(0xFF21262B, 0xFFECEFF1)

    /** 上のバー・検索バー */
    val bar get() = pick(0xCC000000, 0xEEF5F5F5)
    val barText get() = pick(0xFFFFFFFF, 0xFF212121)
    val barSub get() = pick(0xFFCCCCCC, 0xFF616161)
    val searchBg get() = pick(0xEE1E252B, 0xF0ECEFF1)
    val searchField get() = pick(0xFF37474F, 0xFFFFFFFF)

    /** 状態の色(インストールの可否など) */
    val label get() = pick(0xFFB4BBFF, 0xFF3949AB)
    val neutral get() = pick(0xFFB0BEC5, 0xFF607D8B)
    val ok get() = pick(0xFF81C784, 0xFF2E7D32)
    val caution get() = pick(0xFFFFD54F, 0xFF8D6E00)
    val bad get() = pick(0xFFFF8A65, 0xFFD84315)
    val danger get() = pick(0xFFFFB74D, 0xFFBF4A00)
}

/** 上のバー(56dp)と状態バーの下から始める。 */
internal fun Modifier.belowBars(): Modifier = this.statusBarsPadding().padding(top = 56.dp).navigationBarsPadding()

private fun tokColor(t: Tok): Color = when (t) {
    Tok.KEYWORD -> Pv.keyword
    Tok.STRING -> Pv.string
    Tok.COMMENT -> Pv.comment
    Tok.NUMBER -> Pv.number
}

private fun HLine.toAnnotated(): AnnotatedString = buildAnnotatedString {
    append(text)
    for (s in spans) addStyle(SpanStyle(color = tokColor(s.tok)), s.start.coerceIn(0, text.length), s.end.coerceIn(0, text.length))
}

private fun openLink(handler: UriHandler, url: String) {
    val ok = url.startsWith("http://") || url.startsWith("https://") || url.startsWith("mailto:") || url.startsWith("tel:")
    if (ok) runCatching { handler.openUri(url) }
}

/** 文字の中のリンクをタップで開く。リンク以外をタップしたときは onTap(操作バーの表示切り替え)。 */
@Composable
internal fun LinkText(text: AnnotatedString, style: TextStyle, modifier: Modifier = Modifier, onTap: () -> Unit) {
    val uri = LocalUriHandler.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text, style = style, onTextLayout = { layout = it },
        modifier = modifier.pointerInput(text) {
            detectTapGestures { pos ->
                val l = layout
                var url: String? = null
                if (l != null) {
                    val off = l.getOffsetForPosition(pos)
                    if (off < text.length && l.getBoundingBox(off).contains(pos)) {
                        url = text.getStringAnnotations("URL", off, off + 1).firstOrNull()?.item
                    }
                }
                if (url != null) openLink(uri, url) else onTap()
            }
        },
    )
}

internal fun runsToAnnotated(runs: List<Run>): AnnotatedString = buildAnnotatedString {
    for (r in runs) {
        val deco = buildList {
            if (r.strike) add(TextDecoration.LineThrough)
            if (r.link != null) add(TextDecoration.Underline)
        }
        val style = SpanStyle(
            fontWeight = if (r.bold) FontWeight.Bold else null,
            fontStyle = if (r.italic) FontStyle.Italic else null,
            fontFamily = if (r.code) FontFamily.Monospace else null,
            background = if (r.code) Pv.codeInline else Color.Unspecified,
            color = if (r.link != null) Pv.link else Color.Unspecified,
            textDecoration = if (deco.isEmpty()) null else TextDecoration.combine(deco),
        )
        val start = length
        append(r.text)
        addStyle(style, start, length)
        if (r.link != null) addStringAnnotation("URL", r.link, start, length)
    }
}

// ---------------------------------------------------------------- 文書(テキスト系)

private sealed interface DocLoad {
    data object Busy : DocLoad
    data class Ok(val text: String, val charset: String, val truncated: Boolean, val limitMb: Int) : DocLoad
    data class Fail(val message: String) : DocLoad
}

private sealed interface DocView {
    data class Lines(val lines: List<HLine>, val note: String?) : DocView
    data class Md(val blocks: List<MdBlock>) : DocView
    data class Grid(val table: Csv.Table) : DocView
}

private fun readDoc(fs: Fs, item: FileItem, kind: PreviewKind): DocLoad = try {
    val limit = if (kind == PreviewKind.CSV) 8 * 1024 * 1024 else 2 * 1024 * 1024
    var (bytes, truncated) = fs.open(item.path).use { TextDecode.readUpTo(it, limit) }
    if (kind == PreviewKind.XML) {
        // バイナリ plist は末尾に目録があるので、途中まででは読めない。大きいものは 64MB まで読み直す
        if (truncated && Plist.isBinary(bytes)) { val again = fs.open(item.path).use { TextDecode.readUpTo(it, 64 * 1024 * 1024) }; bytes = again.first; truncated = again.second }
        Plist.displayText(item.name, bytes)?.let { return DocLoad.Ok(it.first, it.second, false, limit / 1024 / 1024) }
    }
    val r = TextDecode.decode(bytes, truncated)
    DocLoad.Ok(r.text, r.charsetName, truncated, limit / 1024 / 1024)
} catch (e: Exception) {
    DocLoad.Fail(e.message ?: "読み込めません")
}

private fun fenceExt(lang: String): String = when (val l = lang.lowercase()) {
    "kotlin" -> "kt"
    "javascript", "node" -> "js"
    "typescript" -> "ts"
    "python", "python3" -> "py"
    "bash", "shell", "zsh", "sh" -> "sh"
    "rust" -> "rs"
    "ruby" -> "rb"
    "csharp", "c#" -> "cs"
    "c++" -> "cpp"
    "golang" -> "go"
    "batch", "cmd" -> "bat"
    else -> l
}

private fun highlightText(text: String, ext: String): List<HLine> {
    val lang = Highlight.langFor(ext) ?: return Highlight.plainLines(text)
    return Highlight.splitLines(text, Highlight.spans(text, lang))
}

private fun deriveView(text: String, name: String, kind: PreviewKind, raw: Boolean): DocView {
    val ext = FileOps.extensionOf(name)
    if (!raw) {
        when (kind) {
            PreviewKind.MARKDOWN -> return DocView.Md(Markdown.parse(text))
            PreviewKind.CSV -> return DocView.Grid(Csv.parse(text, Csv.detectDelimiter(text, ext)))
            PreviewKind.JSON -> {
                val p = Pretty.json(text)
                return if (p != null) DocView.Lines(highlightText(p, "json"), "整形済み")
                else DocView.Lines(highlightText(text, "json"), "JSONとして読めないため、そのまま表示しています")
            }
            PreviewKind.XML -> {
                val p = Pretty.xml(text)
                return if (p != null) DocView.Lines(highlightText(p, "xml"), "整形済み")
                else DocView.Lines(highlightText(text, "xml"), "XMLとして読めないため、そのまま表示しています")
            }
            else -> {}
        }
    }
    // 元のテキスト。ソースコードなどは色分けする(Markdown・CSV の元の文は色分けしない)
    val colorize = kind == PreviewKind.CODE || kind == PreviewKind.JSON || kind == PreviewKind.XML
    return DocView.Lines(if (colorize) highlightText(text, ext) else Highlight.plainLines(text), null)
}

/** テキスト系のファイルの表示(Markdown・CSV・JSON・XML・ソースコード・普通のテキスト)。 */
@Composable
internal fun DocPane(
    fs: Fs, item: FileItem, kind: PreviewKind, fontSp: Int, bars: Boolean, raw: Boolean, headerOn: Boolean, onTap: () -> Unit,
    /** 検索の言葉と、何番目に当たった所を見せるか。当たった数は onHits で知らせる(行が出る表示のときだけ検索できる)。 */
    query: String = "", hit: Int = 0, onHits: (Int) -> Unit = {},
) {
    val load = produceState<DocLoad>(DocLoad.Busy, item.path) {
        value = withContext(Dispatchers.IO) { readDoc(fs, item, kind) }
    }
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }) {
        when (val s = load.value) {
            DocLoad.Busy -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Pv.text)
            is DocLoad.Fail -> Text(s.message, color = Pv.text, modifier = Modifier.align(Alignment.Center).padding(32.dp))
            is DocLoad.Ok -> {
                val view = produceState<DocView?>(null, s, raw) {
                    value = withContext(Dispatchers.Default) { deriveView(s.text, item.name, kind, raw) }
                }.value
                if (view == null) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center), color = Pv.text)
                } else {
                    val banner = if (s.truncated) "先頭の ${s.limitMb} MB だけ表示しています" else null
                    val status = when (view) {
                        is DocView.Lines -> listOfNotNull(s.charset, "${view.lines.size}行", view.note).joinToString("・")
                        is DocView.Md -> "Markdown・${s.charset}"
                        is DocView.Grid -> "${view.table.rows.size}行×${view.table.columns}列・区切り「${delimiterName(view.table.delimiter)}」・${s.charset}"
                    }
                    when (view) {
                        is DocView.Lines -> LinesPane(view.lines, banner, fontSp, query, hit, onHits)
                        is DocView.Md -> MarkdownPane(view.blocks, banner, fontSp, onTap)
                        is DocView.Grid -> TablePane(view.table, banner, headerOn, fontSp)
                    }
                    if (bars) {
                        Text(
                            status,
                            color = Color(0xFFCCCCCC), fontSize = 12.sp,
                            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun delimiterName(c: Char) = when (c) {
    ',' -> "カンマ"
    '\t' -> "タブ"
    ';' -> "セミコロン"
    '|' -> "縦線"
    else -> c.toString()
}

@Composable
internal fun Banner(text: String) {
    Text(text, color = Pv.warn, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
}

@Composable
private fun LinesPane(lines: List<HLine>, banner: String?, fontSp: Int, query: String, hit: Int, onHits: (Int) -> Unit) {
    val state = rememberLazyListState()
    val matches = remember(lines, query) {
        val out = ArrayList<Int>()
        if (query.isNotEmpty()) for ((i, l) in lines.withIndex()) if (l.text.contains(query, ignoreCase = true)) out.add(i)
        out
    }
    LaunchedEffect(matches) { onHits(matches.size) }
    val cur = if (matches.isEmpty()) -1 else matches[hit.coerceIn(0, matches.lastIndex)]
    LaunchedEffect(cur, matches) { if (cur >= 0) state.scrollToItem(cur + if (banner != null) 1 else 0) }
    LazyColumn(Modifier.fillMaxSize().belowBars(), state = state) {
        if (banner != null) item { Banner(banner) }
        items(lines.size) { i ->
            val a = remember(lines[i], query, i == cur, Pv.dark) {
                val base = lines[i].toAnnotated()
                if (query.isEmpty()) base else buildAnnotatedString {
                    append(base)
                    val bg = if (i == cur) Color(0xCCFFB300) else Color(0x66FFD54F)
                    for (r in TextSearch.ranges(lines[i].text, query)) addStyle(SpanStyle(background = bg, color = Color.Black), r.first, r.last + 1)
                }
            }
            Text(
                if (a.isEmpty()) AnnotatedString(" ") else a,
                color = Pv.text,
                fontFamily = FontFamily.Monospace,
                fontSize = fontSp.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- Markdown

@Composable
private fun MarkdownPane(blocks: List<MdBlock>, banner: String?, fontSp: Int, onTap: () -> Unit) {
    val base = TextStyle(color = Pv.text, fontSize = fontSp.sp, lineHeight = (fontSp * 1.55f).sp)
    LazyColumn(Modifier.fillMaxSize().belowBars(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (banner != null) item { Banner(banner) }
        items(blocks.size) { i ->
            when (val b = blocks[i]) {
                is MdBlock.Heading -> {
                    val scale = floatArrayOf(1.0f, 1.8f, 1.5f, 1.3f, 1.15f, 1.05f, 1.0f)[b.level]
                    Column {
                        LinkText(
                            remember(b, Pv.dark) { runsToAnnotated(Markdown.inline(b.text)) },
                            base.copy(fontSize = (fontSp * scale).sp, lineHeight = (fontSp * scale * 1.35f).sp, fontWeight = FontWeight.Bold),
                            Modifier.padding(top = if (i == 0) 0.dp else 8.dp), onTap,
                        )
                        if (b.level <= 2) Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(1.dp).background(Pv.line))
                    }
                }
                is MdBlock.Paragraph -> LinkText(remember(b, Pv.dark) { runsToAnnotated(Markdown.inline(b.text)) }, base, Modifier.fillMaxWidth(), onTap)
                is MdBlock.Item -> Row(Modifier.padding(start = (b.level * 18).dp, top = 0.dp)) {
                    val mark = when (b.checked) {
                        true -> "☑"
                        false -> "☐"
                        null -> b.marker
                    }
                    Text(mark, style = base, color = Pv.dim, modifier = Modifier.width(if (b.marker.length > 2) 30.dp else 22.dp))
                    LinkText(remember(b, Pv.dark) { runsToAnnotated(Markdown.inline(b.text)) }, base, Modifier.weight(1f), onTap)
                }
                is MdBlock.Code -> {
                    val lines = remember(b) { highlightText(b.text, fenceExt(b.lang)) }
                    Column(
                        Modifier.fillMaxWidth().background(Pv.codeBg, RoundedCornerShape(6.dp)).padding(10.dp)
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        for (l in lines) {
                            Text(
                                l.toAnnotated().let { if (it.isEmpty()) AnnotatedString(" ") else it },
                                color = Pv.text, fontFamily = FontFamily.Monospace, fontSize = (fontSp - 1).coerceAtLeast(8).sp, softWrap = false,
                            )
                        }
                    }
                }
                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(Pv.accent))
                    LinkText(
                        remember(b, Pv.dark) { runsToAnnotated(Markdown.inline(b.text)) },
                        base.copy(color = Pv.dim), Modifier.padding(start = 10.dp).fillMaxWidth(), onTap,
                    )
                }
                is MdBlock.Table -> {
                    val rows = remember(b) { b.rows.map { r -> r.map { Markdown.plain(it) } } }
                    val head = remember(b) { b.header.map { Markdown.plain(it) } }
                    GridView(head, rows, fontSp - 1, true, Modifier.fillMaxWidth(), null)
                }
                MdBlock.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(Pv.line))
            }
        }
    }
}

// ---------------------------------------------------------------- 表(CSV / TSV)

/** 文字の見た目の幅(全角は 2)。列の幅を決めるのに使う。 */
private fun visualLen(s: String): Int {
    var best = 0
    for (line in s.split('\n')) {
        var w = 0
        for (c in line) w += if (c.code >= 0x2E80) 2 else 1
        if (w > best) best = w
    }
    return best
}

@Composable
private fun TablePane(table: Csv.Table, banner: String?, headerOn: Boolean, fontSp: Int) {
    val model = remember(table, headerOn) { CsvGridModel(table, headerOn) }
    // 表の色もビューアの配色(ライト/ダーク)に従う
    val dark = Pv.dark
    val oc = remember(dark) { OfficeColors(dark) }
    Column(Modifier.fillMaxSize().belowBars().background(oc.paper)) {
        if (banner != null) Text(banner, color = Color(0xFFB26A00), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        SheetGrid(model, fontSp, Modifier.weight(1f).fillMaxWidth(), dark)
    }
}
/**
 * 表。header は固定(縦にスクロールしても残る)で、横にもスクロールできる。
 * lazy が false のときは(Markdown の小さな表)、全部をそのまま並べる。
 */
@Composable
internal fun GridView(header: List<String>?, rows: List<List<String>>, fontSp: Int, small: Boolean, modifier: Modifier, columnsHint: Int?) {
    val cols = maxOf(columnsHint ?: 0, header?.size ?: 0, rows.maxOfOrNull { it.size } ?: 0)
    // 先頭の数百行から、列ごとの幅を決める
    val widths = remember(header, rows, cols, fontSp) {
        List(cols) { c ->
            var m = header?.getOrNull(c)?.let { visualLen(it) } ?: 0
            for (r in 0 until minOf(rows.size, 300)) m = maxOf(m, visualLen(rows[r].getOrElse(c) { "" }))
            (m * fontSp * 0.56f + 22f).coerceIn(52f, 280f).dp
        }
    }
    val numW = (rows.size.toString().length * fontSp * 0.62f + 16f).coerceAtLeast(30f).dp
    var detail by remember { mutableStateOf<String?>(null) }
    val hs = rememberScrollState()

    @Composable
    fun RowOf(index: String, cells: List<String>, bg: Color, bold: Boolean) {
        Row(Modifier.height(IntrinsicSize.Min).background(bg)) {
            Text(index, color = Pv.comment, fontSize = (fontSp - 2).coerceAtLeast(8).sp, modifier = Modifier.width(numW).padding(horizontal = 4.dp, vertical = 4.dp))
            for (c in 0 until cols) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(Pv.line))
                val cell = cells.getOrElse(c) { "" }
                Text(
                    cell, color = Pv.text, fontSize = fontSp.sp, fontWeight = if (bold) FontWeight.Bold else null,
                    maxLines = if (small) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(widths[c]).clickable(enabled = cell.length > 12 || cell.contains('\n')) { detail = cell }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Pv.line))
        }
    }

    val totalW = widths.fold(numW) { a, b -> a + b } + (cols + 1).dp
    Box(modifier.horizontalScroll(hs)) {
        Column(if (small) Modifier else Modifier.fillMaxHeight()) {
            if (header != null) {
                RowOf("#", header, Pv.tableHeader, true)
                Box(Modifier.width(totalW).height(1.dp).background(Pv.accent))
            }
            if (small) {
                rows.forEachIndexed { i, r -> RowOf("${i + 1}", r, if (i % 2 == 0) Color.Transparent else Pv.zebra, false) }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(rows.size) { i ->
                        RowOf("${i + 1}", rows[i], if (i % 2 == 0) Color.Transparent else Pv.zebra, false)
                    }
                }
            }
        }
    }
    detail?.let { text ->
        AlertDialog(
            onDismissRequest = { detail = null },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("閉じる") } },
            text = {
                SelectionContainer {
                    Text(text.take(20_000), modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()))
                }
            },
        )
    }
}
