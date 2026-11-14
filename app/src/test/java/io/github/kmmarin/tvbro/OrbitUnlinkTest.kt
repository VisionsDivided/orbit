// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import io.github.kmmarin.tvbro.orbit.OrbitUnlink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OrbitUnlinkTest {
    /** A TV with an account's data on it. */
    private class Tv : OrbitUnlink.Steps {
        var wipeMark = false
        var session: String? = "sid"
        var serverSources = setOf("p0", "p1")
        val sources = mutableSetOf("p0", "p1", "local")
        val watch = mutableMapOf("m|p0|9" to 1L, "e|p0|101" to 2L)
        val favourites = mutableSetOf("p0|1", "g|p0|News")
        var failAt: String? = null

        private fun step(name: String) { if (failAt == name) throw IllegalStateException(name) }
        override fun setPending(on: Boolean) { wipeMark = on }
        override fun clearSession() { step("session"); session = null }
        override fun serverSources() = serverSources
        override fun removeSources(ids: Set<String>) { step("sources"); sources -= ids }
        override fun forgetServerSources() { serverSources = emptySet() }
        override fun clearWatchState() { step("watch"); watch.clear() }
        override fun clearAccountData() { step("data"); favourites.clear() }
    }

    @Test fun everythingTheAccountBroughtGoes() {
        val tv = Tv()
        OrbitUnlink.wipe(tv)
        assertEquals(null, tv.session)
        assertEquals(setOf("local"), tv.sources)        // only playlists that came from the server are removed
        assertTrue(tv.watch.isEmpty())
        assertTrue(tv.favourites.isEmpty())              // including TV-only keys (groups of the server's playlists)
        assertTrue(tv.serverSources.isEmpty())
        assertFalse(tv.wipeMark)
    }

    @Test fun anInterruptedWipeStaysPendingAndFinishesNextTime() {
        for (where in listOf("watch", "data", "sources")) {
            val tv = Tv().apply { failAt = where }
            try { OrbitUnlink.wipe(tv); fail() } catch (e: IllegalStateException) { }
            assertTrue("finished at the next start", tv.wipeMark)
            assertEquals(null, tv.session)               // the session is gone first, so the TV is no longer linked
            tv.failAt = null
            OrbitUnlink.wipe(tv)
            assertTrue(tv.favourites.isEmpty())
            assertTrue(tv.watch.isEmpty())
            assertEquals(where, setOf("local"), tv.sources)   // the server's playlists are still known, so still removed
            assertFalse(tv.wipeMark)
        }
    }

    @Test fun wipingTwiceIsHarmless() {
        val tv = Tv()
        OrbitUnlink.wipe(tv)
        OrbitUnlink.wipe(tv)
        assertEquals(setOf("local"), tv.sources)
        assertFalse(tv.wipeMark)
    }
}
