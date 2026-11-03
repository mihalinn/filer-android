package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater

/** CadDrawing を PC 上で PNG にする(見た目の確認用。AWT が使えないので、線は自前で引く。塗りは外形線、文字は印だけ) */
internal object CadPng {
    fun render(d: CadDrawing, file: File, w: Int = 1600, h: Int = 1100, hidden: Boolean = false) {
        val px = IntArray(w * h) { 0xFF14181C.toInt() }
        val fw = maxOf(d.fitMaxX - d.fitMinX, 1e-6f); val fh = maxOf(d.fitMaxY - d.fitMinY, 1e-6f)
        val s = minOf((w - 40f) / fw, (h - 40f) / fh)
        val cx = (d.fitMinX + d.fitMaxX) / 2f; val cy = (d.fitMinY + d.fitMaxY) / 2f
        fun sx(x: Float) = w / 2f + (x - cx) * s
        fun sy(y: Float) = h / 2f - (y - cy) * s
        fun put(x: Int, y: Int, c: Int) { if (x in 0 until w && y in 0 until h) px[y * w + x] = c }
        fun line(x0: Float, y0: Float, x1: Float, y1: Float, c: Int) {
            val dx = x1 - x0; val dy = y1 - y0
            val n = maxOf(Math.abs(dx), Math.abs(dy)).toInt().coerceAtMost(20000)
            if (n == 0) { put(x0.toInt(), y0.toInt(), c); return }
            for (i in 0..n) put((x0 + dx * i / n).toInt(), (y0 + dy * i / n).toInt(), c)
        }
        fun col(c: Int) = CadColor.adapt(c, true)
        for (f in d.fills) {
            if (!hidden && !d.layers[f.layer].visible) continue
            var o = 0
            for (n in f.lens) { for (j in 0 until n) { val a = o + j; val b = o + (j + 1) % n; line(sx(f.xy[a * 2]), sy(f.xy[a * 2 + 1]), sx(f.xy[b * 2]), sy(f.xy[b * 2 + 1]), col(f.color)) }; o += n }
        }
        for (st in d.strokes) {
            if (!hidden && !d.layers[st.layer].visible) continue
            var o = 0
            for (i in st.lens.indices) {
                val n = st.lens[i]
                for (j in 0 until n - 1) line(sx(st.xy[(o + j) * 2]), sy(st.xy[(o + j) * 2 + 1]), sx(st.xy[(o + j + 1) * 2]), sy(st.xy[(o + j + 1) * 2 + 1]), col(st.color))
                if (st.closed[i] && n > 2) line(sx(st.xy[(o + n - 1) * 2]), sy(st.xy[(o + n - 1) * 2 + 1]), sx(st.xy[o * 2]), sy(st.xy[o * 2 + 1]), col(st.color))
                o += n
            }
        }
        for (t in d.texts) {
            if (!hidden && !d.layers[t.layer].visible) continue
            // 文字は、書き出す長さの下線で示す
            val len = t.text.length * t.height * s * 0.6f * t.widthFactor
            val a = Math.toRadians(t.rotation.toDouble())
            val x0 = sx(t.x); val y0 = sy(t.y)
            line(x0, y0, x0 + (len * Math.cos(a)).toFloat(), y0 - (len * Math.sin(a)).toFloat(), 0xFF80CBC4.toInt())
        }
        file.parentFile.mkdirs()
        writePng(file, px, w, h)
    }

