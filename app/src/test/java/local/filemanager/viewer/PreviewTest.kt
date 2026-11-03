package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvTest {
    @Test
    fun 単純な表() {
        val t = Csv.parse("a,b,c\n1,2,3\n", ',')
        assertEquals(listOf(listOf("a", "b", "c"), listOf("1", "2", "3")), t.rows)
        assertEquals(3, t.columns)
        assertFalse(t.truncated)
    }

    @Test
    fun 引用符の中の区切りと改行と二重引用符() {
        val t = Csv.parse("name,note\n\"山田, 太郎\",\"1行目\n2行目\"\n\"He said \"\"hi\"\"\",x\n", ',')
        assertEquals(3, t.rows.size)
        assertEquals("山田, 太郎", t.rows[1][0])
        assertEquals("1行目\n2行目", t.rows[1][1])
        assertEquals("He said \"hi\"", t.rows[2][0])
    }

    @Test
    fun 改行コードの違いと空行() {
        val t = Csv.parse("a,b\r\n\r\n1,2\r3,4", ',')
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2"), listOf("3", "4")), t.rows)
    }

    @Test
    fun 空のセルと行の長さの不揃い() {
        val t = Csv.parse("a,,c\n1\n,,\n", ',')
        assertEquals(listOf("a", "", "c"), t.rows[0])
        assertEquals(listOf("1"), t.rows[1])
        assertEquals(listOf("", "", ""), t.rows[2])
        assertEquals(3, t.columns)
    }

    @Test
    fun 先頭のBOMは取る() {
        assertEquals("a", Csv.parse("﻿a,b\n", ',').rows[0][0])
    }

    @Test
    fun 区切り文字の判定() {
        assertEquals(',', Csv.detectDelimiter("a,b,c\n1,2,3\n"))
        assertEquals('\t', Csv.detectDelimiter("a\tb\tc\n1\t2\t3\n"))
        assertEquals(';', Csv.detectDelimiter("a;b;c\n1;2;3\n"))
        assertEquals('\t', Csv.detectDelimiter("a,b\n1,2\n", "tsv"))
        // 引用符の中のカンマは数えない
        assertEquals('\t', Csv.detectDelimiter("\"a,b,c\"\tx\n\"d,e,f\"\ty\n"))
        assertEquals(',', Csv.detectDelimiter("hello\n"))
    }

    @Test
    fun 行数の上限() {
        val sb = StringBuilder()
        for (i in 1..10) sb.append("$i,x\n")
        val t = Csv.parse(sb.toString(), ',', maxRows = 4)
        assertEquals(4, t.rows.size)
        assertTrue(t.truncated)
        assertFalse(Csv.parse("1,x\n2,x\n", ',', maxRows = 2).truncated)
    }
}

class PrettyTest {
    @Test
    fun JSONを整形する() {
        val out = Pretty.json("""{"a":1,"b":[1,2,3],"c":{"d":"e"},"f":[],"g":{}}""")
        assertEquals(
            "{\n  \"a\": 1,\n  \"b\": [1, 2, 3],\n  \"c\": {\n    \"d\": \"e\"\n  },\n  \"f\": [],\n  \"g\": {}\n}",
            out,
        )
    }

    @Test
    fun JSONの文字列の中の記号は壊さない() {
        val out = Pretty.json("""{"s":"a,b:{c}\"d\\","t":"日本語"}""")
        assertNotNull(out)
        assertTrue(out!!.contains("\"a,b:{c}\\\"d\\\\\""))
        assertTrue(out.contains("日本語"))
    }

    @Test
    fun 長い配列と入れ子の配列は複数行() {
        val out = Pretty.json("""[{"a":1},{"a":2}]""")
        assertEquals("[\n  {\n    \"a\": 1\n  },\n  {\n    \"a\": 2\n  }\n]", out)
        val nums = (1..60).joinToString(",")
        assertTrue(Pretty.json("[$nums]")!!.contains("\n  1,\n"))
    }

