package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

private fun appleRes(name: String): ByteArray = object {}.javaClass.getResourceAsStream("/apple/$name")!!.use { it.readBytes() }

private fun varint(v: Long): ByteArray {
    val o = ByteArrayOutputStream()
    var x = v
    while (x >= 0x80) { o.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }
    o.write(x.toInt())
    return o.toByteArray()
}

/** protobuf: 番号 field の長さ付きデータ */
private fun pbBytes(field: Int, data: ByteArray) = varint(((field shl 3) or 2).toLong()) + varint(data.size.toLong()) + data
private fun pbVarint(field: Int, v: Long) = varint((field shl 3).toLong()) + varint(v)

/** Snappy で「文字(リテラル)だけ」の塊にする(圧縮はしないが、正しい形式) */
private fun snappyLiteral(data: ByteArray): ByteArray {
    val o = ByteArrayOutputStream()
    o.write(varint(data.size.toLong()))
    var p = 0
    while (p < data.size) {
        val n = minOf(60, data.size - p)
        o.write((n - 1) shl 2)
        o.write(data, p, n)
        p += n
    }
    return o.toByteArray()
}

/** .iwa の 1 ファイル分: メッセージ(type, payload)を並べ、Snappy の塊 1 つにして枠をつける */
private fun iwa(vararg msgs: Pair<Int, ByteArray>): ByteArray {
    val body = ByteArrayOutputStream()
    for ((i, m) in msgs.withIndex()) {
        val (type, payload) = m
        val mi = pbVarint(1, type.toLong()) + pbVarint(3, payload.size.toLong())
        val info = pbVarint(1, (i + 1).toLong()) + pbBytes(2, mi)
        body.write(varint(info.size.toLong()))
        body.write(info)
        body.write(payload)
    }
    val comp = snappyLiteral(body.toByteArray())
    return byteArrayOf(0, (comp.size and 0xFF).toByte(), ((comp.size shr 8) and 0xFF).toByte(), ((comp.size shr 16) and 0xFF).toByte()) + comp
}

class SnappyTest {
    @Test
    fun literalsAndCopies() {
        // "abc" + コピー(長さ 6・3 つ前から)= abcabcabc
        val src = byteArrayOf(9, (2 shl 2), 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), ((6 - 1) shl 2) or 2, 3, 0)
        assertEquals("abcabcabc", String(Snappy.decompress(src)))
        // 1 バイトの位置のコピー(タグ 01): 長さ 4・位置 1 = aaaaa
        val src2 = byteArrayOf(5, 0, 'a'.code.toByte(), (((4 - 4) shl 2) or 1), 1)
        assertEquals("aaaaa", String(Snappy.decompress(src2)))
        // 長いリテラル(61 バイト以上は、長さを別のバイトで)
        val long = ByteArray(100) { (it + 1).toByte() }
        val src3 = byteArrayOf(100, (60 shl 2).toByte(), 99.toByte()) + long
        assertTrue(long.contentEquals(Snappy.decompress(src3)))
    }

    @Test
    fun brokenInputFails() {
        for (bad in listOf(byteArrayOf(), byteArrayOf(5), byteArrayOf(3, 8, 'a'.code.toByte()), byteArrayOf(4, 0, 'a'.code.toByte(), ((3 shl 2) or 2).toByte(), 9, 0))) {
            try {
                Snappy.decompress(bad)
                org.junit.Assert.fail("読めてしまった")
            } catch (e: OpException) {
                assertNotNull(e.message)
            }
        }
    }
}

class IworkTest {
    @Test
    fun textsFromIwa() {
        val storage = pbVarint(1, 0) + pbBytes(3, "こんにちは\nせかい".toByteArray()) + pbBytes(3, "二つ目の本文".toByteArray())
        val tableEntry1 = pbVarint(1, 1) + pbBytes(3, "セルA".toByteArray())
        val tableEntry2 = pbVarint(1, 2) + pbBytes(3, "セルB".toByteArray())
        val tableList = pbVarint(1, 1) + pbBytes(3, tableEntry1) + pbBytes(3, tableEntry2)
        val other = pbBytes(3, "無関係".toByteArray())
        val file = iwa(2001 to storage, 9999 to other, 6005 to tableList)
        val msgs = Iwa.messages(file)
        assertEquals(listOf(2001, 9999, 6005), msgs.map { it.type })
        val texts = Iwa.texts(listOf("Index/Document.iwa" to file))
        assertEquals(listOf("こんにちは", "せかい", "二つ目の本文", "セルA", "セルB"), texts)
    }

