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

        const val BASE_PLAN_MONTHLY = "premium-monthly"
        const val BASE_PLAN_YEARLY = "premium-yearly"

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