    @Test
    fun JSONとして正しくなければnull() {
        assertNull(Pretty.json("{\"a\":}"))
        assertNull(Pretty.json("{\"a\":1,}"))
        assertNull(Pretty.json("{'a':1}"))
        assertNull(Pretty.json("hello"))
        assertNull(Pretty.json(""))
        assertNull(Pretty.json("{\"a\":1} x"))
    }

    @Test
    fun 値だけのJSONや数値も読める() {
        assertEquals("1.5e3", Pretty.json("1.5e3"))
        assertEquals("true", Pretty.json("true"))
        assertEquals("\"x\"", Pretty.json("\"x\""))
    }

    @Test
    fun JSON_Lines() {
        val out = Pretty.json("{\"a\":1}\n{\"b\":2}\n")
        assertEquals("{\n  \"a\": 1\n}\n\n{\n  \"b\": 2\n}", out)
    }

    @Test
    fun 深すぎるJSONでも落ちない() {
        val deep = "[".repeat(5000) + "]".repeat(5000)
        assertNull(Pretty.json(deep))
    }

    @Test
    fun XMLを整形する() {
        val out = Pretty.xml("""<?xml version="1.0"?><root a="1"><item>x</item><item/><empty></empty><g><h>y</h></g></root>""")
        assertEquals(
            "<?xml version=\"1.0\"?>\n<root a=\"1\">\n  <item>x</item>\n  <item/>\n  <empty></empty>\n  <g>\n    <h>y</h>\n  </g>\n</root>",
            out,
        )
    }

    @Test
    fun XMLのコメントとCDATAと属性内の記号() {
        val out = Pretty.xml("<a><!-- c --><b x=\"1>2\"><![CDATA[<z>]]></b></a>")
        assertEquals("<a>\n  <!-- c -->\n  <b x=\"1>2\"><![CDATA[<z>]]></b>\n</a>", out)
    }

    @Test
    fun XMLとして正しくなければnull() {
        assertNull(Pretty.xml("<a><b></a></b>"))
        assertNull(Pretty.xml("<a>"))
        assertNull(Pretty.xml("</a>"))
        assertNull(Pretty.xml("hello"))
    }
}

class HighlightTest {
    private fun toks(code: String, ext: String): List<Pair<String, Tok>> {
        val lang = Highlight.langFor(ext)!!
        return Highlight.spans(code, lang).map { code.substring(it.start, it.end) to it.tok }
    }

    @Test
    fun Kotlinのキーワード_文字列_コメント_数() {
        val t = toks("val x = \"hi\" // note\nfun f() = 42", "kt")
        assertTrue("val" to Tok.KEYWORD in t)
        assertTrue("\"hi\"" to Tok.STRING in t)
        assertTrue("// note" to Tok.COMMENT in t)
        assertTrue("fun" to Tok.KEYWORD in t)
        assertTrue("42" to Tok.NUMBER in t)
    }

    @Test
    fun 識別子の一部はキーワードにしない() {
        val t = toks("value valueOf x1 iffy", "kt")
        assertFalse(t.any { it.second == Tok.KEYWORD && it.first == "valueOf" })
        assertFalse(t.any { it.first == "iffy" })
        assertFalse(t.any { it.second == Tok.NUMBER })
    }

    @Test
    fun 複数行コメントと文字列の中のキーワードは塗り分けない() {
        val t = toks("/* if\nwhile */ int a = \"for\";", "c")
        assertEquals("/* if\nwhile */" to Tok.COMMENT, t[0])
        assertFalse(t.any { it.second == Tok.KEYWORD && it.first == "for" })
        assertTrue("int" to Tok.KEYWORD in t)
    }

