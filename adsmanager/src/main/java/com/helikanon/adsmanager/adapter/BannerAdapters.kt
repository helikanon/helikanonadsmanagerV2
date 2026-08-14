package com.helikanon.adsmanager.adapter

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import com.applovin.mediation.MaxAd
import com.applovin.mediation.MaxAdFormat
import com.applovin.mediation.MaxAdViewAdListener
import com.applovin.mediation.MaxError
import com.applovin.mediation.ads.MaxAdView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.helikanon.adsmanager.config.AdFormat
import com.helikanon.adsmanager.config.AdNetwork
import com.helikanon.adsmanager.config.BannerType
import com.helikanon.adsmanager.core.EventCenter
import com.helikanon.adsmanager.listener.AdInfo

/** Yüklenmiş bir banner'ı network'ten bağımsız temsil eder. */
internal interface BannerHandle {
    val view: View
    val network: AdNetwork
    fun pause()
    fun resume()
    fun destroy()
}

internal interface BannerLoadCallback {
    fun onLoaded(handle: BannerHandle)
    fun onFailed(error: String)
}

// ================================================================== AdMob

internal object AdMobBannerAdapter {

    fun load(
        activity: Activity,
        type: BannerType,
        placementId: String,
        adUnitId: String,
        callback: BannerLoadCallback
    ) {
        val adView = AdView(activity)
        adView.adUnitId = adUnitId
        adView.setAdSize(resolveSize(activity, type))
        adView.setOnPaidEventListener { adValue ->
            AdMobFullscreenAdapter.emitRevenue(AdFormat.BANNER, placementId, adUnitId, adValue)
        }

        val handle = object : BannerHandle {
            override val view: View = adView
            override val network = AdNetwork.ADMOB
            override fun pause() = adView.pause()
            override fun resume() = adView.resume()
            override fun destroy() {
                (adView.parent as? ViewGroup)?.removeView(adView)
                adView.destroy()
            }
        }

        var firstResult = true
        adView.adListener = object : AdListener() {
            override fun onAdLoaded() {
                if (firstResult) {
                    firstResult = false
                    callback.onLoaded(handle)
                } else {
                    // otomatik refresh yüklemesi
                    EventCenter.event {
                        it.onAdLoaded(AdInfo(AdFormat.BANNER, placementId, AdNetwork.ADMOB, adUnitId))
                    }
                }
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                if (firstResult) {
                    firstResult = false
                    callback.onFailed(error.message)
                }
            }

            override fun onAdClicked() {
                EventCenter.event {
                    it.onAdClicked(AdInfo(AdFormat.BANNER, placementId, AdNetwork.ADMOB, adUnitId))
                }
            }
        }
        adView.loadAd(AdRequest.Builder().build())
    }

    private fun resolveSize(activity: Activity, type: BannerType): AdSize = when (type) {
        BannerType.STANDARD -> AdSize.BANNER
        BannerType.MREC -> AdSize.MEDIUM_RECTANGLE
        BannerType.ADAPTIVE -> {
            val metrics = activity.resources.displayMetrics
            val widthDp = (metrics.widthPixels / metrics.density).toInt()
            @Suppress("DEPRECATION")
            AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, widthDp)
        }
    }
}

// ================================================================== MAX

internal object MaxBannerAdapter {

    fun load(
        activity: Activity,
        type: BannerType,
        placementId: String,
        adUnitId: String,
        callback: BannerLoadCallback
    ) {
        val density = activity.resources.displayMetrics.density
        // MAX 13.x: MaxAdView yalnızca ad unit id (+ format) ile oluşturulur.
        val adView: MaxAdView = when (type) {
            BannerType.MREC -> MaxAdView(adUnitId, MaxAdFormat.MREC)
            else -> MaxAdView(adUnitId)
        }

        // Boyutlar
        when (type) {
            BannerType.MREC -> {
                adView.layoutParams = ViewGroup.LayoutParams(
                    (300 * density).toInt(), (250 * density).toInt()
                )
            }

            BannerType.ADAPTIVE -> {
                adView.setExtraParameter("adaptive_banner", "true")
                val heightDp = MaxAdFormat.BANNER.getAdaptiveSize(activity).height
                adView.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (heightDp * density).toInt()
                )
            }

            BannerType.STANDARD -> {
                adView.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (50 * density).toInt()
                )
            }
        }

        adView.setRevenueListener { maxAd ->
            MaxFullscreenAdapter.emitRevenue(AdFormat.BANNER, placementId, adUnitId, maxAd)
        }

        val handle = object : BannerHandle {
            override val view: View = adView
            override val network = AdNetwork.APPLOVIN
            override fun pause() = adView.stopAutoRefresh()
            override fun resume() = adView.startAutoRefresh()
            override fun destroy() {
                (adView.parent as? ViewGroup)?.removeView(adView)
                adView.destroy()
            }
        }

        var firstResult = true
        adView.setListener(object : MaxAdViewAdListener {
            override fun onAdLoaded(ad: MaxAd) {
                if (firstResult) {
                    firstResult = false
                    callback.onLoaded(handle)
                } else {
                    EventCenter.event {
                        it.onAdLoaded(AdInfo(AdFormat.BANNER, placementId, AdNetwork.APPLOVIN, adUnitId))
                    }
                }
            }

            override fun onAdLoadFailed(adUnitId: String, error: MaxError) {
                if (firstResult) {
                    firstResult = false
                    adView.destroy()
                    callback.onFailed(error.message ?: "MAX banner load error ${error.code}")
                }
            }

            override fun onAdDisplayed(ad: MaxAd) {}
            override fun onAdHidden(ad: MaxAd) {}
            override fun onAdDisplayFailed(ad: MaxAd, error: MaxError) {}
            override fun onAdExpanded(ad: MaxAd) {}
            override fun onAdCollapsed(ad: MaxAd) {}

            override fun onAdClicked(ad: MaxAd) {
                EventCenter.event {
                    it.onAdClicked(AdInfo(AdFormat.BANNER, placementId, AdNetwork.APPLOVIN, adUnitId))
                }
            }
        })
        adView.loadAd()
    }
}
