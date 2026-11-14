// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guide's programme clean-up (EpgIndex.tidy): overlapping provider entries become one timeline. */
class EpgNormalizeTest {
    private val t0 = 1_800_000_000L
    private fun p(a: Int, b: Int, t: String) = Programme("ch", t0 + a * 60L, t0 + b * 60L, t, null)
    private fun view(l: Array<Programme>) = l.map { Triple(((it.start - t0) / 60).toInt(), ((it.stop - t0) / 60).toInt(), it.title) }
    private fun c(vararg x: Triple<Int, Int, String>) = x.toList()
    private fun e(a: Int, b: Int, t: String) = Triple(a, b, t)

    private class Case(val name: String, val input: List<Triple<Int, Int, String>>, val want: List<Triple<Int, Int, String>>)

    // The same table is in web/tests/test_programme_overlaps.py and web/tests/guide.test.mjs (minutes)
    private val cases = listOf(
        Case("owner's hockey: two 10:00-15:00 entries and a duplicate; post-game inside post-game",
            c(e(600, 900, "NHL Hockey on Arena"), e(600, 900, "Next Game Today"), e(600, 900, "NHL Hockey on Arena"),
                e(900, 1020, "Post-game"), e(930, 960, "Post-game")),
            c(e(600, 900, "NHL Hockey on Arena"), e(900, 1020, "Post-game"))),
        Case("owner's hockey: a filler inside the game, the game resumes",
            c(e(600, 900, "NHL Hockey on Arena"), e(720, 750, "Next Game Today"), e(900, 1020, "Post-game")),
            c(e(600, 720, "NHL Hockey on Arena"), e(720, 750, "Next Game Today"), e(750, 900, "NHL Hockey on Arena"),
                e(900, 1020, "Post-game"))),
        Case("Block / Match: the block resumes",
            c(e(60, 300, "Block"), e(120, 180, "Match")),
            c(e(60, 120, "Block"), e(120, 180, "Match"), e(180, 300, "Block"))),
        Case("partial overlap: the later one wins",
            c(e(120, 240, "B"), e(60, 180, "A")),
            c(e(60, 120, "A"), e(120, 240, "B"))),
        Case("nested three levels",
            c(e(0, 600, "Outer"), e(100, 400, "Mid"), e(200, 300, "Inner")),
            c(e(0, 100, "Outer"), e(100, 200, "Mid"), e(200, 300, "Inner"), e(300, 400, "Mid"), e(400, 600, "Outer"))),
        Case("same start: the longest, then the first in input order",
            c(e(0, 30, "Short"), e(0, 300, "Long"), e(0, 300, "Twin"), e(300, 360, "X"), e(300, 360, "Y")),
            c(e(0, 300, "Long"), e(300, 360, "X"))),
        Case("same title inside (and an exact duplicate) dropped",
            c(e(60, 300, "Marathon"), e(120, 180, "Marathon"), e(60, 300, "Marathon"), e(300, 360, "Late")),
            c(e(60, 300, "Marathon"), e(300, 360, "Late"))),
        Case("zero-length and backwards entries dropped",
            c(e(60, 60, "Empty"), e(60, 120, "Real"), e(180, 150, "Backwards")),
            c(e(60, 120, "Real"))),
        Case("a messy day",
            c(e(0, 300, "NHL Hockey on Arena"), e(0, 300, "NHL Hockey on Arena"), e(30, 60, "NHL Hockey on Arena"),
                e(120, 180, "Next Game Today"), e(170, 240, "Analysis"), e(240, 240, "Zero")),
            c(e(0, 120, "NHL Hockey on Arena"), e(120, 170, "Next Game Today"), e(170, 240, "Analysis"),
                e(240, 300, "NHL Hockey on Arena"))),
        Case("a gap stays a gap",
            c(e(0, 60, "A"), e(120, 180, "B"), e(130, 140, "C")),
            c(e(0, 60, "A"), e(120, 130, "B"), e(130, 140, "C"), e(140, 180, "B"))),
    )

