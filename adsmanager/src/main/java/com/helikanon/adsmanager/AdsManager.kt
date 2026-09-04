package com.helikanon.adsmanager

import android.app.Activity
import android.app.Application
import java.util.concurrent.atomic.AtomicBoolean
import android.content.Context
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import com.applovin.sdk.AppLovinSdk
import com.google.android.gms.ads.MobileAds
import com.helikanon.adsmanager.appopen.AppOpenAutoShow
import com.helikanon.adsmanager.banner.BannerAdController
import com.helikanon.adsmanager.banner.BannerCache
import com.helikanon.adsmanager.banner.BannerLoader
import com.helikanon.adsmanager.config.AdFormat
import com.helikanon.adsmanager.config.AdsConfig
import com.helikanon.adsmanager.config.AdsConfigJsonParser
import com.helikanon.adsmanager.config.BannerType
import com.helikanon.adsmanager.consent.ConsentManager
import com.helikanon.adsmanager.core.AdRepository
import com.helikanon.adsmanager.core.AdsLogger
import com.helikanon.adsmanager.core.ConnectivityMonitor
import com.helikanon.adsmanager.core.EventCenter
import com.helikanon.adsmanager.core.FrequencyManager
import com.helikanon.adsmanager.core.FullscreenGate
import com.helikanon.adsmanager.core.FullscreenGateWatchdog
import com.helikanon.adsmanager.core.InitManager
import com.helikanon.adsmanager.core.LoadedFullscreenAd
import com.helikanon.adsmanager.core.MainThread
import com.helikanon.adsmanager.core.RetryScheduler
import com.helikanon.adsmanager.core.SsvStore
import com.helikanon.adsmanager.core.WaterfallLoader
import com.helikanon.adsmanager.listener.AdEventListener
import com.helikanon.adsmanager.listener.AdInfo
import com.helikanon.adsmanager.listener.AdRevenueListener
import com.helikanon.adsmanager.listener.AdShowCallbacks
import com.helikanon.adsmanager.listener.AdsLogListener
import com.helikanon.adsmanager.listener.RewardItem

/**
 * Kütüphanenin ana giriş noktası.
 *
 * Kullanım:
 * ```kotlin
 * // Application.onCreate içinde:
 * val config = AdsConfig.build {
 *     applovinSdkKey = "SDK_KEY"
 *     interstitialMinIntervalSec = 45
 *     interstitial {
 *         preload = true
 *         networkOrderString = "admob,applovin"
 *         placement("inter_default", admobAdUnitId = "...", applovinAdUnitId = "...")
 *     }
 * }
 * AdsManager.initialize(this, config)
 *
 * // Gösterim:
 * AdsManager.showInterstitial(activity)                  // default placement, süre kontrollü
 * AdsManager.showInterstitial(activity, "inter_gameover")
 * ```
 */
object AdsManager {

    @Volatile
    private var config: AdsConfig? = null
    private var appContext: Context? = null
    private var sdksStarted = false

    /**
     * JSON config'e her parse sonrası uygulanan, koddan verilen ayarlar
     * (SDK key, test modu, gizlilik bayrakları). [initialize] ile saklanır ki
     * sonraki [updateConfig] JSON güncellemelerinde de korunsun.
     */
    private var localSettings: (AdsConfig.Builder.() -> Unit)? = null

    val isInitialized: Boolean get() = config != null

    val currentConfig: AdsConfig? get() = config

    // ================================================================ init

