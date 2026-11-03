package local.filemanager

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface Pending {
    data class Delete(val count: Int, val toTrash: Boolean) : Pending
    data class Rename(val item: FileItem) : Pending
    data object NewFolder : Pending
    data object Display : Pending
    data object EmptyTrash : Pending
    data class Zip(val suggested: String) : Pending
    data class Details(val items: List<FileItem>) : Pending
    data class EditRemote(val existing: RemoteConfig?) : Pending
    data class DeleteRemote(val remote: RemoteConfig) : Pending
}

@Composable
internal fun PendingDialogs(p: Pending?, vm: FmViewModel, dismiss: () -> Unit) {
    when (p) {
        null -> {}
        is Pending.Delete -> ConfirmDialog(
            title = if (p.toTrash) "ごみ箱へ移動" else "削除",
            text = if (p.toTrash) "${p.count}件をごみ箱に移動します。ごみ箱から元に戻せます。"
            else "${p.count}件を完全に削除します。この操作は元に戻せません。",
            okLabel = if (p.toTrash) "移動" else "削除",
            dismiss = dismiss,
        ) { vm.deleteSelected() }
        is Pending.Rename -> TextInputDialog("名前の変更", p.item.name, "変更", dismiss) { vm.renameSelected(it) }
        Pending.NewFolder -> TextInputDialog("新しいフォルダ", "", "作成", dismiss) { vm.newFolder(it) }
        is Pending.Zip -> TextInputDialog("ZIPに圧縮", p.suggested, "圧縮", dismiss) { vm.zipSelected(it) }
        Pending.Display -> DisplayDialog(vm, dismiss)
        Pending.EmptyTrash -> ConfirmDialog("ごみ箱を空にする", "ごみ箱の中身をすべて完全に削除します。この操作は元に戻せません。", "空にする", dismiss) { vm.emptyTrash() }
        is Pending.Details -> DetailsDialog(vm, p.items, dismiss)
        is Pending.EditRemote -> RemoteEditDialog(vm, p.existing, dismiss)
        is Pending.DeleteRemote -> ConfirmDialog(
            "接続先を削除", "「${p.remote.name}」の接続情報を削除します(NAS内のファイルは消えません)", "削除", dismiss,
        ) { vm.deleteRemote(p.remote.id) }
    }
}

/** 貼り付け先に同じ名前があるとき。上書き / スキップ / 両方残す を選ぶ。 */
@Composable
internal fun ConflictDialog(vm: FmViewModel, p: FmViewModel.ConflictPrompt) {
    val c = p.conflicts.first()
    var all by remember(p.conflicts.size) { mutableStateOf(false) }
    fun describe(i: FileItem) = (if (i.isDir) "フォルダ" else FileOps.formatSize(i.size)) + "・" + FileOps.formatDate(i.modified)
    AlertDialog(
        onDismissRequest = vm::cancelConflict,
        title = { Text("同じ名前のファイルがあります") },
        text = {
            Column {
                Text("「${c.src.name}」は貼り付け先にすでにあります")
                Spacer(Modifier.height(10.dp))
                Text("貼り付けるもの: ${describe(c.src)}", style = MaterialTheme.typography.bodySmall)
                Text("すでにあるもの: ${describe(c.existing)}", style = MaterialTheme.typography.bodySmall)
                if (c.src.isDir && c.existing.isDir) {
                    Spacer(Modifier.height(6.dp))
                    Text("フォルダを上書きすると、中身がひとつにまとまります(同じ名前のファイルだけが置き換わります)", style = MaterialTheme.typography.bodySmall)
                }
                if (p.conflicts.size > 1) {
                    Row(
                        Modifier.fillMaxWidth().clickable { all = !all }.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(checked = all, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text("残りの${p.conflicts.size}件すべてに同じ操作をする")
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                    TextButton(onClick = { vm.answerConflict(ConflictAction.OVERWRITE, all) }) { Text("上書き") }
                    TextButton(onClick = { vm.answerConflict(ConflictAction.SKIP, all) }) { Text("スキップ") }
                    TextButton(onClick = { vm.answerConflict(ConflictAction.KEEP_BOTH, all) }) { Text("両方残す") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                    TextButton(onClick = vm::cancelConflict) { Text("キャンセル") }
                }
            }
        },
        // ボタンは本文の中に並べたので、ここは空(空の行ぶんの余白ができないようにする)
        confirmButton = {},
    )
}

@Composable
private fun ConfirmDialog(title: String, text: String, okLabel: String, dismiss: () -> Unit, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { dismiss(); onOk() }) { Text(okLabel) } },
        dismissButton = { TextButton(onClick = dismiss) { Text("キャンセル") } },
    )
}

