package com.helikanon.adsmanager.config

import com.helikanon.adsmanager.config.AdNetwork.ADMOB
import com.helikanon.adsmanager.config.AdNetwork.APPLOVIN
import org.junit.Assert.assertEquals
import org.junit.Test

class WaterfallStepsTest {

    private fun AdsConfig.steps(placementId: String): List<Pair<AdNetwork, String>> {
        val fc = format(AdFormat.INTERSTITIAL)!!
        return fc.waterfallSteps(fc.placement(placementId)!!)
    }

    @Test
    fun `ad units of a network are tried in order before the next network`() {
        val config = AdsConfig.build {
            interstitial {
                networkOrderString = "admob,applovin"
                placement("inter", admobAdUnitIds = listOf("high", "fill"), applovinAdUnitId = "max")
                placement("single_first", admobAdUnitId = "single", admobAdUnitIds = listOf("fill"))
            }
        }
        assertEquals(listOf(ADMOB to "high", ADMOB to "fill", APPLOVIN to "max"), config.steps("inter"))
        assertEquals(listOf(ADMOB to "single", ADMOB to "fill"), config.steps("single_first"))
    }

    @Test
    fun `json ad unit is a single id or an ordered array`() {
        val config = AdsConfigJsonParser.parse(
            """
            {"interstitial": {"networkOrder": "admob,applovin", "placements": {
              "arr":    {"admob": ["high", "", "fill"], "max": "m"},
              "single": {"admob": "only"},
              "empty":  {"admob": []}
            }}}
            """
        )
        assertEquals(listOf(ADMOB to "high", ADMOB to "fill", APPLOVIN to "m"), config.steps("arr"))
        assertEquals(listOf(ADMOB to "only"), config.steps("single"))
        assertEquals(emptyList<Pair<AdNetwork, String>>(), config.steps("empty"))
    }
}
