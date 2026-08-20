package com.helikanon.adsmanager.core

import android.content.Context
import com.helikanon.adsmanager.adapter.Adapters
import com.helikanon.adsmanager.config.AdsConfig
import com.helikanon.adsmanager.config.FormatConfig
import com.helikanon.adsmanager.config.PlacementConfig

/**
 * Format için tanımlı network sırasına göre (waterfall) reklam yükler.
 *
 * - Sıradaki network fail olursa veya timeout'a takılırsa bir sonrakine geçer.
 * - Bir network başarılı yüklediğinde durur; diğer networkler DENENMEZ.
 * - Tüm networkler fail olursa hata döner.
 */
internal object WaterfallLoader {

    fun loadFullscreen(
        context: Context,
        config: AdsConfig,
        formatConfig: FormatConfig,
        placement: PlacementConfig,
        onResult: (LoadedFullscreenAd?, String?) -> Unit
    ) {
        val candidates = formatConfig.networkOrderFor(placement)
            .filter { it != com.helikanon.adsmanager.config.AdNetwork.APPLOVIN || InitManager.applovinUsable }
            .mapNotNull { network -> placement.adUnitIdFor(network)?.let { network to it } }

        if (candidates.isEmpty()) {
            onResult(null, "No usable ad unit id configured for placement '${placement.id}' (${formatConfig.format})")
            return
        }

        attemptAt(context, config, formatConfig, placement, candidates, 0, mutableListOf(), onResult)
    }

    private fun attemptAt(
        context: Context,
        config: AdsConfig,
        formatConfig: FormatConfig,
        placement: PlacementConfig,
        candidates: List<Pair<com.helikanon.adsmanager.config.AdNetwork, String>>,
        index: Int,
        errors: MutableList<String>,
        onResult: (LoadedFullscreenAd?, String?) -> Unit
    ) {
        if (index >= candidates.size) {
            onResult(null, errors.joinToString(" | "))
            return
        }

        val (network, adUnitId) = candidates[index]
        val format = formatConfig.format
        AdsLogger.d("Waterfall: loading $format/${placement.id} from $network (step ${index + 1}/${candidates.size})")

        var settled = false
        val timeoutSec = placement.loadTimeoutSec ?: config.loadTimeoutSec
        val timeoutMs = timeoutSec * 1000L

        val timeoutRunnable = Runnable {
            if (!settled) {
                settled = true
                val msg = "$network load timeout (${timeoutSec}s) for $format/${placement.id}"
                AdsLogger.w(msg)
                errors.add(msg)
                attemptAt(context, config, formatConfig, placement, candidates, index + 1, errors, onResult)
            }
        }
        MainThread.handler.postDelayed(timeoutRunnable, timeoutMs)

        Adapters.loadFullscreen(
            context = context,
            network = network,
            format = format,
            placementId = placement.id,
            adUnitId = adUnitId,
            ttlMs = config.adTtlMinutes * 60_000L,
            callback = object : FullscreenLoadCallback {
                override fun onLoaded(ad: LoadedFullscreenAd) {
                    MainThread.run {
                        if (settled) {
                            // Timeout sonrası geç gelen success: reklamı çöpe at
                            AdsLogger.d("Late load success from $network for $format/${placement.id}, discarding")
                            ad.destroy()
                            return@run
                        }
                        settled = true
                        MainThread.handler.removeCallbacks(timeoutRunnable)
                        AdsLogger.i("Loaded $format/${placement.id} from $network")
                        onResult(ad, null)
                    }
                }

                override fun onFailed(error: String) {
                    MainThread.run {
                        if (settled) return@run
                        settled = true
                        MainThread.handler.removeCallbacks(timeoutRunnable)
                        val msg = "$network: $error"
                        AdsLogger.w("Load failed for $format/${placement.id} -> $msg")
                        errors.add(msg)
                        attemptAt(context, config, formatConfig, placement, candidates, index + 1, errors, onResult)
                    }
                }
            }
        )
    }
}
