package com.helikanon.adsmanager.core

import android.os.Handler
import android.os.Looper

internal object MainThread {
    val handler = Handler(Looper.getMainLooper())

    fun run(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else handler.post(block)
    }

    fun runDelayed(delayMs: Long, block: () -> Unit) {
        handler.postDelayed(block, delayMs)
    }
}
