package local.filemanager

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 1 つの形式(または仲間)。exts は拡張子(ドットなし)。detail は扱い方の補足。 */
internal class FormatRow(val name: String, val exts: List<String>, val detail: String? = null)

internal class FormatGroup(val title: String, val rows: List<FormatRow>)

/** アプリ内で開ける・見られるファイルの形式。書き足したら FormatsTest が、実際に開けるかを確かめる。 */
internal object SupportedFormats {
    /** 言語を切り替えたあとも追従するよう、見るたびに作る(Txt を使うので、値として持たない) */
    val groups: List<FormatGroup> get() = listOf(
        FormatGroup(
            Txt(R.string.formats_ui_001),
            listOf(
                FormatRow(Txt(R.string.formats_ui_002), listOf("jpg", "jpeg", "png", "gif", "webp", "bmp")),
                FormatRow(Txt(R.string.formats_ui_003), listOf("heic", "heif", "avif"), Txt(R.string.formats_ui_004)),
                FormatRow("SVG", listOf("svg"), Txt(R.string.formats_ui_005)),
                FormatRow(Txt(R.string.formats_ui_006), listOf("livp"), Txt(R.string.formats_ui_007)),
            ),
        ),
        FormatGroup(
            Txt(R.string.formats_ui_008),
            listOf(
                FormatRow(
                    Txt(R.string.formats_ui_009), listOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "ts", "m2ts", "mts", "flv", "wmv"),
                    Txt(R.string.formats_ui_010),
                ),
                FormatRow(
                    Txt(R.string.formats_ui_011), listOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma", "aiff", "alac", "caf"),
                    Txt(R.string.formats_ui_012),
                ),
                FormatRow("MIDI", listOf("mid", "midi", "kar", "rmi"), Txt(R.string.formats_ui_013)),
                FormatRow(Txt(R.string.formats_ui_014), listOf("srt", "ass", "ssa", "vtt"), Txt(R.string.formats_ui_015)),
            ),
        ),
        FormatGroup(
            Txt(R.string.formats_ui_016),
            listOf(
                FormatRow("PDF", listOf("pdf"), Txt(R.string.formats_ui_017)),
                FormatRow("Word", listOf("docx", "doc", "dot"), Txt(R.string.formats_ui_018)),
                FormatRow("Excel", listOf("xlsx", "xlsm", "xls", "xlt"), Txt(R.string.formats_ui_019)),
                FormatRow("PowerPoint", listOf("pptx", "ppt", "pps"), Txt(R.string.formats_ui_020)),
                FormatRow("OpenDocument", listOf("odt", "ott", "ods", "ots", "odp", "otp")),
                FormatRow(Txt(R.string.formats_ui_021), listOf("rtf")),
                FormatRow(Txt(R.string.formats_ui_022), listOf("epub")),
                FormatRow(Txt(R.string.formats_ui_023), listOf("cbz", "cbr", "cb7", "cbt"), Txt(R.string.formats_ui_024)),
                FormatRow("Markdown", listOf("md", "markdown")),
                FormatRow(Txt(R.string.formats_ui_025), listOf("csv", "tsv")),
                FormatRow(Txt(R.string.formats_ui_026), listOf("vcf")),
                FormatRow(Txt(R.string.formats_ui_027), listOf("html", "htm", "mht", "mhtml", "webarchive"), Txt(R.string.formats_ui_028)),
                FormatRow(Txt(R.string.formats_ui_029), listOf("json", "jsonl", "ndjson", "xml"), Txt(R.string.formats_ui_030)),
            ),
        ),
        FormatGroup(
            Txt(R.string.formats_ui_031),
            listOf(
                FormatRow(
                    Txt(R.string.formats_ui_032), listOf("txt", "log", "nfo", "ini", "conf", "cfg", "yml", "yaml", "toml", "properties", "tex", "m3u", "m3u8", "url"),
                    Txt(R.string.formats_ui_033),
                ),
                FormatRow(
                    Txt(R.string.formats_ui_034), listOf("kt", "kts", "java", "gradle", "cs", "js", "mjs", "jsx", "tsx", "py", "c", "h", "cpp", "hpp", "cc", "go", "rs", "sh", "bat", "sql", "rb", "lua", "php", "css"),
                    Txt(R.string.formats_ui_035),
                ),
            ),
        ),
        FormatGroup(
            Txt(R.string.formats_ui_036),
            listOf(
                FormatRow(Txt(R.string.formats_ui_037), listOf("dxf", "jww", "jwc"), Txt(R.string.formats_ui_038)),
                FormatRow("draw.io", listOf("drawio")),
                FormatRow(Txt(R.string.formats_ui_039), listOf("stl", "obj", "ply", "off", "3mf"), Txt(R.string.formats_ui_040)),
            ),
        ),
        FormatGroup(
            Txt(R.string.formats_ui_041),
            listOf(
                FormatRow("ZIP", listOf("zip", "jar"), Txt(R.string.formats_ui_042)),
                FormatRow(Txt(R.string.formats_ui_043), listOf("7z", "rar"), Txt(R.string.formats_ui_044)),
                FormatRow(Txt(R.string.formats_ui_045), listOf("tar", "tar.gz", "tgz", "tar.bz2", "tbz2", "tbz", "tar.xz", "txz")),
                FormatRow(Txt(R.string.formats_ui_046), listOf("gz", "bz2", "xz")),
                FormatRow("LZH", listOf("lzh", "lha"), Txt(R.string.formats_ui_047)),
                FormatRow(Txt(R.string.formats_ui_048), listOf("apk", "apks", "xapk", "apkm"), Txt(R.string.formats_ui_049)),
                FormatRow(Txt(R.string.formats_ui_050), listOf("ipa"), Txt(R.string.formats_ui_051)),
            ),
        ),
        FormatGroup(
            Txt(R.string.formats_ui_052),
            listOf(
                FormatRow("iWork", listOf("pages", "numbers", "key", "keynote"), Txt(R.string.formats_ui_053)),
                FormatRow(Txt(R.string.formats_ui_054), listOf("pkpass")),
                FormatRow(Txt(R.string.formats_ui_055), listOf("plist", "mobileconfig", "mobileprovision")),
                FormatRow(Txt(R.string.formats_ui_056), listOf("sqlite", "sqlite3", "db", "db3", "s3db", "sl3"), Txt(R.string.formats_ui_057)),
                FormatRow(Txt(R.string.formats_ui_058), listOf("ttf", "otf", "ttc"), Txt(R.string.formats_ui_059)),
            ),
        ),
    )

    /** 載せている拡張子すべて(試験用)。 */
    val extensions: Set<String> by lazy { groups.flatMap { g -> g.rows.flatMap { it.exts } }.toSet() }
}

