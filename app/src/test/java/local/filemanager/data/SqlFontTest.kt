package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class SqlFormatTest {
    @Test
    fun 名前の引用() {
        assertEquals("\"users\"", SqlFormat.quote("users"))
        assertEquals("\"a\"\"b\"", SqlFormat.quote("a\"b"))
        assertEquals("\"売上 表\"", SqlFormat.quote("売上 表"))
    }

    @Test
    fun 読むだけの検索か() {
        assertTrue(SqlFormat.isReadOnlyQuery("SELECT * FROM t"))
        assertTrue(SqlFormat.isReadOnlyQuery("  select 1;  "))
        assertTrue(SqlFormat.isReadOnlyQuery("-- 説明\nSELECT 1"))
        assertTrue(SqlFormat.isReadOnlyQuery("/* c */ WITH x AS (SELECT 1) SELECT * FROM x"))
        assertTrue(SqlFormat.isReadOnlyQuery("PRAGMA table_info(t)"))
        assertTrue(SqlFormat.isReadOnlyQuery("EXPLAIN QUERY PLAN SELECT 1"))
        assertTrue(SqlFormat.isReadOnlyQuery("SELECT ';' AS semi, \"a;b\" FROM t -- ; コメントの中"))
        assertFalse(SqlFormat.isReadOnlyQuery("DELETE FROM t"))
        assertFalse(SqlFormat.isReadOnlyQuery("DROP TABLE t"))
        assertFalse(SqlFormat.isReadOnlyQuery("INSERT INTO t VALUES (1)"))
        assertFalse(SqlFormat.isReadOnlyQuery("UPDATE t SET a = 1"))
        assertFalse(SqlFormat.isReadOnlyQuery("SELECT 1; DROP TABLE t"))
        assertFalse(SqlFormat.isReadOnlyQuery("SELECT 1; SELECT 2"))
        assertFalse(SqlFormat.isReadOnlyQuery(""))
        assertFalse(SqlFormat.isReadOnlyQuery("   ;  "))
        assertFalse(SqlFormat.isReadOnlyQuery("ATTACH DATABASE 'x' AS y"))
    }

    @Test
    fun 値の見せ方() {
        assertEquals("BLOB 3 バイト(01 AB FF)", SqlFormat.blob(byteArrayOf(1, 0xAB.toByte(), 0xFF.toByte())))
        assertEquals("BLOB 10 バイト(00 01 02 03 04 05 06 07 …)", SqlFormat.blob(ByteArray(10) { it.toByte() }))
        assertEquals("BLOB 0 バイト", SqlFormat.blob(ByteArray(0)))
        assertEquals("1", SqlFormat.real(1.0))
        assertEquals("1.5", SqlFormat.real(1.5))
        assertEquals("-3", SqlFormat.real(-3.0))
        assertEquals("1.0E20", SqlFormat.real(1e20))
    }

    @Test
    fun ページの送り() {
        assertEquals(500, SqlFormat.pageStart(0, 500, 1200, true))
        assertEquals(1000, SqlFormat.pageStart(500, 500, 1200, true))
        assertEquals(1000, SqlFormat.pageStart(1000, 500, 1200, true)) // 最後のページからは進まない
        assertEquals(0, SqlFormat.pageStart(500, 500, 1200, false))
        assertEquals(0, SqlFormat.pageStart(0, 500, 1200, false))
        assertEquals(0, SqlFormat.pageStart(0, 500, 0, true))
    }
}

class FontInfoTest {
    @Test
    fun フォントでないものは読まない() {
        assertNull(FontInfo.parse(ByteArray(100)))
        assertNull(FontInfo.parse("hello world, this is text".toByteArray()))
        assertFalse(FontInfo.looksLikeFont(ByteArray(4)))
    }

    /** 環境変数 FILER_FONT_SAMPLES に、フォントの場所を「;」でつないで入れたときだけ動く。 */
    @Test
    fun 実物のフォントを読む() {
        val paths = System.getenv("FILER_FONT_SAMPLES")
        assumeTrue(paths != null)
        for (path in paths!!.split(';').filter { it.isNotBlank() }) {
            val info = FontInfo.parse(File(path).readBytes())
            assertNotNull("読めない: $path", info)
            info!!
            println(
                "font ${File(path).name}: family=${info.family} style=${info.style} full=${info.fullName} ja=${info.familyJa} glyphs=${info.glyphs} upm=${info.unitsPerEm} " +
                    "weight=${info.weight} mono=${info.monospace} var=${info.variable} cff=${info.cff} count=${info.collectionCount} coverage=${info.coverage().filter { it.second }.map { it.first }}",
            )
            assertTrue(info.glyphs > 0)
            assertTrue(info.supports('A'.code))
        }
    }
}
