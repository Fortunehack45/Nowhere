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
        assertTrue(PremiumEntitlement.ALL_PRODUCT_IDS.contains(PremiumEntitlement.PRODUCT_ID_NOWHERE_PRO))
        assertTrue(PremiumEntitlement.ALL_PRODUCT_IDS.contains(PremiumEntitlement.PRODUCT_ID_NOWHERE_PRO_HYPHEN))
        assertTrue(PremiumEntitlement.ALL_PRODUCT_IDS.contains(PremiumEntitlement.PRODUCT_ID_NOWHERE_PREMIUM_HYPHEN))
        assertTrue(PremiumEntitlement.ALL_PRODUCT_IDS.contains(PremiumEntitlement.PRODUCT_ID_NOWHERE_SUBSCRIPTION))
        assertTrue(PremiumEntitlement.ALL_PRODUCT_IDS.contains(PremiumEntitlement.PRODUCT_ID_SANDBOX))
    }

    @Test
    fun testBillingManager_diagnosticInfo() {
        val billingManager = BillingManager.getInstance(context)
        val diag = billingManager.getDiagnosticInfo()
        assertNotNull(diag)
        assertTrue(diag.testedCandidateIds.isNotEmpty())
        assertTrue(diag.testedCandidateIds.contains("nowhere_premium"))
        assertTrue(diag.testedCandidateIds.contains("nowhere-pro"))
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
    fun testBillingManager_sandboxEntitlementToggle() {
        val billingManager = BillingManager.getInstance(context)

        // Toggle on
        val activated = billingManager.toggleDebugSandboxPremium(true)
        assertTrue(activated)
        assertTrue(billingManager.isPremium.value)
        assertEquals(PremiumEntitlement.PRODUCT_ID_SANDBOX, billingManager.entitlementState.value.productId)

        // Toggle off
        val deactivated = billingManager.toggleDebugSandboxPremium(false)
        assertFalse(deactivated)
        assertFalse(billingManager.isPremium.value)
    }

    @Test
    fun testPromotionManager_discounts() {
        val discount = PromotionManager.getYearlyDiscountPercent(context)
        assertTrue(discount == 10 || discount == 15)

        val badge = PromotionManager.getYearlyDiscountBadgeText(context)
        assertTrue(badge.contains("SAVE"))
    }
}
