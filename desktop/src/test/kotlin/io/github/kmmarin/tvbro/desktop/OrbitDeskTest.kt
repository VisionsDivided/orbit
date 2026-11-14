// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import io.github.kmmarin.tvbro.data.Source
import io.github.kmmarin.tvbro.data.WatchState
import io.github.kmmarin.tvbro.orbit.OrbitError
import io.github.kmmarin.tvbro.orbit.OrbitLink
import io.github.kmmarin.tvbro.orbit.OrbitMe
import io.github.kmmarin.tvbro.orbit.OrbitProfile
import io.github.kmmarin.tvbro.orbit.OrbitSyncLogic
import io.github.kmmarin.tvbro.orbit.ServerNext
import io.github.kmmarin.tvbro.orbit.ServerShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OrbitDeskTest {
    @Test fun deviceNames() {
        assertEquals("Orbit on Windows (DESK-1)", OrbitDesk.deviceName(Os.WINDOWS, "DESK-1"))
        assertEquals("Orbit on macOS", OrbitDesk.deviceName(Os.MAC, "  "))
        assertEquals("Orbit on Linux", OrbitDesk.deviceName(Os.LINUX, null))
    }

    @Test fun serverPlaylistsMayBeRelativeAndAlwaysOn() {
        val s = Source("p1", "Main", "xtream", server = "/x/p1", user = "stand-in", pass = "token", enabled = false)
        val l = OrbitDesk.localSource(s, "http://192.0.2.10:8750")
        assertEquals("http://192.0.2.10:8750/x/p1", l.server)
        assertEquals(true, l.enabled)
        assertEquals("stand-in", l.user)                       // logins are kept as the server hands them out
        val m = OrbitDesk.localSource(Source("p0", "M3U", "m3u", url = "http://tv.example.com/a.m3u", epgUrl = "//cdn.example.com/g.xml"), "http://192.0.2.10:8750")
        assertEquals("http://tv.example.com/a.m3u", m.url)
        assertEquals("//cdn.example.com/g.xml", m.epgUrl)
    }

    @Test fun firstSignInReplacesTypedPlaylistsLaterOnlyTheServers() {
        val local = listOf(Source("uuid-1", "Mine", "m3u"), Source("p0", "Main", "xtream"))
        val server = listOf(Source("p0", "Main", "xtream"), Source("p1", "Other", "m3u"))
        assertEquals(setOf("uuid-1"), OrbitDesk.toRemove(local, emptySet(), server))
        assertEquals(setOf("p2"), OrbitDesk.toRemove(local, setOf("p0", "p2"), server))
    }

    @Test fun myListKeepsItsOrder() {
        assertEquals(listOf("b", "a", "c"), OrbitDesk.ordered(listOf("b", "x", "a"), setOf("a", "b", "c")))
    }

    @Test fun errorsInWords() {
        assertEquals("Wrong username or password.", OrbitDesk.describe(OrbitError(403, "wrong username or password")))
        assertTrue(OrbitDesk.describe(OrbitError(429, "too many")).startsWith("Too many"))
        assertTrue(OrbitDesk.describe(java.net.ConnectException()).contains("cannot be reached"))
    }

    @Test fun savedSignInRoundTripsAndHoldsNoPassword() {
        val me = OrbitMe(OrbitLink("http://192.0.2.10:8750", "sess", "csrf", "Alex", "alex", "Kids", true),
            listOf(OrbitProfile("p1", "Alex", false), OrbitProfile("p2", "Kids", true)), "p2")
        val s = OrbitSaved(streamSid = "dkabc").with(me)
        val text = Json2.json.encodeToString(OrbitSaved.serializer(), s)
        val back = Json2.json.decodeFromString(OrbitSaved.serializer(), text)
        assertEquals(s, back)
        assertEquals(me.link, back.link)
        assertEquals("p2", back.profileId)
        assertTrue("password" !in text)
        assertNull(OrbitSaved().link)
    }

    @Test fun remoteStateAndShowRowsLandInTheWatchStore() {
        val f = File(Files.createTempDirectory("orbit-watch").toFile(), "watch.json")
        val w = WatchStore(f)
        w.rememberSubs("s|p0|7", true, "en")
        // the server knows a newer next episode for the show: the row changes, the caption choice stays
        val plan = OrbitSyncLogic.planShows(w.data.series.mapValues { WatchStore.toShared(it.value) },
            listOf(ServerShow("7", "s|p0|7", "101", ServerNext("102", 1, 2, "Two"), false, false, Long.MAX_VALUE / 2)))
        val rows = plan.map { WatchStore.fromShared(it, w.data.series[it.key]) }
        w.applyRemote(mapOf("m|p0|9" to (WatchState(300_000, 5_400_000, false, 1000) to null),
            "e|p0|101" to (WatchState(0, 2_400_000, true, 2000) to "s|p0|7")), rows)
        assertEquals(300_000L, w.state("m|p0|9")!!.posMs)
        assertEquals("s|p0|7", w.seriesKeyOf("e|p0|101"))
        val r = w.series("s|p0|7")!!
        assertEquals(listOf("102", "Two"), listOf(r.nextEp, r.nextTitle))
        assertEquals(true, r.active)
        assertEquals(true, r.subsOn)
        assertEquals("en", r.subsLang)
        w.clearAll()
        assertTrue(w.data.items.isEmpty() && w.data.series.isEmpty() && w.data.snaps.isEmpty())
    }
}