    @Test
    fun 文字列のエスケープと閉じ忘れ() {
        val t = toks("s = \"a\\\"b\" # c\nt = 'oops\nu = 1", "py")
        assertTrue("\"a\\\"b\"" to Tok.STRING in t)
        assertTrue("# c" to Tok.COMMENT in t)
        // 閉じ忘れの ' は、その行の終わりまで
        assertTrue("'oops" to Tok.STRING in t)
        assertTrue("1" to Tok.NUMBER in t)
    }

    @Test
    fun Pythonの三重引用符は複数行() {
        val t = toks("x = \"\"\"a\nb\"\"\"\ny = 2", "py")
        assertTrue("\"\"\"a\nb\"\"\"" to Tok.STRING in t)
    }

    @Test
    fun HTMLのタグと属性とコメント() {
        val t = toks("<!-- c --><a href=\"x\">t</a>", "html")
        assertTrue("<!-- c -->" to Tok.COMMENT in t)
        assertTrue("<a" to Tok.KEYWORD in t)
        assertTrue("\"x\"" to Tok.STRING in t)
        assertTrue(">" to Tok.KEYWORD in t)
        assertTrue("</a" to Tok.KEYWORD in t)
    }

    @Test
    fun 行ごとに切り分ける() {
        val code = "a /* x\ny */ b\r\nc"
        val spans = Highlight.spans(code, Highlight.langFor("c")!!)
        val lines = Highlight.splitLines(code, spans)
        assertEquals(listOf("a /* x", "y */ b", "c"), lines.map { it.text })
        assertEquals(listOf(Span(2, 6, Tok.COMMENT)), lines[0].spans)
        assertEquals(listOf(Span(0, 4, Tok.COMMENT)), lines[1].spans)
        assertTrue(lines[2].spans.isEmpty())
    }

    @Test
    fun 空の文章と末尾の改行() {
        assertEquals(listOf(""), Highlight.splitLines("", emptyList()).map { it.text })
        assertEquals(listOf("a", ""), Highlight.splitLines("a\n", emptyList()).map { it.text })
    }

    @Test
    fun 見せ方の種類() {
        assertEquals(PreviewKind.PDF, PreviewKind.of("a.PDF"))
        assertEquals(PreviewKind.MARKDOWN, PreviewKind.of("README.md"))
        assertEquals(PreviewKind.CSV, PreviewKind.of("a.tsv"))
        assertEquals(PreviewKind.JSON, PreviewKind.of("a.json"))
        assertEquals(PreviewKind.XML, PreviewKind.of("a.xml"))
        assertEquals(PreviewKind.CODE, PreviewKind.of("Main.kt"))
        assertEquals(PreviewKind.TEXT, PreviewKind.of("a.txt"))
        assertNull(PreviewKind.of("rec.ts")) // TS は録画
        assertEquals(PreviewKind.DOC, PreviewKind.of("a.doc"))
        assertEquals(PreviewKind.RTF, PreviewKind.of("a.rtf"))
        assertEquals(PreviewKind.ODT, PreviewKind.of("a.odt"))
    }
}

class MarkdownTest {
    @Test
    fun 見出し_段落_区切り線() {
        val b = Markdown.parse("# 題\n\n本文1\n本文2\n\n---\n\n## 小見出し ##\n")
        assertEquals(
            listOf(
                MdBlock.Heading(1, "題"),
                MdBlock.Paragraph("本文1\n本文2"),
                MdBlock.Rule,
                MdBlock.Heading(2, "小見出し"),
            ),
            b,
        )
    }

    @Test
    fun 下線つきの見出し() {
        assertEquals(listOf(MdBlock.Heading(1, "A"), MdBlock.Heading(2, "B")), Markdown.parse("A\n===\n\nB\n---\n"))
    }

    @Test
    fun 箇条書きと入れ子と番号とタスク() {
        val b = Markdown.parse("- a\n  - b\n1. c\n- [x] d\n- [ ] e\n")
        assertEquals(
            listOf(
                MdBlock.Item(0, "•", "a", null),
                MdBlock.Item(1, "◦", "b", null),
                MdBlock.Item(0, "1.", "c", null),
                MdBlock.Item(0, "•", "d", true),
                MdBlock.Item(0, "•", "e", false),
            ),
            b,
        )
    }

