package local.filemanager

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface OfficeLoad {
    data object Busy : OfficeLoad
    data class Excel(val file: SheetBook, val src: RandomSource) : OfficeLoad
    data class Word(val doc: DocxDoc, val read: (String) -> ByteArray?, val src: RandomSource) : OfficeLoad
    /** deck が読めたら位置どおりの表示、読めなかったときは texts(文字だけ)で出す。 */
    data class Slides(val deck: SlideDeck?, val texts: List<Slide>, val read: (String) -> ByteArray?, val src: RandomSource) : OfficeLoad
    data class Fail(val message: String) : OfficeLoad
}

private const val OFFICE_ENTRY_LIMIT = 64L * 1024 * 1024

/** 古い Office の入れ物(OLE2)であることを確かめる。そうでなければ、中身の見当をつけた分かりやすい文面のエラー。 */
private fun requireOle(bytes: ByteArray, what: String): ByteArray {
    if (Ole2.isOle2(bytes)) return bytes
    val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.ISO_8859_1).trimStart('\uFEFF', 'ï', '»', '¿', ' ', '\t', '\r', '\n').lowercase()
    val hint = when {
        head.startsWith("<?xml") -> Txt(R.string.office_ui_001)
        head.startsWith("<") -> Txt(R.string.office_ui_002)
        bytes.isNotEmpty() && bytes.take(512).none { (it.toInt() and 0xFF).let { v -> v < 9 || v in 14..31 } } -> Txt(R.string.office_ui_003)
        else -> Txt(R.string.office_ui_004)
    }
    throw OpException(Txt(R.string.office_ui_005, what, hint))
}
/** ファイル全体を読む(RTF・古い Office 用)。大きすぎるときは OpException。 */
private fun readWhole(src: RandomSource, limit: Long = 64L * 1024 * 1024): ByteArray {
    if (src.size > limit) throw OpException(Txt(R.string.office_ui_006, limit / 1024 / 1024))
    val out = ByteArray(src.size.toInt())
    var pos = 0
    while (pos < out.size) {
        val n = src.readAt(pos.toLong(), out, pos, out.size - pos)
        if (n <= 0) break
        pos += n
    }
    return if (pos == out.size) out else out.copyOf(pos)
}