    /**
     * Kütüphaneyi başlatır. Application.onCreate içinde çağrılmalıdır.
     *
     * `umpConsentEnabled = true` ise SDK'lar consent akışı tamamlanana kadar başlatılmaz;
     * ilk Activity'de [gatherConsent] çağrılmalıdır. (Sonraki açılışlarda consent
     * kayıtlı olduğundan SDK'lar hemen başlar.)
     */
    @JvmStatic
    @JvmOverloads
    fun initialize(context: Context, config: AdsConfig, onReady: (() -> Unit)? = null) {
        val appCtx = context.applicationContext
        this.appContext = appCtx
        this.config = config

        AdsLogger.debugEnabled = config.debugLogging
        AdsLogger.i("AdsManager initializing (adsEnabled=${config.adsEnabled})")

        FrequencyManager.onSessionStart()
        ConnectivityMonitor.start(appCtx)
        installFullscreenGateWatchdog(appCtx)

        if (!config.adsEnabled) {
            AdsLogger.w("Ads are disabled by config; SDKs will not be initialized")
            return
        }

        if (config.umpConsentEnabled && !ConsentManager.canRequestAds(appCtx)) {
            AdsLogger.i("UMP consent required; waiting for gatherConsent() before starting SDKs")
            return
        }

        startSdks(onReady)
    }

    /**
     * JSON config ile başlatma (ör. Firebase Remote Config değeri).
     *
     * `applovinSdkKey`, `testMode`, `admobTestDeviceIds`, `maxTestDeviceAdvertisingIds`,
     * `childDirected` ve `doNotSell` JSON'dan okunmaz; [localSettings] bloğunda verilmelidir.
     * Blok saklanır ve sonraki [updateConfig] JSON güncellemelerinde tekrar uygulanır.
     *
     * ```kotlin
     * AdsManager.initialize(this, remoteJson, localSettings = {
     *     applovinSdkKey = BuildConfig.APPLOVIN_SDK_KEY
     *     testMode = BuildConfig.DEBUG
     * })
     * ```
     */
    @JvmStatic
    @JvmOverloads
    fun initialize(
        context: Context,
        configJson: String,
        localSettings: (AdsConfig.Builder.() -> Unit)? = null,
        onReady: (() -> Unit)? = null
    ) {
        this.localSettings = localSettings
        val parsed = AdsConfigJsonParser.parseOrNull(configJson, localSettings)
        if (parsed == null) {
            AdsLogger.e("initialize failed: config JSON could not be parsed")
            return
        }
        initialize(context, parsed, onReady)
    }

    private fun startSdks(onReady: (() -> Unit)? = null) {
        val ctx = appContext ?: return
        val cfg = config ?: return
        if (sdksStarted) {
            onReady?.invoke()
            return
        }
        sdksStarted = true
        InitManager.initialize(ctx, cfg) {
            preloadAll()
            onReady?.invoke()
        }
    }

    /**
     * Config'i çalışma anında günceller (ör. Remote Config fetch sonrası).
     * Yüklü reklamlar korunur; yeni preload ayarları hemen uygulanır.
     *
     * Not: Yeni config'te ilk kez kullanılan bir network varsa SDK'sı bu oturumda
     * initialize edilmemiş olabilir; network setini init sırasında sabitlemeniz önerilir.
     */
    @JvmStatic
    fun updateConfig(newConfig: AdsConfig) {
        this.config = newConfig
        AdsLogger.debugEnabled = newConfig.debugLogging
        AdsLogger.i("Config updated")
        // Yeni config'te kapatılan banner'lar cache'ten destroy edilir (container'lar boşalır)
        MainThread.run { BannerCache.applyConfig(newConfig) }
        if (!newConfig.adsEnabled) return
        if (sdksStarted) {
            preloadAll()
        } else if (!newConfig.umpConsentEnabled || ConsentManager.canRequestAds(appContext ?: return)) {
            startSdks()
        }
    }

    /**
     * JSON ile config güncelleme. Parse başarısızsa false döner ve mevcut config korunur.
     *
     * [initialize] sırasında verilen local ayar bloğu (SDK key, test modu, gizlilik
     * bayrakları) otomatik olarak yeniden uygulanır; ayrı bir blok vermek isterseniz
     * [localSettings] parametresini kullanın.
     */
    @JvmStatic
    @JvmOverloads
    fun updateConfig(
        configJson: String,
        localSettings: (AdsConfig.Builder.() -> Unit)? = this.localSettings
    ): Boolean {
        val parsed = AdsConfigJsonParser.parseOrNull(configJson, localSettings) ?: return false
        updateConfig(parsed)
        return true
    }

