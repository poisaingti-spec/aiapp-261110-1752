package com.arin

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The app process. Replies run in [scope], not in a screen's scope, so they keep going
 * when Arin is in the background or its window is closed.
 */
class ArinApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashLog.install(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REPLIES, "Replies", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When Arin finishes a reply while the app is in the background"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WORKING, "Working", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Arin is writing a reply"
            },
        )
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { visibleActivities++ }
            override fun onActivityStopped(activity: Activity) { visibleActivities-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    companion object {
        const val CHANNEL_REPLIES = "replies"
        const val CHANNEL_WORKING = "working"

        lateinit var instance: ArinApp
            private set

        /** App-wide work; an error in one job is written to the crash log instead of closing the app. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CrashLog.handler { if (::instance.isInitialized) instance else null })

        /** The installed version, e.g. "0.9.0 (21)", read from the APK so it is always right. */
        fun versionLabel(context: android.content.Context): String = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            "${info.versionName} (${info.versionCode})"
        }.getOrDefault("?")

        private var visibleActivities = 0

        /** True while any Arin screen is on screen. */
        val inForeground: Boolean get() = visibleActivities > 0
    }
}
