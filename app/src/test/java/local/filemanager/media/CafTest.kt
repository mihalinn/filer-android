package local.filemanager

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

private fun cafRes(name: String): ByteArray = object {}.javaClass.getResourceAsStream("/apple/$name")!!.use { it.readBytes() }

private class MemSrc(val data: ByteArray) : RandomSource {
    override val size: Long get() = data.size.toLong()
    override fun readAt(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos >= data.size) return -1
        val n = minOf(len.toLong(), data.size - pos).toInt()
        System.arraycopy(data, pos.toInt(), buf, off, n)
        return n
    }
    override fun close() {}
}

private fun Shim.bytes(): ByteArray {
    val out = ByteArrayOutputStream()
    val r = reader(0, size)
    val buf = ByteArray(777)
    while (true) {
        val n = r.read(buf, 0, buf.size)
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/** 環境変数 FILER_CAF があれば、作った中身をそこへ書き出す(ffmpeg で実際に読めるか確かめるため)。 */
private fun dump(name: String, b: ByteArray) {
    System.getenv("FILER_CAF")?.let { d -> java.io.File(d, "shim").also { it.mkdirs() }.let { java.io.File(it, name).writeBytes(b) } }
}

private fun be32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
private fun be64(v: Long) = be32((v shr 32).toInt()) + be32(v.toInt())
private fun rd32(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
private fun vlq(v: Int): ByteArray {
    val parts = ArrayList<Int>()
    var x = v
    do { parts.add(x and 0x7F); x = x shr 7 } while (x > 0)
    return ByteArray(parts.size) { i -> (parts[parts.size - 1 - i] or (if (i < parts.size - 1) 0x80 else 0)).toByte() }
}

/** CAF を組み立てる。pakt は data の後ろに置く(ffmpeg と同じ)。 */
private fun caf(
    format: String, rate: Double, channels: Int, flags: Int = 0, bytesPerPacket: Int = 0, framesPerPacket: Int = 0, bits: Int = 0,
    data: ByteArray, sizes: IntArray? = null, kuki: ByteArray? = null, packetFrames: IntArray? = null,
): ByteArray {
    val o = ByteArrayOutputStream()
    o.write("caff".toByteArray()); o.write(byteArrayOf(0, 1, 0, 0))
    fun chunk(type: String, body: ByteArray) { o.write(type.toByteArray()); o.write(be64(body.size.toLong())); o.write(body) }
    chunk("desc", be64(java.lang.Double.doubleToLongBits(rate)) + format.toByteArray() + be32(flags) + be32(bytesPerPacket) + be32(framesPerPacket) + be32(channels) + be32(bits))
    if (kuki != null) chunk("kuki", kuki)
    chunk("data", be32(0) + data)
    if (sizes != null) {
        val t = ByteArrayOutputStream()
        for ((i, s) in sizes.withIndex()) {
            t.write(vlq(s))
            if (framesPerPacket == 0) t.write(vlq(packetFrames!![i]))
        }
        chunk("pakt", be64(sizes.size.toLong()) + be64(sizes.size.toLong() * maxOf(1, framesPerPacket)) + be32(0) + be32(0) + t.toByteArray())
    }
    return o.toByteArray()
}

class CafPcmTest {
    @Test
    fun bigEndian16BecomesLittleEndianWav() {
        val pcm = byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x00, 0x01, 0x7F, 0xFF.toByte())
        val shim = Caf.detect(MemSrc(caf("lpcm", 44100.0, 2, flags = 0, bytesPerPacket = 4, framesPerPacket = 1, bits = 16, data = pcm)))!!
        val b = shim.bytes()
        assertEquals("RIFF", String(b, 0, 4))
        assertEquals(44 + 8, b.size)
        assertEquals(1, b[20].toInt()) // 整数 PCM
        assertEquals(2, b[22].toInt())
        assertEquals(44100, (b[24].toInt() and 0xFF) or ((b[25].toInt() and 0xFF) shl 8))
        assertArrayEquals(byteArrayOf(0x34, 0x12, 0x78, 0x56, 0x01, 0x00, 0xFF.toByte(), 0x7F), b.copyOfRange(44, 52))
    }

    @Test
    fun littleEndianFloatKeepsBytesAndMarksFloat() {
        val pcm = byteArrayOf(0, 0, 0x80.toByte(), 0x3F, 0, 0, 0, 0xBF.toByte())
        val b = Caf.detect(MemSrc(caf("lpcm", 48000.0, 1, flags = 3, bytesPerPacket = 4, framesPerPacket = 1, bits = 32, data = pcm)))!!.bytes()
        assertEquals(3, b[20].toInt())
        assertArrayEquals(pcm, b.copyOfRange(44, 52))
    }

    @Test
    fun threeByteBigEndianAndRandomAccess() {
        val pcm = ByteArray(30) { it.toByte() }
        val shim = Caf.detect(MemSrc(caf("lpcm", 22050.0, 1, flags = 0, bytesPerPacket = 3, framesPerPacket = 1, bits = 24, data = pcm)))!!
        val all = shim.bytes()
        val expected = ByteArray(30)
        for (i in 0 until 10) { expected[i * 3] = pcm[i * 3 + 2]; expected[i * 3 + 1] = pcm[i * 3 + 1]; expected[i * 3 + 2] = pcm[i * 3] }
        assertArrayEquals(expected, all.copyOfRange(44, 74))
        // 途中から読んでも同じ
        val r = shim.reader(50, 10)
        val buf = ByteArray(10)
        var n = 0
        while (n < 10) { val k = r.read(buf, n, 10 - n); if (k < 0) break; n += k }
        assertArrayEquals(all.copyOfRange(50, 60), buf)
    }

    @Test
    fun notCaf() {
        assertNull(Caf.detect(MemSrc("hello world, this is not an audio file at all....".toByteArray())))
        assertNull(Caf.detect(MemSrc("caff".toByteArray() + ByteArray(100))))
    }
}

/** Ogg を、CRC を確かめながらパケットに分ける。 */
private fun oggPackets(b: ByteArray): List<ByteArray> {
    val out = ArrayList<ByteArray>()
    val partial = ByteArrayOutputStream()
    var p = 0
    while (p + 27 <= b.size) {
        assertEquals("OggS", String(b, p, 4))
        val nseg = b[p + 26].toInt() and 0xFF
        val segs = IntArray(nseg) { b[p + 27 + it].toInt() and 0xFF }
        val bodyLen = segs.sum()
        val total = 27 + nseg + bodyLen
        // CRC
        val page = b.copyOfRange(p, p + total)
        val stored = (page[22].toInt() and 0xFF) or ((page[23].toInt() and 0xFF) shl 8) or ((page[24].toInt() and 0xFF) shl 16) or ((page[25].toInt() and 0xFF) shl 24)
        for (i in 22..25) page[i] = 0
        var c = 0
        for (x in page) {
            c = c xor ((x.toInt() and 0xFF) shl 24)
            repeat(8) { c = if ((c and 0x80000000.toInt()) != 0) (c shl 1) xor 0x04C11DB7 else c shl 1 }
        }
        assertEquals("ページの CRC", stored, c)
        var q = p + 27 + nseg
        for (s in segs) {
            partial.write(b, q, s)
            q += s
            if (s < 255) { out.add(partial.toByteArray()); partial.reset() }
        }
        p += total
    }
    assertEquals(b.size, p)
    return out
}

private fun lastGranule(b: ByteArray): Long {
    var p = 0
    var g = 0L
    while (p + 27 <= b.size) {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[p + 6 + i].toLong() and 0xFF)
        g = v
        val nseg = b[p + 26].toInt() and 0xFF
        p += 27 + nseg + (0 until nseg).sumOf { b[p + 27 + it].toInt() and 0xFF }
    }
    return g
}

class CafOpusTest {
    @Test
    fun opusPacketsMoveIntoOgg() {
        val ogg = cafRes("opus.ogg")
        val all = oggPackets(ogg)
        assertEquals("OpusHead", String(all[0], 0, 8))
        assertEquals("OpusTags", String(all[1], 0, 8))
        val audio = all.drop(2)
        val data = ByteArrayOutputStream().also { o -> audio.forEach { o.write(it) } }.toByteArray()
        val c = caf("opus", 48000.0, 2, framesPerPacket = 960, data = data, sizes = IntArray(audio.size) { audio[it].size })
        dump("opus_in.caf", c)
        val out = Caf.detect(MemSrc(c))!!.bytes()
        dump("opus_out.ogg", out)
        val back = oggPackets(out) // CRC も確かめる
        assertEquals("OpusHead", String(back[0], 0, 8))
        assertEquals(2, back[0][9].toInt())
        assertEquals(48000, rd32(byteArrayOf(back[0][15], back[0][14], back[0][13], back[0][12]), 0))
        assertEquals("OpusTags", String(back[1], 0, 8))
        val backAudio = back.drop(2)
        assertEquals(audio.size, backAudio.size)
        for (i in audio.indices) assertArrayEquals(audio[i], backAudio[i])
        // 最後のページの位置は、全パケットのサンプル数の合計
        val expected = audio.sumOf { OggOpus.samples(it, 0, it.size).toLong() }
        assertEquals(expected, lastGranule(out))
        assertTrue(expected in 90000L..100000L)
    }

    @Test
    fun samplesFromToc() {
        // config 31(CELT 20ms)・1 フレーム = 960、config 31 で 2 フレーム(code 1)= 1920、code 3 で 3 フレーム = 2880
        assertEquals(960, OggOpus.samples(byteArrayOf((31 shl 3).toByte()), 0, 1))
        assertEquals(1920, OggOpus.samples(byteArrayOf(((31 shl 3) or 1).toByte()), 0, 1))
        assertEquals(2880, OggOpus.samples(byteArrayOf(((31 shl 3) or 3).toByte(), 3), 0, 2))
        assertEquals(480, OggOpus.samples(byteArrayOf((0 shl 3).toByte()), 0, 1)) // SILK 10ms
        assertEquals(0, OggOpus.samples(ByteArray(0), 0, 0))
    }
}

private class Mp4Info(val sizes: IntArray, val payload: ByteArray, val esds: ByteArray?, val entryType: String, val alac: ByteArray?, val timescale: Int)

private fun parseMp4(b: ByteArray): Mp4Info {
    fun find(type: String, from: Int, to: Int): Int {
        var p = from
        while (p + 8 <= to) {
            val size = rd32(b, p)
            if (size < 8) break
            if (String(b, p + 4, 4) == type) return p
            p += size
        }
        return -1
    }
    val moov = find("moov", 0, b.size)
    assertTrue(moov >= 0)
    fun enter(parent: Int, type: String): Int = find(type, parent + 8, parent + rd32(b, parent)).also { assertTrue("$type が無い", it >= 0) }
    val trak = enter(moov, "trak")
    val mdia = enter(trak, "mdia")
    val mdhd = enter(mdia, "mdhd")
    val minf = enter(mdia, "minf")
    val stbl = enter(minf, "stbl")
    val stsz = enter(stbl, "stsz")
    val count = rd32(b, stsz + 16)
    val sizes = IntArray(count) { rd32(b, stsz + 20 + it * 4) }
    val stco = enter(stbl, "stco")
    val offset = rd32(b, stco + 16)
    val stsd = enter(stbl, "stsd")
    val entry = stsd + 16
    val entryType = String(b, entry + 4, 4)
    val entrySize = rd32(b, entry)
    val childStart = entry + 8 + 28
    var esds: ByteArray? = null
    var alac: ByteArray? = null
    var p = childStart
    while (p + 8 <= entry + entrySize) {
        val sz = rd32(b, p)
        val t = String(b, p + 4, 4)
        if (t == "esds") esds = b.copyOfRange(p + 12, p + sz)
        if (t == "alac") alac = b.copyOfRange(p, p + sz)
        p += sz
    }
    val mdat = find("mdat", 0, b.size)
    assertEquals(offset, mdat + 8)
    return Mp4Info(sizes, b.copyOfRange(offset, offset + sizes.sum()), esds, entryType, alac, rd32(b, mdhd + 20))
}

class CafMp4Test {
    @Test
    fun alacMovesIntoM4a() {
        val src = cafRes("alac.caf")
        val shim = Caf.detect(MemSrc(src))!!
        val out = shim.bytes()
        assertEquals("ftyp", String(out, 4, 4))
        val info = parseMp4(out)
        assertEquals("alac", info.entryType)
        assertEquals(44100, info.timescale)
        assertNotNull(info.alac)
        assertEquals(36, info.alac!!.size)
        // 元の CAF の data チャンクの先頭と、取り出した中身が同じ
        val dataPos = String(src, Charsets.ISO_8859_1).indexOf("data") + 4 + 8 + 4
        assertArrayEquals(src.copyOfRange(dataPos, dataPos + 5000), info.payload.copyOf(5000))
        // 3 秒・4096 フレームごと
        assertTrue(info.sizes.size in 30..34)
        assertEquals(info.payload.size, info.sizes.sum())
    }

    @Test
    fun aacMovesIntoM4aWithTheSameDescriptor() {
        val m4a = cafRes("aac.m4a")
        val src = parseMp4(m4a)
        assertEquals("mp4a", src.entryType)
        val kuki = src.esds!!
        val c = caf("aac ", 44100.0, 2, framesPerPacket = 1024, data = src.payload, sizes = src.sizes, kuki = kuki)
        dump("aac_in.caf", c)
        val out = Caf.detect(MemSrc(c))!!.bytes()
        dump("aac_out.m4a", out)
        val back = parseMp4(out)
        assertEquals("mp4a", back.entryType)
        assertArrayEquals(src.sizes, back.sizes)
        assertArrayEquals(src.payload, back.payload)
        assertArrayEquals(kuki, back.esds)
        // 設定が無いときは、AAC-LC の記述子を作る
        val c2 = caf("aac ", 44100.0, 2, framesPerPacket = 1024, data = src.payload, sizes = src.sizes)
        val back2 = parseMp4(Caf.detect(MemSrc(c2))!!.bytes())
        assertEquals(0x03, back2.esds!![0].toInt())
        assertTrue(back2.esds!!.size > 15)
    }

    @Test
    fun variableFramesAndUnknownFormats() {
        val data = ByteArray(300) { it.toByte() }
        val c = caf("aac ", 48000.0, 1, data = data, sizes = intArrayOf(100, 120, 80), packetFrames = intArrayOf(1024, 1024, 700))
        val info = parseMp4(Caf.detect(MemSrc(c))!!.bytes())
        assertArrayEquals(intArrayOf(100, 120, 80), info.sizes)
        // 未対応の形式(ima4 など)は null
        assertNull(Caf.detect(MemSrc(caf("ima4", 44100.0, 1, bytesPerPacket = 34, framesPerPacket = 64, data = ByteArray(340)))))
        // パケット表が無い・壊れている
        assertNull(Caf.detect(MemSrc(caf("alac", 44100.0, 2, framesPerPacket = 4096, data = ByteArray(100), kuki = ByteArray(24)))))
    }
}
