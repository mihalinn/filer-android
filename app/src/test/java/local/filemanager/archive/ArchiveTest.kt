package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private fun entry(name: String, size: Long = 10, dir: Boolean = name.endsWith("/"), modified: Long = 0, enc: Boolean = false) =
    ZipReader.Entry(name, dir, 8, size / 2, size, 0, enc, modified)

class ZipTreeTest {
    private val entries = listOf(
        entry("readme.txt", 100, modified = 5),
        entry("docs/", 0, modified = 1),
        entry("docs/a.txt", 20, modified = 7),
        entry("docs/sub/b.png", 30, modified = 9),
        entry("img/c.jpg", 40, modified = 2), // フォルダの項目が無いZIP
        entry("Z.txt", 1),
        entry("../evil.txt", 5), // 外へ出る名前は出さない
        entry("a/../b.txt", 5),
    )

    @Test
    fun いちばん上の階層は_フォルダ_ファイルの順() {
        val rows = ZipTree.list(entries, "")
        assertEquals(listOf("docs", "img", "readme.txt", "Z.txt"), rows.map { it.name })
        assertEquals(listOf(true, true, false, false), rows.map { it.isDir })
        val docs = rows[0]
        assertEquals("docs/", docs.path)
        assertEquals(2, docs.count)
        assertEquals(50L, docs.size)
        assertEquals(9L, docs.modified)
        assertEquals(40L, rows[1].size) // 項目の無いフォルダも、中身から作る
    }

    @Test
    fun 下の階層() {
        val rows = ZipTree.list(entries, "docs/")
        assertEquals(listOf("sub", "a.txt"), rows.map { it.name })
        assertEquals("docs/a.txt", rows[1].path)
        assertEquals(1, ZipTree.list(entries, "docs/sub/").size)
        assertTrue(ZipTree.list(entries, "nothing/").isEmpty())
    }

    @Test
    fun 外へ出る名前は出ない() {
        val all = ZipTree.list(entries, "").map { it.name }
        assertFalse("evil.txt" in all)
        assertFalse(ZipTree.list(entries, "a/").any { true })
    }

    @Test
    fun 上の階層と名前の整理() {
        assertEquals("", ZipTree.parent("docs/"))
        assertEquals("docs/", ZipTree.parent("docs/sub/"))
        assertEquals("", ZipTree.parent(""))
        assertEquals("a/b", ZipTree.clean("./a\\b/"))
    }

    @Test
    fun 全体の数と大きさ() {
        val (n, bytes) = ZipTree.totals(entries)
        assertEquals(7, n) // フォルダの項目(docs/)は数えない
        assertEquals(100L + 20 + 30 + 40 + 1 + 5 + 5, bytes)
    }

    @Test
    fun 実際のZIPを端末内のファイルとして読める() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("dir/hello.txt")); z.write("こんにちは".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("top.bin")); z.write(ByteArray(1000) { (it % 7).toByte() }); z.closeEntry()
        }
        val f = File.createTempFile("ziptree", ".zip")
        try {
            f.writeBytes(bos.toByteArray())
            val src = FileRandomSource(f)
            val r = ZipReader(src)
            val rows = ZipTree.list(r.entries, "")
            assertEquals(listOf("dir", "top.bin"), rows.map { it.name })
            val inner = ZipTree.list(r.entries, "dir/").single()
            assertEquals("こんにちは", r.open(inner.entry!!).use { String(it.readBytes()) })
            src.close()
        } finally {
            f.delete()
        }
    }

    @Test
    fun 端末内ファイルの読み出し() {
        val f = File.createTempFile("src", ".bin")
        try {
            f.writeBytes(ByteArray(100) { it.toByte() })
            val s = FileRandomSource(f)
            assertEquals(100L, s.size)
            val b = ByteArray(10)
            assertEquals(10, s.readAt(50, b, 0, 10))
            assertEquals(50, b[0].toInt())
            assertEquals(-1, s.readAt(100, b, 0, 10))
            s.close()
        } finally {
            f.delete()
        }
    }
}

