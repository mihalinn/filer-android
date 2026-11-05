package local.filemanager

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.DeflaterOutputStream

class BackupFileTest {
    /** 試験を速くするため、軽い重さで鍵を作る(本番は DEFAULT_COST) */
    private val fast = BackupFile.Cost(10, 8, 1)
    private val plain = "秘密の設定 secret settings".toByteArray()

    private fun ok(o: BackupFile.Opened): ByteArray = (o as BackupFile.Opened.Ok).plain
    private fun why(o: BackupFile.Opened): BackupFile.Failure = (o as BackupFile.Opened.Fail).why

    @Test
    fun 暗号化して同じパスワードで開ける() {
        val file = BackupFile.seal(plain, "ぱすわーど-123", fast)
        assertEquals(BackupFile.Kind.ENCRYPTED, BackupFile.peek(file))
        assertArrayEquals(plain, ok(BackupFile.open(file, "ぱすわーど-123")))
    }

    @Test
    fun 本番の重さでも開ける() {
        val file = BackupFile.seal(plain, "password-1234")
        assertArrayEquals(plain, ok(BackupFile.open(file, "password-1234")))
    }

    @Test
    fun パスワードが違うと開けない() {
        val file = BackupFile.seal(plain, "correct-horse", fast)
        assertEquals(BackupFile.Failure.PASSWORD_OR_CORRUPT, why(BackupFile.open(file, "correct-horsE")))
        assertEquals(BackupFile.Failure.PASSWORD_OR_CORRUPT, why(BackupFile.open(file, "")))
    }

    @Test
    fun 全角半角のゆれは同じパスワードとして扱う() {
        val file = BackupFile.seal(plain, "ＡＢＣ12345678", fast)
        assertArrayEquals(plain, ok(BackupFile.open(file, "ABC12345678")))
    }

    @Test
    fun 暗号文を1か所でも書き換えると開けない() {
        val file = BackupFile.seal(plain, "correct-horse", fast)
        for (pos in listOf(13, 30, 45, file.size - 1)) { // salt・nonce・暗号文・認証タグ
            val bad = file.copyOf()
            bad[pos] = (bad[pos].toInt() xor 1).toByte()
            assertEquals("位置 $pos", BackupFile.Failure.PASSWORD_OR_CORRUPT, why(BackupFile.open(bad, "correct-horse")))
        }
    }

    @Test
    fun 重さの設定を書き換えても開けない() {
        val file = BackupFile.seal(plain, "correct-horse", fast)
        val bad = file.copyOf()
        bad[10] = 11 // logN を変える
        assertEquals(BackupFile.Failure.PASSWORD_OR_CORRUPT, why(BackupFile.open(bad, "correct-horse")))
    }

    @Test
    fun 暗号化したファイルから中身の言葉は見えない() {
        val secret = "NASPASS-very-secret".toByteArray()
        val file = BackupFile.seal(secret + plain, "correct-horse", fast)
        assertEquals(-1, String(file, Charsets.ISO_8859_1).indexOf("NASPASS"))
        assertEquals(-1, String(file, Charsets.ISO_8859_1).indexOf("secret"))
    }