    @Test
    fun newFormatDocument() {
        val storage = pbBytes(3, "表題\n本文の一行目".toByteArray())
        val files = mapOf(
            "Index/Document.iwa" to iwa(2001 to storage),
            "Index/Metadata.iwa" to iwa(9999 to ByteArray(3)),
            "preview.jpg" to byteArrayOf(1, 2, 3),
            "preview-micro.jpg" to byteArrayOf(1),
            "Data/image-1.png" to ByteArray(50),
            "Data/movie.mov" to ByteArray(500),
            "Metadata/BuildVersionHistory.plist" to """<plist version="1.0"><array><string>Template: Blank (14.0)</string><string>M14.0-7036</string></array></plist>""".toByteArray(),
            "Metadata/Properties.plist" to """<plist version="1.0"><dict><key>fileFormatVersion</key><string>14.0</string></dict></plist>""".toByteArray(),
        )
        val info = Iwork.parse("書類.pages", files.keys, { files[it] })
        assertEquals("Pages", info.app)
        assertFalse(info.legacy)
        assertEquals("preview.jpg", info.preview)
        assertEquals(listOf("表題", "本文の一行目"), info.texts)
        assertEquals(2, info.media.size)
        assertEquals(550L, info.media.sumOf { it.second })
        assertEquals(listOf("Template: Blank (14.0)", "M14.0-7036", "ファイル形式の版: 14.0"), info.versions)
        assertEquals("Numbers", Iwork.appOf("a.numbers"))
        assertEquals("Keynote", Iwork.appOf("a.key"))
    }

    @Test
    fun oldFormatDocumentWithPdf() {
        val xml = """<?xml version="1.0"?><sf:document xmlns:sf="x"><sf:p>古い書類 &amp; 本文</sf:p><sf:p><sf:span>入れ子</sf:span>の段落</sf:p><sf:t sfa:s="セル">値</sf:t></sf:document>"""
        val files = mapOf("index.xml" to xml.toByteArray(), "QuickLook/Thumbnail.jpg" to byteArrayOf(1), "QuickLook/Preview.pdf" to byteArrayOf(2))
        val info = Iwork.parse("a.numbers", files.keys, { files[it] })
        assertTrue(info.legacy)
        assertEquals("QuickLook/Thumbnail.jpg", info.preview)
        assertEquals("QuickLook/Preview.pdf", info.pdf)
        assertEquals(listOf("古い書類 & 本文", "入れ子の段落", "値"), info.texts)
    }

    @Test
    fun brokenIwaDoesNotStopTheInfo() {
        val files = mapOf("Index/Document.iwa" to byteArrayOf(0, 5, 0, 0, 1, 2, 3, 4, 5), "preview.jpg" to byteArrayOf(1))
        val info = Iwork.parse("a.key", files.keys, { files[it] })
        assertEquals("preview.jpg", info.preview)
        assertTrue(info.texts.isEmpty())
        assertNotNull(info.textNote)
    }

    @Test
    fun notIwork() {
        try {
            Iwork.parse("a.pages", setOf("readme.txt"), { null })
            org.junit.Assert.fail()
        } catch (e: OpException) {
            assertNotNull(e.message)
        }
    }
}

