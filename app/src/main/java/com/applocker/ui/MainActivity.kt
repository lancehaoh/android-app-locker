package com.applocker.ui

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.applocker.R
import com.applocker.data.AppLockDatabase
import com.applocker.update.UpdateChecker
import com.applocker.update.UpdateDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.applocker.data.LockMethod
import com.applocker.data.PreferencesManager
import com.applocker.databinding.ActivityMainBinding
import com.applocker.service.AppMonitorService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferencesManager
    private lateinit var db: AppLockDatabase

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, R.string.post_notifications_denied, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        prefs = PreferencesManager.getInstance(this)
        db = AppLockDatabase.getInstance(this)

        requestPostNotificationsIfNeeded()
        setupButtons()
        observeLockedApps()
        checkForUpdate()
    }

    private fun requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
        refreshServiceToggle()
    }

    private fun setupButtons() {
        binding.btnSelectApps.setOnClickListener {
            startActivity(Intent(this, AppListActivity::class.java))
        }

        binding.btnSetupLock.setOnClickListener {
            startActivity(Intent(this, SetupLockActivity::class.java))
        }

        binding.switchService.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (!hasUsageStatsPermission()) {
                    requestUsageStatsPermission()
                    binding.switchService.isChecked = false
                    return@setOnCheckedChangeListener
                }
                if (!prefs.isSetUp()) {
                    Toast.makeText(this, R.string.setup_lock_first, Toast.LENGTH_LONG).show()
                    binding.switchService.isChecked = false
                    startActivity(Intent(this, SetupLockActivity::class.java))
                    return@setOnCheckedChangeListener
                }
                prefs.serviceEnabled = true
                AppMonitorService.start(this)
            } else {
                prefs.serviceEnabled = false
                AppMonitorService.stop(this)
            }
        }

        binding.btnGrantUsageStats.setOnClickListener {
            requestUsageStatsPermission()
        }

        binding.btnGrantOverlay.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        binding.btnGrantAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnTimeout.setOnClickListener { showTimeoutPicker() }

        binding.btnGrantNotification.setOnClickListener {
            Toast.makeText(this, R.string.grant_notification_access_hint, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    private fun observeLockedApps() {
        lifecycleScope.launch {
            db.lockedAppDao().getAllFlow().collectLatest { apps ->
                val enabled = apps.count { it.isEnabled }
                binding.tvLockedAppsCount.text =
                    resources.getQuantityString(R.plurals.locked_apps_count, enabled, enabled)
            }
        }
    }

    private fun refreshPermissionStatus() {
        val hasUsage = hasUsageStatsPermission()
        val hasOverlay = Settings.canDrawOverlays(this)

        binding.btnGrantUsageStats.alpha = if (hasUsage) 0.4f else 1f
        binding.btnGrantUsageStats.isEnabled = !hasUsage

        binding.btnGrantOverlay.alpha = if (hasOverlay) 0.4f else 1f
        binding.btnGrantOverlay.isEnabled = !hasOverlay

        binding.tvPermissionUsage.text = getString(
            if (hasUsage) R.string.permission_granted else R.string.permission_required
        )
        binding.tvPermissionOverlay.text = getString(
            if (hasOverlay) R.string.permission_granted else R.string.permission_required
        )

        binding.tvTimeoutValue.text = gracePeriodLabel(prefs.gracePeriodMs)

        val hasNotificationAccess = hasNotificationListenerPermission()
        binding.btnGrantNotification.alpha = if (hasNotificationAccess) 0.4f else 1f
        binding.btnGrantNotification.isEnabled = !hasNotificationAccess
        binding.tvPermissionNotification.text = getString(
            if (hasNotificationAccess) R.string.permission_granted else R.string.notification_listener_hint
        )

        val lockSummary = when (prefs.lockMethod) {
            LockMethod.PIN -> getString(R.string.lock_method_pin)
            LockMethod.PASSWORD -> getString(R.string.lock_method_password)
            LockMethod.BIOMETRIC -> getString(R.string.lock_method_biometric)
        }
        binding.tvCurrentLockMethod.text = getString(R.string.current_lock_method, lockSummary)
    }

    private fun refreshServiceToggle() {
        binding.switchService.setOnCheckedChangeListener(null)
        binding.switchService.isChecked = prefs.serviceEnabled
        binding.switchService.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (!hasUsageStatsPermission()) {
                    requestUsageStatsPermission()
                    binding.switchService.isChecked = false
                    return@setOnCheckedChangeListener
                }
                if (!prefs.isSetUp()) {
                    Toast.makeText(this, R.string.setup_lock_first, Toast.LENGTH_LONG).show()
                    binding.switchService.isChecked = false
                    startActivity(Intent(this, SetupLockActivity::class.java))
                    return@setOnCheckedChangeListener
                }
                prefs.serviceEnabled = true
                AppMonitorService.start(this)
            } else {
                prefs.serviceEnabled = false
                AppMonitorService.stop(this)
            }
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun requestUsageStatsPermission() {
        Toast.makeText(this, R.string.grant_usage_stats_hint, Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    // ── Timeout picker ────────────────────────────────────────────────────────

    private val timeoutOptions = listOf(
        0L           to R.string.timeout_immediately,
        30_000L      to R.string.timeout_30s,
        60_000L      to R.string.timeout_1m,
        300_000L     to R.string.timeout_5m,
        1_800_000L   to R.string.timeout_30m,
        3_600_000L   to R.string.timeout_1h
    )

    private fun showTimeoutPicker() {
        val labels = timeoutOptions.map { getString(it.second) }.toTypedArray()
        val current = timeoutOptions.indexOfFirst { it.first == prefs.gracePeriodMs }
            .coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.relock_timeout))
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val chosen = timeoutOptions[which]
                prefs.gracePeriodMs = chosen.first
                binding.tvTimeoutValue.text = getString(chosen.second)
                Toast.makeText(
                    this,
                    getString(R.string.relock_timeout_set, getString(chosen.second)),
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun gracePeriodLabel(ms: Long): String =
        timeoutOptions.firstOrNull { it.first == ms }
            ?.let { getString(it.second) }
            ?: getString(R.string.timeout_immediately)

    private fun checkForUpdate() {
        lifecycleScope.launch {
            val update = UpdateChecker.checkForUpdate() ?: return@launch
            UpdateDialog.show(this@MainActivity, update)
        }
    }

    private fun hasNotificationListenerPermission(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        val myComponent = ComponentName(this, com.applocker.service.NotificationHiderService::class.java)
        return flat.split(":").any { entry ->
            runCatching { ComponentName.unflattenFromString(entry) == myComponent }.getOrDefault(false)
        }
    }
}
