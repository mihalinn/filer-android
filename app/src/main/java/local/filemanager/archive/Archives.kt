package local.filemanager

import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.zip.GZIPInputStream

/** 中身の一覧を見て、1 つずつ取り出せる書庫の種類。 */
enum class ArchiveType { ZIP, SEVEN_Z, RAR, TAR, TAR_GZ, TAR_BZ2, TAR_XZ, GZ, BZ2, XZ, LZH }

object Archives {
    private val table: List<Pair<String, ArchiveType>> = listOf(
        ".tar.gz" to ArchiveType.TAR_GZ, ".tgz" to ArchiveType.TAR_GZ,
        ".tar.bz2" to ArchiveType.TAR_BZ2, ".tbz2" to ArchiveType.TAR_BZ2, ".tbz" to ArchiveType.TAR_BZ2,
        ".tar.xz" to ArchiveType.TAR_XZ, ".txz" to ArchiveType.TAR_XZ,
        ".tar" to ArchiveType.TAR, ".7z" to ArchiveType.SEVEN_Z, ".rar" to ArchiveType.RAR,
        ".zip" to ArchiveType.ZIP, ".jar" to ArchiveType.ZIP,
        ".cbz" to ArchiveType.ZIP, ".cbr" to ArchiveType.RAR, ".cb7" to ArchiveType.SEVEN_Z, ".cbt" to ArchiveType.TAR, ".epub" to ArchiveType.ZIP,
        ".pages" to ArchiveType.ZIP, ".numbers" to ArchiveType.ZIP, ".keynote" to ArchiveType.ZIP, ".ipa" to ArchiveType.ZIP, ".livp" to ArchiveType.ZIP, ".pkpass" to ArchiveType.ZIP,
        ".xapk" to ArchiveType.ZIP, ".apkm" to ArchiveType.ZIP, ".apks" to ArchiveType.ZIP,
        ".gz" to ArchiveType.GZ, ".bz2" to ArchiveType.BZ2, ".xz" to ArchiveType.XZ,
        ".lzh" to ArchiveType.LZH, ".lha" to ArchiveType.LZH,
    )

    /** ファイル名から。長い拡張子(.tar.gz)を先に見る。書庫でなければ null。 */
    fun typeOf(name: String): ArchiveType? {
        val n = name.lowercase()
        return table.firstOrNull { n.length > it.first.length && n.endsWith(it.first) }?.second
    }

    /** 展開先のフォルダ名(拡張子を除いた名前)。 */
    fun baseName(name: String): String {
        val n = name.lowercase()
        val ext = table.firstOrNull { n.length > it.first.length && n.endsWith(it.first) }?.first ?: return name
        return name.dropLast(ext.length)
    }

    /** ZIP 以外の書庫(このアプリが ZIP のほかに読むもの)か。 */
    fun isOtherArchive(name: String): Boolean = typeOf(name).let { it != null && it != ArchiveType.ZIP }

    /** ビューアで開く対象の拡張子か(.jar は ZIP として別に扱う)。 */
    fun viewerType(name: String): ArchiveType? = typeOf(name)?.takeIf { it != ArchiveType.ZIP }
}

/** 書庫の中身の一覧と、1 つずつの取り出し。一覧は ZIP と同じ形(ZipReader.Entry)にして、ZIP の画面をそのまま使う。 */
interface ArchiveSource : Closeable {
    val entries: List<ZipReader.Entry>
    fun open(e: ZipReader.Entry): InputStream
}

private fun entry(name: String, isDir: Boolean, size: Long, modified: Long, index: Int, encrypted: Boolean = false) =
    ZipReader.Entry(name, isDir, -1, 0L, size, index.toLong(), encrypted, modified)

/** RandomSource を、頭から順に読む InputStream にする(閉じても元の RandomSource は閉じない)。 */
class SourceInputStream(private val src: RandomSource) : InputStream() {
    private var pos = 0L

    override fun read(): Int {
        val b = ByteArray(1)
        return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val left = src.size - pos
        if (left <= 0) return -1
        val n = src.readAt(pos, b, off, minOf(len.toLong(), left).toInt())
        if (n <= 0) return -1
        pos += n
        return n
    }