    @Test
    fun 箇条書きの続きの行() {
        val b = Markdown.parse("- 長い\n  続き\n- 次\n")
        assertEquals(listOf(MdBlock.Item(0, "•", "長い\n続き", null), MdBlock.Item(0, "•", "次", null)), b)
    }

    @Test
    fun コードブロック() {
        val b = Markdown.parse("```kotlin\nval a = 1\n# not heading\n```\n後\n")
        assertEquals(listOf(MdBlock.Code("kotlin", "val a = 1\n# not heading"), MdBlock.Paragraph("後")), b)
        // 閉じ忘れは最後まで
        assertEquals(listOf(MdBlock.Code("", "x\ny")), Markdown.parse("```\nx\ny"))
    }

    @Test
    fun 引用() {
        assertEquals(listOf(MdBlock.Quote("a\nb")), Markdown.parse("> a\n> b\n"))
    }

    @Test
    fun 表() {
        val b = Markdown.parse("| 名前 | 値 |\n|---|:-:|\n| a | 1 |\n| b \\| c | 2 |\n\n後")
        val t = b[0] as MdBlock.Table
        assertEquals(listOf("名前", "値"), t.header)
        assertEquals(listOf(listOf("a", "1"), listOf("b | c", "2")), t.rows)
        assertEquals(MdBlock.Paragraph("後"), b[1])
    }

    @Test
    fun front_matterはコードとして出す() {
        val b = Markdown.parse("---\ntitle: x\n---\n\n# H\n")
        assertEquals(MdBlock.Code("yaml", "title: x"), b[0])
        assertEquals(MdBlock.Heading(1, "H"), b[1])
    }

    @Test
    fun 太字_斜体_コード_取り消し線() {
        val r = Markdown.inline("a **b** *c* `d` ~~e~~ ***f***")
        assertTrue(Run("b", bold = true) in r)
        assertTrue(Run("c", italic = true) in r)
        assertTrue(Run("d", code = true) in r)
        assertTrue(Run("e", strike = true) in r)
        assertTrue(Run("f", bold = true, italic = true) in r)
        assertEquals("a b c d e f", Markdown.plain("a **b** *c* `d` ~~e~~ ***f***"))
    }

    @Test
    fun リンクと画像と自動リンク() {
        val r = Markdown.inline("[サイト](https://example.com/a) と ![図](x.png) と https://b.example/c.")
        assertTrue(Run("サイト", link = "https://example.com/a") in r)
        assertTrue(r.any { it.text == "[画像: 図]" && it.italic })
        assertTrue(Run("https://b.example/c", link = "https://b.example/c") in r)
        // 文末の . はリンクに含めない
        assertEquals(".", r.last().text)
    }

    @Test
    fun 記号の打ち消しと単語の途中のアンダースコア() {
        assertEquals("*x* snake_case_name", Markdown.plain("\\*x\\* snake_case_name"))
        assertEquals("a*b", Markdown.plain("a*b"))
        assertEquals("**", Markdown.plain("**"))
    }

    @Test
    fun リンクの中の飾り() {
        val r = Markdown.inline("[**太**字](u)")
        assertTrue(Run("太", bold = true, link = "u") in r)
        assertTrue(Run("字", link = "u") in r)
    }

    @Test
    fun 壊れた入力でも落ちない() {
        for (s in listOf("[", "[a](", "**", "`", "```", "![", "<", "~~", "_", "* ", "| |\n|-|", "> ", "- ", "# ", "\\")) {
            Markdown.parse(s)
            Markdown.inline(s)
        }
        Markdown.parse("[".repeat(5000) + "]".repeat(5000))
        Markdown.inline("*".repeat(3000))
    }
}
