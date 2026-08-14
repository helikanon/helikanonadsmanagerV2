package com.helikanon.adsmanager.demo

import android.app.Application
import android.util.Log
import com.helikanon.adsmanager.AdsManager
import com.helikanon.adsmanager.config.AdFormat
import com.helikanon.adsmanager.config.AdsConfig
import com.helikanon.adsmanager.listener.AdEventListener
import com.helikanon.adsmanager.listener.AdInfo
import com.helikanon.adsmanager.listener.RewardItem

class DemoApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Demo config — AdMob'un resmi TEST ad unit id'leri kullanılıyor.
        // AppLovin için kendi SDK key'inizi ve MAX ad unit id'lerinizi girin.
        val config = AdsConfig.build {
            testMode = true
            debugLogging = true
            // applovinSdkKey = "APPLOVIN_SDK_KEY"

            interstitialMinIntervalSec = 15
            appOpenMinGapSec = 10

            interstitial {
                preload = true
                networkOrderString = "admob" // AppLovin key girince: "admob,applovin"
                placement(
                    "inter_default",
                    admobAdUnitId = "ca-app-pub-3940256099942544/1033173712",

                )
            }
            rewarded {
                preload = true
                networkOrderString = "admob"
                placement(
                    "rew_default",
                    admobAdUnitId = "ca-app-pub-3940256099942544/5224354917"
                )
            }
            appOpen {
                preload = true
                networkOrderString = "admob"
                placement(
                    "appopen_default",
                    admobAdUnitId = "ca-app-pub-3940256099942544/9257395921"
                )
            }
            banner {
                networkOrderString = "admob"
                placement(
                    "banner_default",
                    admobAdUnitId = "ca-app-pub-3940256099942544/6300978111"
                )
            }
        }

        AdsManager.initialize(this, config)

        // Foreground'a dönüşte otomatik app open gösterimi
        AdsManager.enableAppOpenAutoShow(this)

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

            override fun onAdDismissed(info: AdInfo) {
                Log.d("Demo", "Dismissed: $info")
            }

            override fun onRewardEarned(info: AdInfo, reward: RewardItem) {
                Log.d("Demo", "Reward: ${reward.amount} ${reward.type}")
            }
        })

        AdsManager.setRevenueListener { revenue ->
            Log.d("Demo", "Revenue: $${revenue.revenue} from ${revenue.network} (${revenue.mediatedNetworkName})")
        }
    }
}
