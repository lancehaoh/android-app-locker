package com.applocker.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.applocker.data.AppLockDatabase
import com.applocker.data.PreferencesManager
import com.applocker.ui.LockActivity
import kotlinx.coroutines.*

/**
 * Accessibility Service as a fallback/complement to UsageStatsManager.
 * Some Samsung One UI builds intercept UsageStats; accessibility events are
 * more reliable for detecting window state changes.
 */
class AppLockAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var prefs: PreferencesManager
    private lateinit var db: AppLockDatabase
    private val unlockedPackages = mutableMapOf<String, Long>()
    private var lastLockedPackage: String? = null
    private var previousPkg: String? = null
    private var currentForegroundPkg: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = PreferencesManager.getInstance(this)
        db = AppLockDatabase.getInstance(this)

        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 50
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        scope.launch {
            // Only evaluate locking on a foreground TRANSITION (app just switched)
            val justSwitchedTo = pkg != currentForegroundPkg
            if (!justSwitchedTo) return@launch

            // Update tracking before any early returns
            previousPkg = currentForegroundPkg
            currentForegroundPkg = pkg

            val locked = db.lockedAppDao().isLocked(pkg)
            if (!locked) {
                lastLockedPackage = null
                return@launch
            }

            val lastUnlock = unlockedPackages[pkg] ?: 0L
            val within = (System.currentTimeMillis() - lastUnlock) < prefs.gracePeriodMs
            val cameFromAppLocker = previousPkg == packageName

            if (within || cameFromAppLocker || lastLockedPackage == pkg) return@launch

            lastLockedPackage = pkg
            val intent = Intent(this@AppLockAccessibilityService, LockActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(LockActivity.EXTRA_LOCKED_PACKAGE, pkg)
            }
            startActivity(intent)
        }
    }

    fun notifyUnlocked(pkg: String) {
        unlockedPackages[pkg] = System.currentTimeMillis()
        lastLockedPackage = null
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
