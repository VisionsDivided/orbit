// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import io.github.kmmarin.tvbro.orbit.BadgeKind
import io.github.kmmarin.tvbro.orbit.DiscoverApi
import io.github.kmmarin.tvbro.orbit.DiscoverRow
import io.github.kmmarin.tvbro.orbit.DiscoverTitle
import io.github.kmmarin.tvbro.orbit.DownloadInfo
import io.github.kmmarin.tvbro.ui.discover.DiscoverFeed
import io.github.kmmarin.tvbro.ui.discover.DiscoverLogic
import io.github.kmmarin.tvbro.ui.discover.PagedRow
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
        assertEquals(listOf("trending-movies", "trending-shows", "upcoming-movies", "popular-movies", "popular-shows"), rows.map { it.id })
        assertEquals(listOf(1L, 3L), rows[0].items.map { it.tmdb })
        val paged = DiscoverLogic.initialRows(raw)
        assertEquals(listOf("trending-movies", "upcoming-movies", "popular-movies"), DiscoverLogic.tabRows(paged, 1).map { it.id })
        assertEquals(listOf("trending-shows", "popular-shows"), DiscoverLogic.tabRows(paged, 2).map { it.id })
        assertTrue(DiscoverLogic.tabRows(paged, 3).isEmpty())
        // a list the server could not load (pages 0) is an empty row that loads nothing
        assertFalse(DiscoverLogic.initialRows(emptyList()).any { it.hasMore })
    }

    // ---- paged rows (orbit/tv-discover2) ----

    private fun m(i: Long) = DiscoverTitle("movie", i, "m$i")
    private fun s(i: Long) = DiscoverTitle("tv", i, "s$i")

    @Test fun rowPagesAddEachTitleOnceAndOnlyTheirType() {
        var row = DiscoverLogic.initialRows(listOf(DiscoverRow("trending", "Trending", listOf(m(1), s(2), m(3)), page = 1, pages = 30)))
            .first { it.id == "trending-shows" }
        assertEquals(listOf(2L), row.items.map { it.tmdb })
        assertEquals(1, row.page); assertEquals(30, row.pages)
        // a mostly-film list: the shows row keeps filling until it has 14, at most 4 pages a go
        assertTrue(DiscoverLogic.keepFilling(row, 0))
        row = DiscoverLogic.withPage(row, 2, 30, listOf(s(2), s(4), m(5), s(6)))
        assertEquals(listOf(2L, 4L, 6L), row.items.map { it.tmdb })
        assertEquals(2, row.page)
        assertTrue(DiscoverLogic.keepFilling(row, 2))
        assertFalse("a page that added enough stops the fill", DiscoverLogic.keepFilling(row, DiscoverLogic.NEAR_END))
        row = DiscoverLogic.withPage(row, 3, 30, (10L..30L).map { s(it) })
        assertTrue(row.items.size >= DiscoverLogic.MIN_FILL)
        assertFalse(DiscoverLogic.keepFilling(row, 1))
        // near the end (6 cards) of a row with more pages: load
        assertTrue(DiscoverLogic.wantsMore(row, row.items.size - 6))
        assertFalse(DiscoverLogic.wantsMore(row, row.items.size - 7))
        assertFalse(DiscoverLogic.wantsMore(row.copy(page = 30), row.items.size - 1))
        // an unfiltered row never "fills"
        assertFalse(DiscoverLogic.keepFilling(PagedRow("x", "X", "upcoming", null, listOf(m(1)), 1, 9), 0))
    }

    @Test fun rowPageAndSearchParse() {
        val j = Json.parseToJsonElement(
            """{"id": "trending", "title": "Trending", "page": 3, "pages": 500, "stale": false,
                "items": [{"type": "movie", "tmdb": 5, "title": "A", "poster": "/img?u=https%3A%2F%2Fimage.tmdb.org%2Ft%2Fp%2Fw342%2Fa.jpg&s=x"}]}""",
        ).jsonObject
        val r = DiscoverApi.parseRow(j, server)
        assertEquals(3, r.page); assertEquals(500, r.pages)
        assertEquals("signed /img links are loaded from the home server", "$server/img?u=https%3A%2F%2Fimage.tmdb.org%2Ft%2Fp%2Fw342%2Fa.jpg&s=x", r.items[0].poster)
        val sj = Json.parseToJsonElement(
            """{"results": [{"type": "tv", "tmdb": 9, "title": "Show"}], "page": 1, "pages": 4, "total": 71, "stale": false,
                "people": [{"id": 31, "name": "Ada Example", "photo": "https://image.tmdb.org/t/p/w185/face_1.jpg",
                            "knownFor": [{"type": "movie", "tmdb": 1, "title": "One"}, {"type": "movie", "tmdb": 2, "title": "Two"}]}]}""",
        ).jsonObject
        val sp = DiscoverApi.parseSearch(sj, server)
        assertEquals(4, sp.pages); assertEquals(71, sp.total)
        assertEquals("$server/v1/discover/image?p=%2Fw185%2Fface_1.jpg", sp.people.single().photo)
        assertEquals(listOf("One", "Two"), sp.people.single().knownFor.map { it.title })
        assertEquals(listOf(1L, 9L), DiscoverLogic.addResults(listOf(m(1)), listOf(m(1), s(9))).map { it.tmdb })
    }

    @Test fun imagesGoThroughTheHomeServer() {
        assertEquals("$server/img?u=x&s=y", DiscoverApi.image(server, "/img?u=x&s=y"))
        assertEquals("$server/v1/discover/image?p=%2Fw500%2Fb-2.png", DiscoverApi.image(server, "https://image.tmdb.org/t/p/w500/b-2.png"))
        assertNull("an unknown size", DiscoverApi.image(server, "https://image.tmdb.org/t/p/w9999/b.jpg"))
        assertNull("a path in the file name", DiscoverApi.image(server, "https://image.tmdb.org/t/p/w185/../b.jpg"))
        assertNull("another host", DiscoverApi.image(server, "https://example.com/t/p/w185/b.jpg"))
        assertNull(DiscoverApi.image(server, null))
    }

    @Test fun downloadPercentAndIsoEta() {
        val d = DiscoverApi.download(Json.parseToJsonElement("""{"percent": 64.6, "size": 4000000000, "sizeLeft": 1000000000, "eta": "2026-11-14T10:18:00Z", "items": 3}"""))!!
        assertEquals(65, d.percent)
        assertEquals(3, d.items)
        assertEquals("Downloading 65% · ~18 min", DiscoverApi.downloadLabel(d, now))
        assertEquals("3.0 GB of 4.0 GB · 3 files", DiscoverLogic.downloadDetail(d))
        assertEquals("1 MB", DiscoverLogic.bytes(10))
    }

    @Test fun titlePageParses() {
        val j = Json.parseToJsonElement(
            """{"type": "tv", "tmdb": 1399, "title": "Tidewater", "year": 2024, "status": "partial", "rating": 8.4,
                "genres": ["Drama", "Mystery", "Crime", "Thriller"], "tagline": "The sea keeps score.", "date": "2024-03-01", "runtime": 52,
                "seasonCount": 3, "episodeCount": 24, "networks": ["Example TV"], "showStatus": "Returning Series",
                "seasons": [{"season": 0, "episodes": 2, "name": "Specials", "status": "requestable"},
                            {"season": 1, "episodes": 8, "name": "Season 1", "status": "available"},
                            {"season": 2, "episodes": 8, "name": "Season 2", "status": "requestable"},
                            {"season": 3, "episodes": 8, "name": "", "status": "requestable"}],
                "cast": [{"id": 7, "name": "Ada Example", "character": "Mara", "photo": "https://image.tmdb.org/t/p/w185/ada.jpg"},
                         {"id": 8, "name": "Ben Example", "character": "", "photo": null}],
                "trailer": {"name": "Trailer", "url": "https://www.youtube.com/watch?v=abcdef12"},
                "requests": [{"id": 4, "status": "pending", "created": "2026-11-01T09:30:00.000Z", "by": "Sam", "seasons": [2]},
                             {"id": 3, "status": "completed", "created": "2026-01-01T09:30:00.000Z", "by": "Dani", "seasons": [1]}],
                "seerrUrl": "http://192.0.2.20:5055/tv/1399", "stale": false}""",
        ).jsonObject
        val p = DiscoverApi.parseTitlePage(j, server)!!
        assertEquals(listOf(1, 2, 3), p.seasons.map { it.season })   // specials left out
        assertEquals("Season 3", p.seasons[2].name)
        assertEquals("$server/v1/discover/image?p=%2Fw185%2Fada.jpg", p.cast[0].photo)
        assertNull(p.cast[1].photo)
        assertEquals("https://www.youtube.com/watch?v=abcdef12", p.trailer)
        assertEquals("http://192.0.2.20:5055/tv/1399", p.seerrUrl)
        assertEquals(listOf("2024", "3 seasons · 24 episodes", "Drama · Mystery · Crime"), DiscoverLogic.metaParts(p))
        assertEquals(listOf("Requested by Sam on 1 Nov 2026 · season 2 · waiting for approval"),
            DiscoverLogic.requestLines(p.requests, java.util.Locale.UK, java.util.TimeZone.getTimeZone("UTC")))
        assertTrue(DiscoverLogic.canRequest(p))
        assertEquals("Request more", DiscoverLogic.requestLabel(p))
        // a trailer link that is not a youtube.com watch page is dropped
        val bad = Json.parseToJsonElement("""{"type": "movie", "tmdb": 1, "title": "X", "trailer": {"url": "https://example.com/x"}, "runtime": 125}""").jsonObject
        val bp = DiscoverApi.parseTitlePage(bad, server)!!
        assertNull(bp.trailer)
        assertEquals(listOf("2 h 5 min"), DiscoverLogic.metaParts(bp))
        assertTrue(DiscoverLogic.canRequest(bp))
        assertFalse("in the catalogue: Play, not Request", DiscoverLogic.canRequest(bp.copy(title = bp.title.copy(play = io.github.kmmarin.tvbro.orbit.CatalogItem("m|p0|1", "movie", "X")))))
        assertFalse("already requested", DiscoverLogic.canRequest(bp.copy(title = bp.title.copy(requestStatus = "pending"))))
    }

    @Test fun seasonChooser() {
        val seasons = listOf(
            io.github.kmmarin.tvbro.orbit.DiscoverSeason(1, 8, "Season 1", "available"),
            io.github.kmmarin.tvbro.orbit.DiscoverSeason(2, 8, "Season 2", "requestable"),
            io.github.kmmarin.tvbro.orbit.DiscoverSeason(3, 8, "Season 3", "requestable"),
        )
        assertEquals(setOf(2, 3), DiscoverLogic.toggleAll(emptySet(), seasons))
        assertEquals(emptySet<Int>(), DiscoverLogic.toggleAll(setOf(2, 3), seasons))
        assertEquals(setOf(2), DiscoverLogic.toggleSeason(setOf(2, 3), 3, seasons))
        assertEquals("a season already there cannot be picked", setOf(2), DiscoverLogic.toggleSeason(setOf(2), 1, seasons))
        assertEquals("all seasons", DiscoverLogic.seasonsText(listOf(3, 2), 2))
        assertEquals("season 2", DiscoverLogic.seasonsText(listOf(2), 2))
        assertEquals("seasons 1, 3", DiscoverLogic.seasonsText(listOf(3, 1), 5))
        val body = DiscoverApi.requestBody(s(1399), listOf(3, 2))
        assertEquals("""{"type":"tv","tmdb":1399,"seasons":[2,3]}""", body.toString())
        assertEquals("""{"type":"movie","tmdb":5}""", DiscoverApi.requestBody(m(5), listOf(1)).toString())
        assertEquals("""{"type":"tv","tmdb":7}""", DiscoverApi.requestBody(s(7), null).toString())
    }

    @Test fun pollsOnlyWhileSomethingIsOnItsWay() {
        assertTrue(DiscoverLogic.shouldPoll(m(1).copy(download = DownloadInfo(5, 0, 0, null))))
        assertTrue(DiscoverLogic.shouldPoll(m(1).copy(status = "processing")))
        assertFalse(DiscoverLogic.shouldPoll(m(1).copy(status = "available")))
        assertEquals(400L, DiscoverLogic.HERO_SETTLE_MS)
        assertEquals(30_000L, DiscoverLogic.TITLE_POLL_MS)
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
