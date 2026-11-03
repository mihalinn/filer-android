package local.filemanager

/** ビューアでの見せ方の種類。 */
enum class PreviewKind {
    PDF, DRAWIO, STL, MODEL, CAD, VCARD, HTML, ZIP, ARCHIVE, COMIC, EPUB, SQLITE, FONT, MHTML, WEBARCHIVE, PKPASS, IWORK, IPA, BUNDLE, APK, XLSX, DOCX, PPTX, ODT, ODS, ODP, RTF, XLS, DOC, PPT, MARKDOWN, CSV, JSON, XML, CODE, TEXT,

    /** 拡張子から種類が分からないファイル(PreviewKind.of は返さない。中身を見て決める)。 */
    UNKNOWN;

    /** Excel・Word・PowerPoint・OpenDocument・RTF のように、OfficePane で見せる種類。 */
    val isOffice: Boolean get() = this == XLSX || this == DOCX || this == PPTX || this == ODT || this == ODS || this == ODP || this == RTF || this == XLS || this == DOC || this == PPT

    companion object {
        /** 拡張子から。アプリ内ビューアで開かない種類は null。 */
        fun of(name: String): PreviewKind? {
            val ext = FileOps.extensionOf(name)
            return when {
                ext == "pdf" -> PDF
                ext == "drawio" -> DRAWIO
                ext == "stl" -> STL
                ext == "obj" || ext == "ply" || ext == "off" || ext == "3mf" -> MODEL
                ext == "dxf" || ext == "dwg" || ext == "jww" || ext == "jwc" -> CAD
                ext == "vcf" -> VCARD
                ext == "html" || ext == "htm" -> HTML
                ext == "zip" || ext == "jar" || ext == "livp" -> ZIP
                ext == "cbz" || ext == "cbr" || ext == "cb7" || ext == "cbt" -> COMIC
                ext == "epub" -> EPUB
                ext == "mht" || ext == "mhtml" -> MHTML
                ext == "webarchive" -> WEBARCHIVE
                ext == "pkpass" -> PKPASS
                ext == "pages" || ext == "numbers" || ext == "key" || ext == "keynote" -> IWORK
                ext == "ipa" -> IPA
                ext == "plist" || ext == "mobileconfig" || ext == "mobileprovision" -> XML
                ext == "db" || ext == "sqlite" || ext == "sqlite3" || ext == "db3" || ext == "s3db" || ext == "sl3" -> SQLITE
                ext == "ttf" || ext == "otf" || ext == "ttc" -> FONT
                Archives.viewerType(name) != null -> ARCHIVE
                ext == "apks" || ext == "xapk" || ext == "apkm" -> BUNDLE
                ext == "apk" -> APK
                ext == "xlsx" || ext == "xlsm" -> XLSX
                ext == "docx" -> DOCX
                ext == "pptx" -> PPTX
                ext == "odt" || ext == "ott" -> ODT
                ext == "ods" || ext == "ots" -> ODS
                ext == "odp" || ext == "otp" -> ODP
                ext == "rtf" -> RTF
                ext == "xls" || ext == "xlt" -> XLS
                ext == "doc" || ext == "dot" -> DOC
                ext == "ppt" || ext == "pps" -> PPT
                ext == "md" || ext == "markdown" -> MARKDOWN
                ext == "csv" || ext == "tsv" -> CSV
                ext == "json" || ext == "jsonl" || ext == "ndjson" -> JSON
                ext == "xml" || ext == "svg" -> XML
                !FileOps.isTextName(name) -> null
                Highlight.langFor(ext) != null -> CODE
                else -> TEXT
            }
        }
    }
}

enum class Tok { KEYWORD, STRING, COMMENT, NUMBER }

/** text の [start, end) が tok の色になる。 */
data class Span(val start: Int, val end: Int, val tok: Tok)

/** 1 行分の文字と、その行の中での色の範囲。 */
data class HLine(val text: String, val spans: List<Span>)

