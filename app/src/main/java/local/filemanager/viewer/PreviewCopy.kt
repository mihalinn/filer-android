package local.filemanager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.withTimeoutOrNull

/** プレビューの文字のコピー(読むだけの画面で、文字を取り出す)。編集の機能とは別で、表示の部品に足すだけにしてある。 */
object CopyText {
    /** 一度にクリップボードへ入れる文字数の上限(端末の受け渡しの上限が約 1MB のため)。 */
    const val MAX_CHARS = 200_000

    /** タブ区切りの 1 セル。タブ・改行・引用符を含むときは、引用符で囲む(Excel に貼っても 1 セルのまま入る)。 */
    fun tsvCell(s: String): String =
        if (s.any { it == '\t' || it == '\n' || it == '\r' || it == '"' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun row(cols: Int, text: (Int) -> String): String = (0 until cols).joinToString("\t") { tsvCell(text(it)) }

    fun column(rows: Int, text: (Int) -> String): String = (0 until rows).joinToString("\n") { tsvCell(text(it)) }

    fun tsv(rows: Int, cols: Int, text: (Int, Int) -> String): String =
        (0 until rows).joinToString("\n") { r -> row(cols) { c -> text(r, c) } }

    /** 上限を超えるときは先頭だけにして、切ったことを second で返す。 */
    fun limit(text: String): Pair<String, Boolean> =
        if (text.length > MAX_CHARS) text.take(MAX_CHARS) to true else text to false
}

/** いま開いているファイルの「全体をコピー」。画面ごとに、中身の取り出し方を入れる(入っていなければメニューに出さない)。 */
internal class CopyHolder {
    var label by mutableStateOf("全文をコピー")
    var provider by mutableStateOf<(() -> String)?>(null)

    /** 取り出しに時間がかかるものは、画面が自分でコピーまでやる(PDF など)。入っていれば、provider より先に使う。 */
    var action by mutableStateOf<(() -> Unit)?>(null)

    fun set(label: String, provider: () -> String) {
        this.label = label
        this.provider = provider
    }

    fun setAction(label: String, action: () -> Unit) {
        this.label = label
        this.action = action
    }
}

internal fun copyToClipboard(ctx: Context, text: String) {
    val (t, cut) = CopyText.limit(text)
    runCatching {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("text", t))
    }.onSuccess {
        Toast.makeText(ctx, if (cut) "大きいため、先頭の ${CopyText.MAX_CHARS / 10_000} 万文字だけコピーしました" else "コピーしました", Toast.LENGTH_SHORT).show()
    }.onFailure {
        Toast.makeText(ctx, "コピーできませんでした", Toast.LENGTH_SHORT).show()
    }
}

/** 中の文字を、長押しで選んでコピーできるようにする(選んだ範囲の色は、ビューアの配色で見やすい色)。 */
@Composable
internal fun CopyableArea(enabled: Boolean = true, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    val colors = TextSelectionColors(handleColor = Pv.accent, backgroundColor = Pv.accent.copy(alpha = 0.4f))
    val focus = LocalFocusManager.current
    // 選択を間違えたとき、ほかの場所を短くタップすると外れる(選択の部品は、フォーカスを失うと選択を消す)
    Box(Modifier.onShortTap { clearSelection(focus) }) {
        CompositionLocalProvider(LocalTextSelectionColors provides colors) { SelectionContainer(content = content) }
    }
}

private fun clearSelection(focus: FocusManager) = focus.clearFocus(force = true)

/**
 * 長押しを見つけて知らせる(触れた操作は消費しない)。ピンチ・ダブルタップなど、ほかの操作は邪魔しない。
 * 傾けたり拡大して表示する図(スライドなど)は、文字の選択の仕組みが使えないので、これでメニューを出す。
 */
internal fun Modifier.onLongPress(onLong: () -> Unit): Modifier = onLongPressAt { onLong() }

/** onLongPress の、押した位置つき版(この部品の左上からの位置)。 */
internal fun Modifier.onLongPressAt(onLong: (androidx.compose.ui.geometry.Offset) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var done = false
            while (!done) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val ch = event.changes.firstOrNull { it.id == down.id }
                done = event.changes.size > 1 || ch == null || ch.changedToUp() ||
                    (ch.position - down.position).getDistance() > viewConfiguration.touchSlop
            }
            true
        }
        if (ended == null) onLong(down.position)
    }
}

/**
 * 短いタップ(動かさず、長押しにならない)を見つけて知らせる。触れた操作は消費しないので、
 * 上下のバーの出し入れなど、ほかのタップの処理は今まで通り動く。
 */
private fun Modifier.onShortTap(onTap: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var moved = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val ch = event.changes.firstOrNull { it.id == down.id } ?: break
            if ((ch.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
            if (ch.changedToUp()) {
                if (!moved && ch.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) onTap()
                break
            }
        }
    }
}

/** 表のセルを長押ししたときのメニュー(セル・行・列・表全体のコピー)。セルの部品の中に置くと、その近くに開く。 */
@Composable
internal fun CellCopyMenu(onDismiss: () -> Unit, cell: String, row: () -> String, column: () -> String, all: () -> String) {
    val ctx = LocalContext.current
    fun copy(t: () -> String) { copyToClipboard(ctx, t()); onDismiss() }
    Box {
        ListMenu(
            expanded = true, dark = Pv.dark, onDismiss = onDismiss,
            items = listOf(
                VMenuItem.Action(Icons.Default.ContentCopy, "セルをコピー") { copy { cell } },
                VMenuItem.Action(Icons.Default.TableRows, "行をコピー") { copy(row) },
                VMenuItem.Action(Icons.Default.ViewColumn, "列をコピー") { copy(column) },
                VMenuItem.Action(Icons.Default.GridOn, "表全体をコピー") { copy(all) },
            ),
        )
    }
}