class ApkFormatTest {
    @Test
    fun 署名の表示() {
        // SHA-256("") の先頭
        assertEquals(
            "E3:B0:C4:42:98:FC:1C:14:9A:FB:F4:C8:99:6F:B9:24:27:AE:41:E4:64:9B:93:4C:A4:95:99:1B:78:52:B8:55",
            ApkFormat.fingerprint(ByteArray(0)),
        )
    }

    @Test
    fun 版の比べ() {
        assertEquals(ApkFormat.Compare.NEWER, ApkFormat.compare(10, 9))
        assertEquals(ApkFormat.Compare.SAME, ApkFormat.compare(9, 9))
        assertEquals(ApkFormat.Compare.OLDER, ApkFormat.compare(8, 9))
    }

    @Test
    fun 署名が同じか() {
        assertEquals(true, ApkFormat.sameSigner(listOf("A", "B"), listOf("B")))
        assertEquals(false, ApkFormat.sameSigner(listOf("A"), listOf("B")))
        assertNull(ApkFormat.sameSigner(emptyList(), listOf("B")))
        assertNull(ApkFormat.sameSigner(listOf("A"), emptyList()))
    }

    @Test
    fun 名前の表示() {
        assertEquals("READ_CONTACTS", ApkFormat.shortPermission("android.permission.READ_CONTACTS"))
        assertEquals("com.example.X", ApkFormat.shortPermission("com.example.X"))
        assertEquals("Android 11(API 30)", ApkFormat.sdkText(30))
        assertEquals("API 99", ApkFormat.sdkText(99))
    }

    @Test
    fun 見せ方の種類_ZIPとAPK() {
        assertEquals(PreviewKind.ZIP, PreviewKind.of("a.zip"))
        assertEquals(PreviewKind.BUNDLE, PreviewKind.of("b.XAPK"))
        assertEquals(PreviewKind.APK, PreviewKind.of("app.apk"))
        assertEquals(PreviewKind.ARCHIVE, PreviewKind.of("a.rar"))
    }
}
class ApkBundleTest {
    private fun e(name: String, size: Long = 100) = ZipReader.Entry(name, name.endsWith("/"), 8, size / 2, size, 0, false, 0)

    // 実物の X Lite_10.1_APKPure.xapk の中身
    private val xapk = listOf(
        e("com.xlite.twitter.apk", 9444527), e("icon.png", 168166), e("config.ko.apk", 29081), e("config.tr.apk", 29081),
        e("config.pt.apk", 37273), e("config.zh.apk", 37273), e("config.ja.apk", 29081), e("config.in.apk", 29081),
        e("config.en.apk", 49561), e("config.xxxhdpi.apk", 79717), e("manifest.json", 2570),
    )

    // 実物の com.amazon.mp3 ….apkm の中身
    private val apkm = listOf(
        e("META-INF/MANIFEST.MF", 1141), e("info.json", 1917), e("icon.png", 129974), e("base.apk", 204554777),
        e("split_config.arm64_v8a.apk", 64755088), e("split_config.xxhdpi.apk", 3464677), e("split_config.xxxhdpi.apk", 3747422),
        e("split_config.ldpi.apk", 1474304), e("split_config.x86_64.apk", 55705351), e("split_config.armeabi_v7a.apk", 52110864),
        e("split_config.xhdpi.apk", 2177967), e("split_config.hdpi.apk", 1743334), e("split_config.x86.apk", 55602975),
        e("split_config.tvdpi.apk", 2726825), e("split_config.mdpi.apk", 1202649), e("APKM_installer.url", 58),
    )

    private val arm = listOf("arm64-v8a", "armeabi-v7a", "armeabi")

