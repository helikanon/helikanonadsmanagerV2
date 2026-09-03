package com.helikanon.adsmanager.core

import android.os.SystemClock
import com.helikanon.adsmanager.config.AdFormat

/**
 * Aynı anda yalnızca bir fullscreen reklamın gösterilmesini garanti eder.
 * Örn: interstitial veya rewarded ekrandayken app open açılamaz.
 *
 * Kilit normalde network callback'i (dismiss / show failed) ile açılır. Reklam
 * activity'sinin üstüne başka bir activity gelmesi gibi durumlarda bu callback
 * hiç gelmeyebilir; bu yüzden [FullscreenGateWatchdog] ve [MAX_HOLD_MS] zaman
 * aşımı kilidi zorla açar. Geç gelen eski callback'in yeni bir gösterimin
 * kilidini açmaması için her edinim bir bilet ([tryAcquire] dönüşü) alır ve
 * [release] yalnızca aynı bileti kabul eder.
 */
internal object FullscreenGate {

    /** Bir fullscreen reklamın kilidi en fazla bu kadar tutulur (5 dk). */
    const val MAX_HOLD_MS = 5 * 60_000L

    @Volatile
    var showingFormat: AdFormat? = null
        private set

    @Volatile
    var showingPlacementId: String? = null
        private set

    @Volatile
    private var acquiredAt = 0L

    /** Şu an kilidi tutan bilet; kilit boşsa 0. */
    @Volatile
    var currentTicket = 0L
        private set
    private var ticketCounter = 0L

    /** Zorla açılma sonrası AdsManager'ın (ör. yeniden yükleme için) haberdar edilmesi. */
    var onForceReleased: ((format: AdFormat, placementId: String?, reason: String) -> Unit)? = null

    val isShowing: Boolean get() = showingFormat != null

    /** Kilit tutuluyorsa ne kadar süredir tutulduğu (ms), yoksa 0. */
    fun heldForMs(): Long =
        if (showingFormat == null) 0L else SystemClock.elapsedRealtime() - acquiredAt

    /**
     * Kilidi almayı dener. Başarılıysa bilet döner, kilit doluysa null.
     */
    fun tryAcquire(format: AdFormat, placementId: String?): Long? {
        val ticket: Long
        synchronized(this) {
            if (showingFormat != null) return null
            showingFormat = format
            showingPlacementId = placementId
            acquiredAt = SystemClock.elapsedRealtime()
            ticket = ++ticketCounter
            currentTicket = ticket
        }
        scheduleTimeout(ticket)
        return ticket
    }

    /** Yalnızca kilidi alan bilet açabilir; eski/yabancı biletler yoksayılır. */
    fun release(ticket: Long) {
        synchronized(this) {
            if (currentTicket == ticket && showingFormat != null) clearLocked()
        }
    }

    /**
     * Kilidi kim tutuyorsa açar ve [onForceReleased] ile haber verir.
     * @return kilit gerçekten tutuluyordu ve açıldıysa true.
     */
    fun forceRelease(reason: String): Boolean {
        val format: AdFormat
        val placementId: String?
        synchronized(this) {
            format = showingFormat ?: return false
            placementId = showingPlacementId
            clearLocked()
        }
        AdsLogger.w("FullscreenGate force-released ($format/${placementId ?: "default"}): $reason")
        onForceReleased?.invoke(format, placementId, reason)
        return true
    }

    fun reset() {
        synchronized(this) { clearLocked() }
    }

    private fun clearLocked() {
        showingFormat = null
        showingPlacementId = null
        acquiredAt = 0L
        currentTicket = 0L
    }

    private fun scheduleTimeout(ticket: Long) {
        MainThread.runDelayed(MAX_HOLD_MS) {
            if (currentTicket == ticket && showingFormat != null) {
                forceRelease("held longer than ${MAX_HOLD_MS / 1000}s without dismiss callback")
            }
        }
    }
}
