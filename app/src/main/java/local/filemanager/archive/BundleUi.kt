package local.filemanager

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------- .xapk / .apkm / .apks(まとめのAPK)

private class BundleView(
    val meta: BundleMeta, val plan: BundlePlan, val icon: Bitmap?, val installed: InstalledApp?,
    val skippedBytes: Long, val abis: List<String>,
)

private sealed interface BundleLoad {
    data object Busy : BundleLoad
    data class Ok(val v: BundleView) : BundleLoad
    data class Fail(val message: String) : BundleLoad
}

private fun loadBundle(ctx: Context, fs: Fs, item: FileItem): BundleLoad {
    val src = try {
        openSource(fs, item.path)
    } catch (e: Exception) {
        null
    } ?: return BundleLoad.Fail("このファイルを開けません")
    return try {
        val r = ZipReader(src)
        val abis = Build.SUPPORTED_ABIS.toList()
        val plan = ApkBundle.plan(r.entries, abis)
        val meta = ApkBundle.meta(item.name, r.entries) { e ->
            if (e.size > 1024 * 1024) null else runCatching { r.open(e).use { String(it.readBytes(), Charsets.UTF_8) } }.getOrNull()
        }
        val icon = meta.iconEntry?.takeIf { it.size <= 2L * 1024 * 1024 }
            ?.let { e -> runCatching { r.open(e).use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull() }
        val installed = meta.packageName?.let { pkg ->
            try {
                val ip = ctx.packageManager.getPackageInfo(pkg, 0)
                InstalledApp(ip.versionName ?: "", ip.longVersionCode, emptyList())
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }
        BundleLoad.Ok(BundleView(meta, plan, icon, installed, plan.skipped.sumOf { it.entry.size }, abis))
    } catch (e: Exception) {
        BundleLoad.Fail(e.message ?: "ZIPとして読めません")
    } finally {
        src.close()
    }
}

/**
 * .xapk / .apkm / .apks の詳細。アプリ名・版・入れる部品(この端末に合うものだけ)を見せ、まとめてインストールする。
 * 中のAPKを個別に見たいときは onList で、ZIPと同じ一覧に切り替える。
 */
@Composable
internal fun BundlePane(fs: Fs, item: FileItem, onInstall: () -> Unit, onList: () -> Unit) {
    val ctx = LocalContext.current
    val load = produceState<BundleLoad>(BundleLoad.Busy, item.path) {
        value = withContext(Dispatchers.IO) { loadBundle(ctx, fs, item) }
    }.value
    when (load) {
        BundleLoad.Busy -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Pv.text)
        }
        is BundleLoad.Fail -> Box(Modifier.fillMaxSize()) {
            Text(load.message, color = Pv.text, modifier = Modifier.align(Alignment.Center).padding(32.dp))
        }
        is BundleLoad.Ok -> BundleDetail(load.v, item, onInstall, onList)
    }
}

@Composable
private fun BundleDetail(v: BundleView, item: FileItem, onInstall: () -> Unit, onList: () -> Unit) {
    val m = v.meta
    val p = v.plan
    val cmp = if (m.versionCode != null && v.installed != null) ApkFormat.compare(m.versionCode, v.installed.versionCode) else null
    val (status, statusColor) = when {
        v.installed == null -> (if (m.packageName == null) "いま入っているかは分かりません" else "まだインストールされていません(新しくインストールします)") to Pv.neutral
        cmp == ApkFormat.Compare.NEWER -> "更新できます(いまの版: ${v.installed.versionName})" to Pv.ok
        cmp == ApkFormat.Compare.SAME -> "同じ版が入っています(${v.installed.versionName})" to Pv.caution
        cmp == ApkFormat.Compare.OLDER -> "新しい版がインストールされています(${v.installed.versionName})。古い版にするには、先にアンインストールが必要です" to Pv.bad
        else -> "すでに入っています(${v.installed.versionName})" to Pv.caution
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().belowBars(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 150.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                    if (v.icon != null) Image(v.icon.asImageBitmap(), null, Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)))
                    Column(Modifier.padding(start = if (v.icon != null) 14.dp else 0.dp)) {
                        Text(m.name ?: item.name, color = Pv.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        if (m.packageName != null) Text(m.packageName, color = rowDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                InfoRow("形式", "${m.format}(本体と部品をまとめたAPK)")
                if (m.versionName != null) InfoRow("バージョン", m.versionName + (m.versionCode?.let { "($it)" } ?: ""))
                if (m.minSdk != null) InfoRow("対応する Android", "${ApkFormat.sdkText(m.minSdk)} 以上")
                InfoRow("ファイル", "${FileOps.formatSize(item.size)}・${FileOps.formatDate(item.modified)}")
                InfoRow("いまの状態", status, statusColor)
            }
            item {
                Text(
                    "入れるもの", color = Pv.text, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                if (p.base == null) {
                    Text("本体のAPKが見つかりません。このファイルはインストールできません", color = Pv.bad, fontSize = 13.sp)
                } else {
                    InfoRow("本体", "1 本(${FileOps.formatSize(p.base.entry.size)})")
                    val parts = listOfNotNull(
                        p.count(SplitKind.DPI).takeIf { it > 0 }?.let { "画面用 $it 本" },
                        p.count(SplitKind.LANG).takeIf { it > 0 }?.let { "言語用 $it 本" },
                        p.count(SplitKind.OTHER).takeIf { it > 0 }?.let { "そのほか $it 本" },
                    )
                    if (parts.isNotEmpty()) InfoRow("部品", parts.joinToString("・"))
                    InfoRow("CPU用", p.abi?.let { "$it(この端末に合うもの)" } ?: if (p.abiMismatch) "この端末に合う部品がありません" else "なし(CPU共通)")
                    InfoRow("合計", "${p.chosen.size} 本・${FileOps.formatSize(p.totalBytes)}")
                }
                if (p.skipped.isNotEmpty()) {
                    val names = p.skipped.map { ApkBundle.abiDisplay(it.tag) }.distinct().joinToString("、")
                    InfoRow("入れないもの", "$names(この端末には要らない・${p.skipped.size} 本・${FileOps.formatSize(v.skippedBytes)})", rowDim)
                }
                if (p.abiMismatch) {
                    Text("この端末(${v.abis.joinToString("・")})に合う部品がないため、インストールしても動きません", color = Pv.bad, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                }
                if (m.hasObb) {
                    Text(
                        "追加データ(OBB)はインストールされません。必要なら、「中身の一覧を見る」から取り出してください",
                        color = Pv.caution, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Button(
                onClick = onInstall, enabled = p.base != null && !p.abiMismatch,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
            ) { Text(if (cmp == ApkFormat.Compare.NEWER) "更新する" else "インストール") }
            TextButton(onClick = onList) { Text("中身の一覧を見る") }
        }
    }
}
