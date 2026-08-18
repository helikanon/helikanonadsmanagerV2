package com.helikanon.adsmanager.config

import org.json.JSONObject

/**
 * JSON string'den [AdsConfig] üretir. Firebase Remote Config gibi bir kaynaktan
 * gelen config'i doğrudan kütüphaneye vermek için kullanılır.
 *
 * ## JSON'dan OKUNMAYAN alanlar
 *
 * Aşağıdaki alanlar bilinçli olarak JSON'dan okunmaz; uygulama kodundan verilmelidir:
 * `applovinSdkKey`, `testMode`, `admobTestDeviceIds`, `maxTestDeviceAdvertisingIds`,
 * `childDirected`, `doNotSell`. JSON içinde bu anahtarlar bulunsa bile yok sayılır.
 *
 * Bunları JSON ile birlikte vermek için [parse] fonksiyonunun `localSettings` bloğunu
 * kullanın; blok JSON okunduktan sonra uygulanır, yani her zaman kod kazanır:
 * ```kotlin
 * val config = AdsConfigJsonParser.parse(remoteJson) {
 *     applovinSdkKey = BuildConfig.APPLOVIN_SDK_KEY
 *     testMode = BuildConfig.DEBUG
 *     childDirected = false
 * }
 * ```
 *
 * Örnek JSON:
 * ```json
 * {
 *   "adsEnabled": true,
 *   "debugLogging": false,
 *   "umpConsentEnabled": true,
 *   "interstitialMinIntervalSec": 45,
 *   "appOpenMinGapSec": 30,
 *   "sessionMaxInterstitials": 0,
 *   "firstInterstitialDelaySec": 0,
 *   "loadTimeoutSec": 20,
 *   "interstitial": {
 *     "enabled": true,
 *     "preload": true,
 *     "networkOrder": "admob,applovin",
 *     "defaultPlacement": "inter_default",
 *     "initialPreloadPlacements": ["inter_default"],
 *     "placements": {
 *       "inter_default": { "admob": "ca-app-pub-.../...", "applovin": "MAX_AD_UNIT" },
 *       "inter_gameover": { "admob": "ca-app-pub-.../..." },
 *       "inter_levelup": { "admob": "ca-app-pub-.../...", "preload": false }
 *     }
 *   },
 *   "rewarded":  { "...": "..." },
 *   "banner":    { "...": "..." },
 *   "appOpen":   { "...": "..." }
 * }
 * ```
 * `networkOrder` string ("admob,applovin") veya dizi (["admob","applovin"]) olabilir.
 *
 * Preload kontrolü üç kademelidir:
 * - Format `preload`: o format için otomatik yükleme ana anahtarı.
 * - `initialPreloadPlacements`: açılışta hangi placement'ların yükleneceği. Boşsa
 *   sadece `defaultPlacement` yüklenir.
 * - Placement `preload` (varsayılan `true`): `false` ise o placement otomatik preload'a
 *   hiç katılmaz — ne açılışta ne gösterim sonrası; yalnızca manuel yüklenir.
 */
object AdsConfigJsonParser {

    /**
     * @param localSettings JSON okunduktan sonra builder üzerinde çalıştırılır. SDK key,
     *   test modu ve gizlilik bayrakları gibi yalnızca koddan verilen alanlar burada set edilir.
     */
    @Throws(org.json.JSONException::class)
    @JvmOverloads
    fun parse(json: String, localSettings: (AdsConfig.Builder.() -> Unit)? = null): AdsConfig {
        val root = JSONObject(json)
        return AdsConfig.build {
            adsEnabled = root.optBoolean("adsEnabled", true)
            interstitialMinIntervalSec = root.optInt("interstitialMinIntervalSec", 30)
            appOpenMinGapSec = root.optInt("appOpenMinGapSec", 30)
            sessionMaxInterstitials = root.optInt("sessionMaxInterstitials", 0)
            firstInterstitialDelaySec = root.optInt("firstInterstitialDelaySec", 0)
            loadTimeoutSec = root.optInt("loadTimeoutSec", 20)
            retryBaseDelaySec = root.optInt("retryBaseDelaySec", 8)
            retryMaxDelaySec = root.optInt("retryMaxDelaySec", 64)
            adTtlMinutes = root.optInt("adTtlMinutes", 55)
            debugLogging = root.optBoolean("debugLogging", false)
            umpConsentEnabled = root.optBoolean("umpConsentEnabled", false)

            // NOT: applovinSdkKey, testMode, admobTestDeviceIds, maxTestDeviceAdvertisingIds,
            // childDirected ve doNotSell JSON'dan okunmaz; sadece localSettings ile verilir.

            parseFormat(root, "interstitial") { block -> interstitial(block) }
            parseFormat(root, "rewarded") { block -> rewarded(block) }
            parseFormat(root, "banner") { block -> banner(block) }
            // hem "appOpen" hem "appopen" anahtarını kabul et
            parseFormat(root, "appOpen", "appopen", "app_open") { block -> appOpen(block) }

            // en son uygulanır ki koddan verilen değerler JSON'u ezsin
            localSettings?.invoke(this)
        }
    }

    @JvmOverloads
    fun parseOrNull(json: String, localSettings: (AdsConfig.Builder.() -> Unit)? = null): AdsConfig? = try {
        parse(json, localSettings)
    } catch (_: Exception) {
        null
    }

    private fun parseFormat(
        root: JSONObject,
        vararg keys: String,
        register: (AdsConfig.FormatBuilder.() -> Unit) -> Unit
    ) {
        val obj = keys.firstNotNullOfOrNull { root.optJSONObject(it) } ?: return
        register {
            enabled = obj.optBoolean("enabled", true)
            preload = obj.optBoolean("preload", false)

            // networkOrder: "admob,applovin" ya da ["admob","applovin"]
            val orderArray = obj.optJSONArray("networkOrder")
            if (orderArray != null) {
                networkOrder = (0 until orderArray.length())
                    .mapNotNull { AdNetwork.fromKey(orderArray.optString(it)) }
                    .distinct()
            } else {
                obj.optStringOrNull("networkOrder")?.let { networkOrderString = it }
            }

            val placementsObj = obj.optJSONObject("placements")
            if (placementsObj != null) {
                for (pid in placementsObj.keys()) {
                    val p = placementsObj.optJSONObject(pid) ?: continue
                    placement(
                        id = pid,
                        admobAdUnitId = p.optStringOrNull("admob"),
                        applovinAdUnitId = p.optStringOrNull("applovin") ?: p.optStringOrNull("max"),
                        enabled = p.optBoolean("enabled", true),
                        preload = p.optBoolean("preload", true)
                    )
                }
            }
            // default placement en son set edilir ki placement() çağrıları ezmesin
            obj.optStringOrNull("defaultPlacement")?.let { defaultPlacement = it }
            initialPreloadPlacements = obj.optStringList("initialPreloadPlacements")
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

    private fun JSONObject.optStringList(key: String): List<String> {
        val arr = optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }
}
