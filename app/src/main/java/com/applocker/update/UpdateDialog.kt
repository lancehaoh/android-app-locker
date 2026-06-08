package com.applocker.update

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.applocker.R

object UpdateDialog {

    fun show(context: Context, info: UpdateChecker.UpdateInfo) {
        MaterialAlertDialogBuilder(context)
            .setTitle("Update Available — v${info.latestVersionName}")
            .setMessage(
                "A new version of App Locker is available.\n\n" +
                "What's new:\n${info.releaseNotes}\n\n" +
                "The app will download and install automatically."
            )
            .setPositiveButton("Update Now") { _, _ ->
                UpdateInstaller.downloadAndInstall(
                    context,
                    info.downloadUrl,
                    info.latestVersionName
                )
            }
            .setNegativeButton("Later", null)
            .setCancelable(true)
            .show()
    }
}
