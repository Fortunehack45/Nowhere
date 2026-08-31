package com.fakegps.mocklocation.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.fakegps.mocklocation.BuildConfig
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object AppUpdateManager {

    private const val TAG = "AppUpdateManager"
    private const val GITHUB_LATEST_RELEASE_URL = "https://api.github.com/repos/Fortunehack45/Nowhere/releases/latest"
    private const val GITHUB_ALL_RELEASES_URL = "https://api.github.com/repos/Fortunehack45/Nowhere/releases?per_page=5"
    private const val GITHUB_TAGS_URL = "https://api.github.com/repos/Fortunehack45/Nowhere/tags?per_page=5"
    const val CHANNEL_ID = "nowhere_app_updates_channel"
    const val NOTIFICATION_ID = 5001
    const val DOWNLOAD_NOTIFICATION_ID = 5002

    const val EXTRA_OPEN_UPDATE_DIALOG = "com.fakegps.mocklocation.EXTRA_OPEN_UPDATE_DIALOG"
    const val EXTRA_UPDATE_VERSION = "com.fakegps.mocklocation.EXTRA_UPDATE_VERSION"
    const val EXTRA_UPDATE_TITLE = "com.fakegps.mocklocation.EXTRA_UPDATE_TITLE"
    const val EXTRA_UPDATE_NOTES = "com.fakegps.mocklocation.EXTRA_UPDATE_NOTES"
    const val EXTRA_UPDATE_DOWNLOAD_URL = "com.fakegps.mocklocation.EXTRA_UPDATE_DOWNLOAD_URL"
    const val EXTRA_UPDATE_HTML_URL = "com.fakegps.mocklocation.EXTRA_UPDATE_HTML_URL"

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
     * Checks for updates asynchronously from GitHub releases API with fallback endpoints.
     */
    suspend fun checkForUpdates(context: Context, forceCheck: Boolean = false): UpdateInfo = withContext(Dispatchers.IO) {
        val currentVersion = BuildConfig.VERSION_NAME
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val lastCheck = prefs.getLong(KEY_LAST_CHECK_TIME, 0L)
        val now = System.currentTimeMillis()

        // Short 5-minute cooldown for background checks; 0s cooldown when forceCheck is true
        if (!forceCheck && (now - lastCheck < 5 * 60 * 1000L)) {
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

        val endpointsToTry = listOf(
            GITHUB_LATEST_RELEASE_URL,
            GITHUB_ALL_RELEASES_URL
        )

        for (endpoint in endpointsToTry) {
            try {
                val connection = openConnectionWithRedirects(endpoint)
                if (connection.responseCode in 200..299) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = reader.use { it.readText() }.trim()

                    val targetReleaseJson = if (response.startsWith("[")) {
                        val array = org.json.JSONArray(response)
                        if (array.length() > 0) array.getJSONObject(0) else null
                    } else if (response.startsWith("{")) {
                        JSONObject(response)
                    } else null

                    if (targetReleaseJson != null) {
                        val tagName = targetReleaseJson.optString("tag_name", "").trim()
                        val cleanTag = tagName.removePrefix("v").removePrefix("V").trim()
                        val releaseName = targetReleaseJson.optString("name", "Nowhere v$cleanTag").ifBlank { "Nowhere v$cleanTag" }
                        val releaseNotes = targetReleaseJson.optString("body", "Performance improvements, stability upgrades, and bug fixes.")
                        val htmlUrl = targetReleaseJson.optString("html_url", "https://play.google.com/store/apps/details?id=${context.packageName}")

                        var apkDownloadUrl = ""
                        val assets = targetReleaseJson.optJSONArray("assets")
                        if (assets != null) {
                            for (i in 0 until assets.length()) {
                                val asset = assets.getJSONObject(i)
                                val name = asset.optString("name", "")
                                if (name.endsWith(".apk", ignoreCase = true)) {
                                    apkDownloadUrl = asset.optString("browser_download_url", "")
                                    if (name.contains("release", ignoreCase = true)) {
                                        break
                                    }
                                }
                            }
                        }

                        if (apkDownloadUrl.isEmpty()) {
                            apkDownloadUrl = htmlUrl
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
                }
            } catch (e: Exception) {
                Log.d(TAG, "Update endpoint check failed ($endpoint): ${e.message}")
            }
        }

        // Fallback: check /tags
        try {
            val tagConn = openConnectionWithRedirects(GITHUB_TAGS_URL)
            if (tagConn.responseCode in 200..299) {
                val response = tagConn.inputStream.bufferedReader().use { it.readText() }.trim()
                if (response.startsWith("[")) {
                    val array = org.json.JSONArray(response)
                    if (array.length() > 0) {
                        val firstTagObj = array.getJSONObject(0)
                        val tagName = firstTagObj.optString("name", "").trim()
                        val cleanTag = tagName.removePrefix("v").removePrefix("V").trim()
                        val isNewer = isNewerVersion(currentVersion, cleanTag)
                        val htmlUrl = "https://play.google.com/store/apps/details?id=${context.packageName}"
                        val downloadUrl = ""

                        prefs.edit().putLong(KEY_LAST_CHECK_TIME, now).apply()

                        val updateInfo = UpdateInfo(
                            isUpdateAvailable = isNewer,
                            currentVersion = currentVersion,
                            latestVersion = cleanTag.ifEmpty { currentVersion },
                            releaseTitle = "Nowhere v$cleanTag",
                            releaseNotes = "Enhanced location simulation accuracy, stability upgrades, and bug fixes.",
                            downloadUrl = downloadUrl,
                            htmlUrl = htmlUrl
                        )

                        val dismissedVersion = prefs.getString(KEY_DISMISSED_VERSION, "") ?: ""
                        if (isNewer && cleanTag != dismissedVersion) {
                            notifyUpdateAvailable(context, updateInfo)
                        }

                        return@withContext updateInfo
                    }
                }
            }
        } catch (ignored: Exception) {}

        return@withContext UpdateInfo(
            isUpdateAvailable = false,
            currentVersion = currentVersion,
            latestVersion = currentVersion,
            releaseTitle = "Current Version",
            releaseNotes = "",
            downloadUrl = "",
            htmlUrl = "https://play.google.com/store/apps/details?id=${context.packageName}"
        )
    }

    /**
     * Compares semantic version strings (e.g. "1.0.1" vs "1.0.0").
     */
    fun isNewerVersion(current: String, remote: String): Boolean {
        if (remote.isBlank() || current.isBlank()) return false
        try {
            val curClean = current.removePrefix("v").removePrefix("V").substringBefore("-")
            val remClean = remote.removePrefix("v").removePrefix("V").substringBefore("-")

            val curParts = curClean.split(".").map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
            val remParts = remClean.split(".").map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }

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

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nowhere App Updates",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies you when new updates, features, and performance enhancements are available"
                setShowBadge(true)
                enableVibration(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    fun notifyUpdateAvailable(context: Context, updateInfo: UpdateInfo) {
        try {
            createNotificationChannel(context)

            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                Log.w(TAG, "Notifications are disabled by user.")
                return
            }

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_UPDATE_DIALOG, true)
                putExtra(EXTRA_UPDATE_VERSION, updateInfo.latestVersion)
                putExtra(EXTRA_UPDATE_TITLE, updateInfo.releaseTitle)
                putExtra(EXTRA_UPDATE_NOTES, updateInfo.releaseNotes)
                putExtra(EXTRA_UPDATE_DOWNLOAD_URL, updateInfo.downloadUrl)
                putExtra(EXTRA_UPDATE_HTML_URL, updateInfo.htmlUrl)
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("🎉 Nowhere v${updateInfo.latestVersion} Available!")
                .setContentText("Tap to review what's new and update on Google Play.")
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setColor(ContextCompat.getColor(context, R.color.primary))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .setBigContentTitle("🎉 Nowhere v${updateInfo.latestVersion} Ready!")
                        .bigText("${updateInfo.releaseTitle}\n\n${updateInfo.releaseNotes.take(250)}\n\nTap to update on Google Play.")
                )
                .addAction(R.drawable.ic_play, "Update on Google Play", pendingIntent)
                .build()

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send update notification: ${e.message}", e)
        }
    }

    /**
     * Retrieves the storage directory for downloaded update APKs.
     */
    fun getUpdateDirectory(context: Context): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir, "updates")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getDownloadedApkFile(context: Context, version: String): File? {
        val file = File(getUpdateDirectory(context), "Nowhere_v$version.apk")
        return if (file.exists() && file.length() > 1024 * 500) file else null
    }

    /**
     * Downloads the APK directly from the release asset with live progress callbacks.
     */
    suspend fun downloadApk(
        context: Context,
        updateInfo: UpdateInfo,
        onProgress: (percent: Int, bytesDownloaded: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val targetUrl = updateInfo.downloadUrl
        if (!targetUrl.endsWith(".apk", ignoreCase = true)) {
            return@withContext Result.failure(IllegalArgumentException("Direct APK URL not available: $targetUrl"))
        }

        createNotificationChannel(context)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

        val outputFile = File(getUpdateDirectory(context), "Nowhere_v${updateInfo.latestVersion}.apk")
        if (outputFile.exists()) {
            outputFile.delete()
        }

        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            val connection = openConnectionWithRedirects(targetUrl)
            if (connection.responseCode !in 200..299) {
                return@withContext Result.failure(Exception("HTTP error ${connection.responseCode} downloading APK"))
            }

            val totalBytes = connection.contentLengthLong
            inputStream = connection.inputStream
            outputStream = FileOutputStream(outputFile)

            val buffer = ByteArray(8192)
            var bytesDownloaded = 0L
            var read: Int
            var lastReportedPercent = -1

            while (inputStream.read(buffer).also { read = it } != -1) {
                outputStream.write(buffer, 0, read)
                bytesDownloaded += read

                val percent = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
                if (percent != lastReportedPercent) {
                    lastReportedPercent = percent
                    onProgress(percent, bytesDownloaded, totalBytes)

                    // Update download progress notification
                    val progressNotification = NotificationCompat.Builder(context, CHANNEL_ID)
                        .setContentTitle("Downloading Nowhere v${updateInfo.latestVersion}")
                        .setContentText(if (totalBytes > 0) "$percent% (${bytesDownloaded / (1024 * 1024)}MB / ${totalBytes / (1024 * 1024)}MB)" else "Downloading...")
                        .setSmallIcon(R.drawable.ic_launcher_monochrome)
                        .setColor(ContextCompat.getColor(context, R.color.primary))
                        .setProgress(100, percent, totalBytes <= 0)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .build()
                    notificationManager?.notify(DOWNLOAD_NOTIFICATION_ID, progressNotification)
                }
            }

            outputStream.flush()

            // Remove progress notification on finish
            notificationManager?.cancel(DOWNLOAD_NOTIFICATION_ID)

            // Notify download completion
            showDownloadCompleteNotification(context, updateInfo, outputFile)

            return@withContext Result.success(outputFile)
        } catch (e: Exception) {
            notificationManager?.cancel(DOWNLOAD_NOTIFICATION_ID)
            Log.e(TAG, "Download APK failed: ${e.message}", e)
            return@withContext Result.failure(e)
        } finally {
            try { inputStream?.close() } catch (_: Exception) {}
            try { outputStream?.close() } catch (_: Exception) {}
        }
    }

    private fun showDownloadCompleteNotification(context: Context, updateInfo: UpdateInfo, apkFile: File) {
        try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                DOWNLOAD_NOTIFICATION_ID,
                installIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("✅ Nowhere v${updateInfo.latestVersion} Downloaded")
                .setContentText("Tap to install the update now.")
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setColor(ContextCompat.getColor(context, R.color.primary))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .addAction(R.drawable.ic_check_circle, "Install Now", pendingIntent)
                .build()

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(DOWNLOAD_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Could not show download complete notification: ${e.message}")
        }
    }

    /**
     * Triggers the Android PackageInstaller for the downloaded APK using FileProvider.
     */
    fun installApk(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists() || apkFile.length() <= 0) {
            Log.e(TAG, "APK file does not exist or is empty")
            return false
        }

        try {
            // Check unknown sources permission on Android 8.0+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(settingsIntent)
                    return false
                }
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            context.startActivity(installIntent)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer: ${e.message}", e)
            return false
        }
    }

    private fun openConnectionWithRedirects(initialUrl: String, maxRedirects: Int = 5): HttpURLConnection {
        var currentUrl = initialUrl
        var redirects = 0
        while (redirects < maxRedirects) {
            val url = URL(currentUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Nowhere-Android-App/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept", "application/vnd.github.v3+json, application/octet-stream, */*")
            }

            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_MOVED_PERM ||
                status == HttpURLConnection.HTTP_MOVED_TEMP ||
                status == HttpURLConnection.HTTP_SEE_OTHER ||
                status == 307 || status == 308) {
                val newUrl = connection.getHeaderField("Location")
                connection.disconnect()
                if (!newUrl.isNullOrBlank()) {
                    currentUrl = newUrl
                    redirects++
                    continue
                }
            }
            return connection
        }
        return (URL(currentUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("User-Agent", "Nowhere-Android-App/${BuildConfig.VERSION_NAME}")
        }
    }
}
