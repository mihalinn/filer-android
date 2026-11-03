package local.filemanager

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.caverock.androidsvg.SVG
import java.io.ByteArrayInputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** SVG を、白い背景の絵(Bitmap)にする。外のファイルや通信は使わない(中に埋め込まれた画像だけ)。 */
object SvgRender {
    /** 読み込む SVG の大きさの上限 */
    const val MAX_BYTES = 16L * 1024 * 1024

    /** 長い辺が limit になる大きさで描く。読めない・描けないときは null。 */
    fun render(bytes: ByteArray, limit: Int): Bitmap? = try {
        val svg = SVG.getFromInputStream(ByteArrayInputStream(bytes))
        var w = svg.documentWidth
        var h = svg.documentHeight
        if (w <= 0f || h <= 0f) {
            val vb = svg.documentViewBox
            if (vb != null && vb.width() > 0f && vb.height() > 0f) {
                w = vb.width()
                h = vb.height()
            } else {
                w = 512f
                h = 512f
            }
        }
        val scale = limit / max(w, h)
        val bw = max(1, (w * scale).roundToInt())
        val bh = max(1, (h * scale).roundToInt())
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        // 中身が原寸のままにならないよう、「元の座標の範囲(viewBox)」を決めてから、書類の大きさを描く大きさに合わせる
        if (svg.documentViewBox == null) svg.setDocumentViewBox(0f, 0f, w, h)
        svg.setDocumentWidth(bw.toFloat())
        svg.setDocumentHeight(bh.toFloat())
        svg.renderToCanvas(canvas)
        bmp
    } catch (e: Throwable) {
        null // 壊れた SVG・メモリ不足を含む。表示できないだけで、アプリは落とさない
    }
}
