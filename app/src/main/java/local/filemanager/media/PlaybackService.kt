package local.filemanager

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 動画・音声の再生エンジン(ExoPlayer)の持ち主。画面ではなくここが持つので、画面を消したり別のアプリに切り替えたりしても再生が続く。
 * プレイヤー画面は [acquire] で受け取り、見えている間だけ [attachUi] で自分を知らせる。画面を閉じる(×・戻る)ときは [stop]。
 * すべてメインスレッドで呼ぶ。
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal object PlaybackHolder {
    /** 一時停止のまま、画面も開いていないときに、再生を終えるまでの時間 */
    const val IDLE_STOP_MS = 10 * 60 * 1000L

    var player: ExoPlayer? = null
        private set
    private var owner: Any? = null
    private var appContext: Context? = null
    private var uiAttached = false
    private val handler = Handler(Looper.getMainLooper())
    private val idleStop = Runnable { if (player?.isPlaying != true) stop() }

    @Volatile
    internal var service: PlaybackService? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            // 画面が無いまま最後まで再生し終えたら、終える(画面があるときは、もう一度再生できるよう残す)
            if (playbackState == Player.STATE_ENDED && !uiAttached) stop() else refreshIdle()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            refreshIdle()
        }
    }

    /** 同じ依頼(同じ画面の開き方)なら、いまの再生を引き継ぐ。違えば前の再生を終えて、新しく作る。 */
    fun acquire(ctx: Context, req: FmViewModel.ViewerRequest, subs: List<SideSub>): ExoPlayer {
        val cur = player
        if (cur != null && owner === req) return cur
        release()
        val app = ctx.applicationContext
        appContext = app
        val p = createPlayer(app, req, subs)
        p.addListener(listener)
        player = p
        owner = req
        service?.rebind(p)
        return p
    }

    /** プレイヤー画面が見える状態になった。再生サービス(通知・画面オフでも続ける役)を動かす。 */
    fun attachUi() {
        uiAttached = true
        handler.removeCallbacks(idleStop)
        val app = appContext ?: return
        if (player != null && service == null) runCatching { app.startService(Intent(app, PlaybackService::class.java)) }
    }

    /** プレイヤー画面が消えた(画面を閉じたのではなく、アプリ側の都合で)。再生は続ける。 */
    fun detachUi() {
        uiAttached = false
        if (player?.playbackState == Player.STATE_ENDED) stop() else refreshIdle()
    }

    /** 再生を終えて、通知も消す。画面を閉じたときに呼ぶ。 */
    fun stop() {
        release()
        service?.stopSelf()
    }

    /** 再生サービスが先に終わったとき(OS に止められたなど)。画面が無ければ再生も終える。 */
    internal fun onServiceGone() {
        service = null
        if (!uiAttached) release()
    }

    private fun release() {
        handler.removeCallbacks(idleStop)
        val p = player ?: return
        // 通知の側(セッション)を先に外してから、再生エンジンを手放す
        service?.dropSession()
        player = null
        owner = null
        p.removeListener(listener)
        p.release()
    }

    private fun refreshIdle() {
        handler.removeCallbacks(idleStop)
        val p = player ?: return
        if (!uiAttached && !p.isPlaying) handler.postDelayed(idleStop, IDLE_STOP_MS)
    }
}

/** 通知の見た目。曲のタグに題名が無いときは、ファイル名を題名にする。 */
@androidx.annotation.OptIn(UnstableApi::class)
private class FilerNotificationProvider(ctx: Context) : DefaultMediaNotificationProvider(
    ctx,
    NotificationIdProvider { DEFAULT_NOTIFICATION_ID },
    DEFAULT_CHANNEL_ID,
    R.string.playback_channel_name,
) {
    override fun getNotificationContentTitle(metadata: MediaMetadata): CharSequence? = metadata.title ?: metadata.displayTitle
}

/**
 * 画面を消しても再生を続ける役(メディア再生のフォアグラウンドサービス)。
 * 通知に、曲名・ジャケット・前へ/再生・一時停止/次へ・シークバーを出し、ロック画面やイヤホンのボタンからも操作できる(Media3 が作る)。
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        PlaybackHolder.service = this
        setMediaNotificationProvider(FilerNotificationProvider(this))
        val p = PlaybackHolder.player
        if (p == null) {
            stopSelf()
            return
        }
        bind(p)
    }

    /** 別のファイルの再生に切り替わったとき、通知の持ち主を付け替える。 */
    internal fun rebind(p: Player) {
        dropSession()
        bind(p)
    }

    internal fun dropSession() {
        session?.let {
            removeSession(it)
            it.release()
        }
        session = null
    }

    private fun bind(p: Player) {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, MainActivity::class.java)
        val open = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val s = MediaSession.Builder(this, p).setSessionActivity(open).build()
        session = s
        addSession(s)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** 最近使ったアプリから消されたとき。流れていなければ終える(流れているあいだは、通知から操作できるまま続ける)。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (PlaybackHolder.player?.isPlaying != true) PlaybackHolder.stop()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        PlaybackHolder.onServiceGone()
        super.onDestroy()
    }
}