    /**
     * Mevcut config'in yalnızca istenen alanlarını günceller; verilmeyen alanlar aynen korunur.
     * Tüm JSON'u yeniden vermeye gerek kalmadan tek bir değeri değiştirmek için kullanılır:
     *
     * ```kotlin
     * AdsManager.updateConfig { appOpenMinIntervalSec = 60 }
     * AdsManager.updateConfig {
     *     interstitialMinIntervalSec = 45
     *     interstitial { preload = false }
     * }
     * ```
     *
     * Notlar:
     * - Kütüphane initialize edilmemişse çağrı yok sayılır ve `false` döner.
     * - Sonraki bir JSON'lu [updateConfig] çağrısı config'i sıfırdan kurduğundan burada
     *   yapılan kısmi değişiklikleri ezer; kalıcı olması gereken ayarları `localSettings`
     *   bloğunda verin.
     * - `applovinSdkKey`, `testMode`, `umpConsentEnabled` gibi init sırasında kullanılan
     *   alanların çalışma anında değiştirilmesinin etkisi yoktur.
     */
    @JvmStatic
    fun updateConfig(block: AdsConfig.Builder.() -> Unit): Boolean {
        val current = config
        if (current == null) {
            AdsLogger.e("updateConfig(block) ignored: AdsManager is not initialized")
            return false
        }
        updateConfig(current.toBuilder().apply(block).build())
        return true
    }

    // ============================================================ listeners

    @JvmStatic
    fun setEventListener(listener: AdEventListener?) {
        EventCenter.eventListener = listener
    }

    @JvmStatic
    fun setRevenueListener(listener: AdRevenueListener?) {
        EventCenter.revenueListener = listener
    }

    @JvmStatic
    fun setLogListener(listener: AdsLogListener?) {
        AdsLogger.listener = listener
    }

    /** AdMob rewarded server-side verification için userId / customData. */
    @JvmStatic
    fun setRewardedSsvOptions(userId: String?, customData: String?) {
        SsvStore.userId = userId
        SsvStore.customData = customData
    }

    // ============================================================== consent

    /**
     * GDPR (UMP) consent akışını çalıştırır; gerekiyorsa consent formu gösterilir.
     * Akış bitince (veya consent zaten varsa) SDK'lar başlatılır ve preload'lar tetiklenir.
     */
    @JvmStatic
    @JvmOverloads
    fun gatherConsent(activity: Activity, onComplete: ((canRequestAds: Boolean) -> Unit)? = null) {
        val cfg = config ?: run {
            AdsLogger.e("gatherConsent called before initialize")
            onComplete?.invoke(false)
            return
        }
        if (!cfg.umpConsentEnabled) {
            startSdks()
            onComplete?.invoke(true)
            return
        }
        ConsentManager.gatherConsent(activity, cfg.testMode, cfg.admobTestDeviceIds) { canRequest ->
            MainThread.run {
                AdsLogger.i("Consent flow complete, canRequestAds=$canRequest")
                if (canRequest && cfg.adsEnabled) startSdks()
                onComplete?.invoke(canRequest)
            }
        }
    }

    @JvmStatic
    fun isPrivacyOptionsRequired(context: Context): Boolean =
        ConsentManager.isPrivacyOptionsRequired(context)

    @JvmStatic
    @JvmOverloads
    fun showPrivacyOptionsForm(activity: Activity, onDismissed: (() -> Unit)? = null) {
        ConsentManager.showPrivacyOptionsForm(activity, onDismissed)
    }

    /** Sadece test amaçlı: UMP consent durumunu sıfırlar. */
    @JvmStatic
    fun resetConsentForTesting(context: Context) = ConsentManager.reset(context)

    // ========================================================= interstitial

