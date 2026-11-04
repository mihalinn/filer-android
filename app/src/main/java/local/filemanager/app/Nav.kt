package local.filemanager

/**
 * 「上へ」「戻る」の判断。画面の状態に依存しないので、そのままテストできる。
 *
 * ホームやドロワーから開いた場所(例: ダウンロード)は、その場所を「最上位」として扱う(anchor)。
 * そこで戻るとストレージの親ではなくホームに戻る。anchor の外へ移ったときは普通のストレージ直下が最上位。
 */
object Nav {
    fun same(a: String, b: String) = a.trimEnd('/') == b.trimEnd('/')

    /** child が parent と同じか、その中にあるか。 */
    fun isUnder(parent: String, child: String): Boolean {
        val p = parent.trimEnd('/')
        val c = child.trimEnd('/')
        return c == p || c.startsWith("$p/")
    }

    /** path から見た最上位。anchor の中にいれば anchor、そうでなければストレージ(共有)の直下。 */
    fun effectiveRoot(path: String, anchor: String?, volumeRoot: String): String =
        if (anchor != null && isUnder(anchor, path)) anchor else volumeRoot

    /** path が root から数えて何階層下か(root そのものは 0。root の外なら大きな値は返さず 0)。 */
    fun depth(root: String, path: String): Int {
        if (!isUnder(root, path)) return 0
        val rest = path.trimEnd('/').removePrefix(root.trimEnd('/')).trim('/')
        return if (rest.isEmpty()) 0 else rest.split('/').size
    }

    /** 「上へ」の移動先。最上位にいる(またはこれ以上上が無い)ときは null = ホームへ。 */
    fun up(path: String, root: String, parent: String?): String? =
        if (same(path, root) || parent == null) null else parent
}
