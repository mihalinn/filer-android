package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {
    private val repo = "https://github.com/mihalinn/filer-android"

    // GitHub の「最新のリリース」の応答(実際のものから、使う所だけ)
    private val sample = """
        {"tag_name":"v0.43.2","name":"ファイラー 0.43.2","prerelease":false,"draft":false,
         "html_url":"https://github.com/mihalinn/filer-android/releases/tag/v0.43.2",
         "assets":[{"name":"Filer-0.43.2.apk","size":17505495,"content_type":"application/vnd.android.package-archive",
                    "browser_download_url":"https://github.com/mihalinn/filer-android/releases/download/v0.43.2/Filer-0.43.2.apk"}],
         "body":"## ファイラー 0.43.2\n\n最初の公開版です。\n\n- 動作環境: Android 11 以上\n- SHA-256: `A965EFBA55D94D2425B6D6E96F69BC5EAE4A5F04002A9DD6BB24A931B7F32EA8`\n- 無保証です"}
    """.trimIndent()

    @Test
    fun 実際の応答から情報を取り出す() {
        val r = UpdateLogic.parseLatest(sample, repo)
        assertNotNull(r)
        r!!
        assertEquals("v0.43.2", r.tag)
        assertEquals("0.43.2", r.version)
        assertEquals("Filer-0.43.2.apk", r.apkName)
        assertEquals(17505495L, r.apkSize)
        assertEquals("a965efba55d94d2425b6d6e96f69bc5eae4a5f04002a9dd6bb24a931b7f32ea8", r.sha256)
        assertTrue(r.apkUrl.startsWith("$repo/releases/download/"))
        assertTrue(r.notes.startsWith("ファイラー 0.43.2"))
        assertTrue(r.notes.contains("・動作環境"))
        assertFalse(r.notes.contains("`"))
    }

    @Test
    fun 下書き_先行公開_APKなし_ほかのリポジトリのURLは無視する() {
        assertNull(UpdateLogic.parseLatest(sample.replace("\"draft\":false", "\"draft\":true"), repo))
        assertNull(UpdateLogic.parseLatest(sample.replace("\"prerelease\":false", "\"prerelease\":true"), repo))
        assertNull(UpdateLogic.parseLatest(sample.replace(".apk\"", ".zip\""), repo))
        assertNull(UpdateLogic.parseLatest(sample, "https://github.com/someone/else"))
        assertNull(UpdateLogic.parseLatest("これはJSONではない", repo))
        assertNull(UpdateLogic.parseLatest("""{"tag_name":"最新","assets":[]}""", repo))
    }

    @Test
    fun バージョンは数字ごとに比べる() {
        assertTrue(UpdateLogic.isNewer("0.43.2", "v0.44.0"))
        assertTrue(UpdateLogic.isNewer("0.9.9", "0.10.0"))
        assertTrue(UpdateLogic.isNewer("0.44", "0.44.1"))
        assertFalse(UpdateLogic.isNewer("0.44.6", "v0.43.2"))
        assertFalse(UpdateLogic.isNewer("0.44.6", "0.44.6"))
        assertFalse(UpdateLogic.isNewer("0.44.6", "0.44.6.0"))
        assertFalse(UpdateLogic.isNewer("0.44.6", "最新"))
        assertEquals(listOf(0, 43, 2), UpdateLogic.versionParts("v0.43.2-beta"))
        assertNull(UpdateLogic.compare("abc", "1.0"))
    }

    @Test
    fun SHA256は説明にあるときだけ取り出す() {
        assertNull(UpdateLogic.sha256Of("説明だけ"))
        assertNull(UpdateLogic.sha256Of("SHA-256: 短すぎる"))
        val hex = "0123456789abcdef".repeat(4)
        assertEquals(hex, UpdateLogic.sha256Of("sha256 = $hex"))
        assertEquals(hex, UpdateLogic.sha256Of("SHA-256: `${hex.uppercase()}`"))
    }

    @Test
    fun 説明は先頭の数行だけにする() {
        val body = (1..30).joinToString("\n") { "行$it" }
        assertEquals(14, UpdateLogic.shortNotes(body).lines().size)
        assertEquals("", UpdateLogic.shortNotes(""))
        assertEquals("見出し", UpdateLogic.shortNotes("\n\n### 見出し"))
    }
}