    @JvmStatic
    @JvmOverloads
    fun loadInterstitial(placementId: String? = null) =
        loadFullscreen(AdFormat.INTERSTITIAL, placementId)

    @JvmStatic
    @JvmOverloads
    fun isInterstitialReady(placementId: String? = null): Boolean =
        isFullscreenReady(AdFormat.INTERSTITIAL, placementId)

    /** Süre/frekans kontrolleriyle interstitial gösterir. Gösterim başladıysa true döner. */
    @JvmStatic
    @JvmOverloads
    fun showInterstitial(
        activity: Activity,
        placementId: String? = null,
        callbacks: AdShowCallbacks? = null
    ): Boolean =
        showFullscreen(AdFormat.INTERSTITIAL, activity, placementId, ignoreTimeChecks = false, onReward = null, callbacks = callbacks)

    /** Minimum aralık ve ilk gecikme kontrollerini ATLAYARAK interstitial gösterir. */
    @JvmStatic
    @JvmOverloads
    fun showInterstitialWithoutTimeCheck(
        activity: Activity,
        placementId: String? = null,
        callbacks: AdShowCallbacks? = null
    ): Boolean =
        showFullscreen(AdFormat.INTERSTITIAL, activity, placementId, ignoreTimeChecks = true, onReward = null, callbacks = callbacks)

    // ============================================================= rewarded

    @JvmStatic
    @JvmOverloads
    fun loadRewarded(placementId: String? = null) =
        loadFullscreen(AdFormat.REWARDED, placementId)

    @JvmStatic
    @JvmOverloads
    fun isRewardedReady(placementId: String? = null): Boolean =
        isFullscreenReady(AdFormat.REWARDED, placementId)

    /**
     * Rewarded reklam gösterir. Ödül kazanıldığında [onReward] çağrılır.
     * Gösterim başladıysa true döner.
     */
    @JvmStatic
    @JvmOverloads
    fun showRewarded(
        activity: Activity,
        placementId: String? = null,
        callbacks: AdShowCallbacks? = null,
        onReward: (RewardItem) -> Unit
    ): Boolean =
        showFullscreen(AdFormat.REWARDED, activity, placementId, ignoreTimeChecks = false, onReward = onReward, callbacks = callbacks)

    // ============================================================= app open

    @JvmStatic
    @JvmOverloads
    fun loadAppOpen(placementId: String? = null) =
        loadFullscreen(AdFormat.APP_OPEN, placementId)

    @JvmStatic
    @JvmOverloads
    fun isAppOpenReady(placementId: String? = null): Boolean =
        isFullscreenReady(AdFormat.APP_OPEN, placementId)

    /**
     * App open reklam gösterir. Kurallar:
     * - Başka bir fullscreen reklam (interstitial/rewarded/app open) açıkken gösterilmez.
     * - Son interstitial/rewarded gösteriminden itibaren `appOpenMinGapSec` geçmemişse gösterilmez.
     * - Son app open gösteriminden itibaren `appOpenMinIntervalSec` geçmemişse gösterilmez (0 = limitsiz).
     */
    @JvmStatic
    @JvmOverloads
    fun showAppOpen(
        activity: Activity,
        placementId: String? = null,
        callbacks: AdShowCallbacks? = null
    ): Boolean =
        showFullscreen(AdFormat.APP_OPEN, activity, placementId, ignoreTimeChecks = false, onReward = null, callbacks = callbacks)

    /**
     * Uygulama foreground'a döndüğünde app open reklamını otomatik gösterir.
     * Application.onCreate içinde çağırın.
     *
     * @param excludedActivities Bu Activity sınıfları öndeyken gösterim yapılmaz (ör. Splash, satın alma).
     * @param excludePredicate true dönerse gösterim yapılmaz (dinamik kontrol için).
     */
    @JvmStatic
    @JvmOverloads
    fun enableAppOpenAutoShow(
        application: Application,
        placementId: String? = null,
        showOnColdStart: Boolean = false,
        excludedActivities: Set<Class<out Activity>> = emptySet(),
        excludePredicate: ((Activity) -> Boolean)? = null
    ) {
        AppOpenAutoShow.enable(application, placementId, showOnColdStart, excludedActivities, excludePredicate)
    }