    @Test fun caseTable() {
        for (k in cases) {
            val got = EpgIndex.tidy(k.input.map { p(it.first, it.second, it.third) }.toTypedArray())
            assertEquals(k.name, k.want, view(got))
        }
        assertEquals(0, EpgIndex.tidy(emptyArray()).size)
        assertEquals(0, EpgIndex.tidy(arrayOf(p(60, 60, "Empty"))).size)
    }

    @Test fun differentTitleInsideResumesTheLongerOne() {
        val block = Programme("ch", t0, t0 + 300 * 60, "Block", "desc", "sub", "S1 E2")
        val r = EpgIndex.tidy(arrayOf(block, p(120, 180, "Match")))
        assertEquals(listOf(e(0, 120, "Block"), e(120, 180, "Match"), e(180, 300, "Block")), view(r))
        for (q in listOf(r[0], r[2])) assertEquals(block.copy(start = q.start, stop = q.stop), q)
        assertNotSame(block, r[0])
    }

    @Test fun cleanListUnchanged() {
        val a = arrayOf(p(0, 30, "A"), p(30, 90, "B"), p(120, 150, "C"))
        val r = EpgIndex.tidy(a)
        assertEquals(a.toList(), r.toList())
        for (i in a.indices) assertSame(a[i], r[i])
    }

    /** The rules written the slow, obvious way: minute -> the kept entry shown then. */
    private fun reference(a: Array<Programme>): Map<Int, Programme> {
        val ps = a.filter { it.stop > it.start }.sortedWith(compareBy<Programme> { it.start }.thenByDescending { it.stop - it.start })
        val kept = ArrayList<Programme>()
        for (q in ps) {
            if (kept.any { it.start == q.start }) continue
            if (kept.any { it.title == q.title && it.start <= q.start && it.stop >= q.stop }) continue
            kept += q
        }
        val out = HashMap<Int, Programme>()
        for (k in kept) for (m in ((k.start - t0) / 60).toInt() until ((k.stop - t0) / 60).toInt()) {
            val o = out[m]
            if (o == null || o.start < k.start) out[m] = k
        }
        return out
    }

    @Test fun randomListsStaySortedCoverTheInputAndMatchTheRules() {
        val rnd = java.util.Random(7)
        repeat(300) {
            val a = Array(rnd.nextInt(31)) {
                val s = rnd.nextInt(97) * 15
                p(s, s + (rnd.nextInt(18) - 1) * 15, "t${rnd.nextInt(5)}")
            }
            val r = EpgIndex.tidy(a)
            for (i in r.indices) {
                assertTrue(r[i].stop > r[i].start)
                if (i > 0) assertTrue("sorted, no overlap", r[i - 1].stop <= r[i].start)
            }
            val covered = HashSet<Int>()
            for (q in a) for (m in ((q.start - t0) / 60).toInt() until ((q.stop - t0) / 60).toInt()) covered += m
            val got = HashMap<Int, Programme>()
            for (q in r) for (m in ((q.start - t0) / 60).toInt() until ((q.stop - t0) / 60).toInt()) got[m] = q
            assertEquals("every covered minute still covered, no time invented", covered, got.keys)
            val ref = reference(a)
            for ((m, q) in got) assertEquals(ref[m]!!.title, q.title)
            for (i in 1 until r.size) if (r[i - 1].stop == r[i].start) {
                assertTrue("adjacent pieces merged",
                    ref[((r[i - 1].start - t0) / 60).toInt()] !== ref[((r[i].start - t0) / 60).toInt()])
            }
        }
    }

    @Test fun bigChannelIsFast() {
        val a = Array(20000) { i -> p(i * 30, i * 30 + 30 + (i % 7) * 45, "t${i % 11}") }
        val t = System.nanoTime()
        val r = EpgIndex.tidy(a)
        assertTrue((System.nanoTime() - t) / 1_000_000 < 2000)
        for (i in 1 until r.size) assertTrue(r[i - 1].stop <= r[i].start)
    }
}
