// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import io.github.kmmarin.tvbro.orbit.BadgeKind
import io.github.kmmarin.tvbro.orbit.DiscoverApi
import io.github.kmmarin.tvbro.orbit.DiscoverRow
import io.github.kmmarin.tvbro.orbit.DiscoverTitle
import io.github.kmmarin.tvbro.orbit.DownloadInfo
import io.github.kmmarin.tvbro.ui.discover.DiscoverFeed
import io.github.kmmarin.tvbro.ui.discover.tabRows
import io.github.kmmarin.tvbro.ui.shell.isPhoneLayout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Discover (Seerr) parsing, badges and rows, and the phone / TV layout switch. */
class DiscoverLogicTest {
    private val server = "http://192.0.2.10:8750"
    private val now = DiscoverApi.isoMs("2026-11-14T10:00:00.000Z")!!

    @Test fun rowsAndRequestsParse() {
        val j = Json.parseToJsonElement(
            """
            {"rows": [{"id": "trending", "title": "Trending", "items": [
                {"type": "movie", "tmdb": 550, "title": "Requestable Film", "year": 1999, "overview": "x", "rating": 7.25,
                 "poster": "https://image.tmdb.org/t/p/w342/p.jpg", "backdrop": null, "status": "requestable"},
                {"type": "tv", "tmdb": 1399, "title": "Partly Show", "year": null, "status": "partial", "requestStatus": "pending",
                 "download": {"percent": 42, "size": 100, "sizeLeft": 58, "eta": "2026-11-14T10:18:00.000Z", "items": 1}},
                {"type": "movie", "tmdb": 7, "title": "In the catalogue", "status": "available",
                 "play": {"key": "m|p0|7", "kind": "movie", "title": "In the catalogue", "poster": "/img?u=a", "source": "iptv", "locked": true}},
                {"type": "person", "tmdb": 3, "title": "Someone"}]}], "stale": false}
            """.trimIndent(),
        ).jsonObject
        val items = DiscoverApi.parseRows(j, server).single().items
        assertEquals(3, items.size)
        assertEquals("7.3", items[0].rating)   // one decimal
        assertEquals("1999", items[0].year)
        assertEquals(DownloadInfo(42, 100, 58, "2026-11-14T10:18:00.000Z"), items[1].download)
        assertEquals("m|p0|7", items[2].play?.key)
        assertEquals("$server/img?u=a", items[2].play?.poster)
        assertTrue(items[2].play!!.locked)

        val r = Json.parseToJsonElement(
            """
            {"requests": [{"id": 4, "type": "tv", "tmdb": 1399, "status": "approved", "mediaStatus": "processing", "created": "2026-11-01",
                           "seasons": [1, 2], "requestedBy": {"seerrUserId": 2, "name": "Sam"},
                           "download": {"percent": 75, "size": 4, "sizeLeft": 1, "eta": null, "items": 2},
                           "title": {"type": "tv", "tmdb": 1399, "title": "Partly Show", "status": "processing"}},
                          {"id": 5, "type": "movie", "tmdb": 999, "status": "declined", "mediaStatus": "requestable", "title": null,
                           "requestedBy": {"name": ""}}], "linked": true}
            """.trimIndent(),
        ).jsonObject
        val reqs = DiscoverApi.parseRequests(r, server)
        assertEquals(listOf(1, 2), reqs[0].seasons)
        assertEquals("Sam", reqs[0].by)
        assertEquals(BadgeKind.DOWNLOADING, DiscoverApi.requestBadge(reqs[0], now).kind)
        assertEquals("Downloading 75%", DiscoverApi.requestBadge(reqs[0], now).label)
        assertEquals("Declined", DiscoverApi.requestBadge(reqs[1], now).label)
        assertEquals("TMDb 999", DiscoverApi.requestTitle(reqs[1]).title)
    }

