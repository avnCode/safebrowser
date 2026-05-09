package com.safebrowser.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps the app process alive and CPU-scheduled while
 * audio/video plays in the background.
 *
 * Without this, Android (especially Samsung One UI) demotes the process to
 * CACHED within seconds of going to the background, throttles CPU, and
 * eventually kills the renderer. A foreground service with the
 * `mediaPlayback` type tells the OS we have active media output and must
 * not be reaped.
 *
 * Lifecycle:
 *   - Started from `MainActivity.onPause()` when `backgroundPlaybackEnabled`
 *     is true.
 *   - Stopped from `MainActivity.onResume()` or when the user toggles the
 *     setting off.
 *   - Self-stops if the notification "Stop" action is tapped.
 */
class PlaybackService : Service() {

    companion object {
        private const val CHANNEL_ID = "safebrowser_playback"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.safebrowser.app.STOP_PLAYBACK"

        fun start(ctx: Context) {
            val intent = Intent(ctx, PlaybackService::class.java)
            ctx.startForegroundService(intent)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PlaybackService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Background playback",
            NotificationManager.IMPORTANCE_LOW   // no sound, shows in shade
        ).apply {
            description = "Keeps audio/video playing when SafeBrowser is in the background"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        // Tap notification → return to the app.
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val launchPi = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Stop" action in the notification.
        val stopIntent = Intent(this, PlaybackService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("SafeBrowser")
            .setContentText("Playing in background")
            .setContentIntent(launchPi)
            .addAction(R.drawable.ic_close, "Stop", stopPi)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