    @Test
    fun xapk_本体は名前で決まらなくても_いちばん大きいAPK() {
        val plan = ApkBundle.plan(xapk, arm)
        assertEquals("com.xlite.twitter.apk", plan.base!!.fileName)
        // 言語用 7 本 + 画面用 1 本 + 本体
        assertEquals(9, plan.chosen.size)
        assertEquals(7, plan.count(SplitKind.LANG))
        assertEquals(1, plan.count(SplitKind.DPI))
        assertNull(plan.abi)
        assertFalse(plan.abiMismatch) // CPU 用の部品が無いアプリは、CPU に依らない
        assertTrue(plan.skipped.isEmpty())
    }

    @Test
    fun apkm_この端末のCPU用だけを選ぶ() {
        val plan = ApkBundle.plan(apkm, arm)
        assertEquals("base.apk", plan.base!!.fileName)
        assertEquals("arm64-v8a", plan.abi)
        assertEquals(1, plan.count(SplitKind.ABI))
        assertEquals(7, plan.count(SplitKind.DPI)) // 画面用は全部入れる
        assertEquals(9, plan.chosen.size)
        assertEquals(setOf("x86_64", "armeabi_v7a", "x86"), plan.skipped.map { it.tag }.toSet())
        assertEquals(204554777L + 64755088 + 3464677 + 3747422 + 1474304 + 2177967 + 1743334 + 2726825 + 1202649, plan.totalBytes)
    }

    @Test
    fun CPUの優先順位に従う() {
        // 32 ビットの端末(armeabi-v7a だけ)なら、v7a 用を選ぶ
        assertEquals("armeabi-v7a", ApkBundle.plan(apkm, listOf("armeabi-v7a", "armeabi")).abi)
        // x86_64 の端末(x86_64 を優先、次に x86)
        assertEquals("x86_64", ApkBundle.plan(apkm, listOf("x86_64", "x86")).abi)
        assertEquals("x86", ApkBundle.plan(apkm, listOf("x86")).abi)
    }

    @Test
    fun 合うCPU用の部品が無いと_動かない印() {
        val onlyX86 = listOf(e("base.apk", 1000), e("split_config.x86.apk", 100), e("split_config.x86_64.apk", 100))
        val plan = ApkBundle.plan(onlyX86, arm)
        assertTrue(plan.abiMismatch)
        assertNull(plan.abi)
    }

    @Test
    fun bundletoolのapks() {
        val apks = listOf(
            e("toc.pb", 300), e("splits/base-master.apk", 5000), e("splits/base-arm64_v8a.apk", 800), e("splits/base-x86.apk", 800),
            e("splits/base-xxhdpi.apk", 300), e("splits/base-ja.apk", 50), e("splits/base-master_2.apk", 60),
            e("standalones/standalone-xxhdpi.apk", 99999), e("metadata.json", 20),
        )
        val plan = ApkBundle.plan(apks, arm)
        assertEquals("base-master.apk", plan.base!!.fileName)
        assertEquals("arm64-v8a", plan.abi)
        // 本体・arm64・画面・言語・そのほか(master_2)。standalones は使わない
        assertEquals(5, plan.chosen.size)
        assertFalse(plan.chosen.any { it.entry.name.startsWith("standalones/") })
    }

    @Test
    fun 単独のAPKが1本だけ() {
        val plan = ApkBundle.plan(listOf(e("universal.apk", 1000)), arm)
        assertEquals("universal.apk", plan.base!!.fileName)
        assertEquals(1, plan.chosen.size)
    }

    @Test
    fun APKが無ければ本体なし() {
        assertNull(ApkBundle.plan(listOf(e("readme.txt")), arm).base)
        assertNull(ApkBundle.plan(emptyList(), arm).base)
    }

    @Test
    fun xapkの目録() {
        val manifest = """{"xapk_version":2,"package_name":"com.xlite.twitter","name":"X","version_code":"10","version_name":"10.1","min_sdk_version":"16","target_sdk_version":"33","permissions":["a"],"icon":"icon.png"}"""
        val m = ApkBundle.meta("X.xapk", xapk) { if (it.name == "manifest.json") manifest else null }
        assertEquals("X", m.name)
        assertEquals("com.xlite.twitter", m.packageName)
        assertEquals("10.1", m.versionName)
        assertEquals(10L, m.versionCode)
        assertEquals(16, m.minSdk)
        assertEquals("icon.png", m.iconEntry!!.name)
        assertFalse(m.hasObb)
        assertEquals("XAPK", m.format)
    }

