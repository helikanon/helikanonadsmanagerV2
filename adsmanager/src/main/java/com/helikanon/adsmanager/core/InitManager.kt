package com.helikanon.adsmanager.core

import android.content.Context
import android.content.pm.PackageManager
import com.applovin.sdk.AppLovinMediationProvider
import com.applovin.sdk.AppLovinPrivacySettings
import com.applovin.sdk.AppLovinSdk
import com.applovin.sdk.AppLovinSdkInitializationConfiguration
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.helikanon.adsmanager.config.AdNetwork
import com.helikanon.adsmanager.config.AdsConfig
import com.helikanon.adsmanager.mediation.MediationPrivacy

/**
 * AdMob ve AppLovin SDK'larını asenkron initialize eder.
 *
 * Hazır olma network bazındadır: waterfall adımları yalnızca kendi network'lerinin hazır
 * olmasını bekler (network'lü [runWhenReady]), yavaş açılan diğer SDK'yı beklemez.
 * [isReady] ve network'süz [runWhenReady] ise tüm SDK'ların init'inin bitmesini bekler.
 */
internal object InitManager {

    private val readiness = NetworkReadiness()

    /** Tüm SDK'ların init callback'i döndü mü (ya da güvenlik süresi doldu mu)? */
    val isReady: Boolean get() = readiness.allReady

    /** AppLovin kullanılabilir mi? (SDK key verilmemişse false; waterfall MAX adımlarını atlar.) */
    @Volatile
    var applovinUsable: Boolean = true
        private set

    /** adsmanager-admob ile AdMob mediation modu mu (manifest işareti)? [initialize]'da belirlenir. */
    @Volatile
    var admobMediation: Boolean = false
        private set

    private var initStarted = false

    /** Init callback'i henüz dönmemiş network'ler; boşalınca tüm SDK'lar hazırdır. */
    private val initPending = mutableSetOf<AdNetwork>()
    private var onAllReady: (() -> Unit)? = null

    fun initialize(context: Context, config: AdsConfig, onComplete: (() -> Unit)? = null) {
        MainThread.run {
            if (initStarted) {
                onComplete?.let { cb -> runWhenReady { cb() } }
                return@run
            }
            initStarted = true
            onAllReady = onComplete

            val appContext = context.applicationContext
            val networks = config.usedNetworks()
            if (networks.isEmpty()) {
                AdsLogger.w("No ad networks configured")
                markAllReady()
                return@run
            }

            admobMediation = AdNetwork.ADMOB in networks && usesAdMobMediation(appContext)
            initPending += networks
            if (config.testMode) warnMissingTestDeviceIds(config, networks)
            if (AdNetwork.ADMOB in networks) initAdMob(appContext, config)
            if (AdNetwork.APPLOVIN in networks) initAppLovin(appContext, config)

            // Güvenlik ağı: init callback'i hiç dönmeyen network kendi adımlarını ve tüm
            // SDK'ları bekleyen işleri sonsuza dek bekletmesin; süre dolunca hazır sayılır.
            // MAX adımları ise MAX init'i bitene kadar atlanır (loader'lar applovinInitialized'a bakar).
            // AdMob mediation'da GMA callback'i adaptör init'leri bitince ya da en geç 30 sn'de gelir;
            // ilk isteğe tüm adaptörler katılsın diye (Google'ın önerisi) süre bundan uzun tutulur.
            val timeoutMs = if (admobMediation) 35_000L else 20_000L
            MainThread.runDelayed(timeoutMs) {
                if (!isReady) {
                    initPending.forEach {
                        AdsLogger.w("$it SDK init timed out after ${timeoutMs / 1000}s; proceeding with pending actions")
                    }
                    markAllReady()
                }
            }
        }
    }

    /**
     * testMode aciksa ama test cihaz id'leri bos birakilmissa uyarir. Bu durumda cihaz
     * test cihazi sayilmaz: gercek reklam envanteri doner (kendi reklamina tiklamak
     * invalid traffic'tir). UMP tarafindaki karsiligi ConsentManager'da uyarilir.
     */
    private fun warnMissingTestDeviceIds(config: AdsConfig, networks: Set<AdNetwork>) {
        if (AdNetwork.ADMOB in networks && config.admobTestDeviceIds.isEmpty()) {
            AdsLogger.w(
                "testMode=true but admobTestDeviceIds is empty! This device is NOT a test device; " +
                    "AdMob will serve REAL ads. Clicking them counts as invalid traffic. " +
                    "Copy the id from the GMA log line: setTestDeviceIds(Arrays.asList(\"...\"))"
            )
        }
        if (AdNetwork.APPLOVIN in networks && config.maxTestDeviceAdvertisingIds.isEmpty()) {
            AdsLogger.w(
                "testMode=true but maxTestDeviceAdvertisingIds is empty! This device is NOT a test device; " +
                    "MAX will serve REAL ads. Use the device advertising id (GAID) shown in Mediation Debugger."
            )
        }
    }

