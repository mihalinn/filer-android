package local.filemanager

import android.net.Uri

/**
 * 他のアプリの「共有」から受け取った1件。uri が読み元で、テキストだけを共有されたときは text が中身。
 * size が分からないときは -1。
 */
data class IncomingFile(val name: String, val size: Long, val uri: Uri?, val text: String?)