private fun loadOffice(fs: Fs, item: FileItem, kind: PreviewKind): OfficeLoad {
    val src = try {
        openSource(fs, item.path)
    } catch (e: Exception) {
        null
    } ?: return OfficeLoad.Fail(Txt(R.string.office_ui_007))
    var keepOpen = false
    return try {
        if (kind == PreviewKind.RTF || kind == PreviewKind.DOC || kind == PreviewKind.XLS || kind == PreviewKind.PPT) {
            val bytes = readWhole(src)
            val zip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
            if (zip && kind != PreviewKind.RTF) {
                // 拡張子は古い形式でも、中身は新しい形式(.docx など)のことがある
                val newer = when (kind) { PreviewKind.DOC -> PreviewKind.DOCX; PreviewKind.XLS -> PreviewKind.XLSX; else -> PreviewKind.PPTX }
                src.close()
                return loadOffice(fs, item, newer)
            }
            keepOpen = true
            return when (kind) {
                PreviewKind.RTF -> OfficeLoad.Word(Rtf.parse(bytes), { null }, src)
                PreviewKind.DOC ->
                    if (Rtf.looksLikeRtf(bytes)) OfficeLoad.Word(Rtf.parse(bytes), { null }, src)
                    else Doc.open(Ole2(requireOle(bytes, "Word"))).let { OfficeLoad.Word(it.doc, it.read, src) }
                PreviewKind.XLS -> OfficeLoad.Excel(XlsBook.open(requireOle(bytes, "Excel").let { it }), src)
                else -> {
                    val ppt = requireOle(bytes, "PowerPoint")
                    // 図形つきで読めればスライドの絵で、読めなければ文字だけの一覧で見せる
                    val drawn = runCatching { PptDeck.parse(ppt) }.getOrNull()?.takeIf { r -> r.deck.slides.any { it.shapes.isNotEmpty() } }
                    if (drawn != null) OfficeLoad.Slides(drawn.deck, emptyList(), drawn.read, src)
                    else OfficeLoad.Slides(null, Ppt.parse(ppt), { null }, src)
                }
            }
        }
        val reader = ZipReader(src)
        val byName = reader.entries.associateBy { ZipTree.clean(it.name) }
        val read: (String) -> ByteArray? = { name ->
            byName[name]?.takeIf { it.size <= OFFICE_ENTRY_LIMIT }?.let { e -> runCatching { reader.open(e).use { it.readBytes() } }.getOrNull() }
        }
        when (kind) {
            PreviewKind.XLSX -> {
                keepOpen = true // シートは、見るときに読む(読む間は開いたままにする)
                OfficeLoad.Excel(XlsxFile(read), src)
            }
            PreviewKind.ODS -> {
                keepOpen = true
                OfficeLoad.Excel(OdsBook(read), src)
            }
            PreviewKind.ODT -> {
                keepOpen = true // 画像は、見るときに読む
                OfficeLoad.Word(Odt.parse(read), read, src)
            }
            PreviewKind.ODP -> {
                keepOpen = true
                OfficeLoad.Slides(Odp.parse(read), emptyList(), read, src)
            }
            PreviewKind.DOCX -> {
                keepOpen = true // 画像は、見るときに読む
                OfficeLoad.Word(Docx.parse(read), read, src)
            }
            else -> {
                keepOpen = true // 画像は、見るときに読む
                val deck = try {
                    SlideDeckParser.parse(read)
                } catch (e: OpException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                OfficeLoad.Slides(deck, if (deck == null) Pptx.parse(read) else emptyList(), read, src)
            }
        }
    } catch (e: OpException) {
        keepOpen = false
        OfficeLoad.Fail(e.message ?: Txt(R.string.office_ui_008))
    } catch (e: Exception) {
        keepOpen = false
        OfficeLoad.Fail(Txt(R.string.office_ui_009, e.message ?: e.javaClass.simpleName))
    } catch (e: OutOfMemoryError) {
        keepOpen = false
        OfficeLoad.Fail(Txt(R.string.office_ui_010))
    } finally {
        if (!keepOpen) src.close()
    }
}

/**
 * Excel(.xlsx)・Word(.docx)・PowerPoint(.pptx)の中身を読むための簡易表示。
 * Excel は表(シートごと)、Word は白い紙のような見た目、PowerPoint はスライドの位置・図形・画像・表・書式のとおりの絵。
 */
@Composable
internal fun OfficePane(fs: Fs, item: FileItem, kind: PreviewKind, fontSp: Int, copy: CopyHolder? = null) {
    // 配色はビューアの「配色」(ライト/ダーク)に従う
    val dark = Pv.dark
    val load = produceState<OfficeLoad>(OfficeLoad.Busy, item.path) {
        value = withContext(Dispatchers.IO) { loadOffice(fs, item, kind) }
    }.value
    when (load) {
        OfficeLoad.Busy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
        is OfficeLoad.Fail -> Box(Modifier.fillMaxSize()) {
            Text(load.message, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(32.dp))
        }
        is OfficeLoad.Excel -> {
            DisposableEffect(load) { onDispose { load.src.close() } }
            ExcelView(load.file, fontSp, dark, copy)
        }
        is OfficeLoad.Word -> {
            DisposableEffect(load) { onDispose { load.src.close() } }
            WordView(load.doc, load.read, fontSp, dark, copy)
        }
        is OfficeLoad.Slides -> {
            DisposableEffect(load) { onDispose { load.src.close() } }
            if (load.deck != null) SlidesView(load.deck, load.read, dark, copy) else TextSlidesView(load.texts, fontSp, dark, copy)
        }
    }
}

@Composable
private fun ExcelView(file: SheetBook, fontSp: Int, dark: Boolean, copy: CopyHolder? = null) {
    val oc = remember(dark) { OfficeColors(dark) }
    var sheetIndex by remember { mutableIntStateOf(0) }
    val sheet = produceState<Result<XlsxSheet>?>(null, sheetIndex) {
        value = null
        value = withContext(Dispatchers.IO) { runCatching { file.load(sheetIndex) } }
    }.value
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().belowBars().background(oc.paper)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val r = sheet
                when {
                    r == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = oc.accent)
                    r.isFailure -> Text(r.exceptionOrNull()?.message ?: Txt(R.string.office_ui_011), color = oc.note, modifier = Modifier.align(Alignment.Center).padding(32.dp))
                    else -> {
                        val s = r.getOrThrow()
                        if (s.rows.isEmpty()) {
                            Text(Txt(R.string.office_ui_012), color = oc.headerText, modifier = Modifier.align(Alignment.Center))
                        } else {
                            val model = remember(s, file) { XlsxGridModel(s, file) }
                            DisposableEffect(model, copy) {
                                copy?.set(Txt(R.string.office_ui_013)) { CopyText.tsv(model.rowCount, model.colCount) { r, c -> model.cell(r, c)?.text.orEmpty() } }
                                onDispose { copy?.provider = null }
                            }
                            Column(Modifier.fillMaxSize()) {
                                if (s.truncated) Text(Txt(R.string.office_ui_014), color = Color(0xFFB26A00), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                                SheetGrid(model, fontSp, Modifier.weight(1f).fillMaxWidth(), dark)
                            }
                        }
                    }
                }
            }
            // シートのタブ(Excel の下のタブのように)
            if (file.sheetNames.size > 1) {
                Row(Modifier.fillMaxWidth().background(oc.tabBar).horizontalScroll(rememberScrollState())) {
                    file.sheetNames.forEachIndexed { i, sheetName ->
                        val on = i == sheetIndex
                        Box(
                            Modifier.clickable { sheetIndex = i }.background(if (on) oc.paper else Color.Transparent)
                                .let { if (on) it.border(0.5.dp, oc.tabBorder) else it }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Text(sheetName, color = if (on) oc.accent else oc.tabText, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else null)
                        }
                    }
                }
            }
        }
        sheet?.getOrNull()?.let { s ->
            Text(
                Txt(R.string.office_sheet_info, file.sheetNames[sheetIndex], s.rows.size, s.columns),
                color = Color(0xFFCCCCCC), fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(bottom = if (file.sheetNames.size > 1) 44.dp else 0.dp)
                    .background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}
// ---------------------------------------------------------------- Word(白い紙・灰色の背景。Word に近い見た目)

/** フォント名から、Android で使える書体の系統に寄せる(明朝・セリフ系 / 等幅 / ゴシック・その他)。 */
internal fun fontFamilyOf(name: String?): FontFamily? {
    if (name == null) return null
    val n = name.lowercase()
    return when {
        Regex("明朝|mincho|serif|times|century|georgia|cambria|garamond|palatino|book antiqua|ms p?明").containsMatchIn(n) && !n.contains("sans") -> FontFamily.Serif
        Regex("courier|consolas|monospace|mono|ms gothic|ＭＳ ゴシック").containsMatchIn(n) && !n.contains("p") -> FontFamily.Monospace
        else -> FontFamily.SansSerif
    }
}

/** 段落の文字を、Word の書式(大きさ・色・太字・下線・蛍光ペン・上付きなど)のままの AnnotatedString にする。 */
private fun wordText(p: DocBlock.Para, k: Float, oc: OfficeColors): AnnotatedString = buildAnnotatedString {
    for (run in p.runs) {
        val r = run.r
        val size = (r.sizeHp ?: 21) / 2f
        val isHeadingFallback = p.heading > 0 && r.bold == null && r.sizeHp == null
        val deco = buildList {
            if (r.underline == true || run.link != null) add(TextDecoration.Underline)
            if (r.strike == true) add(TextDecoration.LineThrough)
        }
        val shift = r.vert ?: 0
        val style = SpanStyle(
            fontSize = (size * k * (if (shift != 0) 0.7f else 1f) * (if (isHeadingFallback) floatArrayOf(1f, 1.8f, 1.5f, 1.3f, 1.15f, 1.05f, 1f)[p.heading] else 1f)).sp,
            color = if (run.link != null) oc.link else r.color?.let { oc.c(it) } ?: oc.ink,
            fontWeight = if (r.bold == true || isHeadingFallback) FontWeight.Bold else null,
            fontStyle = if (r.italic == true) FontStyle.Italic else null,
            textDecoration = if (deco.isEmpty()) null else TextDecoration.combine(deco),
            background = r.highlight?.let { oc.c(it) } ?: Color.Unspecified,
            fontFamily = fontFamilyOf(r.font),
            baselineShift = when (shift) { 1 -> BaselineShift.Superscript; -1 -> BaselineShift.Subscript; else -> null },
        )
        val start = length
        append(run.text.replace("\t", "    "))
        addStyle(style, start, length)
        if (run.link != null) addStringAnnotation("URL", run.link, start, length)
    }
}

private class WordItem(val block: DocBlock, val first: Boolean, val last: Boolean, val gapAfter: Boolean)

@Composable
private fun WordView(doc: DocxDoc, read: (String) -> ByteArray?, fontSp: Int, dark: Boolean, copy: CopyHolder? = null) {
    val oc = remember(dark) { OfficeColors(dark) }
    // 文字の大きさは、ポイント → sp。Word の 10.5pt が、標準(14)で読みやすい大きさになるよう、少し大きめにする
    val k = fontSp / 14f * 1.2f
    val items = remember(doc) {
        val out = ArrayList<WordItem>()
        for ((pi, page) in doc.pages.withIndex()) for ((bi, b) in page.withIndex()) {
            out.add(WordItem(b, bi == 0, bi == page.lastIndex, pi < doc.pages.lastIndex))
        }
        out
    }
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize().background(oc.backdrop)) { Text(Txt(R.string.office_ui_015), color = oc.note, modifier = Modifier.align(Alignment.Center).padding(32.dp)) }
        return
    }
    DisposableEffect(items, copy) {
        copy?.set(Txt(R.string.office_ui_016)) { items.joinToString("\n") { wordBlockText(it.block) } }
        onDispose { copy?.provider = null }
    }
    CopyableArea {
    LazyColumn(Modifier.fillMaxSize().background(oc.backdrop).belowBars(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
        items(items.size) { i ->
            val it = items[i]
            Column(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().background(oc.paper)
                        .padding(start = 16.dp, end = 16.dp, top = if (it.first) 20.dp else 0.dp, bottom = if (it.last) 24.dp else 0.dp),
                ) {
                    when (val b = it.block) {
                        is DocBlock.Para -> WordPara(b, k, oc)
                        is DocBlock.Table -> WordTable(b, k, read, oc)
                        is DocBlock.Image -> WordImage(b, k, read)
                    }
                }
                if (it.last && it.gapAfter) Box(Modifier.fillMaxWidth().height(10.dp))
            }
        }
    }
    }
}

