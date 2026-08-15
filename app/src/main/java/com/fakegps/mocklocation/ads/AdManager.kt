package com.fakegps.mocklocation.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

object AdManager {

    private const val TAG = "AdManager"

    // Official Google Test Ad Unit IDs for safe development & QA
    const val TEST_BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"
    const val TEST_INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"

    private var interstitialAd: InterstitialAd? = null
    private var lastInterstitialShowTime: Long = 0
    private const val INTERSTITIAL_COOLDOWN_MS = 180_000L // 3 minutes cooldown

    fun initialize(context: Context) {
        try {
            MobileAds.initialize(context) { status ->
                Log.d(TAG, "Google Mobile Ads initialized: $status")
            }
            preloadInterstitial(context)
        } catch (e: Exception) {
            Log.w(TAG, "AdMob initialization skipped: ${e.message}")
        }
    }

    fun loadBanner(activity: Activity, container: FrameLayout) {
        try {
            val adView = AdView(activity).apply {
                adUnitId = TEST_BANNER_AD_UNIT_ID
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

    fun showInterstitialIfReady(activity: Activity) {
        val now = System.currentTimeMillis()
        if (now - lastInterstitialShowTime < INTERSTITIAL_COOLDOWN_MS) {
            return // Cooldown active
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
}
