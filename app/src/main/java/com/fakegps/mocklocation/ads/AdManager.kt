package com.fakegps.mocklocation.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RatingBar
import android.widget.TextView
import com.fakegps.mocklocation.BuildConfig
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import com.google.android.gms.ads.rewarded.RewardItem
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback
import com.google.android.material.button.MaterialButton

object AdManager {

    private const val TAG = "AdManager"

    // Production AdMob Ad Unit IDs
    const val PROD_BANNER_AD_UNIT_ID = "ca-app-pub-5191202278112313/6778431802"
    const val PROD_HOME_BANNER_AD_UNIT_ID = "ca-app-pub-5191202278112313/8553859547"
    const val PROD_APP_OPEN_AD_UNIT_ID = "ca-app-pub-5191202278112313/3576719243"
    const val PROD_NATIVE_AD_UNIT_ID = "ca-app-pub-5191202278112313/5736124511"
    const val PROD_REWARDED_AD_UNIT_ID = "ca-app-pub-5191202278112313/1933445026"
    const val PROD_REWARDED_INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-5191202278112313/6932394336"

    // Official Google Test Ad Unit IDs for safe debug & QA
    const val TEST_BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"
    const val TEST_INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"
    const val TEST_NATIVE_AD_UNIT_ID = "ca-app-pub-3940256099942544/2247696110"
    const val TEST_REWARDED_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"
    const val TEST_REWARDED_INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-3940256099942544/5354046379"

    private var interstitialAd: InterstitialAd? = null
    private var rewardedAd: RewardedAd? = null
    private var rewardedInterstitialAd: RewardedInterstitialAd? = null
    private var lastInterstitialShowTime: Long = 0
    private const val INTERSTITIAL_COOLDOWN_MS = 180_000L // 3 minutes cooldown

    fun initialize(context: Context) {
        try {
            MobileAds.initialize(context) { status ->
                Log.d(TAG, "Google Mobile Ads initialized: $status")
            }
            preloadInterstitial(context)
            preloadRewardedAd(context)
            preloadRewardedInterstitialAd(context)
        } catch (e: Exception) {
            Log.w(TAG, "AdMob initialization skipped: ${e.message}")
        }
    }

    /**
     * Loads an Adaptive/Standard Banner Ad into the specified container.
     */
    fun loadBanner(activity: Activity, container: FrameLayout, isHomeBanner: Boolean = false) {

        try {
            val adUnit = if (BuildConfig.DEBUG) {
                TEST_BANNER_AD_UNIT_ID
            } else {
                if (isHomeBanner) PROD_HOME_BANNER_AD_UNIT_ID else PROD_BANNER_AD_UNIT_ID
            }

            val adView = AdView(activity).apply {
                adUnitId = adUnit
                setAdSize(AdSize.BANNER)
            }
            container.removeAllViews()
            container.addView(adView)
            val adRequest = AdRequest.Builder().build()
            adView.loadAd(adRequest)
            container.visibility = View.VISIBLE
        } catch (e: Exception) {
            Log.w(TAG, "Could not load banner: ${e.message}")
            container.visibility = View.GONE
        }
    }

    /**
     * Loads a Native Advanced Ad into the specified container.
     */
    fun loadNativeAd(activity: Activity, container: FrameLayout) {

        try {
            val adUnit = if (BuildConfig.DEBUG) TEST_NATIVE_AD_UNIT_ID else PROD_NATIVE_AD_UNIT_ID

            val adLoader = AdLoader.Builder(activity, adUnit)
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
                }
                .withAdListener(object : com.google.android.gms.ads.AdListener() {
                    override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                        Log.w(TAG, "Native ad failed to load: ${loadAdError.message}")
                        container.visibility = View.GONE
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

    fun preloadInterstitial(context: Context) {

        try {
            val adRequest = AdRequest.Builder().build()
            InterstitialAd.load(
                context,
                TEST_INTERSTITIAL_AD_UNIT_ID,
                adRequest,
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        interstitialAd = ad
                        Log.d(TAG, "Interstitial ad preloaded successfully")
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        interstitialAd = null
                        Log.w(TAG, "Interstitial ad failed to load: ${error.message}")
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
            ad.fullScreenContentCallback = object : com.google.android.gms.ads.FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    interstitialAd = null
                    preloadInterstitial(activity)
                    onDismissed()
                }

                override fun onAdFailedToShowFullScreenContent(adError: com.google.android.gms.ads.AdError) {
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
            ad.show(activity)
            interstitialAd = null
            preloadInterstitial(activity)
        } ?: run {
            preloadInterstitial(activity)
        }
    }

    // --- Rewarded Ads (Watch 5 Videos -> 24 Hours Ad-Free Pass) ---

    fun preloadRewardedAd(context: Context) {
        try {
            val adUnit = if (BuildConfig.DEBUG) TEST_REWARDED_AD_UNIT_ID else PROD_REWARDED_AD_UNIT_ID
            val adRequest = AdRequest.Builder().build()

            RewardedAd.load(
                context,
                adUnit,
                adRequest,
                object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedAd) {
                        rewardedAd = ad
                        Log.d(TAG, "Rewarded ad preloaded successfully.")
                    }

                    override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                        rewardedAd = null
                        Log.w(TAG, "Rewarded ad failed to load: ${loadAdError.message}")
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
            ad.fullScreenContentCallback = object : com.google.android.gms.ads.FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    rewardedAd = null
                    preloadRewardedAd(activity)
                    onAdClosed()
                }

                override fun onAdFailedToShowFullScreenContent(adError: com.google.android.gms.ads.AdError) {
                    rewardedAd = null
                    preloadRewardedAd(activity)
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

    // --- Rewarded Interstitial Ads (Rewarded Interstitial Unit: 6932394336) ---

    fun preloadRewardedInterstitialAd(context: Context) {

        try {
            val adUnit = if (BuildConfig.DEBUG) TEST_REWARDED_INTERSTITIAL_AD_UNIT_ID else PROD_REWARDED_INTERSTITIAL_AD_UNIT_ID
            val adRequest = AdRequest.Builder().build()

            RewardedInterstitialAd.load(
                context,
                adUnit,
                adRequest,
                object : RewardedInterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedInterstitialAd) {
                        rewardedInterstitialAd = ad
                        Log.d(TAG, "Rewarded Interstitial ad preloaded successfully.")
                    }

                    override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                        rewardedInterstitialAd = null
                        Log.w(TAG, "Rewarded Interstitial ad failed to load: ${loadAdError.message}")
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
            ad.fullScreenContentCallback = object : com.google.android.gms.ads.FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    rewardedInterstitialAd = null
                    preloadRewardedInterstitialAd(activity)
                    onAdClosed()
                }

                override fun onAdFailedToShowFullScreenContent(adError: com.google.android.gms.ads.AdError) {
                    rewardedInterstitialAd = null
                    preloadRewardedInterstitialAd(activity)
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
}
