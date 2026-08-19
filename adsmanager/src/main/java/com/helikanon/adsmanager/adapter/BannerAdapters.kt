package com.helikanon.adsmanager.adapter

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.applovin.mediation.MaxAd
import com.applovin.mediation.MaxAdFormat
import com.applovin.mediation.MaxAdViewAdListener
import com.applovin.mediation.MaxError
import com.applovin.mediation.MaxAdViewConfiguration
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

/**
 * Yüklenmekte olan veya yüklenmiş bir banner'ı network'ten bağımsız temsil eder.
 * [destroy] bekleyen load isteğini de iptal eder (MaxAdView.destroy / AdView.destroy);
 * bu sayede timeout'ta in-flight istek askıda bırakılmaz.
 */
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

private fun adViewLayoutParams(width: Int, height: Int): FrameLayout.LayoutParams =
    FrameLayout.LayoutParams(width, height, android.view.Gravity.CENTER_HORIZONTAL)

// ================================================================== AdMob

internal object AdMobBannerAdapter {

    /**
     * AdView'ı oluşturur, [host]'a INVISIBLE olarak ekler ve yüklemeyi başlatır.
     * Handle SENKRON döner; [callback] yalnızca sonucu bildirir. Timeout'ta çağıran
     * taraf handle.destroy() ile bekleyen isteği iptal etmelidir.
     */
    fun load(
        context: Context,
        host: ViewGroup,
        type: BannerType,
        placementId: String,
        adUnitId: String,
        callback: BannerLoadCallback
    ): BannerHandle {
        val adView = AdView(context)
        adView.adUnitId = adUnitId
        adView.setAdSize(resolveSize(context, type))
        adView.setOnPaidEventListener { adValue ->
            AdMobFullscreenAdapter.emitRevenue(AdFormat.BANNER, placementId, adUnitId, adValue)
        }

        val handle = object : BannerHandle {
            private var destroyed = false
            override val view: View = adView
            override val network = AdNetwork.ADMOB
            override fun pause() = adView.pause()
            override fun resume() = adView.resume()
            override fun destroy() {
                if (destroyed) return
                destroyed = true
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

        // Attach-önce-load: detached view'da loadAd bazı network'lerde askıda kalabiliyor.
        adView.visibility = View.INVISIBLE
        host.addView(
            adView,
            adViewLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        adView.loadAd(AdRequest.Builder().build())
        return handle
    }

    private fun resolveSize(context: Context, type: BannerType): AdSize = when (type) {
        BannerType.STANDARD -> AdSize.BANNER
        BannerType.MREC -> AdSize.MEDIUM_RECTANGLE
        BannerType.ADAPTIVE -> {
            val metrics = context.resources.displayMetrics
            val widthDp = (metrics.widthPixels / metrics.density).toInt()
            @Suppress("DEPRECATION")
            AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp)
        }
    }
}

// ================================================================== MAX

internal object MaxBannerAdapter {

    /**
     * MaxAdView'ı oluşturur, [host]'a INVISIBLE olarak ekler ve yüklemeyi başlatır.
     * Handle SENKRON döner; timeout'ta handle.destroy() bekleyen loadAd isteğini iptal
     * eder — aksi halde MAX aynı ad unit'in sonraki isteklerini serileştirip kilitler.
     */
    fun load(
        context: Context,
        host: ViewGroup,
        type: BannerType,
        placementId: String,
        adUnitId: String,
        callback: BannerLoadCallback
    ): BannerHandle {
        val density = context.resources.displayMetrics.density
        // MAX 13.x: adaptive banner MaxAdViewConfiguration ile bildirilir;
        // "adaptive_banner" extra parametresi 13.6+ sürümlerde config hatası logluyor.
        val adView: MaxAdView = when (type) {
            BannerType.MREC -> MaxAdView(adUnitId, MaxAdFormat.MREC)
            BannerType.ADAPTIVE -> MaxAdView(
                adUnitId,
                MaxAdViewConfiguration.builder()
                    .setAdaptiveType(MaxAdViewConfiguration.AdaptiveType.ANCHORED)
                    .build()
            )
            BannerType.STANDARD -> MaxAdView(adUnitId)
        }

        // Boyutlar
        val layoutParams = when (type) {
            BannerType.MREC -> adViewLayoutParams((300 * density).toInt(), (250 * density).toInt())

            BannerType.ADAPTIVE -> {
                val heightDp = MaxAdFormat.BANNER.getAdaptiveSize(context).height
                adViewLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (heightDp * density).toInt())
            }

            BannerType.STANDARD ->
                adViewLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (50 * density).toInt())
        }

        adView.setRevenueListener { maxAd ->
            MaxFullscreenAdapter.emitRevenue(AdFormat.BANNER, placementId, adUnitId, maxAd)
        }

        val handle = object : BannerHandle {
            private var destroyed = false
            override val view: View = adView
            override val network = AdNetwork.APPLOVIN
            override fun pause() = adView.stopAutoRefresh()
            override fun resume() = adView.startAutoRefresh()
            override fun destroy() {
                if (destroyed) return
                destroyed = true
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

        // AppLovin'in önerdiği akış: view'ı hierarchy'e ekledikten SONRA loadAd çağır.
        // Detached view'da loadAd MAX'te ertelenmiş/askıda yüklemelere yol açabiliyor.
        adView.visibility = View.INVISIBLE
        host.addView(adView, layoutParams)
        adView.loadAd()
        return handle
    }
}