/** Word の 1 つのまとまり(段落・表)の文字。表は、タブと改行で区切る。 */
private fun wordBlockText(b: DocBlock): String = when (b) {
    is DocBlock.Para -> b.text
    is DocBlock.Table -> b.rows.joinToString("\n") { row ->
        row.joinToString("\t") { cell -> CopyText.tsvCell(cell.blocks.filterIsInstance<DocBlock.Para>().joinToString(" ") { it.text }) }
    }
    is DocBlock.Image -> ""
}

@Composable
private fun WordPara(p: DocBlock.Para, k: Float, oc: OfficeColors) {
    val text = remember(p, k, oc.dark) { wordText(p, k, oc) }
    val maxSize = (p.runs.maxOfOrNull { (it.r.sizeHp ?: 21) / 2f } ?: 10.5f) * (if (p.heading > 0 && p.runs.all { it.r.sizeHp == null }) 1.5f else 1f)
    val lineSp = (maxSize * k * 1.35f * p.lineMult).sp
    val align = when (p.align) { "center" -> TextAlign.Center; "right" -> TextAlign.End; "both" -> TextAlign.Justify; else -> TextAlign.Start }
    val before = (p.beforePt * k).dp
    val after = (p.afterPt * k).dp
    var mod = Modifier.fillMaxWidth().padding(top = before, bottom = after)
    if (p.fill != null) mod = mod.background(oc.c(p.fill))
    if (p.text.isEmpty() && p.marker == null) {
        Spacer(mod.height(lineSp.value.dp))
        return
    }
    val outdent = (p.hangingPt * k).dp
    val left = (p.leftPt * k).dp
    if (p.marker != null) {
        // 箇条書き: 記号・番号を、字下げの手前に置く
        val markerStart = (left - outdent).coerceAtLeast(0.dp)
        val markerWidth = outdent.coerceAtLeast((18 * k).dp)
        Row(mod.padding(start = markerStart)) {
            Text(p.marker, style = TextStyle(color = oc.ink, fontSize = ((p.runs.firstOrNull()?.r?.sizeHp ?: 21) / 2f * k).sp, lineHeight = lineSp), modifier = Modifier.width(markerWidth))
            LinkText(text, TextStyle(lineHeight = lineSp, textAlign = align), Modifier.weight(1f)) {}
        }
    } else {
        val firstLine = (p.firstLinePt * k).sp
        val rest = (p.hangingPt * k).sp
        val startPad = if (p.hangingPt > 0f) (left - outdent).coerceAtLeast(0.dp) else left
        LinkText(
            text,
            TextStyle(lineHeight = lineSp, textAlign = align, textIndent = TextIndent(firstLine = if (p.hangingPt > 0f) 0.sp else firstLine, restLine = if (p.hangingPt > 0f) rest else 0.sp)),
            mod.padding(start = startPad),
        ) {}
    }
}

