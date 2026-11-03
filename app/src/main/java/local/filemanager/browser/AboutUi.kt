package local.filemanager

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** このアプリが使っているもの1つ分。textFile は assets/licenses の中のライセンス全文。 */
internal class Credit(val name: String, val license: String, val note: String?, val textFile: String)

internal object Credits {
    /** ソースコードの置き場所(公開したら入れる。空なら表示しない) */
    const val SOURCE_URL = "https://github.com/mihalinn/filer-android"

    /** このアプリ自身の著作権表示 */
    const val COPYRIGHT = "Copyright (C) 2026 mihalin"

    val list: List<Credit> = listOf(
        Credit(
            "ファイラー(このアプリ)", "GNU GPL v3",
            "$COPYRIGHT。NextLib(GPL v3)を使っているため、アプリ全体も GPL v3 で提供しています。無保証です。", "gpl-3.0.txt",
        ),
        Credit(
            "NextLib 1.7.1-0.9.0(動画・音声の拡張)", "GNU GPL v3",
            "libmedia3ext.so は、インターレース解除を加えて作り直したものです。変更内容とソースは native フォルダにあります。元のプロジェクト: github.com/anilbeesetti/nextlib",
            "gpl-3.0.txt",
        ),
        Credit(
            "FFmpeg 6.0(NextLib に同梱)", "GNU LGPL v3 以降",
            "動的ライブラリ(.so)として同梱しています。差し替えもできます。ソース: ffmpeg.org",
            "lgpl-3.0.txt",
        ),
        Credit(
            "libvpx 1.13.0(VP8・VP9 の読み取り用。FFmpeg に組み込まれています)", "BSD 3-Clause ライセンス",
            "著作権: The WebM Project authors。libavcodec.so に組み込まれています。", "libvpx.txt",
        ),
        Credit("AndroidX Media3 1.7.1(ExoPlayer)", "Apache License 2.0", null, "apache-2.0.txt"),
        Credit(
            "Jetpack Compose・Material 3・AndroidX", "Apache License 2.0",
            "Activity、Core、Lifecycle、ExifInterface を含みます。画面のアイコンは Material Icons(Google)です。", "apache-2.0.txt",
        ),
        Credit(
            "Kotlin 標準ライブラリ 1.9.22・kotlinx.coroutines 1.7.1", "Apache License 2.0",
            "JetBrains annotations(Apache 2.0)を含みます", "apache-2.0.txt",
        ),
        Credit(
            "Guava 33.3.1(Android 版)", "Apache License 2.0",
            "Media3 が使っています。failureaccess 1.0.2・listenablefuture 1.0・Error Prone annotations 2.38.0(いずれも Apache 2.0)を含みます。", "apache-2.0.txt",
        ),
        Credit("smbj 0.13.0(NAS への接続)", "Apache License 2.0", "同じ作者の asn-one 0.6.0(Apache 2.0)も使っています", "apache-2.0.txt"),
        Credit("Mbassador 1.3.0", "MIT ライセンス", "smbj が使っています。作者: Benjamin Diedrichsen。", "mbassador.txt"),
        Credit(
            "Apache Commons Compress 1.27.1", "Apache License 2.0",
            "7z・tar などを読むために使っています。Commons Codec 1.17.1・IO 2.16.1・Lang 3.16.0 も含みます。押すと著作権表示(NOTICE)を表示します。",
            "commons-notice.txt",
        ),
        Credit("AndroidSVG 1.4", "Apache License 2.0", null, "apache-2.0.txt"),
        Credit("Bouncy Castle 1.79", "MIT ライセンス", null, "bc.txt"),
        Credit("SLF4J 2.0.17(API)・2.0.9(nop)", "MIT ライセンス", null, "slf4j.txt"),
        Credit("XZ for Java 1.10", "0BSD(ほぼパブリックドメイン)", null, "xz.txt"),
        Credit(
            "junrar 8.1.2(RAR の読み取り)", "UnRAR ライセンス",
            "RAR を読むためだけに使っています。RAR と互換の圧縮ソフトを作る目的には使えません。", "unrar.txt",
        ),
        Credit(
            "ezjww(JW_CAD の形式の参考)", "MIT ライセンス",
            ".jww の構造は、公開されている解析(github.com/monozukuri-ai/ezjww)を参考にしました。読み込みの処理は自作です。", "ezjww.txt",
        ),
    )
}

