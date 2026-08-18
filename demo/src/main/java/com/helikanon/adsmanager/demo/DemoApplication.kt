package com.helikanon.adsmanager.demo

import android.app.Application
import android.util.Log
import com.helikanon.adsmanager.AdsManager
import com.helikanon.adsmanager.config.AdFormat
import com.helikanon.adsmanager.config.AdNetwork
import com.helikanon.adsmanager.config.AdsConfig
import com.helikanon.adsmanager.listener.AdEventListener
import com.helikanon.adsmanager.listener.AdInfo
import com.helikanon.adsmanager.listener.RewardItem

/**
 * Referans demo: AdsConfig'in **tüm** parametreleri açıkça yazılmıştır; varsayılan
 * değerler yorumda belirtilmiştir. Amaç, kütüphaneyi entegre ederken hangi ayarların
 * bulunduğunu tek dosyada görebilmek.
 *
 * Reklamlar AdMob'un resmi TEST ad unit id'leriyle çalışır. AppLovin MAX'i denemek
 * için `applovinSdkKey`'i doldurup ilgili formatın network sırasına "applovin" ekleyin
 * ve placement'lara `applovinAdUnitId` verin.
 */
class DemoApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val config = AdsConfig.build {
            // ------------------------------------------------------ genel
            /** Global kill switch; false ise SDK'lar hiç başlatılmaz. (varsayılan: true) */
            adsEnabled = true

            /** MAX kullanılacaksa zorunlu. null ise AppLovin waterfall adımları atlanır. (varsayılan: null) */
            applovinSdkKey = null // örn: "«applovin-sdk-key»"

            // -------------------------------------------- gösterim kuralları
            /** İki interstitial arasındaki minimum süre. (varsayılan: 30) */
            interstitialMinIntervalSec = 15
            /** Son interstitial/rewarded sonrası app open için gereken minimum boşluk. (varsayılan: 30) */
            appOpenMinGapSec = 10
            /** Oturum başına maksimum interstitial; 0 = limitsiz. (varsayılan: 0) */
            sessionMaxInterstitials = 0
            /** Açılıştan ilk interstitial'a kadar bekleme; 0 = beklemesiz. (varsayılan: 0) */
            firstInterstitialDelaySec = 0

            // ------------------------------------------- yükleme / retry / ttl
            /** Bir network'ün load cevabı için beklenecek süre; aşılırsa sıradaki network. (varsayılan: 20) */
            loadTimeoutSec = 20
            /** Otomatik retry başlangıç gecikmesi, exponential backoff ile artar. (varsayılan: 8) */
            retryBaseDelaySec = 8
            /** Retry gecikmesinin tavanı. (varsayılan: 64) */
            retryMaxDelaySec = 64
            /** Yüklü fullscreen reklamın geçerlilik süresi (dakika); aşılırsa yeniden yüklenir. (varsayılan: 55) */
            adTtlMinutes = 55

            // ------------------------------------------------- test / logging
            /**
             * Test cihaz id'lerini ve UMP debug geography'sini devreye alır, debug log'u açar.
             * Tek başına test reklamı GETİRMEZ; aşağıdaki id listeleri boşsa cihaz test
             * cihazı sayılmaz. (varsayılan: false)
             */
            testMode = true
            /** testMode true ise zaten açılır. (varsayılan: false) */
            debugLogging = true
            /** Logcat'teki GMA satırından kopyalanır; UMP debug geography için de kullanılır. (varsayılan: boş) */
            admobTestDeviceIds = emptyList() // örn: listOf("33BE2250B43518CCDA7DE426D04EE231")
            /** Cihazın ham GAID'i; Mediation Debugger'da görünür. (varsayılan: boş) */
            maxTestDeviceAdvertisingIds = emptyList() // örn: listOf("f4e6a1b2-...")

            // ----------------------------------------------------- gizlilik
            /**
             * true ise initialize() SDK'ları başlatmaz; ilk Activity'de
             * AdsManager.gatherConsent(activity) çağrılmalıdır. Demo'nun kutudan
             * çıktığı gibi çalışması için kapalı. (varsayılan: false)
             */
            umpConsentEnabled = false
            /** COPPA: çocuklara yönelik uygulama işareti, AdMob'a iletilir. (varsayılan: null = belirtilmemiş) */
            childDirected = null
            /** CCPA "do not sell" işareti, AppLovin'e iletilir. (varsayılan: null = belirtilmemiş) */
            doNotSell = null

