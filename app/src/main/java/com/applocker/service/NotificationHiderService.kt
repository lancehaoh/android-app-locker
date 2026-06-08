package com.applocker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import com.applocker.R
import com.applocker.data.AppLockDatabase
import com.applocker.data.PreferencesManager
import com.applocker.ui.AppListActivity
import kotlinx.coroutines.*

class NotificationHiderService : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var db: AppLockDatabase
    private lateinit var prefs: PreferencesManager
    private lateinit var nm: NotificationManager

    private val REDACTED_EXTRA = "com.applocker.redacted"
    private val REDACTED_CHANNEL = "applocker_redacted"

    override fun onCreate() {
        super.onCreate()
        db = AppLockDatabase.getInstance(this)
        prefs = PreferencesManager.getInstance(this)
        nm = getSystemService(NotificationManager::class.java)
        ensureRedactedChannel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.notification.extras.getBoolean(REDACTED_EXTRA, false)) return
        if (sbn.packageName == packageName) return

        scope.launch {
            val isLocked = db.lockedAppDao().isLocked(sbn.packageName)
            if (!isLocked) return@launch

            withContext(Dispatchers.Main) {
                redactAndRepost(sbn)
            }
        }
    }

    private fun redactAndRepost(sbn: StatusBarNotification) {
        val original = sbn.notification
        val extras = original.extras
        val isMessaging = sbn.packageName in AppListActivity.MESSAGING_PACKAGES

        // For messaging apps: keep the sender name, replace only the message body.
        // For other apps: replace everything with the app name.
        val title: String = if (isMessaging) {
            // Try to get sender name from notification extras (works for most messaging apps)
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
                ?: run {
                    val info = runCatching {
                        packageManager.getApplicationInfo(sbn.packageName, 0)
                    }.getOrNull()
                    info?.let { packageManager.getApplicationLabel(it).toString() }
                        ?: sbn.packageName
                }
        } else {
            runCatching {
                val info = packageManager.getApplicationInfo(sbn.packageName, 0)
                packageManager.getApplicationLabel(info).toString()
            }.getOrDefault(sbn.packageName)
        }

        val body = getString(
            if (isMessaging) R.string.notification_message_hidden
            else R.string.notification_hidden_text
        )

        val smallIcon = runCatching {
            IconCompat.createFromIcon(this, original.smallIcon)
        }.getOrNull()

        val redacted = NotificationCompat.Builder(this, REDACTED_CHANNEL)
            .apply {
                if (smallIcon != null) setSmallIcon(smallIcon)
                else setSmallIcon(android.R.drawable.ic_dialog_info)
            }
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(original.contentIntent)
            .setDeleteIntent(original.deleteIntent)
            .setGroup(original.group)
            .setGroupSummary(original.flags and Notification.FLAG_GROUP_SUMMARY != 0)
            .setAutoCancel(original.flags and Notification.FLAG_AUTO_CANCEL != 0)
            .setOngoing(original.flags and Notification.FLAG_ONGOING_EVENT != 0)
            .setWhen(original.`when`)
            .setShowWhen(true)
            .setPriority(original.priority)
            .setCategory(original.category ?: NotificationCompat.CATEGORY_MESSAGE)
            .addExtras(android.os.Bundle().apply {
                putBoolean(REDACTED_EXTRA, true)
            })
            .build()

        cancelNotification(sbn.key)
        nm.notify(sbn.tag, sbn.id, redacted)
    }

    private fun ensureRedactedChannel() {
        if (nm.getNotificationChannel(REDACTED_CHANNEL) == null) {
            val channel = NotificationChannel(
                REDACTED_CHANNEL,
                "Locked App Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications from locked apps (content hidden)"
            }
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
