package local.filemanager

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 「ライセンス」画面の一覧と、同梱のライセンス全文(assets/licenses)の食い違いを防ぐ。 */
class CreditsTest {
    private val dir = File("src/main/assets/licenses")

    @Test
    fun everyCreditHasItsLicenseText() {
        assertTrue("assets/licenses が見つかりません: ${dir.absolutePath}", dir.isDirectory)
        for (c in Credits.list) {
            val f = File(dir, c.textFile)
            assertTrue("${c.name}: ${c.textFile} がありません", f.isFile && f.length() > 200)
        }
    }

    @Test
    fun licenseTextsMatchTheirNames() {
        fun head(n: String) = File(dir, n).readText().take(400)
        assertTrue(head("gpl-3.0.txt").contains("GNU GENERAL PUBLIC LICENSE") && head("gpl-3.0.txt").contains("Version 3"))
        assertTrue(head("lgpl-3.0.txt").contains("GNU LESSER GENERAL PUBLIC LICENSE"))
        assertTrue(head("apache-2.0.txt").contains("Apache License"))
        assertTrue(head("libvpx.txt").contains("BSD") && head("libvpx.txt").contains("WebM Project"))
        assertTrue(head("mbassador.txt").contains("MIT License"))
        assertTrue(head("commons-notice.txt").contains("Apache Software Foundation"))
    }

    @Test
    fun appCopyrightNamesTheAuthor() {
        assertTrue(Credits.COPYRIGHT.contains("mihalin"))
        assertTrue(Credits.list.first().note!!.contains(Credits.COPYRIGHT))
    }

    @Test
    fun noLicenseTextIsLeftUnused() {
        val used = Credits.list.map { it.textFile }.toSet()
        val unused = dir.listFiles().orEmpty().map { it.name }.filter { it !in used }
        assertTrue("一覧から参照されていないライセンス全文: $unused", unused.isEmpty())
    }

    @Test
    fun thirdPartyEntriesThatShipInTheApkAreListed() {
        // 実際に APK に入っている部品(依存関係と同梱の .so・データ)。増えたらここも足す
        val names = Credits.list.joinToString("\n") { it.name }
        for (must in listOf(
            "NextLib", "FFmpeg", "libvpx", "Media3", "Compose", "Kotlin", "Guava", "smbj", "Mbassador",
            "Commons", "AndroidSVG", "Bouncy Castle", "SLF4J", "XZ", "junrar", "ezjww",
        )) assertTrue("一覧に $must がありません", names.contains(must))
    }
}
