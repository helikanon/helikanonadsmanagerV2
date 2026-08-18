# AdsManager — Android Reklam Gösterim Kütüphanesi

AdMob (pure, legacy GMA SDK) ve AppLovin MAX destekli, placement bazlı, waterfall mantıklı reklam yönetim kütüphanesi.

**Formatlar:** Interstitial, Rewarded, App Open, Banner (Standart / Adaptive / MREC). Native ileriki sürümde eklenecek.

**MAX adaptörleri:** DT Exchange (Fyber), AdMob (Google), Liftoff (Vungle), Meta, Mintegral, Pangle (ByteDance), Unity Ads.

---

## 1. Kurulum

### settings.gradle.kts (uygulama projenizde)

Mintegral ve Pangle adaptörleri için özel maven repoları gerekir:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://dl-maven-android.mintegral.com/repository/mbridge_android_sdk_oversea") }
        maven { url = uri("https://artifact.bytedance.com/repository/pangle") }
    }
}
```

### Modül olarak ekleme

Bu projeyi `helikanonlibs` altında modül olarak kullanıyorsanız:

```kotlin
// settings.gradle.kts
include(":adsmanager")
project(":adsmanager").projectDir = file("../helikanonlibs/claude_ads_manager/adsmanager")

// app/build.gradle.kts
dependencies {
    implementation(project(":adsmanager"))
}
```

### AndroidManifest.xml (uygulamanızda)

```xml
<meta-data
    android:name="com.google.android.gms.ads.APPLICATION_ID"
    android:value="ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY" />
```

AppLovin SDK key manifest'e değil, config'e yazılır (aşağıda).

Gereksinimler: minSdk 24, AGP 8.7+, Kotlin 2.0+, JDK 17.

### AppLovin Ad Review (opsiyonel)

Bu kütüphane için **zorunlu değildir**, reklam gösterimini etkilemez. MAX panelindeki
Ad Review servisini açar: uygulamanızda gerçekten hangi creative'in gösterildiğini
(ekran görüntüsü, ağ trafiği, advertiser/creative kimliği) görürsünüz ve sorunlu
reklamları tek tek engelleyebilirsiniz.

Mediation'da reklam Meta, Pangle, Mintegral, Unity gibi ağlardan gelir; ne
gösterildiğini normalde göremezsiniz. Ad Review'ın çözdüğü tipik sorunlar: uygulamayı
kilitleyen / kendiliğinden yönlendiren creative'ler, uygunsuz içerik, Play Store
politikasını ihlal eden reklamlar, rakip uygulama reklamları. Bunu sağlamak için
plugin build sırasında APK'yı enstrümante eder — bu yüzden ayrı bir Gradle plugin'i
olarak gelir, SDK'nın içinde değildir.

Plugin nihai APK'yı işlediği için **uygulama modülüne** eklenir, bu kütüphaneye değil:

```kotlin
// root build.gradle.kts
buildscript {
    repositories { maven { url = uri("https://artifacts.applovin.com/android") } }
    dependencies { classpath("com.applovin.quality:AppLovinQualityServiceGradlePlugin:+") }
}

// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("applovin-quality-service")
}

applovin {
    apiKey = "«ad-review-key»"
}
```

Buradaki `apiKey`, init'te verilen `applovinSdkKey` ile **aynı şey değildir**; MAX
panelinde Account > General > Keys altındaki ayrı Ad Review anahtarıdır. Repo public
ise anahtarı `local.properties`'ten okuyun.

Notlar:
- Build süresini uzatır (her build'de enstrümantasyon adımı çalışır).
- Plugin eski AGP variant API'lerine dayanır; uygulamanız AGP 9'a çıkarsa plugin'in
  güncel sürümüyle uyumu ayrıca doğrulanmalıdır.

---

## 2. Başlatma

`Application.onCreate` içinde:

```kotlin
val config = AdsConfig.build {
    adsEnabled = true
    applovinSdkKey = "APPLOVIN_SDK_KEY"

    interstitialMinIntervalSec = 45      // iki interstitial arası min süre
    appOpenMinGapSec = 30                // son inter/rewarded'dan sonra app open için min süre
    sessionMaxInterstitials = 0          // 0 = limitsiz
    firstInterstitialDelaySec = 0        // açılıştan ilk interstitial'a bekleme
    loadTimeoutSec = 20                  // network başına load timeout
    testMode = false
    debugLogging = false
    umpConsentEnabled = true             // GDPR/UMP akışını kütüphane yönetsin

    interstitial {
        enabled = true
        preload = true                               // önceden yükleme
        networkOrderString = "admob,applovin"        // waterfall sırası
        placement("inter_default",
            admobAdUnitId = "ca-app-pub-.../...",
            applovinAdUnitId = "MAX_AD_UNIT_ID")
        placement("inter_gameover",
            admobAdUnitId = "ca-app-pub-.../...")
        placement("inter_levelup",
            admobAdUnitId = "ca-app-pub-.../...",
            preload = false)                         // hiç otomatik yüklenmez, sadece manuel
        defaultPlacement = "inter_default"           // placement verilmezse bu kullanılır
        initialPreloadPlacements = listOf("inter_default")  // açılışta yüklenecekler; boşsa sadece default
    }

    rewarded {
        preload = true
        networkOrderString = "applovin,admob"
        placement("rew_default", admobAdUnitId = "...", applovinAdUnitId = "...")
    }

    appOpen {
        preload = true
        networkOrderString = "admob"
        placement("appopen_default", admobAdUnitId = "...")
    }

    banner {
        networkOrderString = "applovin,admob"
        placement("banner_default", admobAdUnitId = "...", applovinAdUnitId = "...")
    }
}

