package local.filemanager

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

// ---------------------------------------------------------------- APK

private class PermRow(val name: String, val label: String?, val dangerous: Boolean)

internal class InstalledApp(val versionName: String, val versionCode: Long, val signers: List<String>)

private class ApkDetail(
    val label: String, val pkg: String, val versionName: String, val versionCode: Long,
    val minSdk: Int, val targetSdk: Int, val compileSdk: Int,
    val perms: List<PermRow>, val signers: List<String>, val icon: Bitmap?, val installed: InstalledApp?,
)

private sealed interface ApkLoad {
    data object Busy : ApkLoad
    data class Ok(val d: ApkDetail) : ApkLoad
    data class Fail(val message: String) : ApkLoad
}

private const val APK_REMOTE_LIMIT = 300L * 1024 * 1024

private fun signersOf(pi: android.content.pm.PackageInfo?): List<String> {
    val s = pi?.signingInfo ?: return emptyList()
    val sigs = if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory
    return (sigs ?: emptyArray()).map { ApkFormat.fingerprint(it.toByteArray()) }
}

/** NAS の APK を、情報を調べるために取り込んでおく端末内の場所(同じファイルなら同じ場所)。 */
private fun apkCacheFile(ctx: Context, fs: Fs, item: FileItem): File {
    val root = File(ctx.cacheDir, "apkview").also { it.mkdirs() }
    return File(root, cacheKey(fs.id, item.path, item.size, item.modified) + ".apk")
}

/**
 * 情報を見るときに取り込み済みの APK。あれば、インストールのためにもう一度ダウンロードしなくて済む。
 * 端末内のファイルや、まだ取り込んでいないときは null。
 */
internal fun cachedApk(ctx: Context, fs: Fs, item: FileItem): File? {
    if (fs.isLocal) return null
    val f = apkCacheFile(ctx, fs, item)
    return f.takeIf { it.isFile && it.length() == item.size }
}

