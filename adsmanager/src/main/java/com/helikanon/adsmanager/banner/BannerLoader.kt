package com.helikanon.adsmanager.banner

import android.app.Activity
import android.content.Context
import com.helikanon.adsmanager.adapter.AdMobBannerAdapter
import com.helikanon.adsmanager.adapter.BannerHandle
import com.helikanon.adsmanager.adapter.BannerLoadCallback
import com.helikanon.adsmanager.adapter.MaxBannerAdapter
import com.helikanon.adsmanager.config.AdFormat
import com.helikanon.adsmanager.config.AdNetwork
import com.helikanon.adsmanager.config.AdsConfig
import com.helikanon.adsmanager.config.BannerType
import com.helikanon.adsmanager.config.FormatConfig
import com.helikanon.adsmanager.config.PlacementConfig
import com.helikanon.adsmanager.core.AdsLogger
import com.helikanon.adsmanager.core.EventCenter
import com.helikanon.adsmanager.core.InitManager
import com.helikanon.adsmanager.core.MainThread
import com.helikanon.adsmanager.core.RetryScheduler
import com.helikanon.adsmanager.listener.AdInfo
import java.lang.ref.WeakReference

/** Banner için network waterfall'u yürütür. */
internal object BannerLoader {

    fun load(
        context: Context,
        controller: BannerAdController,
        type: BannerType,
        config: AdsConfig,
        formatConfig: FormatConfig,
        placement: PlacementConfig
    ) {
        val candidates = formatConfig.networkOrderFor(placement)
            .filter { it != AdNetwork.APPLOVIN || InitManager.applovinUsable }
            .mapNotNull { network -> placement.adUnitIdFor(network)?.let { network to it } }

        if (candidates.isEmpty()) {
            controller.isLoadActive = false
            EventCenter.event {
                it.onAdLoadFailed(AdFormat.BANNER, placement.id, "No banner ad unit configured for '${placement.id}'")
            }
            return
        }

        controller.isLoadActive = true
        val contextRef = WeakReference(context)
        attemptAt(contextRef, controller, type, config, formatConfig, placement, candidates, 0, mutableListOf())
    }

