package com.fakegps.mocklocation.ui.dialogs

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.ads.AdManager
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.databinding.LayoutDialogSessionExtendBinding
import com.fakegps.mocklocation.service.SessionTimerManager
import com.fakegps.mocklocation.ui.SettingsActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class SessionExtendDialog(
    private val activity: Activity,
    private val isExpiredPrompt: Boolean = false,
    private val onReconnectRequested: (() -> Unit)? = null
) : Dialog(activity) {

    private lateinit var binding: LayoutDialogSessionExtendBinding
    private val dialogScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = LayoutDialogSessionExtendBinding.inflate(LayoutInflater.from(context))
        setContentView(binding.root)

        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.92).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        setCancelable(true)

        setupUI()
        setupListeners()
        observeTimer()
    }

    private fun setupUI() {
        val settingsPrefs = AppSettingsPreferences(context)
        val sessionPrefs = SessionPreferences(context)

        if (isExpiredPrompt || sessionPrefs.isSessionExpired) {
            binding.layoutExtendStatusBadge.backgroundTintList = ContextCompat.getColorStateList(context, R.color.badge_error_bg)
            binding.viewExtendDot.backgroundTintList = ContextCompat.getColorStateList(context, R.color.badge_error_text)
            binding.tvExtendBadgeText.text = "SESSION EXPIRED"
            binding.tvExtendBadgeText.setTextColor(ContextCompat.getColor(context, R.color.badge_error_text))

            binding.tvExtendTitle.text = "Simulation Time Expired"
            binding.tvExtendSubtitle.text = "Watch a short video to add +1 Hour, or reconnect for +20 mins free."
            binding.btnReconnectFallback.visibility = View.VISIBLE
        } else {
            binding.btnReconnectFallback.visibility = View.GONE
        }

        val watchedCount = settingsPrefs.watchedRewardAdsCount
        if (settingsPrefs.isAdFreeActive) {
            binding.tvPromo24hText.text = "24-Hour Pass Active (${settingsPrefs.getAdFreeRemainingTimeText()})"
        } else {
            binding.tvPromo24hText.text = "Unlock 24h Unlimited Pass ($watchedCount / 20 Ads)"
        }
    }

    private fun setupListeners() {
        binding.btnExtendDialogClose.setOnClickListener {
            dismiss()
        }

        binding.btnExtendOneHour.setOnClickListener {
            handleWatchAdToExtend()
        }

        binding.btnReconnectFallback.setOnClickListener {
            handleReconnectFallback()
        }

        binding.layoutPromo24hPass.setOnClickListener {
            val settingsPrefs = AppSettingsPreferences(context)
            if (settingsPrefs.isAdFreeActive) {
                val intent = Intent(activity, SettingsActivity::class.java)
                activity.startActivity(intent)
                dismiss()
            } else {
                handleWatchAdFor24hPass()
            }
        }
    }

    private fun handleWatchAdToExtend() {
        if (AdManager.isRewardedInterstitialAdReady()) {
            AdManager.showRewardedInterstitialAd(
                activity,
                onUserEarnedReward = {
                    SessionTimerManager.extendSession(context, SessionPreferences.REWARD_EXTENSION_DURATION_MILLIS)
                    Toast.makeText(context, "✅ +1 Hour Added! Simulation time extended.", Toast.LENGTH_SHORT).show()
                    dismiss()
                },
                onAdClosed = {
                    AdManager.preloadRewardedInterstitialAd(activity)
                }
            )
        } else if (AdManager.isRewardedAdReady()) {
            AdManager.showRewardedAd(
                activity,
                onUserEarnedReward = {
                    SessionTimerManager.extendSession(context, SessionPreferences.REWARD_EXTENSION_DURATION_MILLIS)
                    Toast.makeText(context, "✅ +1 Hour Added! Simulation time extended.", Toast.LENGTH_SHORT).show()
                    dismiss()
                },
                onAdClosed = {
                    AdManager.preloadRewardedAd(activity)
                }
            )
        } else {
            AdManager.preloadRewardedInterstitialAd(activity)
            AdManager.preloadRewardedAd(activity)
            Toast.makeText(context, "Video ad is loading. Please tap again in a moment.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleWatchAdFor24hPass() {
        if (AdManager.isRewardedInterstitialAdReady()) {
            AdManager.showRewardedInterstitialAd(
                activity,
                onUserEarnedReward = {
                    val settingsPrefs = AppSettingsPreferences(context)
                    val (newCount, unlocked) = settingsPrefs.record24hPassAdWatched()
                    if (unlocked) {
                        SessionTimerManager.startTimer(context, SessionPreferences.UNLIMITED_24H_DURATION_MILLIS)
                        Toast.makeText(context, "🎉 24-Hour Pass Unlocked! 24h unlimited simulation & ad-free active.", Toast.LENGTH_LONG).show()
                        dismiss()
                    } else {
                        Toast.makeText(context, "🎯 24-Hour Pass Progress: $newCount / 20 videos completed!", Toast.LENGTH_SHORT).show()
                        setupUI()
                    }
                },
                onAdClosed = {
                    AdManager.preloadRewardedInterstitialAd(activity)
                }
            )
        } else if (AdManager.isRewardedAdReady()) {
            AdManager.showRewardedAd(
                activity,
                onUserEarnedReward = {
                    val settingsPrefs = AppSettingsPreferences(context)
                    val (newCount, unlocked) = settingsPrefs.record24hPassAdWatched()
                    if (unlocked) {
                        SessionTimerManager.startTimer(context, SessionPreferences.UNLIMITED_24H_DURATION_MILLIS)
                        Toast.makeText(context, "🎉 24-Hour Pass Unlocked! 24h unlimited simulation & ad-free active.", Toast.LENGTH_LONG).show()
                        dismiss()
                    } else {
                        Toast.makeText(context, "🎯 24-Hour Pass Progress: $newCount / 20 videos completed!", Toast.LENGTH_SHORT).show()
                        setupUI()
                    }
                },
                onAdClosed = {
                    AdManager.preloadRewardedAd(activity)
                }
            )
        } else {
            AdManager.preloadRewardedInterstitialAd(activity)
            AdManager.preloadRewardedAd(activity)
            Toast.makeText(context, "Video ad is loading. Please tap again in a moment.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleReconnectFallback() {
        if (AdManager.isInterstitialAdReady()) {
            AdManager.showInterstitialAd(activity) {
                reconnectWith20Mins()
            }
        } else {
            reconnectWith20Mins()
        }
    }

    private fun reconnectWith20Mins() {
        SessionTimerManager.startTimer(context, SessionPreferences.RECONNECT_FALLBACK_DURATION_MILLIS)
        Toast.makeText(context, "Reconnected! +20 Minutes added for free.", Toast.LENGTH_SHORT).show()
        onReconnectRequested?.invoke()
        dismiss()
    }

    private fun observeTimer() {
        dialogScope.launch {
            SessionTimerManager.timerState.collectLatest { state ->
                binding.tvDialogTimeRemaining.text = state.formattedRemaining
                binding.tvDialogTotalAllocated.text = "Total: ${state.formattedTotal}"
                binding.tvDialogProgressPercent.text = "${state.progressPercent}%"
                binding.progressDialogSession.progress = state.progressPercent

                if (state.isExpired) {
                    binding.layoutExtendStatusBadge.backgroundTintList = ContextCompat.getColorStateList(context, R.color.badge_error_bg)
                    binding.viewExtendDot.backgroundTintList = ContextCompat.getColorStateList(context, R.color.badge_error_text)
                    binding.tvExtendBadgeText.text = "SESSION EXPIRED"
                    binding.tvExtendBadgeText.setTextColor(ContextCompat.getColor(context, R.color.badge_error_text))
                    binding.btnReconnectFallback.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        dialogScope.cancel()
        super.onDetachedFromWindow()
    }
}
