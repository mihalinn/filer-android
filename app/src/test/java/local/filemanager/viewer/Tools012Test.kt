package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SniffTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun withHead(head: ByteArray, total: Int = 512): ByteArray = head.copyOf(total)

    @Test
    fun よくある形式を見分ける() {
        assertEquals("PNG画像", Sniff.detect(withHead(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))).label)
        assertEquals("JPEG画像", Sniff.detect(withHead(bytes(0xFF, 0xD8, 0xFF, 0xE0))).label)
        assertEquals("PDF", Sniff.detect(withHead("%PDF-1.7".toByteArray())).label)
        assertEquals("zip", Sniff.detect(withHead(bytes(0x50, 0x4B, 0x03, 0x04))).ext)
        assertEquals("7z", Sniff.detect(withHead(bytes(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C))).ext)
        assertEquals("gz", Sniff.detect(withHead(bytes(0x1F, 0x8B, 0x08))).ext)
        assertEquals("sqlite", Sniff.detect(withHead("SQLite format 3\u0000".toByteArray())).ext)
        assertEquals("mkv", Sniff.detect(withHead(bytes(0x1A, 0x45, 0xDF, 0xA3))).ext)
        assertEquals("webp", Sniff.detect(withHead("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1))).ext)
        assertEquals("wav", Sniff.detect(withHead("RIFF\u0000\u0000\u0000\u0000WAVEfmt ".toByteArray(Charsets.ISO_8859_1))).ext)
    }

    @Test
    fun MP4とHEICとM4A() {
        fun ftyp(brand: String) = withHead(bytes(0, 0, 0, 0x18) + "ftyp$brand".toByteArray())
        assertEquals("mp4", Sniff.detect(ftyp("isom")).ext)
        assertEquals("heic", Sniff.detect(ftyp("heic")).ext)
        assertEquals("avif", Sniff.detect(ftyp("avif")).ext)
        assertEquals("m4a", Sniff.detect(ftyp("M4A ")).ext)
        assertEquals("mov", Sniff.detect(ftyp("qt  ")).ext)
    }

    @Test
    fun TS録画とM2TSは_同期バイトの並びで見分ける() {
        val ts = ByteArray(1000)
        for (p in listOf(0, 188, 376, 564)) ts[p] = 0x47
        assertEquals("ts", Sniff.detect(ts).ext)
        val m2ts = ByteArray(1000)
        for (p in listOf(4, 196, 388, 580)) m2ts[p] = 0x47
        assertEquals("m2ts", Sniff.detect(m2ts).ext)
    }

    @Test
    fun 文字のファイル() {
        assertTrue(Sniff.detect("こんにちは\nhello\n".toByteArray()).isText)
        assertTrue(Sniff.detect("日本語のテキスト".toByteArray(charset("windows-31j"))).isText) // Shift_JIS も文字
        assertTrue(Sniff.detect(ByteArray(0)).isText)
        assertTrue(Sniff.detect(bytes(0xFF, 0xFE, 0x41, 0x00, 0x42, 0x00)).isText) // UTF-16 の BOM
        assertEquals("XML", Sniff.detect("<?xml version=\"1.0\"?><a/>".toByteArray()).label)
        assertEquals("スクリプト", Sniff.detect("#!/bin/sh\necho".toByteArray()).label)
    }

    @Test
    fun 文字でないファイル() {
        assertFalse(Sniff.detect(ByteArray(300) { (it * 7).toByte() }).isText)
        assertFalse(Sniff.looksLikeText(bytes(0x41, 0x00, 0x42)))
        assertEquals("不明なデータ", Sniff.detect(ByteArray(300) { if (it % 2 == 0) 0 else 0x80.toByte() }).label)
    }
}

class TextSearchTest {
    @Test
    fun 大文字小文字を区別しない() {
        assertEquals(listOf(0..2, 4..6), TextSearch.ranges("Foo foo", "FOO"))
        assertTrue(TextSearch.ranges("abc", "").isEmpty())
        assertTrue(TextSearch.ranges("abc", "x").isEmpty())
    }

    @Test
    fun 重ならずに数える() {
        assertEquals(listOf(0..1, 2..3), TextSearch.ranges("aaaa", "aa"))
    }

    @Test
    fun 当たった行() {
        val lines = listOf("alpha", "Beta", "gamma", "BETA2")
        assertEquals(listOf(1, 3), TextSearch.matchingLines(lines, "beta"))
        assertTrue(TextSearch.matchingLines(lines, "").isEmpty())
    }

    @Test
    fun 日本語も探せる() {
        assertEquals(listOf(2..3), TextSearch.ranges("あいうえお", "うえ"))
    }
}

class TrackLabelsTest {
    /** 言語名はアプリの言語(JVM の既定)で書くので、日本語の文面を確かめる間だけ日本語にする */
    private fun inJapanese(block: () -> Unit) {
        val saved = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.JAPANESE)
        try { block() } finally { java.util.Locale.setDefault(saved) }
    }

    @Test
    fun 言語名() = inJapanese {
        assertEquals("日本語", TrackLabels.languageName("ja"))
        assertEquals("日本語", TrackLabels.languageName("jpn"))
        assertEquals("英語", TrackLabels.languageName("en"))
        assertNull(TrackLabels.languageName(null))
        assertNull(TrackLabels.languageName("und"))
        assertNull(TrackLabels.languageName(""))
        // 英語のときは英語で書く
        assertEquals("Japanese", TrackLabels.languageName("ja", java.util.Locale.ENGLISH))
    }

    @Test
    fun 音声の名前() = inJapanese {
        assertEquals("音声1: 日本語・ステレオ・AAC", TrackLabels.audio(1, "ja", null, 2, "audio/mp4a-latm"))
        assertEquals("音声2: 英語・5.1ch・E-AC3", TrackLabels.audio(2, "eng", null, 6, "audio/eac3"))
        assertEquals("音声1: 副音声・ステレオ", TrackLabels.audio(1, null, "副音声", 2, null))
        assertEquals("音声3", TrackLabels.audio(3, null, null, 0, null))
        // 言語名と同じ名前は重ねない
        assertEquals("音声1: 日本語・ステレオ", TrackLabels.audio(1, "ja", "日本語", 2, null))
    }

    @Test
    fun 字幕の名前() = inJapanese {
        assertEquals("字幕1: 日本語・ASS", TrackLabels.text(1, "ja", null, "text/x-ssa"))
        assertEquals("字幕2: SRT", TrackLabels.text(2, null, null, "application/x-subrip"))
        assertEquals("字幕1", TrackLabels.text(1, null, null, null))
    }

    @Test
    fun チャンネル数() {
        assertEquals("モノラル", TrackLabels.channels(1))
        assertEquals("7.1ch", TrackLabels.channels(8))
        assertEquals("4ch", TrackLabels.channels(4))
        assertNull(TrackLabels.channels(0))
    }
}

