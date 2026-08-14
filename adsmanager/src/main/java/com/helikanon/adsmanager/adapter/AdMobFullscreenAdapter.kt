package com.helikanon.adsmanager.adapter

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdValue
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewarded.ServerSideVerificationOptions
import com.helikanon.adsmanager.config.AdFormat
import com.helikanon.adsmanager.config.AdNetwork
import com.helikanon.adsmanager.core.EventCenter
import com.helikanon.adsmanager.core.FullscreenLoadCallback
import com.helikanon.adsmanager.core.LoadedFullscreenAd
import com.helikanon.adsmanager.core.SsvStore
import com.helikanon.adsmanager.listener.AdRevenue
import com.helikanon.adsmanager.listener.RewardItem

/** AdMob (legacy GMA SDK) interstitial / rewarded / app open adapteri. */
internal object AdMobFullscreenAdapter {

    fun load(
        context: Context,
        format: AdFormat,
        placementId: String,
        adUnitId: String,
        ttlMs: Long,
        callback: FullscreenLoadCallback
    ) {
        val request = AdRequest.Builder().build()
        when (format) {
            AdFormat.INTERSTITIAL -> InterstitialAd.load(
                context, adUnitId, request,
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        callback.onLoaded(LoadedAdMobInterstitial(ad, placementId, adUnitId, ttlMs))
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        callback.onFailed(error.message)
                    }
                }
            )

            AdFormat.REWARDED -> RewardedAd.load(
                context, adUnitId, request,
                object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedAd) {
                        applySsv(ad)
                        callback.onLoaded(LoadedAdMobRewarded(ad, placementId, adUnitId, ttlMs))
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        callback.onFailed(error.message)
                    }
                }
            )

            AdFormat.APP_OPEN -> AppOpenAd.load(
                context, adUnitId, request,
                object : AppOpenAd.AppOpenAdLoadCallback() {
                    override fun onAdLoaded(ad: AppOpenAd) {
                        callback.onLoaded(LoadedAdMobAppOpen(ad, placementId, adUnitId, ttlMs))
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        callback.onFailed(error.message)
                    }
                }
            )

            AdFormat.BANNER -> callback.onFailed("Banner is not a fullscreen format")
        }
    }

    private fun applySsv(ad: RewardedAd) {
        val userId = SsvStore.userId
        val customData = SsvStore.customData
        if (userId != null || customData != null) {
            ad.setServerSideVerificationOptions(
                ServerSideVerificationOptions.Builder().apply {
                    userId?.let { setUserId(it) }
                    customData?.let { setCustomData(it) }
                }.build()
            )
        }
    }

    internal fun precisionName(precisionType: Int): String = when (precisionType) {
        AdValue.PrecisionType.PRECISE -> "PRECISE"
        AdValue.PrecisionType.ESTIMATED -> "ESTIMATED"
        AdValue.PrecisionType.PUBLISHER_PROVIDED -> "PUBLISHER_PROVIDED"
        else -> "UNKNOWN"
    }

    internal fun emitRevenue(
        format: AdFormat, placementId: String, adUnitId: String, adValue: AdValue
    ) {
        EventCenter.revenue(
            AdRevenue(
                format = format,
                placementId = placementId,
                network = AdNetwork.ADMOB,
                adUnitId = adUnitId,
                revenue = adValue.valueMicros / 1_000_000.0,
                currency = adValue.currencyCode,
                precision = precisionName(adValue.precisionType),
                mediatedNetworkName = "admob"
            )
        )
    }

    private fun fullScreenCallback(
        listener: LoadedFullscreenAd.ShowListener,
        onDone: () -> Unit
    ) = object : FullScreenContentCallback() {
        override fun onAdShowedFullScreenContent() = listener.onShown("admob")
        override fun onAdFailedToShowFullScreenContent(error: AdError) {
            listener.onShowFailed(error.message)
            onDone()
        }

        override fun onAdDismissedFullScreenContent() {
            listener.onDismissed()
            onDone()
        }

        override fun onAdClicked() = listener.onClicked()
    }

    // ------------------------------------------------------------- wrappers

    private class LoadedAdMobInterstitial(
        private var ad: InterstitialAd?,
        placementId: String,
        adUnitId: String,
        ttlMs: Long
    ) : LoadedFullscreenAd(AdNetwork.ADMOB, AdFormat.INTERSTITIAL, placementId, adUnitId, ttlMs) {

        override val isReady: Boolean get() = ad != null

        override fun show(activity: Activity, listener: ShowListener) {
            val current = ad ?: run { listener.onShowFailed("Ad already consumed"); return }
            ad = null
            current.setOnPaidEventListener { v -> emitRevenue(format, placementId, adUnitId, v) }
            current.fullScreenContentCallback = fullScreenCallback(listener) {}
            current.show(activity)
        }

        override fun destroy() {
            ad = null
        }
    }

    private class LoadedAdMobRewarded(
        private var ad: RewardedAd?,
        placementId: String,
        adUnitId: String,
        ttlMs: Long
    ) : LoadedFullscreenAd(AdNetwork.ADMOB, AdFormat.REWARDED, placementId, adUnitId, ttlMs) {

        override val isReady: Boolean get() = ad != null

        override fun show(activity: Activity, listener: ShowListener) {
            val current = ad ?: run { listener.onShowFailed("Ad already consumed"); return }
            ad = null
            current.setOnPaidEventListener { v -> emitRevenue(format, placementId, adUnitId, v) }
            current.fullScreenContentCallback = fullScreenCallback(listener) {}
            current.show(activity) { reward ->
                listener.onRewardEarned(RewardItem(reward.amount, reward.type))
            }
        }

        override fun destroy() {
            ad = null
        }
    }

    private class LoadedAdMobAppOpen(
        private var ad: AppOpenAd?,
        placementId: String,
        adUnitId: String,
        ttlMs: Long
    ) : LoadedFullscreenAd(AdNetwork.ADMOB, AdFormat.APP_OPEN, placementId, adUnitId, ttlMs) {

        override val isReady: Boolean get() = ad != null

        override fun show(activity: Activity, listener: ShowListener) {
            val current = ad ?: run { listener.onShowFailed("Ad already consumed"); return }
            ad = null
            current.setOnPaidEventListener { v -> emitRevenue(format, placementId, adUnitId, v) }
            current.fullScreenContentCallback = fullScreenCallback(listener) {}
            current.show(activity)
        }

        override fun destroy() {
            ad = null
        }
    }
}
