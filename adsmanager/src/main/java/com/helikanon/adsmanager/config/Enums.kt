package com.helikanon.adsmanager.config

/** Desteklenen reklam ağları. */
enum class AdNetwork(val key: String) {
    ADMOB("admob"),
    APPLOVIN("applovin");

    companion object {
        fun fromKey(key: String): AdNetwork? = when (key.trim().lowercase()) {
            "admob", "google" -> ADMOB
            "applovin", "max" -> APPLOVIN
            else -> null
        }

        /** "admob,applovin" gibi virgüllü listeyi parse eder. */
        fun parseOrder(order: String): List<AdNetwork> =
            order.split(',').mapNotNull { fromKey(it) }.distinct()
    }
}

/** Desteklenen reklam formatları. (NATIVE ileriki sürümde eklenecek) */
enum class AdFormat(val key: String) {
    INTERSTITIAL("interstitial"),
    REWARDED("rewarded"),
    BANNER("banner"),
    APP_OPEN("appOpen");

    companion object {
        fun fromKey(key: String): AdFormat? = when (key.trim().lowercase()) {
            "interstitial" -> INTERSTITIAL
            "rewarded" -> REWARDED
            "banner" -> BANNER
            "appopen", "app_open" -> APP_OPEN
            else -> null
        }
    }
}

/** Banner gösterim tipleri. */
enum class BannerType {
    /** Sabit 320x50 banner */
    STANDARD,

    /** Ekran genişliğine uyumlu adaptive banner (önerilen) */
    ADAPTIVE,

    /** 300x250 medium rectangle */
    MREC
}
