package local.filemanager

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * PC の単体試験用の文字の窓口(本番では Android が strings.xml から取り出す)。
 * res/values-ja/strings.xml(日本語)を直接読み、Android と同じ規則(空白のまとめ方・\n などの書き方)で文にする。
 * Txt は Context が無いとき、この名前のクラスを探して使う。
 */
object TestStrings : Txt.Resolver {
    private val strings = HashMap<String, String>()
    private val plurals = HashMap<String, String>()
    private val names = HashMap<Int, String>()

    init {
        val file = listOf("src/main/res/values-ja/strings.xml", "app/src/main/res/values-ja/strings.xml").map { File(it) }.first { it.isFile }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val root = doc.documentElement.childNodes
        for (i in 0 until root.length) {
            val n = root.item(i)
            if (n.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
            val name = (n as org.w3c.dom.Element).getAttribute("name")
            when (n.tagName) {
                "string" -> strings[name] = unescape(n.textContent)
                "plurals" -> {
                    val items = n.getElementsByTagName("item")
                    for (k in 0 until items.length) {
                        val item = items.item(k) as org.w3c.dom.Element
                        if (item.getAttribute("quantity") == "other") plurals[name] = unescape(item.textContent)
                    }
                }
            }
        }
        for (kind in listOf("string" to strings.keys, "plurals" to plurals.keys)) {
            val cls = Class.forName("local.filemanager.R\$${kind.first}")
            for (f in cls.fields) names[f.getInt(null)] = f.name
        }
    }

    /** Android の規則: 全体を " で囲んでいなければ空白を 1 つにまとめる。\ で始まる書き方を元に戻す。 */
    private fun unescape(raw: String): String {
        val t = raw.trim()
        val quoted = t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")
        val body = if (quoted) t.substring(1, t.length - 1) else t.replace(Regex("\\s+"), " ")
        val sb = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                when (val d = body[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'u' -> { sb.append(body.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                    else -> sb.append(d)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    override fun string(id: Int, args: Array<out Any?>): String {
        val name = names[id] ?: return "string#$id"
        val text = strings[name] ?: return "string:$name"
        return if (args.isEmpty()) text else String.format(Locale.ROOT, text, *args)
    }

    override fun plural(id: Int, count: Int, args: Array<out Any?>): String {
        val name = names[id] ?: return "plural#$id"
        val text = plurals[name] ?: return "plural:$name"
        return String.format(Locale.ROOT, text, count, *args)
    }
}
