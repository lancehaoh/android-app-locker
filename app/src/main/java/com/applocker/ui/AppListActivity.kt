package com.applocker.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.applocker.R
import com.applocker.databinding.ActivityAppListBinding
import com.applocker.data.AppLockDatabase
import com.applocker.data.LockedApp
import com.applocker.ui.adapter.AppListAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppListBinding
    private lateinit var db: AppLockDatabase
    private lateinit var adapter: AppListAdapter
    private var allApps: List<AppItem> = emptyList()

    data class AppItem(
        val packageName: String,
        val label: String,
        var isLocked: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        db = AppLockDatabase.getInstance(this)

        adapter = AppListAdapter(
            packageManager = packageManager,
            onToggle = { item, locked -> toggleLock(item, locked) }
        )

        binding.recyclerApps.layoutManager = LinearLayoutManager(this)
        binding.recyclerApps.adapter = adapter

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                filterApps(s?.toString() ?: "")
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        loadApps()
    }

    private fun loadApps() {
        lifecycleScope.launch(Dispatchers.IO) {
            val locked = db.lockedAppDao().getAll().map { it.packageName }.toSet()
            val pm = packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { isUserApp(it) || isMessagingApp(it.packageName) }
                .map { info ->
                    AppItem(
                        packageName = info.packageName,
                        label = pm.getApplicationLabel(info).toString(),
                        isLocked = info.packageName in locked
                    )
                }
                .sortedWith(compareByDescending<AppItem> { isMessagingApp(it.packageName) }
                    .thenBy { it.label })

            allApps = apps
            withContext(Dispatchers.Main) {
                adapter.submitList(apps.toMutableList())
            }
        }
    }

    private fun isUserApp(info: ApplicationInfo): Boolean =
        (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0

    private fun isMessagingApp(pkg: String): Boolean = pkg in MESSAGING_PACKAGES

    private fun filterApps(query: String) {
        val filtered = if (query.isBlank()) allApps
        else allApps.filter { it.label.contains(query, ignoreCase = true) }
        adapter.submitList(filtered.toMutableList())
    }

    private fun toggleLock(item: AppItem, locked: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (locked) {
                db.lockedAppDao().insert(LockedApp(item.packageName, item.label, true))
            } else {
                db.lockedAppDao().deleteByPackage(item.packageName)
            }
        }
    }


    companion object {
        val MESSAGING_PACKAGES = setOf(
            "org.telegram.messenger",          // Telegram
            "org.telegram.messenger.web",
            "com.whatsapp",                    // WhatsApp
            "com.whatsapp.w4b",                // WhatsApp Business
            "com.samsung.android.messaging",   // Samsung Messages
            "com.google.android.apps.messaging", // Google Messages
            "com.facebook.orca",               // Messenger
            "com.facebook.mlite",
            "com.instagram.android",           // Instagram DMs
            "com.twitter.android",             // Twitter/X DMs
            "com.snapchat.android",            // Snapchat
            "com.discord",                     // Discord
            "com.skype.raider",                // Skype
            "com.viber.voip",                  // Viber
            "kik.android",                     // Kik
            "com.tencent.mm",                  // WeChat
            "jp.naver.line.android",           // LINE
            "com.kakao.talk",                  // KakaoTalk
        )
    }
}
