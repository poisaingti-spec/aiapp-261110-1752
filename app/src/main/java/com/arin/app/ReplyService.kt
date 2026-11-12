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
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val n = NotificationCompat.Builder(this, ArinApp.CHANNEL_WORKING)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Arin is replying…")
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

    companion object {
        private const val ID_WORKING = 1
        private const val ACTION_STOP = "com.arin.STOP_REPLY_SERVICE"
        const val EXTRA_CHAT_ID = "com.arin.CHAT_ID"

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ReplyService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, ReplyService::class.java).setAction(ACTION_STOP)) }
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
