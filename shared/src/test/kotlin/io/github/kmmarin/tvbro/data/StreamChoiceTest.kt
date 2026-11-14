// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamChoiceTest {
    private fun u(id: String, label: String, profile: String = "", what: String = "", rec: Boolean = false, device: String = "") =
        StreamChoice.Use(id, label, profile, what, rec, device)

    @Test fun theDeviceNameComesFirstThenTheProfileThenTheLabel() {
        val r = StreamChoice.rows(listOf(u("a", "TV (Model)", "Kids", "News", device = "Kitchen"),
            u("b", "TV (Other)", "Dad"), u("c", "Chrome on Mac"), u("d", "TV", "Mum", device = "  ")))
        assertEquals(listOf("Kitchen", "Dad", "Chrome on Mac", "Mum"), r.map { it.name })
        assertEquals("Stop Kitchen and watch", r[0].button)
    }

    @Test fun twoDevicesWithTheSameNameAreToldApart() {
        val r = StreamChoice.rows(listOf(u("a", "TV (A)", "Dad", device = "Kitchen"), u("b", "TV (B)", "Mum", device = "Kitchen"),
            u("c", "TV", device = "Den"), u("d", "TV", device = "Den")))
        assertEquals(listOf("Kitchen on TV (A)", "Kitchen on TV (B)", "Den on TV (1)", "Den on TV (2)"), r.map { it.name })
    }

    @Test fun profileNamesTheStreamElseTheDevice() {
        val r = StreamChoice.rows(listOf(u("a", "TV (Model)", "Kitchen", "News"), u("b", "Chrome on Windows", "", "Film")))
        assertEquals(listOf("Stop Kitchen and watch", "Stop Chrome on Windows and watch"), r.map { it.button })
        assertEquals("News", r[0].what)
        assertEquals("a", r[0].id)
    }

    @Test fun sameProfileOnTwoDevicesIsToldApart() {
        val r = StreamChoice.rows(listOf(u("a", "TV (Kitchen)", "Dad"), u("b", "Chrome on Mac", "Dad")))
        assertEquals(listOf("Dad on TV (Kitchen)", "Dad on Chrome on Mac"), r.map { it.name })
    }

    @Test fun identicalNamesAreNumbered() {
        val r = StreamChoice.rows(listOf(u("a", "Chrome on Windows"), u("b", "Chrome on Windows"), u("c", "TV")))
        assertEquals(listOf("Chrome on Windows (1)", "Chrome on Windows (2)", "TV"), r.map { it.name })
    }

    @Test fun recordingsAreListedButNotStoppable() {
        val r = StreamChoice.rows(listOf(u("a", "Recording", "", "The Show", rec = true), u("b", "", "")))
        assertEquals("Recording", r[0].name)
        assertFalse(r[0].stoppable)
        assertEquals("Another device", r[1].name)
        assertTrue(r[1].stoppable)
    }

    @Test fun anOldServerWithoutIdsOffersNoButton() {
        assertFalse(StreamChoice.rows(listOf(u("", "TV"))).single().stoppable)
    }
}
