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
    val groups: List<FormatGroup> = listOf(
        FormatGroup(
            "画像",
            listOf(
                FormatRow("JPEG・PNG・GIF・WebP・BMP", listOf("jpg", "jpeg", "png", "gif", "webp", "bmp")),
                FormatRow("HEIC・HEIF・AVIF", listOf("heic", "heif", "avif"), "端末が対応している場合に表示します"),
                FormatRow("SVG", listOf("svg"), "外部のファイルや通信は使いません"),
                FormatRow("ライブフォト", listOf("livp"), "zip として中身を見ます"),
            ),
        ),
        FormatGroup(
            "動画・音声",
            listOf(
                FormatRow(
                    "動画", listOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "ts", "m2ts", "mts", "flv", "wmv"),
                    "再生できるかは、端末と中身のコーデックによります。端末で再生できない形式は FFmpeg で再生を試みます",
                ),
                FormatRow(
                    "音声", listOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma", "aiff", "alac", "caf"),
                    "曲名やジャケット画像などの情報も表示します",
                ),
                FormatRow("MIDI", listOf("mid", "midi", "kar", "rmi"), "端末の機能で再生します"),
                FormatRow("字幕", listOf("srt", "ass", "ssa", "vtt"), "動画に付けて表示できます"),
            ),
        ),
        FormatGroup(
            "文書",
            listOf(
                FormatRow("PDF", listOf("pdf"), "パスワード付きは開けません"),
                FormatRow("Word", listOf("docx", "doc", "dot"), "古い形式(doc)も読めます"),
                FormatRow("Excel", listOf("xlsx", "xlsm", "xls", "xlt"), "古い形式(xls)も読めます"),
                FormatRow("PowerPoint", listOf("pptx", "ppt", "pps"), "スライドを絵にして表示します"),
                FormatRow("OpenDocument", listOf("odt", "ott", "ods", "ots", "odp", "otp")),
                FormatRow("リッチテキスト", listOf("rtf")),
                FormatRow("電子書籍", listOf("epub")),
                FormatRow("マンガ", listOf("cbz", "cbr", "cb7", "cbt"), "ページをめくって読めます"),
                FormatRow("Markdown", listOf("md", "markdown")),
                FormatRow("表", listOf("csv", "tsv")),
                FormatRow("連絡先", listOf("vcf")),
                FormatRow("Web ページ", listOf("html", "htm", "mht", "mhtml", "webarchive"), "外部への通信は止めて表示します"),
                FormatRow("JSON・XML", listOf("json", "jsonl", "ndjson", "xml"), "整形して色分けします"),
            ),
        ),
        FormatGroup(
            "テキスト・コード",
            listOf(
                FormatRow(
                    "文字のファイル", listOf("txt", "log", "nfo", "ini", "conf", "cfg", "yml", "yaml", "toml", "properties", "tex", "m3u", "m3u8", "url"),
                    "文字コードを自動で判定します。編集して保存もできます",
                ),
                FormatRow(
                    "ソースコード", listOf("kt", "kts", "java", "gradle", "cs", "js", "mjs", "jsx", "tsx", "py", "c", "h", "cpp", "hpp", "cc", "go", "rs", "sh", "bat", "sql", "rb", "lua", "php", "css"),
                    "キーワード・文字列・コメントを色分けします",
                ),
            ),
        ),
        FormatGroup(
            "図面・3D",
            listOf(
                FormatRow("CAD の図面", listOf("dxf", "jww", "jwc"), "DWG は形式が公開されていないため表示できません。DXF に書き出すと見られます"),
                FormatRow("draw.io", listOf("drawio")),
                FormatRow("3D モデル", listOf("stl", "obj", "ply", "off", "3mf"), "指で回して見られます"),
            ),
        ),
        FormatGroup(
            "書庫・アプリ",
            listOf(
                FormatRow("ZIP", listOf("zip", "jar"), "中身の一覧を見て、1 つずつ取り出せます。圧縮もできます"),
                FormatRow("7z・RAR", listOf("7z", "rar"), "パスワード付きは開けないことがあります"),
                FormatRow("tar 系", listOf("tar", "tar.gz", "tgz", "tar.bz2", "tbz2", "tbz", "tar.xz", "txz")),
                FormatRow("単体の圧縮", listOf("gz", "bz2", "xz")),
                FormatRow("LZH", listOf("lzh", "lha"), "-lh5- など一般的な方式に対応しています。一部の古い方式は取り出せません"),
                FormatRow("Android アプリ", listOf("apk", "apks", "xapk", "apkm"), "中身の確認と、インストールができます"),
                FormatRow("iPhone アプリ", listOf("ipa"), "中身を見るだけです"),
            ),
        ),
        FormatGroup(
            "iPhone の書類・データ",
            listOf(
                FormatRow("iWork", listOf("pages", "numbers", "key", "keynote"), "本文とプレビュー画像を見られます"),
                FormatRow("Wallet のパス", listOf("pkpass")),
                FormatRow("設定ファイル", listOf("plist", "mobileconfig", "mobileprovision")),
                FormatRow("データベース", listOf("sqlite", "sqlite3", "db", "db3", "s3db", "sl3"), "表を見られます。読み取りだけの検索(SELECT)もできます"),
                FormatRow("フォント", listOf("ttf", "otf", "ttc"), "見本の文字で表示できます"),
            ),
        ),
    )

    /** 載せている拡張子すべて(試験用)。 */
    val extensions: Set<String> = groups.flatMap { g -> g.rows.flatMap { it.exts } }.toSet()
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
                "アプリの中で開ける形式です。拡張子がなくても、中身から文字のファイルなどを判断します。ここにない形式は「他のアプリで開く」で開けます。",
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