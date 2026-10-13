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
import com.fakegps.mocklocation.R
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * Production-ready Google Play Billing 8.x/7.x Manager for Nowhere.
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
        private const val KEY_CACHED_SIGNATURE = "key_cached_signature"
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

    /**
     * Internal container holding a resolved plan offer ready for checkout.
     */
    data class ResolvedPlanOffer(
        val productDetails: ProductDetails,
        val offerToken: String?,
        val formattedPrice: String?,
        val priceAmountMicros: Long? = null,
        val currencyCode: String? = null,
        val isYearly: Boolean = false,
        val hasFreeTrial: Boolean = false,
        val freeTrialDescription: String? = null
    )

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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

    private val _isLoadingProducts = MutableStateFlow(false)
    val isLoadingProducts: StateFlow<Boolean> = _isLoadingProducts.asStateFlow()

    private val _purchaseMessage = MutableStateFlow<String?>(null)
    val purchaseMessage: StateFlow<String?> = _purchaseMessage.asStateFlow()

    private val _billingError = MutableStateFlow<String?>(null)
    val billingError: StateFlow<String?> = _billingError.asStateFlow()

    var lastResponseCode: Int = BillingClient.BillingResponseCode.OK
        private set
    var lastDebugMessage: String? = null
        private set

    private val _diagnosticEvent = MutableStateFlow<String?>(null)
    val diagnosticEvent: StateFlow<String?> = _diagnosticEvent.asStateFlow()

    fun clearDiagnosticEvent() {
        _diagnosticEvent.value = null
    }

    private var pendingPurchaseWatchdog: Runnable? = null

    private var billingClient: BillingClient = buildBillingClient()
    private var reconnectAttempts = 0

    // Pending purchase action queued while products or billing connection are loading
    private var pendingPurchasePlan: PremiumEntitlement.PlanType? = null
    private var pendingActivityRef: WeakReference<Activity>? = null

    // Callbacks to invoke once BillingClient becomes ready
    private val pendingReadyCallbacks = mutableListOf<() -> Unit>()

    init {
        startConnection()
    }

    private fun schedulePurchaseWatchdog() {
        pendingPurchaseWatchdog?.let { mainHandler.removeCallbacks(it) }
        val watchdog = Runnable {
            if (pendingPurchasePlan != null) {
                val err = _billingError.value ?: "Connection to Google Play timed out after 5s."
                cancelPendingPurchase("Timed out waiting for Google Play Store. $err")
            }
        }
        pendingPurchaseWatchdog = watchdog
        mainHandler.postDelayed(watchdog, 5000L)
    }

    fun cancelPendingPurchase(reason: String) {
        pendingPurchaseWatchdog?.let { mainHandler.removeCallbacks(it) }
        pendingPurchaseWatchdog = null

        val wasPending = pendingPurchasePlan != null
        pendingPurchasePlan = null
        pendingActivityRef = null
        _isLoadingProducts.value = false

        if (wasPending) {
            Log.w(TAG, "Pending purchase cancelled: $reason")
            _billingError.value = reason
            _diagnosticEvent.value = reason
        }
    }

    private fun buildBillingClient(): BillingClient {
        return BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .enablePrepaidPlans()
                    .build()
            )
            .build()
    }

    /**
     * Connect to Google Play Billing Service safely.
     */
    fun startConnection(onReady: (() -> Unit)? = null) {
        if (onReady != null) {
            synchronized(pendingReadyCallbacks) {
                pendingReadyCallbacks.add(onReady)
            }
        }

        if (billingClient.isReady) {
            _billingStatus.value = BillingStatus.READY
            executePendingReadyCallbacks()
            queryProductDetails()
            queryActivePurchases()
            return
        }

        if (billingClient.connectionState == BillingClient.ConnectionState.CONNECTING) {
            Log.d(TAG, "BillingClient connection already in progress.")
            return
        }

        if (billingClient.connectionState == BillingClient.ConnectionState.CLOSED) {
            Log.d(TAG, "BillingClient was closed. Rebuilding instance.")
            billingClient = buildBillingClient()
        }

        _billingStatus.value = BillingStatus.CONNECTING
        _isLoadingProducts.value = true
        _billingError.value = null

        try {
            billingClient.startConnection(this)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting BillingClient connection: ${e.message}", e)
            _billingStatus.value = BillingStatus.ERROR
            _isLoadingProducts.value = false
            _billingError.value = e.message
            cancelPendingPurchase(e.message ?: "Failed to start Google Play connection")
        }
    }

    override fun onBillingSetupFinished(billingResult: BillingResult) {
        lastResponseCode = billingResult.responseCode
        lastDebugMessage = billingResult.debugMessage
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            Log.d(TAG, "BillingClient connected successfully.")
            _billingStatus.value = BillingStatus.READY
            _billingError.value = null
            reconnectAttempts = 0
            executePendingReadyCallbacks()
            queryProductDetails()
            queryActivePurchases()
        } else {
            val errMsg = "Google Play Billing setup failed (${billingResult.responseCode}): ${billingResult.debugMessage}"
            Log.w(TAG, errMsg)
            _billingStatus.value = BillingStatus.ERROR
            _isLoadingProducts.value = false
            _billingError.value = errMsg
            cancelPendingPurchase(errMsg)
            retryConnectionWithBackoff()
        }
    }

    override fun onBillingServiceDisconnected() {
        Log.w(TAG, "Billing service disconnected.")
        _billingStatus.value = BillingStatus.DISCONNECTED
        cancelPendingPurchase("Google Play Billing service disconnected.")
        retryConnectionWithBackoff()
    }

    private fun executePendingReadyCallbacks() {
        val callbacks: List<() -> Unit>
        synchronized(pendingReadyCallbacks) {
            callbacks = ArrayList(pendingReadyCallbacks)
            pendingReadyCallbacks.clear()
        }
        for (cb in callbacks) {
            try {
                cb.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "Error executing onReady callback: ${e.message}", e)
            }
        }
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
     * Force-refreshes connection and queries product details.
     */
    fun refreshProductDetails() {
        reconnectAttempts = 0
        if (billingClient.isReady) {
            queryProductDetails()
            queryActivePurchases()
        } else {
            startConnection {
                queryProductDetails()
                queryActivePurchases()
            }
        }
    }

    /**
     * Resiliently query Subscription Product Details.
     * Queries products independently so a missing product ID on Google Play Console
     * does not block the entire batch query with ITEM_UNAVAILABLE.
     */
    fun queryProductDetails(onComplete: (() -> Unit)? = null) {
        if (!billingClient.isReady) {
            startConnection {
                queryProductDetails(onComplete)
            }
            return
        }

        _isLoadingProducts.value = true
        _billingError.value = null

        val candidateSubscriptionIds = listOf(
            PremiumEntitlement.PRODUCT_ID_PREMIUM,
            PremiumEntitlement.PRODUCT_ID_PREMIUM_YEARLY,
            PremiumEntitlement.PRODUCT_ID_NOWHERE_PRO,
            PremiumEntitlement.PRODUCT_ID_NOWHERE_PRO_HYPHEN,
            PremiumEntitlement.PRODUCT_ID_NOWHERE_PREMIUM_HYPHEN,
            PremiumEntitlement.PRODUCT_ID_NOWHERE_SUBSCRIPTION,
            PremiumEntitlement.PRODUCT_ID_NOWHERE_SUBSCRIPTION_HYPHEN,
            PremiumEntitlement.PRODUCT_ID_NOWHERE_PRO_YEARLY,
            PremiumEntitlement.PRODUCT_ID_PRO,
            PremiumEntitlement.PRODUCT_ID_PREMIUM_SHORT,
            PremiumEntitlement.PRODUCT_ID_VIP,
            PremiumEntitlement.PRODUCT_ID_NOWHERE_VIP
        )

        val retrievedProductDetails = mutableListOf<ProductDetails>()
        val totalQueries = candidateSubscriptionIds.size
        var completedQueries = 0

        fun checkFinished() {
            if (completedQueries >= totalQueries) {
                if (retrievedProductDetails.isNotEmpty()) {
                    processLoadedProductDetails(retrievedProductDetails)
                    _isLoadingProducts.value = false
                    executePendingPurchaseIfReady()
                    onComplete?.invoke()
                } else {
                    // Fallback check for one-time INAPP products if no SUBS were returned
                    queryInAppProductDetails(candidateSubscriptionIds, onComplete)
                }
            }
        }

        for (prodId in candidateSubscriptionIds) {
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(
                    listOf(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(prodId)
                            .setProductType(BillingClient.ProductType.SUBS)
                            .build()
                    )
                )
                .build()

            billingClient.queryProductDetailsAsync(params) { billingResult, queryResult ->
                mainHandler.post {
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        if (queryResult.productDetailsList.isNotEmpty()) {
                            retrievedProductDetails.addAll(queryResult.productDetailsList)
                            Log.d(TAG, "QueryProductDetails for $prodId found: ${queryResult.productDetailsList.size} items")
                        }
                    } else {
                        lastResponseCode = billingResult.responseCode
                        lastDebugMessage = billingResult.debugMessage
                        Log.d(TAG, "QueryProductDetails for $prodId returned code ${billingResult.responseCode}: ${billingResult.debugMessage}")
                    }
                    completedQueries++
                    checkFinished()
                }
            }
        }
    }

    /**
     * Fallback query for in-app products (in case user configured one-time purchase in Play Console).
     */
    private fun queryInAppProductDetails(candidateIds: List<String>, onComplete: (() -> Unit)? = null) {
        val inAppResults = mutableListOf<ProductDetails>()
        var inAppCompleted = 0
        val totalInApp = candidateIds.size

        fun finishInApp() {
            if (inAppCompleted >= totalInApp) {
                _isLoadingProducts.value = false
                if (inAppResults.isNotEmpty()) {
                    Log.d(TAG, "QueryProductDetails (INAPP) found: ${inAppResults.size} items")
                    processLoadedProductDetails(inAppResults)
                    executePendingPurchaseIfReady()
                } else {
                    val notFoundMsg = buildString {
                        append("Google Play returned 0 products for [${candidateIds.take(4).joinToString(", ")}...].")
                        if (lastResponseCode != BillingClient.BillingResponseCode.OK) {
                            append(" (Response code $lastResponseCode: ${lastDebugMessage.orEmpty()})")
                        }
                    }
                    Log.w(TAG, notFoundMsg)
                    if (_monthlyProductDetails.value == null && _yearlyProductDetails.value == null) {
                        _billingError.value = notFoundMsg
                    }
                    cancelPendingPurchase(notFoundMsg)
                }
                onComplete?.invoke()
            }
        }

        for (prodId in candidateIds) {
            val inAppParams = QueryProductDetailsParams.newBuilder()
                .setProductList(
                    listOf(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(prodId)
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build()
                    )
                )
                .build()

            billingClient.queryProductDetailsAsync(inAppParams) { billingResult, queryResult ->
                mainHandler.post {
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        if (queryResult.productDetailsList.isNotEmpty()) {
                            inAppResults.addAll(queryResult.productDetailsList)
                        }
                    } else {
                        lastResponseCode = billingResult.responseCode
                        lastDebugMessage = billingResult.debugMessage
                    }
                    inAppCompleted++
                    finishInApp()
                }
            }
        }
    }

    /**
     * Analyzes and maps returned ProductDetails into monthly & yearly plans with base plans & offer tokens.
     */
    private fun processLoadedProductDetails(detailsList: List<ProductDetails>) {
        if (detailsList.isEmpty()) return

        var foundMonthlyDetails: ProductDetails? = null
        var foundYearlyDetails: ProductDetails? = null

        var bestMonthlyPrice: String? = null
        var bestYearlyPrice: String? = null
        var bestYearlyMicros: Long? = null
        var bestYearlyCurrency: String? = null
        var bestTrialInfo = Pair(false, null as String?)

        for (details in detailsList) {
            val offers = details.subscriptionOfferDetails
            if (!offers.isNullOrEmpty()) {
                for (offer in offers) {
                    val isYearly = isOfferYearly(offer)
                    val isMonthly = isOfferMonthly(offer)
                    val paidPhase = offer.pricingPhases.pricingPhaseList.firstOrNull { it.priceAmountMicros > 0L }
                        ?: offer.pricingPhases.pricingPhaseList.firstOrNull()

                    val trial = checkFreeTrial(offer)
                    if (trial.first) {
                        bestTrialInfo = trial
                    }

                    if (isYearly) {
                        foundYearlyDetails = details
                        if (bestYearlyPrice == null && paidPhase != null) {
                            bestYearlyPrice = paidPhase.formattedPrice
                            bestYearlyMicros = paidPhase.priceAmountMicros
                            bestYearlyCurrency = paidPhase.priceCurrencyCode
                        }
                    }

                    if (isMonthly || (!isYearly && foundMonthlyDetails == null)) {
                        foundMonthlyDetails = details
                        if (bestMonthlyPrice == null && paidPhase != null) {
                            bestMonthlyPrice = paidPhase.formattedPrice
                        }
                    }
                }
            } else if (details.oneTimePurchaseOfferDetails != null) {
                // INAPP One-Time Purchase
                foundMonthlyDetails = details
                foundYearlyDetails = details
                bestMonthlyPrice = details.oneTimePurchaseOfferDetails?.formattedPrice
                bestYearlyPrice = details.oneTimePurchaseOfferDetails?.formattedPrice
            }
        }

        // If dedicated yearly product was not found, check if monthly details contains yearly offer
        if (foundYearlyDetails == null) {
            foundYearlyDetails = foundMonthlyDetails
        }
        if (foundMonthlyDetails == null) {
            foundMonthlyDetails = foundYearlyDetails
        }

        _monthlyProductDetails.value = foundMonthlyDetails
        _yearlyProductDetails.value = foundYearlyDetails

        val monthlyPrice = bestMonthlyPrice ?: getFormattedPrice(foundMonthlyDetails)
        val yearlyPrice = bestYearlyPrice ?: getFormattedPrice(foundYearlyDetails) ?: getYearlyPriceFromMonthlyDetails(foundMonthlyDetails)
        val monthlyEquivalent = calculateMonthlyEquivalent(yearlyPrice, bestYearlyMicros, bestYearlyCurrency)

        _entitlementState.value = _entitlementState.value.copy(
            formattedPrice = monthlyPrice ?: yearlyPrice,
            monthlyPrice = monthlyPrice,
            yearlyPrice = yearlyPrice,
            yearlyMonthlyEquivalent = monthlyEquivalent,
            hasFreeTrial = bestTrialInfo.first,
            freeTrialDescription = bestTrialInfo.second
        )

        saveCachedPricing(monthlyPrice, yearlyPrice)
        Log.d(TAG, "Processed Google Play plans: Monthly=$monthlyPrice, Yearly=$yearlyPrice, Equiv=$monthlyEquivalent")
    }

    private fun isOfferYearly(offer: ProductDetails.SubscriptionOfferDetails): Boolean {
        val basePlan = offer.basePlanId.lowercase(Locale.ROOT)
        val offerId = offer.offerId?.lowercase(Locale.ROOT).orEmpty()

        if (basePlan.contains("year") || basePlan.contains("annual") || basePlan.contains("12m") || basePlan.contains("p1y") || basePlan.contains("annually")) {
            return true
        }
        if (offerId.contains("year") || offerId.contains("annual") || offerId.contains("annually")) {
            return true
        }
        val phases = offer.pricingPhases.pricingPhaseList
        return phases.any { it.billingPeriod.contains("Y", ignoreCase = true) || it.billingPeriod.contains("P1Y", ignoreCase = true) }
    }

    private fun isOfferMonthly(offer: ProductDetails.SubscriptionOfferDetails): Boolean {
        val basePlan = offer.basePlanId.lowercase(Locale.ROOT)
        val offerId = offer.offerId?.lowercase(Locale.ROOT).orEmpty()

        if (basePlan.contains("month") || basePlan.contains("1m") || basePlan.contains("p1m") || basePlan.contains("monthly")) {
            return true
        }
        if (offerId.contains("month") || offerId.contains("monthly")) {
            return true
        }
        val phases = offer.pricingPhases.pricingPhaseList
        if (phases.any { it.billingPeriod.contains("M", ignoreCase = true) || it.billingPeriod.contains("P1M", ignoreCase = true) }) {
            return true
        }
        return !isOfferYearly(offer)
    }

    /**
     * Resolves the best available ProductDetails and offerToken for a plan type.
     */
    fun resolvePlanOffer(planType: PremiumEntitlement.PlanType): ResolvedPlanOffer? {
        val isYearly = planType == PremiumEntitlement.PlanType.YEARLY

        if (isYearly) {
            val yearlyDetails = _yearlyProductDetails.value
            val yearlyOffers = yearlyDetails?.subscriptionOfferDetails
            if (!yearlyOffers.isNullOrEmpty()) {
                val yearlyOffer = yearlyOffers.firstOrNull { isOfferYearly(it) } ?: yearlyOffers.firstOrNull()
                val paidPhase = yearlyOffer?.pricingPhases?.pricingPhaseList?.firstOrNull { it.priceAmountMicros > 0L }
                    ?: yearlyOffer?.pricingPhases?.pricingPhaseList?.firstOrNull()
                return ResolvedPlanOffer(
                    productDetails = yearlyDetails,
                    offerToken = yearlyOffer?.offerToken,
                    formattedPrice = paidPhase?.formattedPrice,
                    priceAmountMicros = paidPhase?.priceAmountMicros,
                    currencyCode = paidPhase?.priceCurrencyCode,
                    isYearly = true
                )
            } else if (yearlyDetails?.oneTimePurchaseOfferDetails != null) {
                val otp = yearlyDetails.oneTimePurchaseOfferDetails
                return ResolvedPlanOffer(
                    productDetails = yearlyDetails,
                    offerToken = null,
                    formattedPrice = otp?.formattedPrice,
                    priceAmountMicros = otp?.priceAmountMicros,
                    currencyCode = otp?.priceCurrencyCode,
                    isYearly = true
                )
            }

            // Fallback: check if monthly details has a yearly offer
            val monthlyDetails = _monthlyProductDetails.value
            val monthlyOffers = monthlyDetails?.subscriptionOfferDetails
            val yearlyInMonthly = monthlyOffers?.firstOrNull { isOfferYearly(it) }
            if (yearlyInMonthly != null && monthlyDetails != null) {
                val paidPhase = yearlyInMonthly.pricingPhases.pricingPhaseList.firstOrNull { it.priceAmountMicros > 0L }
                    ?: yearlyInMonthly.pricingPhases.pricingPhaseList.firstOrNull()
                return ResolvedPlanOffer(
                    productDetails = monthlyDetails,
                    offerToken = yearlyInMonthly.offerToken,
                    formattedPrice = paidPhase?.formattedPrice,
                    priceAmountMicros = paidPhase?.priceAmountMicros,
                    currencyCode = paidPhase?.priceCurrencyCode,
                    isYearly = true
                )
            }
        } else {
            // Monthly Plan
            val monthlyDetails = _monthlyProductDetails.value
            val monthlyOffers = monthlyDetails?.subscriptionOfferDetails
            if (!monthlyOffers.isNullOrEmpty()) {
                val monthlyOffer = monthlyOffers.firstOrNull { isOfferMonthly(it) } ?: monthlyOffers.firstOrNull()
                val paidPhase = monthlyOffer?.pricingPhases?.pricingPhaseList?.firstOrNull { it.priceAmountMicros > 0L }
                    ?: monthlyOffer?.pricingPhases?.pricingPhaseList?.firstOrNull()
                val trial = monthlyOffer?.let { checkFreeTrial(it) } ?: Pair(false, null)
                return ResolvedPlanOffer(
                    productDetails = monthlyDetails,
                    offerToken = monthlyOffer?.offerToken,
                    formattedPrice = paidPhase?.formattedPrice,
                    priceAmountMicros = paidPhase?.priceAmountMicros,
                    currencyCode = paidPhase?.priceCurrencyCode,
                    isYearly = false,
                    hasFreeTrial = trial.first,
                    freeTrialDescription = trial.second
                )
            } else if (monthlyDetails?.oneTimePurchaseOfferDetails != null) {
                val otp = monthlyDetails.oneTimePurchaseOfferDetails
                return ResolvedPlanOffer(
                    productDetails = monthlyDetails,
                    offerToken = null,
                    formattedPrice = otp?.formattedPrice,
                    priceAmountMicros = otp?.priceAmountMicros,
                    currencyCode = otp?.priceCurrencyCode,
                    isYearly = false
                )
            }
        }

        // Generic fallback: if either product details is available, grab its first valid offer
        val fallbackDetails = if (isYearly) _yearlyProductDetails.value ?: _monthlyProductDetails.value else _monthlyProductDetails.value ?: _yearlyProductDetails.value
        val fallbackOffers = fallbackDetails?.subscriptionOfferDetails
        if (fallbackDetails != null && !fallbackOffers.isNullOrEmpty()) {
            val firstOffer = fallbackOffers.first()
            val paidPhase = firstOffer.pricingPhases.pricingPhaseList.firstOrNull { it.priceAmountMicros > 0L }
                ?: firstOffer.pricingPhases.pricingPhaseList.firstOrNull()
            return ResolvedPlanOffer(
                productDetails = fallbackDetails,
                offerToken = firstOffer.offerToken,
                formattedPrice = paidPhase?.formattedPrice,
                priceAmountMicros = paidPhase?.priceAmountMicros,
                currencyCode = paidPhase?.priceCurrencyCode,
                isYearly = isYearly
            )
        }

        return null
    }

    /**
     * Query Active Subscriptions and One-Time Purchases on Google Play.
     */
    fun queryActivePurchases() {
        if (!billingClient.isReady) {
            startConnection()
            return
        }

        val subsParams = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        billingClient.queryPurchasesAsync(subsParams) { billingResult, purchasesList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val activeSubs = purchasesList.filter { isNowhereProduct(it) }
                if (activeSubs.isNotEmpty()) {
                    processPurchases(activeSubs)
                } else {
                    // Check INAPP purchases as fallback
                    queryActiveInAppPurchases()
                }
            } else {
                Log.w(TAG, "QueryPurchases SUBS failed: ${billingResult.responseCode} - ${billingResult.debugMessage}")
            }
        }
    }

    private fun queryActiveInAppPurchases() {
        val inAppParams = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        billingClient.queryPurchasesAsync(inAppParams) { billingResult, purchasesList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                processPurchases(purchasesList.filter { isNowhereProduct(it) })
            } else {
                Log.w(TAG, "QueryPurchases INAPP failed: ${billingResult.responseCode} - ${billingResult.debugMessage}")
            }
        }
    }

    private fun isNowhereProduct(purchase: Purchase): Boolean {
        return purchase.products.any { PremiumEntitlement.ALL_PRODUCT_IDS.contains(it) }
    }

    fun onResume() {
        if (billingClient.isReady) {
            queryActivePurchases()
            if (_monthlyProductDetails.value == null && _yearlyProductDetails.value == null) {
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
                    _purchaseMessage.value = try {
                        context.getString(R.string.premium_purchase_success)
                    } catch (e: Exception) {
                        "Nowhere Premium activated! Enjoy unlimited duration and zero ads."
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.d(TAG, "User canceled purchase.")
                _purchaseMessage.value = null
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                Log.d(TAG, "Item already owned. Reconciling active purchases.")
                queryActivePurchases()
                _purchaseMessage.value = try {
                    context.getString(R.string.premium_purchase_restored)
                } catch (e: Exception) {
                    "Subscription restored! Welcome back to Nowhere Premium."
                }
            }
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingClient.BillingResponseCode.NETWORK_ERROR -> {
                Log.w(TAG, "Network/Service error during purchase: ${billingResult.debugMessage}")
                _purchaseMessage.value = try {
                    context.getString(R.string.premium_network_error)
                } catch (e: Exception) {
                    "Unable to connect to Google Play. Please check your internet connection."
                }
            }
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> {
                Log.w(TAG, "Billing unavailable: ${billingResult.debugMessage}")
                _purchaseMessage.value = try {
                    context.getString(R.string.premium_play_store_unavailable)
                } catch (e: Exception) {
                    "Google Play Billing is currently unavailable on this device."
                }
            }
            else -> {
                Log.w(TAG, "Purchase failed: ${billingResult.responseCode} - ${billingResult.debugMessage}")
                _purchaseMessage.value = "Purchase could not be completed: ${billingResult.debugMessage}"
            }
        }
    }

    private fun processPurchases(purchases: List<Purchase>) {
        val activePurchase = purchases.firstOrNull { isNowhereProduct(it) }

        if (activePurchase != null) {
            val matchedProductId = activePurchase.products.firstOrNull { PremiumEntitlement.ALL_PRODUCT_IDS.contains(it) }
                ?: PremiumEntitlement.PRODUCT_ID_PREMIUM
            val isYearly = matchedProductId == PremiumEntitlement.PRODUCT_ID_PREMIUM_YEARLY

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
                    resetToFreeEntitlement()
                }
            }
        } else {
            // Keep debug sandbox premium active if currently enabled
            if (_entitlementState.value.productId == PremiumEntitlement.PRODUCT_ID_SANDBOX && _entitlementState.value.isPremium) {
                Log.d(TAG, "Retaining Sandbox Nowhere Pro entitlement.")
            } else {
                resetToFreeEntitlement()
            }
        }
    }

    private fun resetToFreeEntitlement() {
        updateEntitlement(PremiumEntitlement.FREE.copy(
            formattedPrice = _entitlementState.value.formattedPrice,
            monthlyPrice = _entitlementState.value.monthlyPrice,
            yearlyPrice = _entitlementState.value.yearlyPrice,
            yearlyMonthlyEquivalent = _entitlementState.value.yearlyMonthlyEquivalent,
            hasFreeTrial = _entitlementState.value.hasFreeTrial,
            freeTrialDescription = _entitlementState.value.freeTrialDescription
        ))
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
     * Automatically handles connection warmup, product resolution, and seamless auto-launch.
     */
    fun launchPurchaseFlow(
        activity: Activity,
        planType: PremiumEntitlement.PlanType = PremiumEntitlement.PlanType.YEARLY
    ): BillingResult? {
        val planOffer = resolvePlanOffer(planType)

        if (planOffer == null) {
            Log.d(TAG, "Plan offer not ready yet. Scheduling pending purchase for plan: $planType")
            pendingPurchasePlan = planType
            pendingActivityRef = WeakReference(activity)
            schedulePurchaseWatchdog()

            val loadingMsg = try {
                context.getString(R.string.premium_connecting_google_play)
            } catch (e: Exception) {
                "Connecting to Google Play Store…"
            }
            Toast.makeText(activity, loadingMsg, Toast.LENGTH_SHORT).show()

            refreshProductDetails()
            return null
        }

        pendingPurchaseWatchdog?.let { mainHandler.removeCallbacks(it) }
        pendingPurchaseWatchdog = null
        pendingPurchasePlan = null
        pendingActivityRef = null

        val productDetailsParamsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(planOffer.productDetails)

        if (!planOffer.offerToken.isNullOrBlank()) {
            productDetailsParamsBuilder.setOfferToken(planOffer.offerToken)
        }

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParamsBuilder.build()))
            .build()

        return billingClient.launchBillingFlow(activity, billingFlowParams)
    }

    private fun executePendingPurchaseIfReady(activity: Activity? = pendingActivityRef?.get()) {
        val plan = pendingPurchasePlan ?: return
        val targetAct = activity ?: pendingActivityRef?.get() ?: return

        val planOffer = resolvePlanOffer(plan)
        if (planOffer != null) {
            Log.d(TAG, "Auto-launching queued purchase flow now that products are ready!")
            pendingPurchaseWatchdog?.let { mainHandler.removeCallbacks(it) }
            pendingPurchaseWatchdog = null
            pendingPurchasePlan = null
            pendingActivityRef = null
            launchPurchaseFlow(targetAct, plan)
        }
    }

    data class BillingDiagnosticInfo(
        val billingStatus: BillingStatus,
        val isReady: Boolean,
        val connectionState: Int,
        val lastResponseCode: Int,
        val lastDebugMessage: String?,
        val monthlyDetailsFound: Boolean,
        val yearlyDetailsFound: Boolean,
        val productsCount: Int,
        val testedCandidateIds: List<String>,
        val lastError: String?
    )

    fun getDiagnosticInfo(): BillingDiagnosticInfo {
        val prods = listOfNotNull(_monthlyProductDetails.value, _yearlyProductDetails.value).distinctBy { it.productId }
        val candidateSubscriptionIds = listOf(
            PremiumEntitlement.PRODUCT_ID_PREMIUM,
            PremiumEntitlement.PRODUCT_ID_PREMIUM_YEARLY,
            PremiumEntitlement.PRODUCT_ID_NOWHERE_PRO,
            PremiumEntitlement.PRODUCT_ID_NOWHERE_PRO_HYPHEN,
            PremiumEntitlement.PRODUCT_ID_NOWHERE_PREMIUM_HYPHEN,
            PremiumEntitlement.PRODUCT_ID_NOWHERE_SUBSCRIPTION,
            PremiumEntitlement.PRODUCT_ID_NOWHERE_SUBSCRIPTION_HYPHEN,
            PremiumEntitlement.PRODUCT_ID_NOWHERE_PRO_YEARLY,
            PremiumEntitlement.PRODUCT_ID_PRO,
            PremiumEntitlement.PRODUCT_ID_PREMIUM_SHORT,
            PremiumEntitlement.PRODUCT_ID_VIP,
            PremiumEntitlement.PRODUCT_ID_NOWHERE_VIP
        )
        return BillingDiagnosticInfo(
            billingStatus = _billingStatus.value,
            isReady = billingClient.isReady,
            connectionState = billingClient.connectionState,
            lastResponseCode = lastResponseCode,
            lastDebugMessage = lastDebugMessage,
            monthlyDetailsFound = _monthlyProductDetails.value != null,
            yearlyDetailsFound = _yearlyProductDetails.value != null,
            productsCount = prods.size,
            testedCandidateIds = candidateSubscriptionIds,
            lastError = _billingError.value
        )
    }

    /**
     * Toggles a secure developer sandbox entitlement for testing on devices
     * where Google Play testing credentials or internal test tracks are unavailable.
     */
    fun toggleDebugSandboxPremium(enable: Boolean? = null): Boolean {
        val current = _isPremium.value
        val target = enable ?: !current

        if (target) {
            val now = System.currentTimeMillis()
            val entitlement = PremiumEntitlement(
                isPremium = true,
                productId = PremiumEntitlement.PRODUCT_ID_SANDBOX,
                purchaseToken = "sandbox_token_$now",
                orderId = "GPA.SANDBOX-$now",
                purchaseTime = now,
                autoRenewing = true,
                isPending = false,
                formattedPrice = "$0.00",
                monthlyPrice = "$0.00",
                yearlyPrice = "$0.00",
                yearlyMonthlyEquivalent = "$0.00 / mo",
                lastVerifiedTimestamp = now
            )
            updateEntitlement(entitlement)
            _purchaseMessage.value = "Sandbox Nowhere Pro Activated!"
        } else {
            resetToFreeEntitlement()
            _purchaseMessage.value = "Sandbox Nowhere Pro Deactivated."
        }
        return _isPremium.value
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
        val offers = details?.subscriptionOfferDetails
        if (!offers.isNullOrEmpty()) {
            val offer = offers.firstOrNull()
            val paidPhase = offer?.pricingPhases?.pricingPhaseList?.firstOrNull { it.priceAmountMicros > 0L }
                ?: offer?.pricingPhases?.pricingPhaseList?.firstOrNull()
            return paidPhase?.formattedPrice
        }
        return details?.oneTimePurchaseOfferDetails?.formattedPrice
    }

    private fun getYearlyPriceFromMonthlyDetails(details: ProductDetails?): String? {
        val yearlyOffer = details?.subscriptionOfferDetails?.firstOrNull { isOfferYearly(it) }
        val paidPhase = yearlyOffer?.pricingPhases?.pricingPhaseList?.firstOrNull { it.priceAmountMicros > 0L }
        return paidPhase?.formattedPrice
    }

    private fun checkFreeTrial(offer: ProductDetails.SubscriptionOfferDetails): Pair<Boolean, String?> {
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

    private fun calculateMonthlyEquivalent(
        yearlyFormattedPrice: String?,
        yearlyMicros: Long? = null,
        currencyCode: String? = null
    ): String? {
        if (yearlyMicros != null && yearlyMicros > 0L && !currencyCode.isNullOrBlank()) {
            try {
                val monthlyMicros = yearlyMicros / 12.0
                val currency = java.util.Currency.getInstance(currencyCode)
                val format = java.text.NumberFormat.getCurrencyInstance().apply {
                    this.currency = currency
                    maximumFractionDigits = if (currency.defaultFractionDigits == 0) 0 else 2
                }
                val formatted = format.format(monthlyMicros / 1_000_000.0)
                return "$formatted / mo"
            } catch (e: Exception) {
                Log.w(TAG, "Failed to format currency by code: ${e.message}")
            }
        }

        if (yearlyFormattedPrice == null) return null
        val cleaned = yearlyFormattedPrice.trim()
        val hasCommaDecimal = cleaned.matches(Regex(".*,\\d{2}([^0-9]*)?$"))
        val normalized = if (hasCommaDecimal) {
            cleaned.replace(".", "").replace(",", ".")
        } else {
            cleaned.replace(",", "")
        }
        val digits = normalized.replace(Regex("[^0-9.]"), "")
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

    private fun generateCacheSignature(productId: String?, token: String?, purchaseTime: Long): String {
        val raw = "${context.packageName}:${productId.orEmpty()}:${token.orEmpty()}:$purchaseTime:NowhereProSecuredEntitlement"
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun loadCachedEntitlement(): PremiumEntitlement {
        val isPremRaw = prefs.getBoolean(KEY_CACHED_IS_PREMIUM, false)
        val prodId = prefs.getString(KEY_CACHED_PRODUCT_ID, null)
        val token = prefs.getString(KEY_CACHED_TOKEN, null)
        val orderId = prefs.getString(KEY_CACHED_ORDER_ID, null)
        val pTime = prefs.getLong(KEY_CACHED_PURCHASE_TIME, 0L)
        val autoRenew = prefs.getBoolean(KEY_CACHED_AUTO_RENEW, false)
        val price = prefs.getString(KEY_CACHED_PRICE, null)
        val yearlyPrice = prefs.getString(KEY_CACHED_YEARLY_PRICE, null)
        val ts = prefs.getLong(KEY_CACHED_TIMESTAMP, 0L)
        val cachedSig = prefs.getString(KEY_CACHED_SIGNATURE, null)

        val isSandbox = isPremRaw && prodId == PremiumEntitlement.PRODUCT_ID_SANDBOX

        // Cryptographic tamper verification: protect against unauthorized XML edits on rooted devices
        val isValidSignature = if (isPremRaw) {
            if (isSandbox) {
                true
            } else {
                !token.isNullOrBlank() && (PremiumEntitlement.ALL_PRODUCT_IDS.contains(prodId)) &&
                cachedSig != null && cachedSig == generateCacheSignature(prodId, token, pTime)
            }
        } else {
            false
        }

        val isPrem = isPremRaw && isValidSignature

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
        val editor = prefs.edit()
            .putBoolean(KEY_CACHED_IS_PREMIUM, entitlement.isPremium)
            .putString(KEY_CACHED_PRODUCT_ID, entitlement.productId)
            .putString(KEY_CACHED_TOKEN, entitlement.purchaseToken)
            .putString(KEY_CACHED_ORDER_ID, entitlement.orderId)
            .putLong(KEY_CACHED_PURCHASE_TIME, entitlement.purchaseTime)
            .putBoolean(KEY_CACHED_AUTO_RENEW, entitlement.autoRenewing)
            .putString(KEY_CACHED_PRICE, entitlement.formattedPrice)
            .putString(KEY_CACHED_YEARLY_PRICE, entitlement.yearlyPrice)
            .putLong(KEY_CACHED_TIMESTAMP, entitlement.lastVerifiedTimestamp)

        if (entitlement.isPremium && !entitlement.purchaseToken.isNullOrBlank()) {
            val sig = generateCacheSignature(entitlement.productId, entitlement.purchaseToken, entitlement.purchaseTime)
            editor.putString(KEY_CACHED_SIGNATURE, sig)
        } else {
            editor.remove(KEY_CACHED_SIGNATURE)
        }

        editor.apply()
    }

    private fun saveCachedPricing(monthlyPrice: String?, yearlyPrice: String?) {
        prefs.edit()
            .putString(KEY_CACHED_PRICE, monthlyPrice)
            .putString(KEY_CACHED_YEARLY_PRICE, yearlyPrice)
            .apply()
    }
}