    @JvmStatic
    fun disableAppOpenAutoShow() = AppOpenAutoShow.disable()

    // =============================================================== banner

    /**
     * Banner yükler ve [container] içine ekler. Waterfall sırasına göre network denenir.
     *
     * Aynı activity + placement + type için canlı bir controller varsa yeni istek atılmaz;
     * yüklü reklamın host'u yeni container'a taşınır ve mevcut controller döner (tab/fragment
     * geçişlerinde anında gösterim). Tip değiştiyse eski controller destroy edilip yenisi
     * yüklenir. `reuseBannersAcrossActivities = true` ise cache activity'den bağımsızdır.
     *
     * @param type STANDARD (320x50), ADAPTIVE (önerilen) veya MREC (300x250)
     * @return Banner'ı yönetmek için controller; config/placement geçersizse null.
     *         Activity bir LifecycleOwner ise pause/resume/destroy otomatiktir.
     */
    @JvmStatic
    @JvmOverloads
    fun loadBanner(
        activity: Activity,
        container: ViewGroup,
        type: BannerType = BannerType.ADAPTIVE,
        placementId: String? = null
    ): BannerAdController? {
        val cfg = config ?: run { AdsLogger.e("loadBanner called before initialize"); return null }
        if (!cfg.isFormatEnabled(AdFormat.BANNER)) {
            AdsLogger.d("Banner disabled by config")
            return null
        }
        val formatConfig = cfg.format(AdFormat.BANNER) ?: return null
        val placement = formatConfig.placement(placementId)
        if (placement == null || !placement.enabled) {
            AdsLogger.w("Banner placement '${placementId ?: formatConfig.defaultPlacementId}' not found or disabled")
            return null
        }

        val shared = cfg.reuseBannersAcrossActivities
        val loadContext: Context = if (shared) activity.applicationContext else activity
        (activity.applicationContext as? Application)?.let { BannerCache.ensureLifecycleHook(it) }

        BannerCache.get(activity, placement.id, shared)?.let { cached ->
            if (cached.type == type) {
                AdsLogger.d("Reusing cached banner controller for '${placement.id}' (no new request)")
                cached.moveToContainer(container)
                (activity as? LifecycleOwner)?.let { cached.bindToLifecycle(it, destroyWithActivity = !shared) }
                // Retry sınırına takılıp boş kalmışsa: sayacı sıfırla, yeniden başlat
                if (!cached.isAdLoaded && !cached.isLoadActive) {
                    RetryScheduler.cancel(AdFormat.BANNER, placement.id)
                    cached.isLoadActive = true
                    InitManager.runWhenReady {
                        if (!cached.isDestroyed) {
                            BannerLoader.load(loadContext, cached, type, cfg, formatConfig, placement)
                        }
                    }
                }
                return cached
            }
            // Tip değişti (ör. ADAPTIVE -> MREC): eskisini at, yenisini yükle
            AdsLogger.d("Banner type changed for '${placement.id}' (${cached.type} -> $type); destroying old controller")
            cached.destroy()
        }

        val controller = BannerAdController(loadContext, placement.id, type)
        (activity as? LifecycleOwner)?.let { controller.bindToLifecycle(it, destroyWithActivity = !shared) }
        BannerCache.put(activity, controller, shared)
        controller.moveToContainer(container)

        // Yeni loadBanner çağrısı retry sayacını sıfırlar
        RetryScheduler.cancel(AdFormat.BANNER, placement.id)
        controller.isLoadActive = true
        InitManager.runWhenReady {
            if (!controller.isDestroyed) {
                BannerLoader.load(loadContext, controller, type, cfg, formatConfig, placement)
            }
        }
        return controller
    }

