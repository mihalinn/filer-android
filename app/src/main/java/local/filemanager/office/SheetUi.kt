package local.filemanager

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 表の 1 つのセルの見た目。 */
internal class GridCell(
    val text: String,
    val fill: Int? = null,
    val color: Int? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    /** -1 左 / 0 中央 / 1 右 */
    val align: Int = -1,
    val wrap: Boolean = false,
    val border: Boolean = false,
    val sizePt: Float? = null,
)

/** Excel 風の表に出す中身。Excel(.xlsx)と CSV で共通。 */
internal interface GridModel {
    val rowCount: Int
    val colCount: Int

    /** 上に固定する行の数 */
    val frozenRows: Int

    /** セルの見た目。何も無い(文字も書式も)ときは null。 */
    fun cell(r: Int, c: Int): GridCell?

    /** セル (r, c) から右へ何列分の幅か(結合したセルの先頭)。結合された右側のセルは 0(出さない)。通常は 1。 */
    fun span(r: Int, c: Int): Int

    /** 列の幅(dp。標準の文字の大きさのとき) */
    fun colWidth(c: Int): Float
}

internal class XlsxGridModel(private val sheet: XlsxSheet, private val file: SheetBook) : GridModel {
    override val rowCount = sheet.rows.size
    override val colCount = sheet.columns
    override val frozenRows = sheet.frozenRows.coerceIn(0, 20)

    private val spans = HashMap<Long, Int>().also { m ->
        for (g in sheet.merges) {
            val (r0, c0, _, c1) = g.toList()
            if (c1 > c0) {
                m[XlsxSheet.key(r0, c0)] = c1 - c0 + 1
                for (c in c0 + 1..c1) m[XlsxSheet.key(r0, c)] = 0
            }
        }
    }

    override fun span(r: Int, c: Int): Int = spans[XlsxSheet.key(r, c)] ?: 1

    override fun cell(r: Int, c: Int): GridCell? {
        val text = sheet.rows.getOrNull(r)?.getOrNull(c) ?: ""
        val st = file.styles.getOrNull(sheet.styleAt(r, c))
        if (text.isEmpty() && (st == null || (st.fill == null && !st.border))) return null
        val kind = sheet.kindAt(r, c)
        val align = when (st?.halign) {
            "left" -> -1
            "center" -> 0
            "right" -> 1
            // 「標準」の配置: 数は右、文字は左、真偽値・エラーは中央
            else -> when (kind) { XlsxSheet.KIND_NUMBER -> 1; XlsxSheet.KIND_BOOL_ERROR -> 0; else -> -1 }
        }
        return GridCell(text, st?.fill, st?.color, st?.bold ?: false, st?.italic ?: false, align, st?.wrap ?: false, st?.border ?: false, st?.sizePt)
    }

    override fun colWidth(c: Int): Float = ((sheet.colWidths[c] ?: 8.43f) * 7.4f + 6f).coerceIn(28f, 420f)
}

/** CSV の表。headerOn のときは、1 行目を見出し(固定・太字・色つき)にする。 */
internal class CsvGridModel(private val table: Csv.Table, private val headerOn: Boolean) : GridModel {
    override val rowCount = table.rows.size
    override val colCount = table.columns
    override val frozenRows = if (headerOn && rowCount > 0) 1 else 0

    private val numeric = Regex("^[+-]?[\\d,]*\\.?\\d+([eE][+-]?\\d+)?%?$")
    private val widths: List<Float> = List(colCount) { c ->
        var m = 0
        for (r in 0 until minOf(table.rows.size, 300)) {
            val s = table.rows[r].getOrNull(c) ?: continue
            var w = 0
            for (ch in s.lineSequence().firstOrNull() ?: "") w += if (ch.code >= 0x2E80) 2 else 1
            if (w > m) m = w
        }
        (m * 7.4f + 16f).coerceIn(48f, 360f)
    }

    override fun span(r: Int, c: Int) = 1

    override fun cell(r: Int, c: Int): GridCell? {
        val text = table.rows.getOrNull(r)?.getOrNull(c) ?: ""
        val head = headerOn && r == 0
        if (text.isEmpty() && !head) return null
        val align = if (!head && numeric.matches(text.trim())) 1 else -1
        return GridCell(text, if (head) 0xFFE7ECF4.toInt() else null, null, head, false, align, false, head)
    }