    @Test
    fun 同じ中身でも毎回違うファイルになる() {
        val a = BackupFile.seal(plain, "correct-horse", fast)
        val b = BackupFile.seal(plain, "correct-horse", fast)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun パスワードなしは簡易な暗号化で包み_パスワードを見ない() {
        val file = BackupFile.pack(plain, "")
        assertEquals(BackupFile.Kind.SIMPLE, BackupFile.peek(file))
        assertArrayEquals(plain, ok(BackupFile.open(file, "")))
        assertArrayEquals(plain, ok(BackupFile.open(file, "何を入れても関係ない")))
    }

    @Test
    fun パスワードなしでも中身の言葉はそのままでは見えない() {
        val marker = "VISIBLE-MARKER-12345".toByteArray()
        val file = BackupFile.pack(marker + plain, "")
        assertEquals(-1, String(file, Charsets.ISO_8859_1).indexOf("VISIBLE-MARKER"))
        assertFalse(BackupFile.pack(plain, "").contentEquals(BackupFile.pack(plain, ""))) // 毎回違う
    }

    @Test
    fun パスワードなしのファイルも壊れたことは見つける() {
        val file = BackupFile.pack(plain, "")
        for (pos in listOf(10, 25, file.size - 1)) { // nonce・暗号文・認証タグ
            val bad = file.copyOf()
            bad[pos] = (bad[pos].toInt() xor 1).toByte()
            assertEquals("位置 $pos", BackupFile.Failure.PASSWORD_OR_CORRUPT, why(BackupFile.open(bad, "")))
        }
        assertEquals(BackupFile.Failure.NOT_BACKUP, why(BackupFile.open(file.copyOf(20), "")))
    }
    @Test
    fun packはパスワードがあれば暗号化する() {
        assertEquals(BackupFile.Kind.ENCRYPTED, BackupFile.peek(BackupFile.pack(plain, "correct-horse", fast)))
    }

    @Test
    fun バックアップではないファイルは区別できる() {
        assertEquals(BackupFile.Kind.NOT_BACKUP, BackupFile.peek(ByteArray(0)))
        assertEquals(BackupFile.Kind.NOT_BACKUP, BackupFile.peek("{\"json\":true}".toByteArray()))
        assertEquals(BackupFile.Failure.NOT_BACKUP, why(BackupFile.open(ByteArray(100) { it.toByte() }, "x")))
    }

    @Test
    fun 新しい版のファイルは断る() {
        val file = BackupFile.seal(plain, "correct-horse", fast)
        file[8] = (BackupFile.VERSION + 1).toByte()
        assertEquals(BackupFile.Kind.TOO_NEW, BackupFile.peek(file))
        assertEquals(BackupFile.Failure.TOO_NEW, why(BackupFile.open(file, "correct-horse")))
    }

    @Test
    fun 細工した重さの設定は断る() {
        val file = BackupFile.seal(plain, "correct-horse", fast)
        val huge = file.copyOf().also { it[10] = 30 } // logN = 30 はメモリを使い切る
        assertEquals(BackupFile.Failure.NOT_BACKUP, why(BackupFile.open(huge, "correct-horse")))
        val zero = file.copyOf().also { it[11] = 0 }
        assertEquals(BackupFile.Failure.NOT_BACKUP, why(BackupFile.open(zero, "correct-horse")))
    }

    @Test
    fun 大きすぎるファイルは断る() {
        assertEquals(BackupFile.Kind.TOO_BIG, BackupFile.peek(ByteArray(BackupFile.MAX_FILE_BYTES + 1)))
        assertEquals(BackupFile.Failure.TOO_BIG, why(BackupFile.open(ByteArray(BackupFile.MAX_FILE_BYTES + 1), "x")))
    }
}

class BackupCodecTest {
    private val remote = RemoteConfig("r1", "自宅NAS", "192.0.2.10", 445, "share", "docs", "taro", "p@ss", "WORK")

    private fun sample() = BackupData(
        linkedMapOf(
            "theme" to "dark", "themeColor" to "teal", "lang" to "en", "sortAsc" to false, "homeCols" to 5, "viewerFontSp" to 18,
            "bookmarks" to "local\t/storage/emulated/0/Download\nsmb:r1\t/docs", "pinned" to "local\t/sdcard",
        ),
        listOf(remote),
    )

    /** 中身を直接組み立てる(形が違う・値が不正なファイルの検査用) */
    private fun raw(build: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DeflaterOutputStream(bytes).use { DataOutputStream(it).use(build) }
        return bytes.toByteArray()
    }

    private fun DataOutputStream.str(s: String) { val b = s.toByteArray(); writeInt(b.size); write(b) }

    private fun head(count: Int): DataOutputStream.() -> Unit = { writeInt(0x46424431); writeInt(count) }

    @Test
    fun 書き出して読み込むと同じになる() {
        val d = BackupCodec.decode(BackupCodec.encode(sample()))!!
        assertEquals(sample().prefs, d.prefs)
        assertEquals(listOf(remote), d.remotes)
    }

    @Test
    fun 暗号化まで通しても同じになる() {
        val file = BackupFile.pack(BackupCodec.encode(sample()), "correct-horse", BackupFile.Cost(10, 8, 1))
        val d = BackupCodec.decode((BackupFile.open(file, "correct-horse") as BackupFile.Opened.Ok).plain)!!
        assertEquals(sample().prefs, d.prefs)
        assertEquals("p@ss", d.remotes.single().password)
    }

