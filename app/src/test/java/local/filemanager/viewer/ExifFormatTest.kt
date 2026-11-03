package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExifFormatTest {
    @Test
    fun 数の読み取り() {
        assertEquals(24.0, ExifFormat.number("24/1")!!, 1e-9)
        assertEquals(0.004, ExifFormat.number("0.004")!!, 1e-9)
        assertEquals(2.8, ExifFormat.number(" 14/5 ")!!, 1e-9)
        assertNull(ExifFormat.number("1/0"))
        assertNull(ExifFormat.number(""))
        assertNull(ExifFormat.number(null))
        assertNull(ExifFormat.number("abc"))
    }

    @Test
    fun シャッター速度() {
        assertEquals("1/250 秒", ExifFormat.exposure("0.004"))
        assertEquals("1/250 秒", ExifFormat.exposure("1/250"))
        assertEquals("1/2 秒", ExifFormat.exposure("0.5"))
        assertEquals("2 秒", ExifFormat.exposure("2"))
        assertEquals("1.5 秒", ExifFormat.exposure("1.5"))
        assertNull(ExifFormat.exposure("0"))
        assertNull(ExifFormat.exposure(null))
    }

    @Test
    fun 絞りと焦点距離とISO() {
        assertEquals("f/2.8", ExifFormat.fNumber("2.8"))
        assertEquals("f/4", ExifFormat.fNumber("4.0"))
        assertEquals("f/1.8", ExifFormat.fNumber("9/5"))
        assertEquals("24 mm", ExifFormat.focalLength("24/1"))
        assertEquals("24 mm(35mm換算 36 mm)", ExifFormat.focalLength("24/1", "36"))
        assertEquals("50 mm", ExifFormat.focalLength("50", "50"))
        assertEquals("ISO 200", ExifFormat.iso("200"))
        assertNull(ExifFormat.iso("0"))
        assertEquals("+0.7 EV", ExifFormat.exposureBias("2/3"))
        assertEquals("-1.0 EV", ExifFormat.exposureBias("-1"))
        assertEquals("±0 EV", ExifFormat.exposureBias("0"))
    }

    @Test
    fun 日付と向きとフラッシュ() {
        assertEquals("2026/10/25 09:30:15", ExifFormat.date("2026:10:25 09:30:15"))
        assertNull(ExifFormat.date("0000:00:00 00:00:00"))
        assertEquals("不明な形式", ExifFormat.date("不明な形式"))
        assertNull(ExifFormat.orientation(1))
        assertEquals("右に90度回転(縦向きで撮影)", ExifFormat.orientation(6))
        assertEquals("発光した", ExifFormat.flash(1))
        assertEquals("発光しなかった", ExifFormat.flash(16))
        assertNull(ExifFormat.flash(null))
    }

    @Test
    fun 場所と画素数() {
        assertEquals("北緯 35.65875°", ExifFormat.latitude(35.65875))
        assertEquals("南緯 33.86882°", ExifFormat.latitude(-33.86882))
        assertEquals("東経 139.74556°", ExifFormat.longitude(139.74556))
        assertEquals("西経 122.41942°", ExifFormat.longitude(-122.41942))
        assertEquals("geo:35.658750,139.745560?q=35.658750,139.745560", ExifFormat.geoUri(35.65875, 139.74556))
        assertEquals("12.0 メガピクセル", ExifFormat.megapixels(4000, 3000))
    }

    @Test
    fun SVGは画像として扱い_ソースはXMLとして見られる() {
        assertEquals(FileKind.IMAGE, FileOps.kindOf("絵.svg"))
        assertEquals("image/svg+xml", FileOps.mimeOf("a.SVG"))
        assertEquals(PreviewKind.XML, PreviewKind.of("a.svg"))
        assertTrue(Highlight.langFor("svg")?.markup == true)
    }
}
