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
 * コピー・移動などの最中と、時間のかかるダウンロード([BgTasks]。アプリの更新・プレビューの取り込み)の最中だけ動く
 * フォアグラウンドサービス。通知に進み具合を出し、アプリを閉じたり画面が消えたりしても OS に止められないようにする。
 * 操作そのものは [Ops] や [BgTasks] が実行していて、ここは見張りと通知だけを担当する。
 */
class TransferService : Service() {
    private var loop: Job? = null
    private var lastStartId = 0
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private val posted = HashSet<Int>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_CANCEL) {
            val taskId = intent.getIntExtra(EXTRA_TASK, -1)
            if (taskId >= 0) BgTasks.cancel(taskId) else Ops.cancel()
            // 操作が終わったあとに古い通知のボタンが押されたとき
            if (loop == null && Ops.busy == null && BgTasks.tracked().isEmpty()) stopSelf(startId)
            return START_NOT_STICKY
        }
        ensureChannels(this)
        // startForegroundService() で起動された以上、必ず startForeground() を呼ぶ必要がある(操作が既に終わっていても)
        ServiceCompat.startForeground(this, ID_PROGRESS, desired().getValue(ID_PROGRESS), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (loop?.isActive != true) loop = Ops.scope.launch { watch() }
        return START_NOT_STICKY
    }

    private fun working() = Ops.busy != null || BgTasks.tracked().isNotEmpty()

    private suspend fun watch() {
        acquireLocks()
        var sawOps = false
        try {
            val nm = getSystemService(NotificationManager::class.java)
            while (working()) {
                if (Ops.busy != null) sawOps = true
                val now = desired()
                for ((id, n) in now) nm.notify(id, n)
                for (id in posted - now.keys) nm.cancel(id)
                posted.clear()
                posted.addAll(now.keys - ID_PROGRESS)
                delay(500)
            }
        } finally {
            releaseLocks()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        val nm = getSystemService(NotificationManager::class.java)
        for (id in posted) nm.cancel(id)
        posted.clear()
        val result = Ops.lastResult
        if (sawOps && !Ops.appVisible && result != null) {
            nm.notify(ID_RESULT, resultNotification(this, result, false))
        }
        loop = null
        // 続けて次の操作が始まっていたら(新しい startId が来ていたら)止まらない
        stopSelf(lastStartId)
    }

    /**
     * いま出す通知(ID → 通知)。ID_PROGRESS は、フォアグラウンドの通知。
     * コピー・移動があればそれを、なければ最初のダウンロードを出し、ほかのダウンロードは別の通知にする。
     */
    private fun desired(): Map<Int, Notification> {
        val tasks = BgTasks.tracked()
        val map = LinkedHashMap<Int, Notification>()
        if (Ops.busy != null || tasks.isEmpty()) {
            map[ID_PROGRESS] = progressNotification()
            tasks.forEach { map[ID_TASK + it.id % 500] = taskNotification(it) }
        } else {
            map[ID_PROGRESS] = taskNotification(tasks[0])
            tasks.drop(1).forEach { map[ID_TASK + it.id % 500] = taskNotification(it) }
        }
        return map
    }

    private fun cancelIntent(requestCode: Int, taskId: Int): PendingIntent = PendingIntent.getService(
        this, requestCode, Intent(this, TransferService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_TASK, taskId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progressNotification(): Notification {
        val c = noticeContent(Ops.busy, Ops.progressView)
        return buildProgress(c, cancelIntent(0, -1))
    }

    private fun taskNotification(t: BgTask): Notification {
        val c = taskNotice(t.title, t.done, t.total, System.currentTimeMillis() - t.startedAt)
        return buildProgress(c, cancelIntent(1000 + t.id % 500, t.id))
    }

    private fun buildProgress(c: NoticeContent, cancel: PendingIntent): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_transfer)
            .setContentTitle(c.title)
            .setContentIntent(openApp(this, false))
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

    /** 画面が消えてスリープしても、ダウンロードやコピーが止まらず NAS との通信も切れないようにする。 */
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
        const val EXTRA_TASK = "task"

        /** 通知をタップしてアプリを開いたとき、更新のインストールの画面を開く印 */
        const val EXTRA_INSTALL_UPDATE = "installUpdate"
        private const val CHANNEL_PROGRESS = "transfer"
        private const val CHANNEL_RESULT = "transfer_result"
        private const val ID_PROGRESS = 1
        private const val ID_RESULT = 2
        private const val ID_TASK = 100
        internal const val ID_TASK_RESULT = 1000

        private fun openApp(ctx: Context, installUpdate: Boolean): PendingIntent = PendingIntent.getActivity(
            ctx, if (installUpdate) 1 else 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_INSTALL_UPDATE, installUpdate),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun resultNotification(ctx: Context, text: String, installUpdate: Boolean): Notification =
            NotificationCompat.Builder(ctx, CHANNEL_RESULT)
                .setSmallIcon(R.drawable.ic_stat_transfer)
                .setContentTitle("ファイラー")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openApp(ctx, installUpdate))
                .setAutoCancel(true)
                .build()

        /** アプリが見えていないときに終わった処理を、通知で知らせる。 */
        internal fun postResult(ctx: Context, id: Int, text: String, installUpdate: Boolean) {
            runCatching {
                ensureChannels(ctx)
                ctx.getSystemService(NotificationManager::class.java).notify(id, resultNotification(ctx, text, installUpdate))
            }
        }

        fun ensureChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "コピー・移動・ダウンロードの進み具合", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "実行中のコピー・移動・圧縮・ダウンロードなどの進み具合を表示します"; setShowBadge(false) },
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_RESULT, "完了のお知らせ", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "アプリを閉じている間に終わった処理をお知らせします" },
            )
        }
    }
}
