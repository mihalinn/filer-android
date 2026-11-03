package local.filemanager

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Fs をまたいだコピー・移動・検索など(端末内 ⇔ NAS、NAS ⇔ NAS を含む)。 */
object Transfer {
    private const val MAX_DEPTH = 32

    fun uniquePath(fs: Fs, dir: String, name: String, isDir: Boolean): String {
        val first = fs.child(dir, name)
        if (!fs.exists(first)) return first
        val dot = name.lastIndexOf('.')
        val base = if (!isDir && dot > 0) name.substring(0, dot) else name
        val ext = if (!isDir && dot > 0) name.substring(dot) else ""
        var n = 1
        while (true) {
            val p = fs.child(dir, "$base ($n)$ext")
            if (!fs.exists(p)) return p
            n++
        }
    }

    private fun isUnder(parent: String, child: String): Boolean {
        val p = parent.trimEnd('/')
        val c = child.trimEnd('/')
        return c == p || c.startsWith("$p/")
    }

    fun pump(i: InputStream, o: OutputStream, check: () -> Unit, progress: Progress? = null) {
        val buf = ByteArray(256 * 1024)
        while (true) {
            check()
            val n = i.read(buf)
            if (n < 0) break
            o.write(buf, 0, n)
            progress?.add(n)
        }
    }

    /**
     * FileOps.writeAtomically の Fs 版。仮の名前(.filerpart)に書き、最後まで書けたら本来の名前に変える。
     * replace=true(上書き)のときは、最後まで書けてから古い方を消す。
     */
    private fun writeAtomically(fs: Fs, dst: String, replace: Boolean, write: (String) -> Unit) {
        val name = dst.trimEnd('/').substringAfterLast('/')
        if (!FileOps.canUsePartName(name)) {
            write(dst)
            return
        }
        val part = dst + FileOps.PART_SUFFIX
        try {
            write(part)
            if (replace) fs.delete(dst)
            fs.rename(part, name)
        } catch (e: Exception) {
            runCatching { fs.delete(part) }
            throw e
        }
    }

    /** 貼り付け先に同じ名前があって、確認が要るもの。 */
    data class Conflict(val src: FileItem, val existing: FileItem)

    /**
     * 貼り付け先に同名があるものを調べる。同じフォルダへのコピーは「名前 (1)」の複製になるので、確認の対象にしない。
     */
    fun conflicts(srcFs: Fs, items: List<FileItem>, dstFs: Fs, dstDir: String, check: () -> Unit = {}): List<Conflict> =
        items.mapNotNull { s ->
            check()
            val sameDir = srcFs.id == dstFs.id && srcFs.parent(s.path)?.trimEnd('/') == dstDir.trimEnd('/')
            if (sameDir) null else dstFs.stat(dstFs.child(dstDir, s.name))?.let { Conflict(s, it) }
        }

    /**
     * overwrite=false: 同名があれば「名前 (1)」にして両方残す。
     * overwrite=true: 同名のファイルは置き換え、同名のフォルダは中身をまとめる。失敗しても元からあったものは消さない。
     */
    fun copy(
        srcFs: Fs, src: FileItem, dstFs: Fs, dstDir: String,
        check: () -> Unit = {}, progress: Progress? = null, overwrite: Boolean = false,
    ): String {
        if (srcFs.isLocal && dstFs.isLocal) return FileOps.copy(File(src.path), File(dstDir), check, progress, overwrite).path
        if (srcFs.id == dstFs.id && src.isDir && isUnder(src.path, dstDir)) {
            throw OpException("フォルダを自分自身の中にはコピーできません: ${src.name}")
        }
        val target = if (overwrite) dstFs.child(dstDir, src.name) else uniquePath(dstFs, dstDir, src.name, src.isDir)
        if (overwrite && srcFs.id == dstFs.id && isUnder(target, src.path) && isUnder(src.path, target)) {
            throw OpException("同じ場所には上書きできません: ${src.name}")
        }
        val existed = overwrite && dstFs.exists(target)
        try {
            copyRecursive(srcFs, src, dstFs, target, check, progress, overwrite)
        } catch (e: Exception) {
            if (!existed) runCatching { dstFs.delete(target) }
            throw e
        }
        return target
    }

