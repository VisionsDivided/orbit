// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import io.github.kmmarin.tvbro.data.WatchState
import io.github.kmmarin.tvbro.orbit.OrbitSyncLogic as L
import io.github.kmmarin.tvbro.orbit.ServerResume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbitSyncLogicTest {
    @Test fun firstSyncTakesBothSides() {
        val m = L.mergeList(local = setOf("p0|1", "g|News"), base = emptySet(), server = setOf("m|p0|7"))
        assertEquals(setOf("p0|1"), m.add)
        assertEquals(emptySet<String>(), m.remove)
        assertEquals(setOf("p0|1", "m|p0|7"), m.result)
        assertEquals(setOf("p0|1", "m|p0|7", "g|News"), L.withLocalOnly(setOf("p0|1", "g|News"), m.result))
    }

    @Test fun changesOnBothSidesAreKept() {
        val base = setOf("p0|1", "p0|2", "s|p0|3")
        // TV removed p0|2 and added p0|4 while offline; the web removed s|p0|3 and added m|p0|5
        val m = L.mergeList(local = setOf("p0|1", "p0|4", "s|p0|3"), base = base, server = setOf("p0|1", "p0|2", "m|p0|5"))
        assertEquals(setOf("p0|4"), m.add)
        assertEquals(setOf("p0|2"), m.remove)
        assertEquals(setOf("p0|1", "p0|4", "m|p0|5"), m.result)
    }

    @Test fun nothingChangedNothingSent() {
        val s = setOf("p0|1", "m|p0|7")
        val m = L.mergeList(s, s, s)
        assertTrue(m.add.isEmpty() && m.remove.isEmpty())
        assertEquals(s, m.result)
    }

    @Test fun resumeNewestWins() {
        val local = mapOf(
            "m|p0|1" to WatchState(600_000, 5_400_000, false, 2_000_000),   // newer on the TV
            "m|p0|2" to WatchState(60_000, 5_400_000, false, 1_000_000),    // older on the TV
            "m|p0|3" to WatchState(5_000, 5_400_000, false, 3_000_000),     // too short to keep
            "e|p0|9" to WatchState(600_000, 2_400_000, false, 3_000_000),   // episode: not this sync
        )
        val server = mapOf(
            "m|p0|1" to ServerResume(100, 5400, 1_500_000, false),
            "m|p0|2" to ServerResume(900, 5400, 1_800_000, false),
            "m|p0|4" to ServerResume(5400, 5400, 1_900_000, true),
        )
        val p = L.planResume(local, server, minResumeMs = 15_000)
        assertEquals(setOf("m|p0|1"), p.toServer.keys)
        assertEquals(setOf("m|p0|2", "m|p0|4"), p.toLocal.keys)
        assertEquals(900_000L, p.toLocal["m|p0|2"]!!.posMs)
        assertEquals(1_800_000L, p.toLocal["m|p0|2"]!!.updated)
        assertTrue(p.toLocal["m|p0|4"]!!.watched)
    }

    @Test fun sameChangeSeenTwiceIsNotSentBack() {
        val p = L.planResume(mapOf("m|p0|1" to WatchState(600_000, 5_400_000, false, 1_000_500)),
            mapOf("m|p0|1" to ServerResume(600, 5400, 1_000_000, false)), 15_000)
        assertTrue(p.toServer.isEmpty() && p.toLocal.isEmpty())
    }
}