    private fun attemptAt(
        contextRef: WeakReference<Context>,
        controller: BannerAdController,
        type: BannerType,
        config: AdsConfig,
        formatConfig: FormatConfig,
        placement: PlacementConfig,
        candidates: List<Pair<AdNetwork, String>>,
        index: Int,
        errors: MutableList<String>
    ) {
        val context = contextRef.get()
        val activityGone = (context as? Activity)?.let { it.isFinishing || it.isDestroyed } ?: false
        if (context == null || activityGone || controller.isDestroyed) {
            AdsLogger.d("Banner load cancelled for '${placement.id}' (context/controller gone)")
            controller.isLoadActive = false
            return
        }

        if (index >= candidates.size) {
            val error = errors.joinToString(" | ")
            AdsLogger.w("Banner waterfall exhausted for '${placement.id}': $error")
            EventCenter.event { it.onAdLoadFailed(AdFormat.BANNER, placement.id, error) }
            // Otomatik retry: backoff ile, maxBannerRetryCount sınırına kadar tekrar dene.
            // Sınıra ulaşılırsa döngü durur; bir sonraki loadBanner çağrısı sayacı sıfırlar.
            val scheduled = RetryScheduler.scheduleRetry(
                AdFormat.BANNER, placement.id, config.retryBaseDelaySec, config.retryMaxDelaySec,
                maxAttempts = config.maxBannerRetryCount
            ) {
                attemptAt(contextRef, controller, type, config, formatConfig, placement, candidates, 0, mutableListOf())
            }
            if (!scheduled) controller.isLoadActive = false
            return
        }

        val (network, adUnitId) = candidates[index]
        // Adım yalnızca kendi network'ünü bekler; hazır olunca aynı adım (context kontrolüyle) yeniden denenir
        if (!InitManager.isReady(network)) {
            AdsLogger.d("Banner waterfall: waiting for $network init before loading '${placement.id}' (step ${index + 1}/${candidates.size})")
            InitManager.runWhenReady(network) {
                attemptAt(contextRef, controller, type, config, formatConfig, placement, candidates, index, errors)
            }
            return
        }
        // SDK key'i olmayan AppLovin init'te anlaşılır; init'ten önce kurulan waterfall'da adım burada atlanır
        if (network == AdNetwork.APPLOVIN && !InitManager.applovinUsable) {
            errors.add("$network: applovinSdkKey not set")
            attemptAt(contextRef, controller, type, config, formatConfig, placement, candidates, index + 1, errors)
            return
        }
        AdsLogger.d("Banner waterfall: loading '${placement.id}' from $network (step ${index + 1}/${candidates.size})")

        var settled = false
        var inFlight: BannerHandle? = null

        fun clearInFlight() {
            if (controller.inFlight === inFlight) controller.inFlight = null
            inFlight = null
        }

        val timeoutRunnable = Runnable {
            if (!settled) {
                settled = true
                // KRİTİK: yüklenmekte olan view'ı MUTLAKA destroy et. MaxAdView.destroy()
                // bekleyen loadAd isteğini iptal eder; aksi halde MAX aynı ad unit'in
                // sonraki tüm isteklerini bu askıdaki isteğin arkasında serileştirir.
                inFlight?.destroy()
                clearInFlight()
                errors.add("$network banner load timeout (${placement.loadTimeoutSec ?: config.loadTimeoutSec}s)")
                attemptAt(contextRef, controller, type, config, formatConfig, placement, candidates, index + 1, errors)
            }
        }
        MainThread.handler.postDelayed(timeoutRunnable, (placement.loadTimeoutSec ?: config.loadTimeoutSec) * 1000L)

        val callback = object : BannerLoadCallback {
            override fun onLoaded(handle: BannerHandle) {
                MainThread.run {
                    if (settled) {
                        // Timeout sonrası geç gelen sonuç: view zaten (veya şimdi) destroy edilir.
                        handle.destroy()
                        return@run
                    }
                    settled = true
                    MainThread.handler.removeCallbacks(timeoutRunnable)
                    clearInFlight()
                    controller.isLoadActive = false
                    RetryScheduler.onLoadSuccess(AdFormat.BANNER, placement.id)
                    controller.onAdLoaded(handle)
                    EventCenter.event {
                        it.onAdLoaded(AdInfo(AdFormat.BANNER, placement.id, network, adUnitId))
                    }
                }
            }

            override fun onFailed(error: String) {
                MainThread.run {
                    if (settled) return@run
                    settled = true
                    MainThread.handler.removeCallbacks(timeoutRunnable)
                    inFlight?.destroy()
                    clearInFlight()
                    errors.add("$network: $error")
                    attemptAt(contextRef, controller, type, config, formatConfig, placement, candidates, index + 1, errors)
                }
            }
        }

        try {
            // Adapter, view'ı controller.host'a INVISIBLE ekleyip yüklemeyi başlatır ve
            // handle'ı senkron döndürür; böylece timeout dalında iptal edilebilir.
            val handle = when (network) {
                AdNetwork.ADMOB ->
                    AdMobBannerAdapter.load(context, controller.host, type, placement.id, adUnitId, callback)
                AdNetwork.APPLOVIN ->
                    MaxBannerAdapter.load(context, controller.host, type, placement.id, adUnitId, callback)
            }
            if (!settled) {
                inFlight = handle
                controller.inFlight = handle
            } else if (controller.handle !== handle) {
                // Callback load() dönmeden senkron ateşlendi (ör. anlık hata): view'ı bırakma.
                handle.destroy()
            }
        } catch (t: Throwable) {
            callback.onFailed("$network banner adapter threw: ${t.message}")
        }
    }
}
