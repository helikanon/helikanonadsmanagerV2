package com.helikanon.adsmanager.admob

import android.content.Context
import com.applovin.sdk.AppLovinPrivacySettings
import com.bytedance.sdk.openadsdk.api.PAGConstant
import com.facebook.ads.AdSettings
import com.fyber.inneractive.sdk.external.InneractiveAdManager
import com.google.ads.mediation.pangle.PangleMediationAdapter
import com.helikanon.adsmanager.mediation.MediationPrivacy
import com.mbridge.msdk.out.MBridgeSDKFactory
import com.unity3d.ads.metadata.MetaData
import com.vungle.ads.VunglePrivacySettings

/**
 * `doNotSell` tercihini 7 mediation ağına kendi API'leriyle iletir. Çağrılar ve zamanlamaları
 * Google'ın ağ bazlı AdMob mediation entegrasyon sayfalarındaki gibidir.
 */
class AdMobMediationPrivacy : MediationPrivacy {

    override fun beforeInit(context: Context, doNotSell: Boolean, log: (String) -> Unit) {
        forward("AppLovin", log) { AppLovinPrivacySettings.setDoNotSell(doNotSell, context) }
        forward("Pangle", log) {
            PangleMediationAdapter.setPAConsent(
                if (doNotSell) PAGConstant.PAGPAConsentType.PAG_PA_CONSENT_TYPE_NO_CONSENT
                else PAGConstant.PAGPAConsentType.PAG_PA_CONSENT_TYPE_CONSENT
            )
        }
        // IAB US Privacy dizesi: sürüm 1, bildirim verildi, opt-out evet/hayır, LSPA kapsamı dışı
        forward("DT Exchange", log) { InneractiveAdManager.setUSPrivacyString(if (doNotSell) "1YYN" else "1YNN") }
        // Liftoff'ta true, kullanıcının veri satışına izin verdiği anlamına gelir
        forward("Liftoff", log) { VunglePrivacySettings.setCCPAStatus(!doNotSell) }
        // Limited Data Use; ülke/eyalet 0,0 verilince konumu Meta kendisi belirler
        forward("Meta", log) {
            if (doNotSell) AdSettings.setDataProcessingOptions(arrayOf("LDU"), 0, 0)
            else AdSettings.setDataProcessingOptions(arrayOf())
        }
        forward("Unity", log) {
            MetaData(context).apply {
                set("privacy.consent", !doNotSell)
                commit()
            }
        }
    }

    override fun afterInit(context: Context, doNotSell: Boolean, log: (String) -> Unit) {
        // Google'ın Mintegral sayfası: GMA init'inden sonra, reklam isteğinden önce verilir
        forward("Mintegral", log) { MBridgeSDKFactory.getMBridgeSDK().setDoNotTrackStatus(context, doNotSell) }
    }

    // Bir ağ uygulamadan çıkarılmışsa (exclude) NoClassDefFoundError gelir; diğer ağlar etkilenmesin
    private inline fun forward(network: String, log: (String) -> Unit, call: () -> Unit) {
        try {
            call()
            log("doNotSell forwarded to $network")
        } catch (t: Throwable) {
            log("doNotSell could not be forwarded to $network: $t")
        }
    }
}
