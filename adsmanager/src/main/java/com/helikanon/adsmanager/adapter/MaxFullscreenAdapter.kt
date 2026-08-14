package com.helikanon.adsmanager.adapter

import android.app.Activity
import android.content.Context
import com.applovin.mediation.MaxAd
import com.applovin.mediation.MaxAdListener
import com.applovin.mediation.MaxError
import com.applovin.mediation.MaxReward
import com.applovin.mediation.MaxRewardedAdListener
import com.applovin.mediation.ads.MaxAppOpenAd
import com.applovin.mediation.ads.MaxInterstitialAd
import com.applovin.mediation.ads.MaxRewardedAd
import com.helikanon.adsmanager.config.AdFormat
import com.helikanon.adsmanager.config.AdNetwork
import com.helikanon.adsmanager.core.EventCenter
import com.helikanon.adsmanager.core.FullscreenLoadCallback
import com.helikanon.adsmanager.core.LoadedFullscreenAd
import com.helikanon.adsmanager.listener.AdRevenue
import com.helikanon.adsmanager.listener.RewardItem

/** AppLovin MAX interstitial / rewarded / app open adapteri. */
internal object MaxFullscreenAdapter {

    fun load(
        context: Context,
        format: AdFormat,
        placementId: String,
        adUnitId: String,
        ttlMs: Long,
        callback: FullscreenLoadCallback
    ) {
        when (format) {
            AdFormat.INTERSTITIAL -> loadInterstitial(context, placementId, adUnitId, ttlMs, callback)
            AdFormat.REWARDED -> loadRewarded(context, placementId, adUnitId, ttlMs, callback)
            AdFormat.APP_OPEN -> loadAppOpen(context, placementId, adUnitId, ttlMs, callback)
            AdFormat.BANNER -> callback.onFailed("Banner is not a fullscreen format")
        }
    }

    internal fun emitRevenue(format: AdFormat, placementId: String, adUnitId: String, maxAd: MaxAd) {
        EventCenter.revenue(
            AdRevenue(
                format = format,
                placementId = placementId,
                network = AdNetwork.APPLOVIN,
                adUnitId = adUnitId,
                revenue = maxAd.revenue,
                currency = "USD",
                precision = maxAd.revenuePrecision?.uppercase() ?: "UNKNOWN",
                mediatedNetworkName = maxAd.networkName
            )
        )
    }

    // ----------------------------------------------------------- interstitial

    private fun loadInterstitial(
        context: Context,
        placementId: String,
        adUnitId: String,
        ttlMs: Long,
        callback: FullscreenLoadCallback
    ) {
        // MAX 13.x: reklam objeleri yalnızca ad unit id ile oluşturulur (SDK init sonrası).
        val interstitial = MaxInterstitialAd(adUnitId)
        val wrapper = LoadedMaxInterstitial(interstitial, placementId, adUnitId, ttlMs)

        interstitial.setRevenueListener { maxAd ->
            emitRevenue(AdFormat.INTERSTITIAL, placementId, adUnitId, maxAd)
        }
        interstitial.setListener(object : MaxAdListener {
            override fun onAdLoaded(ad: MaxAd) {
                callback.onLoaded(wrapper)
            }

            override fun onAdLoadFailed(adUnitId: String, error: MaxError) {
                interstitial.destroy()
                callback.onFailed(error.message ?: "MAX load error ${error.code}")
            }

            override fun onAdDisplayed(ad: MaxAd) {
                wrapper.showListener?.onShown(ad.networkName)
            }

            override fun onAdDisplayFailed(ad: MaxAd, error: MaxError) {
                wrapper.showListener?.onShowFailed(error.message ?: "MAX display error ${error.code}")
                interstitial.destroy()
            }

            override fun onAdHidden(ad: MaxAd) {
                wrapper.showListener?.onDismissed()
                interstitial.destroy()
            }

            override fun onAdClicked(ad: MaxAd) {
                wrapper.showListener?.onClicked()
            }
        })
        interstitial.loadAd()
    }

    private class LoadedMaxInterstitial(
        private val ad: MaxInterstitialAd,
        placementId: String,
        adUnitId: String,
        ttlMs: Long
    ) : LoadedFullscreenAd(AdNetwork.APPLOVIN, AdFormat.INTERSTITIAL, placementId, adUnitId, ttlMs) {

        var showListener: ShowListener? = null

        override val isReady: Boolean get() = ad.isReady

        override fun show(activity: Activity, listener: ShowListener) {
            if (!ad.isReady) {
                listener.onShowFailed("MAX interstitial not ready")
                return
            }
            showListener = listener
            ad.showAd(activity)
        }

        override fun destroy() {
            showListener = null
            ad.destroy()
        }
    }

    // -------------------------------------------------------------- rewarded

