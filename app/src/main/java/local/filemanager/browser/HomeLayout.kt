package local.filemanager

/** ホームに置いたフォルダのショートカット。fsId は "local" や "smb:xxxx"。 */
data class HomeShortcut(val fsId: String, val path: String, val name: String) {
    val id: String get() = "sc:$fsId:$path"
}

/** ホーム画面のタイルの並べ方(保存の形式と、並べ替えの計算)。Android に依存しない。 */
internal object HomeLayout {
    const val MIN_COLS = 3
    const val MAX_COLS = 5

    /** 保存した並び(saved)の順を優先し、そこに無いものは初期の並び(defaults)の順で後ろに付ける。いま無いタイルは捨てる。 */
    fun arrange(defaults: List<String>, saved: List<String>): List<String> {
        val have = defaults.toSet()
        val first = saved.filter { it in have }.distinct()
        return first + defaults.filter { it !in first }
    }

    /** index の項目を delta だけ動かした並びを返す(端を越える動きは何もしない)。 */
    fun move(ids: List<String>, index: Int, delta: Int): List<String> {
        val to = index + delta
        if (index !in ids.indices || to !in ids.indices || delta == 0) return ids
        val out = ids.toMutableList()
        val x = out.removeAt(index)
        out.add(to, x)
        return out
    }

    fun encode(ids: List<String>): String = ids.joinToString("\n")

    fun decode(s: String): List<String> = s.split('\n').filter { it.isNotEmpty() }

    fun encodeShortcuts(list: List<HomeShortcut>): String =
        list.joinToString("\n") { "${it.fsId}\t${it.path}\t${it.name.replace('\n', ' ').replace('\t', ' ')}" }

    fun decodeShortcuts(s: String): List<HomeShortcut> = s.split('\n').mapNotNull {
        val p = it.split('\t')
        if (p.size == 3 && p[0].isNotEmpty()) HomeShortcut(p[0], p[1], p[2]) else null
    }

    /** 列の数から、タイルの絵の枠の大きさ(dp)。4 列で 66dp が基準。 */
    fun frameDp(cols: Int): Int = (66 * 4f / cols).toInt().coerceIn(52, 88)
}
