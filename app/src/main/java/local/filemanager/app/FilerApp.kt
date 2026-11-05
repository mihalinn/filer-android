package local.filemanager

import android.app.Application

/** アプリの入口。画面やサービスより先に、文字の窓口を使えるようにする。 */
class FilerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Txt.init(this)
        Languages.apply(this)
    }
}
