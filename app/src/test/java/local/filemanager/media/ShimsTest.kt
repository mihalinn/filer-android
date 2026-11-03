package local.filemanager

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

private class Mem(val data: ByteArray) : RandomSource {
    override val size: Long get() = data.size.toLong()
    override fun readAt(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos >= data.size) return -1
        val n = minOf(len, (data.size - pos).toInt())
        System.arraycopy(data, pos.toInt(), buf, off, n)
        return n
    }

    override fun close() {}
}

private fun ShimReader.readAll(chunk: Int = 1000): ByteArray {
    val o = ByteArrayOutputStream()
    val b = ByteArray(chunk)
    while (true) {
        val n = read(b, 0, b.size)
        if (n < 0) break
        o.write(b, 0, n)
    }
    return o.toByteArray()
}

class BdavTest {
    /** 4 バイトの時刻 + 188 バイトの TS パケット(0x47 から始まり、中身は番号で埋める)× n */
    private fun bdav(n: Int): ByteArray {
        val o = ByteArrayOutputStream()
        for (k in 0 until n) {
            o.write(byteArrayOf(0x00, 0x00, (k shr 8).toByte(), k.toByte()))
            o.write(0x47)
            for (i in 1 until 188) o.write((k * 7 + i) and 0xFF)
        }
        return o.toByteArray()
    }

    private fun expected(n: Int): ByteArray {
        val o = ByteArrayOutputStream()
        for (k in 0 until n) {
            o.write(0x47)
            for (i in 1 until 188) o.write((k * 7 + i) and 0xFF)
        }
        return o.toByteArray()
    }

    @Test
    fun 見分け() {
        assertNotNull(Bdav.detect(Mem(bdav(10))))
        // 普通の TS(先頭から 188 バイトおき)は対象外
        assertNull(Bdav.detect(Mem(expected(10))))
        assertNull(Bdav.detect(Mem(ByteArray(5000))))
        assertNull(Bdav.detect(Mem(ByteArray(100))))
        assertTrue(Shims.needed("a.M2TS"))
        assertTrue(Shims.needed("x.mts"))
        assertTrue(Shims.needed("a.AIFF"))
        assertFalse(Shims.needed("a.ts"))
        assertFalse(Shims.needed("a.mp4"))
    }

    @Test
    fun 時刻の4バイトを取り除いて_188バイト単位にする() {
        val n = 5000 // 1 回に詰め替える数(2048 パケット)をまたぐ
        val shim = Bdav.detect(Mem(bdav(n)))!!
        assertEquals(n * 188L, shim.size)
        assertArrayEquals(expected(n), shim.reader(0, shim.size).readAll(777))
    }

    @Test
    fun 途中から_途中まで読める() {
        val n = 3000
        val shim = Bdav.detect(Mem(bdav(n)))!!
        val all = expected(n)
        val start = 188L * 2047 + 100 // 詰め替えの境目の近く
        val len = 188L * 5 + 33
        assertArrayEquals(all.copyOfRange(start.toInt(), (start + len).toInt()), shim.reader(start, len).readAll(50))
        assertArrayEquals(all.copyOfRange(all.size - 10, all.size), shim.reader(all.size - 10L, 1000).readAll())
        assertEquals(0, shim.reader(shim.size, 10).readAll().size)
    }

    @Test
    fun 端数のバイトは無視する() {
        val data = bdav(4) + ByteArray(50)
        val shim = Bdav.detect(Mem(data))!!
        assertEquals(4 * 188L, shim.size)
        assertArrayEquals(expected(4), shim.reader(0, shim.size).readAll())
    }
}

class AiffTest {
    private fun be32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun be16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    /** 44100 Hz の 80 ビット浮動小数(指数 0x400E、仮数 0xAC44000000000000) */
    private val rate44100 = byteArrayOf(0x40, 0x0E, 0xAC.toByte(), 0x44, 0, 0, 0, 0, 0, 0)