    override fun skip(n: Long): Long {
        val k = minOf(n, src.size - pos).coerceAtLeast(0L)
        pos += k
        return k
    }

    override fun available(): Int = (src.size - pos).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}

/** RandomSource を、読み取り専用の SeekableByteChannel にする(7z が好きな位置を読むため)。1MB ずつまとめて読む。 */
class SourceChannel(private val src: RandomSource) : SeekableByteChannel {
    private var pos = 0L
    private var open = true
    private var blockStart = -1L
    private var block = ByteArray(0)

    override fun read(dst: ByteBuffer): Int {
        if (!open) throw java.nio.channels.ClosedChannelException()
        if (!dst.hasRemaining()) return 0
        if (pos >= src.size) return -1
        var total = 0
        while (dst.hasRemaining() && pos < src.size) {
            if (blockStart < 0 || pos < blockStart || pos >= blockStart + block.size) {
                val start = pos / BLOCK * BLOCK
                val len = minOf(BLOCK.toLong(), src.size - start).toInt()
                val buf = ByteArray(len)
                var got = 0
                while (got < len) {
                    val n = src.readAt(start + got, buf, got, len - got)
                    if (n <= 0) throw IOException("途中で読めなくなりました")
                    got += n
                }
                block = buf
                blockStart = start
            }
            val inBlock = (pos - blockStart).toInt()
            val n = minOf(block.size - inBlock, dst.remaining())
            dst.put(block, inBlock, n)
            pos += n
            total += n
        }
        return total
    }

    override fun write(src: ByteBuffer): Int = throw java.nio.channels.NonWritableChannelException()
    override fun position(): Long = pos
    override fun position(newPosition: Long): SeekableByteChannel {
        if (newPosition < 0) throw IllegalArgumentException("負の位置")
        pos = newPosition
        return this
    }

    override fun size(): Long = src.size
    override fun truncate(size: Long): SeekableByteChannel = throw java.nio.channels.NonWritableChannelException()
    override fun isOpen(): Boolean = open
    override fun close() {
        open = false
    }

    private companion object {
        const val BLOCK = 1024 * 1024L
    }
}

private class ZipArchive(private val reader: ZipReader, private val src: RandomSource) : ArchiveSource {
    override val entries: List<ZipReader.Entry> get() = reader.entries
    override fun open(e: ZipReader.Entry): InputStream = reader.open(e)
    override fun close() = src.close()
}

private class SevenZArchive(private val src: RandomSource) : ArchiveSource {
    private val file: SevenZFile
    private val list: List<SevenZArchiveEntry>
    override val entries: List<ZipReader.Entry>

    init {
        try {
            file = SevenZFile.builder().setSeekableByteChannel(SourceChannel(src)).get()
        } catch (e: PasswordRequiredException) {
            throw OpException("パスワード付きの7zは未対応")
        } catch (e: IOException) {
            throw OpException("7zとして読めません(${e.message ?: e.javaClass.simpleName})")
        }
        list = file.entries.toList()
        entries = list.mapIndexed { i, e ->
            entry(e.name ?: "", e.isDirectory, if (e.isDirectory) 0L else e.size, if (e.hasLastModifiedDate) e.lastModifiedDate.time else 0L, i)
        }
    }

    override fun open(e: ZipReader.Entry): InputStream = try {
        file.getInputStream(list[e.localOffset.toInt()])
    } catch (ex: PasswordRequiredException) {
        throw OpException("パスワード付きの7zは未対応")
    }

    override fun close() {
        runCatching { file.close() }
        src.close()
    }
}

private class LzhArchive(private val src: RandomSource) : ArchiveSource {
    private val reader = LzhReader(src)
    override val entries: List<ZipReader.Entry> = reader.entries.mapIndexed { i, e ->
        entry(e.name, e.isDir, e.size, e.modified, i)
    }

    override fun open(e: ZipReader.Entry): InputStream = reader.open(reader.entries[e.localOffset.toInt()])
    override fun close() = src.close()
}

