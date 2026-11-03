package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** メモリ上だけの Fs。NAS のように「端末の File ではない場所」の動きを、通信なしで確かめるためのもの。 */
private class MemFs(override val id: String) : Fs {
    override val isLocal = false
    val files = HashMap<String, ByteArray>()
    val dirs = hashSetOf("/")

    override fun list(path: String): List<FileItem>? {
        if (path !in dirs) return null
        val p = path.trimEnd('/')
        return (dirs.filter { it != "/" } + files.keys)
            .filter { parent(it) == (if (p.isEmpty()) "/" else p) }
            .map { item(it) }
    }

    private fun item(path: String) = FileItem(
        path, path.trimEnd('/').substringAfterLast('/'), path in dirs, files[path]?.size?.toLong() ?: 0L, 1000L,
    )

    override fun stat(path: String): FileItem? = if (path in dirs || path in files) item(path) else null

    override fun mkdir(path: String) {
        if (parent(path) !in dirs) throw OpException("親フォルダがありません")
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
        val moved = files.keys.filter { it == src || it.startsWith("$src/") }
        moved.forEach { files[dst + it.removePrefix(src)] = files.remove(it)!! }
        val movedDirs = dirs.filter { it == src || it.startsWith("$src/") }
        movedDirs.forEach { dirs.remove(it); dirs.add(dst + it.removePrefix(src)) }
        return true
    }

    override fun open(path: String): InputStream = ByteArrayInputStream(files[path] ?: throw OpException("見つかりません"))

    override fun create(path: String): OutputStream = object : ByteArrayOutputStream() {
        override fun close() {
            files[path] = toByteArray()
        }
    }
}

/** create されたパスを記録する Fs(書き込み中の仮の名前を確かめる用)。 */
private class SpyFs(val inner: MemFs) : Fs by inner {
    val created = ArrayList<String>()
    override fun create(path: String): OutputStream = inner.create(path).also { created.add(path) }
}

class TransferTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun nasWithData(): MemFs = MemFs("smb:test").apply {
        dirs.add("/docs"); dirs.add("/docs/sub")
        files["/docs/a.txt"] = "A".toByteArray()
        files["/docs/sub/b.txt"] = "BB".toByteArray()
        files["/top.txt"] = "T".toByteArray()
    }

    @Test
    fun NASから端末へフォルダごとコピーできる() {
        val nas = nasWithData()
        val dest = tmp.newFolder()
        val src = nas.stat("/docs")!!
        val out = Transfer.copy(nas, src, LocalFs, dest.path)
        assertEquals("BB", File(out, "sub/b.txt").readText())
        assertEquals("A", File(out, "a.txt").readText())
        assertTrue(nas.exists("/docs/a.txt")) // コピーなので元は残る
    }

    @Test
    fun 端末からNASへコピーして同名は番号が付く() {
        val nas = nasWithData()
        val f = File(tmp.newFolder(), "top.txt").apply { writeText("local") }
        val first = Transfer.copy(LocalFs, FileOps.toItem(f), nas, "/")
        assertEquals("/top (1).txt", first)
        assertEquals("local", String(nas.files[first]!!))
        assertEquals("T", String(nas.files["/top.txt"]!!))
    }

    @Test
    fun 端末からNASへ移動すると元が消える() {
        val nas = nasWithData()
        val f = File(tmp.newFolder(), "m.txt").apply { writeText("moved") }
        val p = Transfer.move(LocalFs, FileOps.toItem(f), nas, "/docs")
        assertEquals("/docs/m.txt", p)
        assertFalse(f.exists())
        assertEquals("moved", String(nas.files[p]!!))
    }

    @Test
    fun NAS内の移動は名前変更で行い中身ごと移る() {
        val nas = nasWithData()
        nas.dirs.add("/dest")
        val p = Transfer.move(nas, nas.stat("/docs")!!, nas, "/dest")
        assertEquals("/dest/docs", p)
        assertEquals("BB", String(nas.files["/dest/docs/sub/b.txt"]!!))
        assertFalse(nas.exists("/docs"))
    }

    @Test
    fun NAS内で同じフォルダへの移動と自分自身の中への移動は拒否() {
        val nas = nasWithData()
        try {
            Transfer.move(nas, nas.stat("/top.txt")!!, nas, "/")
            fail()
        } catch (e: OpException) { /* 想定どおり */ }
        try {
            Transfer.move(nas, nas.stat("/docs")!!, nas, "/docs/sub")
            fail()
        } catch (e: OpException) { /* 想定どおり */ }
        try {
            Transfer.copy(nas, nas.stat("/docs")!!, nas, "/docs/sub")
            fail()
        } catch (e: OpException) { /* 想定どおり */ }
        assertTrue(nas.exists("/docs/sub/b.txt"))
    }

    @Test
    fun コピーの途中で失敗したら中途半端なものを残さない() {
        val nas = nasWithData()
        val dest = tmp.newFolder()
        var calls = 0
        try {
            Transfer.copy(nas, nas.stat("/docs")!!, LocalFs, dest.path, check = { if (++calls > 3) throw OpException("中断") })
            fail()
        } catch (e: OpException) { /* 想定どおり */ }
        assertEquals(0, dest.list()!!.size)
    }

    @Test
    fun 検索と統計はNASでも動く() {
        val nas = nasWithData()
        assertEquals(setOf("a.txt", "b.txt", "top.txt"), Transfer.search(nas, "/", ".TXT", true, 100).map { it.name }.toSet())
        assertEquals(setOf("sub"), Transfer.search(nas, "/docs", "sub", true, 100).map { it.name }.toSet())
        assertEquals(Stats(files = 2, folders = 2, bytes = 3), Transfer.stats(nas, listOf(nas.stat("/docs")!!)))
    }

    @Test
    fun NASへのコピーは仮の名前に書いてから本来の名前にする() {
        val spy = SpyFs(MemFs("nas"))
        val f = File(tmp.newFolder(), "x.txt").apply { writeText("data") }
        val out = Transfer.copy(LocalFs, FileOps.toItem(f), spy, "/")
        assertEquals("/x.txt", out)
        assertEquals(listOf("/x.txt.filerpart"), spy.created)
        assertEquals(setOf("/x.txt"), spy.inner.files.keys)
        assertEquals("data", String(spy.inner.files["/x.txt"]!!))
    }

    @Test
    fun 名前が長すぎて仮の名前を付けられないときは直接書く() {
        val spy = SpyFs(MemFs("nas"))
        val name = "a".repeat(250) + ".txt"
        val f = File(tmp.newFolder(), name).apply { writeText("long") }
        val out = Transfer.copy(LocalFs, FileOps.toItem(f), spy, "/")
        assertEquals("/$name", out)
        assertEquals(listOf("/$name"), spy.created)
    }

    @Test
    fun NASへのコピーが途中で失敗したら仮のファイルも残らない() {
        val spy = SpyFs(MemFs("nas"))
        val f = File(tmp.newFolder(), "big.bin").apply { writeBytes(ByteArray(900 * 1024)) }
        var calls = 0
        try {
            Transfer.copy(LocalFs, FileOps.toItem(f), spy, "/", check = { if (++calls > 3) throw OpException("中断") })
            fail()
        } catch (e: OpException) { /* 想定どおり */ }
        assertTrue("途中のファイルが残っている: ${spy.inner.files.keys}", spy.inner.files.isEmpty())
    }

    @Test
    fun 貼り付け先に同名があるものだけが確認の対象になる() {
        val nas = nasWithData()
        val f = File(tmp.newFolder(), "top.txt").apply { writeText("x") }
        val g = File(f.parentFile, "new.txt").apply { writeText("y") }
        val c = Transfer.conflicts(LocalFs, listOf(FileOps.toItem(f), FileOps.toItem(g)), nas, "/")
        assertEquals(listOf("top.txt"), c.map { it.src.name })
        assertEquals("/top.txt", c.single().existing.path)
        // 同じフォルダへのコピーは「名前 (1)」の複製になるので確認しない
        assertTrue(Transfer.conflicts(nas, listOf(nas.stat("/top.txt")!!), nas, "/").isEmpty())
    }

    @Test
    fun 上書きコピーは同じ名前のまま置き換わり_仮のファイルも残らない() {
        val nas = nasWithData()
        val f = File(tmp.newFolder(), "top.txt").apply { writeText("NEW") }
        val out = Transfer.copy(LocalFs, FileOps.toItem(f), nas, "/", overwrite = true)
        assertEquals("/top.txt", out)
        assertEquals("NEW", String(nas.files["/top.txt"]!!))
        assertEquals(setOf("/top.txt", "/docs/a.txt", "/docs/sub/b.txt"), nas.files.keys)
    }

    @Test
    fun 上書きコピーが途中で失敗しても古いファイルは残る() {
        val nas = nasWithData()
        val f = File(tmp.newFolder(), "top.txt").apply { writeBytes(ByteArray(900 * 1024)) }
        var calls = 0
        try {
            Transfer.copy(LocalFs, FileOps.toItem(f), nas, "/", check = { if (++calls > 3) throw OpException("中断") }, overwrite = true)
            fail()
        } catch (e: OpException) { /* 想定どおり */ }
        assertEquals("T", String(nas.files["/top.txt"]!!))
        assertFalse(nas.files.keys.any { it.endsWith(FileOps.PART_SUFFIX) })
    }

    @Test
    fun フォルダ同士の上書きは中身をまとめる() {
        val nas = nasWithData()
        val local = File(tmp.newFolder(), "docs").apply { mkdirs() }
        File(local, "a.txt").writeText("NEWA")
        File(local, "c.txt").writeText("C")
        Transfer.copy(LocalFs, FileOps.toItem(local), nas, "/", overwrite = true)
        assertEquals("NEWA", String(nas.files["/docs/a.txt"]!!))
        assertEquals("C", String(nas.files["/docs/c.txt"]!!))
        assertEquals("BB", String(nas.files["/docs/sub/b.txt"]!!)) // 元からあった別のファイルは残る
        assertFalse(nas.files.containsKey("/docs (1)/a.txt"))
    }

    @Test
    fun 種類が違うものへは上書きできず_元のものは無事() {
        val nas = nasWithData()
        val f = File(tmp.newFolder(), "docs").apply { writeText("file") }
        try {
            Transfer.copy(LocalFs, FileOps.toItem(f), nas, "/", overwrite = true)
            fail()
        } catch (e: OpException) { /* 想定どおり */ }
        assertEquals("A", String(nas.files["/docs/a.txt"]!!))
        assertTrue(nas.dirs.contains("/docs"))
    }

    @Test
    fun NAS内の上書き移動はファイルを置き換え_退避ファイルを残さない() {
        val nas = nasWithData()
        nas.dirs.add("/dest")
        nas.files["/dest/top.txt"] = "OLD".toByteArray()
        val p = Transfer.move(nas, nas.stat("/top.txt")!!, nas, "/dest", overwrite = true)
        assertEquals("/dest/top.txt", p)
        assertEquals("T", String(nas.files["/dest/top.txt"]!!))
        assertFalse(nas.files.containsKey("/top.txt"))
        assertFalse(nas.files.keys.any { it.endsWith(".filerold") })
    }

    @Test
    fun NAS内のフォルダの上書き移動は中身をまとめて元を消す() {
        val nas = nasWithData()
        nas.dirs.add("/dest"); nas.dirs.add("/dest/docs")
        nas.files["/dest/docs/a.txt"] = "OLD".toByteArray()
        nas.files["/dest/docs/z.txt"] = "Z".toByteArray()
        Transfer.move(nas, nas.stat("/docs")!!, nas, "/dest", overwrite = true)
        assertEquals("A", String(nas.files["/dest/docs/a.txt"]!!))
        assertEquals("Z", String(nas.files["/dest/docs/z.txt"]!!))
        assertEquals("BB", String(nas.files["/dest/docs/sub/b.txt"]!!))
        assertFalse(nas.dirs.contains("/docs"))
    }

    @Test
    fun 名前の重複回避は存在確認だけで決まる() {
        val nas = nasWithData()
        assertEquals("/docs (1)", Transfer.uniquePath(nas, "/", "docs", true))
        assertEquals("/new.txt", Transfer.uniquePath(nas, "/", "new.txt", false))
    }
}
