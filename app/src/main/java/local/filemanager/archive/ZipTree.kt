package local.filemanager

import java.io.File
import java.io.RandomAccessFile

/** 端末内のファイルを、好きな位置から読む(ZIP の中身を、全部読まずに取り出すため)。 */
class FileRandomSource(file: File) : RandomSource {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()

    @Synchronized
    override fun readAt(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        raf.seek(pos)
        return raf.read(buf, off, len)
    }

    override fun close() {
        runCatching { raf.close() }
    }
}

/** fs 上のファイルを、好きな位置から読める形で開く。端末内はファイルから、NAS は Fs から。開けなければ null。 */
fun openSource(fs: Fs, path: String): RandomSource? =
    if (fs.isLocal) runCatching { FileRandomSource(File(path)) }.getOrNull() else fs.openRandom(path)

/** ZIP の中身の一覧を、フォルダごとの階層にして見せるための整理。Android に依存しない。 */
object ZipTree {
    /** 一覧の 1 行。path はフォルダなら「a/b/」(最後が /)、ファイルなら「a/b/c.txt」。 */
    data class Row(
        val name: String,
        val path: String,
        val isDir: Boolean,
        /** フォルダのときは、中のファイルの合計 */
        val size: Long,
        val modified: Long,
        /** フォルダのときは、中にあるファイルの数 */
        val count: Int,
        val entry: ZipReader.Entry?,
    )

    /** 区切りを「/」にそろえ、先頭・末尾の「/」と「.」を取る。 */
    fun clean(name: String): String = name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")

    /** prefix は ""(いちばん上)か「a/b/」。その階層の、フォルダ → ファイルの順(名前順)の一覧。 */
    fun list(entries: List<ZipReader.Entry>, prefix: String): List<Row> {
        class Dir(var size: Long = 0, var modified: Long = 0, var count: Int = 0)

        val dirs = LinkedHashMap<String, Dir>()
        val files = ArrayList<Row>()
        for (e in entries) {
            val p = clean(e.name)
            if (p.isEmpty() || p.split('/').any { it == ".." }) continue // 外へ出る名前は出さない
            if (!p.startsWith(prefix)) continue
            val rest = p.substring(prefix.length)
            if (rest.isEmpty()) continue
            val slash = rest.indexOf('/')
            if (slash >= 0) {
                val d = dirs.getOrPut(rest.substring(0, slash)) { Dir() }
                if (!e.isDir) {
                    d.size += maxOf(e.size, 0L)
                    d.count++
                }
                if (e.modified > d.modified) d.modified = e.modified
            } else if (e.isDir) {
                val d = dirs.getOrPut(rest) { Dir() }
                if (e.modified > d.modified) d.modified = e.modified
            } else {
                files.add(Row(rest, p, false, e.size, e.modified, 0, e))
            }
        }
        val order = compareBy<Row, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        val dirRows = dirs.map { (n, d) -> Row(n, "$prefix$n/", true, d.size, d.modified, d.count, null) }.sortedWith(order)
        return dirRows + files.sortedWith(order)
    }

    /** 1 つ上の階層(「a/b/」→「a/」、「a/」→「」)。 */
    fun parent(prefix: String): String {
        val t = prefix.trimEnd('/')
        val i = t.lastIndexOf('/')
        return if (i < 0) "" else t.substring(0, i + 1)
    }

    /** 全体の (ファイル数, 展開後の合計バイト数)。 */
    fun totals(entries: List<ZipReader.Entry>): Pair<Int, Long> {
        val files = entries.filter { !it.isDir }
        return files.size to files.sumOf { maxOf(it.size, 0L) }
    }
}
