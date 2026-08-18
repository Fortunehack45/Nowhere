package com.fakegps.mocklocation.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fakegps.mocklocation.BuildConfig
import com.fakegps.mocklocation.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object AppUpdateManager {

    private const val TAG = "AppUpdateManager"
    private const val GITHUB_LATEST_RELEASE_URL = "https://api.github.com/repos/Fortunehack45/Nowhere/releases/latest"
    const val CHANNEL_ID = "nowhere_app_updates_channel"
    const val NOTIFICATION_ID = 5001

    private const val PREFS_NAME = "nowhere_update_prefs"
    private const val KEY_LAST_CHECK_TIME = "key_last_update_check_time"
    private const val KEY_DISMISSED_VERSION = "key_dismissed_update_version"

    data class UpdateInfo(
        val isUpdateAvailable: Boolean,
        val currentVersion: String,
        val latestVersion: String,
        val releaseTitle: String,
        val releaseNotes: String,
        val downloadUrl: String,
        val htmlUrl: String
    )

    /**
     * Checks for updates asynchronously from the GitHub releases API.
     */
    suspend fun checkForUpdates(context: Context, forceCheck: Boolean = false): UpdateInfo = withContext(Dispatchers.IO) {
        val currentVersion = BuildConfig.VERSION_NAME
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val lastCheck = prefs.getLong(KEY_LAST_CHECK_TIME, 0L)
        val now = System.currentTimeMillis()

        // Check at most once every 6 hours automatically unless forceCheck is true
        if (!forceCheck && (now - lastCheck < 6 * 60 * 60 * 1000L)) {
            val dismissedVersion = prefs.getString(KEY_DISMISSED_VERSION, "") ?: ""
            return@withContext UpdateInfo(
                isUpdateAvailable = false,
                currentVersion = currentVersion,
                latestVersion = currentVersion,
                releaseTitle = "Current Version",
                releaseNotes = "",
                downloadUrl = "",
                htmlUrl = ""
            )
        }

        try {
            val url = URL(GITHUB_LATEST_RELEASE_URL)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "Nowhere-Android-App/${BuildConfig.VERSION_NAME}")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.use { it.readText() }
                val json = JSONObject(response)

                val tagName = json.optString("tag_name", "").trim()
                val cleanTag = tagName.removePrefix("v").removePrefix("V").trim()
                val releaseName = json.optString("name", "Nowhere $tagName")
                val releaseNotes = json.optString("body", "Performance improvements and bug fixes.")
                val htmlUrl = json.optString("html_url", "https://github.com/Fortunehack45/Nowhere/releases")

                var apkDownloadUrl = htmlUrl
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkDownloadUrl = asset.optString("browser_download_url", htmlUrl)
                            break
                        }
                    }
                }

                prefs.edit().putLong(KEY_LAST_CHECK_TIME, now).apply()

                val isNewer = isNewerVersion(currentVersion, cleanTag)
                val dismissedVersion = prefs.getString(KEY_DISMISSED_VERSION, "") ?: ""

                val updateInfo = UpdateInfo(
                    isUpdateAvailable = isNewer,
                    currentVersion = currentVersion,
                    latestVersion = cleanTag.ifEmpty { currentVersion },
                    releaseTitle = releaseName,
                    releaseNotes = releaseNotes,
                    downloadUrl = apkDownloadUrl,
                    htmlUrl = htmlUrl
                )

                if (isNewer && cleanTag != dismissedVersion) {
                    notifyUpdateAvailable(context, updateInfo)
                }

                return@withContext updateInfo
            }
        } catch (e: Exception) {
            Log.d(TAG, "Update check skipped/failed: ${e.message}")
        }

        return@withContext UpdateInfo(
            isUpdateAvailable = false,
            currentVersion = currentVersion,
            latestVersion = currentVersion,
            releaseTitle = "Current Version",
            releaseNotes = "",
            downloadUrl = "",
            htmlUrl = ""
        )
    }

    /**
     * Compares semantic version strings (e.g., "1.0.1" vs "1.0.0").
     */
    fun isNewerVersion(current: String, remote: String): Boolean {
        if (remote.isBlank() || current.isBlank()) return false
        try {
            val curParts = current.removePrefix("v").removePrefix("V").split(".").map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
            val remParts = remote.removePrefix("v").removePrefix("V").split(".").map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }

            val maxLen = maxOf(curParts.size, remParts.size)
            for (i in 0 until maxLen) {
                val c = curParts.getOrElse(i) { 0 }
                val r = remParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Version parse error: ${e.message}")
        }
        return false
    }

    fun dismissVersion(context: Context, version: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_DISMISSED_VERSION, version).apply()
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nowhere App Updates",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies you when new updates, features, and performance enhancements are available"
                setShowBadge(true)
                enableVibration(true)
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    fun notifyUpdateAvailable(context: Context, updateInfo: UpdateInfo) {
        createNotificationChannel(context)

        val targetUrl = updateInfo.downloadUrl.ifEmpty { updateInfo.htmlUrl }
        val openIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("🎉 Nowhere Update Available (v${updateInfo.latestVersion})")
            .setContentText("A new update with performance & stability improvements is ready to download.")
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setColor(ContextCompat.getColor(context, R.color.primary))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle("🎉 Nowhere v${updateInfo.latestVersion} Available!")
                    .bigText("${updateInfo.releaseTitle}\n\n${updateInfo.releaseNotes.take(200)}\n\nTap to download and install the latest version.")
            )
            .addAction(R.drawable.ic_check_circle, "Download Update", pendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, notification)
    }
}
