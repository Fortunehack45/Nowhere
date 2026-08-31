package com.fakegps.mocklocation.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.fakegps.mocklocation.ui.dialogs.AppReviewBottomSheet

object AppReviewManager {

    private const val TAG = "AppReviewManager"
    private const val PREFS_NAME = "nowhere_review_prefs"

    private const val KEY_HAS_REVIEWED = "key_has_reviewed"
    private const val KEY_NEVER_SHOW_AGAIN = "key_never_show_review"
    private const val KEY_LAST_PROMPT_TIME = "key_last_review_prompt_time"
    private const val KEY_ACTION_COUNT = "key_review_successful_action_count"
    private const val KEY_LAUNCH_COUNT = "key_review_launch_count"
    private const val KEY_DISMISS_COUNT = "key_review_dismiss_count"

    // Trigger thresholds
    private const val REQUIRED_ACTIONS_INITIAL = 3
    private const val COOLDOWN_DAYS_MILLIS = 3 * 24 * 60 * 60 * 1000L // 3 days

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun hasUserReviewed(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_HAS_REVIEWED, false)
    }

    fun hasUserNeverAskAgain(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_NEVER_SHOW_AGAIN, false)
    }

    fun getSuccessfulActionCount(context: Context): Int {
        return getPrefs(context).getInt(KEY_ACTION_COUNT, 0)
    }

    fun getLaunchCount(context: Context): Int {
        return getPrefs(context).getInt(KEY_LAUNCH_COUNT, 0)
    }

    fun incrementLaunchCount(context: Context) {
        val prefs = getPrefs(context)
        val count = prefs.getInt(KEY_LAUNCH_COUNT, 0) + 1
        prefs.edit().putInt(KEY_LAUNCH_COUNT, count).apply()
    }

    /**
     * Records a positive user simulation action (e.g. teleporting, running a route, bookmarking a location)
     * and evaluates whether to present the review dialog.
     */
    fun recordSuccessfulAction(activity: FragmentActivity) {
        val prefs = getPrefs(activity)
        val count = prefs.getInt(KEY_ACTION_COUNT, 0) + 1
        prefs.edit().putInt(KEY_ACTION_COUNT, count).apply()

        if (shouldPromptReview(activity)) {
            launchReviewFlow(activity, forcePrompt = false)
        }
    }

    /**
     * Determines whether the app should prompt for a review based on user actions and cooldown.
     */
    fun shouldPromptReview(context: Context): Boolean {
        val prefs = getPrefs(context)

        // Rule 1: Never show if already reviewed or permanently dismissed
        if (prefs.getBoolean(KEY_HAS_REVIEWED, false)) return false
        if (prefs.getBoolean(KEY_NEVER_SHOW_AGAIN, false)) return false

        // Rule 2: Minimum positive engagement threshold (3+ actions OR 4+ launches)
        val actionCount = prefs.getInt(KEY_ACTION_COUNT, 0)
        val launchCount = prefs.getInt(KEY_LAUNCH_COUNT, 0)
        if (actionCount < REQUIRED_ACTIONS_INITIAL && launchCount < 4) return false

        // Rule 3: Enforce cooldown period since last prompt
        val lastPromptTime = prefs.getLong(KEY_LAST_PROMPT_TIME, 0L)
        val now = System.currentTimeMillis()
        if (lastPromptTime > 0L && (now - lastPromptTime < COOLDOWN_DAYS_MILLIS)) {
            return false
        }

        return true
    }

    /**
     * Called when the user clicks "Rate on Google Play" or submits 4/5 stars.
     * Permanently marks as reviewed so it will NEVER prompt again.
     */
    fun onUserRated(context: Context) {
        val prefs = getPrefs(context)
        prefs.edit()
            .putBoolean(KEY_HAS_REVIEWED, true)
            .putLong(KEY_LAST_PROMPT_TIME, System.currentTimeMillis())
            .apply()
    }

    /**
     * Called when the user clicks "Remind Me Later".
     * Applies a 3-day cooldown before asking again.
     */
    fun onRemindLater(context: Context) {
        val prefs = getPrefs(context)
        val dismissCount = prefs.getInt(KEY_DISMISS_COUNT, 0) + 1
        prefs.edit()
            .putLong(KEY_LAST_PROMPT_TIME, System.currentTimeMillis())
            .putInt(KEY_DISMISS_COUNT, dismissCount)
            .apply()
    }

    /**
     * Called when the user clicks "Never Ask Again".
     * Permanently disables the review prompt.
     */
    fun onNeverAskAgain(context: Context) {
        val prefs = getPrefs(context)
        prefs.edit()
            .putBoolean(KEY_NEVER_SHOW_AGAIN, true)
            .putLong(KEY_LAST_PROMPT_TIME, System.currentTimeMillis())
            .apply()
    }

    /**
     * Directly launches Google Play Store review page.
     */
    fun openPlayStoreReview(context: Context) {
        onUserRated(context)
        val packageName = context.packageName

        try {
            val playStoreIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(playStoreIntent)
        } catch (e: ActivityNotFoundException) {
            try {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(webIntent)
            } catch (err: Exception) {
                Toast.makeText(context, "Could not open Google Play Store.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Displays our luxury branded App Review bottom sheet.
     */
    fun launchReviewFlow(activity: FragmentActivity, forcePrompt: Boolean = false) {
        if (!forcePrompt && !shouldPromptReview(activity)) return

        try {
            if (activity.isFinishing || activity.isDestroyed) return

            val existing = activity.supportFragmentManager.findFragmentByTag(AppReviewBottomSheet.TAG)
            if (existing == null) {
                val dialog = AppReviewBottomSheet.newInstance()
                dialog.show(activity.supportFragmentManager, AppReviewBottomSheet.TAG)

                // Update last prompt time
                getPrefs(activity).edit().putLong(KEY_LAST_PROMPT_TIME, System.currentTimeMillis()).apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch review bottom sheet: ${e.message}", e)
        }
    }

    /**
     * Resets review state (useful for tests or manual testing).
     */
    fun resetForTesting(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}
