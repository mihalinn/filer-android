package local.filemanager

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
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
    val inst = v.installed
    val cmp = if (m.versionCode != null && inst != null) ApkFormat.compare(m.versionCode, inst.versionCode) else null
    val instText = inst?.let { "${it.versionName}(${it.versionCode})" }
    val thisText = m.versionName?.let { it + (m.versionCode?.let { c -> "($c)" } ?: "") }
    val title: String
    val detail: String?
    val statusColor: Color
    when {
        inst == null -> {
            title = if (m.packageName == null) "いま入っているかは分かりません" else "新しくインストールします"
            detail = null; statusColor = Pv.neutral
        }
        cmp == ApkFormat.Compare.NEWER -> {
            title = "更新できます"
            detail = "いまのバージョン $instText" + (thisText?.let { " → このバージョン $it" } ?: "")
            statusColor = Pv.ok
        }
        cmp == ApkFormat.Compare.SAME -> { title = "同じバージョンが入っています"; detail = "バージョン $instText"; statusColor = Pv.caution }
        cmp == ApkFormat.Compare.OLDER -> {
            title = "新しいバージョンが入っています"
            detail = "いまのバージョン $instText。古いバージョンにするには、先にアンインストールが必要です"
            statusColor = Pv.bad
        }
        else -> { title = "すでに入っています"; detail = "バージョン $instText"; statusColor = Pv.caution }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().belowBars(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 150.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { AppHeader(v.icon, m.name ?: item.name, m.versionName?.let { "バージョン $it" }, m.packageName) }
            item { StatusBox(title, detail, statusColor) }
            item {
                ApkCard("情報") {
                    CardRow("形式", "${m.format}(本体と部品をまとめたAPK)")
                    if (m.versionName != null) CardRow("バージョン", m.versionName + (m.versionCode?.let { "($it)" } ?: ""))
                    if (m.minSdk != null) CardRow("対応する Android", "${ApkFormat.sdkText(m.minSdk)} 以上")
                    CardRow("ファイル", "${FileOps.formatSize(item.size)}・${FileOps.formatDate(item.modified)}")
                }
            }
            item {
                ApkCard("入れるもの") {
                    if (p.base == null) {
                        Text("本体のAPKが見つかりません。このファイルはインストールできません", color = Pv.bad, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    } else {
                        CardRow("本体", "1 本(${FileOps.formatSize(p.base.entry.size)})")
                        val parts = listOfNotNull(
                            p.count(SplitKind.DPI).takeIf { it > 0 }?.let { "画面用 $it 本" },
                            p.count(SplitKind.LANG).takeIf { it > 0 }?.let { "言語用 $it 本" },
                            p.count(SplitKind.OTHER).takeIf { it > 0 }?.let { "そのほか $it 本" },
                        )
                        if (parts.isNotEmpty()) CardRow("部品", parts.joinToString("・"))
                        CardRow("CPU用", p.abi?.let { "$it(この端末に合うもの)" } ?: if (p.abiMismatch) "この端末に合う部品がありません" else "なし(CPU共通)")
                        CardRow("合計", "${p.chosen.size} 本・${FileOps.formatSize(p.totalBytes)}")
                    }
                    if (p.skipped.isNotEmpty()) {
                        val names = p.skipped.map { ApkBundle.abiDisplay(it.tag) }.distinct().joinToString("、")
                        CardRow("入れないもの", "$names(この端末には要らない・${p.skipped.size} 本・${FileOps.formatSize(v.skippedBytes)})", rowDim)
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            if (p.abiMismatch) {
                item { StatusBox("インストールしても動きません", "この端末(${v.abis.joinToString("・")})に合う部品がありません", Pv.bad) }
            }
            if (m.hasObb) {
                item { StatusBox("追加データ(OBB)は入りません", "必要なら、「中身の一覧を見る」から取り出してください", Pv.caution) }
            }
        }
        InstallBar(
            if (cmp == ApkFormat.Compare.NEWER) "更新する" else "インストール",
            p.base != null && !p.abiMismatch, onInstall, Modifier.align(Alignment.BottomCenter),
            secondary = "中身の一覧を見る" to onList,
        )
    }
}