AdsManager.initialize(this, config)
```

### UMP consent (GDPR)

`umpConsentEnabled = true` ise ilk açılışta SDK'lar consent alınana kadar başlatılmaz.
İlk Activity'de (örn. Splash):

```kotlin
AdsManager.gatherConsent(this) { canRequestAds ->
    // form gösterildiyse kapandı; SDK'lar başlatıldı, preload'lar tetiklendi
}
```

Sonraki açılışlarda consent kayıtlı olduğu için `initialize` SDK'ları hemen başlatır;
`gatherConsent` yine çağrılabilir (Google her açılışta güncellemeyi önerir, form gerekmedikçe görünmez).

Ayarlar ekranı için: `AdsManager.isPrivacyOptionsRequired(context)` ve `AdsManager.showPrivacyOptionsForm(activity)`.

---

## 3. Kullanım

### Interstitial

```kotlin
AdsManager.loadInterstitial()                        // default placement (preload açıksa gerek yok)
AdsManager.loadInterstitial("inter_gameover")

AdsManager.isInterstitialReady()                     // Boolean

AdsManager.showInterstitial(activity)                // süre + frekans kontrollü
AdsManager.showInterstitial(activity, "inter_gameover")
AdsManager.showInterstitialWithoutTimeCheck(activity) // min aralık / ilk gecikme kontrolü YOK
```

`show*` metodları gösterim başladıysa `true` döner. Reklam hazır değilse `false` döner ve arka planda load tetikler.

### Rewarded

```kotlin
AdsManager.showRewarded(activity) { reward ->
    // ödülü ver: reward.amount, reward.type
}
```

### App Open

Manuel:

```kotlin
AdsManager.showAppOpen(activity)
```

Otomatik (foreground'a dönüşte):

```kotlin
AdsManager.enableAppOpenAutoShow(
    application = this,
    excludedActivities = setOf(SplashActivity::class.java, PurchaseActivity::class.java),
    excludePredicate = { activity -> /* dinamik engel */ false }
)
```

Kurallar her iki modda da geçerli: başka fullscreen reklam açıkken gösterilmez ve
son interstitial/rewarded üzerinden `appOpenMinGapSec` geçmeden gösterilmez.

### Banner

```kotlin
val controller = AdsManager.loadBanner(
    activity = this,
    container = findViewById(R.id.bannerContainer),
    type = BannerType.ADAPTIVE,       // STANDARD | ADAPTIVE | MREC
    placementId = null                 // null -> default placement
)
// Activity ComponentActivity/AppCompatActivity ise pause/resume/destroy otomatik.
// Değilse: controller?.pause() / resume() / destroy()
```

### Listener'lar

```kotlin
AdsManager.setEventListener(object : AdEventListener {
    override fun onAdShown(info: AdInfo) {}
    override fun onAdDismissed(info: AdInfo) {}
    override fun onAdLoadFailed(format: AdFormat, placementId: String, error: String) {}
    // onAdLoaded, onAdShowFailed, onAdClicked, onRewardEarned ...
})

AdsManager.setRevenueListener { rev ->
    // ILRD: rev.revenue (USD), rev.network, rev.mediatedNetworkName, rev.precision
    // Firebase/Adjust/AppsFlyer'a buradan iletin
}

AdsManager.setLogListener { level, message -> /* hataları analytics'e gönderin */ }
```

### Remote Config ile JSON config

Şu alanlar **JSON'dan okunmaz**, sadece koddan verilir (JSON'da bulunsalar bile yok sayılır):
`applovinSdkKey`, `testMode`, `admobTestDeviceIds`, `maxTestDeviceAdvertisingIds`,
`childDirected`, `doNotSell`. Bunları `localSettings` bloğunda verin — blok JSON okunduktan
sonra uygulanır ve `updateConfig` çağrılarında otomatik olarak tekrar uygulanır.

```kotlin
AdsManager.initialize(context, jsonString, localSettings = {
    applovinSdkKey = BuildConfig.APPLOVIN_SDK_KEY
    testMode = BuildConfig.DEBUG
    admobTestDeviceIds = listOf("TEST_DEVICE_ID")
    childDirected = false
    doNotSell = userOptedOutOfSale
})

