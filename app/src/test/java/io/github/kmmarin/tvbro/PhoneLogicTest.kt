// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import androidx.compose.ui.unit.dp
import io.github.kmmarin.tvbro.data.Catchup
import io.github.kmmarin.tvbro.data.Channel
import io.github.kmmarin.tvbro.data.Programme
import io.github.kmmarin.tvbro.ui.live.guideEntries
import io.github.kmmarin.tvbro.ui.movies.DetailButton
import io.github.kmmarin.tvbro.ui.movies.phoneDetailActions
import io.github.kmmarin.tvbro.ui.movies.phoneEpTitle
import io.github.kmmarin.tvbro.ui.movies.phoneGridColumns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain rules behind the phone layouts (grid columns, episode titles, title-page actions, the vertical guide). */
class PhoneLogicTest {
    @Test fun gridColumns() {
        assertEquals(3, phoneGridColumns(360.dp))
        assertEquals(3, phoneGridColumns(412.dp))   // upright: 3 columns (spec section 13)
        assertEquals(6, phoneGridColumns(915.dp))   // on its side: more, posters keep about their size
    }

    @Test fun episodeTitle() {
        assertEquals("4. Ballast", phoneEpTitle("E4 · Ballast"))
        assertEquals("Pilot", phoneEpTitle("Pilot"))
    }

    @Test fun detailActions() {
        val b = listOf(DetailButton("Resume S2 E4", primary = true), DetailButton("Play from start"), DetailButton("My list"), DetailButton("Trailer"), DetailButton("More"))
        val a = phoneDetailActions(b, DetailButton("Watched"))
        assertEquals(listOf("From start", "My list", "Trailer", "Watched", "More"), a.map { it.label })
        assertEquals(listOf("My list", "More"), phoneDetailActions(listOf(DetailButton("Play"), DetailButton("My list"), DetailButton("More")), null).map { it.label })
    }

    private fun ch(catchup: Boolean) = Channel("k", "s", "Vista Cinema", null, "Movies", "http://192.0.2.10/1.ts", null, 104, if (catchup) Catchup("xc", null) else null, if (catchup) 3 else 0)

    @Test fun guideKeepsNowAndLaterAndCatchup() {
        val now = 1_800_000_000L
        val progs = arrayOf(
            Programme("k", now - 7200, now - 3600, "Old", null), Programme("k", now - 3600, now - 60, "Earlier", null),
            Programme("k", now - 60, now + 1800, "Now", null), Programme("k", now + 1800, now + 3600, "Later", null),
        )
        val plain = guideEntries(progs, ch(false), now, { true }, { false })
        assertEquals(listOf("Now", "Later"), plain.map { it.p.title })
        assertTrue(plain[0].live); assertFalse(plain[1].live)
        val cu = guideEntries(progs, ch(true), now, { it.title != "Old" }, { it.title == "Later" })
        assertEquals(listOf("Earlier", "Now", "Later"), cu.map { it.p.title })
        assertTrue(cu[0].past && cu[0].catchup)
        assertTrue(cu[2].rec)
        assertNull(cu[1].day)
    }
}
