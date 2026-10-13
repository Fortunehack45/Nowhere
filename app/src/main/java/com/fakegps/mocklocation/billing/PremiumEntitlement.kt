package com.fakegps.mocklocation.billing

/**
 * Authoritative client entitlement model for Nowhere Premium.
 */
data class PremiumEntitlement(
    val isPremium: Boolean = false,
    val productId: String? = null,
    val purchaseToken: String? = null,
    val orderId: String? = null,
    val purchaseTime: Long = 0L,
    val autoRenewing: Boolean = false,
    val isPending: Boolean = false,
    val formattedPrice: String? = null,
    val billingPeriod: String? = null,
    val hasFreeTrial: Boolean = false,
    val freeTrialDescription: String? = null,
    val monthlyPrice: String? = null,
    val yearlyPrice: String? = null,
    val yearlyMonthlyEquivalent: String? = null,
    val lastVerifiedTimestamp: Long = 0L
) {
    enum class PlanType {
        MONTHLY,
        YEARLY
    }

    companion object {
        const val PRODUCT_ID_PREMIUM = "nowhere_premium"
        const val PRODUCT_ID_PREMIUM_YEARLY = "nowhere_premium_yearly"
        const val PRODUCT_ID_NOWHERE_PRO = "nowhere_pro"
        const val PRODUCT_ID_NOWHERE_PRO_HYPHEN = "nowhere-pro"
        const val PRODUCT_ID_NOWHERE_PREMIUM_HYPHEN = "nowhere-premium"
        const val PRODUCT_ID_NOWHERE_SUBSCRIPTION = "nowhere_subscription"
        const val PRODUCT_ID_NOWHERE_SUBSCRIPTION_HYPHEN = "nowhere-subscription"
        const val PRODUCT_ID_NOWHERE_PRO_YEARLY = "nowhere_pro_yearly"
        const val PRODUCT_ID_PRO = "pro"
        const val PRODUCT_ID_PREMIUM_SHORT = "premium"
        const val PRODUCT_ID_VIP = "vip"
        const val PRODUCT_ID_NOWHERE_VIP = "nowhere_vip"
        const val PRODUCT_ID_SANDBOX = "nowhere_premium_sandbox"

        const val BASE_PLAN_MONTHLY = "premium-monthly"
        const val BASE_PLAN_YEARLY = "premium-yearly"

        val ALL_PRODUCT_IDS = listOf(
            PRODUCT_ID_PREMIUM,
            PRODUCT_ID_PREMIUM_YEARLY,
            PRODUCT_ID_NOWHERE_PRO,
            PRODUCT_ID_NOWHERE_PRO_HYPHEN,
            PRODUCT_ID_NOWHERE_PREMIUM_HYPHEN,
            PRODUCT_ID_NOWHERE_SUBSCRIPTION,
            PRODUCT_ID_NOWHERE_SUBSCRIPTION_HYPHEN,
            PRODUCT_ID_NOWHERE_PRO_YEARLY,
            PRODUCT_ID_PRO,
            PRODUCT_ID_PREMIUM_SHORT,
            PRODUCT_ID_VIP,
            PRODUCT_ID_NOWHERE_VIP,
            PRODUCT_ID_SANDBOX
        )

        val FREE = PremiumEntitlement(
            isPremium = false,
            productId = null,
            purchaseToken = null,
            orderId = null,
            purchaseTime = 0L,
            autoRenewing = false,
            isPending = false,
            formattedPrice = null,
            billingPeriod = null,
            hasFreeTrial = false,
            freeTrialDescription = null,
            monthlyPrice = null,
            yearlyPrice = null,
            yearlyMonthlyEquivalent = null,
            lastVerifiedTimestamp = 0L
        )
    }
}
