package com.applocker.update

import android.util.Log
import com.applocker.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the GitHub Releases API for a newer version of the app.
 *
 * To use:
 *  1. Create a GitHub repo and push your code.
 *  2. Set GITHUB_OWNER and GITHUB_REPO below to match your repo.
 *  3. When releasing a new version, bump versionCode in build.gradle.kts,
 *     build a release APK, and attach it to a GitHub Release tagged v<versionName>
 *     (e.g. v1.1.0). The checker compares versionCode from the release body.
 *
 * Release body format (put this text in your GitHub Release description):
 *   VERSION_CODE=2
 */
object UpdateChecker {

    // ── Configure these two values to match your GitHub repo ─────────────────
    private const val GITHUB_OWNER = "lancehaoh"
    private const val GITHUB_REPO  = "android-app-locker"
    // ─────────────────────────────────────────────────────────────────────────

    private const val API_URL =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    private val TAG = UpdateChecker::class.simpleName

    data class UpdateInfo(
        val latestVersionCode: Int,
        val latestVersionName: String,
        val downloadUrl: String,
        val releaseNotes: String
    )

    /**
     * Returns [UpdateInfo] if a newer version exists, null otherwise.
     * Must be called from a coroutine (runs on IO dispatcher internally).
     */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                connectTimeout = 8_000
                readTimeout = 8_000
            }

            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null

            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            conn.disconnect()

            val tagName      = json.getString("tag_name")          // e.g. "v1.1.0"
            val versionName  = tagName.trimStart('v')
            val body         = json.optString("body", "")
            val releaseNotes = json.optString("body", "No release notes.")

            // Parse VERSION_CODE=N from the release body
            val latestCode = Regex("VERSION_CODE=(\\d+)")
                .find(body)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@withContext null

            if (latestCode <= BuildConfig.VERSION_CODE) return@withContext null

            // Find the first .apk asset
            val assets = json.getJSONArray("assets")
            var downloadUrl = ""
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name").endsWith(".apk")) {
                    downloadUrl = asset.getString("browser_download_url")
                    break
                }
            }

            if (downloadUrl.isEmpty()) return@withContext null

            UpdateInfo(latestCode, versionName, downloadUrl, releaseNotes)

        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            null
        }
    }
}
