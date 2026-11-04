package local.filemanager

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private val slideBackdrop = Color(0xFFE6E6E6)

/** スライドの絵を読み込んで覚えておく入れ物(開いたままの ZIP から、見るときに読む)。 */
private class SlideImages(private val read: (String) -> ByteArray?) {
    private val lock = Any()
    private val map = object : LinkedHashMap<String, Bitmap?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap?>?) = size > 24
    }

    fun load(path: String): Bitmap? {
        synchronized(map) { if (map.containsKey(path)) return map[path] }
        val bytes = synchronized(lock) { runCatching { read(path) }.getOrNull() }
        val b = bytes?.let { runCatching { ImageDecode.decodeBytes(it, 1600) }.getOrNull() }
        synchronized(map) { map[path] = b }
        return b
    }
}

/**
 * PowerPoint のスライドを、位置・大きさ・図形・画像・表・文字の書式のまま描く。
 * 倍率は、画面の上での拡大(ピンチ・ダブルタップ)。
 */
@Composable
internal fun SlidesView(deck: SlideDeck, read: (String) -> ByteArray?, dark: Boolean = false, copy: CopyHolder? = null) {
    val images = remember(deck) { SlideImages(read) }
    // スライドの絵はそのまま。まわりの背景だけを暗くする
    val backdrop = if (dark) Color(0xFF0E0E0E) else slideBackdrop
    if (deck.slides.isEmpty()) {
        Box(Modifier.fillMaxSize().background(backdrop)) { Text("スライドがありません", color = if (dark) Color(0xFFB0B0B0) else Color.DarkGray, modifier = Modifier.align(Alignment.Center)) }
        return
    }
    DisposableEffect(deck, copy) {
        copy?.set("全スライドの文字をコピー") { deck.plainText() }
        onDispose { copy?.provider = null }
    }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var scale by remember { mutableFloatStateOf(1f) }
    var tx by remember { mutableFloatStateOf(0f) }

    fun clampTx(t: Float, s: Float, viewW: Float) = t.coerceIn(viewW * (1f - s), 0f)

    fun zoomAt(newScale: Float, cx: Float, cy: Float, viewW: Float) {
        val s0 = scale
        var s1 = newScale.coerceIn(1f, 4f)
        if (s1 < 1.03f) s1 = 1f
        if (s1 == s0) return
        val xLocal = (cx - tx) / s0
        tx = if (s1 == 1f) 0f else clampTx(cx - s1 * xLocal, s1, viewW)
        val dy = cy / s0 - cy / s1
        scale = s1
        scope.launch { list.scrollBy(dy) }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(backdrop).belowBars().clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { p -> zoomAt(if (scale > 1.05f) 1f else 2.2f, p.x, p.y, size.width.toFloat()) })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val viewW = size.width.toFloat()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2) {
                            val z = event.calculateZoom()
                            val pan = event.calculatePan()
                            val c = event.calculateCentroid()
                            if (z != 1f || pan != Offset.Zero) {
                                zoomAt(scale * z, c.x, c.y, viewW)
                                tx = clampTx(tx + pan.x, scale, viewW)
                                if (pan.y != 0f) scope.launch { list.scrollBy(-pan.y / scale) }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } else if (fingers == 1 && scale > 1f) {
                            val dx = event.changes.firstOrNull { it.pressed }?.positionChange()?.x ?: 0f
                            if (dx != 0f) tx = clampTx(tx + dx, scale, viewW)
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        val cardWidth = maxWidth - 16.dp
        CopyableArea {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxWidth().height(maxHeight / scale).graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = tx
                transformOrigin = TransformOrigin(0f, 0f)
            },
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(deck.slides.size) { i -> SlideCard(deck.slides[i], deck, cardWidth, images, dark) }
        }
        }
        Text(
            "${list.firstVisibleItemIndex + 1} / ${deck.slides.size}" + if (scale > 1f) "  ×${"%.1f".format(scale)}" else "",
            color = Color.White, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)
                .background(Color(0xAA000000), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun SlideCard(slide: SlideDoc, deck: SlideDeck, cardWidth: Dp, images: SlideImages, dark: Boolean = false) {
    val s = cardWidth.value / deck.widthPt
    Column {
        Text(
            "${slide.number}" + if (slide.hidden) "(非表示のスライド)" else "",
            color = if (dark) Color(0xFF9A9A9A) else Color(0xFF777777), fontSize = 11.sp, modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
        )
        Box(Modifier.width(cardWidth).height((deck.heightPt * s).dp).clipToBounds().background(Color.White)) {
            Background(slide.background, images)
            for (sh in slide.shapes) ShapeView(sh, s, images, slide, deck)
        }
    }
}

@Composable
private fun Background(bg: Paint?, images: SlideImages) {
    when (bg) {
        is Paint.Solid -> Box(Modifier.fillMaxSize().background(Color(bg.argb)))
        is Paint.Grad -> Canvas(Modifier.fillMaxSize()) { drawRect(gradientBrush(bg, size.width, size.height)) }
        is Paint.Blip -> {
            val bmp by produceState<Bitmap?>(null, bg.path) { value = withContext(Dispatchers.IO) { images.load(bg.path) } }
            val b = bmp
            if (b != null) {
                val img = remember(b) { b.asImageBitmap() }
                Canvas(Modifier.fillMaxSize()) {
                    drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset.Zero, IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Medium)
                }
            }
        }
        else -> {}
    }
}

