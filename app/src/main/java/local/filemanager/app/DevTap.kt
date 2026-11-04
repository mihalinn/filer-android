package local.filemanager

/** 設定のバージョンを続けて押した回数を数え、開発者向けを出すかを決める。Android に依存しない。 */
object DevTap {
    const val NEED = 7

    /** 押す間隔がこれ(ミリ秒)より空いたら、数え直す */
    const val GAP_MS = 2000L

    /** この回数から、あと何回かを知らせる */
    const val HINT_FROM = 3

    /** 今回の押下で数えた回数。前回から間が空いていれば 1 から。 */
    fun next(count: Int, lastMs: Long, nowMs: Long): Int = if (count <= 0 || nowMs - lastMs > GAP_MS) 1 else count + 1

    /** 知らせる残り回数。知らせない回(まだ早い・開いた後)は null。 */
    fun remaining(count: Int): Int? = (NEED - count).takeIf { count >= HINT_FROM && it > 0 }
}
