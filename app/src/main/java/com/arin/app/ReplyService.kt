package com.arin.app

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.arin.ArinApp
import com.arin.MainActivity
import com.arin.R

/**
 * Keeps Arin's process alive while a reply is being written, so it finishes even if the
 * person switches apps. Shows a quiet "Arin is replying…" notification while it runs.
 */
class ReplyService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            releaseLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // Keep the processor and Wi-Fi awake with the screen off, so downloads, builds and replies keep going.
        if (wake?.isHeld != true) wake = (getSystemService(POWER_SERVICE) as android.os.PowerManager)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Arin:working").apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        if (wifi?.isHeld != true) wifi = runCatching {
            (applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager)
                .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Arin:working").apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        val n = NotificationCompat.Builder(this, ArinApp.CHANNEL_WORKING)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (holders.all { it == "reply" }) "Arin is replying…" else "Arin is working — it keeps going in the background")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp(this, null))
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID_WORKING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ID_WORKING, n)
        }.onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    private var wake: android.os.PowerManager.WakeLock? = null
    private var wifi: android.net.wifi.WifiManager.WifiLock? = null

    private fun releaseLocks() {
        runCatching { if (wake?.isHeld == true) wake?.release() }
        runCatching { if (wifi?.isHeld == true) wifi?.release() }
    }

    override fun onDestroy() { releaseLocks(); super.onDestroy() }

    companion object {
        private const val ID_WORKING = 1
        private const val ACTION_STOP = "com.arin.STOP_REPLY_SERVICE"
        const val EXTRA_CHAT_ID = "com.arin.CHAT_ID"

        /** What is keeping Arin running in the background right now (chat replies, installs, the helper…). */
        private val holders = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        /** How many replies/tasks are running: the background notification stays until the LAST one ends. */
        private val replies = java.util.concurrent.atomic.AtomicInteger(0)

        fun start(context: Context) { replies.incrementAndGet(); hold(context, "reply") }

        fun stop(context: Context) {
            if (replies.updateAndGet { maxOf(0, it - 1) } == 0) release(context, "reply")
        }

        /** Keeps Arin running in the background while [key]'s work goes on (several can run at once). */
        fun hold(context: Context, key: String) {
            holders += key
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ReplyService::class.java)) }
        }

        /** [key]'s work is done; the background service stops only when nothing else is still running. */
        fun release(context: Context, key: String) {
            holders -= key
            if (holders.isEmpty()) runCatching { context.startService(Intent(context, ReplyService::class.java).setAction(ACTION_STOP)) }
        }

        fun openApp(context: Context, chatId: String?): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply { if (chatId != null) putExtra(EXTRA_CHAT_ID, chatId) }
            return PendingIntent.getActivity(
                context, chatId?.hashCode() ?: 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** "Arin replied" with the start of the reply; tapping it opens that chat. */
        fun notifyReply(context: Context, chatId: String, title: String, text: String) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val n: Notification = NotificationCompat.Builder(context, ArinApp.CHANNEL_REPLIES)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(text.take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(600)))
                .setAutoCancel(true)
                .setContentIntent(openApp(context, chatId))
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(chatId.hashCode(), n) }
        }
    }
}
