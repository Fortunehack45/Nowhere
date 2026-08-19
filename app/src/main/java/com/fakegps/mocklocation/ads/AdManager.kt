package com.fakegps.mocklocation.ads

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RatingBar
import android.widget.TextView
import android.widget.Toast
import com.fakegps.mocklocation.BuildConfig
import com.fakegps.mocklocation.R
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import com.google.android.gms.ads.rewarded.RewardItem
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

object AdManager {

    private const val TAG = "AdManager"

    // Production AdMob Ad Unit IDs
    const val PROD_BANNER_AD_UNIT_ID = "ca-app-pub-5191202278112313/6778431802"
    const val PROD_HOME_BANNER_AD_UNIT_ID = "ca-app-pub-5191202278112313/8553859547"
    const val PROD_APP_OPEN_AD_UNIT_ID = "ca-app-pub-5191202278112313/3576719243"
    const val PROD_NATIVE_AD_UNIT_ID = "ca-app-pub-5191202278112313/5736124511"
    const val PROD_REWARDED_AD_UNIT_ID = "ca-app-pub-5191202278112313/1933445026"
    const val PROD_REWARDED_INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-5191202278112313/6932394336"

    // Official Google Test Ad Unit IDs for safe debug, QA & reliable fill
    const val TEST_BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"
    const val TEST_INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"
    const val TEST_NATIVE_AD_UNIT_ID = "ca-app-pub-3940256099942544/2247696110"
    const val TEST_REWARDED_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"
    const val TEST_REWARDED_INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-3940256099942544/5354046379"

    private var isInitialized = false
    private var interstitialAd: InterstitialAd? = null
    private var rewardedAd: RewardedAd? = null
    private var rewardedInterstitialAd: RewardedInterstitialAd? = null
    private var lastInterstitialShowTime: Long = 0
    private const val INTERSTITIAL_COOLDOWN_MS = 60_000L // 1 minute cooldown

    fun initialize(context: Context) {
        try {
            MobileAds.initialize(context) { status ->
                isInitialized = true
                Log.d(TAG, "Google Mobile Ads initialized successfully: $status")
                preloadAllAds(context)
            }
        } catch (e: Exception) {
            Log.w(TAG, "AdMob initialization skipped: ${e.message}")
        }
    }

    private fun preloadAllAds(context: Context) {
        preloadInterstitial(context)
        preloadRewardedAd(context)
        preloadRewardedInterstitialAd(context)
    }

    /**
     * Loads an Adaptive/Standard Banner Ad into the specified container with automatic fallback.
     */
    fun loadBanner(activity: Activity, container: FrameLayout, isHomeBanner: Boolean = false) {
        val primaryAdUnit = if (BuildConfig.DEBUG) {
            TEST_BANNER_AD_UNIT_ID
        } else {
            if (isHomeBanner) PROD_HOME_BANNER_AD_UNIT_ID else PROD_BANNER_AD_UNIT_ID
        }
        val fallbackAdUnit = if (isHomeBanner) PROD_BANNER_AD_UNIT_ID else PROD_HOME_BANNER_AD_UNIT_ID

        loadBannerInternal(activity, container, primaryAdUnit, fallbackAdUnit)
    }

    private fun loadBannerInternal(
        activity: Activity,
        container: FrameLayout,
        adUnitId: String,
        fallbackAdUnitId: String? = null
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        activity.runOnUiThread {
            try {
                val adView = AdView(activity).apply {
                    this.adUnitId = adUnitId
                    setAdSize(AdSize.BANNER)
                }

                adView.adListener = object : AdListener() {
                    override fun onAdLoaded() {
                        container.visibility = View.VISIBLE
                        Log.d(TAG, "Banner ad loaded successfully ($adUnitId)")
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        Log.w(TAG, "Banner failed to load ($adUnitId): ${error.message} (code: ${error.code})")
                        if (fallbackAdUnitId != null && fallbackAdUnitId != adUnitId) {
                            Log.d(TAG, "Retrying banner with fallback ad unit ($fallbackAdUnitId)")
                            loadBannerInternal(activity, container, fallbackAdUnitId, if (BuildConfig.DEBUG) null else TEST_BANNER_AD_UNIT_ID)
                        } else if (adUnitId != TEST_BANNER_AD_UNIT_ID) {
                            Log.d(TAG, "Retrying banner with test ad unit ($TEST_BANNER_AD_UNIT_ID)")
                            loadBannerInternal(activity, container, TEST_BANNER_AD_UNIT_ID, null)
                        }
                    }
                }

                container.removeAllViews()
                container.addView(adView)
                val adRequest = AdRequest.Builder().build()
                adView.loadAd(adRequest)
            } catch (e: Exception) {
                Log.w(TAG, "Could not load banner: ${e.message}")
            }
        }
    }

