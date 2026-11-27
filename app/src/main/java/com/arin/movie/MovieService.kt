package com.arin.movie

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.arin.ArinApp
import com.arin.R
import com.arin.app.ReplyService

/**
 * Keeps a film going when Arin is in the background or the screen is off: a foreground service with a
 * progress notification, plus a partial wake lock so the phone's CPU doesn't sleep mid-render.
 */
class MovieService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val n = build(this, intent?.getStringExtra(EXTRA_TEXT) ?: "Starting…", intent?.getIntExtra(EXTRA_PERMILLE, -1) ?: -1)
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ID, n)
        }.onFailure { stopSelf(); return START_NOT_STICKY }
        if (wake == null) {
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Arin:movie")
                .apply { setReferenceCounted(false); acquire(5 * 3600_000L) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        wake = null
    }

    companion object {
        private const val ID = 4207
        private const val DONE_ID = 4208
        private const val ACTION_STOP = "com.arin.STOP_MOVIE_SERVICE"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_PERMILLE = "permille"

        private fun canNotify(context: Context) = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        private fun build(context: Context, text: String, permille: Int): Notification =
            NotificationCompat.Builder(context, ArinApp.CHANNEL_WORKING)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Making your movie")
                .setContentText(text)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setProgress(1000, permille.coerceAtLeast(0), permille < 0)
                .setContentIntent(ReplyService.openApp(context, null))
                .build()

        /** Start (or keep) the service and show [text] with progress 0..1, or null for "working, unknown". */
        fun start(context: Context, text: String, fraction: Float? = null) {
            val i = Intent(context, MovieService::class.java)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_PERMILLE, fraction?.let { (it.coerceIn(0f, 1f) * 1000).toInt() } ?: -1)
            runCatching { ContextCompat.startForegroundService(context, i) }
        }

        /** Update the notification without restarting the service. */
        fun update(context: Context, text: String, fraction: Float?) {
            if (!canNotify(context)) return
            val n = build(context, text, fraction?.let { (it.coerceIn(0f, 1f) * 1000).toInt() } ?: -1)
            try { NotificationManagerCompat.from(context).notify(ID, n) } catch (e: SecurityException) { /* notifications not allowed */ }
        }

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, MovieService::class.java).setAction(ACTION_STOP)) }
        }

        /** A normal, dismissable "finished" (or "stopped") notification. */
        fun notifyDone(context: Context, title: String, text: String) {
            if (!canNotify(context)) return
            val n = NotificationCompat.Builder(context, ArinApp.CHANNEL_REPLIES)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(ReplyService.openApp(context, null))
                .build()
            try { NotificationManagerCompat.from(context).notify(DONE_ID, n) } catch (e: SecurityException) { /* notifications not allowed */ }
        }
    }
}