    /**
     * Cache'teki banner controller'larını destroy eder ve container'larını boşaltır.
     *
     * @param placementId null ise TÜM banner'lar destroy edilir; değilse yalnızca o placement.
     */
    @JvmStatic
    @JvmOverloads
    fun destroyBanner(placementId: String? = null) {
        MainThread.run { BannerCache.destroy(placementId) }
    }

    // ================================================================ debug

    /** AppLovin MAX Mediation Debugger ekranını açar (adaptör kurulumlarını test etmek için). */
    @JvmStatic
    fun showMediationDebugger(context: Context) {
        try {
            AppLovinSdk.getInstance(context.applicationContext).showMediationDebugger()
        } catch (e: Exception) {
            AdsLogger.e("showMediationDebugger failed", e)
        }
    }

    /** AdMob Ad Inspector ekranını açar. UI gösterdiği için Activity context'i geçirin. */
    @JvmStatic
    fun openAdInspector(context: Context) {
        try {
            MobileAds.openAdInspector(context) { error ->
                if (error != null) AdsLogger.w("Ad Inspector closed with error: ${error.message}")
            }
        } catch (e: Exception) {
            AdsLogger.e("openAdInspector failed", e)
        }
    }

    // ======================================================= internal logic

    private fun loadFullscreen(format: AdFormat, placementId: String?, isAutoPreload: Boolean = false) {
        val cfg = config ?: run { AdsLogger.e("load called before initialize"); return }
        val ctx = appContext ?: return
        if (!cfg.isFormatEnabled(format)) {
            AdsLogger.d("$format disabled by config, skipping load")
            return
        }
        val formatConfig = cfg.format(format) ?: return
        val placement = formatConfig.placement(placementId)
        if (placement == null || !placement.enabled) {
            AdsLogger.w("$format placement '${placementId ?: formatConfig.defaultPlacementId}' not found or disabled")
            return
        }

        MainThread.run {
            // Zaten geçerli bir reklam varsa veya yükleme sürüyorsa tekrar yükleme
            if (AdRepository.hasValidAd(format, placement.id)) {
                AdsLogger.d("$format/${placement.id} already loaded, skipping")
                return@run
            }
            if (AdRepository.isLoading(format, placement.id)) {
                AdsLogger.d("$format/${placement.id} load already in progress, skipping")
                return@run
            }
            AdRepository.markLoading(format, placement.id)

            InitManager.runWhenReady {
                if (!ConnectivityMonitor.isOnline) {
                    AdsLogger.d("Offline; will load $format/${placement.id} when connection returns")
                    AdRepository.clearLoading(format, placement.id)
                    ConnectivityMonitor.addOnlineListenerOnce("$format:${placement.id}") {
                        loadFullscreen(format, placement.id, isAutoPreload)
                    }
                    return@runWhenReady
                }

                WaterfallLoader.loadFullscreen(ctx, cfg, formatConfig, placement) { ad, error ->
                    AdRepository.clearLoading(format, placement.id)
                    if (ad != null) {
                        RetryScheduler.onLoadSuccess(format, placement.id)
                        AdRepository.put(ad)
                        EventCenter.event {
                            it.onAdLoaded(AdInfo(format, placement.id, ad.network, ad.adUnitId))
                        }
                    } else {
                        val err = error ?: "unknown error"
                        EventCenter.event { it.onAdLoadFailed(format, placement.id, err) }
                        // Preload açıksa exponential backoff ile otomatik yeniden dene.
                        // reloadAfterShow=false olan tek kullanımlık placement'larda (ör. splash)
                        // retry döngüsü kurulmaz; o slot oturumda bir kez kullanıldığı için
                        // arka planda sonsuza dek request atmasının anlamı yok.
                        if (formatConfig.reloadsAfterShow(placement)) {
                            RetryScheduler.scheduleRetry(
                                format, placement.id, cfg.retryBaseDelaySec, cfg.retryMaxDelaySec
                            ) {
                                loadFullscreen(format, placement.id, isAutoPreload = true)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun isFullscreenReady(format: AdFormat, placementId: String?): Boolean {
        val cfg = config ?: return false
        if (!cfg.isFormatEnabled(format)) return false
        val placement = cfg.format(format)?.placement(placementId) ?: return false
        return AdRepository.hasValidAd(format, placement.id)
    }

    private fun showFullscreen(
        format: AdFormat,
        activity: Activity,
        placementId: String?,
        ignoreTimeChecks: Boolean,
        onReward: ((RewardItem) -> Unit)?,
        callbacks: AdShowCallbacks? = null
    ): Boolean {
        val cfg = config ?: run { AdsLogger.e("show called before initialize"); return false }
        if (!cfg.isFormatEnabled(format)) {
            AdsLogger.d("$format disabled by config")
            return false
        }
        val formatConfig = cfg.format(format) ?: return false
        val placement = formatConfig.placement(placementId)
        if (placement == null || !placement.enabled) {
            AdsLogger.w("$format placement '${placementId ?: formatConfig.defaultPlacementId}' not found or disabled")
            return false
        }

        // Tek fullscreen kuralı
        if (FullscreenGate.isShowing) {
            AdsLogger.d("Another fullscreen ad (${FullscreenGate.showingFormat}) is showing; $format denied")
            return false
        }

        // Frekans kuralları
        when (format) {
            AdFormat.INTERSTITIAL -> {
                val check = FrequencyManager.canShowInterstitial(cfg, ignoreTimeChecks)
                if (check is FrequencyManager.Check.Denied) {
                    AdsLogger.d("Interstitial denied: ${check.reason}")
                    return false
                }
            }

            AdFormat.APP_OPEN -> {
                val check = FrequencyManager.canShowAppOpen(cfg)
                if (check is FrequencyManager.Check.Denied) {
                    AdsLogger.d("AppOpen denied: ${check.reason}")
                    return false
                }
            }

            else -> Unit
        }

        val ad = AdRepository.get(format, placement.id)
        if (ad == null) {
            AdsLogger.d("$format/${placement.id} not ready; triggering load")
            loadFullscreen(format, placement.id)
            return false
        }

        val info = AdInfo(format, placement.id, ad.network, ad.adUnitId)

        // Gösterim oturumu tam olarak bir kez sonlanır: onDismissed, onShowFailed veya
        // kilidin zorla açılması. İlk sonlandıran kazanır; sonradan gelen (geç) terminal
        // callback'ler yalnızca loglanır. Ödül (onRewardEarned) bu kuralın dışındadır:
        // geç gelse bile kullanıcıya iletilir.
        val terminated = AtomicBoolean(false)
        val shown = AtomicBoolean(false)

        fun reloadAfterShow() {
            if (formatConfig.reloadsAfterShow(placement)) loadFullscreen(format, placement.id)
        }

        fun onForceReleased(reason: String) {
            if (!terminated.compareAndSet(false, true)) return
            // Adapter'a haber ver (ör. MAX rewarded "gösterimde" kaydını düşürsün).
            ad.onForceReleased()
            if (shown.get()) {
                AdsLogger.w("$format/${placement.id} treated as dismissed after force-release: $reason")
                EventCenter.event { it.onAdDismissed(info) }
                callbacks?.onDismissed?.let { cb -> MainThread.run { cb() } }
            } else {
                val error = "force-released before display: $reason"
                AdsLogger.w("$format/${placement.id} show failed: $error")
                EventCenter.event { it.onAdShowFailed(info, error) }
                callbacks?.onShowFailed?.let { cb -> MainThread.run { cb(error) } }
            }
            reloadAfterShow()
        }

        val gateTicket = FullscreenGate.tryAcquire(format, placement.id, ::onForceReleased) ?: return false
        FullscreenGateWatchdog.onShowRequested(activity)
        AdRepository.remove(format, placement.id)

        val showListener = object : LoadedFullscreenAd.ShowListener {
            override fun onShown(mediatedNetworkName: String?) {
                if (terminated.get()) {
                    AdsLogger.d("$format/${placement.id} late onShown ignored (session already terminated)")
                    return
                }
                shown.set(true)
                when (format) {
                    AdFormat.INTERSTITIAL -> FrequencyManager.recordInterstitialShown()
                    AdFormat.REWARDED -> FrequencyManager.recordRewardedShown()
                    AdFormat.APP_OPEN -> FrequencyManager.recordAppOpenShown()
                    else -> Unit
                }
                EventCenter.event { it.onAdShown(info) }
                callbacks?.onShown?.let { cb -> MainThread.run { cb() } }
            }

            override fun onShowFailed(error: String) {
                FullscreenGate.release(gateTicket)
                if (!terminated.compareAndSet(false, true)) {
                    AdsLogger.d("$format/${placement.id} late onShowFailed ignored: $error")
                    return
                }
                AdsLogger.w("$format/${placement.id} show failed: $error")
                EventCenter.event { it.onAdShowFailed(info, error) }
                callbacks?.onShowFailed?.let { cb -> MainThread.run { cb(error) } }
                ad.destroy()
                reloadAfterShow()
            }

            override fun onDismissed() {
                FullscreenGate.release(gateTicket)
                if (!terminated.compareAndSet(false, true)) {
                    AdsLogger.d("$format/${placement.id} late onDismissed ignored (session already terminated)")
                    return
                }
                EventCenter.event { it.onAdDismissed(info) }
                callbacks?.onDismissed?.let { cb -> MainThread.run { cb() } }
                // Gösterim sonrası otomatik re-preload
                reloadAfterShow()
            }

            override fun onClicked() {
                if (terminated.get()) return
                EventCenter.event { it.onAdClicked(info) }
            }

            override fun onRewardEarned(reward: RewardItem) {
                if (terminated.get()) {
                    AdsLogger.w("$format/${placement.id} reward arrived after session terminated; delivering anyway")
                }
                EventCenter.event { it.onRewardEarned(info, reward) }
                onReward?.let { MainThread.run { it(reward) } }
            }
        }

        try {
            ad.show(activity, showListener)
        } catch (t: Throwable) {
            // show senkron patlarsa gate kilitli kalmasın
            FullscreenGate.release(gateTicket)
            AdsLogger.e("$format/${placement.id} show threw", t)
            if (terminated.compareAndSet(false, true)) {
                val error = t.message ?: "show threw"
                EventCenter.event { it.onAdShowFailed(info, error) }
                callbacks?.onShowFailed?.let { cb -> MainThread.run { cb(error) } }
                ad.destroy()
                reloadAfterShow()
            }
            return false
        }
        return true
    }

    /**
     * Fullscreen kilidinin sonsuza dek dolu kalmasına karşı koruma. Reklam
     * activity'sinin üstüne uygulamanın kendi activity'si geldiğinde (bildirim
     * tıklaması vb.) network dismiss callback'i hiç gelmez; bekçi kilidi açar.
     * Zorla açılışın uygulamaya bildirilmesi ve yeniden yükleme, gösterimi
     * başlatan [showFullscreen] oturumunun kendi callback'inde yapılır.
     */
    private fun installFullscreenGateWatchdog(appCtx: Context) {
        (appCtx as? Application)?.let { FullscreenGateWatchdog.install(it) }
    }

    private fun preloadAll() {
        val cfg = config ?: return
        for (format in listOf(AdFormat.INTERSTITIAL, AdFormat.REWARDED, AdFormat.APP_OPEN)) {
            val fc = cfg.format(format) ?: continue
            if (!fc.enabled || !fc.preload) continue
            fc.initialPreloadPlacements().forEach { placement ->
                loadFullscreen(format, placement.id, isAutoPreload = true)
            }
        }
    }
}