    private fun writePng(file: File, px: IntArray, w: Int, h: Int) {
        val raw = ByteArray((w * 3 + 1) * h)
        var o = 0
        for (y in 0 until h) {
            raw[o++] = 0
            for (x in 0 until w) { val c = px[y * w + x]; raw[o++] = (c shr 16).toByte(); raw[o++] = (c shr 8).toByte(); raw[o++] = c.toByte() }
        }
        val def = Deflater(6); def.setInput(raw); def.finish()
        val buf = ByteArray(raw.size + 1024)
        val n = def.deflate(buf)
        fun chunk(out: java.io.OutputStream, type: String, data: ByteArray, len: Int) {
            val dos = java.io.DataOutputStream(out)
            dos.writeInt(len)
            val tb = type.toByteArray(Charsets.ISO_8859_1)
            dos.write(tb); dos.write(data, 0, len)
            val crc = CRC32(); crc.update(tb); crc.update(data, 0, len)
            dos.writeInt(crc.value.toInt())
        }
        file.outputStream().use { out ->
            out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            val ihdr = java.nio.ByteBuffer.allocate(13).putInt(w).putInt(h).put(8).put(2).put(0).put(0).put(0).array()
            chunk(out, "IHDR", ihdr, 13)
            chunk(out, "IDAT", buf, n)
            chunk(out, "IEND", ByteArray(0), 0)
        }
    }
}
class AciTest {
    @Test
    fun matchesTheStandardTable() {
        assertEquals(0xFFFF0000.toInt(), Aci.rgb(1))
        assertEquals(0xFFFFFF00.toInt(), Aci.rgb(2))
        assertEquals(0, Aci.rgb(7))
        assertEquals(0xFFFF0000.toInt(), Aci.rgb(10))
        assertEquals(0xFFFF7F7F.toInt(), Aci.rgb(11))
        assertEquals(0xFFCC0000.toInt(), Aci.rgb(12))
        assertEquals(0xFFFF3F00.toInt(), Aci.rgb(20))
        assertEquals(0xFFFFFF00.toInt(), Aci.rgb(50))
        assertEquals(0xFF333333.toInt(), Aci.rgb(250))
        assertEquals(0xFFFFFFFF.toInt(), Aci.rgb(255))
    }

    @Test
    fun colorsAreReadableOnBothBackgrounds() {
        fun lum(c: Int) = (0.2126f * ((c shr 16) and 0xFF) + 0.7152f * ((c shr 8) and 0xFF) + 0.0722f * (c and 0xFF)) / 255f
        for (i in 1..255) {
            val c = Aci.rgb(i)
            assertTrue("暗い背景で見える $i", lum(CadColor.adapt(c, true)) > 0.2f)
            assertTrue("明るい背景で見える $i", lum(CadColor.adapt(c, false)) < 0.75f)
        }
    }
}

class DxfTest {
    private fun dxf(vararg groups: String): ByteArray = groups.joinToString("\r\n", postfix = "\r\n").toByteArray(Charsets.UTF_8)

    private val head = arrayOf("0", "SECTION", "2", "HEADER", "9", "\$ACADVER", "1", "AC1027", "0", "ENDSEC")
    private fun entities(vararg g: String) = dxf(*head, "0", "SECTION", "2", "ENTITIES", *g, "0", "ENDSEC", "0", "EOF")

    @Test
    fun linesCirclesAndArcsBecomeStrokes() {
        val d = Dxf.parse(
            entities(
                "0", "LINE", "8", "A", "10", "0", "20", "0", "11", "100", "21", "0",
                "0", "CIRCLE", "8", "A", "10", "50", "20", "50", "40", "10",
                "0", "ARC", "8", "B", "62", "1", "10", "0", "20", "0", "40", "20", "50", "0", "51", "90",
            ),
        )
        assertEquals(3, d.shapeCount)
        assertEquals(setOf("A", "B"), d.layers.map { it.name }.toSet())
        val arc = d.strokes.single { d.layers[it.layer].name == "B" }
        assertEquals(0xFFFF0000.toInt(), arc.color)
        // 円弧は 0〜90 度なので、始点から終点まで x は 20 減り、y は 20 増える
        val n = arc.lens[0]
        assertEquals(-20f, arc.xy[(n - 1) * 2] - arc.xy[0], 0.01f)
        assertEquals(20f, arc.xy[(n - 1) * 2 + 1] - arc.xy[1], 0.01f)
        assertEquals(100f, d.maxX - d.minX, 0.01f)
    }

    @Test
    fun lwpolylineWithBulgeMakesAnArc() {
        val d = Dxf.parse(
            entities(
                "0", "LWPOLYLINE", "8", "0", "90", "2", "70", "0",
                "10", "0", "20", "0", "42", "1", "10", "2", "20", "0",
            ),
        )
        val s = d.strokes.single()
        val pts = s.lens[0]
        assertTrue("半円は多数の点になる", pts > 20)
        // 半円(膨らみ 1)の弧は、直径 2 の円の下側。高さは 1
        assertEquals(1f, d.maxY - d.minY, 0.02f)
        assertEquals(2f, d.maxX - d.minX, 0.02f)
    }

