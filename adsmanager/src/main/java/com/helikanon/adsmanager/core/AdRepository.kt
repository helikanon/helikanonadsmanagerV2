package com.helikanon.adsmanager.core

import com.helikanon.adsmanager.config.AdFormat

/**
 * Yüklenmiş fullscreen reklamları placement bazlı saklar.
 * Expire olan reklamlar get() sırasında temizlenir.
 */
internal object AdRepository {

    private val loaded = mutableMapOf<AdFormat, MutableMap<String, LoadedFullscreenAd>>()
    private val loading = mutableMapOf<AdFormat, MutableSet<String>>()

    /** Geçerli (expire olmamış) reklamı döner, yoksa null. */
    fun get(format: AdFormat, placementId: String): LoadedFullscreenAd? {
        val ad = loaded[format]?.get(placementId) ?: return null
        if (ad.isExpired || !ad.isReady) {
            AdsLogger.d("Stored ad for $format/$placementId expired or not ready, dropping")
            remove(format, placementId)?.destroy()
            return null
        }
        return ad
    }

    fun hasValidAd(format: AdFormat, placementId: String): Boolean =
        get(format, placementId) != null

    fun put(ad: LoadedFullscreenAd) {
        // Aynı placement'ta önceki reklam varsa serbest bırak
        loaded.getOrPut(ad.format) { mutableMapOf() }
            .put(ad.placementId, ad)?.destroy()
    }

    fun remove(format: AdFormat, placementId: String): LoadedFullscreenAd? =
        loaded[format]?.remove(placementId)

    fun isLoading(format: AdFormat, placementId: String): Boolean =
        loading[format]?.contains(placementId) == true

    fun markLoading(format: AdFormat, placementId: String) {
        loading.getOrPut(format) { mutableSetOf() }.add(placementId)
    }

    fun clearLoading(format: AdFormat, placementId: String) {
        loading[format]?.remove(placementId)
    }

    fun clearAll() {
        loaded.values.flatMap { it.values }.forEach { it.destroy() }
        loaded.clear()
        loading.clear()
    }
}