            // ----------------------------------------------------- formatlar
            interstitial {
                /** Format kapatılabilir. (varsayılan: true) */
                enabled = true
                /** Init sonrası ve her gösterimden sonra otomatik yükleme. (varsayılan: false) */
                preload = true
                /** Waterfall sırası — AdNetwork listesiyle. (varsayılan: [ADMOB, APPLOVIN]) */
                networkOrder = listOf(AdNetwork.ADMOB)
                /** placementId verilmeden yapılan çağrılarda kullanılır. (varsayılan: ilk eklenen placement) */
                defaultPlacement = "inter_default"
                /** Açılışta yüklenecekler; boşsa yalnızca defaultPlacement yüklenir. (varsayılan: boş) */
                initialPreloadPlacements = listOf("inter_default")

                placement(
                    id = "inter_default",
                    admobAdUnitId = "ca-app-pub-3940256099942544/1033173712",
                    applovinAdUnitId = null, // örn: "«max-interstitial-ad-unit-id»"
                    enabled = true,          // (varsayılan: true)
                    preload = true           // (varsayılan: true)
                )
                // Otomatik preload'a katılmayan, yalnızca manuel yüklenen placement örneği.
                placement(
                    id = "inter_level_end",
                    admobAdUnitId = "ca-app-pub-3940256099942544/1033173712",
                    preload = false
                )
            }

            rewarded {
                enabled = true
                preload = true
                /** Sırayı string ile vermenin karşılığı; networkOrder ile aynı işi yapar. */
                networkOrderString = "admob" // AppLovin eklenince: "admob,applovin"
                defaultPlacement = "rew_default"
                initialPreloadPlacements = listOf("rew_default")

                placement(
                    id = "rew_default",
                    admobAdUnitId = "ca-app-pub-3940256099942544/5224354917",
                    applovinAdUnitId = null,
                    enabled = true,
                    preload = true
                )
            }

            appOpen {
                enabled = true
                preload = true
                networkOrderString = "admob"
                defaultPlacement = "appopen_default"
                initialPreloadPlacements = listOf("appopen_default")

                placement(
                    id = "appopen_default",
                    admobAdUnitId = "ca-app-pub-3940256099942544/9257395921",
                    applovinAdUnitId = null,
                    enabled = true,
                    preload = true
                )
            }

