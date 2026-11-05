package local.filemanager

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * ごみ箱。各ストレージの直下の `.FilerTrash/<id>/` に、消したものと元の場所の記録(.meta)を置く。
 * 同じストレージの中で移すだけなので、大きなファイルでも一瞬で終わる。
 */
object Trash {
    const val DIR = ".FilerTrash"
    private const val META = ".meta"
    private val counter = AtomicInteger()

    class Entry(val item: FileItem, val origin: String, val deletedAt: Long)

    fun root(volume: File) = File(volume, DIR)

    fun isInTrash(path: String) = path.contains("/$DIR/")

    fun moveToTrash(volume: File, f: File) {
        val box = File(root(volume), "${System.currentTimeMillis()}_${counter.incrementAndGet()}")
        if (!box.mkdirs()) throw OpException(Txt(R.string.trash_001))
        try {
            File(box, META).writeText("${f.path}\n${System.currentTimeMillis()}")
            FileOps.move(f, box)
        } catch (e: Exception) {
            runCatching { FileOps.delete(box) }
            throw e
        }
    }

    fun list(volumes: List<File>): List<Entry> {
        val out = ArrayList<Entry>()
        for (v in volumes) {
            val boxes = root(v).listFiles() ?: continue
            for (box in boxes) {
                val child = box.listFiles()?.firstOrNull { it.name != META } ?: continue
                val meta = File(box, META).takeIf { it.isFile }?.readLines().orEmpty()
                val origin = meta.getOrNull(0) ?: child.path
                val at = meta.getOrNull(1)?.toLongOrNull() ?: box.lastModified()
                val size = if (child.isDirectory) FileOps.stats(listOf(child)).bytes else child.length()
                out.add(Entry(FileItem(child.path, child.name, child.isDirectory, size, at), origin, at))
            }
        }
        return out.sortedByDescending { it.deletedAt }
    }

    fun totalSize(volumes: List<File>): Long = volumes.sumOf { v ->
        root(v).takeIf { it.isDirectory }?.let { FileOps.stats(listOf(it)).bytes } ?: 0L
    }

    /** 元の場所へ戻す。元のフォルダが無ければ作り、同名があれば「名前 (1)」にする。 */
    fun restore(child: File): File {
        val box = child.parentFile ?: throw OpException(Txt(R.string.trash_002))
        val origin = File(box, META).takeIf { it.isFile }?.readLines()?.firstOrNull()
            ?: throw OpException(Txt(R.string.trash_003, child.name))
        val parent = File(origin).parentFile ?: throw OpException(Txt(R.string.trash_004))
        if (!parent.isDirectory && !parent.mkdirs()) throw OpException(Txt(R.string.trash_005))
        val restored = FileOps.move(child, parent)
        FileOps.delete(box)
        return restored
    }

    fun deletePermanently(child: File) {
        FileOps.delete(child.parentFile ?: child)
    }

    fun empty(volumes: List<File>) {
        for (v in volumes) {
            val r = root(v)
            if (r.isDirectory) r.listFiles()?.forEach { FileOps.delete(it) }
        }
    }
}
