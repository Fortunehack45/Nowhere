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
        const val PRODUCT_ID_PREMIUM_MONTHLY = "nowhere_premium_monthly"
        const val PRODUCT_ID_PREMIUM_YEARLY_HYPHEN = "nowhere-premium-yearly"
        const val PRODUCT_ID_PREMIUM_MONTHLY_HYPHEN = "nowhere-premium-monthly"

        const val PRODUCT_ID_NOWHERE_PRO = "nowhere_pro"
        const val PRODUCT_ID_NOWHERE_PRO_HYPHEN = "nowhere-pro"
        const val PRODUCT_ID_NOWHERE_PREMIUM_HYPHEN = "nowhere-premium"

        const val PRODUCT_ID_NOWHERE_PRO_YEARLY = "nowhere_pro_yearly"
        const val PRODUCT_ID_NOWHERE_PRO_YEARLY_HYPHEN = "nowhere-pro-yearly"
        const val PRODUCT_ID_NOWHERE_PRO_MONTHLY = "nowhere_pro_monthly"
        const val PRODUCT_ID_NOWHERE_PRO_MONTHLY_HYPHEN = "nowhere-pro-monthly"

        const val PRODUCT_ID_NOWHERE_SUBSCRIPTION = "nowhere_subscription"
        const val PRODUCT_ID_NOWHERE_SUBSCRIPTION_HYPHEN = "nowhere-subscription"
        const val PRODUCT_ID_NOWHERE_SUBSCRIPTION_YEARLY = "nowhere_subscription_yearly"
        const val PRODUCT_ID_NOWHERE_SUBSCRIPTION_YEARLY_HYPHEN = "nowhere-subscription-yearly"
        const val PRODUCT_ID_NOWHERE_SUBSCRIPTION_MONTHLY = "nowhere_subscription_monthly"
        const val PRODUCT_ID_NOWHERE_SUBSCRIPTION_MONTHLY_HYPHEN = "nowhere-subscription-monthly"

        const val PRODUCT_ID_NOWHERE_YEARLY = "nowhere_yearly"
        const val PRODUCT_ID_NOWHERE_YEARLY_HYPHEN = "nowhere-yearly"
        const val PRODUCT_ID_NOWHERE_MONTHLY = "nowhere_monthly"
        const val PRODUCT_ID_NOWHERE_MONTHLY_HYPHEN = "nowhere-monthly"

        const val PRODUCT_ID_NOWHERE_ANNUAL = "nowhere_annual"
        const val PRODUCT_ID_NOWHERE_ANNUAL_HYPHEN = "nowhere-annual"

        const val PRODUCT_ID_PRO = "pro"
        const val PRODUCT_ID_PRO_YEARLY = "pro_yearly"
        const val PRODUCT_ID_PRO_MONTHLY = "pro_monthly"

        const val PRODUCT_ID_PREMIUM_SHORT = "premium"
        const val PRODUCT_ID_PREMIUM_YEARLY_SHORT = "premium_yearly"
        const val PRODUCT_ID_PREMIUM_MONTHLY_SHORT = "premium_monthly"

        const val PRODUCT_ID_YEARLY = "yearly"
        const val PRODUCT_ID_ANNUAL = "annual"
        const val PRODUCT_ID_MONTHLY = "monthly"

        const val PRODUCT_ID_VIP = "vip"
        const val PRODUCT_ID_NOWHERE_VIP = "nowhere_vip"
        const val PRODUCT_ID_SANDBOX = "nowhere_premium_sandbox"

        const val BASE_PLAN_NOWHERE_PRO_MONTHLY = "nowhere-pro-monthly"
        const val BASE_PLAN_NOWHERE_PRO_YEARLY = "nowhere-pro-yearly"

        const val BASE_PLAN_MONTHLY = "monthly"
        const val BASE_PLAN_MONTHLY_HYPHEN = "premium-monthly"
        const val BASE_PLAN_NOWHERE_MONTHLY = "nowhere-monthly"
        const val BASE_PLAN_P1M = "p1m"

        const val BASE_PLAN_YEARLY = "yearly"
        const val BASE_PLAN_ANNUAL = "annual"
        const val BASE_PLAN_YEARLY_HYPHEN = "premium-yearly"
        const val BASE_PLAN_NOWHERE_YEARLY = "nowhere-yearly"
        const val BASE_PLAN_P1Y = "p1y"

        val PRIMARY_PRODUCT_IDS = listOf(
            PRODUCT_ID_NOWHERE_PRO_MONTHLY,
            PRODUCT_ID_NOWHERE_PRO_YEARLY
        )

        val ALL_PRODUCT_IDS = listOf(
            PRODUCT_ID_NOWHERE_PRO_MONTHLY,
            PRODUCT_ID_NOWHERE_PRO_YEARLY,
            PRODUCT_ID_NOWHERE_PRO_MONTHLY_HYPHEN,
            PRODUCT_ID_NOWHERE_PRO_YEARLY_HYPHEN,
            PRODUCT_ID_NOWHERE_PRO,
            PRODUCT_ID_NOWHERE_PRO_HYPHEN,
            PRODUCT_ID_PREMIUM,
            PRODUCT_ID_PREMIUM_YEARLY,
            PRODUCT_ID_PREMIUM_MONTHLY,
            PRODUCT_ID_PREMIUM_YEARLY_HYPHEN,
            PRODUCT_ID_PREMIUM_MONTHLY_HYPHEN,
            PRODUCT_ID_NOWHERE_PREMIUM_HYPHEN,
            PRODUCT_ID_NOWHERE_SUBSCRIPTION,
            PRODUCT_ID_NOWHERE_SUBSCRIPTION_HYPHEN,
            PRODUCT_ID_NOWHERE_SUBSCRIPTION_YEARLY,
            PRODUCT_ID_NOWHERE_SUBSCRIPTION_YEARLY_HYPHEN,
            PRODUCT_ID_NOWHERE_SUBSCRIPTION_MONTHLY,
            PRODUCT_ID_NOWHERE_SUBSCRIPTION_MONTHLY_HYPHEN,
            PRODUCT_ID_NOWHERE_YEARLY,
            PRODUCT_ID_NOWHERE_YEARLY_HYPHEN,
            PRODUCT_ID_NOWHERE_MONTHLY,
            PRODUCT_ID_NOWHERE_MONTHLY_HYPHEN,
            PRODUCT_ID_NOWHERE_ANNUAL,
            PRODUCT_ID_NOWHERE_ANNUAL_HYPHEN,
            PRODUCT_ID_PRO,
            PRODUCT_ID_PRO_YEARLY,
            PRODUCT_ID_PRO_MONTHLY,
            PRODUCT_ID_PREMIUM_SHORT,
            PRODUCT_ID_PREMIUM_YEARLY_SHORT,
            PRODUCT_ID_PREMIUM_MONTHLY_SHORT,
            PRODUCT_ID_YEARLY,
            PRODUCT_ID_ANNUAL,
            PRODUCT_ID_MONTHLY,
            PRODUCT_ID_VIP,
            PRODUCT_ID_NOWHERE_VIP,
            PRODUCT_ID_SANDBOX
        ).distinct()

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
