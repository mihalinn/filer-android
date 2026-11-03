package local.filemanager

import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * CAF(Core Audio Format。iPhone / Mac の音声)を、再生エンジンが読める形に作り直して渡す。
 *  - リニア PCM(.caf の多く): WAV に見せかける(AIFF と同じ仕組み。大きなファイルでも順に読む)
 *  - ALAC / AAC: 同じ圧縮データを MP4(M4A)の入れ物に入れ替える(メモリ上に作る)
 *  - Opus(iMessage の音声メッセージ): Ogg の入れ物に入れ替える(メモリ上に作る)
 * Android に依存しない。
 */
object Caf {
    private const val MEMORY_LIMIT = 192L * 1024 * 1024

    class Desc(
        val sampleRate: Double, val formatId: String, val flags: Int, val bytesPerPacket: Int, val framesPerPacket: Int, val channels: Int, val bits: Int,
    )

    private class Chunk(val type: String, val pos: Long, val size: Long)

    private fun be32(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
    private fun be64(b: ByteArray, o: Int): Long = (be32(b, o).toLong() shl 32) or (be32(b, o + 4).toLong() and 0xFFFFFFFFL)

    fun detect(src: RandomSource): Shim? {
        if (src.size < 8 + 12 + 32) return null
        val head = ByteArray(8)
        if (Shims.readFully(src, 0, head, 8) < 8 || String(head, 0, 4, Charsets.ISO_8859_1) != "caff") return null
        // チャンクの並びを、見出しだけたどる(データ本体は飛ばす。pakt は data の後ろにあることもある)
        val chunks = ArrayList<Chunk>()
        var p = 8L
        val h = ByteArray(12)
        while (p + 12 <= src.size && chunks.size < 200) {
            if (Shims.readFully(src, p, h, 12) < 12) break
            val type = String(h, 0, 4, Charsets.ISO_8859_1)
            var size = be64(h, 4)
            if (size < 0) size = src.size - p - 12 // data の大きさが -1 のとき(最後まで)
            chunks.add(Chunk(type, p + 12, size))
            p += 12 + size
        }
        val descChunk = chunks.firstOrNull { it.type == "desc" && it.size >= 32 } ?: return null
        val dataChunk = chunks.firstOrNull { it.type == "data" } ?: return null
        val d = ByteArray(32)
        Shims.readFully(src, descChunk.pos, d, 32)
        val desc = Desc(
            java.lang.Double.longBitsToDouble(be64(d, 0)), String(d, 8, 4, Charsets.ISO_8859_1), be32(d, 12), be32(d, 16), be32(d, 20), be32(d, 24), be32(d, 28),
        )
        if (desc.channels !in 1..8 || desc.sampleRate < 1000 || desc.sampleRate > 400000) return null
        val dataStart = dataChunk.pos + 4 // 先頭の 4 バイトは「編集の回数」
        val dataLen = (dataChunk.size - 4).coerceAtLeast(0)
        if (dataLen <= 0) return null
        fun chunkBytes(type: String, limit: Long = 64L * 1024 * 1024): ByteArray? {
            val c = chunks.firstOrNull { it.type == type } ?: return null
            if (c.size > limit) return null
            val b = ByteArray(c.size.toInt())
            return if (Shims.readFully(src, c.pos, b, b.size) == b.size) b else null
        }
        return when (desc.formatId) {
            "lpcm" -> pcm(src, desc, dataStart, dataLen)
            "alac", "aac " -> {
                if (dataLen > MEMORY_LIMIT) return null
                val packets = packets(desc, dataLen, chunkBytes("pakt")) ?: return null
                val data = readAll(src, dataStart, dataLen)
                Mp4Audio.build(desc, packets, data, chunkBytes("kuki"))?.let { MemoryShim(it) }
            }
            "opus" -> {
                if (dataLen > MEMORY_LIMIT) return null
                val packets = packets(desc, dataLen, chunkBytes("pakt")) ?: return null
                val data = readAll(src, dataStart, dataLen)
                OggOpus.build(desc, packets, data, chunkBytes("kuki"))?.let { MemoryShim(it) }
            }
            else -> null
        }
    }

    private fun pcm(src: RandomSource, d: Desc, dataStart: Long, dataLen: Long): Shim? {
        val bits = d.bits
        if (bits !in intArrayOf(8, 16, 24, 32)) return null
        val float = (d.flags and 1) != 0
        if (float && bits != 32) return null
        val little = (d.flags and 2) != 0
        return AiffWav.forPcm(src, d.channels, bits, d.sampleRate.toInt(), dataStart, dataLen, !little, float)
    }

    private fun readAll(src: RandomSource, pos: Long, len: Long): ByteArray {
        val b = ByteArray(len.toInt())
        val got = Shims.readFully(src, pos, b, b.size)
        if (got < b.size) throw IOException("ファイルが途中までしか読めませんでした")
        return b
    }

    /** 1 パケットずつの大きさ(バイト)と長さ(フレーム数)。 */
    class Packets(val sizes: IntArray, val frames: IntArray)

    /** pakt チャンク(可変長の整数の並び)を読む。大きさが一定の形式(bytesPerPacket != 0)は、データの長さから数える。 */
    private fun packets(d: Desc, dataLen: Long, pakt: ByteArray?): Packets? {
        if (d.bytesPerPacket > 0) {
            val n = (dataLen / d.bytesPerPacket).toInt()
            if (n <= 0) return null
            val f = if (d.framesPerPacket > 0) d.framesPerPacket else 1
            return Packets(IntArray(n) { d.bytesPerPacket }, IntArray(n) { f })
        }
        pakt ?: return null
        if (pakt.size < 24) return null
        val count = be64(pakt, 0)
        if (count <= 0 || count > 50_000_000) return null
        var p = 24
        val sizes = IntArray(count.toInt())
        val frames = IntArray(count.toInt())
        fun vlq(): Int {
            var v = 0L
            repeat(5) {
                if (p >= pakt.size) throw IOException("パケット表が途中で切れています")
                val x = pakt[p++].toInt() and 0xFF
                v = (v shl 7) or (x and 0x7F).toLong()
                if (x < 0x80) return v.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
            return v.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        try {
            for (i in sizes.indices) {
                sizes[i] = if (d.bytesPerPacket == 0) vlq() else d.bytesPerPacket
                frames[i] = if (d.framesPerPacket == 0) vlq() else d.framesPerPacket
            }
        } catch (e: IOException) {
            return null
        }
        if (sizes.sumOf { it.toLong() } > dataLen) return null
        return Packets(sizes, frames)
    }
}

/** 作り直した中身(メモリ上)を、そのままファイルのように見せる。 */
class MemoryShim(private val bytes: ByteArray) : Shim {
    override val size: Long = bytes.size.toLong()

    override fun reader(start: Long, length: Long): ShimReader = object : ShimReader {
        private var pos = start.coerceAtLeast(0L)
        private val end = minOf(size, start + length)

        override fun read(out: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pos >= end) return -1
            val n = minOf(len.toLong(), end - pos).toInt()
            System.arraycopy(bytes, pos.toInt(), out, off, n)
            pos += n
            return n
        }
    }
}

/** MP4(M4A)を作る。ALAC と AAC の圧縮データを、そのまま入れ替える。 */
internal object Mp4Audio {
    private class W {
        val o = ByteArrayOutputStream()
        fun u8(v: Int) = apply { o.write(v) }
        fun u16(v: Int) = apply { o.write(v shr 8); o.write(v) }
        fun u32(v: Long) = apply { o.write((v shr 24).toInt()); o.write((v shr 16).toInt()); o.write((v shr 8).toInt()); o.write(v.toInt()) }
        fun u32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)
        fun str(s: String) = apply { o.write(s.toByteArray(Charsets.ISO_8859_1)) }
        fun bytes(b: ByteArray) = apply { o.write(b) }
        fun zeros(n: Int) = apply { repeat(n) { o.write(0) } }
        fun toBytes(): ByteArray = o.toByteArray()
    }

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val len = 8 + parts.sumOf { it.size }
        val w = W().u32(len).str(type)
        for (p in parts) w.bytes(p)
        return w.toBytes()
    }

