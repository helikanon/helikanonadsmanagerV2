package com.helikanon.adsmanager.listener

import com.helikanon.adsmanager.config.AdFormat
import com.helikanon.adsmanager.config.AdNetwork

/** Bir reklam olayının kimlik bilgisi. */
data class AdInfo(
    val format: AdFormat,
    val placementId: String,
    val network: AdNetwork?,
    val adUnitId: String?
)

/** Rewarded reklamlardan gelen ödül. */
data class RewardItem(
    val amount: Int,
    val type: String
)

/** Reklam gelirini (ILRD) temsil eder. Değerler USD cinsindendir. */
data class AdRevenue(
    val format: AdFormat,
    val placementId: String,
    val network: AdNetwork,
    val adUnitId: String,
    /** Tahmini gelir. MAX'te her zaman USD, AdMob'da [currency] alanındaki para birimindedir (genellikle USD). */
    val revenue: Double,
    val currency: String,
    /** "PRECISE" / "ESTIMATED" / "PUBLISHER_PROVIDED" / "UNKNOWN" */
    val precision: String,
    /** Reklamı dolduran alt network adı (MAX'te mediated network, AdMob'da "admob"). */
    val mediatedNetworkName: String?
)

/** Tüm reklam olayları için ortak listener. Tüm callbackler main thread'de çağrılır. */
interface AdEventListener {
    fun onAdLoaded(info: AdInfo) {}
    fun onAdLoadFailed(format: AdFormat, placementId: String, error: String) {}
    fun onAdShown(info: AdInfo) {}
    fun onAdShowFailed(info: AdInfo, error: String) {}
    fun onAdDismissed(info: AdInfo) {}
    fun onAdClicked(info: AdInfo) {}
    fun onRewardEarned(info: AdInfo, reward: RewardItem) {}
}

/**
 * Tek bir show çağrısına özel opsiyonel callback'ler.
 * Global [AdEventListener]'a EK olarak çağrılır (onu değiştirmez).
 * Örn. splash'ta "reklam kapanınca ana ekrana geç" akışı için kullanılır.
 */
class AdShowCallbacks(
    val onShown: (() -> Unit)? = null,
    val onDismissed: (() -> Unit)? = null,
    val onShowFailed: ((String) -> Unit)? = null
)

/** Reklam geliri (ILRD) listener'ı. Firebase/Adjust/AppsFlyer entegrasyonları için. */
fun interface AdRevenueListener {
    fun onAdRevenue(revenue: AdRevenue)
}

/** Kütüphane loglarını uygulamaya iletmek için (ör. hataları analytics'e göndermek). */
enum class AdsLogLevel { DEBUG, INFO, WARN, ERROR }

fun interface AdsLogListener {
    fun onLog(level: AdsLogLevel, message: String)
}
