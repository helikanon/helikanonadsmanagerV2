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
import com.helikanon.adsmanager.core.AdsLogger
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
        // MaxRewardedAd ad unit başına singleton'dır ve setListener önceki dinleyiciyi
        // değiştirir. Aynı ad unit gösterimdeyken yeni bir load başlatmak hem AppLovin'in
        // önerdiği akışa aykırıdır (sonraki yükleme onAdHidden'da yapılmalı) hem de eski
        // gösterimin onAdHidden/onUserRewarded olaylarını yeni wrapper'a karıştırabilir.
        // Bu yüzden gösterim sürerken gelen load, gösterim bitene kadar ertelenir.
        if (showingRewarded.containsKey(adUnitId)) {
            AdsLogger.d("MAX rewarded $adUnitId is showing; deferring load until it is hidden")
            pendingRewardedLoads.getOrPut(adUnitId) { mutableListOf() }
                .add { loadRewarded(context, placementId, adUnitId, ttlMs, callback) }
            return
        }

        val rewarded = MaxRewardedAd.getInstance(adUnitId)
        val wrapper = LoadedMaxRewarded(rewarded, placementId, adUnitId, ttlMs)

        rewarded.setRevenueListener { maxAd ->
            emitRevenue(AdFormat.REWARDED, placementId, adUnitId, maxAd)
        }
        rewarded.setListener(object : MaxRewardedAdListener {
            // Show olayları, o an gösterimde olan wrapper'a haritadan yönlendirilir.
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
                val listener = active()?.showListener
                onRewardedShowFinished(adUnitId)
                listener?.onShowFailed(error.message ?: "MAX display error ${error.code}")
            }

            override fun onAdHidden(ad: MaxAd) {
                val listener = active()?.showListener
                onRewardedShowFinished(adUnitId)
                listener?.onDismissed()
            }

            override fun onAdClicked(ad: MaxAd) {
                active()?.showListener?.onClicked()
            }

            override fun onUserRewarded(ad: MaxAd, reward: MaxReward) {
                // Ödül terminal bir olay değildir: kilit zorla açılmış olsa bile kullanıcının
                // kazandığı ödül, gösterimi başlatan wrapper'a iletilir.
                val target = active() ?: forceReleasedRewarded[adUnitId]
                target?.showListener?.onRewardEarned(
                    RewardItem(reward.amount, reward.label ?: "")
                )
            }
        })
        rewarded.loadAd()
    }

    /** Ad unit id -> şu an gösterimde olan rewarded wrapper. */
    private val showingRewarded = mutableMapOf<String, LoadedMaxRewarded>()

    /** Ad unit id -> gösterim sürerken ertelenmiş load istekleri (placement başına bir tane). */
    private val pendingRewardedLoads = mutableMapOf<String, MutableList<() -> Unit>>()

    /**
     * Ad unit id -> kilidi zorla açılmış ama SDK'dan hidden gelmemiş wrapper. Yalnızca
     * geç gelen ödülün yönlendirilmesi için tutulur; terminal olaylar buraya gitmez.
     * Sonraki onAdHidden/onAdDisplayFailed ile temizlenir.
     */
    private val forceReleasedRewarded = mutableMapOf<String, LoadedMaxRewarded>()

    /**
     * Ad unit için gösterimin bittiğini kaydeder (hidden / display failed / zorla
     * açılma) ve ertelenmiş load varsa başlatır. Bekleyen load, kilidin zorla
     * açılması durumunda da tetiklenir; aksi hâlde onAdHidden hiç gelmezse
     * placement süreç boyunca "yükleniyor" kalırdı.
     */
    private fun onRewardedShowFinished(
        adUnitId: String,
        wrapper: LoadedMaxRewarded? = null,
        forceReleased: Boolean = false
    ) {
        if (wrapper != null && showingRewarded[adUnitId] !== wrapper) return
        val finished = showingRewarded.remove(adUnitId)
        if (forceReleased && finished != null) forceReleasedRewarded[adUnitId] = finished
        else forceReleasedRewarded.remove(adUnitId)
        pendingRewardedLoads.remove(adUnitId)?.forEach { it.invoke() }
    }

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
            onRewardedShowFinished(adUnitId, this)
        }

        override fun onForceReleased() {
            // Kilit zorla açıldı: bu wrapper artık "gösterimde" sayılmaz. Geç gelecek
            // onAdHidden/onUserRewarded haritada bu wrapper'ı bulamayacağı için yeni bir
            // gösterime karışmaz. Ertelenmiş load varsa şimdi başlar.
            onRewardedShowFinished(adUnitId, this, forceReleased = true)
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
