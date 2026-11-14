// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import io.github.kmmarin.tvbro.data.ChannelFormats
import io.github.kmmarin.tvbro.data.Programme
import io.github.kmmarin.tvbro.data.Rec
import io.github.kmmarin.tvbro.ui.live.LiveLogic
import io.github.kmmarin.tvbro.ui.live.initials
import io.github.kmmarin.tvbro.ui.live.streamPromptText
import io.github.kmmarin.tvbro.ui.live.streamPromptTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rules behind the direction-5 Live TV screens (ui/live/LiveLogic.kt). */
class LiveLogicTest {
    @Test fun yellowCyclesDefaultTsHls() {
        assertEquals(ChannelFormats.TS, LiveLogic.nextFormat(null))
        assertEquals(ChannelFormats.HLS, LiveLogic.nextFormat(ChannelFormats.TS))
        assertNull(LiveLogic.nextFormat(ChannelFormats.HLS))
        assertEquals("HLS", LiveLogic.formatLabel(ChannelFormats.HLS))
        assertEquals("Default", LiveLogic.formatLabel(null))
        assertEquals("HLS", LiveLogic.effectiveFormat(null, globalHls = true))
        assertEquals("TS", LiveLogic.effectiveFormat(ChannelFormats.TS, globalHls = true))
    }

    private val t0 = 1_800_000_000L
    private fun prog(s: Long, e: Long) = Programme("k", t0 + s * 60, t0 + e * 60, "P", null)
    private fun rec(s: Long, e: Long, status: String = "scheduled", key: String = "c1") =
        Rec("r", "P", "Chan", "http://192.0.2.1/x.ts", (t0 + s * 60) * 1000, if (e == 0L) 0 else (t0 + e * 60) * 1000, status = status, chKey = key)

    @Test fun recordingMarksOnlyTheProgrammeItCovers() {
        // 20:00-21:00 recorded with 5 min padding on both sides
        val r = listOf(rec(-5, 65))
        assertNotNull(LiveLogic.recordingOf(r, "c1", "Chan", prog(0, 60)))
        assertNull(LiveLogic.recordingOf(r, "c1", "Chan", prog(-60, 0)))   // the one before: 5 min of padding only
        assertNull(LiveLogic.recordingOf(r, "c1", "Chan", prog(60, 120)))  // the one after
        assertNull(LiveLogic.recordingOf(r, "c2", "Chan", prog(0, 60)))    // another channel
        assertNull(LiveLogic.recordingOf(listOf(rec(-5, 65, status = "done")), "c1", "Chan", prog(0, 60)))
        // until stopped: covers what follows
        assertNotNull(LiveLogic.recordingOf(listOf(rec(0, 0, status = "recording")), "c1", "Chan", prog(60, 120)))
        // recordings of older versions have no channel key: matched by name
        assertNotNull(LiveLogic.recordingOf(listOf(rec(-5, 65, key = "")), "c1", "Chan", prog(0, 60)))
        assertTrue(LiveLogic.recordingNow(listOf(rec(0, 0, status = "recording")), "c1", "Chan"))
        assertFalse(LiveLogic.recordingNow(r, "c1", "Chan"))
    }

    @Test fun progressIsClamped() {
        assertEquals(0.5f, LiveLogic.progress(0, 100, 50), 0.001f)
        assertEquals(0f, LiveLogic.progress(0, 100, -5), 0.001f)
        assertEquals(1f, LiveLogic.progress(0, 100, 500), 0.001f)
    }

    @Test fun streamPromptWords() {
        assertEquals("Both streams are in use", streamPromptTitle(2))
        assertEquals("The stream is in use", streamPromptTitle(1))
        assertEquals("All 3 streams are in use", streamPromptTitle(3))
        assertEquals("Your provider allows 2 at a time. Choose which one to stop to watch Vista Cinema here.", streamPromptText(2, "Vista Cinema"))
    }

    @Test fun channelInitials() {
        assertEquals("VC", initials("Vista Cinema"))
        assertEquals("MN2", initials("Meridian News 24"))
        assertEquals("BBC", initials("BBC"))
        assertEquals("?", initials(" "))
    }
}
