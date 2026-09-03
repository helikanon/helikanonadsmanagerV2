package com.helikanon.adsmanager.core

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * [FullscreenGate] kilidinin sonsuza dek kalmasını engelleyen lifecycle bekçisi.
 *
 * Senaryo: interstitial (ör. AdMob `AdActivity`) ekrandayken bildirimden veya
 * launcher'dan uygulamanın kendi activity'si onun üstüne açılır. Reklam
 * activity'si kapanmadığı için network `dismiss` callback'i hiç gelmez ve
 * kilit süreç ölümüne kadar dolu kalır; tüm interstitial/app open çağrıları
 * "another fullscreen ad is showing" ile reddedilir.
 *
 * Kural: kilit tutuluyorken, reklam SDK'sına ait olmayan bir activity
 * resume olursa (ve kilit [GRACE_MS]'den uzun süredir tutuluyorsa) kilit
 * zorla açılır. Gösterimin yapıldığı host activity her durumda "reklam değil"
 * sayılır.
 */
internal object FullscreenGateWatchdog : Application.ActivityLifecycleCallbacks {

    /** show() sonrası reklam activity'sinin açılması için tanınan süre. */
    private const val GRACE_MS = 1_500L

    /**
     * Host activity resume olduktan sonra network dismiss callback'inin gelmesi için
     * beklenen süre. Android, kapanan activity'yi destroy etmeden önce alttakini resume
     * eder; bazı SDK'lar dismiss'i onDestroy'da bildirir. Bu yüzden kilit host resume
     * anında değil, bu süre sonunda hâlâ doluysa açılır.
     */
    private const val CONFIRM_MS = 1_000L

    private var registered = false
    private var hostActivityRef: WeakReference<Activity>? = null

    /** Son resume olan activity bir reklam activity'si miydi. */
    @Volatile
    private var lastResumedWasAd = false

    /** Bilinen reklam SDK'larının fullscreen activity paket önekleri. */
    private val adActivityPrefixes = listOf(
        "com.google.android.gms.ads",
        "com.google.ads",
        "com.applovin",
        "com.ironsource",
        "com.unity3d",
        "com.bytedance",
        "com.pangle",
        "com.mbridge",
        "com.mintegral",
        "com.facebook.ads",
        "com.vungle",
        "com.inmobi",
        "com.chartboost",
        "com.fyber",
        "com.moloco",
        "com.bigo",
        "sg.bigo",
        "com.adcolony",
        "com.tapjoy",
        "com.ogury",
        "com.yandex.mobile.ads",
        "com.smaato",
        "com.verve",
        "net.pubnative",
        "com.hyprmx",
        "com.line.ads",
        "com.amazon.device.ads",
        "com.my.target",
        "com.kidoz",
        "com.maio",
        "jp.maio",
        "com.startapp",
        "com.adfly"
    )

    fun install(application: Application) {
        if (registered) return
        registered = true
        application.registerActivityLifecycleCallbacks(this)
    }

    /** showFullscreen çağrısında gösterimin yapıldığı host activity'yi kaydeder. */
    fun onShowRequested(hostActivity: Activity) {
        hostActivityRef = WeakReference(hostActivity)
    }

    internal fun isAdActivity(activity: Activity): Boolean {
        if (hostActivityRef?.get() === activity) return false
        val name = activity.javaClass.name
        return adActivityPrefixes.any { name.startsWith(it) }
    }

    override fun onActivityResumed(activity: Activity) {
        val isAd = isAdActivity(activity)
        lastResumedWasAd = isAd
        if (isAd) return
        if (!FullscreenGate.isShowing) return
        if (FullscreenGate.heldForMs() < GRACE_MS) return

        // Normal kapanışta dismiss callback'i host resume'dan hemen sonra gelir ve kilidi
        // kendisi açar; o yüzden hemen değil, kısa bir doğrulama süresi sonunda karar ver.
        val ticket = FullscreenGate.currentTicket
        val name = activity.javaClass.simpleName
        MainThread.runDelayed(CONFIRM_MS) {
            if (lastResumedWasAd) return@runDelayed
            if (!FullscreenGate.isShowing || FullscreenGate.currentTicket != ticket) return@runDelayed
            val held = FullscreenGate.heldForMs()
            FullscreenGate.forceRelease(
                "$name resumed over the ad; no dismiss callback within ${CONFIRM_MS}ms (held ${held / 1000}s)"
            )
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