private fun gradientBrush(g: Paint.Grad, w: Float, h: Float): Brush {
    val rad = Math.toRadians(g.angle.toDouble())
    val dx = cos(rad).toFloat()
    val dy = sin(rad).toFloat()
    val len = abs(w * dx) + abs(h * dy)
    val cx = w / 2f
    val cy = h / 2f
    val stops = g.stops.sortedBy { it.first }.map { it.first.coerceIn(0f, 1f) to Color(it.second) }.toTypedArray()
    return Brush.linearGradient(
        colorStops = stops,
        start = Offset(cx - dx * len / 2f, cy - dy * len / 2f),
        end = Offset(cx + dx * len / 2f, cy + dy * len / 2f),
    )
}

private fun addContour(path: Path, c: Contour, k: Float) {
    val p = c.pts
    if (p.size < 4) return
    path.moveTo(p[0] * k, p[1] * k)
    var i = 2
    while (i + 1 < p.size) {
        path.lineTo(p[i] * k, p[i + 1] * k)
        i += 2
    }
    if (c.closed) path.close()
}

private fun DrawScope.arrowHead(x: Float, y: Float, fromX: Float, fromY: Float, size: Float, color: Color) {
    val ang = atan2(y - fromY, x - fromX)
    val a1 = ang + 2.6f
    val a2 = ang - 2.6f
    val p = Path()
    p.moveTo(x, y)
    p.lineTo(x + size * cos(a1), y + size * sin(a1))
    p.lineTo(x + size * cos(a2), y + size * sin(a2))
    p.close()
    drawPath(p, color)
}

private fun DrawScope.drawGeometry(sh: SShape, contours: List<Contour>, k: Float) {
    val fillPath = Path().apply { fillType = PathFillType.EvenOdd }
    val strokePath = Path()
    for (c in contours) {
        if (c.fill) addContour(fillPath, c, k)
        if (c.stroke) addContour(strokePath, c, k)
    }
    when (val f = sh.fill) {
        is Paint.Solid -> drawPath(fillPath, Color(f.argb))
        is Paint.Grad -> drawPath(fillPath, gradientBrush(f, size.width, size.height))
        else -> {}
    }
    val ln = sh.line ?: return
    val wpx = max(ln.widthPt * k, 1f)
    val color = Color(ln.argb)
    drawPath(
        strokePath, color,
        style = Stroke(width = wpx, pathEffect = ln.dash?.let { d -> PathEffect.dashPathEffect(FloatArray(d.size) { d[it] * k.coerceAtLeast(0.3f) }, 0f) }),
    )
    if (ln.head || ln.tail) {
        val open = contours.firstOrNull { !it.closed && it.stroke && it.pts.size >= 4 }
        if (open != null) {
            val p = open.pts
            val n = p.size
            val size = max(wpx * 3.5f, 6f)
            if (ln.head) arrowHead(p[0] * k, p[1] * k, p[2] * k, p[3] * k, size, color)
            if (ln.tail) arrowHead(p[n - 2] * k, p[n - 1] * k, p[n - 4] * k, p[n - 3] * k, size, color)
        }
    }
}

