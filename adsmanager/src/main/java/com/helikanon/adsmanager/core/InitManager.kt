package com.helikanon.adsmanager.core

import android.content.Context
import com.applovin.sdk.AppLovinMediationProvider
import com.applovin.sdk.AppLovinPrivacySettings
import com.applovin.sdk.AppLovinSdk
import com.applovin.sdk.AppLovinSdkInitializationConfiguration
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.helikanon.adsmanager.config.AdNetwork
import com.helikanon.adsmanager.config.AdsConfig

/**
 * AdMob ve AppLovin SDK'larını asenkron initialize eder.
 * Init tamamlanmadan gelen load/show istekleri kuyruklanır ve init bitince çalıştırılır.
 */
internal object InitManager {

    @Volatile
    var isReady: Boolean = false
        private set

    /** AppLovin kullanılabilir mi? (SDK key verilmemişse false; waterfall MAX adımlarını atlar.) */
    @Volatile
    var applovinUsable: Boolean = true
        private set

    private var initStarted = false
    private val pendingActions = mutableListOf<() -> Unit>()
    private var remainingNetworks = 0
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

            remainingNetworks = networks.size
            if (AdNetwork.ADMOB in networks) initAdMob(appContext, config)
            if (AdNetwork.APPLOVIN in networks) initAppLovin(appContext, config)

            // Güvenlik ağı: bir SDK'nın init callback'i hiç dönmezse kuyruğu sonsuza dek bekletme
            MainThread.runDelayed(20_000) {
                if (!isReady) {
                    AdsLogger.w("SDK init timed out after 20s; proceeding with pending actions")
                    markAllReady()
                }
            }
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

            MobileAds.initialize(context) { initStatus ->
                MainThread.run {
                    AdsLogger.i("AdMob initialized: ${initStatus.adapterStatusMap.keys.size} adapters")
                    networkReady()
                }
            }
        } catch (e: Exception) {
            AdsLogger.e("AdMob init failed", e)
            MainThread.run { networkReady() }
        }
    }

    private fun initAppLovin(context: Context, config: AdsConfig) {
        val sdkKey = config.applovinSdkKey
        if (sdkKey.isNullOrBlank()) {
            AdsLogger.e("AppLovin is in networkOrder but applovinSdkKey is not set! AppLovin will be skipped in waterfalls.")
            applovinUsable = false
            MainThread.run { networkReady() }
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
                    networkReady()
                }
            }
        } catch (e: Exception) {
            AdsLogger.e("AppLovin init failed", e)
            MainThread.run { networkReady() }
        }
    }

    private fun networkReady() {
        remainingNetworks--
        if (remainingNetworks <= 0) markAllReady()
    }

    private fun markAllReady() {
        if (isReady) return
        isReady = true
        AdsLogger.i("All ad SDKs ready, flushing ${pendingActions.size} pending action(s)")
        val actions = pendingActions.toList()
        pendingActions.clear()
        actions.forEach { it() }
        onAllReady?.invoke()
        onAllReady = null
    }

    /** Init bittiyse hemen, bitmediyse init sonrası main thread'de çalıştırır. */
    fun runWhenReady(action: () -> Unit) {
        MainThread.run {
            if (isReady) action() else pendingActions.add(action)
        }
    }
}
