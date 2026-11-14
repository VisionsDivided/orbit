// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteTest {
    private fun r(c: Int) = c shr 16 and 255
    private fun g(c: Int) = c shr 8 and 255
    private fun b(c: Int) = c and 255

    @Test fun dominantAndSecondaryColours() {
        // mostly red with some teal, plus black and white that never count as colours
        val px = IntArray(600) { 0xFFC3261B.toInt() } + IntArray(200) { 0xFF16968A.toInt() } + IntArray(300) { 0xFF000000.toInt() } + IntArray(300) { -1 }
        val m = Palette.of(px)
        assertEquals(0xC3, r(m.m1)); assertEquals(0x26, g(m.m1))
        assertEquals(0x16, r(m.m2)); assertEquals(0x96, g(m.m2))
        assertTrue("the base is a very dark m1", r(m.base) < 0x20 && g(m.base) < 0x08 && b(m.base) < 0x08)
    }

    @Test fun oneHueGivesALighterSecondAndGreyGivesTheDefault() {
        val m = Palette.of(IntArray(100) { 0xFF1B4F7A.toInt() })
        assertTrue(b(m.m2) > b(m.m1))
        assertEquals(Palette.DEFAULT.m1, Palette.of(IntArray(100) { 0xFF808080.toInt() }).m1)
        assertEquals(Palette.DEFAULT, Palette.of(IntArray(0)))
    }
}