@Composable
private fun ShapeView(sh: SShape, s: Float, images: SlideImages, slide: SlideDoc, deck: SlideDeck) {
    val density = LocalDensity.current
    val wDp = (sh.w * s).dp.coerceAtLeast(1.dp)
    val hDp = (sh.h * s).dp.coerceAtLeast(1.dp)
    val k = s * density.density
    val contours = remember(sh) { sh.geom?.let { SlideGeom.contours(it, sh.w, sh.h, sh.flipH, sh.flipV) } }
    // 傾けた文字・縦書きの文字は、Compose の文字選択の仕組みが位置を合わせられず、長押しで落ちる。
    // そこだけ選択の対象から外し、長押しでメニューを出して、文字をコピーする(まっすぐな文字は、今まで通り長押しで選べる)
    val special = sh.rot != 0f || sh.text?.vert?.let { it != "horz" } == true
    var menu by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    Box(
        Modifier.offset((sh.x * s).dp, (sh.y * s).dp).size(wDp, hDp).graphicsLayer { rotationZ = sh.rot }
            .let { if (special) it.onLongPress { menu = true } else it },
    ) {
        val pic = sh.picture
        if (pic != null) {
            PictureBox(pic, contours, sh, k, images)
        } else if (contours != null && (sh.fill != null || sh.line != null)) {
            Canvas(Modifier.fillMaxSize()) { drawGeometry(sh, contours, k) }
        }
        val words: @Composable () -> Unit = {
            sh.table?.let { TableView(it, s, density) }
            sh.text?.let { BodyText(it, s, sh.w, sh.h, density) }
        }
        if (special) DisableSelection { words() } else words()
        if (menu) {
            val own = (sh.text?.takeIf { it.hasText }?.plainText() ?: sh.table?.plainText()).orEmpty()
            ListMenu(
                expanded = true, dark = Pv.dark, onDismiss = { menu = false },
                items = listOfNotNull(
                    if (own.isNotBlank()) VMenuItem.Action(Icons.Default.ContentCopy, "この文字をコピー") { copyToClipboard(ctx, own) } else null,
                    VMenuItem.Action(Icons.Default.ContentCopy, "このスライドの文字をコピー") { copyToClipboard(ctx, slide.plainText()) },
                    VMenuItem.Action(Icons.Default.ContentCopy, "全スライドの文字をコピー") { copyToClipboard(ctx, deck.plainText()) },
                ),
            )
        }
    }
}

@Composable
private fun PictureBox(pic: SPicture, contours: List<Contour>?, sh: SShape, k: Float, images: SlideImages) {
    val bmp by produceState<Bitmap?>(null, pic.path) { value = withContext(Dispatchers.IO) { images.load(pic.path) } }
    val b = bmp
    val img = remember(b) { b?.asImageBitmap() }
    Canvas(
        Modifier.fillMaxSize().graphicsLayer {
            scaleX = if (sh.flipH) -1f else 1f
            scaleY = if (sh.flipV) -1f else 1f
        },
    ) {
        if (img == null) {
            drawRect(Color(0xFFE8E8E8))
        } else {
            val sx = (pic.cropL * img.width).toInt().coerceIn(0, img.width - 1)
            val sy = (pic.cropT * img.height).toInt().coerceIn(0, img.height - 1)
            val sw = (img.width - sx - (pic.cropR * img.width).toInt()).coerceIn(1, img.width - sx)
            val shh = (img.height - sy - (pic.cropB * img.height).toInt()).coerceIn(1, img.height - sy)
            val round = sh.geom != null && sh.geom.prst != "rect" && contours != null
            val draw: DrawScope.() -> Unit = {
                drawImage(img, IntOffset(sx, sy), IntSize(sw, shh), IntOffset.Zero, IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Medium)
            }
            if (round) {
                val clip = Path()
                for (c in contours!!) addContour(clip, c, k)
                clipPath(clip) { draw() }
            } else {
                draw()
            }
        }
        val ln = sh.line
        if (ln != null && contours != null) {
            val sp = Path()
            for (c in contours) addContour(sp, c, k)
            drawPath(sp, Color(ln.argb), style = Stroke(width = max(ln.widthPt * k, 1f)))
        }
    }
}

