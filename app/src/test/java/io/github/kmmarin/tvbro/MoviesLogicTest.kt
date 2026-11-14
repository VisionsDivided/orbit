// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import io.github.kmmarin.tvbro.orbit.CatalogItem
import io.github.kmmarin.tvbro.orbit.CatalogNext
import io.github.kmmarin.tvbro.ui.movies.Title
import io.github.kmmarin.tvbro.ui.movies.Titles
import io.github.kmmarin.tvbro.ui.movies.collSummary
import io.github.kmmarin.tvbro.ui.movies.gridMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Movies / Shows: catalog items to titles, captions, counts and collection summaries (ui/movies). */
class MoviesLogicTest {
    @Test fun iptvItemKeepsTheTvKeyAndPlaylistId() {
        val t = Titles.of(CatalogItem("m|p1|42", "movie", "Ember Line", year = "2025", genres = listOf("Action"), source = "iptv", id = "p1~42"))
        assertEquals("m|p1|42", t.key)
        assertFalse(t.media); assertFalse(t.show)
        assertEquals("p1", t.vod!!.sourceId); assertEquals("42", t.vod!!.id); assertEquals("movie", t.vod!!.kind)
        assertEquals("2025 · Action", t.meta)
    }

    @Test fun plexItemIsMediaWithItsConnection() {
        val t = Titles.of(CatalogItem("s|c9|abc", "show", "Fathom Station", source = "plex", id = "abc", conn = "c9",
            next = CatalogNext("e1", 2, 4, "Ballast")))
        assertTrue(t.media); assertTrue(t.show)
        assertEquals("s|c9|abc", t.vod!!.key)       // My list uses exactly the catalog key
        assertEquals("c9", t.vod!!.sourceId); assertEquals("abc", t.vod!!.id); assertEquals("series", t.vod!!.kind)
        assertEquals("S2 E4 · Ballast", t.meta)
    }

    @Test fun filmInProgressSaysWhatIsLeft() {
        val t = Titles.of(CatalogItem("m|p0|7", "movie", "Glass Harbor", source = "iptv", progressSecs = 600, progressDur = 3000))
        assertEquals("40 min left", t.meta)
        assertEquals(0.2f, t.progress!!, 0.001f)
    }

    @Test fun oddKeysHaveNoTvItem() {
        assertNull(Titles.vodOf(CatalogItem("x", "movie", "X")))
    }

    @Test fun counts() {
        assertEquals("1,284 titles", Titles.count(1284))
        assertEquals("1 title", Titles.count(1))
        assertEquals("240+ titles", Titles.count(240, more = true))
    }

    @Test fun collectionSummary() {
        val items = (1..4).map { Title("m|p0|$it", "T$it", false, year = "${2000 + it}", genres = listOf("Fantasy")) }
        val s = collSummary(items) { it.key.last().digitToInt() <= 2 }
        assertEquals(2, s.watched); assertEquals(4, s.total); assertEquals("T3", s.next!!.title); assertEquals(2, s.nextIndex)
        assertEquals("2001 – 2004", Titles.years(items))
        assertEquals(listOf("Fantasy"), Titles.topGenres(items))
    }

    @Test fun gridCaptionFallsBackToTheCategory() {
        assertEquals("Drama", gridMeta(Title("k", "T", false, meta = "Drama")))
        assertEquals("2024 · Sci-fi", gridMeta(Title("k", "T", false, year = "2024", genres = listOf("Sci-fi"), meta = "x")))
    }
}