/** 設定の中の「対応しているファイル形式」の画面。 */
@Composable
internal fun FormatsPage() {
    val pal = LocalPal.current
    val shape = RoundedCornerShape(10.dp)
    LazyColumn(
        Modifier.fillMaxSize().background(pal.background),
        contentPadding = PaddingValues(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Text(
                Txt(R.string.formats_ui_060),
                Modifier.padding(start = 6.dp, top = 2.dp, bottom = 4.dp), fontSize = 13.sp, color = pal.subText,
            )
        }
        items(SupportedFormats.groups) { g ->
            Text(g.title, Modifier.padding(start = 6.dp, top = 8.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = pal.accent)
            Column(Modifier.fillMaxWidth().clip(shape).background(pal.surface).border(BorderStroke(1.dp, pal.divider), shape)) {
                g.rows.forEachIndexed { i, r ->
                    if (i > 0) HorizontalDivider(color = pal.divider)
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
                        Text(r.name, fontSize = 15.sp, color = pal.text)
                        Spacer(Modifier.height(2.dp))
                        Text(r.exts.joinToString("  ") { ".$it" }, fontSize = 13.sp, color = pal.accent)
                        if (r.detail != null) {
                            Spacer(Modifier.height(2.dp))
                            Text(r.detail, fontSize = 12.sp, color = pal.subText)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}