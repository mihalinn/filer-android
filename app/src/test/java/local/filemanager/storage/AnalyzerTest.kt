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
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** 大きさだけを持つ、メモリ上のファイル構成(分析の動きを確かめる用)。読めないフォルダも作れる。 */
private class SizeFs : Fs {
    override val id = "smb:size"
    override val isLocal = false
    val dirs = hashSetOf("/")
    val sizes = HashMap<String, Long>()
    val unreadable = HashSet<String>()
    var listCalls = 0

    fun dir(path: String) { dirs.add(path) }
    fun file(path: String, size: Long) { sizes[path] = size }

    override fun list(path: String): List<FileItem>? {
        listCalls++
        if (path in unreadable || path !in dirs) return null
        val p = if (path == "/") "/" else path.trimEnd('/')
        return (dirs.filter { it != "/" } + sizes.keys).filter { parent(it) == p }.map {
            FileItem(it, it.substringAfterLast('/'), it in dirs, if (it in dirs) 0L else sizes[it]!!, 1000L)
        }
    }

    override fun stat(path: String): FileItem? = null
    override fun mkdir(path: String) {}
    override fun delete(path: String) {}
    override fun rename(path: String, newName: String): String = path
    override fun open(path: String): InputStream = ByteArrayInputStream(ByteArray(0))
    override fun create(path: String): OutputStream = throw UnsupportedOperationException()
    override fun moveInside(src: String, dst: String) = false
    override fun space(path: String): Pair<Long, Long>? = 1000L to 400L
}

class AnalyzerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun sample(): SizeFs = SizeFs().apply {
        dir("/a"); dir("/a/sub"); dir("/b")
        file("/a/x.bin", 500); file("/a/sub/y.bin", 300); file("/a/sub/z.bin", 100)
        file("/b/w.bin", 50); file("/top.bin", 1000)
    }

    @Test
    fun フォルダごとの合計とファイル数が数えられる() {
        val r = Analyzer.scan(sample(), "/", "NAS", minDetail = 0)
        assertEquals(1950L, r.root.size)
        assertEquals(5, r.root.files)
        val kids = r.children["/"]!!
        assertEquals(listOf("/top.bin", "/a", "/b"), kids.map { it.path }) // 大きい順
        assertEquals(900L, kids.first { it.path == "/a" }.size)
        assertEquals(3, kids.first { it.path == "/a" }.files)
        assertEquals(400L, r.children["/a"]!!.first { it.path == "/a/sub" }.size)
    }

    @Test
    fun 大きいファイルの上位が大きい順に出る() {
        val r = Analyzer.scan(sample(), "/", "NAS", minDetail = 0)
        assertEquals(listOf("/top.bin", "/a/x.bin", "/a/sub/y.bin", "/a/sub/z.bin", "/b/w.bin"), r.biggestFiles.map { it.path })
    }

    @Test
    fun 大きいファイルの数は上限で打ち切られる() {
        val fs = SizeFs()
        for (i in 1..(Analyzer.BIGGEST + 30)) fs.file("/f$i.bin", i.toLong())
        val r = Analyzer.scan(fs, "/", "NAS", minDetail = 0)
        assertEquals(Analyzer.BIGGEST, r.biggestFiles.size)
        assertEquals((Analyzer.BIGGEST + 30).toLong(), r.biggestFiles.first().size)
        assertEquals(31L, r.biggestFiles.last().size) // 小さい方の30件は落ちる
    }

    @Test
    fun 中身が多すぎるフォルダは上位だけ残し_残りは件数と容量にまとめる() {
        val fs = SizeFs()
        val n = Analyzer.MAX_CHILDREN + 50
        for (i in 1..n) fs.file("/f$i.bin", i.toLong())
        val r = Analyzer.scan(fs, "/", "NAS", minDetail = 0)
        assertEquals(Analyzer.MAX_CHILDREN, r.children["/"]!!.size)
        val (count, bytes) = r.omitted["/"]!!
        assertEquals(50, count)
        assertEquals((1..50).sumOf { it.toLong() }, bytes) // 小さい方の50件
        assertEquals((1..n).sumOf { it.toLong() }, r.root.size) // 合計は省略しても正しい
    }

    @Test
    fun 小さいフォルダは中身の一覧を持たないが_合計には入る() {
        val fs = SizeFs().apply { dir("/tiny"); file("/tiny/a.txt", 10); dir("/big"); file("/big/b.bin", 5000) }
        val r = Analyzer.scan(fs, "/", "NAS", minDetail = 1000)
        assertNull(r.children["/tiny"])
        assertTrue(r.children.containsKey("/big"))
        assertTrue(r.children.containsKey("/")) // 分析した場所は常に持つ
        assertEquals(5010L, r.root.size)
    }

    @Test
    fun 読めないフォルダは0として扱い止まらない() {
        val fs = sample().apply { unreadable.add("/a/sub") }
        val r = Analyzer.scan(fs, "/", "NAS", minDetail = 0)
        assertEquals(1550L, r.root.size) // 400 が読めなかった分だけ減る
    }

    @Test
    fun 中断できる() {
        var calls = 0
        try {
            Analyzer.scan(sample(), "/", "NAS", check = { if (++calls > 2) throw OpException("中断") })
            fail()
        } catch (e: OpException) { /* 想定どおり */ }
    }

    @Test
    fun 辿らない指定のフォルダは数えない() {
        val r = Analyzer.scan(sample(), "/", "NAS", minDetail = 0, isLink = { it == "/a" })
        assertEquals(1050L, r.root.size)
        assertFalse(r.children.containsKey("/a"))
    }

    @Test
    fun 進み具合が数えられる() {
        val p = ScanProgress()
        Analyzer.scan(sample(), "/", "NAS", progress = p)
        assertEquals(5L, p.files)
        assertEquals(1950L, p.bytes)
    }

    @Test
    fun 端末内のフォルダでも同じ結果になる() {
        val root = tmp.newFolder("root")
        File(root, "a/sub").mkdirs()
        File(root, "a/x.bin").writeBytes(ByteArray(500))
        File(root, "a/sub/y.bin").writeBytes(ByteArray(300))
        File(root, "top.bin").writeBytes(ByteArray(1000))
        val r = Analyzer.scan(LocalFs, root.path, "root", minDetail = 0)
        assertEquals(1800L, r.root.size)
        assertEquals(3, r.root.files)
        assertEquals(listOf("top.bin", "a"), r.children[root.path]!!.map { it.name })
    }

    @Test
    fun 空き容量は記憶領域の全体と空きで返る() {
        assertEquals(1000L to 400L, SizeFs().space("/"))
        val dir = tmp.newFolder()
        val space = LocalFs.space(dir.path)
        assertTrue(space == null || (space.first > 0 && space.second <= space.first))
    }
}
