package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 言語ごとの strings.xml(values = 英語(予備)、values-ja、これから足す values-xx)が、そろっているかの検査。
 * 言語を足したときに、訳の抜け・名前の間違い・%1$s などの記号の食い違いを、ここで見つける。
 */
class StringsParityTest {
    private class Entry(val plural: Boolean, val texts: Map<String, String>)

    private val resDir: File = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    private fun localeDirs(): List<File> =
        resDir.listFiles { f -> f.isDirectory && (f.name == "values" || f.name.startsWith("values-")) && File(f, "strings.xml").isFile }!!.sortedBy { it.name }

    private fun load(dir: File): Map<String, Entry> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(dir, "strings.xml"))
        val out = LinkedHashMap<String, Entry>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i) as? Element ?: continue
            val name = n.getAttribute("name")
            when (n.tagName) {
                "string" -> out[name] = Entry(false, mapOf("" to n.textContent))
                "plurals" -> {
                    val items = n.getElementsByTagName("item")
                    out[name] = Entry(true, (0 until items.length).associate { k -> (items.item(k) as Element).getAttribute("quantity") to items.item(k).textContent })
                }
            }
        }
        return out
    }

    /** 「%1$s」「%2$d」のような、値が入る場所の番号の集まり */
    private fun slots(s: String): Set<String> =
        Regex("%(\\d+)\\$").findAll(s).map { it.groupValues[1] }.toSet()

    /** 訳によって、使う値が違ってよい文字(日本語は「万」で数え、英語は桁区切りで書く、など) */
    private val slotsMayDiffer = setOf("preview_copy_cut")

    @Test
    fun すべての言語に同じ名前の文字がそろっている() {
        val dirs = localeDirs()
        assertTrue("values(予備の言語)がありません", dirs.any { it.name == "values" })
        val base = load(dirs.first { it.name == "values" })
        for (d in dirs) {
            val other = load(d)
            assertEquals("${d.name}: 足りない文字", emptyList<String>(), (base.keys - other.keys).sorted())
            assertEquals("${d.name}: 余分な文字", emptyList<String>(), (other.keys - base.keys).sorted())
        }
    }

    @Test
    fun 値が入る場所の番号がどの言語でも合っている() {
        val dirs = localeDirs()
        val base = load(dirs.first { it.name == "values" })
        val problems = ArrayList<String>()
        for (d in dirs) {
            for ((name, e) in load(d)) {
                val b = base[name] ?: continue
                if (e.plural != b.plural) { problems.add("${d.name}/$name: string と plurals が違う"); continue }
                val baseSlots = b.texts.values.flatMap { slots(it) }.toSet()
                for ((q, text) in e.texts) {
                    val s = slots(text)
                    if (name !in slotsMayDiffer && !baseSlots.containsAll(s)) problems.add("${d.name}/$name($q): 基準にない番号 $s(基準 $baseSlots)")
                    if (name !in slotsMayDiffer && s.isNotEmpty() && s != baseSlots && !e.plural) problems.add("${d.name}/$name: 番号が食い違う $s と $baseSlots")
                }
            }
        }
        assertEquals(problems.joinToString("\n"), 0, problems.size)
    }

    @Test
    fun 複数形は他と同じ形で書かれている() {
        val valid = setOf("zero", "one", "two", "few", "many", "other")
        for (d in localeDirs()) {
            for ((name, e) in load(d)) {
                if (!e.plural) continue
                assertTrue("${d.name}/$name: other がありません", "other" in e.texts)
                assertTrue("${d.name}/$name: 使えない区分 ${e.texts.keys - valid}", valid.containsAll(e.texts.keys))
            }
        }
    }

    @Test
    fun アポストロフィはエスケープされている() {
        for (d in localeDirs()) {
            val raw = File(d, "strings.xml").readText()
            // 文字の本文だけ見る(コメントは除く)
            val body = raw.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            val bad = Regex("(?<!\\\\)'").findAll(body).count()
            assertEquals("${d.name}: \\' になっていない ' がある", 0, bad)
        }
    }

    @Test
    fun アプリごとの言語の一覧と言語のフォルダが合っている() {
        val cfg = File(resDir, "xml/locales_config.xml").readText()
        val listed = Regex("android:name=\"([A-Za-z-]+)\"").findAll(cfg).map { it.groupValues[1] }.toSet()
        // values は予備の言語(英語)。values-xx は xx
        val have = localeDirs().map { if (it.name == "values") "en" else it.name.removePrefix("values-") }.toSet()
        assertEquals("locales_config.xml と values-* のフォルダが食い違っています", have, listed)
        // 設定の「言語」に出る一覧(Languages.supported)とも合わせる
        assertEquals("Languages.supported と values-* のフォルダが食い違っています", have, Languages.supported.map { it.tag }.toSet())
    }
}
