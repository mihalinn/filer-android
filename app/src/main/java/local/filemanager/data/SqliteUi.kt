package local.filemanager

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private const val DB_COPY_LIMIT = 256L * 1024 * 1024
private const val PAGE_ROWS = 500
private const val CELL_MAX_CHARS = 4000

private class DbObject(val name: String, val type: String, val sql: String?)

private class SqlDb(val db: SQLiteDatabase, val objects: List<DbObject>)

private sealed interface SqlLoad {
    data object Busy : SqlLoad
    data class Ok(val db: SqlDb) : SqlLoad
    data class Fail(val message: String) : SqlLoad
}

/** 表示する 1 つのセル。kind は 0 = NULL / 1 = 数 / 2 = 文字 / 3 = BLOB。 */
private class SqlCellV(val text: String, val kind: Int)

private sealed interface SqlData {
    class Rows(val header: List<String>, val rows: List<List<SqlCellV>>, val total: Long?, val offset: Int) : SqlData
    class Error(val message: String) : SqlData
}

/** 端末の一時領域へコピーして開く(元のファイルに、ジャーナルなどを作らないため)。WAL 方式のものは、-wal も一緒にコピーする。 */
private fun copyForDb(ctx: Context, fs: Fs, item: FileItem): File {
    if (item.size > DB_COPY_LIMIT) throw OpException("大きすぎて開けません(${DB_COPY_LIMIT / 1024 / 1024} MB まで)")
    val dir = File(ctx.cacheDir, "sqliteview").also { it.mkdirs() }
    val base = cacheKey(fs.id, item.path, item.size, item.modified)
    val out = File(dir, "$base.db")
    fun copy(from: String, to: File, expect: Long?) {
        if (to.exists() && (expect == null || to.length() == expect)) return
        val part = File(dir, to.name + ".part")
        fs.open(from).use { i -> part.outputStream().use { o -> i.copyTo(o) } }
        to.delete()
        if (!part.renameTo(to)) throw IOException("一時ファイルを作れません")
    }
    copy(item.path, out, item.size)
    val wal = item.path + "-wal"
    val walFile = File(dir, "$base.db-wal")
    walFile.delete()
    if (runCatching { fs.exists(wal) }.getOrDefault(false)) runCatching { copy(wal, walFile, null) }
    out.setLastModified(System.currentTimeMillis())
    trimCacheTree(dir, out, 768L * 1024 * 1024, 24L * 3600 * 1000)
    return out
}

private fun openDb(ctx: Context, fs: Fs, item: FileItem): SqlLoad = try {
    val f = copyForDb(ctx, fs, item)
    val head = f.inputStream().use { s -> ByteArray(16).also { s.read(it) } }
    if (String(head, Charsets.ISO_8859_1) != "SQLite format 3\u0000") {
        SqlLoad.Fail("SQLiteのデータベースではありません(別の形式か、暗号化されているかもしれません)")
    } else {
        val db = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY)
        val objs = ArrayList<DbObject>()
        db.rawQuery("SELECT name, type, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY CASE type WHEN 'table' THEN 0 WHEN 'view' THEN 1 WHEN 'index' THEN 2 ELSE 3 END, name", null).use { c ->
            while (c.moveToNext()) objs.add(DbObject(c.getString(0) ?: "", c.getString(1) ?: "", c.getString(2)))
        }
        SqlLoad.Ok(SqlDb(db, objs))
    }
} catch (e: OpException) {
    SqlLoad.Fail(e.message ?: "開けません")
} catch (e: Exception) {
    SqlLoad.Fail("開けません(${e.message ?: e.javaClass.simpleName})")
} catch (e: OutOfMemoryError) {
    SqlLoad.Fail("大きすぎて読み込めません")
}

private fun cellOf(c: Cursor, i: Int): SqlCellV = when (c.getType(i)) {
    Cursor.FIELD_TYPE_NULL -> SqlCellV("NULL", 0)
    Cursor.FIELD_TYPE_INTEGER -> SqlCellV(c.getLong(i).toString(), 1)
    Cursor.FIELD_TYPE_FLOAT -> SqlCellV(SqlFormat.real(c.getDouble(i)), 1)
    Cursor.FIELD_TYPE_BLOB -> SqlCellV(SqlFormat.blob(c.getBlob(i)), 3)
    else -> SqlCellV((c.getString(i) ?: "").let { if (it.length > CELL_MAX_CHARS) it.take(CELL_MAX_CHARS) + "…" else it }, 2)
}

