package com.fakegps.mocklocation.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * Manages Google User Messaging Platform (UMP) SDK consent flow.
 * Required for EU User Consent Policy & LGPD compliance prior to requesting AdMob ads.
 */
class ConsentManager private constructor(private val context: Context) {

    private val consentInformation: ConsentInformation =
        UserMessagingPlatform.getConsentInformation(context)

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
     * Checks if ads can be requested based on user consent state or non-EEA geographic exemption.
     */
    val canRequestAds: Boolean
        get() = consentInformation.canRequestAds()

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
        onConsentGatheringCompleteListener: (error: Exception?) -> Unit
    ) {
        val params = ConsentRequestParameters.Builder()
            .setTagForUnderAgeOfConsent(false)
            .build()

        consentInformation.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    if (formError != null) {
                        Log.w(TAG, "Consent form error: [${formError.errorCode}] ${formError.message}")
                    }
                    onConsentGatheringCompleteListener(if (formError != null) Exception(formError.message) else null)
                }
            },
            { requestConsentError ->
                Log.w(TAG, "Consent info update failed: [${requestConsentError.errorCode}] ${requestConsentError.message}")
                onConsentGatheringCompleteListener(Exception(requestConsentError.message))
            }
        )
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