    @Test
    fun insertAppliesScaleRotationAndBasePoint() {
        val bytes = dxf(
            *head,
            "0", "SECTION", "2", "BLOCKS",
            "0", "BLOCK", "2", "B1", "10", "1", "20", "1",
            "0", "LINE", "8", "0", "10", "1", "20", "1", "11", "2", "21", "1",
            "0", "ENDBLK",
            "0", "ENDSEC",
            "0", "SECTION", "2", "ENTITIES",
            "0", "INSERT", "8", "L", "2", "B1", "10", "10", "20", "20", "41", "2", "42", "2", "50", "90",
            "0", "ENDSEC", "0", "EOF",
        )
        val d = Dxf.parse(bytes)
        // 線は基点 (1,1) から長さ 1 → 拡大 2 → 90 度回転 → (10,20) から真上へ長さ 2
        val s = d.strokes.single()
        assertEquals(2, s.lens[0])
        val h = d.maxY - d.minY
        val w = d.maxX - d.minX
        assertEquals(2f, h, 0.01f)
        assertEquals(0f, w, 0.01f)
        assertEquals("L", d.layers[s.layer].name) // 層「0」の図形は、挿入した層になる
    }

    @Test
    fun textKeepsSpecialSymbolsAndMtextFormatting() {
        assertEquals("Φ10 ±2 90°".replace("Φ", "⌀"), DxfText.clean("%%c10 %%p2 90%%d"))
        assertEquals("あ", DxfText.clean("\\U+3042"))
        assertEquals("1行目\n2行目", DxfText.mtext("{\\fMS Gothic|b0|i0|c128;1行目}\\P2行目"))
        assertEquals("a/b", DxfText.mtext("\\Sa^b;"))
    }

    @Test
    fun shiftJisFilesAreDecodedByCodepage() {
        val txt = listOf(
            "0", "SECTION", "2", "HEADER", "9", "\$ACADVER", "1", "AC1015", "9", "\$DWGCODEPAGE", "3", "ANSI_932", "0", "ENDSEC",
            "0", "SECTION", "2", "ENTITIES",
            "0", "TEXT", "8", "文字", "10", "0", "20", "0", "40", "5", "1", "日本語の図面",
            "0", "LINE", "8", "文字", "10", "0", "20", "0", "11", "10", "21", "0",
            "0", "ENDSEC", "0", "EOF",
        ).joinToString("\r\n", postfix = "\r\n")
        val d = Dxf.parse(txt.toByteArray(charset("windows-31j")))
        assertEquals("文字", d.layers.single().name)
        assertEquals("日本語の図面", d.texts.single().text)
    }

    @Test
    fun hatchWithPolylineBoundaryIsFilled() {
        val d = Dxf.parse(
            entities(
                "0", "HATCH", "8", "H", "10", "0", "20", "0", "30", "0", "70", "1", "71", "0", "91", "1",
                "92", "7", "72", "0", "73", "1", "93", "4",
                "10", "0", "20", "0", "10", "10", "20", "0", "10", "10", "20", "10", "10", "0", "20", "10",
                "97", "0", "75", "0", "76", "1", "98", "0",
            ),
        )
        assertEquals(1, d.fills.size)
        assertEquals(4, d.fills[0].lens[0])
    }

    @Test
    fun splineIsEvaluatedThroughItsControlPoints() {
        val d = Dxf.parse(
            entities(
                "0", "SPLINE", "8", "S", "70", "8", "71", "2", "72", "6", "73", "3", "74", "0",
                "40", "0", "40", "0", "40", "0", "40", "1", "40", "1", "40", "1",
                "10", "0", "20", "0", "10", "5", "20", "10", "10", "10", "20", "0",
            ),
        )
        val s = d.strokes.single()
        val n = s.lens[0]
        assertTrue(n > 20)
        // 2 次の B スプラインは、始点と終点を通る
        assertEquals(10f, d.maxX - d.minX, 0.05f)
        assertEquals(5f, d.maxY - d.minY, 0.1f)
    }

    @Test
    fun binaryDxfIsRefusedClearly() {
        try {
            Dxf.parse("AutoCAD Binary DXF\r\n\u001a\u0000".toByteArray(Charsets.ISO_8859_1))
            fail()
        } catch (e: OpException) {
            assertTrue(e.message!!.contains("バイナリ"))
        }
    }

    @Test
    fun dwgVersionNames() {
        assertEquals("AutoCAD 2013", dwgVersion("AC1027"))
        assertEquals(null, dwgVersion("zzzzzz"))
    }
}

class MeshFormatsTest {
    private fun tri(m: Mesh) = m.triangles

    @Test
    fun objQuadsAreSplitAndYUpIsConverted() {
        val obj = """
            # square
            v 0 0 0
            v 1 0 0
            v 1 1 0
            v 0 1 0
            f 1 2 3 4
            f -4 -3 -2
        """.trimIndent()
        val m = MeshFormats.parseObj(obj.byteInputStream())
        assertEquals(3, tri(m))
        // Y 上の (x, y, z) は Z 上の (x, -z, y) になるので、y=1 の頂点は高さ(Z)1
        assertEquals(1f, m.sizeZ, 1e-4f)
        assertEquals(1f, m.sizeX, 1e-4f)
    }