class Lang(
    val keywords: Set<String> = emptySet(),
    val lineComments: List<String> = emptyList(),
    val blockComment: Pair<String, String>? = null,
    /** 1 行の中で閉じる引用符 */
    val quotes: String = "\"'",
    /** 行をまたげる引用符(JavaScript の ` など) */
    val multilineQuotes: String = "",
    /** """ や ''' で始まる複数行の文字列 */
    val tripleQuotes: Boolean = false,
    val ignoreCase: Boolean = false,
    val markup: Boolean = false,
)

/** ごく簡単な色分け(正確な構文解析ではなく、キーワード・文字列・コメント・数を見分けるだけ)。Android に依存しない。 */
object Highlight {
    private fun words(s: String) = s.split(' ').filter { it.isNotEmpty() }.toSet()

    private val kotlin = words(
        "abstract annotation as break by catch class companion const constructor continue crossinline data do else enum " +
            "false final finally for fun get if in infix init inline inner interface internal is it lateinit lazy noinline null " +
            "object open operator out override package private protected public reified return sealed set super suspend this " +
            "throw true try typealias val value var vararg when where while",
    )
    private val java = words(
        "abstract assert boolean break byte case catch char class const continue default do double else enum extends false final " +
            "finally float for goto if implements import instanceof int interface long native new null package private protected " +
            "public return short static strictfp super switch synchronized this throw throws transient true try var void volatile while " +
            "record sealed permits yield",
    )
    private val csharp = words(
        "abstract as base bool break byte case catch char checked class const continue decimal default delegate do double else enum " +
            "event explicit extern false finally fixed float for foreach goto if implicit in int interface internal is lock long " +
            "namespace new null object operator out override params private protected public readonly ref return sbyte sealed short " +
            "sizeof stackalloc static string struct switch this throw true try typeof uint ulong unchecked unsafe ushort using var " +
            "virtual void volatile while async await record",
    )
    private val js = words(
        "async await break case catch class const continue debugger default delete do else export extends false finally for from " +
            "function if import in instanceof let new null of return static super switch this throw true try typeof undefined var " +
            "void while with yield interface type enum implements namespace readonly declare",
    )
    private val python = words(
        "False None True and as assert async await break class continue def del elif else except finally for from global if import " +
            "in is lambda nonlocal not or pass raise return try while with yield match case self",
    )
    private val c = words(
        "auto break case char const continue default do double else enum extern float for goto if inline int long register restrict " +
            "return short signed sizeof static struct switch typedef union unsigned void volatile while bool true false NULL nullptr " +
            "class namespace template typename public private protected virtual override new delete this using try catch throw " +
            "constexpr noexcept operator explicit friend mutable",
    )
    private val go = words(
        "break case chan const continue default defer else fallthrough for func go goto if import interface map package range " +
            "return select struct switch type var true false nil iota",
    )
    private val rust = words(
        "as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut pub " +
            "ref return self Self static struct super trait true type unsafe use where while",
    )
    private val shell = words(
        "if then else elif fi for while until do done case esac in function return exit break continue local export readonly " +
            "echo cd set unset source alias true false",
    )
    private val bat = words(
        "echo set if else for in do goto call exit not exist errorlevel equ neq lss leq gtr geq setlocal endlocal pause rem start",
    )
    private val sql = words(
        "select from where and or not null insert into values update set delete create table alter drop index view join inner " +
            "left right outer full on as group by order having limit offset union all distinct case when then else end is in like " +
            "between exists primary key foreign references default unique constraint begin commit rollback with asc desc",
    )
    private val ruby = words(
        "alias and begin break case class def defined do else elsif end ensure false for if in module next nil not or redo rescue " +
            "retry return self super then true undef unless until when while yield require include attr_accessor puts",
    )
    private val lua = words(
        "and break do else elseif end false for function goto if in local nil not or repeat return then true until while",
    )
    private val php = words(
        "abstract and array as break callable case catch class clone const continue declare default do echo else elseif empty " +
            "enddeclare endfor endforeach endif endswitch endwhile extends final finally fn for foreach function global if implements " +
            "include include_once instanceof interface isset list match namespace new null or print private protected public require " +
            "require_once return static switch this throw trait true false try unset use var while yield",
    )
    private val literals = words("true false null yes no on off")

