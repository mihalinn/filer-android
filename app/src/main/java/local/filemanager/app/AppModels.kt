package local.filemanager

import java.io.File

data class Volume(val label: String, val dir: File, val total: Long, val free: Long)

/** コピー・移動の待ち状態。どの場所(fsId)のどのファイルかを覚えておく。 */
data class Clip(val fsId: String, val items: List<FileItem>, val move: Boolean)

/** ブックマークと履歴に使う「場所」。 */
data class Place(val fsId: String, val path: String)

data class Crumb(val label: String, val path: String)

/** 画面の種類。 */
sealed interface Screen {
    data object Home : Screen
    data class Dir(val fsId: String, val path: String) : Screen
    data class Cat(val cat: Category) : Screen
    data object Trash : Screen
    data object Network : Screen

    /** 容量の分析。fsId が null のときは、分析する場所を選ぶ画面。 */
    data class Analyze(val fsId: String?, val path: String?) : Screen

    /** 細かい設定の画面。page はその中の階層(設定の一覧 / ライセンス)。 */
    data class Settings(val page: SettingsPage = SettingsPage.MAIN) : Screen
}

enum class SettingsPage { MAIN, LICENSES, FORMATS }

/** ビューアの文字の大きさ(sp)の範囲。ビューアのメニューの文字サイズ欄と同じ。 */
const val VIEWER_FONT_MIN = 9
const val VIEWER_FONT_MAX = 28