    private fun fullBox(type: String, version: Int, flags: Int, vararg parts: ByteArray): ByteArray =
        box(type, W().u8(version).u8(flags shr 16).u8(flags shr 8).u8(flags).toBytes(), *parts)

    fun build(d: Caf.Desc, pk: Caf.Packets, data: ByteArray, kuki: ByteArray?): ByteArray? {
        val rate = d.sampleRate.toLong()
        val n = pk.sizes.size
        val entry: ByteArray = when (d.formatId) {
            "alac" -> alacEntry(d, kuki ?: return null)
            "aac " -> aacEntry(d, kuki)
            else -> return null
        } ?: return null
        val duration = pk.frames.sumOf { it.toLong() }
        // 時間の表(同じ長さが続くところはまとめる)
        val stts = W()
        var runs = 0
        run {
            val body = W()
            var i = 0
            while (i < n) {
                var j = i
                while (j < n && pk.frames[j] == pk.frames[i]) j++
                body.u32(j - i).u32(pk.frames[i])
                runs++
                i = j
            }
            stts.u32(runs).bytes(body.toBytes())
        }
        val stsz = W().u32(0).u32(n)
        for (s in pk.sizes) stsz.u32(s)
        fun moov(chunkOffset: Long): ByteArray {
            val mvhd = fullBox(
                "mvhd", 0, 0, W().u32(0).u32(0).u32(rate).u32(duration).u32(0x00010000).u16(0x0100).zeros(10)
                    .u32(0x00010000).zeros(12).u32(0x00010000).zeros(12).u32(0x40000000).zeros(24).u32(2).toBytes(),
            )
            val tkhd = fullBox(
                "tkhd", 0, 3, W().u32(0).u32(0).u32(1).u32(0).u32(duration).zeros(8).u16(0).u16(0).u16(0x0100).u16(0)
                    .u32(0x00010000).zeros(12).u32(0x00010000).zeros(12).u32(0x40000000).u32(0).u32(0).toBytes(),
            )
            val mdhd = fullBox("mdhd", 0, 0, W().u32(0).u32(0).u32(rate).u32(duration).u16(0x55C4).u16(0).toBytes())
            val hdlr = fullBox("hdlr", 0, 0, W().u32(0).str("soun").zeros(12).str("SoundHandler").u8(0).toBytes())
            val smhd = fullBox("smhd", 0, 0, W().u16(0).u16(0).toBytes())
            val dinf = box("dinf", fullBox("dref", 0, 0, W().u32(1).bytes(fullBox("url ", 0, 1)).toBytes()))
            val stsd = fullBox("stsd", 0, 0, W().u32(1).bytes(entry).toBytes())
            val stbl = box(
                "stbl", stsd, fullBox("stts", 0, 0, stts.toBytes()),
                fullBox("stsc", 0, 0, W().u32(1).u32(1).u32(n).u32(1).toBytes()),
                fullBox("stsz", 0, 0, stsz.toBytes()),
                fullBox("stco", 0, 0, W().u32(1).u32(chunkOffset).toBytes()),
            )
            return box("moov", mvhd, box("trak", tkhd, box("mdia", mdhd, hdlr, box("minf", smhd, dinf, stbl))))
        }
        val ftyp = box("ftyp", W().str("M4A ").u32(0).str("M4A ").str("mp42").str("isom").toBytes())
        val moovSize = moov(0).size
        val offset = ftyp.size.toLong() + moovSize + 8
        val total = pk.sizes.sumOf { it.toLong() }
        if (total > data.size || total + offset > Int.MAX_VALUE - 16) return null
        val out = ByteArrayOutputStream(offset.toInt() + total.toInt())
        out.write(ftyp)
        out.write(moov(offset))
        out.write(W().u32(8 + total).str("mdat").toBytes())
        out.write(data, 0, total.toInt())
        return out.toByteArray()
    }

