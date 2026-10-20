package com.fakegps.mocklocation.billing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BillingManagerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testPremiumEntitlement_candidateProductIdsContainsExpectedIds() {
        assertTrue(PremiumEntitlement.ALL_PRODUCT_IDS.contains(PremiumEntitlement.PRODUCT_ID_PREMIUM))
        assertTrue(PremiumEntitlement.ALL_PRODUCT_IDS.contains(PremiumEntitlement.PRODUCT_ID_PREMIUM_YEARLY))
        assertTrue(PremiumEntitlement.ALL_PRODUCT_IDS.contains(PremiumEntitlement.PRODUCT_ID_PREMIUM_MONTHLY))
        assertTrue(PremiumEntitlement.ALL_PRODUCT_IDS.contains(PremiumEntitlement.PRODUCT_ID_NOWHERE_PRO))
        assertTrue(PremiumEntitlement.ALL_PRODUCT_IDS.contains(PremiumEntitlement.PRODUCT_ID_NOWHERE_PRO_HYPHEN))
        assertTrue(PremiumEntitlement.ALL_PRODUCT_IDS.contains(PremiumEntitlement.PRODUCT_ID_NOWHERE_PRO_MONTHLY))
        assertTrue(PremiumEntitlement.ALL_PRODUCT_IDS.contains(PremiumEntitlement.PRODUCT_ID_NOWHERE_PRO_YEARLY))
        assertTrue(PremiumEntitlement.ALL_PRODUCT_IDS.contains(PremiumEntitlement.PRODUCT_ID_NOWHERE_MONTHLY))
        assertTrue(PremiumEntitlement.ALL_PRODUCT_IDS.contains(PremiumEntitlement.PRODUCT_ID_NOWHERE_YEARLY))
        assertTrue(PremiumEntitlement.ALL_PRODUCT_IDS.contains(PremiumEntitlement.PRODUCT_ID_MONTHLY))
        assertTrue(PremiumEntitlement.ALL_PRODUCT_IDS.contains(PremiumEntitlement.PRODUCT_ID_YEARLY))
        assertTrue(PremiumEntitlement.ALL_PRODUCT_IDS.contains(PremiumEntitlement.PRODUCT_ID_NOWHERE_PREMIUM_HYPHEN))
        assertTrue(PremiumEntitlement.ALL_PRODUCT_IDS.contains(PremiumEntitlement.PRODUCT_ID_NOWHERE_SUBSCRIPTION))
    }

    @Test
    fun testBillingManager_diagnosticInfo() {
        val billingManager = BillingManager.getInstance(context)
        val diag = billingManager.getDiagnosticInfo()
        assertNotNull(diag)
        assertTrue(diag.testedCandidateIds.isNotEmpty())
        assertTrue(diag.testedCandidateIds.contains("nowhere_premium"))
        assertTrue(diag.testedCandidateIds.contains("nowhere-pro"))
        assertTrue(diag.testedCandidateIds.contains("nowhere_pro_monthly"))
        assertTrue(diag.testedCandidateIds.contains("nowhere_monthly"))
        assertTrue(diag.testedCandidateIds.contains("nowhere_premium_monthly"))
    }

    @Test
    fun testBillingManager_offerSeparationDefaults() {
        val billingManager = BillingManager.getInstance(context)
        // With no active Play Store connection in mock unit test, offers should be gracefully null/false
        assertFalse(billingManager.hasMonthlyOffer())
        assertFalse(billingManager.hasYearlyOffer())
        assertNull(billingManager.resolvePlanOffer(PremiumEntitlement.PlanType.MONTHLY))
        assertNull(billingManager.resolvePlanOffer(PremiumEntitlement.PlanType.YEARLY))
    }

    @Test
    fun testPremiumEntitlement_freeDefaults() {
        val free = PremiumEntitlement.FREE
        assertFalse(free.isPremium)
        assertNull(free.productId)
        assertNull(free.purchaseToken)
        assertFalse(free.isPending)
    }

    @Test
    fun testBillingManager_securityEntitlementIntegrity() {
        val billingManager = BillingManager.getInstance(context)
        // With no legitimate Google Play token or purchase, entitlement MUST strictly remain false
        assertFalse(billingManager.isPremium.value)
        assertFalse(billingManager.entitlementState.value.isPremium)
        assertNull(billingManager.entitlementState.value.purchaseToken)
    }

    @Test
    fun testPromotionManager_discounts() {
        val discount = PromotionManager.getYearlyDiscountPercent(context)
        assertTrue(discount == 10 || discount == 15)

        val badge = PromotionManager.getYearlyDiscountBadgeText(context)
        assertTrue(badge.contains("SAVE"))
    }
}