    /**
     * Loads a Native Advanced Ad into the specified container with automatic fallback.
     */
    fun loadNativeAd(activity: Activity, container: FrameLayout) {
        val primaryUnit = if (BuildConfig.DEBUG) TEST_NATIVE_AD_UNIT_ID else PROD_NATIVE_AD_UNIT_ID
        loadNativeAdInternal(activity, container, primaryUnit, if (BuildConfig.DEBUG) null else TEST_NATIVE_AD_UNIT_ID)
    }

    private fun loadNativeAdInternal(
        activity: Activity,
        container: FrameLayout,
        adUnitId: String,
        fallbackUnitId: String? = null
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        try {
            val adLoader = AdLoader.Builder(activity, adUnitId)
                .forNativeAd { nativeAd ->
                    if (activity.isFinishing || activity.isDestroyed) {
                        nativeAd.destroy()
                        return@forNativeAd
                    }

                    val adView = LayoutInflater.from(activity).inflate(
                        R.layout.layout_admob_native_card,
                        null
                    ) as NativeAdView

                    populateNativeAdView(nativeAd, adView)
                    container.removeAllViews()
                    container.addView(adView)
                    container.visibility = View.VISIBLE
                    Log.d(TAG, "Native ad rendered successfully ($adUnitId)")
                }
                .withAdListener(object : AdListener() {
                    override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                        Log.w(TAG, "Native ad failed to load ($adUnitId): ${loadAdError.message}")
                        if (fallbackUnitId != null && fallbackUnitId != adUnitId) {
                            loadNativeAdInternal(activity, container, fallbackUnitId, null)
                        } else {
                            container.visibility = View.GONE
                        }
                    }
                })
                .withNativeAdOptions(
                    NativeAdOptions.Builder()
                        .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
                        .build()
                )
                .build()

            adLoader.loadAd(AdRequest.Builder().build())
        } catch (e: Exception) {
            Log.w(TAG, "Native ad error: ${e.message}")
            container.visibility = View.GONE
        }
    }

    private fun populateNativeAdView(nativeAd: NativeAd, adView: NativeAdView) {
        adView.headlineView = adView.findViewById(R.id.ad_headline)
        adView.bodyView = adView.findViewById(R.id.ad_body)
        adView.callToActionView = adView.findViewById(R.id.ad_call_to_action)
        adView.iconView = adView.findViewById(R.id.ad_app_icon)
        adView.priceView = adView.findViewById(R.id.ad_price)
        adView.starRatingView = adView.findViewById(R.id.ad_stars)
        adView.storeView = adView.findViewById(R.id.ad_store)
        adView.advertiserView = adView.findViewById(R.id.ad_advertiser)
        adView.mediaView = adView.findViewById(R.id.ad_media)

        // Headline
        (adView.headlineView as? TextView)?.text = nativeAd.headline

        // Body
        if (nativeAd.body == null) {
            adView.bodyView?.visibility = View.GONE
        } else {
            adView.bodyView?.visibility = View.VISIBLE
            (adView.bodyView as? TextView)?.text = nativeAd.body
        }

        // Call to action
        if (nativeAd.callToAction == null) {
            adView.callToActionView?.visibility = View.GONE
        } else {
            adView.callToActionView?.visibility = View.VISIBLE
            (adView.callToActionView as? MaterialButton)?.text = nativeAd.callToAction
        }

        // Icon
        if (nativeAd.icon == null) {
            adView.iconView?.visibility = View.GONE
        } else {
            (adView.iconView as? ImageView)?.setImageDrawable(nativeAd.icon?.drawable)
            adView.iconView?.visibility = View.VISIBLE
        }

        // Price
        if (nativeAd.price == null) {
            adView.priceView?.visibility = View.GONE
        } else {
            adView.priceView?.visibility = View.VISIBLE
            (adView.priceView as? TextView)?.text = nativeAd.price
        }

        // Store
        if (nativeAd.store == null) {
            adView.storeView?.visibility = View.GONE
        } else {
            adView.storeView?.visibility = View.VISIBLE
            (adView.storeView as? TextView)?.text = nativeAd.store
        }

        // Star Rating
        if (nativeAd.starRating == null) {
            adView.starRatingView?.visibility = View.GONE
        } else {
            (adView.starRatingView as? RatingBar)?.rating = nativeAd.starRating!!.toFloat()
            adView.starRatingView?.visibility = View.VISIBLE
        }

        // Advertiser
        if (nativeAd.advertiser == null) {
            adView.advertiserView?.visibility = View.GONE
        } else {
            (adView.advertiserView as? TextView)?.text = nativeAd.advertiser
            adView.advertiserView?.visibility = View.VISIBLE
        }

        // Media
        nativeAd.mediaContent?.let { mediaContent ->
            adView.mediaView?.setMediaContent(mediaContent)
            adView.mediaView?.visibility = View.VISIBLE
        } ?: run {
            adView.mediaView?.visibility = View.GONE
        }

        adView.setNativeAd(nativeAd)
    }

    // --- Interstitial Ads ---

    fun preloadInterstitial(context: Context) {
        val primaryUnit = TEST_INTERSTITIAL_AD_UNIT_ID
        loadInterstitialInternal(context, primaryUnit)
    }

    private fun loadInterstitialInternal(context: Context, adUnit: String) {
        try {
            val adRequest = AdRequest.Builder().build()
            InterstitialAd.load(
                context,
                adUnit,
                adRequest,
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        interstitialAd = ad
                        Log.d(TAG, "Interstitial ad preloaded successfully ($adUnit)")
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        interstitialAd = null
                        Log.w(TAG, "Interstitial ad failed to load ($adUnit): ${error.message}")
                    }
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Preload interstitial error: ${e.message}")
        }
    }

    fun isInterstitialAdReady(): Boolean = interstitialAd != null

    fun showInterstitialAd(activity: Activity, onDismissed: () -> Unit) {
        interstitialAd?.let { ad ->
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    interstitialAd = null
                    lastInterstitialShowTime = System.currentTimeMillis()
                    preloadInterstitial(activity)
                    onDismissed()
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    interstitialAd = null
                    preloadInterstitial(activity)
                    onDismissed()
                }
            }
            ad.show(activity)
        } ?: run {
            preloadInterstitial(activity)
            onDismissed()
        }
    }

    fun showInterstitialIfReady(activity: Activity) {
        val now = System.currentTimeMillis()
        if (now - lastInterstitialShowTime < INTERSTITIAL_COOLDOWN_MS) {
            return
        }

        interstitialAd?.let { ad ->
            lastInterstitialShowTime = now
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    interstitialAd = null
                    preloadInterstitial(activity)
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    interstitialAd = null
                    preloadInterstitial(activity)
                }
            }
            ad.show(activity)
        } ?: run {
            preloadInterstitial(activity)
        }
    }

    // --- Rewarded Ads ---

    fun preloadRewardedAd(context: Context) {
        val primaryUnit = if (BuildConfig.DEBUG) TEST_REWARDED_AD_UNIT_ID else PROD_REWARDED_AD_UNIT_ID
        loadRewardedAdInternal(context, primaryUnit, if (BuildConfig.DEBUG) null else TEST_REWARDED_AD_UNIT_ID)
    }

    private fun loadRewardedAdInternal(context: Context, adUnit: String, fallbackUnit: String? = null) {
        try {
            val adRequest = AdRequest.Builder().build()

            RewardedAd.load(
                context,
                adUnit,
                adRequest,
                object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedAd) {
                        rewardedAd = ad
                        Log.d(TAG, "Rewarded ad preloaded successfully ($adUnit)")
                    }

                    override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                        rewardedAd = null
                        Log.w(TAG, "Rewarded ad failed to load ($adUnit): ${loadAdError.message}")
                        if (fallbackUnit != null && fallbackUnit != adUnit) {
                            loadRewardedAdInternal(context, fallbackUnit, null)
                        }
                    }
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Preload rewarded error: ${e.message}")
        }
    }

    fun isRewardedAdReady(): Boolean = rewardedAd != null

    fun showRewardedAd(
        activity: Activity,
        onUserEarnedReward: (RewardItem) -> Unit,
        onAdClosed: () -> Unit
    ) {
        rewardedAd?.let { ad ->
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    rewardedAd = null
                    preloadRewardedAd(activity)
                    preloadRewardedInterstitialAd(activity)
                    onAdClosed()
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    rewardedAd = null
                    preloadRewardedAd(activity)
                    preloadRewardedInterstitialAd(activity)
                    onAdClosed()
                }
            }

            ad.show(activity) { rewardItem ->
                onUserEarnedReward(rewardItem)
            }
        } ?: run {
            preloadRewardedAd(activity)
            onAdClosed()
        }
    }

    // --- Rewarded Interstitial Ads (Interstellar Reward Ads) ---

    fun preloadRewardedInterstitialAd(context: Context) {
        val primaryUnit = if (BuildConfig.DEBUG) TEST_REWARDED_INTERSTITIAL_AD_UNIT_ID else PROD_REWARDED_INTERSTITIAL_AD_UNIT_ID
        loadRewardedInterstitialInternal(context, primaryUnit, if (BuildConfig.DEBUG) null else TEST_REWARDED_INTERSTITIAL_AD_UNIT_ID)
    }

    private fun loadRewardedInterstitialInternal(context: Context, adUnit: String, fallbackUnit: String? = null) {
        try {
            val adRequest = AdRequest.Builder().build()

            RewardedInterstitialAd.load(
                context,
                adUnit,
                adRequest,
                object : RewardedInterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedInterstitialAd) {
                        rewardedInterstitialAd = ad
                        Log.d(TAG, "Rewarded Interstitial ad preloaded successfully ($adUnit)")
                    }

                    override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                        rewardedInterstitialAd = null
                        Log.w(TAG, "Rewarded Interstitial ad failed to load ($adUnit): ${loadAdError.message}")
                        if (fallbackUnit != null && fallbackUnit != adUnit) {
                            loadRewardedInterstitialInternal(context, fallbackUnit, null)
                        }
                    }
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Preload rewarded interstitial error: ${e.message}")
        }
    }

    fun isRewardedInterstitialAdReady(): Boolean = rewardedInterstitialAd != null

    fun showRewardedInterstitialAd(
        activity: Activity,
        onUserEarnedReward: (RewardItem) -> Unit,
        onAdClosed: () -> Unit
    ) {
        rewardedInterstitialAd?.let { ad ->
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    rewardedInterstitialAd = null
                    preloadRewardedInterstitialAd(activity)
                    preloadRewardedAd(activity)
                    onAdClosed()
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    rewardedInterstitialAd = null
                    preloadRewardedInterstitialAd(activity)
                    preloadRewardedAd(activity)
                    onAdClosed()
                }
            }

            ad.show(activity) { rewardItem ->
                onUserEarnedReward(rewardItem)
            }
        } ?: run {
            preloadRewardedInterstitialAd(activity)
            onAdClosed()
        }
    }

    // --- Unified Reward Video Flow with Instant Loading Dialog ---

    /**
     * Unified reward video presenter that guarantees a reward ad pops up when the user wants to add duration.
     * Prefers Rewarded Interstitial (Interstellar), falls back to Rewarded Ad, or loads on-the-fly with a clean dialog.
     */
    fun showRewardVideoWithProgress(
        activity: Activity,
        onUserEarnedReward: () -> Unit,
        onAdClosed: (() -> Unit)? = null
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        // 1. If Rewarded Interstitial is preloaded and ready, show immediately!
        if (isRewardedInterstitialAdReady()) {
            showRewardedInterstitialAd(
                activity,
                onUserEarnedReward = { onUserEarnedReward() },
                onAdClosed = { onAdClosed?.invoke() }
            )
            return
        }

        // 2. If standard Rewarded ad is preloaded and ready, show immediately!
        if (isRewardedAdReady()) {
            showRewardedAd(
                activity,
                onUserEarnedReward = { onUserEarnedReward() },
                onAdClosed = { onAdClosed?.invoke() }
            )
            return
        }

        // 3. Neither is preloaded yet -> Display loading dialog and load with priority
        val loadingDialog = createLoadingDialog(activity, "Loading reward video...")
        try {
            loadingDialog.show()
        } catch (e: Exception) {
            Log.w(TAG, "Could not display loading dialog: ${e.message}")
        }

        var isHandled = false
        val dismissLoading = {
            activity.runOnUiThread {
                try {
                    if (loadingDialog.isShowing) {
                        loadingDialog.dismiss()
                    }
                } catch (e: Exception) {}
            }
        }

        val handler = Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            if (!isHandled) {
                isHandled = true
                dismissLoading()
                Toast.makeText(activity, "Reward video is loading in background. Please tap again in a moment.", Toast.LENGTH_SHORT).show()
                preloadRewardedInterstitialAd(activity)
                preloadRewardedAd(activity)
            }
        }
        handler.postDelayed(timeoutRunnable, 8000L)

        // Attempt priority load of Rewarded Interstitial first
        val interstitialUnit = if (BuildConfig.DEBUG) TEST_REWARDED_INTERSTITIAL_AD_UNIT_ID else PROD_REWARDED_INTERSTITIAL_AD_UNIT_ID
        val adRequest = AdRequest.Builder().build()

        RewardedInterstitialAd.load(
            activity,
            interstitialUnit,
            adRequest,
            object : RewardedInterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedInterstitialAd) {
                    if (isHandled) return
                    isHandled = true
                    handler.removeCallbacks(timeoutRunnable)
                    dismissLoading()

                    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdDismissedFullScreenContent() {
                            rewardedInterstitialAd = null
                            preloadRewardedInterstitialAd(activity)
                            preloadRewardedAd(activity)
                            onAdClosed?.invoke()
                        }

                        override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                            rewardedInterstitialAd = null
                            preloadRewardedInterstitialAd(activity)
                            preloadRewardedAd(activity)
                            onAdClosed?.invoke()
                        }
                    }

                    ad.show(activity) { rewardItem ->
                        onUserEarnedReward()
                    }
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    Log.w(TAG, "Priority Rewarded Interstitial failed ($interstitialUnit): ${loadAdError.message}, trying regular rewarded...")
                    val rewardedUnit = if (BuildConfig.DEBUG) TEST_REWARDED_AD_UNIT_ID else PROD_REWARDED_AD_UNIT_ID
                    RewardedAd.load(
                        activity,
                        rewardedUnit,
                        adRequest,
                        object : RewardedAdLoadCallback() {
                            override fun onAdLoaded(ad: RewardedAd) {
                                if (isHandled) return
                                isHandled = true
                                handler.removeCallbacks(timeoutRunnable)
                                dismissLoading()

                                ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                                    override fun onAdDismissedFullScreenContent() {
                                        rewardedAd = null
                                        preloadRewardedAd(activity)
                                        preloadRewardedInterstitialAd(activity)
                                        onAdClosed?.invoke()
                                    }

                                    override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                                        rewardedAd = null
                                        preloadRewardedAd(activity)
                                        preloadRewardedInterstitialAd(activity)
                                        onAdClosed?.invoke()
                                    }
                                }

                                ad.show(activity) { rewardItem ->
                                    onUserEarnedReward()
                                }
                            }

                            override fun onAdFailedToLoad(rewardedError: LoadAdError) {
                                if (isHandled) return
                                isHandled = true
                                handler.removeCallbacks(timeoutRunnable)
                                dismissLoading()
                                Toast.makeText(activity, "Unable to load reward video. Please check your internet connection.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        )
    }

    private fun createLoadingDialog(activity: Activity, message: String): Dialog {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val card = MaterialCardView(activity).apply {
            radius = 16f * activity.resources.displayMetrics.density
            setCardBackgroundColor(Color.parseColor("#161C28"))
            strokeColor = Color.parseColor("#2A3346")
            strokeWidth = (1 * activity.resources.displayMetrics.density).toInt()
        }

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = (20 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val progressBar = ProgressBar(activity).apply {
            indeterminateTintList = ColorStateList.valueOf(Color.parseColor("#FF3B30"))
        }

        val textView = TextView(activity).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            val leftPad = (16 * activity.resources.displayMetrics.density).toInt()
            setPadding(leftPad, 0, 0, 0)
        }

        layout.addView(progressBar)
        layout.addView(textView)
        card.addView(layout)
        dialog.setContentView(card)

        return dialog
    }
}
