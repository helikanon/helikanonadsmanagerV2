package com.helikanon.adsmanager.core

import com.helikanon.adsmanager.config.AdNetwork

/**
 * SDK'ların hazır olma durumunu network bazında tutar ve bekleyen işleri, bekledikleri
 * network hazır olunca çalıştırır. Android'e bağımlı değildir (JVM testinde koşar) ve
 * thread-safe değildir: [InitManager] yalnızca main thread'den çağırır.
 *
 * - Network'e bağlı iş yalnızca o network'ü bekler; yavaş açılan diğer SDK'yı beklemez.
 * - Network'süz iş (`network = null`) tüm SDK'ların hazır olmasını bekler.
 * - [markAllReady] sonrası her network hazır sayılır (bu oturumda init edilmemiş olanlar dahil).
 */
internal class NetworkReadiness {

    @Volatile
    var allReady: Boolean = false
        private set

    private val readyNetworks = mutableSetOf<AdNetwork>()
    private val pending = mutableListOf<Pair<AdNetwork?, () -> Unit>>()

    val pendingCount: Int get() = pending.size

    fun isReady(network: AdNetwork?): Boolean =
        allReady || (network != null && network in readyNetworks)

    /** Hazırsa hemen çalıştırır; değilse hazır olunca bir kez çalıştırılmak üzere kuyruğa alır. */
    fun runWhenReady(network: AdNetwork?, action: () -> Unit) {
        if (isReady(network)) action() else pending.add(network to action)
    }

    fun markReady(network: AdNetwork) {
        if (allReady || !readyNetworks.add(network)) return
        flush { it == network }
    }

    fun markAllReady() {
        if (allReady) return
        allReady = true
        flush { true }
    }

    // İşler çalıştırılmadan önce kuyruktan çıkarılır: çalışan iş (ör. fail olup sıradaki
    // adıma geçen waterfall) kuyruğa yenisini ekleyebilir, hiçbir iş iki kez çalışmaz.
    private fun flush(matches: (AdNetwork?) -> Boolean) {
        val ready = pending.filter { matches(it.first) }
        pending.removeAll { matches(it.first) }
        ready.forEach { it.second() }
    }
}