// ---------------------------------------------------------------- 文字

private fun alignmentOf(anchor: Int, algn: Int): Alignment = when (anchor) {
    1 -> when (algn) { 0 -> Alignment.Center; 1 -> Alignment.CenterEnd; else -> Alignment.CenterStart }
    2 -> when (algn) { 0 -> Alignment.BottomCenter; 1 -> Alignment.BottomEnd; else -> Alignment.BottomStart }
    else -> when (algn) { 0 -> Alignment.TopCenter; 1 -> Alignment.TopEnd; else -> Alignment.TopStart }
}

/** 図形や表のセルの中の文字。縦位置(上・中央・下)と余白に合わせて並べ、はみ出しても切らない。 */
@Composable
private fun BodyText(body: STextBody, s: Float, wPt: Float, hPt: Float, density: Density) {
    val vert = body.vert
    if (vert != null && vert != "horz") {
        // 縦書きは、横書きを 90 度回して近づける
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.align(Alignment.Center).requiredSize((hPt * s).dp.coerceAtLeast(1.dp), (wPt * s).dp.coerceAtLeast(1.dp))
                    .graphicsLayer { rotationZ = if (vert == "vert270") 270f else 90f },
            ) { BodyColumn(body, s, density) }
        }
    } else {
        BodyColumn(body, s, density)
    }
}

@Composable
private fun BodyColumn(body: STextBody, s: Float, density: Density) {
    val vAlign = when (body.anchor) { 1 -> Alignment.CenterVertically; 2 -> Alignment.Bottom; else -> Alignment.Top }
    var m = Modifier.fillMaxSize().padding(start = (body.l * s).dp, top = (body.t * s).dp, end = (body.r * s).dp, bottom = (body.b * s).dp)
    m = if (body.wrap) {
        m.wrapContentHeight(vAlign, unbounded = true)
    } else {
        m.wrapContentSize(alignmentOf(body.anchor, body.paras.firstOrNull { it.hasText }?.algn ?: -1), unbounded = true)
    }
    Column(m) {
        body.paras.forEachIndexed { i, p -> ParaView(p, s, density, i == 0, body.wrap) }
    }
}

private fun runStyle(r: SRun, s: Float, density: Density): SpanStyle {
    val deco = buildList {
        if (r.underline) add(TextDecoration.Underline)
        if (r.strike) add(TextDecoration.LineThrough)
    }
    val shift = r.baseline
    val size = r.sizePt * (if (shift != 0) 0.7f else 1f)
    return SpanStyle(
        fontSize = with(density) { (size * s).dp.toSp() },
        color = Color(r.color),
        fontWeight = if (r.bold) FontWeight.Bold else null,
        fontStyle = if (r.italic) FontStyle.Italic else null,
        textDecoration = if (deco.isEmpty()) null else TextDecoration.combine(deco),
        fontFamily = fontFamilyOf(r.font),
        baselineShift = when (shift) { 1 -> BaselineShift.Superscript; -1 -> BaselineShift.Subscript; else -> null },
    )
}

