// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import io.github.kmmarin.tvbro.orbit.OrbitError
import io.github.kmmarin.tvbro.orbit.ProfileSwitch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kids lock on the TV: the server's answers to POST /v1/profiles/select become a PIN prompt, an error or a note. */
class KidsLockTest {
    private fun err(code: Int, msg: String, pinRequired: Boolean? = null) =
        OrbitError(code, msg, JsonObject(mapOf("error" to JsonPrimitive(msg)) +
            (if (pinRequired != null) mapOf("pinRequired" to JsonPrimitive(pinRequired)) else emptyMap())))

    @Test fun pinNeededAsksForIt() {
        val e = err(403, "the parental PIN is needed to leave a kids profile", true)
        assertTrue(e.pinRequired)
        assertEquals(ProfileSwitch.NeedPin(wrong = false), ProfileSwitch.of(e, "x"))
        assertEquals(ProfileSwitch.NeedPin(wrong = true), ProfileSwitch.of(err(403, "wrong PIN", true), "x"))
        assertTrue(ProfileSwitch.pinTitle(true).startsWith("Wrong PIN"))
        assertFalse(ProfileSwitch.pinTitle(false).startsWith("Wrong"))
    }

    @Test fun otherRefusalsAreErrors() {
        assertFalse(err(403, "forbidden").pinRequired)
        assertEquals(ProfileSwitch.Failed("described"), ProfileSwitch.of(err(403, "forbidden"), "described"))
        assertTrue((ProfileSwitch.of(err(429, "too many wrong PINs"), "x") as ProfileSwitch.Failed).message.startsWith("Too many"))
        assertEquals(ProfileSwitch.Failed("offline"), ProfileSwitch.of(java.net.ConnectException(), "offline"))
        assertFalse(OrbitError(500, "x").pinRequired)       // the old constructor still works
    }
}
