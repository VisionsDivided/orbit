// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** The player overlay's ASS (DESIGN-SPEC-5 §10) and the recording's own stream id. */
class PlayerOsdTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun done() { scope.cancel() }

    @Test fun quickActionsAreWhereClicksFindThem() {
        val items = (0 until 10).map { PlayerOsd.Qa("a$it", "A $it", "x") }
        for (i in items.indices) {
            val cx = PlayerOsd.qaX(i) + PlayerOsd.QA_W / 2
            assertEquals(i, PlayerOsd.qaAt(cx, PlayerOsd.QA_Y + PlayerOsd.QA_D / 2, items.size))
        }
        assertEquals(-1, PlayerOsd.qaAt(PlayerOsd.qaX(0) + 10, 200.0, items.size))      // above the row
        assertEquals(-1, PlayerOsd.qaAt(PlayerOsd.qaX(10) + 10, PlayerOsd.QA_Y + 10, items.size)) // past the last one
        // the spec's 96 px margin and 846 px row at 1080 lines are 64 and 564 at mpv's 720
        assertEquals(64.0, PlayerOsd.SIDE, .01)
        assertEquals(564.0, PlayerOsd.QA_Y, .01)
    }

    @Test fun assPiecesAreSafe() {
        assertEquals("&H00&", PlayerOsd.alpha(1.0))
        assertEquals("&HFF&", PlayerOsd.alpha(0.0))
        // a programme title can never open an override block or break the line
        assertEquals("a (b) c/d e", PlayerOsd.esc("a {b} c\\d\ne"))
        val t = PlayerOsd.text(10.0, 20.0, "{\\pos(0,0)}x", 20.0)
        assertTrue(t.endsWith("(/pos(0,0))x"))
        val box = PlayerOsd.box(0.0, 0.0, 100.0, 40.0, 10.0, PlayerOsd.WHITE)
        assertTrue(box.contains("\\p1}m 10 0 l 90 0 b") && box.endsWith("{\\p0}"))
        // the scrims cover the bottom two thirds and the top quarter
        assertTrue(PlayerOsd.scrims(1280.0).size > 20)
        // the legend has one pill per key plus the hints
        val l = PlayerOsd.legend(listOf(PlayerOsd.Key(PlayerOsd.RED, "Favourite"), PlayerOsd.Key(PlayerOsd.BLUE, "Options")), listOf("Enter" to "Select"))
        assertTrue(l.any { it.contains("Favourite") } && l.any { it.contains("Options") } && l.any { it.contains("Select") })
    }

    @Test fun aRecordingHasItsOwnStreamId() {
        val app = AppState(scope, emptyList())
        val player = "dk" + "0123456789abcdef0123456789abcdef"
        app.orbit.showForTest(OrbitSaved(server = "http://192.0.2.10:8750", session = "s", streamSid = player))
        val a = app.orbit.recSid("rec1"); val b = app.orbit.recSid("rec2")
        assertTrue(a.length >= 32)                                  // the API's sid is 32+ characters
        assertNotEquals(a, b)
        // the server shows the first 6 characters: the player never takes a recording's slot for its own
        assertNotEquals(player.take(6), a.take(6))
        assertFalse(a.startsWith(player.take(6)))
    }
}
