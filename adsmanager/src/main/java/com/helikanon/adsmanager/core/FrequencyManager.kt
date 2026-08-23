package com.helikanon.adsmanager.core

import android.os.SystemClock
import com.helikanon.adsmanager.config.AdsConfig

/**
 * Gösterim sıklığı kuralları: interstitial minimum aralık, oturum limiti,
 * ilk gösterim gecikmesi, app open'ın interstitial/rewarded sonrası minimum bekleme süresi
 * ve iki app open arasındaki minimum aralık.
 */
internal object FrequencyManager {

    private var sessionStartMs: Long = SystemClock.elapsedRealtime()
    private var lastInterstitialShownMs: Long = 0L
    /** Son interstitial VEYA rewarded gösterim zamanı (app open gap kontrolü için). */
    private var lastInterOrRewardedShownMs: Long = 0L
    private var lastAppOpenShownMs: Long = 0L
    private var sessionInterstitialCount: Int = 0

    fun onSessionStart() {
        sessionStartMs = SystemClock.elapsedRealtime()
        lastInterstitialShownMs = 0L
        lastInterOrRewardedShownMs = 0L
        lastAppOpenShownMs = 0L
        sessionInterstitialCount = 0
    }

    sealed class Check {
        object Allowed : Check()
        data class Denied(val reason: String) : Check()
    }

    /**
     * @param ignoreTimeChecks true ise süre bazlı kontroller (min aralık + ilk gecikme) atlanır.
     */
    fun canShowInterstitial(config: AdsConfig, ignoreTimeChecks: Boolean): Check {
        val now = SystemClock.elapsedRealtime()

        if (config.sessionMaxInterstitials > 0 &&
            sessionInterstitialCount >= config.sessionMaxInterstitials
        ) {
            return Check.Denied("session max interstitial limit (${config.sessionMaxInterstitials}) reached")
        }

        if (!ignoreTimeChecks) {
            val sinceStartSec = (now - sessionStartMs) / 1000
            if (sinceStartSec < config.firstInterstitialDelaySec) {
                return Check.Denied("first interstitial delay: ${sinceStartSec}s < ${config.firstInterstitialDelaySec}s")
            }
            if (lastInterstitialShownMs > 0) {
                val sinceLastSec = (now - lastInterstitialShownMs) / 1000
                if (sinceLastSec < config.interstitialMinIntervalSec) {
                    return Check.Denied("interstitial min interval: ${sinceLastSec}s < ${config.interstitialMinIntervalSec}s")
                }
            }
        }
        return Check.Allowed
    }

    fun canShowAppOpen(config: AdsConfig): Check {
        val now = SystemClock.elapsedRealtime()
        if (lastInterOrRewardedShownMs > 0) {
            val sinceSec = (now - lastInterOrRewardedShownMs) / 1000
            if (sinceSec < config.appOpenMinGapSec) {
                return Check.Denied("app open min gap after inter/rewarded: ${sinceSec}s < ${config.appOpenMinGapSec}s")
            }
        }
        if (config.appOpenMinIntervalSec > 0 && lastAppOpenShownMs > 0) {
            val sinceSec = (now - lastAppOpenShownMs) / 1000
            if (sinceSec < config.appOpenMinIntervalSec) {
                return Check.Denied("app open min interval: ${sinceSec}s < ${config.appOpenMinIntervalSec}s")
            }
        }
        return Check.Allowed
    }

    fun recordInterstitialShown() {
        val now = SystemClock.elapsedRealtime()
        lastInterstitialShownMs = now
        lastInterOrRewardedShownMs = now
        sessionInterstitialCount++
    }

    fun recordAppOpenShown() {
        lastAppOpenShownMs = SystemClock.elapsedRealtime()
    }

    fun recordRewardedShown() {
        lastInterOrRewardedShownMs = SystemClock.elapsedRealtime()
    }
}
