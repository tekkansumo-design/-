package com.tekkansumo.ebayscanner

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** 利益が見込める商品を見つけたときに、振動・音・通知で知らせる。 */
class Alerter(private val ctx: Context) {

    companion object {
        const val CHANNEL = "profit_hit"
    }

    private var tone: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
    } catch (_: Exception) {
        null
    }
    private var nextId = 100

    init {
        val ch = NotificationChannel(CHANNEL, ctx.getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH)
        ch.enableVibration(false) // 振動はアプリ側で鳴らす
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    fun alert(title: String, body: String, url: String, sound: Boolean) {
        vibrate()
        if (sound) tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
        notify(title, body, url)
    }

    private fun vibrate() {
        val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 180, 90, 180, 90, 400), -1))
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun notify(title: String, body: String, url: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val pi = PendingIntent.getActivity(
            ctx, nextId, Intent(Intent.ACTION_VIEW, Uri.parse(url)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(nextId++, n)
    }

    fun release() {
        tone?.release()
        tone = null
    }
}
