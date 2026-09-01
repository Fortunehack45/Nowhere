package com.fakegps.mocklocation.billing

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.android.billingclient.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Production-ready Google Play Billing 7.x Manager for Nowhere.
 * Handles both Monthly (with Free Trial support) and Yearly Subscriptions (with 10% / 15% VIP discounts),
 * localized regional pricing, purchase reconciliation, automatic acknowledgement, lifecycle resilience,
 * and offline-grace entitlement caching.
 */
class BillingManager private constructor(private val context: Context) : PurchasesUpdatedListener, BillingClientStateListener {

    companion object {
        private const val TAG = "BillingManager"
        private const val PREFS_NAME = "nowhere_billing_cache_prefs"
        private const val KEY_CACHED_IS_PREMIUM = "key_cached_is_premium"
        private const val KEY_CACHED_PRODUCT_ID = "key_cached_product_id"
        private const val KEY_CACHED_TOKEN = "key_cached_token"
        private const val KEY_CACHED_ORDER_ID = "key_cached_order_id"
        private const val KEY_CACHED_PURCHASE_TIME = "key_cached_purchase_time"
        private const val KEY_CACHED_AUTO_RENEW = "key_cached_auto_renew"
        private const val KEY_CACHED_PRICE = "key_cached_price"
        private const val KEY_CACHED_YEARLY_PRICE = "key_cached_yearly_price"
        private const val KEY_CACHED_TIMESTAMP = "key_cached_timestamp"
        private const val MAX_RECONNECT_ATTEMPTS = 5

        @Volatile
        private var instance: BillingManager? = null

        fun getInstance(context: Context): BillingManager {
            return instance ?: synchronized(this) {
                instance ?: BillingManager(context.applicationContext).also { instance = it }
            }
        }
    }

    enum class BillingStatus {
        CONNECTING,
        READY,
        ERROR,
        DISCONNECTED
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _entitlementState = MutableStateFlow(loadCachedEntitlement())
    val entitlementState: StateFlow<PremiumEntitlement> = _entitlementState.asStateFlow()

    private val _isPremium = MutableStateFlow(_entitlementState.value.isPremium)
    val isPremium: StateFlow<Boolean> = _isPremium.asStateFlow()

    private val _monthlyProductDetails = MutableStateFlow<ProductDetails?>(null)
    val monthlyProductDetails: StateFlow<ProductDetails?> = _monthlyProductDetails.asStateFlow()

    private val _yearlyProductDetails = MutableStateFlow<ProductDetails?>(null)
    val yearlyProductDetails: StateFlow<ProductDetails?> = _yearlyProductDetails.asStateFlow()

    private val _billingStatus = MutableStateFlow(BillingStatus.CONNECTING)
    val billingStatus: StateFlow<BillingStatus> = _billingStatus.asStateFlow()

    private val _purchaseMessage = MutableStateFlow<String?>(null)
    val purchaseMessage: StateFlow<String?> = _purchaseMessage.asStateFlow()

    private var billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .enablePrepaidPlans()
                .build()
        )
        .build()

    private var reconnectAttempts = 0

    init {
        startConnection()
    }

    /**
     * Connect to Google Play Billing Service.
     */
    fun startConnection() {
        if (billingClient.isReady) {
            _billingStatus.value = BillingStatus.READY
            queryProductDetails()
            queryActivePurchases()
            return
        }

        _billingStatus.value = BillingStatus.CONNECTING
        try {
            billingClient.startConnection(this)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting BillingClient connection: ${e.message}", e)
            _billingStatus.value = BillingStatus.ERROR
        }
    }