private fun runQuery(db: SQLiteDatabase, sql: String, offset: Int, total: Long?): SqlData = try {
    db.rawQuery(sql, null).use { c ->
        val header = c.columnNames.toList()
        val rows = ArrayList<List<SqlCellV>>()
        while (rows.size < PAGE_ROWS && c.moveToNext()) rows.add(List(header.size) { cellOf(c, it) })
        SqlData.Rows(header, rows, total, offset)
    }
} catch (e: Exception) {
    SqlData.Error(e.message ?: e.javaClass.simpleName)
}

private fun tableData(db: SQLiteDatabase, name: String, offset: Int): SqlData {
    val q = SqlFormat.quote(name)
    val total = try {
        db.rawQuery("SELECT COUNT(*) FROM $q", null).use { if (it.moveToFirst()) it.getLong(0) else null }
    } catch (e: Exception) {
        null
    }
    return runQuery(db, "SELECT * FROM $q LIMIT $PAGE_ROWS OFFSET $offset", offset, total)
}

private class SqlGridModel(private val header: List<String>, private val rows: List<List<SqlCellV>>) : GridModel {
    override val rowCount = rows.size + 1
    override val colCount = header.size
    override val frozenRows = 1
    override fun span(r: Int, c: Int) = 1

    private val widths: List<Float> = List(header.size) { c ->
        fun w(s: String): Int {
            var n = 0
            for (ch in s.lineSequence().firstOrNull().orEmpty()) n += if (ch.code >= 0x2E80) 2 else 1
            return n
        }
        var m = w(header[c])
        for (r in 0 until minOf(rows.size, 200)) m = maxOf(m, w(rows[r].getOrNull(c)?.text.orEmpty()))
        (m * 7.4f + 18f).coerceIn(52f, 360f)
    }

    override fun colWidth(c: Int) = widths.getOrElse(c) { 80f }

    override fun cell(r: Int, c: Int): GridCell? {
        if (r == 0) return GridCell(header.getOrElse(c) { "" }, 0xFFE7ECF4.toInt(), null, true, false, -1, false, true)
        val v = rows.getOrNull(r - 1)?.getOrNull(c) ?: return null
        return when (v.kind) {
            0 -> GridCell(v.text, null, 0xFF9E9E9E.toInt(), false, true, -1)
            1 -> GridCell(v.text, null, null, false, false, 1)
            3 -> GridCell(v.text, null, 0xFF6A1B9A.toInt(), false, false, -1)
            else -> GridCell(v.text, null, null, false, false, -1)
        }
    }
}

private const val TAB_SCHEMA = "\u0001schema"
private const val TAB_QUERY = "\u0001query"

/**
 * SQLite のデータベース(.db / .sqlite / .sqlite3 など)を、読むだけで開く。
 * 表(テーブル)・ビューを選んで 500 行ずつ見られる。スキーマ(作り方)と、検索(SELECT など)も。
 */
@Composable
internal fun SqlitePane(fs: Fs, item: FileItem, fontSp: Int) {
    val ctx = LocalContext.current
    val load = produceState<SqlLoad>(SqlLoad.Busy, item.path) {
        value = withContext(Dispatchers.IO) { openDb(ctx, fs, item) }
    }.value
    when (load) {
        SqlLoad.Busy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Pv.text) }
        is SqlLoad.Fail -> Box(Modifier.fillMaxSize()) {
            Text(load.message, color = Pv.text, modifier = Modifier.align(Alignment.Center).padding(32.dp))
        }
        is SqlLoad.Ok -> {
            DisposableEffect(load.db) { onDispose { runCatching { load.db.db.close() } } }
            SqliteView(load.db, item, fontSp)
        }
    }
}

