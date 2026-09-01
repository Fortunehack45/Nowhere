package com.fakegps.mocklocation.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Official Google Play In-App Update Manager.
 * Checks directly with Google Play Store on device and initiates in-app update flows
 * or opens the Google Play Store listing. No external GitHub dependencies or URLs.
 */
object AppUpdateManager {

    private const val TAG = "AppUpdateManager"
    const val REQUEST_CODE_PLAY_UPDATE = 9001

    data class PlayUpdateInfo(
        val isUpdateAvailable: Boolean,
        val availableVersionCode: Int,
        val isImmediateUpdateAllowed: Boolean,
        val isFlexibleUpdateAllowed: Boolean,
        val appUpdateInfo: AppUpdateInfo?
    )

    /**
     * Checks Google Play for an available app update asynchronously.
     */
    suspend fun checkForUpdates(context: Context): PlayUpdateInfo = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            try {
                val appUpdateManager = AppUpdateManagerFactory.create(context.applicationContext)
                val appUpdateInfoTask = appUpdateManager.appUpdateInfo

                appUpdateInfoTask.addOnSuccessListener { info ->
                    val isAvailable = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                    val immediateAllowed = info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
                    val flexibleAllowed = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)

                    Log.d(TAG, "Play update availability: $isAvailable (Version code: ${info.availableVersionCode()})")
                    if (continuation.isActive) {
                        continuation.resume(
                            PlayUpdateInfo(
                                isUpdateAvailable = isAvailable,
                                availableVersionCode = info.availableVersionCode(),
                                isImmediateUpdateAllowed = immediateAllowed,
                                isFlexibleUpdateAllowed = flexibleAllowed,
                                appUpdateInfo = info
                            )
                        )
                    }
                }.addOnFailureListener { error ->
                    Log.w(TAG, "Failed checking Google Play updates: ${error.message}")
                    if (continuation.isActive) {
                        continuation.resume(
                            PlayUpdateInfo(
                                isUpdateAvailable = false,
                                availableVersionCode = 0,
                                isImmediateUpdateAllowed = false,
                                isFlexibleUpdateAllowed = false,
                                appUpdateInfo = null
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing Play AppUpdateManager: ${e.message}", e)
                if (continuation.isActive) {
                    continuation.resume(
                        PlayUpdateInfo(
                            isUpdateAvailable = false,
                            availableVersionCode = 0,
                            isImmediateUpdateAllowed = false,
                            isFlexibleUpdateAllowed = false,
                            appUpdateInfo = null
                        )
                    )
                }
            }
        }
    }

    /**
     * Starts official Google Play In-App Update flow.
     */
    fun startPlayUpdateFlow(activity: Activity, updateInfo: AppUpdateInfo, updateType: Int = AppUpdateType.FLEXIBLE) {
        try {
            val appUpdateManager = AppUpdateManagerFactory.create(activity)
            appUpdateManager.startUpdateFlowForResult(
                updateInfo,
                activity,
                AppUpdateOptions.defaultOptions(updateType),
                REQUEST_CODE_PLAY_UPDATE
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not start Play update flow: ${e.message}", e)
            openPlayStore(activity)
        }
    }

    /**
     * Opens the app's official Google Play Store page.
     */
    fun openPlayStore(context: Context) {
        val packageName = context.packageName
        try {
            val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(marketIntent)
        } catch (e: ActivityNotFoundException) {
            try {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(webIntent)
            } catch (ignored: Exception) {
                Toast.makeText(context, "Could not open Google Play Store", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open Google Play Store", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Compares semantic version strings (e.g., "1.0.0" vs "1.0.1", with optional 'v' prefix).
     * Returns true if targetVersion is strictly newer than currentVersion.
     */
    fun isNewerVersion(currentVersion: String, targetVersion: String): Boolean {
        val cleanCurrent = currentVersion.trim().removePrefix("v").removePrefix("V")
        val cleanTarget = targetVersion.trim().removePrefix("v").removePrefix("V")

        if (cleanCurrent.isBlank() || cleanTarget.isBlank()) return false
        if (cleanCurrent == cleanTarget) return false

        val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }
        val targetParts = cleanTarget.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(currentParts.size, targetParts.size)
        for (i in 0 until maxLen) {
            val curr = currentParts.getOrElse(i) { 0 }
            val target = targetParts.getOrElse(i) { 0 }
            if (target > curr) return true
            if (target < curr) return false
        }
        return false
    }
}