    private fun audioEntry(type: String, d: Caf.Desc, child: ByteArray): ByteArray {
        val rateField = if (d.sampleRate < 65536) (d.sampleRate.toLong() shl 16) else 0L
        return box(
            type,
            W().zeros(6).u16(1).u16(0).u16(0).u32(0).u16(d.channels).u16(16).u16(0).u16(0).u32(rateField).toBytes(), child,
        )
    }

    /** kuki は [frma][alac …]。'alac' の入れ物(36 バイト)を取り出す。24 バイトの設定だけのときは、入れ物をつける。 */
    private fun alacEntry(d: Caf.Desc, kuki: ByteArray): ByteArray? {
        var p = 0
        while (p + 8 <= kuki.size) {
            val size = ((kuki[p].toInt() and 0xFF) shl 24) or ((kuki[p + 1].toInt() and 0xFF) shl 16) or ((kuki[p + 2].toInt() and 0xFF) shl 8) or (kuki[p + 3].toInt() and 0xFF)
            val type = String(kuki, p + 4, 4, Charsets.ISO_8859_1)
            if (size < 8 || p + size > kuki.size) break
            if (type == "alac" && size >= 36) return audioEntry("alac", d, kuki.copyOfRange(p, p + size))
            p += size
        }
        if (kuki.size == 24) return audioEntry("alac", d, fullBox("alac", 0, 0, kuki))
        return null
    }

    /** kuki は ES 記述子(最初のバイトが 0x03)か、'esds' の入れ物。無ければ AAC-LC として作る。 */
    private fun aacEntry(d: Caf.Desc, kuki: ByteArray?): ByteArray? {
        var desc: ByteArray? = null
        if (kuki != null && kuki.isNotEmpty()) {
            if (kuki[0].toInt() == 0x03) desc = kuki
            else {
                val i = String(kuki, Charsets.ISO_8859_1).indexOf("esds")
                if (i >= 4 && i + 8 <= kuki.size) desc = kuki.copyOfRange(i + 8, kuki.size)
            }
        }
        if (desc == null) {
            val idx = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000).indexOf(d.sampleRate.toInt())
            if (idx < 0) return null
            val asc = byteArrayOf(((2 shl 3) or (idx shr 1)).toByte(), (((idx and 1) shl 7) or (d.channels shl 3)).toByte())
            val dsi = byteArrayOf(0x05, asc.size.toByte()) + asc
            val dcd = byteArrayOf(0x04, (13 + dsi.size).toByte(), 0x40, 0x15, 0, 0, 0, 0, 0, 0, 0, 0, 0) + dsi
            val sl = byteArrayOf(0x06, 0x01, 0x02)
            desc = byteArrayOf(0x03, (3 + dcd.size + sl.size).toByte(), 0, 0, 0) + dcd + sl
        }
        return audioEntry("mp4a", d, fullBox("esds", 0, 0, desc))
    }
}