@Composable
private fun WordTable(t: DocBlock.Table, k: Float, read: (String) -> ByteArray?, oc: OfficeColors) {
    val line = oc.strongLine
    // 列の幅は、使える幅を列の幅の比で分けて、先に決める(行の高さを、その幅での折り返しで正しく測るため)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        val total = maxWidth
        val sum = t.weights.sum().coerceAtLeast(1)
        Column(Modifier.let { if (t.borders) it.border(0.6.dp, line) else it }) {
            for (row in t.rows) {
                var col = 0
                Row(Modifier.height(IntrinsicSize.Min)) {
                    for (cell in row) {
                        val w = (col until (col + cell.span).coerceAtMost(t.weights.size)).sumOf { t.weights[it] }.coerceAtLeast(1)
                        col += cell.span
                        Column(
                            Modifier.width(total * (w.toFloat() / sum)).fillMaxHeight()
                                .let { if (t.borders) it.border(0.4.dp, line) else it }
                                .let { if (cell.fill != null) it.background(oc.c(cell.fill)) else it }
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                        ) {
                            for (b in cell.blocks) when (b) {
                                is DocBlock.Para -> WordPara(b, k * 0.95f, oc)
                                is DocBlock.Image -> WordImage(b, k, read)
                                is DocBlock.Table -> {}
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun WordImage(img: DocBlock.Image, k: Float, read: (String) -> ByteArray?) {
    val bmp = produceState<android.graphics.Bitmap?>(null, img.path) {
        value = withContext(Dispatchers.IO) { read(img.path)?.let { ImageDecode.decodeBytes(it, 1600) } }
    }.value
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (bmp != null) {
            Image(
                bmp.asImageBitmap(), null,
                Modifier.widthIn(max = (img.widthPt * k).dp).fillMaxWidth(), contentScale = ContentScale.FillWidth,
            )
        } else {
            Spacer(Modifier.height((img.heightPt * k).dp.coerceAtMost(240.dp)).fillMaxWidth())
        }
    }
}
/** スライドの位置どおりの表示が読めなかったときの、文字だけの表示。 */
@Composable
private fun TextSlidesView(slides: List<Slide>, fontSp: Int, dark: Boolean, copy: CopyHolder? = null) {
    val oc = remember(dark) { OfficeColors(dark) }
    if (slides.isEmpty()) {
        Box(Modifier.fillMaxSize().background(oc.backdrop)) { Text(Txt(R.string.office_ui_017), color = oc.note, modifier = Modifier.align(Alignment.Center)) }
        return
    }
    val k = fontSp / 14f
    DisposableEffect(slides, copy) {
        copy?.set(Txt(R.string.office_ui_018)) {
            slides.joinToString("\n\n") { s -> Txt(R.string.office_ui_019, s.number) + (listOfNotNull(s.title) + s.lines).joinToString("\n") }
        }
        onDispose { copy?.provider = null }
    }
    CopyableArea {
    LazyColumn(
        Modifier.fillMaxSize().background(oc.backdrop).belowBars(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(slides.size) { i ->
            val s = slides[i]
            // 白いスライド(横長の比率を最低の高さにする)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().heightIn(min = maxWidth * 9f / 16f).background(oc.paper).padding(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    Text("${s.number}", color = oc.slideNumber, fontSize = (11 * k).sp)
                    if (s.title != null) Text(s.title, color = oc.slideTitle, fontSize = (20 * k).sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                    for (l in s.lines) Text(l, color = oc.slideBody, fontSize = (14 * k).sp, modifier = Modifier.padding(top = 5.dp))
                    if (s.title == null && s.lines.isEmpty()) Text(Txt(R.string.office_ui_020), color = oc.headerText, fontSize = (13 * k).sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
    }
}