class SubtitleFilesTest {
    @Test
    fun 動画名で始まる字幕を選ぶ() {
        val files = listOf("movie.mp4", "movie.srt", "movie.ja.srt", "movie.JPN.ass", "other.srt", "movie.txt", "movie2.srt", "MOVIE.vtt", "movie.en.ssa")
        val picked = SubtitleFiles.pick("movie.mp4", files)
        // 言語の指定が無いものを先に、あとは名前順
        assertEquals(listOf("movie.srt", "MOVIE.vtt", "movie.en.ssa", "movie.ja.srt", "movie.JPN.ass"), picked.map { it.name })
        assertEquals(listOf(null, null, "en", "ja", "JPN"), picked.map { it.language })
        assertEquals(listOf("srt", "vtt", "ssa", "srt", "ass"), picked.map { it.ext })
    }

    @Test
    fun 動画名が似ているだけの字幕は選ばない() {
        assertTrue(SubtitleFiles.pick("a.mp4", listOf("ab.srt", "a_b.srt", "b.a.srt")).isEmpty())
    }

    @Test
    fun 多すぎるときは8つまで() {
        val files = (1..20).map { "v.l$it.srt" }
        assertEquals(8, SubtitleFiles.pick("v.mkv", files).size)
    }

    @Test
    fun 日本語の名前() {
        val picked = SubtitleFiles.pick("名探偵プリキュア #35.ts", listOf("名探偵プリキュア #35.srt", "名探偵プリキュア #35.ja.ass"))
        assertEquals(2, picked.size)
    }
}