private fun tarDecompress(type: ArchiveType, raw: InputStream): InputStream {
    val buffered = BufferedInputStream(raw, 256 * 1024)
    return when (type) {
        ArchiveType.TAR_GZ, ArchiveType.GZ -> GZIPInputStream(buffered, 64 * 1024)
        ArchiveType.TAR_BZ2, ArchiveType.BZ2 -> BZip2CompressorInputStream(buffered, true)
        ArchiveType.TAR_XZ, ArchiveType.XZ -> XZCompressorInputStream(buffered)
        else -> buffered
    }
}

private class TarArchive(private val type: ArchiveType, private val src: RandomSource) : ArchiveSource {
    override val entries: List<ZipReader.Entry>

    init {
        val out = ArrayList<ZipReader.Entry>()
        try {
            TarArchiveInputStream(tarDecompress(type, SourceInputStream(src)), "UTF-8").use { tar ->
                var i = 0
                while (true) {
                    val e: TarArchiveEntry = tar.nextEntry ?: break
                    if (e.isFile || e.isDirectory) out.add(entry(e.name, e.isDirectory, if (e.isDirectory) 0L else e.size, e.lastModifiedDate.time, i))
                    i++
                }
            }
        } catch (e: IOException) {
            // 途中まで読めたぶんは出す。1 つも読めなかったときだけエラーにする
            if (out.isEmpty()) throw OpException("書庫として読めません(${e.message ?: e.javaClass.simpleName})")
        }
        entries = out
    }

    override fun open(e: ZipReader.Entry): InputStream {
        val tar = TarArchiveInputStream(tarDecompress(type, SourceInputStream(src)), "UTF-8")
        try {
            var i = 0
            while (true) {
                tar.nextEntry ?: throw OpException("書庫の中に見つかりません")
                if (i == e.localOffset.toInt()) break
                i++
            }
        } catch (ex: Exception) {
            runCatching { tar.close() }
            throw ex
        }
        return object : FilterInputStream(tar) {
            override fun close() = tar.close()
        }
    }

    override fun close() = src.close()
}

/** .gz / .bz2 / .xz 1 つだけを圧縮したもの。中身が 1 つのファイルとして出す。 */
private class SingleArchive(private val type: ArchiveType, private val name: String, private val src: RandomSource) : ArchiveSource {
    override val entries: List<ZipReader.Entry>

    init {
        var size = -1L
        if (type == ArchiveType.GZ && src.size >= 4) {
            // gzip の末尾 4 バイトは、元の大きさ(4GB を超えると正確でない)
            val b = ByteArray(4)
            var got = 0
            while (got < 4) {
                val n = src.readAt(src.size - 4 + got, b, got, 4 - got)
                if (n <= 0) break
                got += n
            }
            if (got == 4) size = (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
        }
        entries = listOf(entry(name, false, size, 0L, 0))
    }

    override fun open(e: ZipReader.Entry): InputStream = tarDecompress(type, SourceInputStream(src))
    override fun close() = src.close()
}

private class RarArchive(file: File) : ArchiveSource {
    private val archive: Archive
    private val headers: List<FileHeader>
    override val entries: List<ZipReader.Entry>

    init {
        try {
            archive = Archive(file)
        } catch (e: Exception) {
            throw OpException("RARとして読めませんでした。パスワード付きか、未対応の形式かもしれません(${e.message ?: e.javaClass.simpleName})")
        }
        headers = archive.fileHeaders.toList()
        entries = headers.mapIndexed { i, h ->
            entry(h.fileName ?: "", h.isDirectory, if (h.isDirectory) 0L else h.fullUnpackSize, h.mTime?.time ?: 0L, i, h.isEncrypted)
        }
    }

    override fun open(e: ZipReader.Entry): InputStream {
        val h = headers[e.localOffset.toInt()]
        if (h.isEncrypted) throw OpException("パスワード付きのRARは未対応")
        return archive.getInputStream(h)
    }

    override fun close() {
        runCatching { archive.close() }
    }
}

/** 開いた書庫。閉じると、読み込み元も閉じる。 */
class ArchiveHandle(val source: ArchiveSource) : Closeable {
    override fun close() = source.close()
}

object ArchiveOpener {
    private const val RAR_COPY_LIMIT = 1024L * 1024 * 1024

