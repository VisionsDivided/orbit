// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import io.github.kmmarin.tvbro.data.VodItem
import io.github.kmmarin.tvbro.orbit.CatalogItem
import io.github.kmmarin.tvbro.orbit.CatalogNext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CatalogDeskTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun done() { scope.cancel() }

    @Test fun catalogItemsBecomeVodItems() {
        val local = VodItem("m|p0|7", "p0", "movie", "7", "Local name", null, "Drama", "7", "mkv")
        val c = CatalogItem("m|p0|7", "movie", "Server name", poster = "http://192.0.2.10:8750/img?u=1", genres = listOf("Sci-fi"))
        // a title the playlists have is that item (it knows how to play), with the server's poster when it has none
        assertEquals(local.copy(poster = c.poster), CatalogMap.toVod(c, mapOf(local.key to local)::get))
        // otherwise made from the key
        val s = CatalogMap.toVod(CatalogItem("s|p1|42", "show", "A show", genres = listOf("Drama")), { null })
        assertEquals(listOf("p1", "series", "42", "Drama"), listOf(s.sourceId, s.kind, s.id, s.category))
        // a Plex title never takes an IPTV item's place, even with the same key shape
        val plex = CatalogItem("m|p0|7", "movie", "Plex film", source = "plex", conn = "p0")
        assertTrue(CatalogMap.isMedia(plex))
        assertEquals("Plex film", CatalogMap.toVod(plex, mapOf(local.key to local)::get).name)
    }

    @Test fun captionsMeshAndCount() {
        assertEquals("S2 E4", CatalogMap.caption(CatalogItem("s|p0|1", "show", "X", next = CatalogNext("e", 2, 4, null))))
        assertEquals("E3", CatalogMap.caption(CatalogItem("s|p0|1", "show", "X", next = CatalogNext("e", 0, 3, null))))
        assertEquals("30 min left", CatalogMap.caption(CatalogItem("m|p0|1", "movie", "X", progressSecs = 1800, progressDur = 3600)))
        assertNull(CatalogMap.caption(CatalogItem("m|p0|1", "movie", "X")))
        val m = CatalogMap.mesh(listOf("#c3261b", "#000000"))!!
        assertEquals(0xFFC3261B.toInt(), m.m1)
        assertEquals(0xFF000000.toInt(), m.m2)
        assertTrue("the base is a very dark m1", (m.base shr 16 and 255) < 0x20)
        assertNull(CatalogMap.mesh(listOf("#c3261b")))
        assertNull(CatalogMap.mesh(listOf("red", "blue")))
        assertEquals("1,284 titles", CatalogMap.count(1284))
        assertEquals("1 title", CatalogMap.count(1))
    }

    private fun linkedApp(fake: FakeOrbit): AppState {
        val app = AppState(scope, emptyList())
        app.orbit.showForTest(OrbitSaved(server = "http://192.0.2.10:8750", session = "s", profile = "Dani", profileId = "1"))
        app.catalog.api = fake.catalog
        return app
    }

    @Test fun signedInUsesTheServerAndA404FallsBackToLocal() = runBlocking {
        val fake = FakeOrbit(mapOf("/v1/catalog/movies/browse" to """{"total": 2, "genres": ["Drama", "Sci-fi"], "items": [${FakeOrbit.item("m|p0|1", "One")}, ${FakeOrbit.item("m|plex1|9", "Two", source = "plex", colors = listOf("#16968a", "#f2fffb"))}]}"""))
        val app = linkedApp(fake)
        assertTrue(app.catalog.usable)
        val p = app.catalog.browse("movies", "all", "", "added", 0)!!
        assertEquals(2, p.total)
        assertEquals(listOf("Drama", "Sci-fi"), p.genres)
        assertTrue(fake.asked.single().startsWith("/v1/catalog/movies/browse?source=all&genre=&sort=added&offset=0&limit=${CatalogMap.PAGE}"))
        // the catalogue's colours and source are kept for the backdrop and the badges
        assertEquals("plex", app.catalog.info["m|plex1|9"]?.source)
        // no catalogue on this server (404): null, and the screens use the local titles from now on
        assertNull(app.catalog.home())
        assertTrue(app.catalog.missing)
        assertFalse(app.catalog.usable)
        assertNull(app.catalog.browse("movies", "all", "", "added", 0))
        assertEquals(2, fake.asked.size)                           // nothing more was asked
    }

    @Test fun signedOutAsksNothing() = runBlocking {
        val fake = FakeOrbit(emptyMap())
        val app = AppState(scope, emptyList())
        app.catalog.api = fake.catalog
        assertFalse(app.catalog.usable)
        assertNull(app.catalog.home())
        assertTrue(fake.asked.isEmpty())
    }
}
