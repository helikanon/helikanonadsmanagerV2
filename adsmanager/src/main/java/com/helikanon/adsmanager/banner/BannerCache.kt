package com.helikanon.adsmanager.banner

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.helikanon.adsmanager.config.AdFormat
import com.helikanon.adsmanager.config.AdsConfig
import com.helikanon.adsmanager.core.AdsLogger
import java.util.WeakHashMap

/**
 * Canlı banner controller'larının cache'i. Aynı activity + placement için tekrar eden
 * loadBanner çağrılarında yeni istek atılmaz; mevcut controller'ın host'u yeni
 * container'a taşınır. `reuseBannersAcrossActivities = true` iken cache activity'den
 * bağımsız, placement bazlı tutulur.
 *
 * Temizlik iki katmanlıdır: [ensureLifecycleHook] ile kaydedilen ActivityLifecycleCallbacks
 * her activity destroy'unda ilgili girdileri deterministik olarak temizler (LifecycleOwner
 * olmayan activity'ler dahil); WeakHashMap ise ek bir güvenlik ağıdır.
 */
internal object BannerCache {

    private val byActivity = WeakHashMap<Activity, MutableMap<String, BannerAdController>>()
    private val global = mutableMapOf<String, BannerAdController>()
    private var lifecycleHookRegistered = false

    /** Activity destroy olduğunda cache girdilerini leak'siz temizleyen global hook. */
    fun ensureLifecycleHook(application: Application) {
        if (lifecycleHookRegistered) return
        lifecycleHookRegistered = true
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityDestroyed(activity: Activity) {
                byActivity.remove(activity)?.values?.toList()?.forEach { it.destroy() }
                // Paylaşılan controller'lar yaşar; yalnızca ölen activity'nin view
                // ağacındaki host'lar ayrılır (activity leak önlemi).
                global.values.toList().forEach { it.detachHostIfInside(activity) }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
    }

    fun get(activity: Activity, placementId: String, shared: Boolean): BannerAdController? {
        val controller = if (shared) global[placementId] else byActivity[activity]?.get(placementId)
        return controller?.takeIf { !it.isDestroyed }
    }

    fun put(activity: Activity, controller: BannerAdController, shared: Boolean) {
        if (shared) {
            global[controller.placementId] = controller
        } else {
            byActivity.getOrPut(activity) { mutableMapOf() }[controller.placementId] = controller
        }
    }

    /** Controller destroy edildiğinde tüm cache girdilerinden düşürülür. */
    fun onControllerDestroyed(controller: BannerAdController) {
        global.entries.removeAll { it.value === controller }
        byActivity.values.forEach { map -> map.entries.removeAll { it.value === controller } }
    }

    /** [placementId] null ise tüm banner'ları, değilse o placement'ın banner'larını destroy eder. */
    fun destroy(placementId: String?) {
        val victims = allControllers().filter { placementId == null || it.placementId == placementId }
        if (victims.isNotEmpty()) {
            AdsLogger.d("Destroying ${victims.size} cached banner controller(s) (placement=${placementId ?: "all"})")
        }
        victims.forEach { it.destroy() }
    }

    /** Config değişiminde artık kapalı olan banner'ları temizler. */
    fun applyConfig(config: AdsConfig) {
        val formatConfig = config.format(AdFormat.BANNER)
        val bannerEnabled = config.isFormatEnabled(AdFormat.BANNER)
        val victims = allControllers().filter { controller ->
            !bannerEnabled || formatConfig?.placements?.get(controller.placementId)?.enabled != true
        }
        if (victims.isNotEmpty()) {
            AdsLogger.i("Config update disabled ${victims.size} cached banner(s); destroying")
        }
        victims.forEach { it.destroy() }
    }

    private fun allControllers(): List<BannerAdController> =
        global.values.toList() + byActivity.values.flatMap { it.values.toList() }
}