    @Test
    fun apkmの目録() {
        val info = """{ "apkm_version": 5, "app_name": "Amazon Music: Songs &amp; Podcasts", "release_version": "26.17.1", "versioncode": "526170110", "pname": "com.amazon.mp3" }"""
        val m = ApkBundle.meta("a.apkm", apkm) { if (it.name == "info.json") info else null }
        assertEquals("Amazon Music: Songs & Podcasts", m.name)
        assertEquals("com.amazon.mp3", m.packageName)
        assertEquals("26.17.1", m.versionName)
        assertEquals(526170110L, m.versionCode)
        assertEquals("APKM", m.format)
    }

    @Test
    fun 目録が無いapksやOBBの印() {
        val m = ApkBundle.meta("a.apks", listOf(e("toc.pb"), e("Android/obb/com.x/main.1.com.x.obb", 10))) { null }
        assertNull(m.name)
        assertEquals("APKS", m.format)
        assertTrue(m.hasObb)
    }

    @Test
    fun JSONの文字の読み出し() {
        assertEquals("a\"b\n日", ApkBundle.jsonString("""{"k":"a\"b\n\u65e5"}""", "k"))
        assertNull(ApkBundle.jsonString("""{"k":1}""", "k"))
        assertEquals(12L, ApkBundle.jsonNumber("""{"n": 12}""", "n"))
        assertEquals(12L, ApkBundle.jsonNumber("""{"n": "12"}""", "n"))
        // 別の項目の名前の一部は、拾わない
        assertNull(ApkBundle.jsonString("""{"package_name":"p"}""", "name"))
    }

    @Test
    fun インストール結果の文() {
        assertEquals("インストールしました",InstallStatusText.of(android.content.pm.PackageInstaller.STATUS_SUCCESS, null))
        val c = InstallStatusText.of(android.content.pm.PackageInstaller.STATUS_FAILURE_CONFLICT, "INSTALL_FAILED_UPDATE_INCOMPATIBLE")
        assertTrue(c.contains("署名"))
        assertTrue(c.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE"))
        assertTrue(InstallStatusText.of(android.content.pm.PackageInstaller.STATUS_FAILURE_STORAGE, null).contains("空き容量"))
    }
}
class BundleSampleTest {
    /** 実物での確認用(環境変数 FILER_BUNDLE_SAMPLES に、ファイルの場所を「;」区切りで入れたときだけ動く)。 */
    @Test
    fun 実物のまとめ() {
        val paths = System.getenv("FILER_BUNDLE_SAMPLES")
        org.junit.Assume.assumeTrue(paths != null)
        for (p in paths!!.split(';')) {
            val f = File(p)
            val src = FileRandomSource(f)
            val r = ZipReader(src)
            val plan = ApkBundle.plan(r.entries, listOf("arm64-v8a", "armeabi-v7a", "armeabi"))
            val meta = ApkBundle.meta(f.name, r.entries) { e -> r.open(e).use { String(it.readBytes(), Charsets.UTF_8) } }
            println("bundle ${f.name}: name=${meta.name} pkg=${meta.packageName} ver=${meta.versionName}(${meta.versionCode}) min=${meta.minSdk} obb=${meta.hasObb}")
            println("  base=${plan.base?.fileName} chosen=${plan.chosen.size} skipped=${plan.skipped.map { it.fileName }} abi=${plan.abi} bytes=${plan.totalBytes}")
            assertTrue(plan.base != null)
            assertFalse(plan.abiMismatch)
            // 選んだ部品が、本当に取り出せる(最後まで読める)ことも確かめる
            for (s in plan.chosen) {
                val n = r.open(s.entry).use { it.copyTo(java.io.OutputStream.nullOutputStream()) }
                assertEquals(s.entry.name, s.entry.size, n)
            }
            src.close()
        }
    }
}
