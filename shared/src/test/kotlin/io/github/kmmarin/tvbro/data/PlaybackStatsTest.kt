// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStatsTest {
    private var t = 1_000_000L
    private val s = PlaybackStats { t }

    @Test fun startTimeAndStalls() {
        s.begin(1, "live", "News One")
        s.buffering(true); t += 1200; s.firstFrame(); s.buffering(false)       // start buffering is not a stall
        t += 60_000; s.buffering(true); t += 2_000; s.buffering(false)        // stall 1: 2 s
        t += 30_000; s.buffering(true); t += 500; s.buffering(false)          // stall 2: 0.5 s
        val x = s.snapshot()!!
        assertEquals(1200L, x.firstFrameMs)
        assertEquals(2, x.stalls)
        assertEquals(2500L, x.stallMs)
        assertEquals(2000L, x.longestStallMs)
        assertTrue(x.line().contains("stalls 2"))
    }

    @Test fun aStallStillGoingCountsUpToNow() {
        s.begin(1, "live", "A"); s.firstFrame()
        t += 10_000; s.buffering(true); t += 4_000
        val x = s.snapshot()!!
        assertEquals(1, x.stalls)
        assertEquals(4000L, x.stallMs)
        assertEquals(4000L, x.longestStallMs)
    }

    @Test fun sameStreamAgainIsAReconnectAnotherIsANewSession() {
        s.begin(7, "live", "A"); s.firstFrame()
        s.error("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED")
        assertNull("a reconnect keeps the session", s.begin(7, "live", "A"))
        t += 900; s.firstFrame()
        val old = s.begin(8, "live", "B")!!
        assertEquals(1, old.reconnects)
        assertEquals(1, old.errors)
        assertEquals("A", old.label)
        assertEquals("B", s.snapshot()!!.label)
        assertEquals(0, s.snapshot()!!.reconnects)
    }

    @Test fun durationIsPlayingTime() {
        s.begin(1, "live", "A"); s.firstFrame(); s.playing(true)
        t += 30_000; s.playing(false)            // paused / stopped
        t += 600_000                             // not watching: does not count
        s.playing(true); t += 10_000
        assertEquals(40_000L, s.snapshot()!!.durationMs)
    }

    @Test fun endAndBitrate() {
        s.begin(1, "vod", "Film"); s.firstFrame(); s.bitrate(5_200_000); s.network(23_000_000)
        val x = s.end()!!
        assertEquals(5200, x.bitrateKbps)
        assertEquals(23000, x.networkKbps)
        assertFalse(s.active)
        assertNull(s.end())
    }

    @Test fun jsonHasNumbersAndNamesOnly() {
        s.begin(1, "live", "News \"One\""); s.firstFrame()
        val j = PlaybackStats.toJson(listOf(s.end()!!), "BRAVIA", "Orbit 0.1.0")
        assertTrue(j.contains("\"channel\":\"News \\\"One\\\"\""))
        assertFalse(j.contains("http"))
    }
}
