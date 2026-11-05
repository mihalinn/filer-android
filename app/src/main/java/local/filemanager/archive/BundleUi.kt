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
    } ?: return BundleLoad.Fail(Txt(R.string.bundle_ui_001))
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
        BundleLoad.Fail(e.message ?: Txt(R.string.bundle_ui_002))
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
            title = if (m.packageName == null) Txt(R.string.bundle_ui_003) else Txt(R.string.bundle_ui_004)
            detail = null; statusColor = Pv.neutral
        }
        cmp == ApkFormat.Compare.NEWER -> {
            title = Txt(R.string.bundle_ui_005)
            detail = Txt(R.string.bundle_ui_006, instText) + (thisText?.let { Txt(R.string.bundle_ui_007, it) } ?: "")
            statusColor = Pv.ok
        }
        cmp == ApkFormat.Compare.SAME -> { title = Txt(R.string.bundle_ui_008); detail = Txt(R.string.bundle_ui_009, instText); statusColor = Pv.caution }
        cmp == ApkFormat.Compare.OLDER -> {
            title = Txt(R.string.bundle_ui_010)
            detail = Txt(R.string.bundle_ui_011, instText)
            statusColor = Pv.bad
        }
        else -> { title = Txt(R.string.bundle_ui_012); detail = Txt(R.string.bundle_ui_013, instText); statusColor = Pv.caution }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().belowBars(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 150.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { AppHeader(v.icon, m.name ?: item.name, m.versionName?.let { Txt(R.string.bundle_ui_014, it) }, m.packageName) }
            item { StatusBox(title, detail, statusColor) }
            item {
                ApkCard(Txt(R.string.bundle_ui_015)) {
                    CardRow(Txt(R.string.bundle_ui_016), Txt(R.string.bundle_ui_017, m.format))
                    if (m.versionName != null) CardRow(Txt(R.string.bundle_ui_018), m.versionName + (m.versionCode?.let { "($it)" } ?: ""))
                    if (m.minSdk != null) CardRow(Txt(R.string.bundle_ui_019), Txt(R.string.bundle_ui_020, ApkFormat.sdkText(m.minSdk)))
                    CardRow(Txt(R.string.bundle_ui_021), Txt(R.string.bundle_ui_022, FileOps.formatSize(item.size), FileOps.formatDate(item.modified)))
                }
            }
            item {
                ApkCard(Txt(R.string.bundle_ui_023)) {
                    if (p.base == null) {
                        Text(Txt(R.string.bundle_ui_024), color = Pv.bad, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    } else {
                        CardRow(Txt(R.string.bundle_ui_025), Txt(R.string.bundle_ui_026, FileOps.formatSize(p.base.entry.size)))
                        val parts = listOfNotNull(
                            p.count(SplitKind.DPI).takeIf { it > 0 }?.let { Txt.n(R.plurals.bundle_part_dpi, it) },
                            p.count(SplitKind.LANG).takeIf { it > 0 }?.let { Txt.n(R.plurals.bundle_part_lang, it) },
                            p.count(SplitKind.OTHER).takeIf { it > 0 }?.let { Txt.n(R.plurals.bundle_part_other, it) },
                        )
                        if (parts.isNotEmpty()) CardRow(Txt(R.string.bundle_ui_027), parts.joinToString(Txt(R.string.sep_dot)))
                        CardRow(Txt(R.string.bundle_ui_028), p.abi?.let { Txt(R.string.bundle_ui_029, it) } ?: if (p.abiMismatch) Txt(R.string.bundle_ui_030) else Txt(R.string.bundle_ui_031))
                        CardRow(Txt(R.string.bundle_ui_032), Txt.n(R.plurals.bundle_total, p.chosen.size, FileOps.formatSize(p.totalBytes)))
                    }
                    if (p.skipped.isNotEmpty()) {
                        val names = p.skipped.map { ApkBundle.abiDisplay(it.tag) }.distinct().joinToString(Txt(R.string.bundle_ui_033))
                        CardRow(Txt(R.string.bundle_ui_034), Txt.n(R.plurals.bundle_skipped, p.skipped.size, names, FileOps.formatSize(v.skippedBytes)), rowDim)
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            if (p.abiMismatch) {
                item { StatusBox(Txt(R.string.bundle_ui_035), Txt(R.string.bundle_ui_036, v.abis.joinToString(Txt(R.string.sep_dot))), Pv.bad) }
            }
            if (m.hasObb) {
                item { StatusBox(Txt(R.string.bundle_ui_037), Txt(R.string.bundle_ui_038), Pv.caution) }
            }
        }
        InstallBar(
            if (cmp == ApkFormat.Compare.NEWER) Txt(R.string.bundle_ui_039) else Txt(R.string.bundle_ui_040),
            p.base != null && !p.abiMismatch, onInstall, Modifier.align(Alignment.BottomCenter),
            secondary = Txt(R.string.bundle_ui_041) to onList,
        )
    }
}