    private fun aiff(samples: ShortArray, channels: Int = 1, aifc: String? = null): ByteArray {
        val pcm = ByteArrayOutputStream()
        for (s in samples) pcm.write(be16(s.toInt()))
        val comm = ByteArrayOutputStream()
        comm.write(be16(channels)); comm.write(be32(samples.size / channels)); comm.write(be16(16)); comm.write(rate44100)
        if (aifc != null) { comm.write(aifc.toByteArray()); comm.write(byteArrayOf(0, 0)) }
        val ssnd = ByteArrayOutputStream()
        ssnd.write(be32(0)); ssnd.write(be32(0)); ssnd.write(pcm.toByteArray())
        val body = ByteArrayOutputStream()
        body.write((if (aifc != null) "AIFC" else "AIFF").toByteArray())
        body.write("COMM".toByteArray()); body.write(be32(comm.size())); body.write(comm.toByteArray())
        body.write("SSND".toByteArray()); body.write(be32(ssnd.size())); body.write(ssnd.toByteArray())
        val f = ByteArrayOutputStream()
        f.write("FORM".toByteArray()); f.write(be32(body.size())); f.write(body.toByteArray())
        return f.toByteArray()
    }

    @Test
    fun WAVのように見せる() {
        val samples = ShortArray(3000) { ((it * 37) - 1500).toShort() }
        val shim = AiffWav.detect(Mem(aiff(samples)))!!
        assertEquals(44L + 6000, shim.size)
        val wav = shim.reader(0, shim.size).readAll(333)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("data", String(wav, 36, 4))
        assertEquals(1, wav[22].toInt()) // チャンネル
        assertEquals(44100, (wav[24].toInt() and 0xFF) or ((wav[25].toInt() and 0xFF) shl 8) or ((wav[26].toInt() and 0xFF) shl 16))
        assertEquals(16, wav[34].toInt())
        assertEquals(6000, (wav[40].toInt() and 0xFF) or ((wav[41].toInt() and 0xFF) shl 8))
        // 下の桁から並んだ 16 ビットになっている
        for (i in samples.indices) {
            val v = (wav[44 + i * 2].toInt() and 0xFF) or (wav[45 + i * 2].toInt() shl 8)
            assertEquals(samples[i].toInt(), v.toShort().toInt())
        }
    }

    @Test
    fun 途中からも正しく読める() {
        val samples = ShortArray(200_000) { (it % 30000).toShort() }
        val shim = AiffWav.detect(Mem(aiff(samples, 2)))!!
        val whole = shim.reader(0, shim.size).readAll(10_000)
        assertEquals(shim.size, whole.size.toLong())
        // 奇数の位置から(サンプルの途中から)読んでも、同じ並びになる
        for (start in listOf(45L, 46L, 100_001L, 262_145L, shim.size - 7)) {
            val part = shim.reader(start, 5000).readAll(97)
            assertArrayEquals("start=$start", whole.copyOfRange(start.toInt(), minOf(whole.size, start.toInt() + 5000)), part)
        }
    }

    @Test
    fun AIFCの_sowtは並べ替えない() {
        val samples = shortArrayOf(1, 2, 3)
        val big = aiff(samples, 1, "sowt")
        // 中身を、下の桁から並んだ形に書き換える
        val shim = AiffWav.detect(Mem(big))
        assertNotNull(shim)
        assertNull(AiffWav.detect(Mem(aiff(samples, 1, "ulaw"))))
        assertNotNull(AiffWav.detect(Mem(aiff(samples, 1, "NONE"))))
    }

    @Test
    fun AIFFでないものは対象外() {
        assertNull(AiffWav.detect(Mem(ByteArray(100))))
        assertNull(AiffWav.detect(Mem("RIFF....WAVEfmt ".toByteArray() + ByteArray(40))))
    }
}

class MidiNameTest {
    @Test
    fun MIDIの名前() {
        assertTrue(isMidiName("a.MID"))
        assertTrue(isMidiName("曲.midi"))
        assertTrue(isMidiName("karaoke.kar"))
        assertFalse(isMidiName("a.mp3"))
    }
}

/** 環境変数 FILER_SHIM_IN(入力)と FILER_SHIM_OUT(出力)があるときだけ、実物のファイルを変換して書き出す(ffprobe で確かめる用)。 */
class ShimSampleTest {
    @Test
    fun 実物を変換する() {
        val input = System.getenv("FILER_SHIM_IN")
        val output = System.getenv("FILER_SHIM_OUT")
        org.junit.Assume.assumeTrue(input != null && output != null)
        val f = java.io.File(input!!)
        val src = FileRandomSource(f)
        val shim = Shims.detect(f.name, src)
        assertNotNull(shim)
        java.io.File(output!!).outputStream().use { o -> o.write(shim!!.reader(0, shim.size).readAll(64 * 1024)) }
        println("converted ${f.name}: ${f.length()} -> ${shim!!.size}")
    }
}
