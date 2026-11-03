package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicTest {
    private fun e(name: String, dir: Boolean = false) = ZipReader.Entry(name, dir, 8, 0, 100, 0, false, 0)

    @Test
    fun 数字は数の大きさで並べる() {
        val names = listOf("page10.jpg", "page2.jpg", "page1.jpg", "Page3.JPG", "page02a.jpg")
        assertEquals(
            listOf("page1.jpg", "page2.jpg", "page02a.jpg", "Page3.JPG", "page10.jpg"),
            names.sortedWith(NaturalOrder),
        )
        assertTrue(NaturalOrder.compare("001.png", "2.png") < 0)
        assertTrue(NaturalOrder.compare("a", "a1") < 0)
        assertEquals(0, NaturalOrder.compare("ABC", "abc"))
        assertTrue(NaturalOrder.compare("第2巻/10.jpg", "第10巻/1.jpg") < 0)
    }

    @Test
    fun ページになる画像だけを選ぶ() {
        val entries = listOf(
            e("book/10.jpg"), e("book/2.jpg"), e("book/1.PNG"), e("book/", true), e("book/readme.txt"),
            e("__MACOSX/book/._1.png"), e("book/.hidden.jpg"), e("book/ComicInfo.xml"), e("book/Thumbs.db"), e("book/3.webp"),
        )
        assertEquals(listOf("book/1.PNG", "book/2.jpg", "book/3.webp", "book/10.jpg"), ComicPages.select(entries).map { it.name })
        assertFalse(ComicPages.isPage("a.pdf"))
        assertTrue(ComicPages.isPage("巻1/001.jpeg"))
    }

    @Test
    fun 種類の判定() {
        assertEquals(PreviewKind.COMIC, PreviewKind.of("a.cbz"))
        assertEquals(PreviewKind.COMIC, PreviewKind.of("a.CBR"))
        assertEquals(PreviewKind.COMIC, PreviewKind.of("a.cb7"))
        assertEquals(PreviewKind.EPUB, PreviewKind.of("本.epub"))
        assertEquals(ArchiveType.ZIP, Archives.typeOf("a.cbz"))
        assertEquals(ArchiveType.RAR, Archives.typeOf("a.cbr"))
        assertEquals(ArchiveType.SEVEN_Z, Archives.typeOf("a.cb7"))
        assertEquals(ArchiveType.ZIP, Archives.typeOf("a.epub"))
        assertEquals(FileKind.ARCHIVE, FileOps.kindOf("a.cbz"))
        assertEquals(FileKind.DOCUMENT, FileOps.kindOf("a.epub"))
    }
}

class EpubTest {
    private val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""

    private fun book(files: Map<String, String>) = Epub.parse { files[it]?.toByteArray() }

    private val opf3 = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
 <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
  <dc:title>吾輩は猫である</dc:title><dc:creator>夏目 漱石</dc:creator><dc:creator>共著者</dc:creator>
  <dc:language>ja</dc:language><dc:publisher>テスト出版</dc:publisher><dc:date>2026-10-25</dc:date><dc:identifier id="id">urn:uuid:1234</dc:identifier>
 </metadata>
 <manifest>
  <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
  <item id="c1" href="text/ch%201.xhtml" media-type="application/xhtml+xml"/>
  <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
  <item id="c3" href="text/ch3.xhtml" media-type="application/xhtml+xml"/>
  <item id="cover" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
 </manifest>
 <spine page-progression-direction="rtl"><itemref idref="c1"/><itemref idref="c2"/><itemref idref="c3"/><itemref idref="zzz"/></spine>
</package>"""

    private val nav = """<!DOCTYPE html><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
<nav epub:type="toc"><ol>
 <li><a href="text/ch%201.xhtml">第一章 &amp; はじめ</a></li>
 <li><a href="text/ch2.xhtml#sec">第二章</a><ol><li><a href="text/ch3.xhtml">第二章の 1 節</a></li></ol></li>
 <li><a href="text/missing.xhtml">存在しない章</a></li>
</ol></nav>
<nav epub:type="landmarks"><ol><li><a href="text/ch3.xhtml">ランドマーク</a></li></ol></nav>
</body></html>"""

    @Test
    fun EPUB3の目録と目次() {
        val b = book(mapOf("META-INF/container.xml" to container, "OEBPS/content.opf" to opf3, "OEBPS/nav.xhtml" to nav))
        assertEquals("吾輩は猫である", b.title)
        assertEquals(listOf("夏目 漱石", "共著者"), b.creators)
        assertEquals("ja", b.language)
        assertEquals("テスト出版", b.publisher)
        assertEquals("urn:uuid:1234", b.identifier)
        assertTrue(b.rtl)
        // 「%20」は空白に直る。manifest に無い idref は読み飛ばす
        assertEquals(listOf("OEBPS/text/ch 1.xhtml", "OEBPS/text/ch2.xhtml", "OEBPS/text/ch3.xhtml"), b.spine)
        assertEquals("OEBPS/images/cover.jpg", b.coverPath)
        assertEquals(listOf("第一章 & はじめ", "第二章", "第二章の 1 節"), b.toc.map { it.title })
        assertEquals(listOf(0, 1, 2), b.toc.map { it.spineIndex })
        assertEquals(listOf(0, 0, 1), b.toc.map { it.depth })
        assertEquals("sec", b.toc[1].anchor)
        assertNull(b.toc[0].anchor)
    }

    private val opf2 = """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="b">
 <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
  <dc:title>EPUB2の本</dc:title><dc:creator opf:role="aut">山田 太郎</dc:creator><dc:language>en</dc:language><meta name="cover" content="img1"/>
 </metadata>
 <manifest>
  <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
  <item id="p1" href="a.html" media-type="application/xhtml+xml"/><item id="p2" href="b.html" media-type="application/xhtml+xml"/>
  <item id="img1" href="c.png" media-type="image/png"/>
 </manifest>
 <spine toc="ncx"><itemref idref="p1"/><itemref idref="p2"/></spine>
</package>"""

    private val ncx = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><navMap>
 <navPoint id="n1" playOrder="1"><navLabel><text>序章</text></navLabel><content src="a.html"/>
   <navPoint id="n2" playOrder="2"><navLabel><text>1.1 節</text></navLabel><content src="a.html#s1"/></navPoint>
 </navPoint>
 <navPoint id="n3" playOrder="3"><navLabel><text>本編</text></navLabel><content src="b.html"/></navPoint>
</navMap></ncx>"""

