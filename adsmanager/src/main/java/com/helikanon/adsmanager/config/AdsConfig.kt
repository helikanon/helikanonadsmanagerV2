package com.helikanon.adsmanager.config

/**
 * Bir placement'ın network bazlı ad unit id eşlemesi.
 */
class PlacementConfig(
    val id: String,
    val adUnitIds: Map<AdNetwork, String>,
    val enabled: Boolean = true,
    /**
     * Bu placement otomatik preload'a katılsın mı? `false` ise ne açılışta yüklenir
     * ne de gösterim sonrası tekrar yüklenir; yalnızca manuel `load...()` çağrısıyla
     * ya da hazır değilken yapılan `show...()` çağrısının tetiklediği yüklemeyle dolar.
     * Format seviyesindeki [FormatConfig.preload] kapalıysa bu değerin etkisi yoktur.
     */
    val preload: Boolean = true,
    /**
     * Bu placement'a özel waterfall sırası. `null` (varsayılan) ise format seviyesindeki
     * [FormatConfig.networkOrder] kullanılır. Örn: format sırası `applovin,admob` iken
     * splash placement'ı için `admob,applovin` vererek açılışta önce AdMob denenir.
     * Sıradaki bir network'ün bu placement'ta ad unit id'si yoksa o adım yine atlanır.
     */
    val networkOrder: List<AdNetwork>? = null,
    /**
     * Gösterim sonrası otomatik yeniden yükleme yapılsın mı? `false` ise placement
     * "tek kullanımlık" olur: açılışta (preload açıksa) yüklenir, gösterildikten sonra
     * bir daha kendiliğinden yüklenmez. Oturumda bir kez kullanılan splash/açılış
     * placement'ları için boşa giden request'i önler.
     *
     * `false` ayrıca load hatası sonrası kurulan exponential backoff retry döngüsünü de
     * kapatır; manuel `load...()` çağrıları her zaman çalışmaya devam eder.
     */
    val reloadAfterShow: Boolean = true,
    /**
     * Bu placement için network başına load timeout (saniye). `null` ise
     * [AdsConfig.loadTimeoutSec] kullanılır. Splash gibi kullanıcının beklediği
     * yerlerde daha kısa bir değer vererek waterfall'un sonraki adımına hızlı geçilir.
     */
    val loadTimeoutSec: Int? = null
) {
    fun adUnitIdFor(network: AdNetwork): String? = adUnitIds[network]?.takeIf { it.isNotBlank() }
}

/**
 * Format (interstitial / rewarded / banner / appOpen) bazlı ayarlar.
 */
class FormatConfig(
    val format: AdFormat,
    val enabled: Boolean = true,
    /** Waterfall sırası. Örn: [ADMOB, APPLOVIN] -> önce AdMob dener, fail olursa AppLovin. */
    val networkOrder: List<AdNetwork> = listOf(AdNetwork.ADMOB, AdNetwork.APPLOVIN),
    /** true ise init sonrası ve her gösterimden sonra otomatik önceden yükleme yapılır. */
    val preload: Boolean = false,
    /** load/show çağrılarında placement verilmezse kullanılacak placement. */
    val defaultPlacementId: String? = null,
    val placements: Map<String, PlacementConfig> = emptyMap(),
    /**
     * Açılışta (init sonrası) önceden yüklenecek placement listesi. Boşsa sadece
     * default placement yüklenir. Buradaki bir placement [PlacementConfig.preload]
     * `false` ise yine de yüklenmez.
     *
     * Not: Bu liste yalnızca açılıştaki ilk yüklemeyi belirler; gösterim sonrası
     * otomatik yeniden yükleme [PlacementConfig.preload] bayrağına bakar.
     */
    val initialPreloadPlacementIds: List<String> = emptyList()
) {
    fun placement(id: String?): PlacementConfig? {
        val pid = id ?: defaultPlacementId ?: return null
        return placements[pid]
    }

    /** Bu placement için otomatik (açılış + gösterim sonrası) preload açık mı? */
    fun autoPreloads(placement: PlacementConfig): Boolean =
        preload && placement.enabled && placement.preload

    /**
     * Gösterim (veya show hatası) sonrası otomatik yeniden yükleme yapılsın mı?
     * [PlacementConfig.reloadAfterShow] `false` ise açılış preload'ı yapılır ama
     * gösterim sonrası yeniden yükleme yapılmaz.
     */
    fun reloadsAfterShow(placement: PlacementConfig): Boolean =
        autoPreloads(placement) && placement.reloadAfterShow

    /** Bu placement için geçerli waterfall sırası (placement override'ı varsa o kazanır). */
    fun networkOrderFor(placement: PlacementConfig): List<AdNetwork> =
        placement.networkOrder?.takeIf { it.isNotEmpty() } ?: networkOrder

    /** Açılışta yüklenecek placement'lar. */
    fun initialPreloadPlacements(): List<PlacementConfig> {
        val ids = if (initialPreloadPlacementIds.isNotEmpty()) initialPreloadPlacementIds
        else listOfNotNull(defaultPlacementId)
        return ids.mapNotNull { placements[it] }.filter { autoPreloads(it) }
    }
}

