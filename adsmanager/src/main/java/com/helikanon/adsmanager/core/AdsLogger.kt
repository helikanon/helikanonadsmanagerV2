package com.helikanon.adsmanager.core

import android.util.Log
import com.helikanon.adsmanager.listener.AdsLogLevel
import com.helikanon.adsmanager.listener.AdsLogListener

internal object AdsLogger {
    private const val TAG = "AdsManager"

    @Volatile var debugEnabled: Boolean = false
    @Volatile var listener: AdsLogListener? = null

    fun d(message: String) {
        if (debugEnabled) Log.d(TAG, message)
        listener?.onLog(AdsLogLevel.DEBUG, message)
    }

    fun i(message: String) {
        if (debugEnabled) Log.i(TAG, message)
        listener?.onLog(AdsLogLevel.INFO, message)
    }

    fun w(message: String) {
        Log.w(TAG, message)
        listener?.onLog(AdsLogLevel.WARN, message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        listener?.onLog(AdsLogLevel.ERROR, message)
    }
}
