// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceNameTest {
    @Test fun acceptsWhatTheServerAccepts() {
        assertEquals("Kitchen", DeviceName.clean("Kitchen"))
        assertEquals("Kids' room 2", DeviceName.clean("  Kids'   room 2 "))
        assertEquals("Salón", DeviceName.clean("Salón"))
        assertEquals("TV (Model-X) & #1+", DeviceName.clean("TV (Model-X) & #1+"))
        assertEquals("x".repeat(32), DeviceName.clean("x".repeat(32)))
    }

    @Test fun refusesWhatTheServerRefuses() {
        for (bad in listOf("", "   ", "x".repeat(33), "<b>", "a\nb", "a\tb", "say \"hi\"", "📺", "tv‮")) {
            assertNull(bad, DeviceName.clean(bad))
        }
    }

    @Test fun theDefaultIsTheModelTidied() {
        assertEquals("SHIELD Android TV", DeviceName.default("SHIELD Android TV"))
        assertEquals("AFTMM", DeviceName.default("AFTMM"))
        assertEquals("TV", DeviceName.default(null))
        assertEquals("TV", DeviceName.default("™®"))
        assertEquals(32, DeviceName.default("y".repeat(50)).length)
        assertEquals("Model 1", DeviceName.default("Model/  1"))
    }
}