/** 前回の異常終了の記録。コピーできる。「消去」を押したら onCleared を呼ぶ。 */
@Composable
internal fun CrashLogDialog(onCleared: () -> Unit, dismiss: () -> Unit) {
    val ctx = LocalContext.current
    val crashText = remember { CrashLog.read(ctx) }
    val exits = remember { CrashLog.abnormalExits(ctx) }
    val full = buildString {
        crashText?.let { append("【アプリの例外】\n").append(it).append("\n\n") }
        if (exits.isNotEmpty()) append("【OS の記録】\n").append(exits.joinToString("\n\n"))
    }
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("異常終了の記録") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("異常終了の原因の記録です。コピーできます。",fontSize = 12.sp, color = LocalPal.current.subText)
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(full, fontSize = 10.sp, lineHeight = 13.sp, fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("閉じる") } },
        dismissButton = {
            Row {
                TextButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(full)) }) { Text("コピー") }
                TextButton(onClick = { CrashLog.clear(ctx); onCleared(); dismiss() }) { Text("消去") }
            }
        },
    )
}

/** ライセンスの名前の小さなラベル。 */
@Composable
internal fun LicenseTag(text: String) {
    val pal = LocalPal.current
    Text(
        text, fontSize = 12.sp, color = pal.accent,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(pal.tileBg).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** ライセンス全文の画面(一覧の上に重ねて出す)。 */
@Composable
internal fun LicenseDetail(c: Credit) {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val text by produceState("読み込み中…", c.textFile) {
        value = withContext(Dispatchers.IO) {
            runCatching { ctx.assets.open("licenses/${c.textFile}").use { it.readBytes().toString(Charsets.UTF_8) } }
                .getOrElse { "ライセンスの全文を読み込めませんでした" }
        }
    }
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxSize().background(pal.background).verticalScroll(rememberScrollState()).padding(10.dp)) {
        Text(c.name, fontSize = 16.sp, color = pal.text)
        Spacer(Modifier.height(6.dp))
        LicenseTag(c.license)
        if (c.note != null) {
            Spacer(Modifier.height(6.dp))
            Text(c.note, fontSize = 12.sp, color = pal.subText)
        }
        Spacer(Modifier.height(10.dp))
        val blocks = remember(text) { reflowLicense(text) }
        Column(Modifier.fillMaxWidth().clip(shape).background(pal.surface).border(1.dp, pal.divider, shape).padding(12.dp)) {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    blocks.forEach { b ->
                        val heading = b.length <= 60 && b.any { it.isLetter() } && b.none { it.isLowerCase() }
                        Text(
                            b, fontSize = 13.sp, lineHeight = 20.sp, color = pal.text,
                            fontWeight = if (heading) androidx.compose.ui.text.font.FontWeight.Bold else null,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

private val licenseItem = Regex("""^\s*(\d+[.)]|\([a-zA-Z0-9]+\)|[a-zA-Z][.)]|[-*•])\s""")

/**
 * ライセンス全文は 70 文字ほどで改行されているので、そのまま出すと幅の違う画面で行が不揃いになる。
 * 空行までのひとかたまりを 1 行につなぎ、箇条書きや見出しの行は別のかたまりにして、画面の幅で折り返せるようにする。
 */
internal fun reflowLicense(raw: String): List<String> {
    val out = ArrayList<String>()
    val cur = StringBuilder()
    var prevIndent = 0
    fun flush() { if (cur.isNotEmpty()) { out.add(cur.toString()); cur.setLength(0) } }
    for (line in raw.replace("\r\n", "\n").split("\n")) {
        val t = line.trim()
        if (t.isEmpty()) { flush(); continue }
        if (t.all { it == '-' || it == '=' || it == '*' || it == '_' || it == '#' }) { flush(); continue }
        val indent = line.length - line.trimStart().length
        val startsNew = licenseItem.containsMatchIn(line) || Math.abs(indent - prevIndent) >= 3
        if (startsNew) flush()
        if (cur.isNotEmpty()) cur.append(' ')
        cur.append(t)
        prevIndent = indent
    }
    flush()
    return out
}