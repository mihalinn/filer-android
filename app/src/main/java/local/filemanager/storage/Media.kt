package local.filemanager

import android.content.Context
import android.provider.MediaStore
import java.io.File

/** ホーム画面の分類(画像・動画など)。端末のメディア情報(MediaStore)から引くので、全フォルダを走査せずに済む。 */
enum class Category(val label: String) {
    IMAGE("画像"), VIDEO("動画"), AUDIO("オーディオ"), DOCUMENT("ドキュメント"), APK("アプリ"), RECENT("新着ファイル")
}

object Media {
    private val docExts = listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf", "odt", "ods", "odp", "md", "epub")
    private const val RECENT_DAYS = 7

    private fun selection(cat: Category): String {
        val base = when (cat) {
            Category.IMAGE -> "media_type=1"
            Category.VIDEO -> "media_type=3"
            Category.AUDIO -> "media_type=2"
            Category.DOCUMENT -> docExts.joinToString(" OR ", "(", ")") { "_display_name LIKE '%.$it'" }
            Category.APK -> "_display_name LIKE '%.apk'"
            Category.RECENT -> "date_modified > ${System.currentTimeMillis() / 1000 - RECENT_DAYS * 86400L} AND media_type IN (0,1,2,3)"
        }
        // 0バイト(フォルダ等)・隠しフォルダ・ごみ箱の中は含めない
        return "$base AND _size > 0 AND _data NOT LIKE '%/.%'"
    }

    private val uri get() = MediaStore.Files.getContentUri("external")

    /** 新しい順の一覧。実在しないファイル(索引だけ残ったもの)は除く。 */
    fun query(ctx: Context, cat: Category, limit: Int = 5000): List<FileItem> {
        val out = ArrayList<FileItem>()
        ctx.contentResolver.query(
            uri, arrayOf("_data", "_size", "date_modified"), selection(cat), null, "date_modified DESC",
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                val path = c.getString(0) ?: continue
                val f = File(path)
                if (!f.isFile) continue
                out.add(FileItem(path, f.name, false, c.getLong(1), c.getLong(2) * 1000))
            }
        }
        return out
    }

    /** 件数と合計サイズ。 */
    fun summary(ctx: Context, cat: Category): Pair<Int, Long> {
        var count = 0
        var bytes = 0L
        ctx.contentResolver.query(uri, arrayOf("_size"), selection(cat), null, null)?.use { c ->
            while (c.moveToNext()) {
                count++
                bytes += c.getLong(0)
            }
        }
        return count to bytes
    }
}
