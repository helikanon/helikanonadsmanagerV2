package com.helikanon.adsmanager.banner

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.helikanon.adsmanager.adapter.BannerHandle
import com.helikanon.adsmanager.config.BannerType
import com.helikanon.adsmanager.core.AdsLogger

/**
 * Yüklenmiş bir banner'ı yönetir. Activity bir LifecycleOwner ise
 * (ComponentActivity/AppCompatActivity) pause/resume/destroy otomatik yapılır;
 * değilse [pause], [resume] ve [destroy] manuel çağrılmalıdır.
 */
class BannerAdController internal constructor(
    private val container: ViewGroup,
    val placementId: String,
    val type: BannerType
) {

    internal var handle: BannerHandle? = null

    var isDestroyed: Boolean = false
        private set

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onPause(owner: LifecycleOwner) = pause()
        override fun onResume(owner: LifecycleOwner) = resume()
        override fun onDestroy(owner: LifecycleOwner) {
            owner.lifecycle.removeObserver(this)
            destroy()
        }
    }

    internal fun attachLifecycle(owner: LifecycleOwner) {
        owner.lifecycle.addObserver(lifecycleObserver)
    }

    internal fun attach(newHandle: BannerHandle) {
        if (isDestroyed) {
            newHandle.destroy()
            return
        }
        handle?.destroy()
        handle = newHandle
        container.removeAllViews()

        val view = newHandle.view
        if (container is FrameLayout) {
            val lp = FrameLayout.LayoutParams(
                view.layoutParams?.width ?: ViewGroup.LayoutParams.MATCH_PARENT,
                view.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.BOTTOM
            container.addView(view, lp)
        } else {
            container.addView(view)
        }
        AdsLogger.d("Banner attached to container for placement '$placementId' (${newHandle.network})")
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
        // Bekleyen banner retry'larını iptal et (Activity/view sızıntısını önler)
        com.helikanon.adsmanager.core.RetryScheduler.cancel(
            com.helikanon.adsmanager.config.AdFormat.BANNER, placementId
        )
        handle?.destroy()
        handle = null
        container.removeAllViews()
    }
}
