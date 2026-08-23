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

JitPack üzerinden bağımlılık olarak alacaksanız `jitpack.io` reposunu da ekleyin:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://dl-maven-android.mintegral.com/repository/mbridge_android_sdk_oversea") }
        maven { url = uri("https://artifact.bytedance.com/repository/pangle") }
        // JitPack en sona eklenmeli
        maven { url = uri("https://jitpack.io") }
    }
}
```

### JitPack bağımlılığı olarak ekleme

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.github.helikanon:helikanonadsmanagerV2:1.2.0")
}
```

Sürüm, GitHub'da atılan git tag'inin adıdır. Belirli bir commit'i denemek için tag yerine
kısa commit hash'i, geliştirme sürümü için `main-SNAPSHOT` da kullanılabilir.

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
    appOpenMinIntervalSec = 0            // iki app open arası min süre (0 = limitsiz)
    sessionMaxInterstitials = 0          // 0 = limitsiz
    firstInterstitialDelaySec = 0        // açılıştan ilk interstitial'a bekleme
    loadTimeoutSec = 20                  // network başına load timeout
    maxBannerRetryCount = 5              // banner otomatik retry üst sınırı (0 = limitsiz)
    reuseBannersAcrossActivities = false // true: banner'lar activity'ler arası paylaşılır
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
        placement("inter_splash",                    // açılış/splash: önce AdMob, kısa timeout, tek kullanımlık
            admobAdUnitId = "ca-app-pub-.../...",
            applovinAdUnitId = "MAX_AD_UNIT_ID",
            networkOrder = listOf(AdNetwork.ADMOB, AdNetwork.APPLOVIN),  // format sırasını ezer
            reloadAfterShow = false,                 // gösterimden sonra tekrar yüklenmez
            loadTimeoutSec = 8)                      // bu placement için network başına timeout
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
son interstitial/rewarded üzerinden `appOpenMinGapSec` ve son app open üzerinden
`appOpenMinIntervalSec` geçmeden gösterilmez.

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

