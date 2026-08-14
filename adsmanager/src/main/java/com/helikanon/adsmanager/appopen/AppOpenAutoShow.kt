package com.helikanon.adsmanager.appopen

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.helikanon.adsmanager.AdsManager
import com.helikanon.adsmanager.core.AdsLogger
import java.lang.ref.WeakReference

/**
 * Uygulama foreground'a döndüğünde app open reklamını otomatik gösteren lifecycle helper.
 *
 * [AdsManager.enableAppOpenAutoShow] ile aktifleştirilir. Gösterim yine
 * [AdsManager.showAppOpen] üzerinden yapıldığı için tüm kurallar
 * (fullscreen kilidi, interstitial/rewarded sonrası minimum bekleme, frequency)
 * otomatik modda da geçerlidir.
 *
 * Otomatik modu kullanmak istemeyenler kendi lifecycle takibiyle
 * [AdsManager.showAppOpen] çağırabilir; kurallar aynı şekilde işler.
 */
internal object AppOpenAutoShow : DefaultLifecycleObserver, Application.ActivityLifecycleCallbacks {

    private var enabled = false
    private var registered = false
    private var placementId: String? = null
    private var showOnColdStart = false
    private var excludedActivities: Set<Class<out Activity>> = emptySet()
    private var excludePredicate: ((Activity) -> Boolean)? = null

    private var currentActivityRef: WeakReference<Activity>? = null
    private var isColdStart = true

    fun enable(
        application: Application,
        placementId: String?,
        showOnColdStart: Boolean,
        excludedActivities: Set<Class<out Activity>>,
        excludePredicate: ((Activity) -> Boolean)?
    ) {
        this.placementId = placementId
        this.showOnColdStart = showOnColdStart
        this.excludedActivities = excludedActivities
        this.excludePredicate = excludePredicate
        this.enabled = true

        if (!registered) {
            registered = true
            application.registerActivityLifecycleCallbacks(this)
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        }
        AdsLogger.i("AppOpen auto-show enabled (placement=${placementId ?: "default"})")
    }

    fun disable() {
        enabled = false
        AdsLogger.i("AppOpen auto-show disabled")
    }

    // ------------------------------------------------ ProcessLifecycleOwner

    override fun onStart(owner: LifecycleOwner) {
        val coldStart = isColdStart
        isColdStart = false
        if (!enabled) return
        if (coldStart && !showOnColdStart) {
            AdsLogger.d("AppOpen auto-show: cold start, skipping (showOnColdStart=false)")
            return
        }

        val activity = currentActivityRef?.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return

        if (excludedActivities.any { it.isInstance(activity) }) {
            AdsLogger.d("AppOpen auto-show: ${activity.javaClass.simpleName} is excluded")
            return
        }
        if (excludePredicate?.invoke(activity) == true) {
            AdsLogger.d("AppOpen auto-show: excluded by predicate")
            return
        }

        AdsManager.showAppOpen(activity, placementId)
    }

    // ------------------------------------------- ActivityLifecycleCallbacks

    override fun onActivityStarted(activity: Activity) {
        currentActivityRef = WeakReference(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivityRef = WeakReference(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivityRef?.get() === activity) currentActivityRef = null
    }
}
