package com.fakegps.mocklocation.billing

import android.content.Context
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import com.fakegps.mocklocation.util.AppReviewManager

/**
 * Evaluates promotional discount eligibility and smart segmentation for Nowhere Premium.
 *
 * Default annual plan discount: 10% OFF for standard users.
 * Exclusive VIP annual plan discount: 15% OFF reserved strictly for loyal power users
 * who meet high-engagement, multi-day retention, and active simulation thresholds.
 */
object PromotionManager {

    private const val MIN_INSTALL_AGE_VIP_MS = 3 * 24 * 60 * 60 * 1000L // 3 Full Days (72 Hours)
    private const val MIN_LAUNCHES_VIP = 5
    private const val MIN_ACTIONS_VIP = 4
    private const val MIN_EXTENSIONS_VIP = 3

    /**
     * Stricter qualification rule for the exclusive 15% VIP discount:
     * User MUST meet sustained multi-day loyalty:
     * - Installed for at least 3 full days (72+ hours)
     * - Launched the app at least 5 times
     * - Successfully performed at least 4 simulation actions
     * OR
     * - Power user who has extended their session with rewarded videos at least 3 times.
     */
    fun isEligibleForVipDiscount(context: Context): Boolean {
        val appPrefs = AppSettingsPreferences(context)
        val launchCount = AppReviewManager.getLaunchCount(context)
        val actionCount = AppReviewManager.getSuccessfulActionCount(context)
        val extensionCount = appPrefs.sessionExtensionCount + appPrefs.sessionExpiryCount
        val now = System.currentTimeMillis()
        val installAge = now - appPrefs.firstInstallTimestamp

        val meetsSustainedLoyalty = (installAge >= MIN_INSTALL_AGE_VIP_MS) &&
                (launchCount >= MIN_LAUNCHES_VIP) &&
                (actionCount >= MIN_ACTIONS_VIP)

        val meetsPowerUserExtensions = extensionCount >= MIN_EXTENSIONS_VIP

        return meetsSustainedLoyalty || meetsPowerUserExtensions
    }

    /**
     * Returns the discount percentage for the annual pass (15% for VIP power pilots, 10% standard).
     */
    fun getYearlyDiscountPercent(context: Context): Int {
        return if (isEligibleForVipDiscount(context)) 15 else 10
    }

    /**
     * Formats the discount badge text.
     */
    fun getYearlyDiscountBadgeText(context: Context): String {
        return if (isEligibleForVipDiscount(context)) {
            "SAVE 15% VIP"
        } else {
            "SAVE 10%"
        }
    }
}
