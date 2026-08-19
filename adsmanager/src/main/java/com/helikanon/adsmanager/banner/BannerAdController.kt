package com.helikanon.adsmanager.banner

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.helikanon.adsmanager.adapter.BannerHandle
import com.helikanon.adsmanager.config.BannerType
import com.helikanon.adsmanager.core.AdsLogger

/**
 * Yüklenmiş bir banner'ı yönetir. Reklam, kütüphanenin yarattığı kalıcı bir
 * [host] FrameLayout'una yüklenir; aynı placement için tekrar eden loadBanner
 * çağrılarında bu host yeni container'a taşınır ve yeni istek atılmaz.
 *
 * Activity bir LifecycleOwner ise (ComponentActivity/AppCompatActivity)
 * pause/resume/destroy otomatik yapılır; değilse [pause], [resume] ve [destroy]
 * manuel çağrılmalıdır. Host window'dan ayrıldığında auto-refresh her durumda
 * otomatik durdurulur (görünmeyen banner refresh etmez).
 */
class BannerAdController internal constructor(
    context: Context,
    val placementId: String,
    val type: BannerType
) {

    /** Reklam view'larının içinde yaşadığı kalıcı host; container'lar arasında taşınır. */
    internal val host: FrameLayout = FrameLayout(context)

    internal var handle: BannerHandle? = null

    /** Loader'ın o an yüklemekte olduğu (henüz sonuçlanmamış) view; destroy'da temizlenir. */
    internal var inFlight: BannerHandle? = null

    /** Waterfall veya planlanmış bir retry hâlâ aktif mi? (Loader yönetir.) */
    internal var isLoadActive: Boolean = false

    var isDestroyed: Boolean = false
        private set

    /** Yüklü bir reklam var mı? (Cache'ten dönen controller'da hızlı kontrol için.) */
    val isAdLoaded: Boolean get() = handle != null

    private var boundOwner: LifecycleOwner? = null
    private var destroyWithActivity: Boolean = true

    init {
        // Görünmeyen banner asla refresh etmemeli: host window'dan ayrılınca durdur.
        host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (!isDestroyed) resume()
            }

            override fun onViewDetachedFromWindow(v: View) {
                pause()
            }
        })
    }

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onPause(owner: LifecycleOwner) = pause()
        override fun onResume(owner: LifecycleOwner) = resume()
        override fun onDestroy(owner: LifecycleOwner) {
            owner.lifecycle.removeObserver(this)
            if (boundOwner === owner) boundOwner = null
            if (destroyWithActivity) {
                destroy()
            } else {
                // Paylaşılan (reuse) modda controller yaşamaya devam eder; yalnızca
                // host ölen activity'nin view hiyerarşisinden çıkarılır (leak önlemi).
                detachHostFromParent()
            }
        }
    }

    /**
     * Controller'ı activity yaşam döngüsüne bağlar. [destroyWithActivity] false ise
     * (activity'ler arası reuse modu) activity destroy olduğunda controller yaşar,
     * yalnızca host parent'ından ayrılır.
     */
    internal fun bindToLifecycle(owner: LifecycleOwner, destroyWithActivity: Boolean) {
        if (boundOwner === owner) return
        boundOwner?.lifecycle?.removeObserver(lifecycleObserver)
        boundOwner = owner
        this.destroyWithActivity = destroyWithActivity
        owner.lifecycle.addObserver(lifecycleObserver)
    }

    /** Host'u eski parent'ından çıkarıp [container] içine taşır. */
    internal fun moveToContainer(container: ViewGroup) {
        if (isDestroyed) return
        if (host.parent === container) return
        detachHostFromParent()
        container.removeAllViews()
        if (container is FrameLayout) {
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.BOTTOM
            container.addView(host, lp)
        } else {
            container.addView(
                host,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun detachHostFromParent() {
        (host.parent as? ViewGroup)?.removeView(host)
    }

    /** Host, destroy olan [activity]'nin view ağacındaysa ayırır (paylaşılan modda leak önlemi). */
    internal fun detachHostIfInside(activity: android.app.Activity) {
        if (host.parent != null && host.rootView === activity.window?.peekDecorView()) {
            detachHostFromParent()
        }
    }

    /** Yükleme başarıyla sonuçlandı: eski reklamı at, yenisini görünür yap. */
    internal fun onAdLoaded(newHandle: BannerHandle) {
        if (isDestroyed) {
            newHandle.destroy()
            return
        }
        handle?.destroy()
        handle = newHandle
        inFlight = null
        newHandle.view.visibility = View.VISIBLE
        // Host o an window'da değilse (ör. tab geçilmiş) refresh başlamasın
        if (!host.isAttachedToWindow) newHandle.pause()
        AdsLogger.d("Banner ready in host for placement '$placementId' (${newHandle.network})")
    }

    fun pause() {
        handle?.pause()
    }

    fun resume() {
        handle?.resume()
    }

    fun destroy() {
        if (isDestroyed) return
        isDestroyed = true
        isLoadActive = false
        boundOwner?.lifecycle?.removeObserver(lifecycleObserver)
        boundOwner = null
        // Bekleyen banner retry'larını iptal et (Activity/view sızıntısını önler)
        com.helikanon.adsmanager.core.RetryScheduler.cancel(
            com.helikanon.adsmanager.config.AdFormat.BANNER, placementId
        )
        inFlight?.destroy()
        inFlight = null
        handle?.destroy()
        handle = null
        host.removeAllViews()
        detachHostFromParent()
        BannerCache.onControllerDestroyed(this)
    }
}
