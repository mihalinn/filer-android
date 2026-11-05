package local.filemanager

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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

    /** 言語を切り替えたあとも追従するよう、見るたびに作る(Txt を使うので、値として持たない) */
    val list: List<Credit> get() = listOf(
        Credit(
            Txt(R.string.about_ui_001), "GNU GPL v3",
            Txt(R.string.credits_app_note, COPYRIGHT), "gpl-3.0.txt",
        ),
        Credit(
            Txt(R.string.about_ui_002), "GNU GPL v3",
            Txt(R.string.about_ui_003),
            "gpl-3.0.txt",
        ),
        Credit(
            Txt(R.string.about_ui_004), Txt(R.string.about_ui_005),
            Txt(R.string.about_ui_006),
            "lgpl-3.0.txt",
        ),
        Credit(
            Txt(R.string.about_ui_007), Txt(R.string.about_ui_008),
            Txt(R.string.about_ui_009), "libvpx.txt",
        ),
        Credit(Txt(R.string.about_ui_010), "Apache License 2.0", null, "apache-2.0.txt"),
        Credit(
            Txt(R.string.about_ui_011), "Apache License 2.0",
            Txt(R.string.about_ui_012), "apache-2.0.txt",
        ),
        Credit(
            Txt(R.string.about_ui_013), "Apache License 2.0",
            Txt(R.string.about_ui_014), "apache-2.0.txt",
        ),
        Credit(
            Txt(R.string.about_ui_015), "Apache License 2.0",
            Txt(R.string.about_ui_016), "apache-2.0.txt",
        ),
        Credit(Txt(R.string.about_ui_017), "Apache License 2.0", Txt(R.string.about_ui_018), "apache-2.0.txt"),
        Credit("Mbassador 1.3.0", Txt(R.string.about_ui_019), Txt(R.string.about_ui_020), "mbassador.txt"),
        Credit(
            "Apache Commons Compress 1.27.1", "Apache License 2.0",
            Txt(R.string.about_ui_021),
            "commons-notice.txt",
        ),
        Credit(
            Txt(R.string.about_ui_022), "Apache License 2.0",
            Txt(R.string.about_ui_023),
            "pdfium-notice.txt",
        ),
        Credit("AndroidSVG 1.4", "Apache License 2.0", null, "apache-2.0.txt"),
        Credit("Bouncy Castle 1.79", Txt(R.string.about_ui_024), null, "bc.txt"),
        Credit(Txt(R.string.about_ui_025), Txt(R.string.about_ui_026), null, "slf4j.txt"),
        Credit("XZ for Java 1.10", Txt(R.string.about_ui_027), null, "xz.txt"),
        Credit(
            Txt(R.string.about_ui_028), Txt(R.string.about_ui_029),
            Txt(R.string.about_ui_030), "unrar.txt",
        ),
        Credit(
            Txt(R.string.about_ui_031), Txt(R.string.about_ui_032),
            Txt(R.string.about_ui_033), "ezjww.txt",
        ),
    )
}

/**
 * 異常終了の記録の画面。新しい順に 1 件ずつ見せる(日時・種類・内容)。内容はコピーできる。
 * 開いた時点で「見た」ことにする(起動時のお知らせは、同じ記録では 2 度出ない)。「消去」を押したら onCleared を呼ぶ。
 */
@Composable
internal fun CrashLogDialog(onCleared: () -> Unit, dismiss: () -> Unit) {
    val ctx = LocalContext.current
    val pal = LocalPal.current
    val entries = remember { CrashLog.entries(ctx) }
    LaunchedEffect(Unit) { CrashLog.markSeen(ctx) }
    val full = remember(entries) { entries.joinToString("\n\n") { "【${CrashLog.stamp(it.time)}  ${it.kind}】\n${it.body}".trimEnd() } }
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    var confirm by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(Txt(R.string.about_ui_034)) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                if (entries.isEmpty()) {
                    Text(Txt(R.string.about_ui_035), fontSize = 14.sp, color = pal.subText)
                } else {
                    Text(
                        Txt(R.string.about_ui_036),
                        fontSize = 12.sp, color = pal.subText,
                    )
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                            Spacer(Modifier.height(0.dp))
                            entries.forEach { e ->
                                Column(Modifier.fillMaxWidth().clip(shape).background(pal.surface).border(1.dp, pal.divider, shape).padding(10.dp)) {
                                    Text(e.kind, fontSize = 14.sp, color = pal.text, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                    Text(CrashLog.stamp(e.time), fontSize = 12.sp, color = pal.subText)
                                    if (e.body.isNotBlank()) {
                                        Spacer(Modifier.height(6.dp))
                                        Text(e.body.trim(), fontSize = 10.sp, lineHeight = 13.sp, fontFamily = FontFamily.Monospace, color = pal.text)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(Txt(R.string.about_ui_037)) } },
        dismissButton = {
            if (entries.isNotEmpty()) {
                Row {
                    TextButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(full)) }) { Text(Txt(R.string.about_ui_038)) }
                    TextButton(onClick = { confirm = true }) { Text(Txt(R.string.about_ui_039)) }
                }
            }
        },
    )
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(Txt(R.string.about_ui_040)) },
            text = { Text(Txt(R.string.about_ui_041)) },
            confirmButton = { TextButton(onClick = { CrashLog.clear(ctx); onCleared(); dismiss() }) { Text(Txt(R.string.about_ui_042)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(Txt(R.string.about_ui_043)) } },
        )
    }
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
    val text by produceState(Txt(R.string.about_ui_044), c.textFile) {
        value = withContext(Dispatchers.IO) {
            runCatching { ctx.assets.open("licenses/${c.textFile}").use { it.readBytes().toString(Charsets.UTF_8) } }
                .getOrElse { Txt(R.string.about_ui_045) }
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