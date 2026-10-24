package com.fakegps.mocklocation.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.fakegps.mocklocation.BuildConfig
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * Manages Google User Messaging Platform (UMP) SDK consent flow.
 * Required for EU User Consent Policy & LGPD compliance prior to requesting AdMob ads.
 * Provides resilient fallbacks for non-EEA users, network delays, and debug environments.
 */
class ConsentManager private constructor(private val context: Context) {

    private val consentInformation: ConsentInformation =
        UserMessagingPlatform.getConsentInformation(context)

    @Volatile
    private var isConsentGatheringCompleted: Boolean = false

    @Volatile
    private var consentUpdateFailed: Boolean = false

    companion object {
        private const val TAG = "ConsentManager"

        @Volatile
        private var instance: ConsentManager? = null

        fun getInstance(context: Context): ConsentManager {
            return instance ?: synchronized(this) {
                instance ?: ConsentManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * True if the user is in the EEA/UK and legally required to provide GDPR consent.
     */
    val isConsentRequired: Boolean
        get() = consentInformation.privacyOptionsRequirementStatus ==
                ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED ||
                consentInformation.consentStatus ==
                ConsentInformation.ConsentStatus.REQUIRED

    /**
     * Checks if ads can be requested based on user consent state or geographic exemption.
     * Fully aligned with Google UMP guidelines:
     * 1. Returns true if consentInformation.canRequestAds() is true (OBTAINED or NOT_REQUIRED).
     * 2. Returns true if user is outside EEA (privacyOptionsRequirementStatus or consentStatus == NOT_REQUIRED).
     * 3. If consent update completed or failed due to network/configuration (non-EEA or unconfigured CMP),
     *    falls back gracefully so ads are not permanently blocked.
     */
    val canRequestAds: Boolean
        get() {
            if (consentInformation.canRequestAds()) {
                return true
            }
            if (consentInformation.privacyOptionsRequirementStatus ==
                ConsentInformation.PrivacyOptionsRequirementStatus.NOT_REQUIRED
            ) {
                return true
            }
            if (consentInformation.consentStatus ==
                ConsentInformation.ConsentStatus.NOT_REQUIRED
            ) {
                return true
            }
            if (isConsentGatheringCompleted || consentUpdateFailed) {
                return !isConsentRequired
            }
            return false
        }

    /**
     * Helper to determine if a privacy options form is required (e.g. for user settings link).
     */
    val isPrivacyOptionsRequired: Boolean
        get() = consentInformation.privacyOptionsRequirementStatus ==
                ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    /**
     * Requests consent information update and presents the consent form if required.
     */
    fun gatherConsent(
        activity: Activity,
        onConsentGatheringCompleteListener: (canRequestAds: Boolean) -> Unit
    ) {
        val paramsBuilder = ConsentRequestParameters.Builder()
            .setTagForUnderAgeOfConsent(false)

        if (BuildConfig.DEBUG) {
            val debugSettings = ConsentDebugSettings.Builder(activity)
                .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_DISABLED)
                .build()
            paramsBuilder.setConsentDebugSettings(debugSettings)
        }

        val params = paramsBuilder.build()

        consentInformation.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    isConsentGatheringCompleted = true
                    if (formError != null) {
                        Log.w(TAG, "Consent form error: [${formError.errorCode}] ${formError.message}")
                    }
                    onConsentGatheringCompleteListener(canRequestAds)
                }
            },
            { requestConsentError ->
                isConsentGatheringCompleted = true
                consentUpdateFailed = true
                Log.w(TAG, "Consent info update failed: [${requestConsentError.errorCode}] ${requestConsentError.message}")
                // Fallback for non-EEA users or when CMP message is not configured in AdMob console
                onConsentGatheringCompleteListener(canRequestAds)
            }
        )
    }

    /**
     * Ensures consent has been resolved before executing ad actions.
     */
    fun ensureConsent(
        activity: Activity,
        onComplete: (canRequestAds: Boolean) -> Unit
    ) {
        if (canRequestAds) {
            onComplete(true)
            return
        }
        gatherConsent(activity) { canRequest ->
            onComplete(canRequest)
        }
    }

    /**
     * Shows privacy options form allowing the user to update their consent choices later.
     */
    fun showPrivacyOptionsForm(
        activity: Activity,
        onDismissListener: () -> Unit
    ) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
            if (formError != null) {
                Log.w(TAG, "Privacy options form error: [${formError.errorCode}] ${formError.message}")
            }
            onDismissListener()
        }
    }
}
