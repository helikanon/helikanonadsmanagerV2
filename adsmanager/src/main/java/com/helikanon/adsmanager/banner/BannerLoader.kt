package com.helikanon.adsmanager.banner

import android.app.Activity
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
import com.helikanon.adsmanager.core.MainThread
import com.helikanon.adsmanager.core.RetryScheduler
import com.helikanon.adsmanager.listener.AdInfo
import java.lang.ref.WeakReference

/** Banner için network waterfall'u yürütür. */
internal object BannerLoader {

    fun load(
        activity: Activity,
        controller: BannerAdController,
        type: BannerType,
        config: AdsConfig,
        formatConfig: FormatConfig,
        placement: PlacementConfig
    ) {
        val candidates = formatConfig.networkOrder
            .filter { it != AdNetwork.APPLOVIN || com.helikanon.adsmanager.core.InitManager.applovinUsable }
            .mapNotNull { network -> placement.adUnitIdFor(network)?.let { network to it } }

        if (candidates.isEmpty()) {
            EventCenter.event {
                it.onAdLoadFailed(AdFormat.BANNER, placement.id, "No banner ad unit configured for '${placement.id}'")
            }
            return
        }

        val activityRef = WeakReference(activity)
        attemptAt(activityRef, controller, type, config, formatConfig, placement, candidates, 0, mutableListOf())
    }

    private fun attemptAt(
        activityRef: WeakReference<Activity>,
        controller: BannerAdController,
        type: BannerType,
        config: AdsConfig,
        formatConfig: FormatConfig,
        placement: PlacementConfig,
        candidates: List<Pair<AdNetwork, String>>,
        index: Int,
        errors: MutableList<String>
    ) {
        val activity = activityRef.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed || controller.isDestroyed) {
            AdsLogger.d("Banner load cancelled for '${placement.id}' (activity/controller gone)")
            return
        }

        if (index >= candidates.size) {
            val error = errors.joinToString(" | ")
            AdsLogger.w("Banner waterfall exhausted for '${placement.id}': $error")
            EventCenter.event { it.onAdLoadFailed(AdFormat.BANNER, placement.id, error) }
            // Otomatik retry: controller yaşıyorsa backoff ile tekrar dene
            RetryScheduler.scheduleRetry(
                AdFormat.BANNER, placement.id, config.retryBaseDelaySec, config.retryMaxDelaySec
            ) {
                attemptAt(activityRef, controller, type, config, formatConfig, placement, candidates, 0, mutableListOf())
            }
            return
        }

        val (network, adUnitId) = candidates[index]
        AdsLogger.d("Banner waterfall: loading '${placement.id}' from $network (step ${index + 1}/${candidates.size})")

        var settled = false
        val timeoutRunnable = Runnable {
            if (!settled) {
                settled = true
                errors.add("$network banner load timeout (${config.loadTimeoutSec}s)")
                attemptAt(activityRef, controller, type, config, formatConfig, placement, candidates, index + 1, errors)
            }
        }
        MainThread.handler.postDelayed(timeoutRunnable, config.loadTimeoutSec * 1000L)

        val callback = object : BannerLoadCallback {
            override fun onLoaded(handle: BannerHandle) {
                MainThread.run {
                    if (settled) {
                        handle.destroy()
                        return@run
                    }
                    settled = true
                    MainThread.handler.removeCallbacks(timeoutRunnable)
                    RetryScheduler.onLoadSuccess(AdFormat.BANNER, placement.id)
                    controller.attach(handle)
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
                    errors.add("$network: $error")
                    attemptAt(activityRef, controller, type, config, formatConfig, placement, candidates, index + 1, errors)
                }
            }
        }

        try {
            when (network) {
                AdNetwork.ADMOB -> AdMobBannerAdapter.load(activity, type, placement.id, adUnitId, callback)
                AdNetwork.APPLOVIN -> MaxBannerAdapter.load(activity, type, placement.id, adUnitId, callback)
            }
        } catch (t: Throwable) {
            callback.onFailed("$network banner adapter threw: ${t.message}")
        }
    }
}
