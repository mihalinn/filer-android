package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 実際の SMB サーバーに対する動作確認。環境変数 FILER_SMB_HOST が無いときは何もしない(通常のテストでは実行されない)。
 *   FILER_SMB_HOST=NASのIP  FILER_SMB_SHARE=共有名  [FILER_SMB_USER / FILER_SMB_PASS]  (ユーザー無しならゲスト)
 *   FILER_SMB_WRITE=1 のときだけ書き込みも試す。共有の直下に一時フォルダ _filer_smoke_<時刻> を作り、最後に必ず消す。
 */
class SmbSmokeTest {
    private fun config(): RemoteConfig {
        val host = System.getenv("FILER_SMB_HOST")
        assumeTrue(host != null)
        return RemoteConfig(
            id = "smoke", name = "smoke", host = host!!, share = System.getenv("FILER_SMB_SHARE") ?: "storage",
            user = System.getenv("FILER_SMB_USER") ?: "", password = System.getenv("FILER_SMB_PASS") ?: "",
        )
    }

    @Test
    fun 読み取りの操作ができる() {
        val fs = SmbFs(config())
        try {
            val root = fs.list("/")!!
            println("共有の直下: ${root.size} 件 ${root.take(8).map { (if (it.isDir) "[D]" else "[F]") + it.name }}")
            assertEquals(root.size, fs.test())
            val small = root.firstOrNull { !it.isDir && it.size in 1..(1024 * 1024) }
            if (small != null) {
                val bytes = fs.open(small.path).use { it.readBytes() }
                assertEquals("読み取ったバイト数が一覧のサイズと一致する", small.size, bytes.size.toLong())
                assertEquals(small.size, fs.stat(small.path)!!.size)
            }
            val firstDir = root.firstOrNull { it.isDir }
            if (firstDir != null) assertTrue(fs.list(firstDir.path) != null)
            assertFalse(fs.exists("/__not_exist__"))
            println("読み取りの確認: OK(読んだファイル: ${small?.name}, フォルダ: ${firstDir?.name})")
        } finally {
            fs.close()
        }
    }

    @Test
    fun 書き込みの操作ができる() {
        assumeTrue(System.getenv("FILER_SMB_WRITE") != null)
        val fs = SmbFs(config())
        // FILER_SMB_BASE: 書き込める場所(ゲストの共有では、書き込み可にした作業用フォルダ)を共有の直下以外に指定できる
        val base = (System.getenv("FILER_SMB_BASE") ?: "") + "/_filer_smoke_${System.currentTimeMillis()}"
        try {
            fs.mkdir(base)
            fs.mkdir("$base/日本語フォルダ")
            fs.create("$base/日本語フォルダ/メモ.txt").use { it.write("こんにちは".toByteArray()) }
            fs.create("$base/big.bin").use { o -> repeat(40) { o.write(ByteArray(256 * 1024) { b -> (b % 251).toByte() }) } }

            val names = fs.list(base)!!.map { it.name }.toSet()
            assertEquals(setOf("日本語フォルダ", "big.bin"), names)
            assertEquals(40L * 256 * 1024, fs.stat("$base/big.bin")!!.size)
            assertEquals("こんにちは", String(fs.open("$base/日本語フォルダ/メモ.txt").use { it.readBytes() }))

            // 大きめのファイルを読み戻して中身が一致する
            val back = fs.open("$base/big.bin").use { it.readBytes() }
            assertEquals(40 * 256 * 1024, back.size)
            assertTrue(back.indices.all { back[it] == ((it % (256 * 1024)) % 251).toByte() })

            // 名前変更・移動
            val renamed = fs.rename("$base/big.bin", "large.bin")
            assertEquals("$base/large.bin", renamed)
            assertFalse(fs.exists("$base/big.bin"))
            fs.mkdir("$base/dest")
            val moved = Transfer.move(fs, fs.stat(renamed)!!, fs, "$base/dest")
            assertEquals("$base/dest/large.bin", moved)
            Transfer.move(fs, fs.stat("$base/日本語フォルダ")!!, fs, "$base/dest")
            assertEquals("こんにちは", String(fs.open("$base/dest/日本語フォルダ/メモ.txt").use { it.readBytes() }))

            // 同じ共有の中でのコピー(同名は番号付き)
            val copied = Transfer.copy(fs, fs.stat("$base/dest")!!, fs, base)
            assertEquals("$base/dest (1)", copied)
            assertEquals(2, Transfer.stats(fs, listOf(fs.stat(copied)!!)).files)
            assertEquals(2, Transfer.search(fs, base, "メモ", true, 10).size)
        } finally {
            runCatching { fs.delete(base) }
            assertFalse("後片付けできていない", runCatching { fs.exists(base) }.getOrDefault(false))
            fs.close()
        }
    }
}