    private val cLike = listOf("//")
    private val cBlock = "/*" to "*/"

    private val byExt: Map<String, Lang> = buildMap {
        put("kt", Lang(kotlin, cLike, cBlock, tripleQuotes = true))
        put("kts", Lang(kotlin, cLike, cBlock, tripleQuotes = true))
        put("java", Lang(java, cLike, cBlock))
        put("gradle", Lang(java + kotlin, cLike, cBlock))
        put("cs", Lang(csharp, cLike, cBlock))
        for (e in listOf("js", "mjs", "ts", "tsx", "jsx")) put(e, Lang(js, cLike, cBlock, multilineQuotes = "`"))
        put("py", Lang(python, listOf("#"), null, tripleQuotes = true))
        for (e in listOf("c", "h", "cpp", "hpp", "cc")) put(e, Lang(c, cLike, cBlock))
        put("go", Lang(go, cLike, cBlock, multilineQuotes = "`"))
        put("rs", Lang(rust, cLike, cBlock))
        put("sh", Lang(shell, listOf("#")))
        put("bat", Lang(bat, listOf("::", "rem ", "REM "), null, "\"", ignoreCase = true))
        put("sql", Lang(sql, listOf("--"), cBlock, ignoreCase = true))
        put("rb", Lang(ruby, listOf("#")))
        put("lua", Lang(lua, listOf("--"), "--[[" to "]]"))
        put("php", Lang(php, listOf("//", "#"), cBlock))
        put("css", Lang(emptySet(), emptyList(), cBlock))
        put("json", Lang(literals, quotes = "\""))
        put("jsonl", Lang(literals, quotes = "\""))
        put("ndjson", Lang(literals, quotes = "\""))
        for (e in listOf("yml", "yaml", "toml")) put(e, Lang(literals, listOf("#")))
        for (e in listOf("ini", "cfg", "conf", "properties")) put(e, Lang(literals, listOf("#", ";"), quotes = "\""))
        for (e in listOf("html", "htm", "xml", "svg")) put(e, Lang(markup = true))
    }

    fun langFor(ext: String): Lang? = byExt[ext]

    private fun isIdentStart(c: Char) = c == '_' || c == '$' || c.isLetter()
    private fun isIdentPart(c: Char) = c == '_' || c == '$' || c.isLetterOrDigit()

    fun spans(text: String, lang: Lang): List<Span> = if (lang.markup) markup(text) else code(text, lang)

