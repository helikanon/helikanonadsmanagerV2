package com.helikanon.adsmanager.consent

import android.app.Activity
import android.content.Context
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.helikanon.adsmanager.core.AdsLogger

/**
 * Google UMP (User Messaging Platform) ile GDPR consent akışını yönetir.
 * AppLovin MAX, UMP'nin yazdığı TCF string'ini otomatik okur; ayrıca bir aktarım gerekmez.
 */
internal object ConsentManager {

    private fun consentInfo(context: Context): ConsentInformation =
        UserMessagingPlatform.getConsentInformation(context)

    fun canRequestAds(context: Context): Boolean = try {
        consentInfo(context).canRequestAds()
    } catch (_: Exception) {
        false
    }

    /**
     * Consent bilgisini günceller ve gerekiyorsa formu gösterir.
     * @param onComplete canRequestAds değeri ile çağrılır (form hatasında da çağrılır).
     */
    fun gatherConsent(
        activity: Activity,
        testMode: Boolean,
        testDeviceHashedIds: List<String>,
        onComplete: (Boolean) -> Unit
    ) {
        val paramsBuilder = ConsentRequestParameters.Builder()
        if (testMode && testDeviceHashedIds.isNotEmpty()) {
            val debugSettings = ConsentDebugSettings.Builder(activity).apply {
                testDeviceHashedIds.forEach { addTestDeviceHashedId(it) }
                setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
            }.build()
            paramsBuilder.setConsentDebugSettings(debugSettings)
        } else if (testMode) {
            AdsLogger.w(
                "testMode=true but no test device hashed ids (admobTestDeviceIds) given; " +
                    "UMP debug geography (EEA) is NOT applied. Outside the EEA the consent form " +
                    "will never appear and canRequestAds() returns true. Copy the id from the UMP " +
                    "log line: addTestDeviceHashedId(\"...\")"
            )
        }

        val info = consentInfo(activity)
        info.requestConsentInfoUpdate(
            activity,
            paramsBuilder.build(),
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    if (formError != null) {
                        AdsLogger.w("UMP consent form error: ${formError.errorCode} ${formError.message}")
                    }
                    onComplete(info.canRequestAds())
                }
            },
            { requestError ->
                AdsLogger.w("UMP consent info update failed: ${requestError.errorCode} ${requestError.message}")
                // Form gösterilemese de önceki oturumdan geçerli consent olabilir
                onComplete(info.canRequestAds())
            }
        )
    }

    fun isPrivacyOptionsRequired(context: Context): Boolean = try {
        consentInfo(context).privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    } catch (_: Exception) {
        false
    }

    /** Ayarlar ekranından "Gizlilik seçenekleri" formunu açar. */
    fun showPrivacyOptionsForm(activity: Activity, onDismissed: (() -> Unit)? = null) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
            if (formError != null) {
                AdsLogger.w("UMP privacy options form error: ${formError.errorCode} ${formError.message}")
            }
            onDismissed?.invoke()
        }
    }

    /** Sadece test için: consent durumunu sıfırlar. */
    fun reset(context: Context) {
        try {
            consentInfo(context).reset()
        } catch (e: Exception) {
            AdsLogger.w("UMP reset failed: ${e.message}")
        }
    }
}
