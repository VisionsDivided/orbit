// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import io.github.kmmarin.tvbro.orbit.CatalogApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The catalog client is shared by the TV and the desktop app (docs/orbit/API-v1.md "Catalogue"). */
class CatalogApiTest {
    private val server = "http://192.0.2.10:8750"

    @Test fun itemsAndRows() {
        val j = Json.parseToJsonElement("""{"hero": [{"key": "m|p0|7", "title": "A", "rating": 7.0, "poster": "/img?u=1", "backdrop": null}],
            "rows": [{"id": "continue", "title": "Continue watching", "items": [
              {"key": "s|p0|9", "kind": "show", "title": "B", "year": 2023, "rating": "8.4", "genres": ["Drama", null],
               "colors": ["#c3261b", "#000000"], "progress": {"secs": 30, "dur": 120}, "next": {"id": "e1", "season": 2, "num": 4, "title": "Ballast"}},
              {"title": "no key"}]},
              {"id": "empty", "title": "Nothing", "items": []}]}""").jsonObject
        val f = CatalogApi.feed(j, server)
        val a = f.hero.single()
        assertEquals("$server/img?u=1", a.poster)
        assertNull(a.backdrop)
        assertEquals("7", a.rating)
        assertEquals("movie", a.kind)
        assertEquals(listOf("continue"), f.rows.map { it.id })   // rows without items are left out
        val b = f.rows[0].items.single()                          // items without a key are left out
        assertTrue(b.isShow)
        assertEquals("2023", b.year)
        assertEquals(listOf("Drama"), b.genres)
        assertEquals(listOf("#c3261b", "#000000"), b.colors)
        assertEquals(0.25f, b.progress!!, 0.001f)
        assertEquals(2, b.next!!.season)
    }
}
