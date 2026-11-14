// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import androidx.compose.ui.graphics.Color
import io.github.kmmarin.tvbro.orbit.CatalogApi
import io.github.kmmarin.tvbro.orbit.OrbitApi
import io.github.kmmarin.tvbro.orbit.OrbitLink
import io.github.kmmarin.tvbro.orbit.OrbitProfiles
import io.github.kmmarin.tvbro.ui.home.HomeFeed
import io.github.kmmarin.tvbro.ui.orbit5.ColorKey
import io.github.kmmarin.tvbro.ui.orbit5.ColorKeys
import io.github.kmmarin.tvbro.ui.orbit5.KeyAction
import io.github.kmmarin.tvbro.ui.orbit5.MeshColors
import io.github.kmmarin.tvbro.ui.profiles.OrbitRing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class Orbit5LogicTest {
    private val server = "http://192.0.2.10:8750"

    @After fun tearDown() = ColorKeys.reset()

    @Test fun catalogHomeParsesHeroRowsAndNewFields() {
        val j = Json.parseToJsonElement(
            """
            {"hero": [{"key": "s|p0|7", "kind": "show", "title": "Glass Harbor", "year": 2024, "poster": "/img?u=a&s=x",
                       "backdrop": "http://cdn.example.com/b.jpg", "rating": 8.2, "genres": ["Drama"], "source": "iptv",
                       "added": 1731500000, "released": null, "colors": ["#16968a", "#f2fffb"], "progress": {"secs": 120, "dur": 3000},
                       "id": "7", "conn": null, "locked": false, "next": {"id": "101", "season": 2, "num": 4, "title": "Ballast"}}],
             "rows": [{"id": "continue", "title": "Continue watching", "items": [
                         {"key": "m|p1|9", "kind": "movie", "title": "Ember Line", "year": null, "poster": null, "backdrop": null,
                          "rating": 7.0, "genres": [], "source": "iptv", "added": null, "released": "2025-01-02", "colors": null,
                          "progress": null, "id": "p1~9", "conn": null, "locked": true}]},
                      {"id": "genre:drama", "title": "Top in Drama", "items": []},
                      {"id": "mylist", "title": "My list", "items": [{"key": "m|c3|abc", "kind": "movie", "title": "Plex film", "source": "plex",
                          "poster": "/v1/media/image?conn=c3&path=%2Fx", "id": "abc", "conn": "c3"}]}]}
            """.trimIndent(),
        ).jsonObject
        val f = CatalogApi.feed(j, server)
        assertEquals(1, f.hero.size)
        val h = f.hero[0]
        assertEquals("2024", h.year)
        assertEquals("8.2", h.rating)
        assertEquals("$server/img?u=a&s=x", h.poster)
        assertEquals("http://cdn.example.com/b.jpg", h.backdrop)
        assertEquals(listOf("#16968a", "#f2fffb"), h.colors)
        assertTrue(h.isShow)
        assertEquals(0.04f, h.progress!!, 0.001f)
        assertEquals(2, h.next!!.season); assertEquals("Ballast", h.next!!.title)
        // empty rows are left out
        assertEquals(listOf("continue", "mylist"), f.rows.map { it.id })
        val e = f.rows[0].items[0]
        assertNull(e.year); assertEquals("7", e.rating); assertTrue(e.locked); assertNull(e.colors); assertEquals("p1~9", e.id)
        val plex = f.rows[1].items[0]
        assertEquals("plex", plex.source); assertEquals("c3", plex.conn); assertEquals("$server/v1/media/image?conn=c3&path=%2Fx", plex.poster)
    }

    @Test fun catalogKeysMapToTheTvsOwnTitles() {
        val j = Json.parseToJsonElement("""{"key": "m|p1|9", "title": "X", "genres": ["Drama"]}""")
        val v = HomeFeed.vodOf(CatalogApi.item(j, server)!!)!!
        assertEquals("p1", v.sourceId); assertEquals("9", v.id); assertEquals("movie", v.kind); assertEquals("m|p1|9", v.key)
        val s = HomeFeed.vodOf(CatalogApi.item(Json.parseToJsonElement("""{"key": "s|p0|7", "title": "Y"}"""), server)!!)!!
        assertEquals("series", s.kind)
    }

    @Test fun imageCookieOnlyForTheHomeServer() {
        val l = OrbitLink(server, "sess", "csrf", "A", "a", "P", false)
        assertEquals(mapOf("Cookie" to "tvbro_s=sess"), OrbitApi.imageHeaders(l, "$server/img?u=1"))
        assertNull(OrbitApi.imageHeaders(l, "http://192.0.2.10:8750.example.com/img"))
        assertNull(OrbitApi.imageHeaders(l, "http://cdn.example.com/a.jpg"))
        assertNull(OrbitApi.imageHeaders(null, "$server/img"))
        assertEquals("$server/img?a", OrbitApi.absolute(server, "/img?a"))
        assertNull(OrbitApi.absolute(server, ""))
    }

    @Test fun profilesFromMe() {
        val me = Json.parseToJsonElement("""{"profiles": [{"id": "p1", "name": "Dani", "kids": false}, {"id": "p2", "name": "Leo", "kids": true}, {"name": "no id"}], "profile": {"id": "p1"}}""").jsonObject
        val l = OrbitProfiles.parseProfiles(me)
        assertEquals(listOf("Dani", "Leo"), l.map { it.name }); assertTrue(l[1].kids)
    }

    @Test fun colorKeysNewestRegistrationWinsAndClears() {
        var hits = ""
        val home = Any(); val page = Any()
        assertFalse(ColorKeys.dispatch(ColorKey.RED.code))
        ColorKeys.set(home, mapOf(ColorKey.RED to KeyAction("My list") { hits += "h" }, ColorKey.BLUE to null))
        assertEquals(mapOf(ColorKey.RED to "My list"), ColorKeys.labels)
        assertTrue(ColorKeys.dispatch(ColorKey.RED.code))
        assertFalse(ColorKeys.dispatch(ColorKey.BLUE.code))
        assertFalse(ColorKeys.dispatch(android.view.KeyEvent.KEYCODE_DPAD_UP))
        ColorKeys.set(page, mapOf(ColorKey.RED to KeyAction("Favourite") { hits += "p" }))
        assertTrue(ColorKeys.dispatch(ColorKey.RED.code))
        ColorKeys.clear(page)
        assertTrue(ColorKeys.dispatch(ColorKey.RED.code))
        assertEquals("hph", hits)
        ColorKeys.clear(home)
        assertTrue(ColorKeys.labels.isEmpty())
    }

    @Test fun orbitRingMatchesTheMockup() {
        // the mockup's avatar centres: focused (960, 796), Maya (554, 743), Guest (314, 598), Leo (1366, 743), Add (1606, 598)
        fun near(d: Float, x: Float, y: Float) { val c = OrbitRing.center(d); assertTrue("$d: $c", abs(c.x - x) < 6 && abs(c.y - y) < 6) }
        near(0f, 960f, 796f); near(-1f, 554f, 743f); near(-2f, 314f, 598f); near(1f, 1366f, 743f); near(2f, 1606f, 598f)
        assertEquals(200f, OrbitRing.size(0f), 0.01f); assertEquals(150f, OrbitRing.size(1.5f), 0.01f)
        assertEquals(1f, OrbitRing.alpha(2f), 0.01f); assertEquals(0f, OrbitRing.alpha(3f), 0.01f)
    }

    @Test fun meshColours() {
        assertEquals(Color(0xFFC3261B), MeshColors.parse("#c3261b"))
        assertNull(MeshColors.parse("nope")); assertNull(MeshColors.fromCatalog(null)); assertNull(MeshColors.fromCatalog(listOf("bad")))
        val c = MeshColors.fromCatalog(listOf("#16968a"))!!
        assertEquals(c.m1, c.m2)
        // the base is m1 at about a tenth: #c3261b -> about #110304
        val b = MeshColors.darkBase(Color(0xFFC3261B))
        assertEquals(0x11 / 255f, b.red, 0.01f); assertEquals(0x03 / 255f, b.green, 0.01f)
    }
}
