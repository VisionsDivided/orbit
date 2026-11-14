// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import io.github.kmmarin.tvbro.data.SeriesRow
import io.github.kmmarin.tvbro.data.WatchState
import io.github.kmmarin.tvbro.orbit.OrbitSyncLogic as L
import io.github.kmmarin.tvbro.orbit.ServerEpisode
import io.github.kmmarin.tvbro.orbit.ServerNext
import io.github.kmmarin.tvbro.orbit.ServerShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbitShowSyncTest {
    @Test fun idsBetweenTvAndServer() {
        assertEquals("7", L.seriesIdFromKey("s|p0|7"))
        assertEquals("p1~7", L.seriesIdFromKey("s|p1|7"))
        assertNull(L.seriesIdFromKey("m|p0|7"))
        assertEquals("s|p0|7", L.seriesKeyFromId("7"))
        assertEquals("s|p1|7", L.seriesKeyFromId("p1~7"))
        assertEquals("e|p0|101", L.episodeKey("7", "101"))
        assertEquals("e|p1|101", L.episodeKey("p1~7", "101"))
    }

    @Test fun episodesNewestWinsBothWays() {
        val local = mapOf(
            "e|p0|101" to WatchState(0, 2_400_000, true, 5_000_000),       // watched on the TV, newer
            "e|p0|102" to WatchState(60_000, 2_400_000, false, 1_000_000), // older on the TV
            "e|p0|104" to WatchState(900_000, 2_400_000, false, 6_000_000),// TV only, but its series is unknown
            "m|p0|9" to WatchState(900_000, 5_400_000, false, 6_000_000),  // a film: not this sync
        )
        val series = mapOf("e|p0|101" to "s|p0|7", "e|p0|102" to "s|p0|7")
        val server = listOf(
            ServerEpisode("7", "101", 300, 2400, false, 4_000_000),
            ServerEpisode("7", "102", 1200, 2400, false, 3_000_000),
            ServerEpisode("p1~8", "201", 2400, 2400, true, 3_500_000),
        )
        val p = L.planEpisodes(local, series::get, server, minResumeMs = 5_000)
        assertEquals(listOf("e|p0|101"), p.toServer.map { it.key })
        assertEquals(Triple("7", "101", true), p.toServer[0].let { Triple(it.seriesId, it.epId, it.state.watched) })
        assertEquals(setOf("e|p0|102", "e|p1|201"), p.toLocal.keys)
        assertEquals(1_200_000L to "s|p0|7", p.toLocal["e|p0|102"]!!.let { it.first.posMs to it.second })
        assertTrue(p.toLocal["e|p1|201"]!!.first.watched)
        assertEquals("s|p1|8", p.toLocal["e|p1|201"]!!.second)
    }

    @Test fun showRowsFromTheServerWhenNewer() {
        val local = mapOf(
            "s|p0|7" to SeriesRow("s|p0|7", updated = 9_000_000, nextEp = "103", active = true),   // newer on the TV
            "s|p0|8" to SeriesRow("s|p0|8", updated = 1_000_000, nextEp = "301", active = true, audioLang = "en"),
        )
        val server = listOf(
            ServerShow("7", "s|p0|7", "102", ServerNext("103", 1, 3, "Three"), false, false, 8_000_000),
            ServerShow("8", "s|p0|8", "302", ServerNext("303", 1, 3, "Next one"), false, false, 2_000_000),
            ServerShow("9", "s|p0|9", "401", null, true, false, 3_000_000),
        )
        val rows = L.planShows(local, server).associateBy { it.key }
        assertEquals(setOf("s|p0|8", "s|p0|9"), rows.keys)
        val r8 = rows["s|p0|8"]!!
        assertEquals(listOf("303", "Next one", "en"), listOf(r8.nextEp, r8.nextTitle, r8.audioLang))
        assertTrue(r8.active)
        assertTrue(rows["s|p0|9"]!!.finished && !rows["s|p0|9"]!!.active)
    }
}
