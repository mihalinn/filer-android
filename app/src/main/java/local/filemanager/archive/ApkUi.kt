package local.filemanager

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
private fun loadApk(ctx: Context, fs: Fs, item: FileItem): ApkLoad {
    return try {
        val file: File = if (fs.isLocal) {
            File(item.path)
        } else {
            if (item.size > APK_REMOTE_LIMIT) return ApkLoad.Fail("大きすぎて調べられません(${APK_REMOTE_LIMIT / 1024 / 1024} MB まで)")
            val dest = apkCacheFile(ctx, fs, item)
            val root = dest.parentFile!!
            if (!dest.exists() || dest.length() != item.size) {
                val tmp = File(root, dest.name + ".part")
                fs.open(item.path).use { i -> tmp.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
                dest.delete()
                if (!tmp.renameTo(dest)) throw IOException("一時ファイルを作れませんでした")
            }
            dest.setLastModified(System.currentTimeMillis())
            trimCacheTree(root, dest, 500L * 1024 * 1024, 24L * 3600 * 1000)
            dest
        }
        val pm = ctx.packageManager
        val pi = pm.getPackageArchiveInfo(file.path, PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return ApkLoad.Fail("APKとして読めませんでした(壊れているか、途中までのファイルかもしれません)")
        val ai = pi.applicationInfo ?: return ApkLoad.Fail("APKとして読めませんでした")
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
        ApkLoad.Fail("APKを調べられません(${e.message ?: e.javaClass.simpleName})")
    }
}

/** APK の詳細(アイコン・名前・版・必要な権限・署名・いま入っているものとの比べ)と、インストールのボタン。 */
@Composable
internal fun ApkPane(fs: Fs, item: FileItem, onInstall: () -> Unit) {
    val ctx = LocalContext.current
    val load = produceState<ApkLoad>(ApkLoad.Busy, item.path) {
        value = withContext(Dispatchers.IO) { loadApk(ctx, fs, item) }
    }.value
    when (load) {
        ApkLoad.Busy -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Pv.text)
            if (!fs.isLocal) Text("読み込み中…", color = rowDim, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
        }
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
    val (status, statusColor) = when {
        inst == null -> "まだインストールされていません(新しくインストールします)" to Pv.neutral
        cmp == ApkFormat.Compare.NEWER -> "更新できます(いまの版: ${inst.versionName})" to Pv.ok
        cmp == ApkFormat.Compare.SAME -> "同じ版が入っています(${inst.versionName})" to Pv.caution
        else -> "新しい版がインストールされています(${inst.versionName})。古い版にするには、先にアンインストールが必要です" to Pv.bad
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().belowBars(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                    if (d.icon != null) Image(d.icon.asImageBitmap(), null, Modifier.size(72.dp))
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(d.label, color = Pv.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(d.pkg, color = rowDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                InfoRow("バージョン", "${d.versionName}(${d.versionCode})")
                InfoRow("対応する Android", "${ApkFormat.sdkText(d.minSdk)} 以上")
                InfoRow("対象の Android",ApkFormat.sdkText(d.targetSdk) + if (d.compileSdk > 0) "・コンパイル API ${d.compileSdk}" else "")
                InfoRow("ファイル", "${FileOps.formatSize(item.size)}・${FileOps.formatDate(item.modified)}")
                InfoRow("いまの状態", status, statusColor)
                if (inst != null && same != null) {
                    InfoRow(
                        "署名", if (same) "いま入っているものと同じです(上書きできます)" else "いま入っているものと違います(上書きできません)",
                        if (same) Pv.ok else Pv.bad,
                    )
                }
            }
            val danger = d.perms.filter { it.dangerous }
            val other = d.perms.filter { !it.dangerous }
            item {
                Text(
                    "必要な権限(${d.perms.size})", color = Pv.text, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                if (d.perms.isEmpty()) Text("なし", color = rowDim, fontSize = 13.sp)
            }
            if (danger.isNotEmpty()) {
                item { Text("特に注意する権限", color = Pv.danger, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
                items(danger) { PermLine(it, Pv.danger) }
            }
            if (other.isNotEmpty()) {
                item { Text("そのほか", color = rowDim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp)) }
                items(other) { PermLine(it, rowText) }
            }
            item {
                Text(
                    "署名(SHA-256)", color = Pv.text, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
                )
                if (d.signers.isEmpty()) Text("署名を読めませんでした(署名されていないAPKかもしれません)", color = rowDim, fontSize = 13.sp)
                SelectionContainer {
                    Column {
                        for (s in d.signers) Text(s, color = rowText, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 6.dp))
                    }
                }
            }
        }
        Button(
            onClick = onInstall,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp).fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(if (cmp == ApkFormat.Compare.NEWER) "更新する" else "インストール")
        }
    }
}

@Composable
private fun PermLine(p: PermRow, color: Color) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(p.label ?: ApkFormat.shortPermission(p.name), color = color, fontSize = 14.sp)
        Text(p.name, color = rowDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}
