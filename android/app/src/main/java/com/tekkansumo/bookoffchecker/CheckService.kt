package com.tekkansumo.bookoffchecker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONObject

/**
 * チェックをフォアグラウンドサービスで動かす。
 * 画面を消しても Android に止められず、進捗を通知に出し続ける。
 */
class CheckService : Service() {

    companion object {
        const val CH_PROGRESS = "check_progress"
        const val CH_DONE = "check_done"
        const val NOTI_PROGRESS = 1
        const val NOTI_DONE = 2
        const val ACTION_CANCEL = "com.tekkansumo.bookoffchecker.CANCEL"
    }

    private var lastShown = -1

    private val listener: (String, JSONObject) -> Unit = { kind, data ->
        when (kind) {
            "row" -> {
                val done = data.optInt("done")
                val total = data.optInt("total")
                if (done != lastShown) {
                    lastShown = done
                    nm().notify(NOTI_PROGRESS, progressNoti(done, total))
                }
            }
            "end" -> {
                showDone(data)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun nm() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            Checker.cancel()
            return START_NOT_STICKY
        }

        createChannels()
        startForeground(NOTI_PROGRESS, progressNoti(Checker.done, Checker.total))

        Checker.addListener(listener)
        if (!Checker.start(Store.ids(this))) {
            // すでに走っている。通知だけ出し直して購読を続ける
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Checker.removeListener(listener)
        super.onDestroy()
    }

    private fun createChannels() {
        val m = nm()
        if (m.getNotificationChannel(CH_PROGRESS) == null) {
            m.createNotificationChannel(
                NotificationChannel(CH_PROGRESS, "チェックの進捗", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
        if (m.getNotificationChannel(CH_DONE) == null) {
            m.createNotificationChannel(
                NotificationChannel(CH_DONE, "チェック完了", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    private fun openIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cancelIntent(): PendingIntent {
        val i = Intent(this, CheckService::class.java).setAction(ACTION_CANCEL)
        return PendingIntent.getService(
            this, 1, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun progressNoti(done: Int, total: Int): Notification =
        NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("在庫をチェック中")
            .setContentText(if (total > 0) "$done / $total　並列 ${Checker.workers}" else "準備中")
            .setProgress(maxOf(total, 1), done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent())
            .addAction(0, "中止", cancelIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun showDone(data: JSONObject) {
        val done = data.optInt("done")
        val total = data.optInt("total")
        val hits = data.optInt("hits")
        val text = if (data.optBoolean("cancelled")) {
            "中止しました（$done / $total 件）"
        } else {
            "$done 件を確認、在庫あり $hits 件"
        }
        nm().notify(
            NOTI_DONE,
            NotificationCompat.Builder(this, CH_DONE)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("在庫チェック完了")
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(openIntent())
                .build()
        )
    }
}