    private fun loadRewarded(
        context: Context,
        placementId: String,
        adUnitId: String,
        ttlMs: Long,
        callback: FullscreenLoadCallback
    ) {
        // MaxRewardedAd ad unit başına singleton'dır. Show olayları, o an gösterimde olan
        // wrapper'a haritadan yönlendirilir; böylece aynı ad unit için sonradan yapılan bir
        // load, gösterimdeki reklamın dismiss callback'ini kaybettirmez.
        val rewarded = MaxRewardedAd.getInstance(adUnitId)
        val wrapper = LoadedMaxRewarded(rewarded, placementId, adUnitId, ttlMs)

        rewarded.setRevenueListener { maxAd ->
            emitRevenue(AdFormat.REWARDED, placementId, adUnitId, maxAd)
        }
        rewarded.setListener(object : MaxRewardedAdListener {
            private fun active(): LoadedMaxRewarded? = showingRewarded[adUnitId]

            override fun onAdLoaded(ad: MaxAd) {
                callback.onLoaded(wrapper)
            }

            override fun onAdLoadFailed(adUnitId: String, error: MaxError) {
                callback.onFailed(error.message ?: "MAX load error ${error.code}")
            }

            override fun onAdDisplayed(ad: MaxAd) {
                active()?.showListener?.onShown(ad.networkName)
            }

            override fun onAdDisplayFailed(ad: MaxAd, error: MaxError) {
                active()?.showListener?.onShowFailed(error.message ?: "MAX display error ${error.code}")
                showingRewarded.remove(adUnitId)
            }

            override fun onAdHidden(ad: MaxAd) {
                active()?.showListener?.onDismissed()
                showingRewarded.remove(adUnitId)
            }

            override fun onAdClicked(ad: MaxAd) {
                active()?.showListener?.onClicked()
            }

            override fun onUserRewarded(ad: MaxAd, reward: MaxReward) {
                active()?.showListener?.onRewardEarned(
                    RewardItem(reward.amount, reward.label ?: "")
                )
            }
        })
        rewarded.loadAd()
    }

    /** Ad unit id -> şu an gösterimde olan rewarded wrapper. */
    private val showingRewarded = mutableMapOf<String, LoadedMaxRewarded>()

    private class LoadedMaxRewarded(
        private val ad: MaxRewardedAd,
        placementId: String,
        adUnitId: String,
        ttlMs: Long
    ) : LoadedFullscreenAd(AdNetwork.APPLOVIN, AdFormat.REWARDED, placementId, adUnitId, ttlMs) {

        var showListener: ShowListener? = null

        override val isReady: Boolean get() = ad.isReady

        override fun show(activity: Activity, listener: ShowListener) {
            if (!ad.isReady) {
                listener.onShowFailed("MAX rewarded not ready")
                return
            }
            showListener = listener
            showingRewarded[adUnitId] = this
            ad.showAd(activity)
        }

        override fun destroy() {
            // MaxRewardedAd singleton'dır; destroy edilmez, sadece listener bırakılır
            showListener = null
        }
    }

    // -------------------------------------------------------------- app open

    private fun loadAppOpen(
        context: Context,
        placementId: String,
        adUnitId: String,
        ttlMs: Long,
        callback: FullscreenLoadCallback
    ) {
        val appOpen = MaxAppOpenAd(adUnitId)
        val wrapper = LoadedMaxAppOpen(appOpen, placementId, adUnitId, ttlMs)

        appOpen.setRevenueListener { maxAd ->
            emitRevenue(AdFormat.APP_OPEN, placementId, adUnitId, maxAd)
        }
        appOpen.setListener(object : MaxAdListener {
            override fun onAdLoaded(ad: MaxAd) {
                callback.onLoaded(wrapper)
            }

            override fun onAdLoadFailed(adUnitId: String, error: MaxError) {
                appOpen.destroy()
                callback.onFailed(error.message ?: "MAX load error ${error.code}")
            }

            override fun onAdDisplayed(ad: MaxAd) {
                wrapper.showListener?.onShown(ad.networkName)
            }

            override fun onAdDisplayFailed(ad: MaxAd, error: MaxError) {
                wrapper.showListener?.onShowFailed(error.message ?: "MAX display error ${error.code}")
                appOpen.destroy()
            }

            override fun onAdHidden(ad: MaxAd) {
                wrapper.showListener?.onDismissed()
                appOpen.destroy()
            }

            override fun onAdClicked(ad: MaxAd) {
                wrapper.showListener?.onClicked()
            }
        })
        appOpen.loadAd()
    }

    private class LoadedMaxAppOpen(
        private val ad: MaxAppOpenAd,
        placementId: String,
        adUnitId: String,
        ttlMs: Long
    ) : LoadedFullscreenAd(AdNetwork.APPLOVIN, AdFormat.APP_OPEN, placementId, adUnitId, ttlMs) {

        var showListener: ShowListener? = null

        override val isReady: Boolean get() = ad.isReady

        override fun show(activity: Activity, listener: ShowListener) {
            if (!ad.isReady) {
                listener.onShowFailed("MAX app open not ready")
                return
            }
            showListener = listener
            ad.showAd()
        }

        override fun destroy() {
            showListener = null
            ad.destroy()
        }
    }
}
