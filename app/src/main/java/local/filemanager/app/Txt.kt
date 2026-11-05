package local.filemanager

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * 画面に出す文字の窓口。文字は res の values 系フォルダの strings.xml に置き、ここから取り出す。
 * Compose の中でも、画面を持たない処理(読み取りの失敗メッセージなど)でも、同じ書き方で使える。
 *
 *   Txt(R.string.done)                          … 文字そのまま
 *   Txt(R.string.moved_to, name)                … %1$s などに値を入れる
 *   Txt.n(R.plurals.items_moved, count, extra)  … 件数で言い方が変わる文。件数は %1$d、続く値は %2$s から
 *
 * 言語は端末(Android 13 以上はアプリごとの設定)に合わせて、Android が選ぶ。
 * PC の単体試験では Context が無いので、試験側の TestStrings(strings.xml を読む)に任せる。
 */
object Txt {
    @Volatile private var app: Context? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    /** 選んだ言語を反映した Context に切り替える(Languages から呼ぶ)。 */
    fun useContext(context: Context) {
        app = context
    }

    operator fun invoke(@StringRes id: Int, vararg args: Any?): String {
        val c = app ?: return fallback().string(id, args)
        return if (args.isEmpty()) c.getString(id) else c.getString(id, *args)
    }

    fun n(@PluralsRes id: Int, count: Int, vararg args: Any?): String {
        val c = app ?: return fallback().plural(id, count, args)
        return c.resources.getQuantityString(id, count, count, *args)
    }

    /** 件数が Long のとき(ファイルの数など)。 */
    fun n(@PluralsRes id: Int, count: Long, vararg args: Any?): String {
        val c = app ?: return fallback().plural(id, count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), args)
        return c.resources.getQuantityString(id, count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), count, *args)
    }

    interface Resolver {
        fun string(id: Int, args: Array<out Any?>): String
        fun plural(id: Int, count: Int, args: Array<out Any?>): String
    }

    private fun fallback(): Resolver = runCatching {
        Class.forName("local.filemanager.TestStrings").getField("INSTANCE").get(null) as Resolver
    }.getOrElse {
        object : Resolver {
            override fun string(id: Int, args: Array<out Any?>) = "string#$id"
            override fun plural(id: Int, count: Int, args: Array<out Any?>) = "plural#$id"
        }
    }
}
