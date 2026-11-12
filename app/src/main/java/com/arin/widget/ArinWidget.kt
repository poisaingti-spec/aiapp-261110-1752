package com.arin.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.arin.MainActivity
import com.arin.R
import com.arin.app.Launch

/** Home-screen widget: "Ask Arin" opens a new chat; the mic opens voice mode. */
class ArinWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_arin).apply {
            setOnClickPendingIntent(R.id.widget_ask, open(context, Launch.ACTION_NEW_CHAT, 1))
            setOnClickPendingIntent(R.id.widget_logo, open(context, Launch.ACTION_NEW_CHAT, 1))
            setOnClickPendingIntent(R.id.widget_mic, open(context, Launch.ACTION_VOICE, 2))
        }
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    private fun open(context: Context, action: String, code: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
