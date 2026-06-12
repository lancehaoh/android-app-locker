package com.applocker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import com.applocker.R
import com.applocker.data.AppLockDatabase
import com.applocker.data.PreferencesManager
import com.applocker.ui.AppListActivity
import com.applocker.ui.LockActivity
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

            // Small delay so the original notification is fully dismissed before
            // we post the redacted replacement — prevents Samsung from suppressing
            // it as a duplicate.
            delay(300)

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

        // --- FIX 1: Route taps through LockActivity so auth is required ----------
        // Instead of original.contentIntent (which opens the app directly), we
        // launch LockActivity. After successful unlock, LockActivity opens the app.
        val lockIntent = Intent(this, LockActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(LockActivity.EXTRA_LOCKED_PACKAGE, sbn.packageName)
            putExtra(LockActivity.EXTRA_LAUNCH_APP_AFTER_UNLOCK, true)
        }
        val lockPendingIntent = PendingIntent.getActivity(
            this,
            sbn.id,
            lockIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // --- FIX 2: Show the messaging app's own icon as the large icon ----------
        // smallIcon must belong to the posting app (App Locker), but largeIcon can
        // be any bitmap — so we use the locked app's launcher icon there.
        val appIconBitmap: Bitmap? = runCatching {
            val drawable = packageManager.getApplicationIcon(sbn.packageName)
            when (drawable) {
                is BitmapDrawable -> drawable.bitmap
                else -> Bitmap.createBitmap(
                    drawable.intrinsicWidth.coerceAtLeast(1),
                    drawable.intrinsicHeight.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                ).also { bmp ->
                    val canvas = Canvas(bmp)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                }
            }
        }.getOrNull()

        val redacted = NotificationCompat.Builder(this, REDACTED_CHANNEL)
            .apply {
                if (smallIcon != null) setSmallIcon(smallIcon)
                else setSmallIcon(R.mipmap.ic_launcher)
            }
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(lockPendingIntent)          // ← auth required on tap
            .setDeleteIntent(original.deleteIntent)
            .apply { if (appIconBitmap != null) setLargeIcon(appIconBitmap) }  // ← app logo
            .setGroup(original.group)
            .setGroupSummary(original.flags and Notification.FLAG_GROUP_SUMMARY != 0)
            .setAutoCancel(true)   // ← always dismiss the notification once tapped
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