    @Test
    fun EPUB2のNCXの目次_DOCTYPEつき() {
        val c = container.replace("OEBPS/content.opf", "content.opf")
        val b = book(mapOf("META-INF/container.xml" to c, "content.opf" to opf2, "toc.ncx" to ncx))
        assertEquals("EPUB2の本", b.title)
        assertFalse(b.rtl)
        assertEquals("c.png", b.coverPath)
        assertEquals(listOf("序章", "1.1 節", "本編"), b.toc.map { it.title })
        assertEquals(listOf(0, 1, 0), b.toc.map { it.depth })
        assertEquals(listOf(0, 0, 1), b.toc.map { it.spineIndex })
        assertEquals("s1", b.toc[1].anchor)
    }

    @Test
    fun 読めないものは分かりやすいエラー() {
        try {
            book(mapOf("a.txt" to "x"))
            org.junit.Assert.fail()
        } catch (e: OpException) {
            assertTrue(e.message!!.contains("container.xml"))
        }
        try {
            book(mapOf("META-INF/container.xml" to container))
            org.junit.Assert.fail()
        } catch (e: OpException) {
            assertTrue(e.message!!.contains("content.opf"))
        }
    }

    @Test
    fun パーセント表記と相対パスと暗い配色() {
        assertEquals("画像 1.png", Epub.percentDecode("%E7%94%BB%E5%83%8F%201.png"))
        assertEquals("100%", Epub.percentDecode("100%"))
        assertEquals("a%zz", Epub.percentDecode("a%zz"))
        assertEquals("OEBPS/images/a.png" to "x", Epub.resolve("OEBPS/text", "../images/a.png#x"))
        assertEquals("a.html" to null, Epub.resolve("", "a.html"))
        assertEquals("<html><head><style>", Epub.injectHead("<html><head lang=\"ja\"><style>".replace(" lang=\"ja\"", ""), "").substring(0, 19))
        val inj = Epub.injectHead("<html><head><title>t</title></head><body>b</body></html>", "<X/>")
        assertEquals("<html><head><X/><title>t</title></head><body>b</body></html>", inj)
        assertEquals("<X/>plain", Epub.injectHead("plain", "<X/>"))
        assertEquals("text/html", Epub.mimeFor("a/b.xhtml"))
        assertEquals("text/css", Epub.mimeFor("a.css"))
        assertEquals("image/jpeg", Epub.mimeFor("a.jpg"))
    }
}

/** 環境変数 FILER_BOOK_SAMPLES に、見本のファイルの場所を「;」でつないで入れたときだけ動く。 */
class BookSampleTest {
    @Test
    fun 見本の書籍を読む() {
        val paths = System.getenv("FILER_BOOK_SAMPLES")
        org.junit.Assume.assumeTrue(paths != null)
        for (path in paths!!.split(';').filter { it.isNotBlank() }) {
            val zf = java.util.zip.ZipFile(java.io.File(path))
            if (path.endsWith(".epub", true)) {
                val names = zf.entries().toList().associateBy { ZipTree.clean(it.name) }
                val read: (String) -> ByteArray? = { n -> names[ZipTree.clean(n)]?.let { e -> zf.getInputStream(e).readBytes() } }
                try {
                    val b = Epub.parse(read)
                    println("epub ${java.io.File(path).name}: title=${b.title} rtl=${b.rtl} spine=${b.spine.size} toc=${b.toc.map { it.depth.toString() + ":" + it.title + "->" + it.spineIndex + (it.anchor?.let { a -> "#$a" } ?: "") }} cover=${b.coverPath}")
                    for (p in b.spine) assertTrue("読めない章: $p", read(p) != null)
                } catch (e: OpException) {
                    println("epub ${java.io.File(path).name}: エラー ${e.message}")
                }
            } else {
                val entries = zf.entries().toList().map { ZipReader.Entry(it.name, it.isDirectory, 8, 0, it.size, 0, false, 0) }
                println("comic ${java.io.File(path).name}: pages=${ComicPages.select(entries).map { it.name }}")
                val fi = FileItem(path, java.io.File(path).name, false, java.io.File(path).length(), 0)
                ArchiveOpener.open(LocalFs, fi, java.io.File(System.getProperty("java.io.tmpdir"), "cache")).use { h ->
                    for (e in ComicPages.select(h.source.entries).take(3)) {
                        val a = h.source.open(e).use { it.readBytes() }
                        val b = zf.getInputStream(zf.getEntry(e.name)).readBytes()
                        println("   page ${e.name}: method=${e.method} size=${e.size} csize=${e.compressedSize} read=${a.size} ref=${b.size} same=${a.contentEquals(b)} head=${a.take(4).map { "%02X".format(it) }}")
                    }
                }
            }
        }
    }
}
