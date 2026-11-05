package local.filemanager

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/**
 * アプリの言語の選び方。設定の「言語」で選び、SharedPreferences "fm" の "lang" に残す(空 = 端末に合わせる)。
 * 選んだ言語は、画面(MainActivity.attachBaseContext)と、文字の窓口 Txt(通知など)の両方に反映する。
 * 端末の「アプリの言語」(Android 13 以上)で選んだ場合も、ここが「端末に合わせる」なら、その言語に従う。
 */
object Languages {
    class Lang(val tag: String, val name: String)

    /** 選べる言語。名前は、その言語自身の書き方(どの言語で表示しても同じ)。足したら locales_config.xml にも足す */
    val supported = listOf(Lang("ja", "日本語"), Lang("en", "English"))

    private const val PREFS = "fm"
    private const val KEY = "lang"

    /** 選んでいる言語(「ja」など)。端末に合わせるときは空。 */
    fun saved(ctx: Context): String {
        val t = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        return if (supported.any { it.tag == t }) t else ""
    }

    /** 選んだ言語を反映した Context(選んでいなければ、そのまま返す) */
    fun wrap(ctx: Context): Context {
        val tag = saved(ctx)
        if (tag.isEmpty()) return ctx
        val cfg = Configuration(ctx.resources.configuration)
        cfg.setLocale(Locale.forLanguageTag(tag))
        return ctx.createConfigurationContext(cfg)
    }

    /** アプリ全体(文字の窓口 Txt と、Locale の既定)に、選んだ言語を反映する */
    fun apply(app: Context) {
        val tag = saved(app)
        Txt.useContext(wrap(app))
        Locale.setDefault(if (tag.isEmpty()) Resources.getSystem().configuration.locales[0] else Locale.forLanguageTag(tag))
    }

    /** 言語を選び直す。画面は、呼んだ側が作り直す(Activity.recreate)。 */
    fun change(app: Context, tag: String) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
        apply(app.applicationContext)
    }
}
