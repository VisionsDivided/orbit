// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import org.junit.Assert.*
import org.junit.Test

/** The per-OS video output choices: Windows and Linux exactly as before, macOS not embedding unless asked to. */
class MpvVideoTest {
    @Test fun optionsPerOs() {
        assertEquals(listOf("wid" to "42", "vo" to "gpu"), MpvVideo.options(Os.WINDOWS, 42))
        assertEquals(listOf("wid" to "42", "gpu-context" to "x11egl", "vo" to "gpu"), MpvVideo.options(Os.LINUX, 42))
    }

    @Test fun macEmbedsOnlyForTheSpike() {
        assertTrue(MpvVideo.embeds(Os.WINDOWS, null)); assertTrue(MpvVideo.embeds(Os.LINUX, null))
        assertFalse(MpvVideo.embeds(Os.MAC, null))
        assertFalse(MpvVideo.embeds(Os.MAC, "render"))
        assertTrue(MpvVideo.embeds(Os.MAC, "wid"))
        // by default macOS never asks AWT / JNA for a native handle
        if (MpvVideo.macMode != "wid") assertEquals(MpvVideo.NO_EMBED, MpvVideo.windowId(java.awt.Canvas(), Os.MAC))
    }

    @Test fun hwdecDefaults() {
        assertEquals("auto-safe", MpvVideo.hwdecChoices(Os.WINDOWS).first())
        assertEquals("v4l2m2m-copy,auto-safe", MpvVideo.hwdecChoices(Os.LINUX).first())
        assertEquals(listOf("auto-safe", "videotoolbox", "no"), MpvVideo.hwdecChoices(Os.MAC))
    }
}
