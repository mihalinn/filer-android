package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavTest {
    private val storage = "/storage/emulated/0"
    private val download = "$storage/Download"

    @Test
    fun ダウンロードを最上位として開くと_そこで上へでホームに戻る() {
        val root = Nav.effectiveRoot(download, download, storage)
        assertEquals(download, root)
        assertNull("ダウンロードの1つ上(メインストレージ)ではなくホームへ", Nav.up(download, root, storage))
    }

    @Test
    fun ダウンロードの中のフォルダからは1つずつ上がり_ダウンロードで止まる() {
        val root = Nav.effectiveRoot("$download/X/Y", download, storage)
        assertEquals(download, root)
        assertEquals("$download/X", Nav.up("$download/X/Y", root, "$download/X"))
        assertEquals(download, Nav.up("$download/X", root, download))
        assertNull(Nav.up(download, root, storage))
    }

    @Test
    fun 最上位を決めていないときは従来どおりストレージ直下まで上がれる() {
        val root = Nav.effectiveRoot(download, null, storage)
        assertEquals(storage, root)
        assertEquals(storage, Nav.up(download, root, storage))
        assertNull(Nav.up(storage, root, "/storage/emulated"))
    }

    @Test
    fun 最上位の外のフォルダには最上位が効かない() {
        assertEquals(storage, Nav.effectiveRoot("$storage/Pictures", download, storage))
        // 名前が前方一致するだけの別フォルダ(Download2)は中ではない
        assertFalse(Nav.isUnder(download, "$storage/Download2"))
        assertEquals(storage, Nav.effectiveRoot("$storage/Download2", download, storage))
        assertTrue(Nav.isUnder(download, "$download/"))
    }

    @Test
    fun 親が無い場所ではホームに戻る() {
        assertNull(Nav.up("/", "/", null))
        assertNull(Nav.up("/x", "/", null))
    }
}
