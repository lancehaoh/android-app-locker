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
import kotlinx.coroutines.*

class NotificationHiderService : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var db: AppLockDatabase
    private lateinit var prefs: PreferencesManager
    private lateinit var nm: NotificationManager

    // Tag we stamp on redacted notifications so we don't re-process them
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
        // Skip notifications we already redacted (avoid infinite loop)
        if (sbn.notification.extras.getBoolean(REDACTED_EXTRA, false)) return
        // Skip our own app's notifications
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

        // Build a replacement notification with hidden content
        val appName = try {
            val info = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            sbn.packageName
        }

        val smallIcon = runCatching {
            IconCompat.createFromIcon(this, original.smallIcon)
        }.getOrNull()

        val redacted = NotificationCompat.Builder(this, REDACTED_CHANNEL)
            .apply {
                if (smallIcon != null) setSmallIcon(smallIcon)
                else setSmallIcon(android.R.drawable.ic_dialog_info)
            }
            .setContentTitle(appName)
            .setContentText(getString(R.string.notification_hidden_text))
            .setSubText(getString(R.string.notification_hidden_subtext))
            .setContentIntent(original.contentIntent)       // tapping still opens the (locked) app
            .setDeleteIntent(original.deleteIntent)         // swipe-to-dismiss still works
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

        // Cancel the original, post the redacted version under the same ID
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