            banner {
                enabled = true
                /** Banner'lar loadBanner() ile yüklenir; preload bayrağı banner'ı etkilemez. */
                preload = false
                networkOrderString = "admob"
                defaultPlacement = "banner_default"

                placement(
                    id = "banner_default",
                    admobAdUnitId = "ca-app-pub-3940256099942544/6300978111",
                    applovinAdUnitId = null,
                    enabled = true,
                    preload = true
                )
            }
        }

        // onReady: SDK init + ilk preload'lar tamamlandığında çağrılır (opsiyonel).
        AdsManager.initialize(this, config) {
            Log.d("Demo", "AdsManager hazır")
        }

        // Rewarded SSV (server-side verification) parametreleri; sunucu doğrulaması
        // kullanmıyorsanız gerekmez.
        AdsManager.setRewardedSsvOptions(userId = "demo-user-1", customData = "demo")

        // Foreground'a dönüşte otomatik app open gösterimi.
        AdsManager.enableAppOpenAutoShow(
            application = this,
            placementId = "appopen_default",              // (varsayılan: null = defaultPlacement)
            showOnColdStart = false,                      // (varsayılan: false)
            excludedActivities = emptySet(),              // örn: setOf(SplashActivity::class.java)
            excludePredicate = null                       // örn: { activity -> activity is PurchaseActivity }
        )

        AdsManager.setEventListener(object : AdEventListener {
            override fun onAdLoaded(info: AdInfo) {
                Log.d("Demo", "Loaded: $info")
            }

            override fun onAdLoadFailed(format: AdFormat, placementId: String, error: String) {
                Log.d("Demo", "Load failed: $format/$placementId -> $error")
            }

            override fun onAdShown(info: AdInfo) {
                Log.d("Demo", "Shown: $info")
            }

            override fun onAdShowFailed(info: AdInfo, error: String) {
                Log.d("Demo", "Show failed: $info -> $error")
            }

            override fun onAdDismissed(info: AdInfo) {
                Log.d("Demo", "Dismissed: $info")
            }

            override fun onAdClicked(info: AdInfo) {
                Log.d("Demo", "Clicked: $info")
            }

            override fun onRewardEarned(info: AdInfo, reward: RewardItem) {
                Log.d("Demo", "Reward: ${reward.amount} ${reward.type}")
            }
        })

        AdsManager.setRevenueListener { revenue ->
            Log.d("Demo", "Revenue: $${revenue.revenue} from ${revenue.network} (${revenue.mediatedNetworkName})")
        }

        // Kütüphane loglarını kendi log/analytics altyapınıza aktarmak için.
        AdsManager.setLogListener { level, message ->
            Log.d("Demo", "[$level] $message")
        }
    }

    /**
     * ÖRNEK — çağrılmıyor. JSON ile başlatmanın referansı; aşağıdaki JSON,
     * [com.helikanon.adsmanager.config.AdsConfigJsonParser] tarafından okunan **tüm**
     * anahtarları içerir. Gerçek kullanımda bu string Firebase Remote Config gibi bir
     * kaynaktan gelir.
     *
     * `applovinSdkKey`, `testMode`, `admobTestDeviceIds`, `maxTestDeviceAdvertisingIds`,
     * `childDirected` ve `doNotSell` JSON'dan **okunmaz** (JSON'a yazılsa bile yok
     * sayılır); yalnızca `localSettings` bloğundan verilir. Blok JSON'dan sonra
     * uygulandığı için koddan verilen değer her zaman kazanır.
     */
    @Suppress("unused")
    private fun initWithJsonExample() {
        val remoteJson = """
        {
          "adsEnabled": true,
          "debugLogging": false,
          "umpConsentEnabled": false,

          "interstitialMinIntervalSec": 30,
          "appOpenMinGapSec": 30,
          "sessionMaxInterstitials": 0,
          "firstInterstitialDelaySec": 0,

          "loadTimeoutSec": 20,
          "retryBaseDelaySec": 8,
          "retryMaxDelaySec": 64,
          "adTtlMinutes": 55,

          "interstitial": {
            "enabled": true,
            "preload": true,
            "networkOrder": "admob,applovin",
            "defaultPlacement": "inter_default",
            "initialPreloadPlacements": ["inter_default"],
            "placements": {
              "inter_default": {
                "admob": "ca-app-pub-3940256099942544/1033173712",
                "applovin": "«max-interstitial-ad-unit-id»",
                "enabled": true,
                "preload": true
              },
              "inter_level_end": {
                "admob": "ca-app-pub-3940256099942544/1033173712",
                "preload": false
              }
            }
          },

          "rewarded": {
            "enabled": true,
            "preload": true,
            "networkOrder": ["admob", "applovin"],
            "defaultPlacement": "rew_default",
            "initialPreloadPlacements": ["rew_default"],
            "placements": {
              "rew_default": {
                "admob": "ca-app-pub-3940256099942544/5224354917",
                "max": "«max-rewarded-ad-unit-id»",
                "enabled": true,
                "preload": true
              }
            }
          },

          "appOpen": {
            "enabled": true,
            "preload": true,
            "networkOrder": "admob",
            "defaultPlacement": "appopen_default",
            "initialPreloadPlacements": ["appopen_default"],
            "placements": {
              "appopen_default": {
                "admob": "ca-app-pub-3940256099942544/9257395921",
                "enabled": true,
                "preload": true
              }
            }
          },

          "banner": {
            "enabled": true,
            "preload": false,
            "networkOrder": "admob",
            "defaultPlacement": "banner_default",
            "initialPreloadPlacements": [],
            "placements": {
              "banner_default": {
                "admob": "ca-app-pub-3940256099942544/6300978111",
                "enabled": true,
                "preload": true
              }
            }
          }
        }
        """.trimIndent()

        AdsManager.initialize(
            context = this,
            configJson = remoteJson,
            localSettings = {
                // JSON'dan okunmayan, yalnızca koddan verilebilen alanlar:
                applovinSdkKey = null // örn: BuildConfig.APPLOVIN_SDK_KEY
                testMode = BuildConfig.DEBUG
                admobTestDeviceIds = emptyList()          // örn: listOf("33BE2250B43518CCDA7DE426D04EE231")
                maxTestDeviceAdvertisingIds = emptyList()  // örn: listOf("f4e6a1b2-...")
                childDirected = null
                doNotSell = null
            },
            onReady = { Log.d("Demo", "AdsManager hazır (JSON config)") }
        )

        // Remote Config yeniden fetch edildiğinde aynı JSON şemasıyla güncelleme:
        // AdsManager.updateConfig(newJson)   // localSettings bloğu otomatik tekrar uygulanır
    }
}
