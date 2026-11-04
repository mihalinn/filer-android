package local.filemanager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfPasswordException
import io.legere.pdfiumandroid.PdfiumCore
import java.io.Closeable
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** render の結果。bmp が覆うページの範囲(0〜1 の割合)も返す(整数のピクセルに丸めるので、頼んだ範囲より少し広い)。 */
internal class PdfRegion(val bmp: Bitmap, val x0: Float, val y0: Float, val x1: Float, val y1: Float)

internal class PdfTextResult(val text: String, val truncated: Boolean) {
    val hasText: Boolean get() = text.isNotBlank()
}

/**
 * 開いた PDF 1 つ分(PDFium)。ページを絵にする・文字と位置を取る・文字を取り出す、をまとめる。
 * PDFium は同時に 1 つの呼び出ししか受けられないので、すべて lock の中で順番に行う。
 * パスワード付きの PDF は SecurityException。
 */
internal class PdfiumDoc(ctx: Context, val file: File) : Closeable {
    private val lock = Any()
    private val pfd: ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val pdf: PdfDocument = try {
        PdfiumCore(ctx.applicationContext).newDocument(pfd)
    } catch (e: PdfPasswordException) {
        runCatching { pfd.close() }
        throw SecurityException("password")
    } catch (e: Exception) {
        runCatching { pfd.close() }
        throw e
    }
    private var closed = false
    private val layouts = HashMap<Int, PdfPageText>()

    /** ページごとの大きさ(pt。回転は反映済み)。 */
    val sizes: List<Pair<Int, Int>> = try {
        List(pdf.getPageCount()) { i ->
            pdf.openPage(i).use { p -> p.getPageWidthPoint().coerceAtLeast(1) to p.getPageHeightPoint().coerceAtLeast(1) }
        }
    } catch (e: Exception) {
        runCatching { pdf.close() }
        runCatching { pfd.close() }
        throw e
    }

    val pageCount: Int get() = sizes.size

    /**
     * ページ全体を幅 fullW(ピクセル)にしたときの、(x0,y0)-(x1,y1)(ページの 0〜1 の割合)の範囲だけを絵にして返す。
     * 拡大したときに、見えている所だけを細かく描くために範囲を選べる。
     */
    fun render(index: Int, fullW: Int): Bitmap? = renderRegion(index, fullW, 0f, 0f, 1f, 1f)?.bmp

    fun renderRegion(index: Int, fullW: Int, x0: Float, y0: Float, x1: Float, y1: Float): PdfRegion? = synchronized(lock) {
        if (closed) return null
        val (pw, ph) = sizes[index]
        val fullH = (fullW.toLong() * ph / pw).toInt().coerceAtLeast(1)
        val left = floor(x0 * fullW).toInt().coerceAtLeast(0)
        val top = floor(y0 * fullH).toInt().coerceAtLeast(0)
        val bw = (ceil(x1 * fullW).toInt() - left).coerceIn(1, 8192)
        val bh = (ceil(y1 * fullH).toInt() - top).coerceIn(1, 8192)
        val m = Matrix()
        m.setScale(fullW.toFloat() / pw, fullH.toFloat() / ph)
        m.postTranslate(-left.toFloat(), -top.toFloat())
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        pdf.openPage(index).use { page ->
            page.renderPageBitmap(bmp, m, RectF(0f, 0f, bw.toFloat(), bh.toFloat()), true, false, Color.WHITE, Color.WHITE)
        }
        PdfRegion(bmp, left.toFloat() / fullW, top.toFloat() / fullH, (left + bw).toFloat() / fullW, (top + bh).toFloat() / fullH)
    }

    /** ページの文字と位置(左上が原点、単位は pt)。文字が無いページは、文字の無い PdfPageText。閉じた後は null。 */
    fun layout(index: Int): PdfPageText? = synchronized(lock) {
        if (closed || index !in sizes.indices) return null
        layouts[index]?.let { return it }
        val (pw, ph) = sizes[index]
        val chars = ArrayList<PdfChar>()
        pdf.openPage(index).use { page ->
            page.openTextPage().use { tp ->
                val n = tp.textPageCountChars().coerceAtMost(MAX_LAYOUT_CHARS)
                for (i in 0 until n) {
                    val ch = tp.textPageGetUnicode(i)
                    if (ch == '\r' || ch == '\n') { chars.lastOrNull()?.lineEnd = true; continue }
                    if (ch.code < 0x20 || ch == '￾' || ch == '￿') continue
                    val box = tp.textPageGetCharBox(i) ?: continue
                    // 文字の枠は、ページの座標(左下が原点)で返る。回転や余白を反映した画面向きの座標にする
                    val r = page.mapRectToDevice(0, 0, pw * K, ph * K, 0, box)
                    val l = min(r.left, r.right) / K.toFloat()
                    val t = min(r.top, r.bottom) / K.toFloat()
                    val w = abs(r.right - r.left) / K.toFloat()
                    val h = abs(r.bottom - r.top) / K.toFloat()
                    if (w <= 0.01f || h <= 0.01f) continue
                    chars.add(PdfChar(ch.toString(), l, t, w, h))
                }
            }
        }
        unifyLineBands(chars)
        PdfPageText(pw.toFloat(), ph.toFloat(), chars).also { layouts[index] = it }
    }

    /**
     * 文字を取り出す。pages は 0 から数えたページの範囲(null なら全ページ)。
     * maxChars を超えたら、そこで止めて truncated にする。isActive が false になったら、途中までの結果で止める。
     */
    fun extractText(pages: IntRange?, maxChars: Int, isActive: () -> Boolean = { true }): PdfTextResult {
        val last = pageCount - 1
        val range = (pages ?: (0..last)).let { it.first.coerceAtLeast(0)..it.last.coerceAtMost(last) }
        val sb = StringBuilder()
        var truncated = false
        for (p in range) {
            if (!isActive()) break
            val t = synchronized(lock) {
                if (closed) "" else pdf.openPage(p).use { page ->
                    page.openTextPage().use { tp ->
                        val n = tp.textPageCountChars()
                        if (n <= 0) "" else tp.textPageGetText(0, n).orEmpty()
                    }
                }
            }.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "").replace("￾", "").trim()
            if (t.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append(t)
            if (sb.length > maxChars) {
                truncated = true
                break
            }
        }
        return PdfTextResult(if (truncated) sb.substring(0, maxChars) else sb.toString(), truncated)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            layouts.clear()
            runCatching { pdf.close() }
            runCatching { pfd.close() }
        }
    }

    private fun abs(v: Int) = if (v < 0) -v else v

    private companion object {
        /** 座標の細かさ(1pt を K 等分)。整数で返る変換の誤差を小さくする */
        const val K = 8
        const val MAX_LAYOUT_CHARS = 100_000
    }
}