AdsManager.destroyBanner()                   // tüm banner'ları destroy eder
AdsManager.destroyBanner("banner_default")   // sadece bu placement'ı
```

Davranış:

- **Yeniden kullanım (reattach):** Reklam, kütüphanenin yarattığı kalıcı bir host view'ına
  yüklenir ve controller (activity, placement) bazında cache'lenir. Aynı activity +
  placement + type için tekrar `loadBanner` çağrıldığında **yeni istek atılmaz**; yüklü
  reklam yeni container'a taşınır ve anında görünür (tab/fragment geçişlerinde 10-20 sn
  boş alan kalmaz). Tip değiştiyse (örn. ADAPTIVE → MREC) eski controller destroy edilip
  yenisi yüklenir.
- **Görünürlük:** Host window'dan ayrılınca auto-refresh otomatik durur, geri eklenince
  devam eder. Görünmeyen banner refresh etmez.
- **Timeout:** Bir network `loadTimeoutSec` içinde cevap vermezse yüklenmekte olan reklam
  view'ı destroy edilip (bekleyen istek iptal edilir) sıradaki network'e geçilir.
- **Retry sınırı:** Waterfall tamamen fail olursa exponential backoff ile en fazla
  `maxBannerRetryCount` (varsayılan 5, 0 = limitsiz) otomatik retry yapılır. Sınıra
  ulaşınca durur; bir sonraki `loadBanner` çağrısı sayacı sıfırlayıp yeniden dener.
- **Temizlik:** Activity destroy olduğunda controller ve cache girdisi otomatik temizlenir.
  `updateConfig` ile banner (veya placement) kapatılırsa cache'teki controller'lar destroy
  edilir ve container'lar boşalır.

**Activity'ler arası paylaşım (opsiyonel):** Config'de `reuseBannersAcrossActivities = true`
(JSON anahtarı: `"reuseBannersAcrossActivities"`) verilirse banner host'u ve reklam view'ları
application context ile yaratılır, cache activity'den bağımsız placement bazlı tutulur.
Böylece Activity A'da yüklenen banner Activity B'de (örn. ayarlar ekranı) anında gösterilir;
activity destroy'da reklam destroy edilmez, yalnızca ölen activity'nin view ağacından ayrılır.
Trade-off: AppLovin, MaxAdView için activity context önerir; bu yüzden varsayılan `false`'tur.
Bu moddaki banner'ları `AdsManager.destroyBanner()` ile elle temizleyebilirsiniz.

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
  "appOpenMinIntervalSec": 0,
  "interstitial": {
    "enabled": true,
    "preload": true,
    "networkOrder": "admob,applovin",
    "defaultPlacement": "inter_default",
    "initialPreloadPlacements": ["inter_default"],
    "placements": {
      "inter_default": { "admob": "ca-app-pub-.../...", "applovin": "MAX_ID" },
      "inter_levelup": { "admob": "ca-app-pub-.../...", "preload": false },
      "inter_splash": {
        "admob": "ca-app-pub-.../...", "applovin": "MAX_ID",
        "networkOrder": "admob,applovin",
        "reloadAfterShow": false,
        "loadTimeoutSec": 8
      }
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

- **Waterfall:** `networkOrder` sırasıyla denenir. Bir network başarılı yüklerse diğerleri denenmez; fail/timeout olursa sıradakine geçilir. Sıradaki bir network'ün o placement'ta ad unit id'si yoksa (ya da boş string ise) adım atlanır.
- **Placement bazlı sıra & timeout:** `networkOrder` ve `loadTimeoutSec` placement seviyesinde de verilebilir ve format/global değeri ezer. Tipik kullanım: format sırası `applovin,admob` iken splash placement'ında `admob,applovin` + kısa `loadTimeoutSec` ile açılışta hızlı AdMob gösterimi.
- **Tek kullanımlık placement:** Placement `reloadAfterShow = false` ise açılışta (preload açıksa) yüklenir, gösterimden sonra otomatik yeniden yüklenmez ve load hatası sonrası backoff retry döngüsü kurulmaz. Oturumda bir kez kullanılan splash placement'ları için boşa giden request'i önler.
- **Placement bazlı saklama:** Her placement için ayrı reklam tutulur. `load/show`'da placement verilmezse `defaultPlacement` kullanılır.
- **Preload (3 kademeli):** Format `preload = true` ana anahtardır. `initialPreloadPlacements` açılışta hangi placement'ların yükleneceğini belirler (boşsa sadece `defaultPlacement`). Placement bazlı `preload = false` ise o placement otomatik preload'a hiç katılmaz — ne açılışta ne gösterim sonrası — yalnızca `load...()` ile veya hazır değilken yapılan `show...()` çağrısıyla yüklenir. Otomatik preload açık olan placement'larda tüm waterfall fail olursa exponential backoff (8s → 16s → ... → 64s) ile retry yapılır.
- **Banner reuse & retry sınırı:** Banner'lar (activity, placement) bazında cache'lenir; aynı parametrelerle tekrar `loadBanner` yeni istek atmaz, yüklü reklamı yeni container'a taşır. Banner retry'ları `maxBannerRetryCount` ile sınırlıdır (detaylar Banner bölümünde).
- **Expire:** Yüklenen reklam `adTtlMinutes` (varsayılan 55 dk) sonra geçersiz sayılır ve çöpe atılır; preload açıksa yenisi yüklenir.
- **Tek fullscreen kuralı:** Herhangi bir fullscreen reklam açıkken ikinci bir `show` çağrısı reddedilir.
- **App open koruması:** Interstitial/rewarded açıkken app open gösterilmez; son inter/rewarded gösteriminden `appOpenMinGapSec`, son app open gösteriminden `appOpenMinIntervalSec` geçmeden app open gösterilmez.
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
- Tüm MAX adaptör sürümleri `adsmanager/build.gradle.kts` içinde sabitlenmiştir (kütüphane JitPack'ten yayınlandığı için tekrarlanabilir build şart). Güncel adaptörlere geçmek için o bloğu düzenleyip yeni bir sürüm yayınlayın.
- **Aynı MAX rewarded ad unit id'sini birden fazla placement'ta kullanmayın** — `MaxRewardedAd` ad unit başına singleton'dır; kütüphane gösterim olaylarını doğru wrapper'a yönlendirir ama aynı anda iki placement'a aynı unit ile load etmek kaçınılması gereken bir kullanım.
- MAX adaptive banner, MAX 13.x'in önerdiği `MaxAdViewConfiguration.builder().setAdaptiveType(ANCHORED)` API'siyle oluşturulur (legacy `adaptive_banner` extra parametresi 13.6+ sürümlerde config hatası logluyordu, kullanılmaz).
- Bu kod cloud ortamında derlenemedi (Google Maven erişimi yok). İlk `gradle sync` / build sırasında ufak derleme düzeltmeleri gerekebilir; hata çıktısını paylaşmanız yeterli.
