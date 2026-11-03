package local.filemanager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * コピー・移動などの最中だけ動くフォアグラウンドサービス。
 * 通知に進み具合を出し、アプリを閉じても OS に止められないようにする。操作そのものは [Ops] が実行していて、
 * ここは見張りと通知だけを担当する。
 */
class TransferService : Service() {
    private var loop: Job? = null
    private var lastStartId = 0
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_CANCEL) {
            Ops.cancel()
            // 操作が終わったあとに古い通知のボタンが押されたとき
            if (loop == null && Ops.busy == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        ensureChannels(this)
        // startForegroundService() で起動された以上、必ず startForeground() を呼ぶ必要がある(操作が既に終わっていても)
        ServiceCompat.startForeground(this, ID_PROGRESS, progressNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (loop?.isActive != true) loop = Ops.scope.launch { watch() }
        return START_NOT_STICKY
    }

    private suspend fun watch() {
        acquireLocks()
        try {
            val nm = getSystemService(NotificationManager::class.java)
            while (Ops.busy != null) {
                nm.notify(ID_PROGRESS, progressNotification())
                delay(500)
            }
        } finally {
            releaseLocks()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        val result = Ops.lastResult
        if (!Ops.appVisible && result != null) {
            getSystemService(NotificationManager::class.java).notify(ID_RESULT, resultNotification(result))
        }
        loop = null
        // 続けて次の操作が始まっていたら(新しい startId が来ていたら)止まらない
        stopSelf(lastStartId)
    }

    private fun progressNotification(): Notification {
        val c = noticeContent(Ops.busy, Ops.progressView)
        val cancel = PendingIntent.getService(
            this, 0, Intent(this, TransferService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_transfer)
            .setContentTitle(c.title)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "キャンセル", cancel)
        if (c.text.isNotEmpty()) b.setContentText(c.text).setStyle(NotificationCompat.BigTextStyle().bigText(c.detail))
        if (c.percent != null) b.setProgress(100, c.percent, false) else b.setProgress(0, 0, true)
        return b.build()
    }

    private fun resultNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_transfer)
            .setContentTitle("ファイラー")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** 画面が消えて端末がスリープしても、コピーが止まらず NAS との通信も切れないようにする。 */
    private fun acquireLocks() {
        runCatching {
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "filer:transfer")
                .apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        }
        runCatching {
            @Suppress("DEPRECATION")
            wifi = applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "filer:transfer")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        runCatching { wifi?.takeIf { it.isHeld }?.release() }
        wake = null
        wifi = null
    }

    override fun onDestroy() {
        loop?.cancel()
        releaseLocks()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL = "local.filemanager.CANCEL"
        private const val CHANNEL_PROGRESS = "transfer"
        private const val CHANNEL_RESULT = "transfer_result"
        private const val ID_PROGRESS = 1
        private const val ID_RESULT = 2

        fun ensureChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "コピー・移動の進み具合", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "実行中のコピー・移動・圧縮などの進み具合を表示します"; setShowBadge(false) },
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_RESULT, "完了のお知らせ", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "アプリを閉じている間に終わった処理をお知らせします" },
            )
        }
    }
}
