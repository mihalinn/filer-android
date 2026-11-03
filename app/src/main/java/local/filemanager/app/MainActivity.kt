package local.filemanager

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : ComponentActivity() {
    private val vm: FmViewModel by viewModels()
    private var hasAccess by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 落ちたときに、原因(スタックトレース)を次の起動で見られるよう記録する
        CrashLog.install(this)
        // 上部バーは常に濃い色なので、ステータスバーの文字は常に白にする
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        val actions = Actions(openFile = ::openFile, share = ::share, openUrl = ::openUrl)
        // 画面の回転などで作り直されたときは、同じ共有を二重に受け取らない
        if (savedInstanceState == null) handleSharedIntent(intent)
        setContent {
            FmTheme(vm.themeMode) {
                AppRoot(vm, hasAccess, onRequestAccess = ::requestAccess, actions = actions)
            }
        }
    }

    // 通知の許可。断られても、コピーなどは動く(進み具合が通知に出ないだけ)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedIntent(intent)
    }

    /** 他のアプリの「共有」で渡されたものを受け取り、保存先を選ぶ状態にする。 */
    private fun handleSharedIntent(i: Intent?) {
        if (i == null || (i.action != Intent.ACTION_SEND && i.action != Intent.ACTION_SEND_MULTIPLE)) return
        val uris: List<Uri> = if (Build.VERSION.SDK_INT >= 33) {
            if (i.action == Intent.ACTION_SEND) listOfNotNull(i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            if (i.action == Intent.ACTION_SEND) listOfNotNull(i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
        // 他のアプリのファイルを file:// で直接渡されても、読めない・危ないので受け付けない(content:// だけ)
        val files = uris.filter { it.scheme == "content" }.map { uri -> describe(uri) }.toMutableList()
        if (files.isEmpty()) {
            val text = i.getStringExtra(Intent.EXTRA_TEXT)
            if (!text.isNullOrBlank()) {
                val subject = i.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() }?.take(60) ?: "共有テキスト"
                val name = FileOps.safeFileName(subject, "共有テキスト").let { if (it.endsWith(".txt", ignoreCase = true)) it else "$it.txt" }
                files.add(IncomingFile(name, text.toByteArray().size.toLong(), null, text))
            }
        }
        if (files.isEmpty()) {
            Toast.makeText(this, "受け取れる内容がありません", Toast.LENGTH_SHORT).show()
            return
        }
        vm.receiveShared(files)
    }

    /** 共有された content:// の名前と大きさを、共有元に尋ねる(答えてくれなければ名前は URI の末尾、大きさは不明)。 */
    private fun describe(uri: Uri): IncomingFile {
        var name: String? = null
        var size = -1L
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                }
            }
        }
        return IncomingFile(FileOps.safeFileName(name ?: uri.lastPathSegment), size, uri, null)
    }

    private var stoppedAt = 0L

    override fun onStart() {
        super.onStart()
        Ops.appVisible = true
        // しばらく裏にいたあとは NAS との接続が切れていることが多い。切れた接続で待たされないよう、先に捨てる
        if (stoppedAt != 0L && android.os.SystemClock.elapsedRealtime() - stoppedAt > 10_000) vm.recycleConnections()
    }

    override fun onStop() {
        Ops.appVisible = false
        stoppedAt = android.os.SystemClock.elapsedRealtime()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // 設定画面から戻ったとき・USB 等を抜き差ししたときに状態を取り直す
        hasAccess = Environment.isExternalStorageManager()
        if (hasAccess) {
            vm.refreshVolumes()
            vm.reload()
            askNotificationPermissionOnce()
        }
    }

    /** コピー・移動の進み具合を通知に出すための許可(Android 13 以降)を、最初に1回だけ頼む。 */
    private fun askNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("fm", MODE_PRIVATE)
        if (prefs.getBoolean("askedNotification", false)) return
        prefs.edit().putBoolean("askedNotification", true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestAccess() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun mimeOf(file: File): String {
        val ext = FileOps.extensionOf(file.name)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: FileOps.mimeOf(file.name)
    }

    private fun uriOf(file: File): Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

    private fun openFile(file: File) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriOf(file), mimeOf(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "このファイルを開けるアプリがありません", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "ブラウザが見つかりません", Toast.LENGTH_SHORT).show()
        }
    }

    private fun share(files: List<File>) {
        val onlyFiles = files.filter { it.isFile }
        if (onlyFiles.isEmpty()) {
            Toast.makeText(this, "フォルダは共有できません。ZIPに圧縮してから共有してください", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = if (onlyFiles.size == 1) {
            Intent(Intent.ACTION_SEND)
                .setType(mimeOf(onlyFiles[0]))
                .putExtra(Intent.EXTRA_STREAM, uriOf(onlyFiles[0]))
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType("*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(onlyFiles.map { uriOf(it) }))
        }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, "共有"))
    }
}