@Composable
private fun ParaView(p: SPara, s: Float, density: Density, first: Boolean, wrap: Boolean) {
    val text: AnnotatedString = remember(p, s, density.density) {
        buildAnnotatedString {
            for (r in p.runs) {
                val start = length
                append(r.text)
                addStyle(runStyle(r, s, density), start, length)
                if (r.link != null) addStringAnnotation("URL", r.link, start, length)
            }
        }
    }
    fun sp(pt: Float) = with(density) { (pt * s).dp.toSp() }
    val lineH = if (p.linePt != null) sp(p.linePt) else sp(p.sizePt * 1.2f * (p.lineMult ?: 1f))
    val align = when (p.algn) { 0 -> TextAlign.Center; 1 -> TextAlign.End; 2 -> TextAlign.Justify; else -> TextAlign.Start }
    val top = if (first) 0.dp else (p.beforePt * s).dp
    val bottom = (p.afterPt * s).dp
    val hasLink = p.runs.any { it.link != null }
    if (!p.hasText && p.marker == null) {
        androidx.compose.foundation.layout.Spacer(Modifier.fillMaxWidth().padding(top = top).height((p.sizePt * 1.2f * s).dp))
        return
    }
    val marker = p.marker
    if (marker != null) {
        val hang = if (p.indent < 0f) -p.indent else p.sizePt * 0.9f
        val startPad = if (p.indent < 0f) (p.marL + p.indent).coerceAtLeast(0f) else p.marL
        Row(Modifier.fillMaxWidth().padding(start = (startPad * s).dp, top = top, bottom = bottom)) {
            Text(
                marker,
                softWrap = false,
                modifier = Modifier.width((hang * s).dp),
                style = TextStyle(
                    color = Color(p.markerColor ?: 0xFF000000.toInt()), fontSize = sp(p.sizePt * p.markerScale), lineHeight = lineH,
                ),
            )
            ParaText(text, TextStyle(lineHeight = lineH, textAlign = align), Modifier.weight(1f), hasLink, wrap)
        }
    } else {
        ParaText(
            text,
            TextStyle(lineHeight = lineH, textAlign = align, textIndent = if (p.indent != 0f) TextIndent(firstLine = sp(p.indent)) else null),
            Modifier.fillMaxWidth().padding(start = (p.marL * s).dp, top = top, bottom = bottom),
            hasLink, wrap,
        )
    }
}

@Composable
private fun ParaText(text: AnnotatedString, style: TextStyle, modifier: Modifier, hasLink: Boolean, wrap: Boolean) {
    if (hasLink) {
        LinkText(text, style, modifier) {}
    } else {
        Text(text, style = style, modifier = modifier, softWrap = wrap)
    }
}

// ---------------------------------------------------------------- 表

@Composable
private fun TableView(t: STable, s: Float, density: Density) {
    // 文字が多いと行は枠より高くなる(PowerPoint でも同じ)。高さを切らない
    Column(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)) {
        for ((ri, row) in t.rows.withIndex()) {
            Row(Modifier.heightIn(min = ((t.rowH.getOrNull(ri) ?: 0f) * s).dp).height(IntrinsicSize.Min)) {
                var col = 0
                for (cell in row) {
                    if (cell.hMerge) continue
                    val span = cell.gridSpan.coerceAtLeast(1)
                    var wPt = 0f
                    for (i in col until minOf(col + span, t.colW.size)) wPt += t.colW[i]
                    col += span
                    CellView(cell, (wPt * s).dp, s, wPt, density)
                }
            }
        }
    }
}

@Composable
private fun CellView(cell: STableCell, width: Dp, s: Float, wPt: Float, density: Density) {
    val fill = cell.fill
    val align = when (cell.body.anchor) { 1 -> Alignment.CenterStart; 2 -> Alignment.BottomStart; else -> Alignment.TopStart }
    Box(
        Modifier.width(width).fillMaxHeight()
            .let { if (fill != null) it.background(Color(fill)) else it }
            .drawBorders(cell, s * density.density),
        contentAlignment = align,
    ) {
        if (cell.body.paras.isNotEmpty()) {
            val b = cell.body
            Column(Modifier.fillMaxWidth().padding(start = (b.l * s).dp, end = (b.r * s).dp, top = (b.t * s).dp, bottom = (b.b * s).dp)) {
                b.paras.forEachIndexed { i, p -> ParaView(p, s, density, i == 0, true) }
            }
        }
    }
}

private fun Modifier.drawBorders(cell: STableCell, k: Float): Modifier = this.drawBehind {
    fun edge(l: SLine?, x0: Float, y0: Float, x1: Float, y1: Float) {
        if (l == null) return
        drawLine(Color(l.argb), Offset(x0, y0), Offset(x1, y1), strokeWidth = max(l.widthPt * k, 1f))
    }
    edge(cell.top, 0f, 0f, size.width, 0f)
    edge(cell.bottom, 0f, size.height, size.width, size.height)
    edge(cell.left, 0f, 0f, 0f, size.height)
    edge(cell.right, size.width, 0f, size.width, size.height)
}
