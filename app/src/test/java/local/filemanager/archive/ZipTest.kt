package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** メモリ上のバイト列を、好きな位置から読める形にしたもの(NAS 上の ZIP の代わり)。 */
private class BytesSource(val data: ByteArray) : RandomSource {
    override val size = data.size.toLong()
    override fun readAt(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos >= data.size) return -1
        val n = minOf(len.toLong(), data.size - pos).toInt()
        System.arraycopy(data, pos.toInt(), buf, off, n)
        return n
    }

    override fun close() {}
}

/** ZIP の読み出しだけを試すための、メモリ上の Fs。 */
private class ZipFs(private val files: MutableMap<String, ByteArray>) : Fs {
    override val id = "smb:zip"
    override val isLocal = false
    val dirs = hashSetOf("/")

    override fun list(path: String): List<FileItem>? {
        if (path !in dirs) return null
        val p = path.trimEnd('/')
        val pp = if (p.isEmpty()) "/" else p
        return (dirs.filter { it != "/" } + files.keys).filter { parent(it) == pp }.map { item(it) }
    }

    private fun item(path: String) = FileItem(path, path.trimEnd('/').substringAfterLast('/'), path in dirs, files[path]?.size?.toLong() ?: 0L, 1000L)
    override fun stat(path: String): FileItem? = if (path in dirs || path in files) item(path) else null
    override fun mkdir(path: String) {
        if (parent(path) !in dirs) throw OpException("親フォルダがありません: $path")
        dirs.add(path)
    }

    override fun delete(path: String) {
        files.keys.removeAll { it == path || it.startsWith("$path/") }
        dirs.removeAll { it == path || it.startsWith("$path/") }
    }

    override fun rename(path: String, newName: String): String {
        val dst = child(parent(path)!!, newName)
        moveInside(path, dst)
        return dst
    }

    override fun moveInside(src: String, dst: String): Boolean {
        files[src]?.let { files[dst] = files.remove(src)!! }
        return true
    }

    override fun open(path: String): InputStream = ByteArrayInputStream(files[path] ?: throw OpException("見つかりません: $path"))
    override fun create(path: String): OutputStream = object : ByteArrayOutputStream() {
        override fun close() {
            files[path] = toByteArray()
        }
    }

    override fun openRandom(path: String): RandomSource? = files[path]?.let { BytesSource(it) }
    val data get() = files
}

class ZipTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun makeZip(charset: Charset = Charsets.UTF_8, build: (ZipOutputStream) -> Unit): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo, charset).use(build)
        return bo.toByteArray()
    }

    private fun ZipOutputStream.file(name: String, content: ByteArray, stored: Boolean = false) {
        val e = ZipEntry(name)
        if (stored) {
            e.method = ZipEntry.STORED
            e.size = content.size.toLong()
            e.compressedSize = content.size.toLong()
            e.crc = java.util.zip.CRC32().also { it.update(content) }.value
        }
        putNextEntry(e)
        write(content)
        closeEntry()
    }

    // ---- ZipReader ----

    @Test
    fun ZIPの一覧と中身を読める() {
        val big = ByteArray(300_000) { (it % 251).toByte() } // 圧縮されて複数回に分けて読まれる大きさ
        val zip = makeZip {
            it.file("a.txt", "hello".toByteArray())
            it.putNextEntry(ZipEntry("dir/")); it.closeEntry()
            it.file("dir/b.bin", big)
            it.file("stored.txt", "plain".toByteArray(), stored = true)
        }
        val r = ZipReader(BytesSource(zip))
        assertEquals(listOf("a.txt", "dir/", "dir/b.bin", "stored.txt"), r.entries.map { it.name })
        assertTrue(r.entries[1].isDir)
        assertEquals("hello", String(r.open(r.entries[0]).use { it.readBytes() }))
        assertTrue(big.contentEquals(r.open(r.entries[2]).use { it.readBytes() }))
        assertEquals("plain", String(r.open(r.entries[3]).use { it.readBytes() }))
    }

    @Test
    fun ShiftJISの名前のZIPを読める() {
        val sjis = Charset.forName("windows-31j")
        val zip = makeZip(sjis) {
            it.file("録画/番組表.txt", "x".toByteArray())
            it.file("メモ.txt", "y".toByteArray())
        }
        val r = ZipReader(BytesSource(zip))
        assertEquals(listOf("録画/番組表.txt", "メモ.txt"), r.entries.map { it.name })
    }

    @Test
    fun UTF8の名前のZIPを読める() {
        val zip = makeZip { it.file("日本語/ファイル.txt", "z".toByteArray()) }
        assertEquals(listOf("日本語/ファイル.txt"), ZipReader(BytesSource(zip)).entries.map { it.name })
    }

    @Test
    fun ZIPでないものと壊れたものはエラー() {
        try { ZipReader(BytesSource(ByteArray(100) { 7 })); fail() } catch (e: OpException) { /* 想定どおり */ }
        try { ZipReader(BytesSource(ByteArray(5))); fail() } catch (e: OpException) { /* 想定どおり */ }
        val ok = makeZip { it.file("a.txt", "hello".toByteArray()) }
        try { ZipReader(BytesSource(ok.copyOf(ok.size - 10))); fail() } catch (e: OpException) { /* 想定どおり */ }
    }

    // ---- Transfer.zip / unzip ----

    @Test
    fun NAS上でZIPに圧縮して_NAS上で展開すると元の内容に戻る() {
        val nas = ZipFs(hashMapOf())
        nas.dirs.addAll(listOf("/src", "/src/sub"))
        nas.data["/src/a.txt"] = "AAA".toByteArray()
        nas.data["/src/sub/b.bin"] = ByteArray(200_000) { (it % 7).toByte() }
        nas.data["/top.txt"] = "TOP".toByteArray()

        Transfer.zip(nas, listOf(nas.stat("/src")!!, nas.stat("/top.txt")!!), "/pack.zip")
        assertTrue(nas.data.containsKey("/pack.zip"))
        assertFalse(nas.data.keys.any { it.endsWith(FileOps.PART_SUFFIX) })

        val out = Transfer.unzip(nas, nas.stat("/pack.zip")!!)
        assertEquals("/pack", out)
        assertEquals("AAA", String(nas.data["/pack/src/a.txt"]!!))
        assertTrue(nas.data["/src/sub/b.bin"]!!.contentEquals(nas.data["/pack/src/sub/b.bin"]!!))
        assertEquals("TOP", String(nas.data["/pack/top.txt"]!!))
    }

    @Test
    fun 展開先の名前が重なれば番号が付く() {
        val zip = makeZip { it.file("f.txt", "1".toByteArray()) }
        val nas = ZipFs(hashMapOf("/x.zip" to zip))
        nas.dirs.add("/x")
        assertEquals("/x (1)", Transfer.unzip(nas, nas.stat("/x.zip")!!))
        assertEquals("1", String(nas.data["/x (1)/f.txt"]!!))
    }

    @Test
    fun 展開先の外へ出る名前は拒否され_何も残らない() {
        val zip = makeZip {
            it.file("ok.txt", "1".toByteArray())
            it.file("../evil.txt", "2".toByteArray())
        }
        val nas = ZipFs(hashMapOf("/bad.zip" to zip))
        try { Transfer.unzip(nas, nas.stat("/bad.zip")!!); fail() } catch (e: OpException) { /* 想定どおり */ }
        assertFalse(nas.dirs.contains("/bad"))
        assertEquals(setOf("/bad.zip"), nas.data.keys)
    }

    @Test
    fun 展開の途中で止められたら作りかけのフォルダを消す() {
        val zip = makeZip {
            it.file("a.txt", "1".toByteArray())
            it.file("b.txt", "2".toByteArray())
        }
        val nas = ZipFs(hashMapOf("/c.zip" to zip))
        var calls = 0
        try { Transfer.unzip(nas, nas.stat("/c.zip")!!, check = { if (++calls > 2) throw OpException("中断") }); fail() } catch (e: OpException) { /* 想定どおり */ }
        assertFalse(nas.dirs.contains("/c"))
        assertFalse(nas.data.keys.any { it.startsWith("/c/") })
    }

    @Test
    fun ZIPの中のパスの整え方() {
        assertEquals("a/b.txt", Transfer.safeRelative("a/b.txt"))
        assertEquals("a/b.txt", Transfer.safeRelative("/a//b.txt"))
        assertEquals("a/b.txt", Transfer.safeRelative("a\\b.txt"))
        assertEquals("a/b.txt", Transfer.safeRelative("./a/./b.txt"))
        assertNull(Transfer.safeRelative("../x"))
        assertNull(Transfer.safeRelative("a/../../x"))
        assertEquals("", Transfer.safeRelative("/"))
    }

    @Test
    fun 同じ名前のZIPがあれば作らない() {
        val nas = ZipFs(hashMapOf("/pack.zip" to ByteArray(3), "/a.txt" to "A".toByteArray()))
        try { Transfer.zip(nas, listOf(nas.stat("/a.txt")!!), "/pack.zip"); fail() } catch (e: OpException) { /* 想定どおり */ }
        assertEquals(3, nas.data["/pack.zip"]!!.size) // 既存のものは無事
    }

    // ---- 他のアプリから受け取った内容の保存 ----

    @Test
    fun 受け取った内容を保存し_同名は番号が付く() {
        val nas = ZipFs(hashMapOf("/pic.jpg" to "old".toByteArray()))
        val p1 = Transfer.importStream(nas, "/", "pic.jpg", ByteArrayInputStream("new".toByteArray()))
        assertEquals("/pic (1).jpg", p1)
        assertEquals("new", String(nas.data[p1]!!))
        assertEquals("old", String(nas.data["/pic.jpg"]!!))
        assertFalse(nas.data.keys.any { it.endsWith(FileOps.PART_SUFFIX) })
    }

    @Test
    fun 保存が失敗したら何も残らない() {
        val nas = ZipFs(hashMapOf())
        val broken = object : InputStream() {
            override fun read(): Int = throw java.io.IOException("途中で切れた")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw java.io.IOException("途中で切れた")
        }
        try { Transfer.importStream(nas, "/", "x.bin", broken); fail() } catch (e: java.io.IOException) { /* 想定どおり */ }
        assertTrue(nas.data.isEmpty())
    }

    @Test
    fun 受け取った名前は保存できる形に整えられる() {
        assertEquals("a_b_c.txt", FileOps.safeFileName("a/b:c.txt"))
        assertEquals("共有ファイル", FileOps.safeFileName(null))
        assertEquals("共有ファイル", FileOps.safeFileName("   "))
        assertEquals("メモ.txt", FileOps.safeFileName("メモ.txt"))
        assertEquals("fallback", FileOps.safeFileName("", "fallback"))
        val long = FileOps.safeFileName("x".repeat(300) + ".jpg")
        assertTrue(long.toByteArray().size <= 255)
        assertTrue(long.endsWith(".jpg"))
    }

    @Test
    fun 端末内のZIPとNAS向けのZIP処理が同じ形式で作れる() {
        // 端末内(File)で作った ZIP を、ZipReader でも読める
        val dir = tmp.newFolder()
        val f = File(dir, "a.txt").apply { writeText("local") }
        val out = File(dir, "o.zip")
        FileOps.zip(listOf(f), out)
        val r = ZipReader(BytesSource(out.readBytes()))
        assertEquals(listOf("a.txt"), r.entries.map { it.name })
        assertEquals("local", String(r.open(r.entries[0]).use { it.readBytes() }))
    }
}