/** APK を端末内のファイルにして(NAS なら取り込んで)、中の情報を読む。 */
private fun loadApk(ctx: Context, fs: Fs, item: FileItem, task: BgTask): ApkLoad {
    return try {
        val file: File = if (fs.isLocal) {
            File(item.path)
        } else {
            if (item.size > APK_REMOTE_LIMIT) return ApkLoad.Fail(Txt(R.string.apk_ui_001, APK_REMOTE_LIMIT / 1024 / 1024))
            val dest = apkCacheFile(ctx, fs, item)
            val root = dest.parentFile!!
            if (!dest.exists() || dest.length() != item.size) {
                val tmp = File(root, dest.name + ".part")
                BgTasks.run(task, item.size) { task.download(fs, item.path, tmp) }
                dest.delete()
                if (!tmp.renameTo(dest)) throw IOException(Txt(R.string.apk_ui_002))
            }
            dest.setLastModified(System.currentTimeMillis())
            trimCacheTree(root, dest, 500L * 1024 * 1024, 24L * 3600 * 1000)
            dest
        }
        val pm = ctx.packageManager
        val pi = pm.getPackageArchiveInfo(file.path, PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return ApkLoad.Fail(Txt(R.string.apk_ui_003))
        val ai = pi.applicationInfo ?: return ApkLoad.Fail(Txt(R.string.apk_ui_004))
        ai.sourceDir = file.path
        ai.publicSourceDir = file.path
        val perms = (pi.requestedPermissions ?: emptyArray()).map { name ->
            try {
                val p = pm.getPermissionInfo(name, 0)
                PermRow(name, p.loadLabel(pm).toString().takeIf { it.isNotBlank() && it != name }, p.protection == PermissionInfo.PROTECTION_DANGEROUS)
            } catch (e: PackageManager.NameNotFoundException) {
                PermRow(name, null, false)
            }
        }.sortedWith(compareByDescending<PermRow> { it.dangerous }.thenByDescending { it.label != null }.thenBy { it.name })
        val installed = try {
            val ip = pm.getPackageInfo(pi.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            InstalledApp(ip.versionName ?: "", ip.longVersionCode, signersOf(ip))
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        ApkLoad.Ok(
            ApkDetail(
                label = ai.loadLabel(pm).toString(), pkg = pi.packageName, versionName = pi.versionName ?: "", versionCode = pi.longVersionCode,
                minSdk = ai.minSdkVersion, targetSdk = ai.targetSdkVersion, compileSdk = if (Build.VERSION.SDK_INT >= 31) ai.compileSdkVersion else 0,
                perms = perms, signers = signersOf(pi),
                icon = runCatching { ai.loadIcon(pm).toBitmap(192, 192) }.getOrNull(), installed = installed,
            ),
        )
    } catch (e: Exception) {
        ApkLoad.Fail(Txt(R.string.apk_ui_005, e.message ?: e.javaClass.simpleName))
    }
}

/** APK の詳細(アイコン・名前・版・必要な権限・署名・いま入っているものとの比べ)と、インストールのボタン。 */
@Composable
internal fun ApkPane(fs: Fs, item: FileItem, onInstall: () -> Unit) {
    val ctx = LocalContext.current
    val task = rememberBgTask(item.path, Txt(R.string.apk_ui_006, item.name), Txt(R.string.apk_ui_007, item.name), Txt(R.string.apk_ui_008, item.name))
    val load = produceState<ApkLoad>(ApkLoad.Busy, item.path) {
        value = withContext(Dispatchers.IO) { loadApk(ctx, fs, item, task) }
    }.value
    when (load) {
        ApkLoad.Busy -> LoadingPanel(item.name, task, !fs.isLocal)
        is ApkLoad.Fail -> Box(Modifier.fillMaxSize()) {
            Text(load.message, color = Pv.text, modifier = Modifier.align(Alignment.Center).padding(32.dp))
        }
        is ApkLoad.Ok -> ApkDetailView(load.d, item, onInstall)
    }
}

@Composable
internal fun InfoRow(label: String, value: String, valueColor: Color = rowText) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = Pv.label, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp).width(104.dp))
        Text(value, color = valueColor, fontSize = 14.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ApkDetailView(d: ApkDetail, item: FileItem, onInstall: () -> Unit) {
    val inst = d.installed
    val cmp = inst?.let { ApkFormat.compare(d.versionCode, it.versionCode) }
    val same = inst?.let { ApkFormat.sameSigner(d.signers, it.signers) }
    // 状態: 見出しと補足、色
    val title: String
    val detail: String?
    val statusColor: Color
    when {
        inst == null -> { title = Txt(R.string.apk_ui_009); detail = null; statusColor = Pv.neutral }
        cmp == ApkFormat.Compare.NEWER -> {
            title = Txt(R.string.apk_ui_010); detail = Txt(R.string.apk_ui_011, inst.versionName, inst.versionCode, d.versionName, d.versionCode); statusColor = Pv.ok
        }
        cmp == ApkFormat.Compare.SAME -> { title = Txt(R.string.apk_ui_012); detail = Txt(R.string.apk_ui_013, inst.versionName, inst.versionCode); statusColor = Pv.caution }
        else -> {
            title = Txt(R.string.apk_ui_014)
            detail = Txt(R.string.apk_ui_015, inst.versionName, inst.versionCode)
            statusColor = Pv.bad
        }
    }
    val signNote = if (inst != null && same == false) Txt(R.string.apk_ui_016) else null
    val danger = d.perms.filter { it.dangerous }
    val other = d.perms.filter { !it.dangerous }
    var showOther by remember { mutableStateOf(other.size <= 5) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().belowBars(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { AppHeader(d.icon, d.label, Txt(R.string.apk_ui_017, d.versionName), d.pkg) }
            item { StatusBox(title, detail, statusColor, signNote) }
            item {
                ApkCard(Txt(R.string.apk_ui_018)) {
                    CardRow(Txt(R.string.apk_ui_019), "${d.versionName}(${d.versionCode})")
                    CardRow(Txt(R.string.apk_ui_020), Txt(R.string.apk_ui_021, ApkFormat.sdkText(d.minSdk)))
                    CardRow(Txt(R.string.apk_ui_022), ApkFormat.sdkText(d.targetSdk) + if (d.compileSdk > 0) Txt(R.string.apk_ui_023, d.compileSdk) else "")
                    CardRow(Txt(R.string.apk_ui_024), Txt(R.string.apk_ui_025, FileOps.formatSize(item.size), FileOps.formatDate(item.modified)))
                    if (inst != null && same != null) {
                        CardRow(Txt(R.string.apk_ui_026), if (same) Txt(R.string.apk_ui_027) else Txt(R.string.apk_ui_028), if (same) Pv.ok else Pv.bad)
                    }
                }
            }
            item {
                ApkCard(Txt(R.string.apk_ui_029, d.perms.size)) {
                    if (d.perms.isEmpty()) Text(Txt(R.string.apk_ui_030), color = rowDim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    if (danger.isNotEmpty()) {
                        Text(Txt(R.string.apk_ui_031), color = Pv.danger, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
                        for (p in danger) PermLine(p, Pv.danger)
                    }
                    if (other.isNotEmpty()) {
                        Text(
                            Txt(R.string.apk_ui_032, other.size) + if (showOther) "▲" else "▼", color = rowDim, fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth().clickable { showOther = !showOther }.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
                        )
                        if (showOther) for (p in other) PermLine(p, rowText)
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            item {
                ApkCard(Txt(R.string.apk_ui_033)) {
                    if (d.signers.isEmpty()) Text(Txt(R.string.apk_ui_034), color = rowDim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    SelectionContainer {
                        Column(Modifier.padding(horizontal = 12.dp)) {
                            for (s in d.signers) Text(s, color = rowText, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 6.dp))
                        }
                    }
                }
            }
        }
        InstallBar(
            when (cmp) {
                ApkFormat.Compare.NEWER -> Txt(R.string.apk_ui_035)
                ApkFormat.Compare.SAME -> Txt(R.string.apk_ui_036)
                else -> Txt(R.string.apk_ui_037)
            },
            true, onInstall, Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** アイコン・名前・バージョン・パッケージ名の見出し。APK とまとめ入り APK で共通。 */
@Composable
internal fun AppHeader(icon: Bitmap?, name: String, versionLine: String?, pkg: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
        if (icon != null) Image(icon.asImageBitmap(), null, Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)))
        Column(Modifier.padding(start = if (icon != null) 14.dp else 0.dp)) {
            Text(name, color = Pv.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (versionLine != null) Text(versionLine, color = rowText, fontSize = 13.sp)
            if (pkg != null) Text(pkg, color = rowDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

/** いまの状態を色つきの枠で見せる。title は短い見出し、detail と note は補足(note は注意の赤)。 */
@Composable
internal fun StatusBox(title: String, detail: String?, color: Color, note: String? = null) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(color.copy(alpha = 0.14f)).border(1.dp, color.copy(alpha = 0.45f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(title, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        if (detail != null) Text(detail, color = rowText, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        if (note != null) Text(note, color = Pv.bad, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/** 画面の下に固定するインストールのバー。secondary があれば、下に文字のボタンを 1 つ足す。 */
@Composable
internal fun InstallBar(label: String, enabled: Boolean, onInstall: () -> Unit, modifier: Modifier = Modifier, secondary: Pair<String, () -> Unit>? = null) {
    Column(modifier.fillMaxWidth().background(Pv.bg), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Pv.dim.copy(alpha = 0.25f)))
        Column(Modifier.navigationBarsPadding().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Button(onClick = onInstall, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) { Text(label) }
            if (secondary != null) TextButton(onClick = secondary.second) { Text(secondary.first) }
        }
    }
}

/** 見出しつきの囲み。中に行を並べる。 */
@Composable
internal fun ApkCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Pv.panel)) {
        Text(title, color = Pv.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 2.dp))
        content()
    }
}

@Composable
internal fun CardRow(label: String, value: String, valueColor: Color = rowText) {
    Box(Modifier.padding(horizontal = 12.dp)) { InfoRow(label, value, valueColor) }
}
@Composable
private fun PermLine(p: PermRow, color: Color) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)) {
        Text(p.label ?: ApkFormat.shortPermission(p.name), color = color, fontSize = 14.sp)
        Text(p.name, color = rowDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}
