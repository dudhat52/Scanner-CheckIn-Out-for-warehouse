package com.idlogistics.scannercheckout

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.widget.Toast

/** Opens MainActivity from the background. */
object Launcher {
    const val ACTION_SCAN = "com.idlogistics.scannercheckout.action.SCAN"
    const val ACTION_ADMIN = "com.idlogistics.scannercheckout.action.ADMIN"
    const val ACTION_FILL_LOGIN = "com.idlogistics.scannercheckout.action.FILL_LOGIN"
    const val EXTRA_PAYLOAD = "payload"

    fun open(ctx: Context, action: String, payload: String?) {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            this.action = action
            putExtra(EXTRA_PAYLOAD, payload)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Log.e("Launcher", "startActivity failed", e)
        }
        // Android 10/11 silently blocks background launches unless "Display over other apps" is
        // granted. Without it, fall back to a full-screen/heads-up notification the associate taps.
        if (!Settings.canDrawOverlays(ctx)) {
            Notifications.showLaunchFallback(ctx, intent)
        }
    }
}

object Notifications {
    const val CHANNEL_SERVICE = "listener"
    const val CHANNEL_ALERT = "scan_alert"
    const val ID_SERVICE = 1001
    const val ID_FALLBACK = 1002

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_SERVICE) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_SERVICE, "Scanner listener", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
        if (nm.getNotificationChannel(CHANNEL_ALERT) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERT, "Scan received", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    fun serviceNotification(ctx: Context): Notification {
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(ctx, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Scanner Check-In ready")
            .setContentText("Scan a department QR code")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    fun showLaunchFallback(ctx: Context, intent: Intent) {
        ensureChannels(ctx)
        val pi = PendingIntent.getActivity(
            ctx, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(ctx, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Department QR scanned")
            .setContentText("Tap to enter your Login ID")
            .setCategory(Notification.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_FALLBACK, n)
    }

    fun cancelFallback(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(ID_FALLBACK)
    }
}

/** Short sound/vibration feedback. The PM86 media volume is muted by default, so we also vibrate. */
object Feedback {
    private val main = Handler(Looper.getMainLooper())

    fun toast(ctx: Context, msg: String) {
        main.post { Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }

    fun success(ctx: Context) {
        tone(ToneGenerator.TONE_PROP_ACK, 150)
        vibrate(ctx, 80)
    }

    fun error(ctx: Context) {
        tone(ToneGenerator.TONE_PROP_NACK, 300)
        vibrate(ctx, 400)
    }

    private fun tone(type: Int, ms: Int) {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
            tg.startTone(type, ms)
            main.postDelayed({ tg.release() }, (ms + 100).toLong())
        } catch (e: Exception) {
            // Tone hardware busy – ignore.
        }
    }

    private fun vibrate(ctx: Context, ms: Long) {
        try {
            val v = ctx.getSystemService(Vibrator::class.java) ?: return
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            // ignore
        }
    }
}
