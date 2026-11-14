// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guide's programme clean-up (EpgIndex.tidy): overlapping provider entries become one timeline. */
class EpgNormalizeTest {
    private val h = 3600L
    private val t0 = 1_800_000_000L
    private fun p(a: Double, b: Double, t: String) = Programme("ch", t0 + (a * h).toLong(), t0 + (b * h).toLong(), t, null)
    private fun view(l: Array<Programme>) = l.map { Triple((it.start - t0) / 60, (it.stop - t0) / 60, it.title) }

    @Test fun ownersHockeyChannel() {
        // the owner's screenshot: "Next Game Today" printed over "NHL Hockey on ...", 10:00 AM - 3:00 PM twice
        val r = EpgIndex.tidy(arrayOf(
            p(10.0, 15.0, "NHL Hockey on Arcadia"), p(10.0, 15.0, "Next Game Today"), p(10.0, 15.0, "NHL Hockey on Arcadia"),
            p(15.0, 17.0, "Post-game"), p(15.5, 16.0, "Post-game"),
        ))
        assertEquals(listOf(Triple(600L, 900L, "NHL Hockey on Arcadia"), Triple(900L, 1020L, "Post-game")), view(r))
    }

    @Test fun exactDuplicatesDropped() {
        val r = EpgIndex.tidy(arrayOf(p(1.0, 2.0, "News"), p(1.0, 2.0, "News"), p(2.0, 3.0, "Film")))
        assertEquals(listOf("News", "Film"), r.map { it.title })
    }

    @Test fun insideALongerOneWithTheSameTitleDropped() {
        val r = EpgIndex.tidy(arrayOf(p(1.0, 5.0, "Marathon"), p(2.0, 3.0, "Marathon"), p(5.0, 6.0, "Late")))
        assertEquals(listOf(Triple(60L, 300L, "Marathon"), Triple(300L, 360L, "Late")), view(r))
    }

    @Test fun differentTitleInsideTrimsTheLongerOne() {
        val r = EpgIndex.tidy(arrayOf(p(1.0, 5.0, "Block"), p(2.0, 3.0, "Match")))
        assertEquals(listOf(Triple(60L, 120L, "Block"), Triple(120L, 180L, "Match")), view(r))
    }

    @Test fun overlapTrimmedToTheNextStart() {
        val r = EpgIndex.tidy(arrayOf(p(3.0, 4.0, "c"), p(1.0, 2.5, "a"), p(2.0, 3.5, "b")))
        assertEquals(listOf(Triple(60L, 120L, "a"), Triple(120L, 180L, "b"), Triple(180L, 240L, "c")), view(r))
    }

    @Test fun zeroLengthDropped() {
        val r = EpgIndex.tidy(arrayOf(p(1.0, 1.0, "empty"), p(1.0, 2.0, "real"), p(3.0, 2.5, "backwards")))
        assertEquals(listOf("real"), r.map { it.title })
    }

    @Test fun neverOverlapsAndStaysSorted() {
        val rnd = java.util.Random(7)
        repeat(200) {
            val a = Array(30) { val s = rnd.nextInt(48) * 0.5; p(s, s + 0.5 * (1 + rnd.nextInt(8)), "t${rnd.nextInt(5)}") }
            val r = EpgIndex.tidy(a)
            for (i in r.indices) {
                assertTrue(r[i].stop > r[i].start)
                if (i > 0) assertTrue(r[i - 1].stop <= r[i].start)
            }
        }
    }
}
