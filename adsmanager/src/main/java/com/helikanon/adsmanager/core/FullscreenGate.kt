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
 * kilidini açmaması için her edinim bir bilet ([tryAcquire] dönüşü) alır;
 * [release] ve [forceRelease] yalnızca aynı bileti kabul eder.
 */
internal object FullscreenGate {

    /**
     * Bir fullscreen reklamın kilidi, reklam activity'si ön planda değilken en fazla
     * bu kadar tutulur (5 dk). Reklam activity'si hâlâ ön plandaysa (uzun rewarded,
     * reklam tıklamasıyla tarayıcıya gidilmesi vb.) süre uzatılır.
     */
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

    /** Kilidi tutan gösterimin zorla açılma bildirimi (bilet sahibine özel). */
    private var forceReleaseListener: ((reason: String) -> Unit)? = null

    val isShowing: Boolean get() = showingFormat != null

    /** Kilit tutuluyorsa ne kadar süredir tutulduğu (ms), yoksa 0. */
    fun heldForMs(): Long =
        if (showingFormat == null) 0L else SystemClock.elapsedRealtime() - acquiredAt

    /**
     * Kilidi almayı dener. Başarılıysa bilet döner, kilit doluysa null.
     *
     * @param onForceReleased kilit bu gösterim için [forceRelease] ile açılırsa
     * (main thread'de) çağrılır; normal [release] için çağrılmaz.
     */
    fun tryAcquire(
        format: AdFormat,
        placementId: String?,
        onForceReleased: ((reason: String) -> Unit)? = null
    ): Long? {
        val ticket: Long
        synchronized(this) {
            if (showingFormat != null) return null
            showingFormat = format
            showingPlacementId = placementId
            acquiredAt = SystemClock.elapsedRealtime()
            forceReleaseListener = onForceReleased
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
     * Kilidi, hâlâ [ticket] tarafından tutuluyorsa açar ve o gösterimin
     * zorla açılma dinleyicisine haber verir. Bilet karşılaştırması ile açma aynı
     * kilit bloğunda yapılır; bu sayede eski bir zaman aşımı/bekçi kararı, arada
     * alınmış yeni bir gösterimin kilidini açamaz.
     *
     * @return kilit gerçekten bu bilet tarafından tutuluyordu ve açıldıysa true.
     */
    fun forceRelease(ticket: Long, reason: String): Boolean {
        val format: AdFormat
        val placementId: String?
        val listener: ((String) -> Unit)?
        synchronized(this) {
            if (currentTicket != ticket) return false
            format = showingFormat ?: return false
            placementId = showingPlacementId
            listener = forceReleaseListener
            clearLocked()
        }
        AdsLogger.w("FullscreenGate force-released ($format/${placementId ?: "default"}): $reason")
        MainThread.run { listener?.invoke(reason) }
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
        forceReleaseListener = null
    }

    private fun scheduleTimeout(ticket: Long) {
        MainThread.runDelayed(MAX_HOLD_MS) {
            if (currentTicket != ticket || showingFormat == null) return@runDelayed
            if (FullscreenGateWatchdog.isAdActivityInForeground) {
                // Reklam activity'si hâlâ en üstte: gerçek reklam sürüyor (uzun rewarded,
                // tıklama sonrası tarayıcı vb.). Kilidi açmak ikinci bir fullscreen'e ve
                // erken reload'a yol açar; süreyi uzat.
                AdsLogger.d(
                    "FullscreenGate held ${heldForMs() / 1000}s but ad activity is still in " +
                        "foreground; extending timeout"
                )
                scheduleTimeout(ticket)
                return@runDelayed
            }
            forceRelease(ticket, "held longer than ${MAX_HOLD_MS / 1000}s without dismiss callback")
        }
    }
}
