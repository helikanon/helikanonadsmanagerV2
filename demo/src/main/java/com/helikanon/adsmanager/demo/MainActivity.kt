package com.helikanon.adsmanager.demo

import android.os.Bundle
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.helikanon.adsmanager.AdsManager
import com.helikanon.adsmanager.banner.BannerAdController
import com.helikanon.adsmanager.config.BannerType

class MainActivity : ComponentActivity() {

    private var bannerController: BannerAdController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val bannerContainer = findViewById<FrameLayout>(R.id.bannerContainer)

        findViewById<Button>(R.id.btnInterstitial).setOnClickListener {
            val shown = AdsManager.showInterstitial(this)
            if (!shown) toast("Interstitial hazır değil ya da kurallar engelledi")
        }

        findViewById<Button>(R.id.btnInterstitialForced).setOnClickListener {
            val shown = AdsManager.showInterstitialWithoutTimeCheck(this)
            if (!shown) toast("Interstitial hazır değil")
        }

        findViewById<Button>(R.id.btnRewarded).setOnClickListener {
            val shown = AdsManager.showRewarded(this) { reward ->
                toast("Ödül kazanıldı: ${reward.amount} ${reward.type}")
            }
            if (!shown) toast("Rewarded hazır değil")
        }

        findViewById<Button>(R.id.btnAppOpen).setOnClickListener {
            val shown = AdsManager.showAppOpen(this)
            if (!shown) toast("App open hazır değil ya da kurallar engelledi")
        }

        findViewById<Button>(R.id.btnBannerAdaptive).setOnClickListener {
            loadBanner(bannerContainer, BannerType.ADAPTIVE)
        }
        findViewById<Button>(R.id.btnBannerStandard).setOnClickListener {
            loadBanner(bannerContainer, BannerType.STANDARD)
        }
        findViewById<Button>(R.id.btnBannerMrec).setOnClickListener {
            loadBanner(bannerContainer, BannerType.MREC)
        }

        findViewById<Button>(R.id.btnMediationDebugger).setOnClickListener {
            AdsManager.showMediationDebugger(this)
        }
        findViewById<Button>(R.id.btnAdInspector).setOnClickListener {
            AdsManager.openAdInspector(this)
        }
    }

    private fun loadBanner(container: FrameLayout, type: BannerType) {
        // Aynı tip için tekrar çağrıda cache'teki banner anında geri gelir (yeni istek yok);
        // tip değiştiyse kütüphane eski controller'ı kendisi destroy edip yenisini yükler.
        bannerController = AdsManager.loadBanner(this, container, type)
        if (bannerController == null) toast("Banner yüklenemedi (config kontrol edin)")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
