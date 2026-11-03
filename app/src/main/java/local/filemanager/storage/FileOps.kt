package local.filemanager

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.HashSet
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

enum class SortKey(val label: String) {
    NAME("名前"), DATE("更新日時"), SIZE("サイズ"), TYPE("種類")
}

enum class FileKind { FOLDER, IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, APK, OTHER }

data class Stats(val files: Int, val folders: Int, val bytes: Long)

/** 利用者に見せてよい文面を持つエラー。 */
class OpException(message: String) : Exception(message)

/** ファイル操作の本体。Android に依存しないので PC 上の単体テストで検証できる。 */
object FileOps {
    private val image = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg")
    private val video = setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "ts", "m2ts", "mts", "flv", "wmv")
    private val audio = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma", "aiff", "alac", "mid", "midi", "kar", "rmi", "caf")
    private val document = setOf(
        "pdf", "epub", "mht", "mhtml", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "rtf", "odt", "ods", "odp", "json", "xml", "html", "htm", "log", "drawio", "vcf",
    )
    private val archive = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "cbz", "cbr", "cb7", "cbt", "lzh", "lha")
    private val apk = setOf("apk", "apks", "xapk", "apkm")

    private val mimes = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
        "webp" to "image/webp", "svg" to "image/svg+xml", "mht" to "multipart/related", "mhtml" to "multipart/related", "ttf" to "font/ttf", "otf" to "font/otf", "sqlite" to "application/vnd.sqlite3", "db" to "application/vnd.sqlite3", "epub" to "application/epub+zip", "cbz" to "application/vnd.comicbook+zip", "bmp" to "image/bmp", "heic" to "image/heic", "heif" to "image/heif", "avif" to "image/avif",
        "mp4" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm", "avi" to "video/x-msvideo",
        "mov" to "video/quicktime", "3gp" to "video/3gpp", "m4v" to "video/x-m4v",
        "ts" to "video/mp2t", "m2ts" to "video/mp2t", "mts" to "video/mp2t",
        "drawio" to "application/vnd.jgraph.mxfile", "stl" to "model/stl", "vcf" to "text/x-vcard",
        "obj" to "model/obj", "ply" to "model/ply", "off" to "model/off", "3mf" to "model/3mf",
        "dxf" to "image/vnd.dxf", "dwg" to "image/vnd.dwg", "jww" to "application/x-jww", "jwc" to "application/x-jwc",
        "mp3" to "audio/mpeg", "flac" to "audio/flac", "wav" to "audio/x-wav", "m4a" to "audio/mp4",
        "aac" to "audio/aac", "ogg" to "audio/ogg", "opus" to "audio/ogg", "mid" to "audio/midi", "midi" to "audio/midi", "kar" to "audio/midi", "rmi" to "audio/midi", "caf" to "audio/x-caf",
        "pdf" to "application/pdf", "txt" to "text/plain", "log" to "text/plain", "md" to "text/plain",
        "csv" to "text/csv", "json" to "application/json", "xml" to "text/xml", "html" to "text/html", "htm" to "text/html",
        "zip" to "application/zip", "apk" to "application/vnd.android.package-archive",
    )

    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase(Locale.ROOT)
    }

    fun kindOf(name: String): FileKind {
        val ext = extensionOf(name)
        return when (ext) {
            in image -> FileKind.IMAGE
            in video -> FileKind.VIDEO
            in audio -> FileKind.AUDIO
            in document -> FileKind.DOCUMENT
            in archive -> FileKind.ARCHIVE
            in apk -> FileKind.APK
            else -> FileKind.OTHER
        }
    }

    // アプリ内の簡易ビューアで開くテキスト。「ts」は録画(MPEG-TS)なので含めない
    private val text = setOf(
        "txt", "log", "md", "csv", "tsv", "json", "xml", "ini", "conf", "cfg", "yml", "yaml", "toml", "properties",
        "srt", "ass", "ssa", "vtt", "sh", "bat", "py", "js", "kt", "java", "c", "h", "cpp", "html", "htm", "css", "sql",
        "php", "rb", "go", "rs", "lua", "tex", "nfo", "m3u", "m3u8", "url", "gradle",
        "markdown", "jsonl", "ndjson", "kts", "cs", "mjs", "jsx", "tsx", "cc", "hpp",
    )

    fun isTextName(name: String): Boolean = extensionOf(name) in text

    fun mimeOf(name: String): String = mimes[extensionOf(name)] ?: "application/octet-stream"

    /** 中身の数(childCount)は数えない。フォルダごとに list() すると遅いので、表示する行だけが後から数える。 */
    fun toItem(f: File): FileItem {
        val dir = f.isDirectory
        return FileItem(f.path, f.name, dir, if (dir) 0L else f.length(), f.lastModified())
    }

    /** 読めないフォルダでは null。 */
    fun list(dir: File, showHidden: Boolean, key: SortKey, ascending: Boolean): List<FileItem>? {
        val files = dir.listFiles() ?: return null
        val items = files.filter { showHidden || !it.name.startsWith(".") }.map { toItem(it) }
        return sort(items, key, ascending)
    }

    fun countChildren(dir: File): Int = dir.list()?.size ?: 0

    fun sort(items: List<FileItem>, key: SortKey, ascending: Boolean): List<FileItem> {
        val byName = Comparator<FileItem> { a, b -> naturalCompare(a.name, b.name) }
        val cmp: Comparator<FileItem> = when (key) {
            SortKey.NAME -> byName
            SortKey.DATE -> compareBy<FileItem> { it.modified }.then(byName)
            SortKey.SIZE -> compareBy<FileItem> { it.size }.then(byName)
            SortKey.TYPE -> compareBy<FileItem> { extensionOf(it.name) }.then(byName)
        }
        val directed = if (ascending) cmp else cmp.reversed()
        // フォルダは常に先頭に置く
        return items.sortedWith(compareBy<FileItem> { !it.isDir }.then(directed))
    }

    /** 大文字小文字を区別せず、数字の並びは数値として比べる(img2 < img10)。 */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ie = i
                while (ie < a.length && a[ie].isDigit()) ie++
                var je = j
                while (je < b.length && b[je].isDigit()) je++
                val na = a.substring(i, ie).trimStart('0')
                val nb = b.substring(j, je).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ie
                j = je
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    fun uniqueFile(dir: File, name: String, isDir: Boolean): File {
        val first = File(dir, name)
        if (!first.exists()) return first
        val dot = name.lastIndexOf('.')
        val base = if (!isDir && dot > 0) name.substring(0, dot) else name
        val ext = if (!isDir && dot > 0) name.substring(dot) else ""
        var n = 1
        while (true) {
            val f = File(dir, "$base ($n)$ext")
            if (!f.exists()) return f
            n++
        }
    }

    fun isInside(parent: File, child: File): Boolean {
        val p = parent.canonicalFile
        val c = child.canonicalFile
        return c == p || c.path.startsWith(p.path + File.separator)
    }

    private fun isLink(f: File) = Files.isSymbolicLink(f.toPath())

    /**
     * overwrite=false: 同名があれば「名前 (1)」にして両方残す。
     * overwrite=true: 同名のファイルは置き換え、同名のフォルダは中身をまとめる(マージ)。失敗しても元からあったものは消さない。
     */
    fun copy(src: File, destDir: File, check: () -> Unit = {}, progress: Progress? = null, overwrite: Boolean = false): File {
        if (src.isDirectory && isInside(src, destDir)) throw OpException("フォルダを自分自身の中にはコピーできません: ${src.name}")
        val target = if (overwrite) File(destDir, src.name) else uniqueFile(destDir, src.name, src.isDirectory)
        if (overwrite && src.canonicalFile == target.canonicalFile) throw OpException("同じ場所には上書きできません: ${src.name}")
        val existed = target.exists()
        try {
            copyRecursive(src, target, check, progress)
        } catch (e: Exception) {
            if (!existed) runCatching { delete(target) }
            throw e
        }
        return target
    }

    private fun copyRecursive(src: File, dst: File, check: () -> Unit, progress: Progress? = null) {
        check()
        if (isLink(src)) return
        if (src.isDirectory) {
            if (dst.exists() && !dst.isDirectory) throw OpException("同じ名前のファイルがあります: ${dst.name}")
            if (!dst.mkdirs() && !dst.isDirectory) throw OpException("フォルダを作成できません: ${dst.name}")
            src.listFiles()?.forEach { copyRecursive(it, File(dst, it.name), check, progress) }
        } else {
            if (dst.isDirectory) throw OpException("同じ名前のフォルダがあります: ${dst.name}")
            progress?.begin(src.path, dst.path)
            writeAtomically(dst) { w -> src.inputStream().use { i -> w.outputStream().use { o -> pump(i, o, check, progress) } } }
            dst.setLastModified(src.lastModified())
        }
    }

    /** 書き込み中のファイルにつける仮の名前の末尾。アプリが途中で止められても、完成品と見分けられるようにする。 */
    const val PART_SUFFIX = ".filerpart"

    /** 仮の名前を付けられる長さか(名前の上限は 255 バイト)。 */
    fun canUsePartName(name: String): Boolean = name.toByteArray().size + PART_SUFFIX.length <= 255

    /**
     * dst に書く内容を、まず「dst.filerpart」に書いて、最後まで書けたら dst に名前を変える。
     * 途中で止まると仮の名前のファイルが残るので、壊れたファイルが完成品の顔をして残ることがない。
     * 失敗したときは仮のファイルを消す。
     */
    fun writeAtomically(dst: File, write: (File) -> Unit) {
        if (!canUsePartName(dst.name)) {
            write(dst)
            return
        }
        val part = File(dst.parentFile, dst.name + PART_SUFFIX)
        try {
            write(part)
            // 上書きのときは、最後まで書けてから古い方を消す(途中で失敗しても古い方が残る)
            if (dst.exists() && !dst.delete()) throw OpException("上書きできません: ${dst.name}")
            if (!part.renameTo(dst)) throw OpException("書き込みを完了できませんでした: ${dst.name}")
        } catch (e: Exception) {
            part.delete()
            throw e
        }
    }

    private fun pump(i: InputStream, o: OutputStream, check: () -> Unit, progress: Progress? = null) {
        val buf = ByteArray(256 * 1024)
        while (true) {
            check()
            val n = i.read(buf)
            if (n < 0) break
            o.write(buf, 0, n)
            progress?.add(n)
        }
    }

    fun move(src: File, destDir: File, check: () -> Unit = {}, progress: Progress? = null, overwrite: Boolean = false): File {
        if (src.parentFile?.canonicalFile == destDir.canonicalFile) throw OpException("同じフォルダには移動できません: ${src.name}")
        if (src.isDirectory && isInside(src, destDir)) throw OpException("フォルダを自分自身の中には移動できません: ${src.name}")
        val target = if (overwrite) File(destDir, src.name) else uniqueFile(destDir, src.name, src.isDirectory)
        val existed = target.exists()
        if (overwrite && existed && src.isDirectory != target.isDirectory) {
            throw OpException("同じ名前で種類の違うものがあります: ${src.name}")
        }
        // フォルダ同士の上書きは中身をまとめるので、名前の付け替えではなくコピーで行う
        if (!(overwrite && existed && src.isDirectory) && src.renameTo(target)) return target
        // 別のストレージ間などで rename できないときは、コピーしてから元を消す
        try {
            copyRecursive(src, target, check, progress)
        } catch (e: Exception) {
            if (!existed) runCatching { delete(target) }
            throw e
        }
        delete(src)
        return target
    }

    fun delete(f: File) {
        if (f.isDirectory && !isLink(f)) f.listFiles()?.forEach { delete(it) }
        if (!f.delete() && f.exists()) throw OpException("削除できません: ${f.name}")
    }

    /** 他のアプリから受け取った名前を、保存できる名前にする(NAS で使えない文字は「_」に)。空なら fallback。 */
    fun safeFileName(raw: String?, fallback: String = "共有ファイル"): String {
        val cleaned = (raw ?: "").replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim()
        val name = if (cleaned.isBlank()) fallback else cleaned
        if (name.toByteArray().size <= 255) return name
        val ext = extensionOf(name).take(10)
        return name.take(100) + if (ext.isEmpty()) "" else ".$ext"
    }

    fun validateName(name: String): String? = when {
        name.isBlank() -> "名前を入力してください"
        name == "." || name == ".." -> "この名前は使えません"
        name.contains('/') || name.contains('\u0000') -> "「/」は名前に使えません"
        name.toByteArray().size > 255 -> "名前が長すぎます"
        else -> null
    }

    fun rename(f: File, newName: String): File {
        validateName(newName)?.let { throw OpException(it) }
        val target = File(f.parentFile, newName)
        if (target.exists()) throw OpException("同じ名前のファイルがすでにあります")
        if (!f.renameTo(target)) throw OpException("名前を変更できません")
        return target
    }

    fun createFolder(parent: File, name: String): File {
        validateName(name)?.let { throw OpException(it) }
        val target = File(parent, name)
        if (target.exists()) throw OpException("同じ名前のファイルがすでにあります")
        if (!target.mkdir()) throw OpException("フォルダを作成できません")
        return target
    }

    fun zip(sources: List<File>, out: File, check: () -> Unit = {}, progress: Progress? = null) {
        if (out.exists()) throw OpException("同じ名前のファイルがすでにあります")
        try {
            writeAtomically(out) { w ->
                ZipOutputStream(w.outputStream().buffered()).use { z ->
                    val used = HashSet<String>()
                    for (s in sources) {
                        var name = s.name
                        var n = 1
                        while (!used.add(name)) name = "${s.name} (${n++})"
                        addToZip(z, s, name, check, progress)
                    }
                }
            }
        } catch (e: Exception) {
            out.delete()
            throw e
        }
    }

    private fun addToZip(z: ZipOutputStream, f: File, path: String, check: () -> Unit, progress: Progress?) {
        check()
        if (isLink(f)) return
        if (f.isDirectory) {
            z.putNextEntry(ZipEntry("$path/").apply { time = f.lastModified() })
            z.closeEntry()
            f.listFiles()?.forEach { addToZip(z, it, "$path/${it.name}", check, progress) }
        } else {
            z.putNextEntry(ZipEntry(path).apply { time = f.lastModified() })
            progress?.begin(f.path, path)
            f.inputStream().use { pump(it, z, check, progress) }
            z.closeEntry()
        }
    }

    private val shiftJis: Charset by lazy { Charset.forName("windows-31j") }

    /** UTF-8 として読めない ZIP(Windows の日本語環境で作られたもの)は Shift_JIS 系として読む。 */
    private fun pickZipCharset(zip: File): Charset = try {
        ZipFile(zip, Charsets.UTF_8).use { zf ->
            if (zf.entries().asSequence().any { it.name.contains('�') }) shiftJis else Charsets.UTF_8
        }
    } catch (e: Exception) {
        shiftJis
    }

    /** zip と同じ場所に、拡張子を除いた名前のフォルダを作って展開する。 */
    fun unzip(zip: File, check: () -> Unit = {}, progress: Progress? = null): File {
        val parent = zip.parentFile ?: throw OpException("展開先が見つかりません")
        val base = zip.name.let { if (it.endsWith(".zip", ignoreCase = true) && it.length > 4) it.dropLast(4) else it }
        val root = uniqueFile(parent, base, true)
        if (!root.mkdirs()) throw OpException("フォルダを作成できません")
        val rootCanon = root.canonicalFile
        try {
            ZipFile(zip, pickZipCharset(zip)).use { zf ->
                if (progress != null && progress.totalBytes == 0L) {
                    progress.totalBytes = zf.entries().asSequence().sumOf { maxOf(it.size, 0L) }
                }
                for (e in zf.entries()) {
                    check()
                    val out = File(root, e.name.replace('\\', '/'))
                    val canon = out.canonicalFile
                    if (canon != rootCanon && !canon.path.startsWith(rootCanon.path + File.separator)) {
                        throw OpException("不正なパスを含む ZIP です: ${e.name}")
                    }
                    if (e.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        progress?.begin(zip.path, out.path)
                        zf.getInputStream(e).use { i -> out.outputStream().use { o -> pump(i, o, check, progress) } }
                    }
                    if (e.time > 0) out.setLastModified(e.time)
                }
            }
        } catch (e: Exception) {
            runCatching { delete(root) }
            throw e
        }
        return root
    }

    fun search(root: File, query: String, showHidden: Boolean, limit: Int, check: () -> Unit = {}): List<FileItem> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<FileItem>()
        fun walk(d: File) {
            check()
            val children = d.listFiles() ?: return
            for (c in children) {
                if (out.size >= limit) return
                if (!showHidden && c.name.startsWith(".")) continue
                if (c.name.lowercase().contains(q)) out.add(toItem(c))
                if (c.isDirectory && !isLink(c)) walk(c)
            }
        }
        walk(root)
        return out
    }

    fun stats(roots: List<File>, check: () -> Unit = {}): Stats {
        var files = 0
        var folders = 0
        var bytes = 0L
        fun walk(x: File) {
            check()
            if (isLink(x)) return
            if (x.isDirectory) {
                folders++
                x.listFiles()?.forEach { walk(it) }
            } else {
                files++
                bytes += x.length()
            }
        }
        roots.forEach { walk(it) }
        return Stats(files, folders, bytes)
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = -1
        do {
            v /= 1024
            i++
        } while (v >= 1024 && i < units.size - 1)
        return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
    }

    // SimpleDateFormat は作るのが重く、スレッドセーフでもないので、スレッドごとに1つだけ持つ
    private val dayFormat = ThreadLocal.withInitial { SimpleDateFormat("yyyy/MM/dd", Locale.JAPAN) }
    private val minuteFormat = ThreadLocal.withInitial { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN) }

    fun formatDate(millis: Long): String =
        if (millis <= 0) "-" else minuteFormat.get()!!.format(Date(millis))

    fun formatDay(millis: Long): String =
        if (millis <= 0) "-" else dayFormat.get()!!.format(Date(millis))
}
