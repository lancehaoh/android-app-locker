package com.applocker

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class AppLockerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            MONITOR_CHANNEL_ID,
            "App Lock Monitor",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Monitors foreground apps to enforce locks"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val MONITOR_CHANNEL_ID = "app_lock_monitor"
        const val MONITOR_NOTIFICATION_ID = 1001
    }
}