    @Test
    fun objVertexColorsAreKept() {
        val obj = "v 0 0 0 1 0 0\nv 1 0 0 0 1 0\nv 0 1 0 0 0 1\nf 1 2 3\n"
        val m = MeshFormats.parseObj(obj.byteInputStream())
        assertNotNull(m.colors)
        assertEquals(1f, m.colors!!.get(0), 1e-4f)
        assertEquals(1f, m.colors!!.get(4), 1e-4f)
        assertEquals(1f, m.colors!!.get(8), 1e-4f)
    }

    @Test
    fun objWithoutFacesIsRefused() {
        try {
            MeshFormats.parseObj("v 0 0 0\nv 1 0 0\n".byteInputStream())
            fail()
        } catch (e: OpException) {
            assertTrue(e.message!!.contains("面"))
        }
    }

    @Test
    fun asciiPlyWithColors() {
        val ply = """
            ply
            format ascii 1.0
            comment test
            element vertex 4
            property float x
            property float y
            property float z
            property uchar red
            property uchar green
            property uchar blue
            element face 2
            property list uchar int vertex_indices
            end_header
            0 0 0 255 0 0
            1 0 0 0 255 0
            1 1 0 0 0 255
            0 1 0 255 255 255
            3 0 1 2
            3 0 2 3
        """.trimIndent() + "\n"
        val m = MeshFormats.parsePly(ply.byteInputStream())
        assertEquals(2, tri(m))
        assertNotNull(m.colors)
        assertEquals(1f, m.colors!!.get(0), 1e-4f)
    }

    @Test
    fun binaryPlyBigAndLittleEndianAndPointClouds() {
        fun build(order: java.nio.ByteOrder, name: String): ByteArray {
            val head = "ply\nformat $name 1.0\nelement vertex 3\nproperty float x\nproperty float y\nproperty float z\nelement face 1\nproperty list uchar uint vertex_indices\nend_header\n"
            val bb = java.nio.ByteBuffer.allocate(3 * 12 + 1 + 12).order(order)
            floatArrayOf(0f, 0f, 0f, 2f, 0f, 0f, 0f, 3f, 0f).forEach { bb.putFloat(it) }
            bb.put(3); bb.putInt(0); bb.putInt(1); bb.putInt(2)
            return head.toByteArray() + bb.array()
        }
        for ((o, n) in listOf(java.nio.ByteOrder.LITTLE_ENDIAN to "binary_little_endian", java.nio.ByteOrder.BIG_ENDIAN to "binary_big_endian")) {
            val m = MeshFormats.parsePly(build(o, n).inputStream())
            assertEquals(1, tri(m))
            assertEquals(2f, m.sizeX, 1e-4f)
            assertEquals(3f, m.sizeY, 1e-4f)
        }
        val cloud = "ply\nformat ascii 1.0\nelement vertex 3\nproperty float x\nproperty float y\nproperty float z\nend_header\n0 0 0\n1 1 1\n2 0 0\n"
        val pc = MeshFormats.parsePly(cloud.byteInputStream())
        assertTrue(pc.points)
        assertEquals(3, pc.vertexCount)
    }

    @Test
    fun offWithFaceColors() {
        val off = "COFF\n# c\n4 1 0\n0 0 0 255 0 0 255\n1 0 0 0 255 0 255\n1 1 0 0 0 255 255\n0 1 0 255 255 255 255\n4 0 1 2 3\n"
        val m = MeshFormats.parseOff(off.byteInputStream())
        assertEquals(2, tri(m))
        assertNotNull(m.colors)
    }