    /** 中身を読む前の確認用に、圧縮された中身の先頭が tar か(「ustar」の印)を見る。 */
    internal fun looksLikeTar(head: ByteArray, n: Int): Boolean =
        n >= 262 && head[257] == 'u'.code.toByte() && head[258] == 's'.code.toByte() && head[259] == 't'.code.toByte() && head[260] == 'a'.code.toByte() && head[261] == 'r'.code.toByte()

    /** .gz / .bz2 / .xz の中身が tar なら tar として扱う。 */
    private fun sniffTar(type: ArchiveType, src: RandomSource): Boolean {
        return try {
            tarDecompress(type, SourceInputStream(src)).use { s ->
                val head = ByteArray(512)
                var n = 0
                while (n < head.size) {
                    val r = s.read(head, n, head.size - n)
                    if (r <= 0) break
                    n += r
                }
                looksLikeTar(head, n)
            }
        } catch (e: Exception) {
            false
        }
    }

    /** tmpDir: RAR を NAS から読むときの、一時コピーを置く場所。 */
    fun open(fs: Fs, item: FileItem, tmpDir: File): ArchiveHandle {
        val type = Archives.typeOf(item.name) ?: (if (FileOps.extensionOf(item.name) == "key") ArchiveType.ZIP else null) ?: throw OpException("対応していない書庫")
        val src = try {
            openSource(fs, item.path)
        } catch (e: Exception) {
            null
        } ?: throw OpException("このファイルを開けません")
        try {
            val source: ArchiveSource = when (type) {
                ArchiveType.ZIP -> ZipArchive(ZipReader(src), src)
                ArchiveType.SEVEN_Z -> SevenZArchive(src)
                ArchiveType.LZH -> LzhArchive(src)
                ArchiveType.RAR -> {
                    val f = if (fs.isLocal) File(item.path) else copyForRar(item, src, tmpDir)
                    src.close()
                    RarArchive(f)
                }
                ArchiveType.TAR, ArchiveType.TAR_GZ, ArchiveType.TAR_BZ2, ArchiveType.TAR_XZ -> TarArchive(type, src)
                ArchiveType.GZ, ArchiveType.BZ2, ArchiveType.XZ -> {
                    val tarType = when (type) { ArchiveType.GZ -> ArchiveType.TAR_GZ; ArchiveType.BZ2 -> ArchiveType.TAR_BZ2; else -> ArchiveType.TAR_XZ }
                    if (sniffTar(type, src)) TarArchive(tarType, src) else SingleArchive(type, Archives.baseName(item.name), src)
                }
            }
            return ArchiveHandle(source)
        } catch (e: Throwable) {
            src.close()
            throw e
        }
    }

    private fun copyForRar(item: FileItem, src: RandomSource, tmpDir: File): File {
        if (src.size > RAR_COPY_LIMIT) throw OpException("大きすぎて開けません(RARは ${RAR_COPY_LIMIT / 1024 / 1024} MB まで)")
        val dir = File(tmpDir, "rarview").also { it.mkdirs() }
        val out = File(dir, cacheKey(item.path, item.size, item.modified) + ".rar")
        if (!(out.exists() && out.length() == src.size)) {
            val part = File(dir, out.name + ".part")
            // 大きいので、通知を出して画面オフでも続ける(利用者が通知から止められる)
            val task = BgTask("「${item.name}」を取り込んでいます", "「${item.name}」の取り込みが終わりました", "「${item.name}」を取り込めませんでした")
            try {
                BgTasks.run(task, src.size) {
                    part.outputStream().use { o ->
                        val buf = ByteArray(256 * 1024)
                        var pos = 0L
                        while (pos < src.size) {
                            task.check()
                            val n = src.readAt(pos, buf, 0, minOf(buf.size.toLong(), src.size - pos).toInt())
                            if (n <= 0) throw IOException("途中で読めなくなりました")
                            o.write(buf, 0, n)
                            pos += n
                            task.done = pos
                        }
                    }
                }
            } catch (e: Throwable) {
                part.delete()
                throw e
            }
            out.delete()
            if (!part.renameTo(out)) throw IOException("一時ファイルを作れませんでした")
        }
        out.setLastModified(System.currentTimeMillis())
        trimCacheTree(dir, out, 1536L * 1024 * 1024, 24L * 3600 * 1000)
        return out
    }
}
