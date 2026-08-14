package com.helikanon.adsmanager.core

import com.helikanon.adsmanager.config.AdFormat
import kotlin.math.min
import kotlin.math.pow

/**
 * Preload açık formatlarda tüm waterfall fail olduğunda exponential backoff ile
 * otomatik yeniden deneme planlar. (8s, 16s, 32s, ... maks retryMaxDelaySec)
 */
internal object RetryScheduler {

    private data class Key(val format: AdFormat, val placementId: String)

    private val attempts = mutableMapOf<Key, Int>()
    private val pendingRetries = mutableMapOf<Key, Runnable>()

    fun scheduleRetry(
        format: AdFormat,
        placementId: String,
        baseDelaySec: Int,
        maxDelaySec: Int,
        action: () -> Unit
    ) {
        val key = Key(format, placementId)
        // Zaten planlanmış bir retry varsa üstüne planlama
        if (pendingRetries.containsKey(key)) return

        val attempt = attempts.getOrDefault(key, 0)
        attempts[key] = attempt + 1

        val delaySec = min(baseDelaySec.toDouble() * 2.0.pow(attempt.toDouble()), maxDelaySec.toDouble())
        val delayMs = (delaySec * 1000).toLong()
        AdsLogger.d("Retry #${attempt + 1} for $format/$placementId in ${delaySec.toInt()}s")

        val runnable = Runnable {
            pendingRetries.remove(key)
            if (ConnectivityMonitor.isOnline) {
                action()
            } else {
                // Bağlantı yok: sayacı artırmadan bağlantı gelmesini bekle
                AdsLogger.d("Retry for $format/$placementId postponed (offline)")
                ConnectivityMonitor.addOnlineListenerOnce(key.toString()) { action() }
            }
        }
        pendingRetries[key] = runnable
        MainThread.handler.postDelayed(runnable, delayMs)
    }

    fun onLoadSuccess(format: AdFormat, placementId: String) {
        val key = Key(format, placementId)
        attempts.remove(key)
        pendingRetries.remove(key)?.let { MainThread.handler.removeCallbacks(it) }
    }

    /** Bekleyen retry'ı ve offline listener'ını tamamen iptal eder (ör. banner destroy edildiğinde). */
    fun cancel(format: AdFormat, placementId: String) {
        val key = Key(format, placementId)
        attempts.remove(key)
        pendingRetries.remove(key)?.let { MainThread.handler.removeCallbacks(it) }
        ConnectivityMonitor.removeOnceListener(key.toString())
    }

    fun cancelAll() {
        pendingRetries.values.forEach { MainThread.handler.removeCallbacks(it) }
        pendingRetries.clear()
        attempts.clear()
    }
}
