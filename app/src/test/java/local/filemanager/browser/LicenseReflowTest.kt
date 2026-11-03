package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LicenseReflowTest {
    @Test
    fun 同じ段落の行は1行につなぐ() {
        val raw = "This is the first line of a paragraph\nthat continues on the second line.\n\nNext paragraph."
        assertEquals(
            listOf("This is the first line of a paragraph that continues on the second line.", "Next paragraph."),
            reflowLicense(raw),
        )
    }

    @Test
    fun 箇条書きは別のかたまりにする() {
        val raw = "Terms:\n(a) first item that is\nlong.\n(b) second item.\n1. numbered."
        assertEquals(
            listOf("Terms:", "(a) first item that is long.", "(b) second item.", "1. numbered."),
            reflowLicense(raw),
        )
    }

    @Test
    fun 字下げが同じ続きの行はつなぎ_字下げが変わると分ける() {
        val raw = "                    GNU GENERAL PUBLIC LICENSE\n                       Version 3\n\n  Body text starts here\n  and goes on."
        val r = reflowLicense(raw)
        assertEquals(listOf("GNU GENERAL PUBLIC LICENSE", "Version 3", "Body text starts here and goes on."), r)
    }

    @Test
    fun 罫線だけの行は捨てて_改行コードの違いは気にしない() {
        val r = reflowLicense("A line\r\n-----\r\nAnother line")
        assertEquals(listOf("A line", "Another line"), r)
        assertTrue(reflowLicense("").isEmpty())
    }
}
