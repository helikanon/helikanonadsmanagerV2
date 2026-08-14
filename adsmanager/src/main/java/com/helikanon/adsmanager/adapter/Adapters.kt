package com.helikanon.adsmanager.adapter

import android.content.Context
import com.helikanon.adsmanager.config.AdFormat
import com.helikanon.adsmanager.config.AdNetwork
import com.helikanon.adsmanager.core.FullscreenLoadCallback

/** Network adapterlerine yönlendirme yapan giriş noktası. */
internal object Adapters {

    fun loadFullscreen(
        context: Context,
        network: AdNetwork,
        format: AdFormat,
        placementId: String,
        adUnitId: String,
        ttlMs: Long,
        callback: FullscreenLoadCallback
    ) {
        try {
            when (network) {
                AdNetwork.ADMOB -> AdMobFullscreenAdapter.load(context, format, placementId, adUnitId, ttlMs, callback)
                AdNetwork.APPLOVIN -> MaxFullscreenAdapter.load(context, format, placementId, adUnitId, ttlMs, callback)
            }
        } catch (t: Throwable) {
            // Adapter içindeki beklenmedik bir hata waterfall'u kırmasın, sıradaki network denensin
            callback.onFailed("$network adapter threw: ${t.message}")
        }
    }
}