    @Test
    fun 決まっていない名前の設定は書き出さない() {
        val d = BackupData(mapOf("devMode" to true, "perfLogWanted" to true, "theme" to "dark"), emptyList())
        assertEquals(mapOf<String, Any>("theme" to "dark"), BackupCodec.decode(BackupCodec.encode(d))!!.prefs)
    }

    @Test
    fun 知らない名前や合わない型は読み込まない() {
        val bytes = raw {
            head(4)()
            str("devMode"); writeByte(1); writeBoolean(true)      // 決まっていない名前
            str("sortAsc"); writeByte(3); str("yes")              // 型が違う(真偽のはずが文字)
            str("theme"); writeByte(3); str("dark")               // 正しい
            str("homeCols"); writeByte(2); writeInt(6)            // 正しい
            writeInt(0)
        }
        val d = BackupCodec.decode(bytes)!!
        assertEquals(mapOf<String, Any>("theme" to "dark", "homeCols" to 6), d.prefs)
    }

    @Test
    fun 使えない値は読み込まない() {
        val bytes = raw {
            head(4)()
            str("theme"); writeByte(3); str("neon")               // 決まった言葉にない
            str("lang"); writeByte(3); str("xx")                  // 対応していない言語
            str("homeCols"); writeByte(2); writeInt(-5)           // 範囲の外
            str("sortKey"); writeByte(3); str("DATE")             // 正しい
            writeInt(0)
        }
        assertEquals(mapOf<String, Any>("sortKey" to "DATE"), BackupCodec.decode(bytes)!!.prefs)
    }

    @Test
    fun 不正なNASの接続先は読み込まない() {
        val bytes = raw {
            head(0)()
            writeInt(3)
            for ((id, host, port) in listOf(Triple("ok", "nas.local", 445), Triple("", "nas.local", 445), Triple("p0", "nas.local", 0))) {
                str(id); str("名前"); str(host); writeInt(port); str("share"); str(""); str(""); str(""); str("")
            }
        }
        assertEquals(listOf("ok"), BackupCodec.decode(bytes)!!.remotes.map { it.id })
    }

    @Test
    fun 壊れたデータは読み込まない() {
        assertNull(BackupCodec.decode(ByteArray(0)))
        assertNull(BackupCodec.decode("not a backup".toByteArray()))
        val good = BackupCodec.encode(sample())
        assertNull(BackupCodec.decode(good.copyOf(good.size / 2)))
        assertNull(BackupCodec.decode(raw { writeInt(0x12345678); writeInt(0) })) // 目印が違う
        assertNull(BackupCodec.decode(raw { head(1)(); str("theme"); writeByte(9) })) // 知らない型
        assertNull(BackupCodec.decode(raw { head(1)(); str("theme"); writeByte(3); writeInt(Int.MAX_VALUE) })) // 長さが不正
    }

    @Test
    fun 小さなファイルが巨大に膨らむ細工は弾く() {
        val bomb = ByteArrayOutputStream().also { o -> DeflaterOutputStream(o).use { it.write(ByteArray(6 * 1024 * 1024)) } }.toByteArray()
        assertTrue(bomb.size < 100_000)
        assertNull(BackupCodec.decode(bomb))
    }

    @Test
    fun 場所の一覧は足し合わせて重ならない() {
        assertEquals("a\nb\nc", BackupCodec.mergeLines("a\nb", "b\nc"))
        assertEquals("a", BackupCodec.mergeLines("", "a\n\n"))
        assertEquals("", BackupCodec.mergeLines("", ""))
    }

    @Test
    fun 中身の件数を数える() {
        val s = BackupCodec.summarize(sample())
        assertEquals(1, s.remotes)
        assertEquals(2, s.bookmarks)
        assertEquals(1, s.pinned)
        assertEquals(0, s.history)
        assertEquals(6, s.settings) // theme, themeColor, lang, sortAsc, homeCols, viewerFontSp(場所の一覧は数えない)
        assertNotNull(s)
    }

    @Test
    fun 履歴の名前と場所の名前は別() {
        assertTrue(BackupCodec.HISTORY.none { it in BackupCodec.PLACES })
        assertTrue((BackupCodec.PLACES + BackupCodec.HISTORY).all { BackupCodec.SCHEMA[it] == BackupCodec.T.STR })
    }
}