@Composable
private fun SqliteView(sdb: SqlDb, item: FileItem, fontSp: Int) {
    val tables = remember(sdb) { sdb.objects.filter { it.type == "table" || it.type == "view" } }
    var tab by remember { mutableStateOf(tables.firstOrNull()?.name ?: TAB_SCHEMA) }
    var offset by remember { mutableIntStateOf(0) }
    var queryDialog by remember { mutableStateOf(false) }
    var queryText by remember { mutableStateOf("SELECT * FROM ") }
    var queryRun by remember { mutableStateOf<String?>(null) }
    var queryError by remember { mutableStateOf<String?>(null) }

    val data = produceState<SqlData?>(null, tab, offset, queryRun) {
        value = null
        value = withContext(Dispatchers.IO) {
            when (tab) {
                TAB_SCHEMA -> null
                TAB_QUERY -> queryRun?.let { runQuery(sdb.db, it.trim().trimEnd(';'), 0, null) }
                else -> tableData(sdb.db, tab, offset)
            }
        }
    }.value

    Column(Modifier.fillMaxSize().belowBars()) {
        // 上: いま見ているものの説明
        val info = when {
            tab == TAB_SCHEMA -> "スキーマ(作り方)・${sdb.objects.size} 個"
            tab == TAB_QUERY -> "検索の結果" + ((data as? SqlData.Rows)?.let { "・${it.rows.size} 行" + if (it.rows.size >= PAGE_ROWS) "(先頭の ${PAGE_ROWS} 行まで)" else "" } ?: "")
            else -> {
                val r = data as? SqlData.Rows
                val t = tables.firstOrNull { it.name == tab }
                (if (t?.type == "view") "ビュー " else "表 ") + tab + (r?.total?.let { "・${"%,d".format(it)} 行" } ?: "") + (r?.header?.size?.let { "・$it 列" } ?: "")
            }
        }
        Text(info, color = Pv.dim, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                tab == TAB_SCHEMA -> SchemaText(sdb, fontSp)
                data == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Pv.text)
                data is SqlData.Error -> Text(data.message, color = Pv.bad, modifier = Modifier.padding(16.dp))
                data is SqlData.Rows -> {
                    if (data.rows.isEmpty()) {
                        Text(if (data.header.isEmpty()) "結果がありません" else "行がありません(列: ${data.header.joinToString("、")})", color = Pv.dim, modifier = Modifier.align(Alignment.Center).padding(24.dp))
                    } else {
                        val model = remember(data) { SqlGridModel(data.header, data.rows) }
                        SheetGrid(model, fontSp, Modifier.fillMaxSize(), Pv.dark)
                    }
                }
            }
        }

        // ページ送り(表・ビューのとき)
        val rows = data as? SqlData.Rows
        if (tab != TAB_SCHEMA && tab != TAB_QUERY && rows != null && ((rows.total ?: 0) > PAGE_ROWS || offset > 0)) {
            val total = rows.total ?: 0L
            Row(Modifier.fillMaxWidth().background(Pv.panel), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { offset = SqlFormat.pageStart(offset, PAGE_ROWS, total, false) }, enabled = offset > 0) { Text("前の ${PAGE_ROWS} 行") }
                Text(
                    "${offset + 1}〜${minOf(total, (offset + PAGE_ROWS).toLong())} / ${"%,d".format(total)} 行", color = Pv.text, fontSize = 12.sp,
                    modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                TextButton(onClick = { offset = SqlFormat.pageStart(offset, PAGE_ROWS, total, true) }, enabled = offset + PAGE_ROWS < total) { Text("次の ${PAGE_ROWS} 行") }
            }
        }

        // 下: 表・ビューのタブ、スキーマ、検索
        Row(Modifier.fillMaxWidth().background(Pv.panel).horizontalScroll(rememberScrollState())) {
            for (t in tables) Tab(t.name, tab == t.name) { tab = t.name; offset = 0 }
            Tab("スキーマ", tab == TAB_SCHEMA) { tab = TAB_SCHEMA }
            Tab("SQL検索…", tab == TAB_QUERY) { queryDialog = true }
        }
    }

    if (queryDialog) {
        AlertDialog(
            onDismissRequest = { queryDialog = false },
            title = { Text("SQLで検索") },
            text = {
                Column {
                    OutlinedTextField(
                        value = queryText, onValueChange = { queryText = it; queryError = null },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                    )
                    Text("読み取りだけの検索(SELECT など)を 1 つ実行できます。データは変わりません。", fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    queryError?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (!SqlFormat.isReadOnlyQuery(queryText)) {
                        queryError = "読み取りだけの検索(SELECT など)を 1 つ書いて"
                    } else {
                        queryRun = queryText
                        tab = TAB_QUERY
                        queryDialog = false
                    }
                }) { Text("実行") }
            },
            dismissButton = { TextButton(onClick = { queryDialog = false }) { Text("閉じる") } },
        )
    }
}

@Composable
private fun Tab(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clickable(onClick = onClick).background(if (on) Pv.bg else androidx.compose.ui.graphics.Color.Transparent).padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Text(label, color = if (on) Pv.accent else Pv.dim, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else null, maxLines = 1)
    }
}

@Composable
private fun SchemaText(sdb: SqlDb, fontSp: Int) {
    SelectionContainer {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            for (o in sdb.objects) {
                Text("-- ${o.type}: ${o.name}", color = Pv.comment, fontFamily = FontFamily.Monospace, fontSize = (fontSp - 1).sp)
                Text((o.sql ?: "(自動で作られたもの)") + ";", color = Pv.text, fontFamily = FontFamily.Monospace, fontSize = fontSp.sp, modifier = Modifier.padding(bottom = 14.dp))
            }
        }
    }
}
