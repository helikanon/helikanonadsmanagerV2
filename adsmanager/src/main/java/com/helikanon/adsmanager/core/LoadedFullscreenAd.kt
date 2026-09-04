package com.helikanon.adsmanager.core

import android.app.Activity
import android.os.SystemClock
import com.helikanon.adsmanager.config.AdFormat
import com.helikanon.adsmanager.config.AdNetwork
import com.helikanon.adsmanager.listener.RewardItem

/**
 * Network'ten bağımsız, yüklenmiş bir fullscreen reklamı (interstitial / rewarded / app open)
 * temsil eder. Adapterler bu sınıfın network'e özel implementasyonlarını döner.
 */
internal abstract class LoadedFullscreenAd(
    val network: AdNetwork,
    val format: AdFormat,
    val placementId: String,
    val adUnitId: String,
    private val ttlMs: Long
) {
    val loadedAtMs: Long = SystemClock.elapsedRealtime()

    val isExpired: Boolean
        get() = SystemClock.elapsedRealtime() - loadedAtMs > ttlMs

    abstract val isReady: Boolean

    /** Reklamı gösterir. Callbackler main thread'de çağrılır. */
    abstract fun show(activity: Activity, listener: ShowListener)

    /** Reklam objesini serbest bırakır (gösterilmeden çöpe atılırsa da çağrılır). */
    open fun destroy() {}

    /**
     * Gösterim sürerken fullscreen kilidi zorla açıldığında (dismiss callback'i
     * gelmeden) çağrılır. Adapter, "gösterimde" saydığı durumu düşürmeli ve geç
     * gelecek SDK callback'lerinin yeni bir gösterime karışmamasını sağlamalıdır.
     * SDK reklam objesi burada destroy edilmez; reklam activity'si hâlâ altta
     * yaşıyor olabilir.
     */
    open fun onForceReleased() {}

    interface ShowListener {
        fun onShown(mediatedNetworkName: String?)
        fun onShowFailed(error: String)
        fun onDismissed()
        fun onClicked()
        fun onRewardEarned(reward: RewardItem)
    }
}

/** Adapter load sonucu. */
internal interface FullscreenLoadCallback {
    fun onLoaded(ad: LoadedFullscreenAd)
    fun onFailed(error: String)
}