    private fun copyRecursive(
        srcFs: Fs, src: FileItem, dstFs: Fs, dst: String,
        check: () -> Unit, progress: Progress?, overwrite: Boolean,
    ) {
        check()
        if (src.isDir) {
            val ex = if (overwrite) dstFs.stat(dst) else null
            when {
                ex == null -> dstFs.mkdir(dst)
                !ex.isDir -> throw OpException("同じ名前のファイルがあります: ${src.name}")
            }
            val children = srcFs.list(src.path) ?: throw OpException("フォルダを読めませんでした: ${src.name}")
            for (c in children) copyRecursive(srcFs, c, dstFs, dstFs.child(dst, c.name), check, progress, overwrite)
        } else {
            val ex = if (overwrite) dstFs.stat(dst) else null
            if (ex != null && ex.isDir) throw OpException("同じ名前のフォルダがあります: ${src.name}")
            progress?.begin(src.path, dst)
            writeAtomically(dstFs, dst, replace = ex != null) { w ->
                srcFs.open(src.path).use { i -> dstFs.create(w).use { o -> pump(i, o, check, progress) } }
            }
            if (dstFs.isLocal) File(dst).setLastModified(src.modified)
        }
    }

    fun move(
        srcFs: Fs, src: FileItem, dstFs: Fs, dstDir: String,
        check: () -> Unit = {}, progress: Progress? = null, overwrite: Boolean = false,
    ): String {
        if (srcFs.isLocal && dstFs.isLocal) return FileOps.move(File(src.path), File(dstDir), check, progress, overwrite).path
        if (srcFs.id == dstFs.id) {
            if (srcFs.parent(src.path)?.trimEnd('/') == dstDir.trimEnd('/')) {
                throw OpException("同じフォルダには移動できません: ${src.name}")
            }
            if (src.isDir && isUnder(src.path, dstDir)) throw OpException("フォルダを自分自身の中には移動できません: ${src.name}")
            val target = if (overwrite) dstFs.child(dstDir, src.name) else uniquePath(dstFs, dstDir, src.name, src.isDir)
            val ex = if (overwrite) dstFs.stat(target) else null
            if (ex != null && ex.isDir != src.isDir) throw OpException("同じ名前で種類の違うものがあります: ${src.name}")
            when {
                ex == null -> if (srcFs.moveInside(src.path, target)) return target
                !ex.isDir -> {
                    // ファイルの上書き: 古い方をいったん脇に退避してから移し、失敗したら戻す
                    val backup = target + ".filerold"
                    dstFs.moveInside(target, backup)
                    try {
                        if (srcFs.moveInside(src.path, target)) {
                            runCatching { dstFs.delete(backup) }
                            return target
                        }
                    } catch (e: Exception) {
                        runCatching { if (!dstFs.exists(target)) dstFs.moveInside(backup, target) }
                        throw e
                    }
                    runCatching { if (!dstFs.exists(target)) dstFs.moveInside(backup, target) }
                }
                // フォルダ同士はまとめるので、下のコピー+削除で行う
            }
        }
        val copied = copy(srcFs, src, dstFs, dstDir, check, progress, overwrite)
        srcFs.delete(src.path)
        return copied
    }
    // ---- ZIP(端末内でも NAS でも同じ処理) ----

    /** items を1つの ZIP にまとめて out に作る。作る途中は仮の名前に書き、失敗したら何も残さない。 */
    fun zip(fs: Fs, items: List<FileItem>, out: String, check: () -> Unit = {}, progress: Progress? = null) {
        if (fs.exists(out)) throw OpException("同じ名前のファイルがすでにあります")
        try {
            writeAtomically(fs, out, replace = false) { w ->
                java.util.zip.ZipOutputStream(fs.create(w).buffered()).use { z ->
                    val used = HashSet<String>()
                    for (s in items) {
                        var name = s.name
                        var n = 1
                        while (!used.add(name)) name = "${s.name} (${n++})"
                        addToZip(fs, z, s, name, check, progress, 0)
                    }
                }
            }
        } catch (e: Exception) {
            runCatching { fs.delete(out) }
            throw e
        }
    }

    private fun addToZip(fs: Fs, z: java.util.zip.ZipOutputStream, f: FileItem, path: String, check: () -> Unit, progress: Progress?, depth: Int) {
        check()
        if (f.isDir) {
            z.putNextEntry(java.util.zip.ZipEntry("$path/").apply { if (f.modified > 0) time = f.modified })
            z.closeEntry()
            if (depth < MAX_DEPTH) fs.list(f.path)?.forEach { addToZip(fs, z, it, "$path/${it.name}", check, progress, depth + 1) }
        } else {
            z.putNextEntry(java.util.zip.ZipEntry(path).apply { if (f.modified > 0) time = f.modified })
            progress?.begin(f.path, path)
            fs.open(f.path).use { pump(it, z, check, progress) }
            z.closeEntry()
        }
    }

