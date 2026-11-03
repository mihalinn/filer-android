package local.filemanager

import java.util.PriorityQueue

/** 容量の分析結果の1項目(フォルダまたはファイル)。files は中のファイルの数(ファイルなら1)。 */
data class DiskNode(val path: String, val name: String, val isDir: Boolean, val size: Long, val files: Int)

/**
 * 1つの場所を調べた結果。
 * children は、フォルダごとの「大きい順の中身」(多すぎる分は omitted にまとめる)。小さなフォルダは中身を持たない。
 */
class Analysis(
    val root: DiskNode,
    val children: Map<String, List<DiskNode>>,
    /** フォルダの path → 一覧に載せきれなかった分の (個数, 容量) */
    val omitted: Map<String, Pair<Int, Long>>,
    /** 全体で大きいファイル(大きい順) */
    val biggestFiles: List<DiskNode>,
)

/** 調べている最中の進み具合。調べる側が更新し、画面側が一定間隔で読む。 */
class ScanProgress {
    @Volatile var files = 0L
    @Volatile var bytes = 0L
    @Volatile var current = ""
}

/** フォルダの中身の大きさを再帰的に数え、大きい順に整理する。Android に依存しないので PC 上でテストできる。 */
object Analyzer {
    /** 1つのフォルダについて、一覧に残す中身の数の上限 */
    const val MAX_CHILDREN = 200

    /** 「大きいファイル」に残す数 */
    const val BIGGEST = 100

    /** これより小さいフォルダは、中身の一覧を残さない(メモリを抑える。調べたいのは大きいところなので) */
    const val MIN_DETAIL = 256L * 1024

    private const val MAX_DEPTH = 32

    /**
     * rootPath 以下を調べる。読めないフォルダは 0 として扱い、止まらない。
     * isLink が true のフォルダは、輪になる恐れがあるので辿らない。check が例外を投げれば中断。
     */
    fun scan(
        fs: Fs,
        rootPath: String,
        rootName: String,
        check: () -> Unit = {},
        progress: ScanProgress? = null,
        minDetail: Long = MIN_DETAIL,
        isLink: (String) -> Boolean = { false },
    ): Analysis {
        val children = HashMap<String, List<DiskNode>>()
        val omitted = HashMap<String, Pair<Int, Long>>()
        // 小さい順に取り出せる山。上限を超えたら、いちばん小さいものを捨てていく
        val heap = PriorityQueue<DiskNode>(compareBy { it.size })

        fun walk(path: String, name: String, depth: Int): DiskNode {
            check()
            progress?.current = path
            val list = if (depth > MAX_DEPTH) null else fs.list(path)
            var size = 0L
            var files = 0
            val nodes = ArrayList<DiskNode>()
            for (c in list.orEmpty()) {
                val n = if (c.isDir) {
                    if (isLink(c.path)) DiskNode(c.path, c.name, true, 0L, 0) else walk(c.path, c.name, depth + 1)
                } else {
                    progress?.let {
                        it.files++
                        it.bytes += c.size
                    }
                    val f = DiskNode(c.path, c.name, false, c.size, 1)
                    if (heap.size < BIGGEST) heap.add(f) else if (f.size > heap.peek()!!.size) {
                        heap.poll()
                        heap.add(f)
                    }
                    f
                }
                nodes.add(n)
                size += n.size
                files += n.files
            }
            if (depth == 0 || size >= minDetail) {
                nodes.sortByDescending { it.size }
                if (nodes.size > MAX_CHILDREN) {
                    omitted[path] = (nodes.size - MAX_CHILDREN) to nodes.drop(MAX_CHILDREN).sumOf { it.size }
                    children[path] = nodes.take(MAX_CHILDREN)
                } else {
                    children[path] = nodes
                }
            }
            return DiskNode(path, name, true, size, files)
        }

        val root = walk(rootPath, rootName, 0)
        return Analysis(root, children, omitted, heap.sortedByDescending { it.size })
    }
}
