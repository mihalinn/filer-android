package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProgressTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun bytes(dir: File, name: String, size: Int) = File(dir, name).apply {
        parentFile?.mkdirs()
        writeBytes(ByteArray(size) { (it % 251).toByte() })
    }

    @Test
    fun コピーした量が進捗に積み上がる() {
        val d = tmp.newFolder()
        bytes(d, "src/a.bin", 700_000)
        bytes(d, "src/sub/b.bin", 300_000)
        val dest = tmp.newFolder()
        val p = Progress("コピー")
        FileOps.copy(File(d, "src"), dest, progress = p)
        assertEquals(1_000_000L, p.doneBytes)
        assertTrue(p.to.startsWith(dest.path))
        assertNotNull(p.from)
    }

    @Test
    fun 全体の大きさの見積もりと実際のコピー量が一致する() {
        val d = tmp.newFolder()
        bytes(d, "src/a.bin", 123_456)
        bytes(d, "src/x/y/b.bin", 654_321)
        val p = Progress("コピー")
        p.totalBytes = FileOps.stats(listOf(File(d, "src"))).bytes
        FileOps.copy(File(d, "src"), tmp.newFolder(), progress = p)
        assertEquals(p.totalBytes, p.doneBytes)
    }

    @Test
    fun ZIPの圧縮と展開でも進捗が進む() {
        val d = tmp.newFolder()
        bytes(d, "dir/a.bin", 400_000)
        bytes(d, "dir/b.bin", 100_000)
        val zip = File(d, "o.zip")
        val pz = Progress("圧縮")
        FileOps.zip(listOf(File(d, "dir")), zip, progress = pz)
        assertEquals(500_000L, pz.doneBytes)
        val pu = Progress("展開")
        FileOps.unzip(zip, progress = pu)
        assertEquals(500_000L, pu.totalBytes) // 展開では ZIP の中身の合計を自分で数える
        assertEquals(500_000L, pu.doneBytes)
    }

    @Test
    fun 端末とNASの間の転送でも進捗が進む() {
        val f = bytes(tmp.newFolder(), "m.bin", 250_000)
        val p = Progress("コピー")
        val out = ByteArrayFs()
        Transfer.copy(LocalFs, FileOps.toItem(f), out, "/", progress = p)
        assertEquals(250_000L, p.doneBytes)
        assertEquals("/m.bin", p.to)
    }

    @Test
    fun 経過時間の表記() {
        assertEquals("0:00:00", formatElapsed(0))
        assertEquals("0:01:05", formatElapsed(65_000))
        assertEquals("1:00:01", formatElapsed(3_601_000))
    }

    @Test
    fun 割合は全体が分かるときだけ出る() {
        fun v(done: Long, total: Long) = ProgressView("コピー", "", "", done, total, 0, 0, 1, 0)
        assertEquals(0.5f, v(50, 100).fraction)
        assertEquals(null, v(50, 0).fraction)
        assertEquals(1f, v(150, 100).fraction) // 見積もりより多く読んでも 100% を超えない
    }
}

/** 書き込んだ内容を捨てるだけの Fs(進捗の確認用)。 */
private class ByteArrayFs : Fs {
    override val id = "smb:sink"
    override val isLocal = false
    override fun list(path: String): List<FileItem>? = emptyList()
    override fun stat(path: String): FileItem? = null
    override fun mkdir(path: String) {}
    override fun delete(path: String) {}
    override fun rename(path: String, newName: String) = path
    override fun open(path: String): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))
    override fun create(path: String): java.io.OutputStream = java.io.ByteArrayOutputStream()
    override fun moveInside(src: String, dst: String) = false
}