    @Test
    fun threeMfWithTransformMaterialAndComponents() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
 <resources>
  <basematerials id="5"><base name="red" displaycolor="#FF0000FF"/><base name="blue" displaycolor="#0000FF"/></basematerials>
  <object id="1" type="model" pid="5" pindex="0"><mesh>
   <vertices><vertex x="0" y="0" z="0"/><vertex x="10" y="0" z="0"/><vertex x="0" y="10" z="0"/><vertex x="0" y="0" z="10"/></vertices>
   <triangles><triangle v1="0" v2="1" v3="2"/><triangle v1="0" v2="1" v3="3" pid="5" p1="1"/></triangles>
  </mesh></object>
  <object id="2"><components><component objectid="1" transform="1 0 0 0 1 0 0 0 1 100 0 0"/></components></object>
 </resources>
 <build><item objectid="2" transform="1 0 0 0 1 0 0 0 1 0 50 0"/></build>
</model>"""
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("[Content_Types].xml")); z.write("<Types/>".toByteArray()); z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("3D/3dmodel.model")); z.write(xml.toByteArray()); z.closeEntry()
        }
        val m = MeshFormats.parse3mf(bos.toByteArray().inputStream())
        assertEquals(2, tri(m))
        // 部品の移動 (100,0,0) + 配置の移動 (0,50,0)
        assertEquals(100f, m.min[0], 1e-3f)
        assertEquals(110f, m.max[0], 1e-3f)
        assertEquals(50f, m.min[1], 1e-3f)
        assertNotNull(m.colors)
        assertEquals(1f, m.colors!!.get(0), 1e-4f) // 1 つ目の三角形は材質 0(赤)
    }

    @Test
    fun supportedExtensions() {
        for (e in listOf("stl", "obj", "ply", "off", "3mf")) assertTrue(MeshFormats.supports(e))
        assertEquals(PreviewKind.MODEL, PreviewKind.of("a.OBJ"))
        assertEquals(PreviewKind.CAD, PreviewKind.of("図面.dxf"))
        assertEquals(PreviewKind.CAD, PreviewKind.of("平面図.JWW"))
        assertEquals(PreviewKind.CAD, PreviewKind.of("old.jwc"))
    }
}

/** 実物の JW_CAD ファイル(PC 上のフォルダ。環境変数 FILER_JWW_SAMPLES)を読んで、概要と絵を書き出す。 */
class JwwSampleTest {
    private fun dir(): File? = System.getenv("FILER_JWW_SAMPLES")?.let { File(it) }?.takeIf { it.isDirectory }

    @Test
    fun readsEveryRealSample() {
        val d = dir()
        assumeTrue(d != null)
        val files = d!!.listFiles { f -> f.extension.lowercase() == "jww" }!!.sortedBy { it.name }
        assertTrue(files.isNotEmpty())
        val report = StringBuilder()
        var failed = 0
        for (f in files) {
            try {
                val t0 = System.nanoTime()
                val cad = Jww.parse(f.readBytes())
                val ms = (System.nanoTime() - t0) / 1_000_000
                report.append("${f.name}: shapes=${cad.shapeCount} layers=${cad.layers.count { it.count > 0 }}/${cad.layers.size} hidden=${cad.layers.count { it.count > 0 && !it.visible }} texts=${cad.texts.size} fills=${cad.fills.size} size=${"%.0f".format(cad.maxX - cad.minX)}x${"%.0f".format(cad.maxY - cad.minY)} ${ms}ms note='${cad.note}'\n")
                report.append("   layers: " + cad.layers.filter { it.count > 0 }.take(8).joinToString(" | ") { "${it.name}${if (it.visible) "" else "(非表示)"}=${it.count}" } + "\n")
                cad.texts.take(3).forEach { report.append("   text: '${it.text}' h=${it.height} rot=${it.rotation}\n") }
                CadPng.render(cad, File(d, "png/${f.nameWithoutExtension}.png"))
            } catch (e: Exception) {
                failed++
                report.append("${f.name}: FAIL ${e::class.simpleName}: ${e.message}\n")
            }
        }
        File(d, "report.txt").writeText(report.toString())
        assertEquals(report.toString(), 0, failed)
    }
}

/** 手で組み立てた JW_CAD(.jww)。MFC のアーカイブ(個数・種類の目印・図形)を再現する */
private class JwwBuilder(val version: Int = 600) {
    val out = java.io.ByteArrayOutputStream()
    private val classPid = HashMap<String, Int>()
    private var next = 1

    fun u8(v: Int) = out.write(v)
    fun u16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
    fun u32(v: Long) { u16((v and 0xFFFF).toInt()); u16(((v shr 16) and 0xFFFF).toInt()) }
    fun f64(v: Double) { val l = java.lang.Double.doubleToLongBits(v); u32(l and 0xFFFFFFFFL); u32((l ushr 32) and 0xFFFFFFFFL) }
    fun cstring(b: ByteArray) { u8(b.size); out.write(b) }

    fun header() {
        out.write("JwwData.".toByteArray()); u32(version.toLong())
        cstring(ByteArray(0)) // メモ
        u32(8); u32(0) // 用紙・書き込みグループ
        for (g in 0 until 16) {
            u32(2); u32(0); f64(1.0); u32(0)
            for (l in 0 until 16) { u32(if (g == 0 && l == 3) 0 else 2); u32(0) } // 0-3 は非表示
        }
    }

    /** 図形の種類の目印(最初は種類名つき、2 回目からは参照) */
    private fun tag(name: String) {
        val pid = classPid[name]
        if (pid == null) {
            u16(0xFFFF); u16(version); u16(name.length); out.write(name.toByteArray())
            classPid[name] = next++
        } else u16(0x8000 or pid)
    }

    fun base(style: Int = 1, color: Int = 1, layer: Int = 0, group: Int = 0) {
        u32(0); u8(style); u16(color); if (version >= 351) u16(0); u16(layer); u16(group); u16(0)
    }

    fun line(x1: Double, y1: Double, x2: Double, y2: Double, color: Int = 1, layer: Int = 0) {
        tag("CDataSen"); base(color = color, layer = layer); f64(x1); f64(y1); f64(x2); f64(y2); next++
    }

    fun circle(cx: Double, cy: Double, r: Double) {
        tag("CDataEnko"); base(); f64(cx); f64(cy); f64(r); f64(0.0); f64(0.0); f64(0.0); f64(1.0); u32(1); next++
    }

    fun text(x: Double, y: Double, size: Double, s: ByteArray) {
        tag("CDataMoji"); base(); f64(x); f64(y); f64(x + size * s.size / 2); f64(y); u32(0)
        f64(size); f64(size); f64(0.0); f64(0.0); cstring("ＭＳ ゴシック".toByteArray(charset("windows-31j"))); cstring(s); next++
    }

    fun block(x: Double, y: Double, sx: Double, sy: Double, rot: Double, def: Int) {
        tag("CDataBlock"); base(layer = 5); f64(x); f64(y); f64(sx); f64(sy); f64(rot); u32(def.toLong()); next++
    }

    fun count(n: Int) = u16(n)

    fun blockDef(number: Int, name: String, body: JwwBuilder.() -> Unit, n: Int) {
        tag("CDataList"); next++
        base(); u32(number.toLong()); u32(1); out.write(ByteArray(4)); cstring(name.toByteArray())
        count(n); body()
    }

    fun bytes(): ByteArray = out.toByteArray()
}

class JwwSyntheticTest {
    private fun sample(): ByteArray {
        val b = JwwBuilder()
        b.header()
        b.count(5)
        b.line(0.0, 0.0, 100.0, 0.0)
        b.line(0.0, 0.0, 0.0, 50.0, color = 2, layer = 3)
        b.circle(50.0, 25.0, 10.0)
        b.text(10.0, 10.0, 5.0, "日本語の図面".toByteArray(charset("windows-31j")))
        b.block(200.0, 100.0, 2.0, 2.0, Math.PI / 2, 1)
        b.count(1) // ブロック定義の数
        b.blockDef(1, "B1", { line(0.0, 0.0, 10.0, 0.0) }, 1)
        return b.bytes()
    }

    @Test
    fun readsLinesCircleTextAndBlocks() {
        val d = Jww.parse(sample())
        // 線 2 + 円 1 + 文字 1 + ブロックの中の線 1
        assertEquals(5, d.shapeCount)
        assertEquals("日本語の図面", d.texts.single().text)
        assertEquals(5.0f, d.texts.single().height, 1e-4f)
        // ブロック: 拡大 2、90 度回転、(200,100)に置く → 線は (200,100) から真上へ長さ 20
        val blockLine = d.strokes.flatMap { s -> (0 until s.lens.size).map { s } }.first { st -> d.layers[st.layer].name.startsWith("0-5") }
        assertEquals(2, blockLine.lens[0])
        assertEquals(0f, blockLine.xy[2] - blockLine.xy[0], 1e-3f)
        assertEquals(20f, blockLine.xy[3] - blockLine.xy[1], 1e-3f)
    }

    @Test
    fun layerVisibilityComesFromTheHeader() {
        val d = Jww.parse(sample())
        val hidden = d.layers.filter { !it.visible }.map { it.name }
        assertEquals(listOf("0-3"), hidden)
    }

    @Test
    fun penColorsAreAppliedAndOthersFollowTheTheme() {
        val d = Jww.parse(sample())
        // ヘッダーに色の表が無い場合は「自動色」(0)
        assertTrue(d.strokes.all { it.color == 0 })
    }

    @Test
    fun truncatedFileKeepsWhatWasRead() {
        val full = sample()
        // ブロックの定義の途中で切る
        val cut = full.copyOf(full.size - 20)
        val d = Jww.parse(cut)
        assertTrue(d.shapeCount >= 4)
    }

    @Test
    fun notJwwIsRefused() {
        try {
            Jww.parse("hello".toByteArray())
            fail()
        } catch (e: OpException) {
            assertTrue(e.message!!.contains("JW_CAD"))
        }
    }

    @Test
    fun utf8NamesAreDetected() {
        assertEquals("日本語", Jww.decode("日本語".toByteArray(Charsets.UTF_8)))
        assertEquals("日本語", Jww.decode("日本語".toByteArray(charset("windows-31j"))))
        assertEquals("abc", Jww.decode("abc".toByteArray()))
    }
}

class JwcTest {
    /** 用紙 A3 に、線 1 本・円 1 つ・文字 1 つを入れた最小の .jwc */
    private fun sample(u16Scales: Boolean = true): ByteArray {
        val header = if (u16Scales) 2389 else 2421
        val lines = 1; val arcs = 1; val texts = 1
        val pool = "ABC日本\u0000".toByteArray(charset("windows-31j"))
        val size = header + lines * 22 + arcs * 32 + texts * 24 + pool.size + 2304
        val b = java.nio.ByteBuffer.allocate(size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        fun put(o: Int, s: String) { for (i in s.indices) b.put(o + i, s[i].code.toByte()) }
        put(0, "jw_cad(c)data.......a." + (if (u16Scales) "." else "f") + ".m...............")
        for (o in intArrayOf(199, 399, 599, 799)) b.put(o, '\n'.code.toByte())
        put(200, "1,1,1,0,0,3,2,1,1,1,0,3,2,1,0,0,5,10,19,1,2,3,4,5,6,5,5,259,182.932,0,518,0")
        put(600, "4000:0000,4000:%04X,0,0,1".format(pool.size))
        // 文字の大きさ(プリセット 1: 幅 20・高さ 40 = 2.0mm・4.0mm)
        b.putShort(1709 + 2, 1); b.putShort(1709 + 22 + 2, 20); b.putShort(1709 + 44 + 2, 40)
        var o = header
        // 線: (0,0)-(518,0)  色 2、層 0-0(パックされた層 0x00)
        b.putFloat(o, 0f); b.putFloat(o + 4, 0f); b.putFloat(o + 8, 518f); b.putFloat(o + 12, 0f)
        b.put(o + 16, 1); b.put(o + 17, 2); b.put(o + 18, 0x00)
        o += 22
        // 円: 中心 (259, 182) 半径 50 扁平率 1、角度 0/0 = 全周
        b.putFloat(o, 259f); b.putFloat(o + 4, 182f); b.putFloat(o + 8, 50f); b.putShort(o + 12, 10000.toShort())
        b.put(o + 26, 1); b.put(o + 27, 3); b.put(o + 28, 0x00)
        o += 32
        // 文字: 基線 (100,100)-(160,100)、プリセット 1、文字は 4000:0000
        b.putFloat(o, 100f); b.putFloat(o + 4, 100f); b.putFloat(o + 8, 160f); b.putFloat(o + 12, 100f)
        b.putInt(o + 16, 0x40000000); b.put(o + 20, 1); b.put(o + 21, 0x00)
        o += 24
        for (i in pool.indices) b.put(o + i, pool[i])
        return b.array()
    }

    @Test
    fun readsPaperCoordinatesAsMillimetres() {
        val d = Jwc.parse(sample())
        assertEquals(3, d.shapeCount)
        val line = d.strokes.first { it.lens[0] == 2 }
        // A3(420 × 297): 518 → 420mm。線の長さは 420mm
        assertEquals(420f, kotlin.math.abs(line.xy[2] - line.xy[0]), 0.01f)
        val t = d.texts.single()
        assertEquals("ABC日本", t.text)
        assertEquals(4.0f, t.height, 1e-4f)
    }

    @Test
    fun bothHeaderProfilesAreRead() {
        assertEquals(3, Jwc.parse(sample(false)).shapeCount)
    }

    @Test
    fun truncatedFileIsRefused() {
        try {
            Jwc.parse(sample().copyOf(2450))
            fail()
        } catch (e: OpException) {
            assertTrue(e.message!!.contains("途中"))
        }
    }
}

/** ezjww の .jwc 見本(FILER_JWC_SAMPLES)を読む */
class JwcSampleTest {
    @Test
    fun readsSamples() {
        val dir = System.getenv("FILER_JWC_SAMPLES")?.let { File(it) }?.takeIf { it.isDirectory }
        assumeTrue(dir != null)
        val report = StringBuilder()
        for (f in dir!!.listFiles { x -> x.extension == "jwc" }!!.sortedBy { it.name }) {
            try {
                val d = Jwc.parse(f.readBytes())
                report.append("${f.name}: shapes=${d.shapeCount} texts=${d.texts.size} size=${d.maxX - d.minX}x${d.maxY - d.minY}\n")
                CadPng.render(d, File(dir, "png/${f.nameWithoutExtension}.png"), 800, 560)
            } catch (e: OpException) {
                report.append("${f.name}: ${e.message}\n")
            }
        }
        File(dir, "report.txt").writeText(report.toString())
    }
}
/** ezdxf の実物の .dxf 見本(FILER_DXF_SAMPLES)を読んで、概要と絵を書き出す */
class DxfSampleTest {
    @Test
    fun readsEveryRealSample() {
        val dir = System.getenv("FILER_DXF_SAMPLES")?.let { File(it) }?.takeIf { it.isDirectory }
        assumeTrue(dir != null)
        val report = StringBuilder()
        var failed = 0
        for (f in dir!!.listFiles { x -> x.extension.lowercase() == "dxf" }!!.sortedBy { it.name.lowercase() }) {
            try {
                val t0 = System.nanoTime()
                val d = Dxf.parse(f.readBytes())
                val ms = (System.nanoTime() - t0) / 1_000_000
                report.append("${f.name}: shapes=${d.shapeCount} layers=${d.layers.count { it.count > 0 }} texts=${d.texts.size} fills=${d.fills.size} size=${"%.1f".format(d.maxX - d.minX)}x${"%.1f".format(d.maxY - d.minY)} ${ms}ms note='${d.note}'\n")
                d.texts.take(2).forEach { report.append("   text: '${it.text.replace('\n', '|')}' h=${it.height}\n") }
                CadPng.render(d, File(dir, "png/${f.nameWithoutExtension}.png"), 1200, 800)
            } catch (e: OpException) {
                report.append("${f.name}: 読めない(想定内): ${e.message}\n")
            } catch (e: Exception) {
                failed++
                report.append("${f.name}: FAIL ${e::class.simpleName}: ${e.message}\n")
            }
        }
        File(dir, "report.txt").writeText(report.toString())
        assertEquals(report.toString(), 0, failed)
    }
}
/** 公開されている実物の 3D ファイル(FILER_MESH_SAMPLES)を読んで、概要と正面からの絵を書き出す */
class MeshSampleTest {
    @Test
    fun readsEveryRealSample() {
        val dir = System.getenv("FILER_MESH_SAMPLES")?.let { File(it) }?.takeIf { it.isDirectory }
        assumeTrue(dir != null)
        val report = StringBuilder()
        var failed = 0
        for (f in dir!!.listFiles { x -> MeshFormats.supports(x.extension.lowercase()) }!!.sortedBy { it.name }) {
            try {
                val t0 = System.nanoTime()
                val m = f.inputStream().use { MeshFormats.parse(f.extension.lowercase(), it, f.length()) }
                val ms = (System.nanoTime() - t0) / 1_000_000
                report.append("${f.name}: tris=${m.triangles} points=${m.points} colors=${m.colors != null} size=${"%.1f".format(m.sizeX)}x${"%.1f".format(m.sizeY)}x${"%.1f".format(m.sizeZ)} ${ms}ms\n")
                // 正面(x と z)から見た三角形の辺
                val b = CadBuilder(maxShapes = 10_000_000)
                val l = b.layer("m")
                val v = m.vertices
                val step = maxOf(1, m.triangles / 60000)
                var t = 0
                while (t < m.triangles) {
                    val pts = doubleArrayOf(v.get(t * 9).toDouble(), v.get(t * 9 + 2).toDouble(), v.get(t * 9 + 3).toDouble(), v.get(t * 9 + 5).toDouble(), v.get(t * 9 + 6).toDouble(), v.get(t * 9 + 8).toDouble())
                    b.poly(l, 0xFF80B0E0.toInt(), pts, 3, true)
                    t += step
                }
                CadPng.render(b.build(), File(dir, "png/${f.nameWithoutExtension}.png"), 700, 700)
            } catch (e: Exception) {
                failed++
                report.append("${f.name}: FAIL ${e::class.simpleName}: ${e.message}\n")
            }
        }
        File(dir, "report.txt").writeText(report.toString())
        assertEquals(report.toString(), 0, failed)
    }
}