    /** ZIP の中の名前を、展開先の中の相対パスにする。「..」を含むもの(展開先の外へ出るもの)は null。 */
    fun safeRelative(name: String): String? {
        val parts = name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    /**
     * Fs 上の ZIP を、同じ場所の「拡張子を除いた名前」のフォルダに展開する。ZIP は全部を読み込まず、必要な部分だけを読む。
     * 失敗したら作りかけのフォルダを消す。
     */
    fun unzip(fs: Fs, zip: FileItem, check: () -> Unit = {}, progress: Progress? = null): String {
        val src = fs.openRandom(zip.path) ?: throw OpException("この場所のZIPは展開できません")
        try {
            val reader = ZipReader(src)
            val base = zip.name.let { if (it.endsWith(".zip", ignoreCase = true) && it.length > 4) it.dropLast(4) else it }
            return extractAll(fs, zip, base, reader.entries, { reader.open(it) }, check, progress)
        } finally {
            src.close()
        }
    }

    /** ZIP 以外の書庫(7z・RAR・tar 系・gz など)を、同じ場所の「拡張子を除いた名前」のフォルダに展開する。tmpDir は RAR の一時コピー用。 */
    fun extractArchive(fs: Fs, item: FileItem, tmpDir: java.io.File, check: () -> Unit = {}, progress: Progress? = null): String =
        ArchiveOpener.open(fs, item, tmpDir).use { h ->
            extractAll(fs, item, Archives.baseName(item.name), h.source.entries, { h.source.open(it) }, check, progress)
        }

    private fun extractAll(
        fs: Fs,
        archive: FileItem,
        base: String,
        entries: List<ZipReader.Entry>,
        open: (ZipReader.Entry) -> InputStream,
        check: () -> Unit,
        progress: Progress?,
    ): String {
        val parent = fs.parent(archive.path) ?: throw OpException("展開先が見つかりません")
        val root = uniquePath(fs, parent, base, true)
        if (progress != null && progress.totalBytes == 0L) {
            progress.totalBytes = entries.sumOf { maxOf(it.size, 0L) }
        }
        fs.mkdir(root)
        val made = HashSet<String>().apply { add(root) }
        fun ensureDir(path: String) {
            if (path in made) return
            fs.parent(path)?.let { ensureDir(it) }
            if (!fs.exists(path)) fs.mkdir(path)
            made.add(path)
        }
        try {
            for (e in entries) {
                check()
                val rel = safeRelative(e.name) ?: throw OpException("不正なパスを含む書庫です: ${e.name}")
                if (rel.isEmpty()) continue
                val target = rel.split('/').fold(root) { acc, seg -> fs.child(acc, seg) }
                if (e.isDir) {
                    ensureDir(target)
                    continue
                }
                ensureDir(fs.parent(target) ?: root)
                progress?.begin(archive.path, target)
                // 同じ名前が書庫の中に 2 回あれば、あとのものを残す
                writeAtomically(fs, target, replace = fs.exists(target)) { w ->
                    open(e).use { i -> fs.create(w).use { o -> pump(i, o, check, progress) } }
                }
            }
        } catch (ex: Exception) {
            runCatching { fs.delete(root) }
            throw ex
        }
        return root
    }

    // ---- 他のアプリから受け取ったデータの保存 ----

    /** 読み込み元(他のアプリから渡されたデータ)を、dir に name という名前で保存する。同名があれば「名前 (1)」。 */
    fun importStream(fs: Fs, dir: String, name: String, input: InputStream, check: () -> Unit = {}, progress: Progress? = null): String {
        val target = uniquePath(fs, dir, name, false)
        progress?.begin(name, target)
        try {
            writeAtomically(fs, target, replace = false) { w -> fs.create(w).use { o -> pump(input, o, check, progress) } }
        } catch (e: Exception) {
            runCatching { fs.delete(target) }
            throw e
        }
        return target
    }

    fun stats(fs: Fs, roots: List<FileItem>, check: () -> Unit = {}): Stats {
        var files = 0
        var folders = 0
        var bytes = 0L
        // シンボリックリンクの輪で止まらないよう、深さに上限を付ける
        fun walk(x: FileItem, depth: Int) {
            check()
            if (x.isDir) {
                folders++
                if (depth < MAX_DEPTH) fs.list(x.path)?.forEach { walk(it, depth + 1) }
            } else {
                files++
                bytes += x.size
            }
        }
        roots.forEach { walk(it, 0) }
        return Stats(files, folders, bytes)
    }

    fun search(fs: Fs, root: String, query: String, showHidden: Boolean, limit: Int, check: () -> Unit = {}): List<FileItem> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<FileItem>()
        fun walk(dir: String, depth: Int) {
            check()
            val children = fs.list(dir) ?: return
            for (c in children) {
                if (out.size >= limit) return
                if (!showHidden && c.name.startsWith(".")) continue
                if (c.name.lowercase().contains(q)) out.add(c)
                if (c.isDir && depth < MAX_DEPTH) walk(c.path, depth + 1)
            }
        }
        walk(root, 0)
        return out
    }
}
