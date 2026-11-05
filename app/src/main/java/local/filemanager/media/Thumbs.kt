package local.filemanager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * NAS 上の画像・動画のサムネイル。
 * 同時に読む数を絞り(NAS と一覧の動きを重くしない)、作ったものは端末に保存して、2回目からはすぐ出す。
 */
object Thumbs {
    /** NAS を読む専用のスレッド。端末内の読み込みとは別にして、少なめにしてある。 */
    val dispatcher = backgroundPool(2, "filer-thumbs").asCoroutineDispatcher()

    private const val CACHE_LIMIT_BYTES = 150L * 1024 * 1024
    private const val VIDEO_READ_BUDGET = 48L * 1024 * 1024
    private val writes = AtomicInteger()

    private fun cacheDir(ctx: Context) = File(ctx.cacheDir, "thumbs").apply { mkdirs() }

    /** 場所・更新日時・大きさ・サイズ区分から決まる保存名。中身が変われば別の名前になる。 */
    fun cacheName(fsId: String, item: FileItem, big: Boolean): String {
        val raw = "$fsId|${item.path}|${item.modified}|${item.size}|${if (big) "L" else "S"}"
        val h = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
        return h.joinToString("") { "%02x".format(it) } + ".jpg"
    }

    /** 失敗したら null(アイコンのまま表示される)。呼ぶのは [dispatcher] 上。 */
    fun load(ctx: Context, fs: Fs, item: FileItem, big: Boolean): Bitmap? {
        val side = if (big) 320 else 144
        val dir = cacheDir(ctx)
        val file = File(dir, cacheName(fs.id, item, big))
        if (file.isFile) {
            BitmapFactory.decodeFile(file.path)?.let {
                file.setLastModified(System.currentTimeMillis()) // 新しく使ったものを残す目印
                return it
            }
            file.delete()
        }
        val bmp = try {
            when (item.kind) {
                FileKind.IMAGE -> imageThumb(fs, item, side)
                FileKind.VIDEO -> videoThumb(fs, item, side)
                else -> null
            }
        } catch (e: Throwable) {
            null
        } ?: return null
        runCatching {
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            if (writes.incrementAndGet() % 40 == 0) trim(dir, CACHE_LIMIT_BYTES)
        }
        return bmp
    }

    private fun imageThumb(fs: Fs, item: FileItem, side: Int): Bitmap? {
        if (FileOps.extensionOf(item.name) == "svg") {
            if (item.size !in 1..SvgRender.MAX_BYTES) return null
            return SvgRender.render(fs.open(item.path).use { it.readBytes() }, side)
        }
        if (item.size in 1..ImageDecode.MAX_THUMB_SOURCE_BYTES) {
            val bytes = fs.open(item.path).use { it.readBytes() }
            return ImageDecode.decodeBytes(bytes, side * 2)?.let { ImageDecode.fit(it, side) }
        }
        // 大きすぎるものは、ファイルの先頭に埋め込まれた小さな画像だけを使う(全部は読まない)
        return fs.open(item.path).use { ImageDecode.embeddedThumbnail(it) }?.let { ImageDecode.fit(it, side) }
    }

    private fun videoThumb(fs: Fs, item: FileItem, side: Int): Bitmap? {
        val src = fs.openRandom(item.path) ?: return null
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(BudgetedSource(src, VIDEO_READ_BUDGET))
            // 時間に負の値を渡すと、代表的なコマを選んでくれる
            val frame = mmr.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            return ImageDecode.fit(frame, side)
        } finally {
            runCatching { mmr.release() }
            src.close()
        }
    }

    /** NAS のファイルを MediaMetadataRetriever に渡す窓口。読みすぎ(索引の無い長い動画など)は途中で打ち切る。 */
    private class BudgetedSource(private val src: RandomSource, private val budget: Long) : MediaDataSource() {
        private var used = 0L

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (used > budget) throw IOException(Txt(R.string.thumbs_001))
            val n = src.readAt(position, buffer, offset, size)
            if (n > 0) used += n
            return n
        }

        override fun getSize(): Long = src.size

        override fun close() {}
    }

    /** 保存したサムネイルの合計が max バイトを超えたら、古いもの(最後に使った順)から消す。 */
    fun trim(dir: File, max: Long) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= max) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= max) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }
}
