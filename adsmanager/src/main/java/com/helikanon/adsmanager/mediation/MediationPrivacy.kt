package com.helikanon.adsmanager.mediation

import android.content.Context

/**
 * AdMob mediation'da `doNotSell` (ABD eyalet gizlilik yasaları, CCPA) tercihini ağlara ileten kanca.
 * Google bu tercihi mediation ağlarına kendisi uygulayamaz; her ağın kendi API'si çağrılmalıdır.
 *
 * adsmanager-admob modülü [IMPLEMENTATION] adıyla uygular; core onu yalnızca AdMob mediation
 * modunda ve `doNotSell` verilmişse çağırır. Uygulamaların implement etmesi gerekmez.
 */
interface MediationPrivacy {

    /** GMA init'inden önce, arka plan thread'inde çağrılır. */
    fun beforeInit(context: Context, doNotSell: Boolean, log: (String) -> Unit)

    /** GMA init callback'inde, ilk reklam isteğinden önce main thread'de çağrılır. */
    fun afterInit(context: Context, doNotSell: Boolean, log: (String) -> Unit)

    companion object {
        const val IMPLEMENTATION = "com.helikanon.adsmanager.admob.AdMobMediationPrivacy"
    }
}