class IpaTest {
    @Test
    fun readsInfoPlist() {
        val files = mapOf(
            "Payload/Filer.app/Info.plist" to appleRes("s_bin.plist"),
            "Payload/Filer.app/embedded.mobileprovision" to appleRes("test.mobileprovision"),
            "Payload/Filer.app/_CodeSignature/CodeResources" to ByteArray(10),
            "Payload/Filer.app/Filer" to ByteArray(100),
        )
        val i = Ipa.parse(files.keys, { files[it] })
        assertEquals("ファイラー", i.name)
        assertEquals("local.filemanager.ios", i.bundleId)
        assertEquals("1.2.3", i.version)
        assertEquals("45", i.build)
        assertEquals("15.0", i.minOs)
        assertEquals(listOf("iPhone", "iPad"), i.devices)
        assertEquals(listOf("audio", "fetch"), i.backgroundModes)
        assertTrue(i.signed)
        assertEquals(listOf("カメラ", "写真"), i.permissions.map { it.first })
        assertEquals("写真を撮影するためにカメラを使います。", i.permissions[0].second)
        assertTrue(i.provision!!.contains("テスト用プロファイル"))
        assertTrue(i.provision!!.contains("期限: 2027-01-05"))
    }

    @Test
    fun notAnIpa() {
        try {
            Ipa.parse(setOf("a.txt"), { null })
            org.junit.Assert.fail()
        } catch (e: OpException) {
            assertNotNull(e.message)
        }
        assertNull(null as String?)
    }
}
class AppleSampleTest {
    private fun dir(): java.io.File? = System.getenv("FILER_TD")?.let { java.io.File(it, "18_iPhoneのファイル") }?.takeIf { it.isDirectory }

    private fun <T> withZip(f: java.io.File, block: (Set<String>, (String) -> ByteArray?) -> T): T {
        java.util.zip.ZipFile(f).use { z ->
            val names = z.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toSet()
            return block(names) { n -> z.getEntry(n)?.let { e -> z.getInputStream(e).use { it.readBytes() } } }
        }
    }

    @Test
    fun heifIsRecognized() {
        assertTrue(ImageDecode.isHeif(appleRes("exif.heic")))
        assertFalse(ImageDecode.isHeif(appleRes("s_bin.plist")))
        assertFalse(ImageDecode.isHeif(ByteArray(4)))
    }

    @Test
    fun iworkSamples() {
        org.junit.Assume.assumeTrue(dir() != null)
        val d = java.io.File(dir(), "iWork")
        val pages = withZip(java.io.File(d, "活動報告.pages")) { n, r -> Iwork.parse("活動報告.pages", n, r) }
        assertEquals("Pages", pages.app)
        assertEquals("preview.jpg", pages.preview)
        assertEquals(listOf("2026年度 活動報告", "この書類は、ファイラーの iWork 表示を確かめるための見本です。", "二つ目の段落です。", "改行のある段落。", "見出し2", "表の外の文章"), pages.texts)
        assertEquals(2, pages.media.size)
        val numbers = withZip(java.io.File(d, "売上表.numbers")) { n, r -> Iwork.parse("売上表.numbers", n, r) }
        assertEquals(listOf("品名", "数量", "単価", "りんご", "3", "120", "みかん", "10", "30", "売上表のメモ"), numbers.texts)
        val key = withZip(java.io.File(d, "発表.key")) { n, r -> Iwork.parse("発表.key", n, r) }
        assertEquals("Keynote", key.app)
        assertEquals(listOf("発表のタイトル", "一枚目", "箇条書きの文章", "二枚目の本文"), key.texts)
        val old = withZip(java.io.File(d, "古い形式(2009).pages")) { n, r -> Iwork.parse("a.pages", n, r) }
        assertTrue(old.legacy)
        assertEquals("QuickLook/Preview.pdf", old.pdf)
        assertEquals(listOf("古い形式の書類", "太字と普通の文字。 & 記号"), old.texts)
    }

    @Test
    fun ipaSample() {
        org.junit.Assume.assumeTrue(dir() != null)
        val i = withZip(java.io.File(dir(), "アプリ(ipa)/Filer.ipa")) { n, r -> Ipa.parse(n, r) }
        assertEquals("ファイラー", i.name)
        assertTrue(i.signed)
        try {
            withZip(java.io.File(dir(), "アプリ(ipa)/Info.plistなし.ipa")) { n, r -> Ipa.parse(n, r) }
            org.junit.Assert.fail()
        } catch (e: OpException) {
            assertNotNull(e.message)
        }
    }
}