    private fun initAdMob(context: Context, config: AdsConfig) {
        try {
            val reqConfig = RequestConfiguration.Builder().apply {
                if (config.admobTestDeviceIds.isNotEmpty()) {
                    setTestDeviceIds(config.admobTestDeviceIds)
                }
                config.childDirected?.let {
                    setTagForChildDirectedTreatment(
                        if (it) RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_TRUE
                        else RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_FALSE
                    )
                }
            }.build()
            MobileAds.setRequestConfiguration(reqConfig)
        } catch (e: Exception) {
            AdsLogger.e("AdMob request configuration failed", e)
        }

        // Mediation'da Google "satma" (CCPA) tercihini ağlara kendisi iletemez; her ağın API'si çağrılır
        val doNotSell = config.doNotSell
        val privacy = if (admobMediation && doNotSell != null) mediationPrivacy() else null
        val logOnMain: (String) -> Unit = { msg -> MainThread.run { AdsLogger.i(msg) } }

        // Google: MobileAds.initialize() arka plan thread'inde çağrılmalı
        Thread({
            if (privacy != null && doNotSell != null) privacy.beforeInit(context, doNotSell, logOnMain)
            try {
                MobileAds.initialize(context) { initStatus ->
                    MainThread.run {
                        AdsLogger.i("AdMob initialized: ${initStatus.adapterStatusMap.keys.size} adapters")
                        if (admobMediation) initStatus.adapterStatusMap.forEach { (name, status) ->
                            AdsLogger.d("AdMob adapter $name: ${status.initializationState} ${status.description}")
                        }
                        if (privacy != null && doNotSell != null) privacy.afterInit(context, doNotSell) { AdsLogger.i(it) }
                        initFinished(AdNetwork.ADMOB)
                    }
                }
            } catch (e: Exception) {
                MainThread.run {
                    AdsLogger.e("AdMob init failed", e)
                    initFinished(AdNetwork.ADMOB)
                }
            }
            // Mediation'sız AdMob'da yüklemeler init callback'ini beklemez (Google: beklemek yalnızca
            // mediation adapter'ları için gerekir); initialize() çağrıldıktan sonra hazır sayılır.
            // adsmanager-admob ile ise adaptörler ilk isteğe teklif verebilsin diye callback beklenir.
            if (!admobMediation) MainThread.run { readiness.markReady(AdNetwork.ADMOB) }
        }, "AdsManager-AdMob-init").start()
    }

    /** adsmanager-admob'un gizlilik kancası; bulunamazsa null (uyarı loglanır). */
    private fun mediationPrivacy(): MediationPrivacy? = try {
        Class.forName(MediationPrivacy.IMPLEMENTATION).getDeclaredConstructor().newInstance() as MediationPrivacy
    } catch (e: Exception) {
        AdsLogger.w("doNotSell is not forwarded: mediation privacy hook not found (${e.message})")
        null
    }

    /** adsmanager-admob modülü bu işareti manifest'e ekler (o modülün AndroidManifest.xml'i). */
    private fun usesAdMobMediation(context: Context): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData?.getBoolean("com.helikanon.adsmanager.ADMOB_MEDIATION") == true
    } catch (e: Exception) {
        AdsLogger.w("Could not read AdMob mediation flag from manifest: ${e.message}")
        false
    }

    private fun initAppLovin(context: Context, config: AdsConfig) {
        val sdkKey = config.applovinSdkKey
        if (sdkKey.isNullOrBlank()) {
            AdsLogger.e("AppLovin is in networkOrder but applovinSdkKey is not set! AppLovin will be skipped in waterfalls.")
            applovinUsable = false
            MainThread.run { initFinished(AdNetwork.APPLOVIN) }
            return
        }
        applovinUsable = true
        try {
            // CCPA
            config.doNotSell?.let { AppLovinPrivacySettings.setDoNotSell(it, context) }

            val initConfig = AppLovinSdkInitializationConfiguration.builder(sdkKey)
                .setMediationProvider(AppLovinMediationProvider.MAX)
                .apply {
                    if (config.maxTestDeviceAdvertisingIds.isNotEmpty()) {
                        setTestDeviceAdvertisingIds(config.maxTestDeviceAdvertisingIds)
                    }
                }
                .build()

            val sdk = AppLovinSdk.getInstance(context)
            sdk.settings.setVerboseLogging(config.debugLogging)
            sdk.initialize(initConfig) {
                MainThread.run {
                    AdsLogger.i("AppLovin MAX initialized")
                    initFinished(AdNetwork.APPLOVIN)
                }
            }
        } catch (e: Exception) {
            AdsLogger.e("AppLovin init failed", e)
            MainThread.run { initFinished(AdNetwork.APPLOVIN) }
        }
    }

    /** Network'ün init'i bitti (callback döndü, init hata verdi ya da network kullanılamıyor). */
    private fun initFinished(network: AdNetwork) {
        readiness.markReady(network)
        initPending.remove(network)
        if (initPending.isEmpty()) markAllReady()
    }

    private fun markAllReady() {
        if (isReady) return
        AdsLogger.i("All ad SDKs ready, flushing ${readiness.pendingCount} pending action(s)")
        readiness.markAllReady()
        onAllReady?.invoke()
        onAllReady = null
    }

    /** Tüm SDK'lar hazırsa hemen, değilse hepsi hazır olunca main thread'de çalıştırır. */
    fun runWhenReady(action: () -> Unit) {
        MainThread.run { readiness.runWhenReady(null, action) }
    }

    /** [network] hazırsa hemen, değilse yalnızca o network hazır olunca main thread'de çalıştırır. */
    fun runWhenReady(network: AdNetwork, action: () -> Unit) {
        MainThread.run { readiness.runWhenReady(network, action) }
    }

    /** [network] ile yükleme başlatılabilir mi? Main thread'den çağrılır. */
    fun isReady(network: AdNetwork): Boolean = readiness.isReady(network)

    /**
     * MAX init'i bitti mi? 20 sn zaman aşımında [isReady] true olsa da MAX'e init bitmeden istek
     * atılmaz: AppLovin bu yüklemeleri init'e kadar bekletip geç döndürüyor, load timeout'a
     * takılıp boşa gidiyorlardı. GMA ise init'ten önceki istekleri kendisi karşılar.
     */
    fun applovinInitialized(): Boolean = readiness.hasInitialized(AdNetwork.APPLOVIN)
}
