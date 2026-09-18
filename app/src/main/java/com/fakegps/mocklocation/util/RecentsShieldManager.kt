package com.fakegps.mocklocation.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences

object RecentsShieldManager {
    private const val TAG = "RecentsShieldManager"

    /**
     * Dynamically excludes or includes the application task in the Android Recents / Overview tray.
     * When [shouldExclude] is true and [AppSettingsPreferences.isRecentsShieldEnabled] is true:
     * - The task is removed from the recent apps list.
     * - OEM "Clear All" batch-killers (Xiaomi, Samsung, Oppo, Vivo, Tecno) cannot target or kill Nowhere.
     * - The ongoing notification, floating joystick, and launcher icon remain 100% active to control Nowhere.
     * When [shouldExclude] is false, normal Recents overview visibility is restored.
     */
    fun updateRecentsShield(context: Context, shouldExclude: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return

        val prefs = AppSettingsPreferences(context)
        val targetExcluded = shouldExclude && prefs.isRecentsShieldEnabled

        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val appTasks = activityManager?.appTasks

            if (appTasks.isNullOrEmpty()) {
                Log.d(TAG, "No AppTasks found for package to update recents shield.")
                return
            }

            for (task in appTasks) {
                try {
                    task.setExcludeFromRecents(targetExcluded)
                    Log.d(TAG, "setExcludeFromRecents($targetExcluded) applied successfully to task.")
                } catch (taskEx: Exception) {
                    Log.w(TAG, "Failed to apply setExcludeFromRecents to task: ${taskEx.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error accessing ActivityManager for recents shield: ${e.message}")
        }
    }
}
