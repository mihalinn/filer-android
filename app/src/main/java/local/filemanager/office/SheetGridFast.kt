package local.filemanager

import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 画面に出す 1 つ分の文字(長ければ「…」で切ったもの)と、その幅(px)。 */
private class Shown(val text: String, val width: Float)

/** セルの見た目の覚え書きで、「このセルは空」を表す印。 */
private val NO_CELL = Any()

/**
 * Excel 風の表(CSV・Excel・SQLite の結果)を、軽く描く。以前の描き方は、セルごとに部品(文字・枠・押す仕組み)を持つので、
 * 列も行も多い表で、動かすたびに大量の作り直しと GC が起きてカクついた。こちらは、
 *  ・1 行を 1 つの部品にして、見えているセルの文字・色・線を、その中で直接描く(セルごとの部品は無い)
 *  ・横にスクロールしても組み立て直さず、描き直すだけ(見えている列だけを描く)
 *  ・押す・長押しは表全体で 1 つだけ受けて、場所から行と列を割り出す
 *  ・「…」で切った文字・折り返した文字・セルの見た目・行の高さは覚えておき、描くたびに作り直さない
 * ようにしている。結合したセル(横)、折り返して高くなる行、太字・斜体・色・背景・枠にも対応する。
 */
@Composable
internal fun FastGrid(
    model: GridModel, fontSp: Int, modifier: Modifier, dark: Boolean, copyEnabled: Boolean,
    selected: Pair<Int, Int>?, onCellTap: ((Int, Int) -> Unit)?,
    zoom: Float, scroll: GridScrollHandle,
) {
    val oc = remember(dark) { OfficeColors(dark) }
    val density = LocalDensity.current
    val d = density.density
    val s = fontSp / 14f * zoom
    val colCount = model.colCount
    val uniform = model.uniformRows
    // dp の長さ
    val widths = remember(model, s) { FloatArray(colCount) { model.colWidth(it) * s } }
    val colStart = remember(widths) { FloatArray(colCount + 1).also { a -> for (i in 0 until colCount) a[i + 1] = a[i] + widths[i] } }
    val numW = (model.rowCount.toString().length * 9 + 22) * s
    val rowH = 22f * s
    val frozen = model.frozenRows.coerceAtMost(model.rowCount)
    val totalW = numW + colStart[colCount]
    // px の長さ
    val colStartPx = remember(colStart, d) { FloatArray(colCount + 1) { colStart[it] * d } }
    val numWpx = numW * d
    val rowHpxMin = rowH * d
    val hs = rememberScrollState()
    val listState = rememberLazyListState()
    scroll.hs = hs
    scroll.list = listState
    var viewportPx by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf<Triple<Int, Int, IntOffset>?>(null) }
    val tap by rememberUpdatedState(onCellTap)
    // 描くときに読む(変わっても組み立て直さず、描き直すだけ)
    val sel by rememberUpdatedState(selected)
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val shownCache = remember(model, s, d, fontSp) { HashMap<Long, Shown>() }
    val layoutCache = remember(model, s, d, fontSp, dark) { HashMap<Long, StaticLayout>() }
    val heightCache = remember(model, s, d, fontSp, dark) { HashMap<Int, Float>() }
    val inkArgb = oc.ink.toArgb()
    val headArgb = oc.headerText.toArgb()
    // セルの見た目も覚えておく(描くたびに作り直すと、動かすたびに大量のゴミが出る)。中身が無いセルは NO_CELL
    val cellCache = remember(model) { HashMap<Long, Any>() }
    val padX = 4f * d
    val padY = 2f * d

    fun cellAt(r: Int, c: Int): GridCell? {
        val key = r.toLong() * (colCount + 1L) + c
        val v = cellCache.getOrPut(key) { model.cell(r, c) ?: NO_CELL }
        return v as? GridCell
    }

    /** 左端から xRel(px。行番号の列の右から数える)の位置にある列。範囲の外なら両端。結合したセルの右側なら、その左端の列。 */
    fun columnAt(xRel: Float, r: Int): Int {
        if (colCount == 0) return 0
        var lo = 0
        var hi = colCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (colStartPx[mid] <= xRel) lo = mid else hi = mid - 1
        }
        if (r >= 0 && !uniform) while (lo > 0 && model.span(r, lo) == 0) lo--
        return lo
    }

    /** 折り返す文字の組み版。幅は枠の幅(px)。 */
    fun layoutFor(r: Int, c: Int, cell: GridCell, wPx: Float): StaticLayout =
        layoutCache.getOrPut(r.toLong() * (colCount + 1L) + c) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
            tp.textSize = with(density) { ((cell.sizePt ?: 11f) * 1.05f * s).sp.toPx() }
            tp.isFakeBoldText = cell.bold
            tp.textSkewX = if (cell.italic) -0.25f else 0f
            tp.color = cell.color?.let { oc.c(it).toArgb() } ?: inkArgb
            val text = if (cell.text.length > 4000) cell.text.take(4000) else cell.text
            StaticLayout.Builder.obtain(text, 0, text.length, tp, maxOf(1, (wPx - 2 * padX).toInt()))
                .setAlignment(
                    when (cell.align) {
                        0 -> Layout.Alignment.ALIGN_CENTER
                        1 -> Layout.Alignment.ALIGN_OPPOSITE
                        else -> Layout.Alignment.ALIGN_NORMAL
                    },
                )
                .setIncludePad(false)
                .build()
        }

    /** r 行目の高さ(px)。折り返す文字があれば、その分だけ高くなる(最大 409pt まで。Excel と同じ上限)。 */
    fun rowHpx(r: Int): Float {
        if (uniform) return rowHpxMin
        return heightCache.getOrPut(r) {
            var need = 0f
            var c = 0
            while (c < colCount) {
                val sp = model.span(r, c)
                if (sp == 0) { c++; continue }
                val cell = cellAt(r, c)
                if (cell != null && cell.wrap && cell.text.isNotEmpty()) {
                    val end = minOf(c + sp, colCount)
                    need = maxOf(need, layoutFor(r, c, cell, colStartPx[end] - colStartPx[c]).height + 2 * padY)
                }
                c += sp
            }
            maxOf(rowHpxMin, minOf(need, 409f * s * d))
        }
    }

    /** 1 行の文字を、枠 [x0, x1) の中に描く(はみ出すなら「…」で切る)。align: -1 左 / 0 中央 / 1 右 */
    fun DrawScope.text(key: Long, str: String, x0: Float, x1: Float, sizeSp: Float, bold: Boolean, italic: Boolean, argb: Int, align: Int, top: Boolean) {
        val avail = (x1 - x0) - 2 * padX
        if (avail <= 0f || str.isEmpty()) return
        paint.textSize = sizeSp.sp.toPx()
        paint.isFakeBoldText = bold
        paint.textSkewX = if (italic) -0.25f else 0f
        paint.color = argb
        val shown = shownCache.getOrPut(key) {
            val nl = str.indexOf('\n')
            val line = if (nl >= 0) str.substring(0, nl) else str
            val n = paint.breakText(line, true, avail, null)
            val out = if (n < line.length || nl >= 0) line.substring(0, maxOf(0, minOf(n, line.length) - 1)) + "…" else line
            Shown(out, paint.measureText(out))
        }
        val x = when (align) {
            0 -> (x0 + x1) / 2f - shown.width / 2f
            1 -> x1 - padX - shown.width
            else -> x0 + padX
        }
        val fm = paint.fontMetrics
        val baseline = if (top) padY - fm.ascent else (size.height - (fm.descent - fm.ascent)) / 2f - fm.ascent
        drawContext.canvas.nativeCanvas.drawText(shown.text, x, baseline, paint)
    }

    /** 1 行を描く。r = -1 は列の見出し(A・B・C…)。 */
    fun DrawScope.paintRow(r: Int) {
        if (shownCache.size > 20_000) shownCache.clear()
        if (cellCache.size > 20_000) cellCache.clear()
        if (layoutCache.size > 2_000) layoutCache.clear()
        val h = size.height
        val left = hs.value.toFloat()
        val right = left + viewportPx
        val line = 0.5.dp.toPx()
        val head = r < 0
        val top = !uniform
        if (head) drawRect(oc.headerFill, Offset.Zero, Size(size.width, h))
        // 行番号の列
        drawRect(oc.headerFill, Offset.Zero, Size(numWpx, h))
        if (!head && left < numWpx) text(-1L - r, "${r + 1}", 0f, numWpx, 11f * s, false, false, headArgb, 0, top)
        // 見えている列(結合したセルは、左端の列から)
        var c = columnAt(left - numWpx, r)
        while (c < colCount) {
            val x0 = numWpx + colStartPx[c]
            if (x0 >= right) break
            val span = if (head || uniform) 1 else model.span(r, c).coerceAtLeast(1)
            val end = minOf(c + span, colCount)
            val x1 = numWpx + colStartPx[end]
            if (head) {
                text(c.toLong(), XlsxFormat.columnName(c), x0, x1, 11f * s, false, false, headArgb, 0, false)
            } else {
                val cell = cellAt(r, c)
                if (cell != null) {
                    cell.fill?.let { drawRect(oc.c(it), Offset(x0, 0f), Size(x1 - x0, h)) }
                    if (cell.text.isNotEmpty()) {
                        if (cell.wrap) {
                            val lay = layoutFor(r, c, cell, x1 - x0)
                            val nat = drawContext.canvas.nativeCanvas
                            nat.save()
                            nat.clipRect(x0, 0f, x1, h)
                            nat.translate(x0 + padX, padY)
                            lay.draw(nat)
                            nat.restore()
                        } else {
                            text(
                                (r + 1L) * (colCount + 1L) + c, cell.text, x0, x1, (cell.sizePt ?: 11f) * 1.05f * s, cell.bold, cell.italic,
                                cell.color?.let { oc.c(it).toArgb() } ?: inkArgb, cell.align, top,
                            )
                        }
                    }
                }
            }
            drawLine(oc.gridLine, Offset(x1, 0f), Offset(x1, h), line)
            if (!head && cellAt(r, c)?.border == true) {
                val bw = 0.8.dp.toPx()
                drawRect(oc.strongLine, Offset(x0, 0f), Size(x1 - x0, h), style = Stroke(bw))
            }
            c = end
        }
        drawLine(oc.gridLine, Offset(numWpx, 0f), Offset(numWpx, h), line)
        drawLine(oc.gridLine, Offset(0f, h - line / 2), Offset(size.width, h - line / 2), line)
        // 編集で選んでいるセル
        val sc = sel
        if (!head && sc != null && sc.first == r && sc.second in 0 until colCount) {
            val x0 = numWpx + colStartPx[sc.second]
            val x1 = numWpx + colStartPx[sc.second + 1]
            if (x1 > left && x0 < right) {
                val bw = 2.dp.toPx()
                drawRect(Color(0xFF217346), Offset(x0 + bw / 2, bw / 2), Size(x1 - x0 - bw, h - bw), style = Stroke(bw))
            }
        }
    }

    @Composable
    fun RowOf(r: Int) {
        Box(Modifier.width(totalW.dp).height((rowHpx(r) / d).dp).drawBehind { paintRow(r) })
    }

    /** 押した場所(表の中の px)から、(行, 列)を割り出す。見出しや範囲の外なら null。 */
    fun hit(x: Float, y: Float): Pair<Int, Int>? {
        val xd = x - numWpx
        if (xd < 0f || colCount == 0 || y < rowHpxMin) return null
        var acc = rowHpxMin
        var r = -1
        for (fr in 0 until frozen) {
            val hh = rowHpx(fr)
            if (y < acc + hh) { r = fr; break }
            acc += hh
        }
        if (r < 0) {
            val yy = y - (acc + (if (frozen > 0) 1.5f * d else 0f))
            val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { yy >= it.offset && yy < it.offset + it.size } ?: return null
            r = frozen + item.index
        }
        return if (r in 0 until model.rowCount) r to columnAt(xd, r) else null
    }

    Box(modifier.onSizeChanged { viewportPx = it.width }.background(oc.paper).horizontalScroll(hs)) {
        Box(Modifier.width(totalW.dp).fillMaxHeight()) {
            Column(
                Modifier.fillMaxSize().pointerInput(model, s) {
                    detectTapGestures(
                        onTap = { o ->
                            hit(o.x, o.y)?.let { (r, c) ->
                                val t = tap
                                if (t != null) {
                                    t(r, c)
                                } else {
                                    val cell = model.cell(r, c)
                                    if (cell != null && (cell.text.length > 18 || cell.text.contains('\n'))) detail = cell.text
                                }
                            }
                        },
                        onLongPress = { o ->
                            if (copyEnabled) {
                                hit(o.x, o.y)?.let { (r, c) ->
                                    if (model.cell(r, c)?.text?.isNotEmpty() == true) menu = Triple(r, c, IntOffset(o.x.toInt(), o.y.toInt()))
                                }
                            }
                        },
                    )
                },
            ) {
                // 列の見出し(A・B・C…)、固定の行、縦にスクロールする行
                Box(Modifier.width(totalW.dp).height(rowH.dp).drawBehind { paintRow(-1) })
                for (r in 0 until frozen) RowOf(r)
                if (frozen > 0) Box(Modifier.width(totalW.dp).height(1.5.dp).background(oc.frozenLine))
                LazyColumn(Modifier.weight(1f).onGloballyPositioned { scroll.lazyTopPx = it.positionInParent().y }, state = listState) {
                    items(maxOf(0, model.rowCount - frozen)) { i -> RowOf(i + frozen) }
                }
            }
            menu?.let { (r, c, pos) ->
                Box(Modifier.offset { pos }.size(1.dp)) {
                    CellCopyMenu(
                        onDismiss = { menu = null },
                        cell = model.cell(r, c)?.text.orEmpty(),
                        row = { CopyText.row(model.colCount) { model.cell(r, it)?.text.orEmpty() } },
                        column = { CopyText.column(model.rowCount) { model.cell(it, c)?.text.orEmpty() } },
                        all = { CopyText.tsv(model.rowCount, model.colCount) { rr, cc -> model.cell(rr, cc)?.text.orEmpty() } },
                    )
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