    private fun code(text: String, lang: Lang): List<Span> {
        val out = ArrayList<Span>()
        val n = text.length
        val block = lang.blockComment
        var i = 0
        while (i < n) {
            val ch = text[i]
            if (block != null && text.startsWith(block.first, i)) {
                val e = text.indexOf(block.second, i + block.first.length)
                val end = if (e < 0) n else e + block.second.length
                out.add(Span(i, end, Tok.COMMENT))
                i = end
                continue
            }
            val lc = lang.lineComments.firstOrNull { text.regionMatches(i, it, 0, it.length, ignoreCase = lang.ignoreCase) }
            if (lc != null) {
                var e = text.indexOf('\n', i)
                if (e < 0) e = n
                out.add(Span(i, e, Tok.COMMENT))
                i = e
                continue
            }
            if (lang.quotes.indexOf(ch) >= 0 || lang.multilineQuotes.indexOf(ch) >= 0) {
                if (lang.tripleQuotes && text.startsWith("$ch$ch$ch", i)) {
                    val e = text.indexOf("$ch$ch$ch", i + 3)
                    val end = if (e < 0) n else e + 3
                    out.add(Span(i, end, Tok.STRING))
                    i = end
                    continue
                }
                val multi = lang.multilineQuotes.indexOf(ch) >= 0
                var j = i + 1
                while (j < n) {
                    val d = text[j]
                    if (d == '\\') {
                        j += 2
                        continue
                    }
                    if (d == ch) {
                        j++
                        break
                    }
                    if (d == '\n' && !multi) break
                    j++
                }
                val end = minOf(j, n)
                out.add(Span(i, end, Tok.STRING))
                i = end
                continue
            }
            if (ch.isDigit() && (i == 0 || !isIdentPart(text[i - 1]))) {
                var j = i + 1
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '.' || text[j] == '_')) j++
                out.add(Span(i, j, Tok.NUMBER))
                i = j
                continue
            }
            if (isIdentStart(ch)) {
                var j = i + 1
                while (j < n && isIdentPart(text[j])) j++
                val word = text.substring(i, j)
                if (lang.keywords.isNotEmpty() && (word in lang.keywords || (lang.ignoreCase && word.lowercase() in lang.keywords))) {
                    out.add(Span(i, j, Tok.KEYWORD))
                }
                i = j
                continue
            }
            i++
        }
        return out
    }

    private fun markup(text: String): List<Span> {
        val out = ArrayList<Span>()
        val n = text.length
        var i = 0
        while (i < n) {
            if (text[i] != '<') {
                i++
                continue
            }
            if (text.startsWith("<!--", i)) {
                val e = text.indexOf("-->", i + 4)
                val end = if (e < 0) n else e + 3
                out.add(Span(i, end, Tok.COMMENT))
                i = end
                continue
            }
            if (i + 1 >= n || !(text[i + 1].isLetter() || text[i + 1] == '/' || text[i + 1] == '!' || text[i + 1] == '?')) {
                i++
                continue
            }
            // 開始タグの名前まで
            var j = i + 1
            if (text[j] == '/') j++
            while (j < n && !text[j].isWhitespace() && text[j] != '>' && text[j] != '/') j++
            out.add(Span(i, j, Tok.KEYWORD))
            // 属性の値(引用符の中)と、閉じの >
            var q = 0.toChar()
            var qStart = 0
            while (j < n) {
                val c = text[j]
                if (q != 0.toChar()) {
                    if (c == q) {
                        out.add(Span(qStart, j + 1, Tok.STRING))
                        q = 0.toChar()
                    }
                } else if (c == '"' || c == '\'') {
                    q = c
                    qStart = j
                } else if (c == '>') {
                    break
                }
                j++
            }
            if (q != 0.toChar()) out.add(Span(qStart, minOf(j, n), Tok.STRING))
            if (j < n) {
                val from = if (j > 0 && text[j - 1] == '/') j - 1 else j
                out.add(Span(from, j + 1, Tok.KEYWORD))
            }
            i = minOf(j + 1, n)
        }
        return out
    }

    /** 全体の色の範囲を、行ごとに切り分ける(行をまたぐコメント・文字列は、各行に分けて塗る)。 */
    fun splitLines(text: String, spans: List<Span>): List<HLine> {
        val out = ArrayList<HLine>()
        var lineStart = 0
        var si = 0
        val n = text.length
        while (lineStart <= n) {
            var lineEnd = text.indexOf('\n', lineStart)
            if (lineEnd < 0) lineEnd = n
            var visibleEnd = lineEnd
            if (visibleEnd > lineStart && text[visibleEnd - 1] == '\r') visibleEnd--
            while (si < spans.size && spans[si].end <= lineStart) si++
            val here = ArrayList<Span>()
            var k = si
            while (k < spans.size && spans[k].start < visibleEnd) {
                val s = spans[k]
                val a = maxOf(s.start, lineStart)
                val b = minOf(s.end, visibleEnd)
                if (b > a) here.add(Span(a - lineStart, b - lineStart, s.tok))
                k++
            }
            out.add(HLine(text.substring(lineStart, visibleEnd), here))
            if (lineEnd >= n) break
            lineStart = lineEnd + 1
        }
        return out
    }

    /** 色分けなしの行。 */
    fun plainLines(text: String): List<HLine> {
        val out = ArrayList<HLine>()
        for (l in text.split('\n')) out.add(HLine(l.trimEnd('\r'), emptyList()))
        return out
    }
}