/** Opus のパケットを、Ogg の入れ物に入れる(iMessage の音声メッセージ = CAF + Opus)。 */
internal object OggOpus {
    private val crcTable = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var r = i shl 24
            repeat(8) { r = if ((r and 0x80000000.toInt()) != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
            t[i] = r
        }
    }

    private fun crc(b: ByteArray): Int {
        var c = 0
        for (x in b) c = (c shl 8) xor crcTable[((c ushr 24) xor (x.toInt() and 0xFF)) and 0xFF]
        return c
    }

    /** パケットの長さ(48kHz でのサンプル数)。先頭の TOC バイトから。 */
    fun samples(p: ByteArray, off: Int, len: Int): Int {
        if (len < 1) return 0
        val toc = p[off].toInt() and 0xFF
        val config = toc shr 3
        val frameSize = when {
            config < 12 -> intArrayOf(480, 960, 1920, 2880)[config and 3]
            config < 16 -> if ((config and 1) == 0) 480 else 960
            else -> intArrayOf(120, 240, 480, 960)[config and 3]
        }
        val frames = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> if (len >= 2) p[off + 1].toInt() and 0x3F else 1
        }
        return frameSize * frames
    }

    fun build(d: Caf.Desc, pk: Caf.Packets, data: ByteArray, kuki: ByteArray?): ByteArray? {
        val out = ByteArrayOutputStream()
        var seq = 0
        val serial = 0x46494C52
        fun page(type: Int, granule: Long, segs: List<Int>, body: ByteArray) {
            val h = ByteArrayOutputStream()
            h.write("OggS".toByteArray())
            h.write(0); h.write(type)
            for (i in 0 until 8) h.write((granule shr (8 * i)).toInt())
            for (i in 0 until 4) h.write(serial shr (8 * i))
            for (i in 0 until 4) h.write(seq shr (8 * i))
            seq++
            for (i in 0 until 4) h.write(0)
            h.write(segs.size)
            for (s in segs) h.write(s)
            h.write(body)
            val bytes = h.toByteArray()
            val c = crc(bytes)
            for (i in 0 until 4) bytes[22 + i] = (c shr (8 * i)).toByte()
            out.write(bytes)
        }
        fun segmentsOf(len: Int): List<Int> {
            val l = ArrayList<Int>()
            var r = len
            while (r >= 255) { l.add(255); r -= 255 }
            l.add(r)
            return l
        }
        // 1 ページ目: OpusHead
        val head = if (kuki != null && kuki.size >= 19 && String(kuki, 0, 8, Charsets.ISO_8859_1) == "OpusHead") kuki.copyOf(19) else {
            val w = ByteArrayOutputStream()
            w.write("OpusHead".toByteArray()); w.write(1); w.write(d.channels)
            w.write(312 and 0xFF); w.write(312 shr 8)
            val rate = d.sampleRate.toInt()
            for (i in 0 until 4) w.write(rate shr (8 * i))
            w.write(0); w.write(0); w.write(0)
            w.toByteArray()
        }
        page(0x02, 0, segmentsOf(head.size), head)
        // 2 ページ目: OpusTags
        val vendor = "filer".toByteArray()
        val tags = ByteArrayOutputStream().also { t ->
            t.write("OpusTags".toByteArray())
            for (i in 0 until 4) t.write(vendor.size shr (8 * i))
            t.write(vendor)
            for (i in 0 until 4) t.write(0)
        }.toByteArray()
        page(0x00, 0, segmentsOf(tags.size), tags)
        // データのページ: パケットを順に詰める
        var granule = 0L
        var i = 0
        var pos = 0
        val n = pk.sizes.size
        while (i < n) {
            val segs = ArrayList<Int>()
            val body = ByteArrayOutputStream()
            var last = i
            while (last < n) {
                val s = pk.sizes[last]
                val sg = segmentsOf(s)
                if (segs.isNotEmpty() && (segs.size + sg.size > 250 || body.size() > 8000)) break
                segs.addAll(sg)
                body.write(data, pos, s)
                granule += samples(data, pos, s)
                pos += s
                last++
            }
            i = last
            page(if (i >= n) 0x04 else 0x00, granule, segs, body.toByteArray())
        }
        return out.toByteArray()
    }
}
