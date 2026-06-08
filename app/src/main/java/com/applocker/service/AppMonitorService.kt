package com.applocker.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.applocker.AppLockerApp
import com.applocker.R
import com.applocker.data.AppLockDatabase
import com.applocker.data.PreferencesManager
import com.applocker.ui.LockActivity
import com.applocker.ui.MainActivity
import kotlinx.coroutines.*

/**
 * Polls UsageStatsManager every 300 ms to detect the foreground app.
 * When a locked app comes to the foreground, it launches LockActivity as an overlay.
 *
 * UsageStatsManager is used because it works reliably on Samsung One UI without
 * requiring Accessibility Service, which Samsung may restrict.
 */
class AppMonitorService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var prefs: PreferencesManager
    private lateinit var db: AppLockDatabase

    // Package that was last shown the lock screen (cleared by LockActivity on success)
    private var lastLockedPackage: String? = null
    // Timestamp of last successful unlock per package
    private val unlockedPackages = mutableMapOf<String, Long>()
    // Previously seen foreground package — lock check only fires on transition
    private var previousPkg: String? = null
    // Package currently in the foreground (used to detect active use)
    private var currentForegroundPkg: String? = null

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager.getInstance(this)
        db = AppLockDatabase.getInstance(this)
        startForeground(AppLockerApp.MONITOR_NOTIFICATION_ID, buildNotification())
        startMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UNLOCK_SUCCESS -> {
                val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return START_STICKY
                unlockedPackages[pkg] = System.currentTimeMillis()
                lastLockedPackage = null
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startMonitoring() {
        scope.launch {
            val usageStatsManager =
                getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val gracePeriod = prefs.gracePeriodMs

            while (isActive) {
                val currentPkg = getForegroundPackage(usageStatsManager)

                if (currentPkg != null && currentPkg != packageName) {
                    // Only evaluate locking on a foreground TRANSITION (app just switched)
                    val justSwitchedTo = currentPkg != currentForegroundPkg

                    if (justSwitchedTo) {
                        val isLocked = db.lockedAppDao().isLocked(currentPkg)
                        if (isLocked) {
                            val lastUnlock = unlockedPackages[currentPkg] ?: 0L
                            val withinGrace = (System.currentTimeMillis() - lastUnlock) < gracePeriod
                            val cameFromAppLocker = previousPkg == packageName

                            if (!withinGrace && !cameFromAppLocker && lastLockedPackage != currentPkg) {
                                lastLockedPackage = currentPkg
                                showLockScreen(currentPkg)
                            }
                        } else {
                            lastLockedPackage = null
                        }

                        // Update tracking (ignore the lock screen activity itself)
                        if (currentPkg != "com.applocker.lock") {
                            previousPkg = currentForegroundPkg
                            currentForegroundPkg = currentPkg
                        }
                    }
                } else if (currentPkg == packageName) {
                    if (currentForegroundPkg != packageName) {
                        previousPkg = currentForegroundPkg
                        currentForegroundPkg = packageName
                    }
                }

                delay(300)
            }
        }
    }

    private fun getForegroundPackage(usm: UsageStatsManager): String? {
        val end = System.currentTimeMillis()
        val begin = end - 3_000L
        val events = usm.queryEvents(begin, end) ?: return null
        var last: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                last = event.packageName
            }
        }
        return last
    }

    private fun showLockScreen(packageName: String) {
        val intent = Intent(this, LockActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(LockActivity.EXTRA_LOCKED_PACKAGE, packageName)
        }
        startActivity(intent)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, AppLockerApp.MONITOR_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_lock)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        const val ACTION_UNLOCK_SUCCESS = "com.applocker.UNLOCK_SUCCESS"
        const val EXTRA_PACKAGE = "locked_package"

        fun start(context: Context) {
            val intent = Intent(context, AppMonitorService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AppMonitorService::class.java))
        }

        fun notifyUnlocked(context: Context, packageName: String) {
            val intent = Intent(context, AppMonitorService::class.java).apply {
                action = ACTION_UNLOCK_SUCCESS
                putExtra(EXTRA_PACKAGE, packageName)
            }
            context.startForegroundService(intent)
        }
    }
}