AdsManager.updateConfig(jsonString)   // fetch sonrası; local ayarlar korunur, yüklü reklamlar durur
```

JSON şeması (tam örnek `AdsConfigJsonParser` dokümantasyonunda):

```json
{
  "adsEnabled": true,
  "umpConsentEnabled": true,
  "interstitialMinIntervalSec": 45,
  "appOpenMinGapSec": 30,
  "interstitial": {
    "enabled": true,
    "preload": true,
    "networkOrder": "admob,applovin",
    "defaultPlacement": "inter_default",
    "initialPreloadPlacements": ["inter_default"],
    "placements": {
      "inter_default": { "admob": "ca-app-pub-.../...", "applovin": "MAX_ID" },
      "inter_levelup": { "admob": "ca-app-pub-.../...", "preload": false }
    }
  }
}
```

> Not: Network seti (AdMob/AppLovin kullanımı) init sırasında belirlenir. `updateConfig` ile
> sıra/limit/enable değiştirin; o oturumda hiç kullanılmamış bir network'ü sonradan eklemeyin.

### Diğer

```kotlin
AdsManager.showMediationDebugger(context)   // MAX adaptör kurulum testi
AdsManager.openAdInspector(context)         // AdMob Ad Inspector
AdsManager.setRewardedSsvOptions(userId, customData)  // AdMob rewarded SSV
```

---

## 4. Davranış Kuralları (özet)

- **Waterfall:** Format bazlı `networkOrder` sırasıyla denenir. Bir network başarılı yüklerse diğerleri denenmez; fail/timeout olursa sıradakine geçilir.
- **Placement bazlı saklama:** Her placement için ayrı reklam tutulur. `load/show`'da placement verilmezse `defaultPlacement` kullanılır.
- **Preload (3 kademeli):** Format `preload = true` ana anahtardır. `initialPreloadPlacements` açılışta hangi placement'ların yükleneceğini belirler (boşsa sadece `defaultPlacement`). Placement bazlı `preload = false` ise o placement otomatik preload'a hiç katılmaz — ne açılışta ne gösterim sonrası — yalnızca `load...()` ile veya hazır değilken yapılan `show...()` çağrısıyla yüklenir. Otomatik preload açık olan placement'larda tüm waterfall fail olursa exponential backoff (8s → 16s → ... → 64s) ile retry yapılır.
- **Expire:** Yüklenen reklam `adTtlMinutes` (varsayılan 55 dk) sonra geçersiz sayılır ve çöpe atılır; preload açıksa yenisi yüklenir.
- **Tek fullscreen kuralı:** Herhangi bir fullscreen reklam açıkken ikinci bir `show` çağrısı reddedilir.
- **App open koruması:** Interstitial/rewarded açıkken app open gösterilmez; son inter/rewarded gösteriminden `appOpenMinGapSec` geçmeden app open gösterilmez.
- **Offline:** Bağlantı yokken load bekletilir, bağlantı gelince otomatik tetiklenir.
- **Init kuyruğu:** SDK init'i bitmeden yapılan çağrılar kuyruklanır, init bitince çalışır.
- **Kill switch:** `adsEnabled` (global) → format `enabled` → placement `enabled` hiyerarşisiyle kapatma.
- Tüm callback'ler main thread'de çağrılır.

---

## 5. Demo

`demo` modülü AdMob'un resmi test ad unit'leriyle çalışır. AppLovin'i test etmek için
`DemoApplication` içine SDK key'inizi ve MAX ad unit id'lerinizi girin,
`networkOrderString`'i `"admob,applovin"` yapın.

---

## 6. Notlar

- AdMob **legacy** SDK (`play-services-ads:25.3.0`) kullanılır; mediation özelliği kullanılmaz (pure AdMob). 25.3.0 seçildi çünkü AppLovin `google-adapter:25.3.0.0` bu sürümle sertifikalı — GMA'yı yükseltirken google-adapter'ı da aynı majöre yükseltin.
- `google-adapter` sürümü sabitlendi; diğer MAX adaptörleri AppLovin'in önerdiği gibi `+` (en güncel) ile bağlanır. Sabitlemek isterseniz `adsmanager/build.gradle.kts` içinde değiştirin.
- **Aynı MAX rewarded ad unit id'sini birden fazla placement'ta kullanmayın** — `MaxRewardedAd` ad unit başına singleton'dır; kütüphane gösterim olaylarını doğru wrapper'a yönlendirir ama aynı anda iki placement'a aynı unit ile load etmek kaçınılması gereken bir kullanım.
- MAX adaptive banner için legacy `adaptive_banner` extra parametresi kullanılır; AppLovin bunu 13.x'te hâlâ destekler. İleride kaldırılırsa `MaxAdViewConfiguration.setAdaptiveType(ANCHORED)` API'sine geçin.
- Bu kod cloud ortamında derlenemedi (Google Maven erişimi yok). İlk `gradle sync` / build sırasında ufak derleme düzeltmeleri gerekebilir; hata çıktısını paylaşmanız yeterli.
