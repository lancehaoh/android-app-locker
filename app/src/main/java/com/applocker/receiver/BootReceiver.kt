package com.applocker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.applocker.data.PreferencesManager
import com.applocker.service.AppMonitorService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val prefs = PreferencesManager.getInstance(context)
            if (prefs.serviceEnabled) {
                AppMonitorService.start(context)
            }
        }
    }
}
