package com.helikanon.adsmanager.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * İnternet bağlantısını izler. Bağlantı yokken load/retry denemeleri bekletilir,
 * bağlantı geldiğinde bekleyen işler tetiklenir.
 */
internal object ConnectivityMonitor {

    private var connectivityManager: ConnectivityManager? = null
    private val onlineListeners = mutableListOf<() -> Unit>()
    private val onceListeners = mutableMapOf<String, () -> Unit>()

    @Volatile
    var isOnline: Boolean = true
        private set

    fun start(context: Context) {
        if (connectivityManager != null) return
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = cm
        isOnline = currentlyOnline(cm)
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val wasOffline = !isOnline
                    isOnline = true
                    if (wasOffline) {
                        AdsLogger.d("Connectivity restored, flushing pending ad work")
                        MainThread.run {
                            onlineListeners.toList().forEach { it() }
                            val once = onceListeners.values.toList()
                            onceListeners.clear()
                            once.forEach { it() }
                        }
                    }
                }

                override fun onLost(network: Network) {
                    isOnline = currentlyOnline(cm)
                }
            })
        } catch (e: Exception) {
            AdsLogger.w("ConnectivityMonitor register failed: ${e.message}")
        }
    }

    private fun currentlyOnline(cm: ConnectivityManager): Boolean = try {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Exception) {
        true
    }

    /** Bağlantı geri geldiğinde her seferinde çağrılacak kalıcı listener. */
    fun addOnlineListener(listener: () -> Unit) {
        onlineListeners.add(listener)
    }

    /** Bağlantı geri geldiğinde bir kez çağrılacak, key ile tekilleştirilen listener. */
    fun addOnlineListenerOnce(key: String, listener: () -> Unit) {
        onceListeners[key] = listener
    }

    fun removeOnceListener(key: String) {
        onceListeners.remove(key)
    }
}
