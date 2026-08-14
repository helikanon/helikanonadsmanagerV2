package com.helikanon.adsmanager.core

import com.helikanon.adsmanager.config.AdFormat

/**
 * Aynı anda yalnızca bir fullscreen reklamın gösterilmesini garanti eder.
 * Örn: interstitial veya rewarded ekrandayken app open açılamaz.
 */
internal object FullscreenGate {

    @Volatile
    var showingFormat: AdFormat? = null
        private set

    val isShowing: Boolean get() = showingFormat != null

    fun tryAcquire(format: AdFormat): Boolean {
        synchronized(this) {
            if (showingFormat != null) return false
            showingFormat = format
            return true
        }
    }

    fun release(format: AdFormat) {
        synchronized(this) {
            if (showingFormat == format) showingFormat = null
        }
    }

    fun reset() {
        synchronized(this) { showingFormat = null }
    }
}