    override fun colWidth(c: Int): Float = widths.getOrElse(c) { 68f }
}

/**
 * Excel のような表(列は A・B・C…、行は番号、薄い罫線)。白い背景。上の見出しの行は固定で、横にも縦にもスクロールできる。
 * 長い文字のセルは、タップで全文を見られる。
 */
@Composable
internal fun SheetGrid(model: GridModel, fontSp: Int, modifier: Modifier = Modifier, dark: Boolean = false) {
    val oc = remember(dark) { OfficeColors(dark) }
    val gridLine = oc.gridLine
    val headerFill = oc.headerFill
    val headerText = oc.headerText
    val s = fontSp / 14f
    val widths = remember(model, s) { List(model.colCount) { (model.colWidth(it) * s).dp } }
    val numW = ((model.rowCount.toString().length * 9 + 22) * s).dp
    val rowH = (22f * s).dp
    var detail by remember { mutableStateOf<String?>(null) }
    val hs = rememberScrollState()

    @Composable
    fun RowOf(r: Int) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(numW).fillMaxHeight().heightIn(min = rowH).background(headerFill).border(0.5.dp, gridLine), contentAlignment = Alignment.Center) {
                Text("${r + 1}", color = headerText, fontSize = (11 * s).sp)
            }
            var c = 0
            while (c < model.colCount) {
                val span = model.span(r, c)
                if (span == 0) { c++; continue }
                val w: Dp = (c until minOf(c + span, model.colCount)).fold(0.dp) { a, i -> a + widths[i] }
                val cell = model.cell(r, c)
                val tall = cell != null && (cell.text.length > 18 || cell.text.contains('\n'))
                Box(
                    Modifier.width(w).fillMaxHeight().heightIn(min = rowH)
                        .background(cell?.fill?.let { oc.c(it) } ?: oc.paper)
                        .border(if (cell?.border == true) 0.8.dp else 0.5.dp, if (cell?.border == true) oc.strongLine else gridLine)
                        .let { if (tall) it.clickable { detail = cell?.text } else it }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    contentAlignment = when (cell?.align) { 0 -> Alignment.TopCenter; 1 -> Alignment.TopEnd; else -> Alignment.TopStart },
                ) {
                    if (cell != null && cell.text.isNotEmpty()) {
                        Text(
                            cell.text,
                            color = cell.color?.let { oc.c(it) } ?: oc.ink,
                            fontSize = ((cell.sizePt ?: 11f) * 1.05f * s).sp,
                            fontWeight = if (cell.bold) FontWeight.Bold else null,
                            fontStyle = if (cell.italic) FontStyle.Italic else null,
                            textAlign = when (cell.align) { 0 -> TextAlign.Center; 1 -> TextAlign.End; else -> TextAlign.Start },
                            maxLines = if (cell.wrap) Int.MAX_VALUE else 1,
                            overflow = if (cell.wrap) TextOverflow.Clip else TextOverflow.Ellipsis,
                            softWrap = cell.wrap,
                        )
                    }
                }
                c += span
            }
        }
    }

    Box(modifier.background(oc.paper).horizontalScroll(hs)) {
        Column(Modifier.fillMaxHeight()) {
            // 列の見出し(A・B・C…)
            Row(Modifier.height(rowH)) {
                Box(Modifier.width(numW).fillMaxHeight().background(headerFill).border(0.5.dp, gridLine))
                for (c in 0 until model.colCount) {
                    Box(Modifier.width(widths[c]).fillMaxHeight().background(headerFill).border(0.5.dp, gridLine), contentAlignment = Alignment.Center) {
                        Text(XlsxFormat.columnName(c), color = headerText, fontSize = (11 * s).sp)
                    }
                }
            }
            for (r in 0 until model.frozenRows.coerceAtMost(model.rowCount)) RowOf(r)
            if (model.frozenRows > 0) Box(Modifier.fillMaxWidth().height(1.5.dp).background(oc.frozenLine))
            LazyColumn(Modifier.weight(1f)) {
                items(maxOf(0, model.rowCount - model.frozenRows)) { i -> RowOf(i + model.frozenRows) }
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