@Composable
private fun TextInputDialog(title: String, initial: String, okLabel: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember {
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.substringBeforeLast('.', initial).length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val error = if (text.text.isEmpty()) null else FileOps.validateName(text.text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onOk(text.text); onDismiss() },
                enabled = text.text.isNotBlank() && error == null,
            ) { Text(okLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, Modifier.padding(top = 10.dp, bottom = 2.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun DisplayDialog(vm: FmViewModel, dismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("表示設定") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SectionLabel("並べ替え")
                SortKey.entries.forEach { k -> OptionRow(k.label, vm.sortKey == k) { vm.setSort(k, vm.sortAsc) } }
                Row {
                    Column(Modifier.weight(1f)) { OptionRow("昇順", vm.sortAsc) { vm.setSort(vm.sortKey, true) } }
                    Column(Modifier.weight(1f)) { OptionRow("降順", !vm.sortAsc) { vm.setSort(vm.sortKey, false) } }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { vm.toggleHidden() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("隠しファイルを表示", Modifier.weight(1f))
                    Switch(checked = vm.showHidden, onCheckedChange = { vm.toggleHidden() })
                }
                Row(
                    Modifier.fillMaxWidth().clickable { vm.toggleNasThumbs() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("NASのサムネイルを表示", Modifier.weight(1f))
                    Switch(checked = vm.nasThumbs, onCheckedChange = { vm.toggleNasThumbs() })
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionLabel("テーマ")
                OptionRow("ライト", vm.themeMode == "light") { vm.changeTheme("light") }
                OptionRow("ダーク", vm.themeMode == "dark") { vm.changeTheme("dark") }
                OptionRow("端末の設定に合わせる", vm.themeMode == "system") { vm.changeTheme("system") }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                // ビューアの配色・マンガの向き・ライセンスなどは、別画面の「設定」にある
                TextButton(onClick = { dismiss(); vm.openSettings() }) { Text("その他の設定…") }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("閉じる") } },
    )
}

@Composable
private fun DetailsDialog(vm: FmViewModel, items: List<FileItem>, dismiss: () -> Unit) {
    val stats by produceState<Stats?>(null, items) {
        value = withContext(Dispatchers.IO) { runCatching { vm.statsOf(items) }.getOrNull() }
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("詳細") },
        text = {
            Column {
                if (items.size == 1) {
                    val f = items[0]
                    DetailLine("名前", f.name)
                    DetailLine("場所", f.path.substringBeforeLast('/').ifEmpty { "/" })
                    DetailLine("更新日時", FileOps.formatDate(f.modified))
                } else {
                    DetailLine("選択", "${items.size}件")
                }
                val s = stats
                if (s == null) {
                    DetailLine("サイズ", "計算中…")
                } else {
                    DetailLine("サイズ", "${FileOps.formatSize(s.bytes)}(${s.bytes} B)")
                    if (s.folders > 0) DetailLine("中身", "フォルダ ${s.folders}、ファイル ${s.files}")
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("閉じる") } },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** NAS(SMB共有)の接続先の追加・編集。 */
@Composable
private fun RemoteEditDialog(vm: FmViewModel, existing: RemoteConfig?, dismiss: () -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "NAS") }
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var share by remember { mutableStateOf(existing?.share ?: "") }
    var start by remember { mutableStateOf(existing?.startPath ?: "") }
    var user by remember { mutableStateOf(existing?.user ?: "") }
    var password by remember { mutableStateOf(existing?.password ?: "") }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun build() = RemoteConfig(
        id = existing?.id ?: vm.newRemoteId(),
        name = name.trim(), host = host.trim(), share = share.trim().trim('/', '\\'),
        startPath = start.trim(), user = user.trim(), password = password,
    )

    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (existing == null) "NASを追加" else "接続先を編集") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Field("表示名", name) { name = it; status = null }
                Field("ホスト(IPアドレスか名前)", host, KeyboardType.Uri) { host = it; status = null }
                Field("共有名", share) { share = it; status = null }
                Field("最初に開くフォルダ(省略可)", start) { start = it }
                Field("ユーザー名(空ならゲスト)", user) { user = it; status = null }
                Field("パスワード", password, KeyboardType.Password, secret = true) { password = it; status = null }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    enabled = !testing && host.isNotBlank() && share.isNotBlank(),
                    onClick = {
                        testing = true
                        status = "接続中…"
                        val cfg = build()
                        scope.launch {
                            status = withContext(Dispatchers.IO) {
                                try {
                                    "接続できました(直下に ${vm.testRemote(cfg)} 件)"
                                } catch (e: Exception) {
                                    e.message ?: "接続できませんでした"
                                }
                            }
                            testing = false
                        }
                    },
                ) { Text("接続テスト") }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && host.isNotBlank() && share.isNotBlank(),
                onClick = { vm.saveRemote(build()); dismiss() },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("キャンセル") } },
    )
}

@Composable
private fun Field(label: String, value: String, type: KeyboardType = KeyboardType.Text, secret: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    )
}