    override fun onBillingSetupFinished(billingResult: BillingResult) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            Log.d(TAG, "BillingClient connected successfully.")
            _billingStatus.value = BillingStatus.READY
            reconnectAttempts = 0
            queryProductDetails()
            queryActivePurchases()
        } else {
            Log.w(TAG, "BillingClient setup failed with code: ${billingResult.responseCode} - ${billingResult.debugMessage}")
            _billingStatus.value = BillingStatus.ERROR
            retryConnectionWithBackoff()
        }
    }

    override fun onBillingServiceDisconnected() {
        Log.w(TAG, "Billing service disconnected.")
        _billingStatus.value = BillingStatus.DISCONNECTED
        retryConnectionWithBackoff()
    }

    private fun retryConnectionWithBackoff() {
        if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++
            val delayMs = (1000L * (1 shl reconnectAttempts)).coerceAtMost(16000L)
            Log.d(TAG, "Scheduling billing reconnection attempt $reconnectAttempts in ${delayMs}ms")
            mainHandler.postDelayed({
                startConnection()
            }, delayMs)
        }
    }

    /**
     * Query Subscription Product Details for both Monthly and Yearly options.
     */
    fun queryProductDetails() {
        if (!billingClient.isReady) {
            startConnection()
            return
        }

        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PremiumEntitlement.PRODUCT_ID_PREMIUM)
                .setProductType(BillingClient.ProductType.SUBS)
                .build(),
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PremiumEntitlement.PRODUCT_ID_PREMIUM_YEARLY)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val monthlyDetails = productDetailsList.firstOrNull { it.productId == PremiumEntitlement.PRODUCT_ID_PREMIUM }
                val yearlyDetails = productDetailsList.firstOrNull { it.productId == PremiumEntitlement.PRODUCT_ID_PREMIUM_YEARLY }

                _monthlyProductDetails.value = monthlyDetails
                _yearlyProductDetails.value = yearlyDetails

                val monthlyPrice = getFormattedPrice(monthlyDetails)
                val yearlyPrice = getFormattedPrice(yearlyDetails) ?: getYearlyPriceFromMonthlyDetails(monthlyDetails)
                val trialInfo = checkFreeTrial(monthlyDetails)

                val monthlyEquivalent = calculateMonthlyEquivalent(yearlyPrice)

                _entitlementState.value = _entitlementState.value.copy(
                    formattedPrice = monthlyPrice,
                    monthlyPrice = monthlyPrice,
                    yearlyPrice = yearlyPrice,
                    yearlyMonthlyEquivalent = monthlyEquivalent,
                    hasFreeTrial = trialInfo.first,
                    freeTrialDescription = trialInfo.second
                )

                saveCachedPricing(monthlyPrice, yearlyPrice)
                Log.d(TAG, "ProductDetails loaded: Monthly=$monthlyPrice, Yearly=$yearlyPrice, Trial=${trialInfo.second}")
            } else {
                Log.w(TAG, "QueryProductDetails failed: ${billingResult.responseCode} - ${billingResult.debugMessage}")
            }
        }
    }

    /**
     * Query Active Subscriptions on Google Play.
     */
    fun queryActivePurchases() {
        if (!billingClient.isReady) {
            startConnection()
            return
        }

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        billingClient.queryPurchasesAsync(params) { billingResult, purchasesList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                processPurchases(purchasesList)
            } else {
                Log.w(TAG, "QueryPurchases failed: ${billingResult.responseCode} - ${billingResult.debugMessage}")
            }
        }
    }

    fun onResume() {
        if (billingClient.isReady) {
            queryActivePurchases()
            if (_monthlyProductDetails.value == null) {
                queryProductDetails()
            }
        } else {
            startConnection()
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (!purchases.isNullOrEmpty()) {
                    processPurchases(purchases)
                    _purchaseMessage.value = "Nowhere Premium activated! Enjoy unlimited duration and zero ads."
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.d(TAG, "User canceled purchase.")
                _purchaseMessage.value = null
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                Log.d(TAG, "Item already owned. Reconciling active purchases.")
                queryActivePurchases()
                _purchaseMessage.value = "Subscription restored! Welcome back to Nowhere Premium."
            }
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingClient.BillingResponseCode.NETWORK_ERROR -> {
                Log.w(TAG, "Network/Service error during purchase: ${billingResult.debugMessage}")
                _purchaseMessage.value = "Unable to connect to Google Play. Please check your internet connection."
            }
            else -> {
                Log.w(TAG, "Purchase failed: ${billingResult.responseCode} - ${billingResult.debugMessage}")
                _purchaseMessage.value = "Purchase could not be completed: ${billingResult.debugMessage}"
            }
        }
    }

    private fun processPurchases(purchases: List<Purchase>) {
        val activePurchase = purchases.firstOrNull { purchase ->
            purchase.products.contains(PremiumEntitlement.PRODUCT_ID_PREMIUM) ||
            purchase.products.contains(PremiumEntitlement.PRODUCT_ID_PREMIUM_YEARLY)
        }

        if (activePurchase != null) {
            val isYearly = activePurchase.products.contains(PremiumEntitlement.PRODUCT_ID_PREMIUM_YEARLY)
            val matchedProductId = if (isYearly) PremiumEntitlement.PRODUCT_ID_PREMIUM_YEARLY else PremiumEntitlement.PRODUCT_ID_PREMIUM

            when (activePurchase.purchaseState) {
                Purchase.PurchaseState.PURCHASED -> {
                    val price = if (isYearly) _entitlementState.value.yearlyPrice else _entitlementState.value.monthlyPrice
                    val newEntitlement = _entitlementState.value.copy(
                        isPremium = true,
                        productId = matchedProductId,
                        purchaseToken = activePurchase.purchaseToken,
                        orderId = activePurchase.orderId,
                        purchaseTime = activePurchase.purchaseTime,
                        autoRenewing = activePurchase.isAutoRenewing,
                        isPending = false,
                        formattedPrice = price,
                        lastVerifiedTimestamp = System.currentTimeMillis()
                    )
                    updateEntitlement(newEntitlement)

                    if (!activePurchase.isAcknowledged) {
                        acknowledgePurchase(activePurchase.purchaseToken)
                    }
                }
                Purchase.PurchaseState.PENDING -> {
                    Log.d(TAG, "Purchase is in PENDING state. Not acknowledging yet.")
                    val pendingEntitlement = _entitlementState.value.copy(
                        isPremium = false,
                        productId = matchedProductId,
                        purchaseToken = activePurchase.purchaseToken,
                        orderId = activePurchase.orderId,
                        purchaseTime = activePurchase.purchaseTime,
                        autoRenewing = activePurchase.isAutoRenewing,
                        isPending = true,
                        lastVerifiedTimestamp = System.currentTimeMillis()
                    )
                    updateEntitlement(pendingEntitlement)
                }
                else -> {
                    updateEntitlement(PremiumEntitlement.FREE.copy(
                        formattedPrice = _entitlementState.value.formattedPrice,
                        monthlyPrice = _entitlementState.value.monthlyPrice,
                        yearlyPrice = _entitlementState.value.yearlyPrice,
                        yearlyMonthlyEquivalent = _entitlementState.value.yearlyMonthlyEquivalent,
                        hasFreeTrial = _entitlementState.value.hasFreeTrial,
                        freeTrialDescription = _entitlementState.value.freeTrialDescription
                    ))
                }
            }
        } else {
            updateEntitlement(PremiumEntitlement.FREE.copy(
                formattedPrice = _entitlementState.value.formattedPrice,
                monthlyPrice = _entitlementState.value.monthlyPrice,
                yearlyPrice = _entitlementState.value.yearlyPrice,
                yearlyMonthlyEquivalent = _entitlementState.value.yearlyMonthlyEquivalent,
                hasFreeTrial = _entitlementState.value.hasFreeTrial,
                freeTrialDescription = _entitlementState.value.freeTrialDescription
            ))
        }
    }

    private fun acknowledgePurchase(purchaseToken: String) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchaseToken)
            .build()

        billingClient.acknowledgePurchase(params) { billingResult ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                Log.d(TAG, "Subscription purchase acknowledged successfully.")
            } else {
                Log.w(TAG, "AcknowledgePurchase failed: ${billingResult.responseCode} - ${billingResult.debugMessage}")
            }
        }
    }

    private fun updateEntitlement(entitlement: PremiumEntitlement) {
        _entitlementState.value = entitlement
        _isPremium.value = entitlement.isPremium
        saveCachedEntitlement(entitlement)
    }

    /**
     * Launch Google Play purchase flow for either Monthly or Yearly plan.
     */
    fun launchPurchaseFlow(
        activity: Activity,
        planType: PremiumEntitlement.PlanType = PremiumEntitlement.PlanType.YEARLY
    ): BillingResult? {
        val isYearly = planType == PremiumEntitlement.PlanType.YEARLY

        val targetProductDetails: ProductDetails?
        val offerToken: String?

        if (isYearly) {
            // First look for dedicated yearly product
            if (_yearlyProductDetails.value != null) {
                targetProductDetails = _yearlyProductDetails.value
                offerToken = targetProductDetails?.subscriptionOfferDetails?.firstOrNull()?.offerToken
            } else {
                // Check if monthly product details contains yearly base plan offer
                targetProductDetails = _monthlyProductDetails.value
                val yearlyOffer = targetProductDetails?.subscriptionOfferDetails?.firstOrNull {
                    it.basePlanId.contains("year", ignoreCase = true) || it.offerId?.contains("year", ignoreCase = true) == true
                }
                offerToken = yearlyOffer?.offerToken ?: targetProductDetails?.subscriptionOfferDetails?.firstOrNull()?.offerToken
            }
        } else {
            // Monthly plan
            targetProductDetails = _monthlyProductDetails.value
            val monthlyOffer = targetProductDetails?.subscriptionOfferDetails?.firstOrNull {
                it.basePlanId.contains("month", ignoreCase = true) || !it.basePlanId.contains("year", ignoreCase = true)
            } ?: targetProductDetails?.subscriptionOfferDetails?.firstOrNull()
            offerToken = monthlyOffer?.offerToken
        }

        if (targetProductDetails == null || offerToken == null) {
            queryProductDetails()
            Toast.makeText(activity, "Loading subscription pricing from Google Play. Please try again in a moment.", Toast.LENGTH_SHORT).show()
            return null
        }

        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(targetProductDetails)
                .setOfferToken(offerToken)
                .build()
        )

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        return billingClient.launchBillingFlow(activity, billingFlowParams)
    }

    /**
     * Opens the Google Play subscription management page so users can manage or cancel.
     */
    fun openManageSubscriptions(activity: Activity) {
        try {
            val uri = Uri.parse("https://play.google.com/store/account/subscriptions?sku=${PremiumEntitlement.PRODUCT_ID_PREMIUM}&package=${activity.packageName}")
            val intent = Intent(Intent.ACTION_VIEW, uri)
            activity.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/account/subscriptions"))
                activity.startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(activity, "Could not open Google Play Subscriptions.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Extract localized formatted price string from ProductDetails.
     */
    fun getFormattedPrice(details: ProductDetails? = _monthlyProductDetails.value): String? {
        val offer = details?.subscriptionOfferDetails?.firstOrNull()
        // If there's a free trial phase, find the recurring paid phase
        val paidPhase = offer?.pricingPhases?.pricingPhaseList?.firstOrNull { it.priceAmountMicros > 0L }
            ?: offer?.pricingPhases?.pricingPhaseList?.firstOrNull()
        return paidPhase?.formattedPrice
    }

    private fun getYearlyPriceFromMonthlyDetails(details: ProductDetails?): String? {
        val yearlyOffer = details?.subscriptionOfferDetails?.firstOrNull {
            it.basePlanId.contains("year", ignoreCase = true)
        }
        val paidPhase = yearlyOffer?.pricingPhases?.pricingPhaseList?.firstOrNull { it.priceAmountMicros > 0L }
        return paidPhase?.formattedPrice
    }

    private fun checkFreeTrial(details: ProductDetails?): Pair<Boolean, String?> {
        val offer = details?.subscriptionOfferDetails?.firstOrNull() ?: return Pair(false, null)
        val trialPhase = offer.pricingPhases.pricingPhaseList.firstOrNull { it.priceAmountMicros == 0L }

        return if (trialPhase != null) {
            val desc = when (trialPhase.billingPeriod) {
                "P7D", "P1W" -> "7-Day Free Trial"
                "P14D", "P2W" -> "14-Day Free Trial"
                "P1M" -> "1-Month Free Trial"
                "P3D" -> "3-Day Free Trial"
                else -> "Free Trial Available"
            }
            Pair(true, desc)
        } else {
            Pair(false, null)
        }
    }

    private fun calculateMonthlyEquivalent(yearlyFormattedPrice: String?): String? {
        if (yearlyFormattedPrice == null) return null
        val digits = yearlyFormattedPrice.replace(Regex("[^0-9.]"), "")
        val priceVal = digits.toDoubleOrNull() ?: return null
        val perMonth = priceVal / 12.0

        val currencySymbol = yearlyFormattedPrice.replace(Regex("[0-9.,\\s]"), "")
        return if (currencySymbol.isNotEmpty()) {
            String.format(Locale.US, "%s%.2f / mo", currencySymbol, perMonth)
        } else {
            String.format(Locale.US, "%.2f / mo", perMonth)
        }
    }

    fun clearPurchaseMessage() {
        _purchaseMessage.value = null
    }

    private fun loadCachedEntitlement(): PremiumEntitlement {
        val isPrem = prefs.getBoolean(KEY_CACHED_IS_PREMIUM, false)
        val prodId = prefs.getString(KEY_CACHED_PRODUCT_ID, null)
        val token = prefs.getString(KEY_CACHED_TOKEN, null)
        val orderId = prefs.getString(KEY_CACHED_ORDER_ID, null)
        val pTime = prefs.getLong(KEY_CACHED_PURCHASE_TIME, 0L)
        val autoRenew = prefs.getBoolean(KEY_CACHED_AUTO_RENEW, false)
        val price = prefs.getString(KEY_CACHED_PRICE, null)
        val yearlyPrice = prefs.getString(KEY_CACHED_YEARLY_PRICE, null)
        val ts = prefs.getLong(KEY_CACHED_TIMESTAMP, 0L)

        return PremiumEntitlement(
            isPremium = isPrem,
            productId = prodId,
            purchaseToken = token,
            orderId = orderId,
            purchaseTime = pTime,
            autoRenewing = autoRenew,
            isPending = false,
            formattedPrice = price,
            monthlyPrice = price,
            yearlyPrice = yearlyPrice,
            yearlyMonthlyEquivalent = calculateMonthlyEquivalent(yearlyPrice),
            lastVerifiedTimestamp = ts
        )
    }

    private fun saveCachedEntitlement(entitlement: PremiumEntitlement) {
        prefs.edit()
            .putBoolean(KEY_CACHED_IS_PREMIUM, entitlement.isPremium)
            .putString(KEY_CACHED_PRODUCT_ID, entitlement.productId)
            .putString(KEY_CACHED_TOKEN, entitlement.purchaseToken)
            .putString(KEY_CACHED_ORDER_ID, entitlement.orderId)
            .putLong(KEY_CACHED_PURCHASE_TIME, entitlement.purchaseTime)
            .putBoolean(KEY_CACHED_AUTO_RENEW, entitlement.autoRenewing)
            .putString(KEY_CACHED_PRICE, entitlement.formattedPrice)
            .putString(KEY_CACHED_YEARLY_PRICE, entitlement.yearlyPrice)
            .putLong(KEY_CACHED_TIMESTAMP, entitlement.lastVerifiedTimestamp)
            .apply()
    }

    private fun saveCachedPricing(monthlyPrice: String?, yearlyPrice: String?) {
        prefs.edit()
            .putString(KEY_CACHED_PRICE, monthlyPrice)
            .putString(KEY_CACHED_YEARLY_PRICE, yearlyPrice)
            .apply()
    }
}
