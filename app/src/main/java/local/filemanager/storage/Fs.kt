package local.filemanager

import androidx.compose.runtime.Immutable
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** 一覧の1行。端末内でも NAS でも同じ形で扱う(path は Fs ごとの絶対パス)。 */
@Immutable
data class FileItem(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    /** 中身の数。分からなければ -1(表示する行が後から数える)。 */
    val childCount: Int = -1,
) {
    val kind: FileKind get() = if (isDir) FileKind.FOLDER else FileOps.kindOf(name)
}

/** ファイルの好きな位置から読めるもの(動画のサムネイルなど、先頭から順にではなく一部だけ読みたいとき用)。 */
interface RandomSource {
    val size: Long

    /** pos から最大 len バイトを buf の off へ読む。読めた数を返し、終わりなら -1。 */
    fun readAt(pos: Long, buf: ByteArray, off: Int, len: Int): Int
    fun close()
}

/** 端末内ストレージと NAS(SMB)を同じ操作で扱うための入れ物。 */
interface Fs {
    val id: String
    val isLocal: Boolean

    /** 読めない・存在しない場所では null。 */
    fun list(path: String): List<FileItem>?
    fun stat(path: String): FileItem?
    fun mkdir(path: String)
    fun delete(path: String)

    /** 同じ場所で名前だけ変える。新しいパスを返す。 */
    fun rename(path: String, newName: String): String
    fun open(path: String): InputStream
    fun create(path: String): OutputStream

    /** この場所がある記憶領域の (全体, 空き) バイト。分からなければ null。 */
    fun space(path: String): Pair<Long, Long>? = null

    /** 好きな位置から読める形で開く。対応しない Fs(端末内は File を直接使う)は null。 */
    fun openRandom(path: String): RandomSource? = null

    /** この Fs の中での移動(rename)。できないときは false を返し、呼び出し側がコピー+削除に切り替える。 */
    fun moveInside(src: String, dst: String): Boolean

    fun exists(path: String): Boolean = stat(path) != null

    fun child(dir: String, name: String): String = if (dir.endsWith("/")) dir + name else "$dir/$name"

    fun parent(path: String): String? {
        val t = path.trimEnd('/')
        if (t.isEmpty()) return null
        val i = t.lastIndexOf('/')
        return if (i <= 0) "/" else t.substring(0, i)
    }
}

/** 端末内のファイル。java.io.File をそのまま使う。 */
object LocalFs : Fs {
    override val id = "local"
    override val isLocal = true

    override fun list(path: String): List<FileItem>? = File(path).listFiles()?.map { FileOps.toItem(it) }

    override fun stat(path: String): FileItem? = File(path).takeIf { it.exists() }?.let { FileOps.toItem(it) }

    override fun mkdir(path: String) {
        if (!File(path).mkdirs() && !File(path).isDirectory) throw OpException(Txt(R.string.fs_001))
    }

    override fun delete(path: String) = FileOps.delete(File(path))

    override fun rename(path: String, newName: String): String = FileOps.rename(File(path), newName).path

    override fun open(path: String): InputStream = File(path).inputStream()

    override fun create(path: String): OutputStream = File(path).outputStream()

    override fun moveInside(src: String, dst: String): Boolean = File(src).renameTo(File(dst))

    override fun space(path: String): Pair<Long, Long>? =
        File(path).let { f -> f.totalSpace.takeIf { it > 0 }?.let { it to f.freeSpace } }

    // 端末ではどちらも同じ結果。区切りが「/」でない環境(PC上のテスト)でも正しく動くよう File に任せる
    override fun parent(path: String): String? = File(path).parent
}