    @Test fun badges() {
        val t = DiscoverTitle("movie", 1, "X")
        assertNull(DiscoverApi.badge(t))
        assertEquals("Requested", DiscoverApi.badge(t.copy(requestStatus = "pending"))?.label)
        assertEquals("Pending", DiscoverApi.badge(t.copy(status = "pending"))?.label)
        assertEquals("Approved", DiscoverApi.badge(t.copy(status = "processing", requestStatus = "approved"))?.label)
        assertEquals("Available", DiscoverApi.badge(t.copy(status = "available"))?.label)
        assertEquals("Partly available", DiscoverApi.badge(t.copy(status = "partial"))?.label)
        val dl = t.copy(status = "processing", requestStatus = "approved", download = DownloadInfo(42, 10, 6, "2026-11-14T10:18:00.000Z"))
        val b = DiscoverApi.badge(dl, now)!!
        assertEquals(BadgeKind.DOWNLOADING, b.kind)
        assertEquals("Downloading 42% · ~18 min", b.label)
        assertEquals(0.42f, b.fraction, 0.001f)
        // in the catalogue: Available (Play) whatever Seerr says
        val play = io.github.kmmarin.tvbro.orbit.CatalogItem("m|p0|1", "movie", "X")
        assertEquals(BadgeKind.AVAILABLE, DiscoverApi.badge(dl.copy(play = play), now)?.kind)
    }

    @Test fun timeLeftLabels() {
        fun l(eta: String?) = DiscoverApi.downloadLabel(DownloadInfo(10, 0, 0, eta), now)
        assertEquals("Downloading 10%", l(null))
        assertEquals("Downloading 10%", l("2026-11-14T09:00:00Z"))              // past: left out
        assertEquals("Downloading 10% · ~1 min", l("2026-11-14T10:00:30Z"))
        assertEquals("Downloading 10% · ~2 h 5 min", l("2026-11-14T12:05:00Z"))
        assertEquals("Downloading 10% · ~2 h", l("2026-11-14T12:02:00Z"))
        assertEquals("Downloading 10% · ~3 d", l("2026-11-17T10:00:00Z"))
        assertEquals("Downloading 10% · ~18 min", l("2026-11-14T11:18:00+01:00"))
        assertNull(DiscoverApi.isoMs("soon"))
    }

    @Test fun rowsAreShapedAsTheSpec() {
        fun m(i: Long) = DiscoverTitle("movie", i, "m$i")
        fun s(i: Long) = DiscoverTitle("tv", i, "s$i")
        val raw = listOf(
            DiscoverRow("trending", "Trending", listOf(m(1), s(2), m(3))),
            DiscoverRow("movies", "Popular movies", listOf(m(4), m(5))),
            DiscoverRow("tv", "Popular TV", listOf(s(6))),
            DiscoverRow("upcoming", "Coming soon", listOf(m(7))),
        )
        val rows = DiscoverFeed.shapeRows(raw)
        assertEquals(listOf("trending-movies", "trending-shows", "upcoming-movies", "popular"), rows.map { it.id })
        assertEquals(listOf(1L, 3L), rows[0].items.map { it.tmdb })
        assertEquals("films and shows interleaved", listOf(4L, 6L, 5L), rows.last().items.map { it.tmdb })
        assertEquals(listOf("trending-movies", "upcoming-movies", "popular"), tabRows(rows, 1).map { it.id })
        assertEquals(listOf(6L), tabRows(rows, 2).last().items.map { it.tmdb })
        assertTrue(tabRows(rows, 3).isEmpty())
    }

    @Test fun phoneOrTvLayout() {
        assertTrue(isPhoneLayout(tv = false, touch = true, smallestWidthDp = 412, widthDp = 412, heightDp = 915))   // phone upright
        assertTrue(isPhoneLayout(false, true, 412, 915, 412))                                                        // phone sideways
        assertTrue(isPhoneLayout(false, true, 800, 800, 1280))                                                       // tablet upright
        assertFalse(isPhoneLayout(false, true, 800, 1280, 800))                                                      // tablet sideways
        assertFalse(isPhoneLayout(tv = true, touch = false, smallestWidthDp = 540, widthDp = 960, heightDp = 540))  // TV
        assertFalse(isPhoneLayout(tv = true, touch = true, smallestWidthDp = 400, widthDp = 400, heightDp = 700))
        assertFalse(isPhoneLayout(tv = false, touch = false, smallestWidthDp = 400, widthDp = 960, heightDp = 400)) // a stick
    }
}
