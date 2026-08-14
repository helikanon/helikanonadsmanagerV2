package com.helikanon.adsmanager.core

import com.helikanon.adsmanager.listener.AdEventListener
import com.helikanon.adsmanager.listener.AdRevenue
import com.helikanon.adsmanager.listener.AdRevenueListener

/** Listener'ları tutar ve tüm olayları main thread'de iletir. */
internal object EventCenter {

    @Volatile var eventListener: AdEventListener? = null
    @Volatile var revenueListener: AdRevenueListener? = null

    fun event(block: (AdEventListener) -> Unit) {
        val l = eventListener ?: return
        MainThread.run { block(l) }
    }

    fun revenue(revenue: AdRevenue) {
        val l = revenueListener ?: return
        MainThread.run { l.onAdRevenue(revenue) }
    }
}

/** AdMob rewarded server-side verification (SSV) ayarları. */
internal object SsvStore {
    @Volatile var userId: String? = null
    @Volatile var customData: String? = null
}
