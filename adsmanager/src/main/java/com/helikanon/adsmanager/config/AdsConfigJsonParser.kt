package com.helikanon.adsmanager.config

import org.json.JSONObject

/**
 * JSON string'den [AdsConfig] üretir. Firebase Remote Config gibi bir kaynaktan
 * gelen config'i doğrudan kütüphaneye vermek için kullanılır.
 *
 * Örnek JSON:
 * ```json
 * {
 *   "adsEnabled": true,
 *   "applovinSdkKey": "SDK_KEY",
 *   "testMode": false,
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
 *     "placements": {
 *       "inter_default": { "admob": "ca-app-pub-.../...", "applovin": "MAX_AD_UNIT" },
 *       "inter_gameover": { "admob": "ca-app-pub-.../..." }
 *     }
 *   },
 *   "rewarded":  { "...": "..." },
 *   "banner":    { "...": "..." },
 *   "appOpen":   { "...": "..." }
 * }
 * ```
 * `networkOrder` string ("admob,applovin") veya dizi (["admob","applovin"]) olabilir.
 */
object AdsConfigJsonParser {

    @Throws(org.json.JSONException::class)
    fun parse(json: String): AdsConfig {
        val root = JSONObject(json)
        return AdsConfig.build {
            adsEnabled = root.optBoolean("adsEnabled", true)
            applovinSdkKey = root.optStringOrNull("applovinSdkKey")
            interstitialMinIntervalSec = root.optInt("interstitialMinIntervalSec", 30)
            appOpenMinGapSec = root.optInt("appOpenMinGapSec", 30)
            sessionMaxInterstitials = root.optInt("sessionMaxInterstitials", 0)
            firstInterstitialDelaySec = root.optInt("firstInterstitialDelaySec", 0)
            loadTimeoutSec = root.optInt("loadTimeoutSec", 20)
            retryBaseDelaySec = root.optInt("retryBaseDelaySec", 8)
            retryMaxDelaySec = root.optInt("retryMaxDelaySec", 64)
            adTtlMinutes = root.optInt("adTtlMinutes", 55)
            testMode = root.optBoolean("testMode", false)
            debugLogging = root.optBoolean("debugLogging", false)
            umpConsentEnabled = root.optBoolean("umpConsentEnabled", false)
            if (root.has("childDirected")) childDirected = root.optBoolean("childDirected")
            if (root.has("doNotSell")) doNotSell = root.optBoolean("doNotSell")
            admobTestDeviceIds = root.optStringList("admobTestDeviceIds")
            maxTestDeviceAdvertisingIds = root.optStringList("maxTestDeviceAdvertisingIds")

            parseFormat(root, "interstitial") { block -> interstitial(block) }
            parseFormat(root, "rewarded") { block -> rewarded(block) }
            parseFormat(root, "banner") { block -> banner(block) }
            // hem "appOpen" hem "appopen" anahtarını kabul et
            parseFormat(root, "appOpen", "appopen", "app_open") { block -> appOpen(block) }
        }
    }

    fun parseOrNull(json: String): AdsConfig? = try {
        parse(json)
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
                        enabled = p.optBoolean("enabled", true)
                    )
                }
            }
            // default placement en son set edilir ki placement() çağrıları ezmesin
            obj.optStringOrNull("defaultPlacement")?.let { defaultPlacement = it }
            preloadPlacements = obj.optStringList("preloadPlacements")
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

    private fun JSONObject.optStringList(key: String): List<String> {
        val arr = optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }
}
