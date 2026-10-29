package com.helikanon.adsmanager.core

import com.helikanon.adsmanager.config.AdNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkReadinessTest {

    private val readiness = NetworkReadiness()
    private val log = mutableListOf<String>()

    @Test
    fun `step of a ready network does not wait for a late network`() {
        // AdMob initialize() çağrılır çağrılmaz hazır; MAX init callback'i henüz dönmedi
        readiness.markReady(AdNetwork.ADMOB)

        // admob,applovin: ilk adım hemen başlar; AdMob fail olunca MAX adımı MAX'i bekler
        readiness.runWhenReady(AdNetwork.ADMOB) {
            log += "admob-first step 1 (ADMOB)"
            readiness.runWhenReady(AdNetwork.APPLOVIN) { log += "admob-first step 2 (APPLOVIN)" }
        }
        // applovin,admob: ilk adım MAX'i bekler
        readiness.runWhenReady(AdNetwork.APPLOVIN) { log += "max-first step 1 (APPLOVIN)" }
        // Tüm SDK'ları bekleyen iş (initialize onComplete)
        readiness.runWhenReady(null) { log += "all ready" }

        assertEquals(listOf("admob-first step 1 (ADMOB)"), log)

        readiness.markReady(AdNetwork.APPLOVIN) // MAX init callback'i
        assertEquals(
            listOf("admob-first step 1 (ADMOB)", "admob-first step 2 (APPLOVIN)", "max-first step 1 (APPLOVIN)"),
            log
        )

        readiness.markAllReady() // Tüm init callback'leri döndü
        assertEquals(4, log.size)
        assertEquals("all ready", log.last())
    }

    @Test
    fun `timeout releases waiting steps once and late callback does not rerun them`() {
        var runs = 0
        readiness.runWhenReady(AdNetwork.APPLOVIN) { runs++ }

        readiness.markAllReady() // 20 sn zaman aşımı
        readiness.markReady(AdNetwork.APPLOVIN) // Geç gelen init callback'i
        readiness.markAllReady()

        assertEquals(1, runs)
        assertEquals(0, readiness.pendingCount)
        // Tümü hazır olduktan sonra bu oturumda init edilmemiş network de beklemez
        assertTrue(readiness.isReady(AdNetwork.ADMOB))
    }
}