/**
 * Kütüphanenin ana konfigürasyonu. [AdsConfig.build] DSL'i veya
 * [com.helikanon.adsmanager.config.AdsConfigJsonParser] ile JSON'dan oluşturulur.
 */
class AdsConfig private constructor(
    /** Tüm reklamları aç/kapat (global kill switch). */
    val adsEnabled: Boolean,
    /** AppLovin SDK key (MAX kullanılacaksa zorunlu). */
    val applovinSdkKey: String?,
    /** İki interstitial gösterimi arasındaki minimum süre (saniye). */
    val interstitialMinIntervalSec: Int,
    /** Son interstitial/rewarded gösteriminden sonra app open gösterilebilmesi için gereken minimum süre (saniye). */
    val appOpenMinGapSec: Int,
    /** İki app open gösterimi arasındaki minimum süre (saniye). 0 = limitsiz. */
    val appOpenMinIntervalSec: Int,
    /** Oturum başına maksimum interstitial gösterimi. 0 = limitsiz. */
    val sessionMaxInterstitials: Int,
    /** Uygulama açılışından ilk interstitial gösterimine kadar beklenecek süre (saniye). 0 = beklemesiz. */
    val firstInterstitialDelaySec: Int,
    /** Bir network'ün load cevabı için beklenecek maksimum süre (saniye). Aşılırsa sıradaki network denenir. */
    val loadTimeoutSec: Int,
    /** Otomatik retry başlangıç gecikmesi (saniye). Exponential backoff ile artar. */
    val retryBaseDelaySec: Int,
    /** Otomatik retry maksimum gecikmesi (saniye). */
    val retryMaxDelaySec: Int,
    /**
     * Banner waterfall'u tamamen fail olduğunda yapılacak maksimum otomatik retry sayısı.
     * Sınıra ulaşınca retry durur; bir sonraki loadBanner çağrısı sayacı sıfırlar. 0 = limitsiz.
     */
    val maxBannerRetryCount: Int,
    /**
     * true ise banner host'u ve reklam view'ları application context ile yaratılır ve
     * cache activity'den bağımsız placement bazlı tutulur; bir Activity'de yüklenen banner
     * başka bir Activity'de anında gösterilebilir. AppLovin MaxAdView için activity
     * context önerdiğinden varsayılan false'tur.
     */
    val reuseBannersAcrossActivities: Boolean,
    /** Yüklenen fullscreen reklamın geçerlilik süresi (dakika). Aşılırsa expire sayılır ve yeniden yüklenir. */
    val adTtlMinutes: Int,
    /** Test modu: AdMob test cihazları + AppLovin test cihazları aktifleşir, debug log açılır. */
    val testMode: Boolean,
    /** Debug loglama. */
    val debugLogging: Boolean,
    /** Google UMP (GDPR) consent akışını kütüphane yönetsin mi? */
    val umpConsentEnabled: Boolean,
    /** COPPA: çocuklara yönelik uygulama işareti (AdMob'a iletilir). */
    val childDirected: Boolean?,
    /** CCPA "do not sell" işareti (AppLovin'e iletilir). */
    val doNotSell: Boolean?,
    /** AdMob test cihaz id'leri (testMode'dan bağımsız da verilebilir). */
    val admobTestDeviceIds: List<String>,
    /** AppLovin test cihazlarının advertising id'leri (GAID). */
    val maxTestDeviceAdvertisingIds: List<String>,
    val formats: Map<AdFormat, FormatConfig>
) {

    fun format(format: AdFormat): FormatConfig? = formats[format]

    fun isFormatEnabled(format: AdFormat): Boolean =
        adsEnabled && (formats[format]?.enabled == true)

    /** Config'de en az bir formatta kullanılan network'ler. */
    fun usedNetworks(): Set<AdNetwork> =
        formats.values.filter { it.enabled }
            .flatMap { fc -> fc.networkOrder + fc.placements.values.flatMap { it.networkOrder.orEmpty() } }
            .toSet()

    /**
     * Tüm alanları bu config'in değerleriyle doldurulmuş yeni bir [Builder] döner.
     * Birkaç alanı değiştirip [Builder.build] ile yeni bir config üretmek için kullanılır;
     * bu config değişmez. Ör: `config.toBuilder().apply { appOpenMinIntervalSec = 60 }.build()`
     */
    fun toBuilder(): Builder = Builder().also { it.seedFrom(this) }

    // ---------------------------------------------------------------- Builder

    companion object {
        fun build(block: Builder.() -> Unit): AdsConfig = Builder().apply(block).build()
    }

    class Builder {
        var adsEnabled: Boolean = true
        var applovinSdkKey: String? = null
        var interstitialMinIntervalSec: Int = 30
        var appOpenMinGapSec: Int = 30
        var appOpenMinIntervalSec: Int = 0
        var sessionMaxInterstitials: Int = 0
        var firstInterstitialDelaySec: Int = 0
        var loadTimeoutSec: Int = 20
        var retryBaseDelaySec: Int = 8
        var retryMaxDelaySec: Int = 64
        var maxBannerRetryCount: Int = 5
        var reuseBannersAcrossActivities: Boolean = false
        var adTtlMinutes: Int = 55
        var testMode: Boolean = false
        var debugLogging: Boolean = false
        var umpConsentEnabled: Boolean = false
        var childDirected: Boolean? = null
        var doNotSell: Boolean? = null
        var admobTestDeviceIds: List<String> = emptyList()
        var maxTestDeviceAdvertisingIds: List<String> = emptyList()

        private val formatBuilders = mutableMapOf<AdFormat, FormatBuilder>()

        fun interstitial(block: FormatBuilder.() -> Unit) = format(AdFormat.INTERSTITIAL, block)
        fun rewarded(block: FormatBuilder.() -> Unit) = format(AdFormat.REWARDED, block)
        fun banner(block: FormatBuilder.() -> Unit) = format(AdFormat.BANNER, block)
        fun appOpen(block: FormatBuilder.() -> Unit) = format(AdFormat.APP_OPEN, block)

        private fun format(format: AdFormat, block: FormatBuilder.() -> Unit) {
            formatBuilders.getOrPut(format) { FormatBuilder(format) }.apply(block)
        }

        internal fun seedFrom(source: AdsConfig) {
            adsEnabled = source.adsEnabled
            applovinSdkKey = source.applovinSdkKey
            interstitialMinIntervalSec = source.interstitialMinIntervalSec
            appOpenMinGapSec = source.appOpenMinGapSec
            appOpenMinIntervalSec = source.appOpenMinIntervalSec
            sessionMaxInterstitials = source.sessionMaxInterstitials
            firstInterstitialDelaySec = source.firstInterstitialDelaySec
            loadTimeoutSec = source.loadTimeoutSec
            retryBaseDelaySec = source.retryBaseDelaySec
            retryMaxDelaySec = source.retryMaxDelaySec
            maxBannerRetryCount = source.maxBannerRetryCount
            reuseBannersAcrossActivities = source.reuseBannersAcrossActivities
            adTtlMinutes = source.adTtlMinutes
            testMode = source.testMode
            debugLogging = source.debugLogging
            umpConsentEnabled = source.umpConsentEnabled
            childDirected = source.childDirected
            doNotSell = source.doNotSell
            admobTestDeviceIds = source.admobTestDeviceIds
            maxTestDeviceAdvertisingIds = source.maxTestDeviceAdvertisingIds
            source.formats.forEach { (fmt, fc) ->
                formatBuilders.getOrPut(fmt) { FormatBuilder(fmt) }.seedFrom(fc)
            }
        }

        fun build(): AdsConfig = AdsConfig(
            adsEnabled = adsEnabled,
            applovinSdkKey = applovinSdkKey,
            interstitialMinIntervalSec = interstitialMinIntervalSec,
            appOpenMinGapSec = appOpenMinGapSec,
            appOpenMinIntervalSec = appOpenMinIntervalSec,
            sessionMaxInterstitials = sessionMaxInterstitials,
            firstInterstitialDelaySec = firstInterstitialDelaySec,
            loadTimeoutSec = loadTimeoutSec,
            retryBaseDelaySec = retryBaseDelaySec,
            retryMaxDelaySec = retryMaxDelaySec,
            maxBannerRetryCount = maxBannerRetryCount,
            reuseBannersAcrossActivities = reuseBannersAcrossActivities,
            adTtlMinutes = adTtlMinutes,
            testMode = testMode,
            debugLogging = debugLogging || testMode,
            umpConsentEnabled = umpConsentEnabled,
            childDirected = childDirected,
            doNotSell = doNotSell,
            admobTestDeviceIds = admobTestDeviceIds,
            maxTestDeviceAdvertisingIds = maxTestDeviceAdvertisingIds,
            formats = formatBuilders.mapValues { it.value.build() }
        )
    }

    class FormatBuilder(private val format: AdFormat) {
        var enabled: Boolean = true
        var preload: Boolean = false
        var defaultPlacement: String? = null

        /**
         * Açılışta preload edilecek placement id'leri. Boş bırakılırsa sadece
         * [defaultPlacement] yüklenir. Gösterim sonrası yeniden yüklemeyi değil,
         * yalnızca ilk yüklemeyi etkiler.
         */
        var initialPreloadPlacements: List<String> = emptyList()

        /** [AdNetwork] listesi olarak sıra. */
        var networkOrder: List<AdNetwork> = listOf(AdNetwork.ADMOB, AdNetwork.APPLOVIN)

        /** "admob,applovin" şeklinde string ile sıra vermek için. */
        var networkOrderString: String? = null
            set(value) {
                field = value
                if (value != null) networkOrder = AdNetwork.parseOrder(value)
            }

        private val placements = mutableMapOf<String, PlacementConfig>()

        /**
         * @param preload `false` ise bu placement otomatik preload'a hiç katılmaz
         *   (ne açılışta ne de gösterim sonrası); sadece manuel yüklenir.
         * @param networkOrder bu placement'a özel waterfall sırası; `null` ise format sırası kullanılır.
         * @param reloadAfterShow `false` ise gösterim sonrası otomatik yeniden yükleme yapılmaz.
         * @param loadTimeoutSec bu placement'a özel network başına load timeout'u (saniye).
         */
        @JvmOverloads
        fun placement(
            id: String,
            admobAdUnitId: String? = null,
            applovinAdUnitId: String? = null,
            enabled: Boolean = true,
            preload: Boolean = true,
            networkOrder: List<AdNetwork>? = null,
            reloadAfterShow: Boolean = true,
            loadTimeoutSec: Int? = null
        ) {
            val map = buildMap {
                admobAdUnitId?.let { put(AdNetwork.ADMOB, it) }
                applovinAdUnitId?.let { put(AdNetwork.APPLOVIN, it) }
            }
            placements[id] = PlacementConfig(
                id = id,
                adUnitIds = map,
                enabled = enabled,
                preload = preload,
                networkOrder = networkOrder,
                reloadAfterShow = reloadAfterShow,
                loadTimeoutSec = loadTimeoutSec
            )
            if (defaultPlacement == null) defaultPlacement = id
        }

        internal fun seedFrom(source: FormatConfig) {
            enabled = source.enabled
            preload = source.preload
            defaultPlacement = source.defaultPlacementId
            initialPreloadPlacements = source.initialPreloadPlacementIds
            networkOrder = source.networkOrder
            placements.putAll(source.placements)
        }

        fun build(): FormatConfig = FormatConfig(
            format = format,
            enabled = enabled,
            networkOrder = networkOrder,
            preload = preload,
            defaultPlacementId = defaultPlacement,
            placements = placements.toMap(),
            initialPreloadPlacementIds = initialPreloadPlacements
        )
    }
}
