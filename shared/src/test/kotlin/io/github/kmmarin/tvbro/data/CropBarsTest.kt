// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** "Fit, crop black bars": the same cases as web/tests/cropbars.test.mjs, plus the zoom and cropdetect maths. */
class CropBarsTest {
    /** RGBA bytes, picture pixels 160, bars 4 (as the web test's frame()). */
    private fun frame(w: Int, h: Int, lit: (Int, Int) -> Boolean): ByteArray {
        val d = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val v = (if (lit(x, y)) 160 else 4).toByte(); val i = (y * w + x) * 4
            d[i] = v; d[i + 1] = v; d[i + 2] = v; d[i + 3] = 255.toByte()
        }
        return d
    }

    @Test fun `a 2_39 film letterboxed in a 16_9 frame`() {
        val w = 96; val h = 54; val bar = 7
        val b = CropBars.contentBoxRgba(frame(w, h) { _, y -> y >= bar && y < h - bar }, w, h)
        assertNotNull(b)
        assertEquals(0.0, b!!.x0, 0.0); assertEquals(1.0, b.x1, 0.0)
        assertEquals(bar.toDouble() / h, b.y0, 0.0); assertEquals((h - bar).toDouble() / h, b.y1, 0.0)
    }

    @Test fun `pillarboxed 4_3 content`() {
        val w = 96; val h = 54
        val b = CropBars.contentBoxRgba(frame(w, h) { x, _ -> x in 12 until 84 }, w, h)!!
        assertEquals(12.0 / w, b.x0, 0.0); assertEquals(84.0 / w, b.x1, 0.0); assertEquals(0.0, b.y0, 0.0); assertEquals(1.0, b.y1, 0.0)
    }

    @Test fun `a dark scene tells nothing`() {
        assertNull(CropBars.contentBoxRgba(frame(96, 54) { _, y -> y in 26..29 }, 96, 54))
    }

    @Test fun `ARGB pixels give the same box`() {
        val w = 96; val h = 54
        val px = IntArray(w * h) { i -> if (i / w in 7 until 47) 0xFFA0A0A0.toInt() else 0xFF040404.toInt() }
        val b = CropBars.contentBoxArgb(px, w, h)!!
        assertEquals(7.0 / h, b.y0, 0.0); assertEquals(47.0 / h, b.y1, 0.0)
    }

    @Test fun `the box only grows within a title`() {
        val wide = CropBars.Box(0.0, 1.0, 0.13, 0.87)
        val dark = CropBars.Box(0.1, 0.9, 0.3, 0.7)
        assertEquals(wide, dark.union(wide))
        assertEquals(wide, wide.union(dark))
        assertEquals(dark, dark.union(null))
    }

    @Test fun `a 2_39 film on a 16_9 screen is left alone - filling it would crop picture at the sides`() {
        val box = CropBars.Box(0.0, 1.0, 138.0 / 1080, 942.0 / 1080)
        assertNull(CropBars.fill(box, 1920.0, 1080.0, 1920.0, 1080.0))
    }

    @Test fun `a 2_39 film in a 16_9 frame fills an ultrawide screen`() {
        val box = CropBars.Box(0.0, 1.0, 138.0 / 1080, 942.0 / 1080)
        val f = CropBars.fill(box, 2560.0, 1080.0, 1920.0, 1080.0)!!
        assertEquals(2560.0 / 1920, f.scale, 1e-9)   // 2560x1080 is 2.37:1: the width decides
        assertEquals(0.0, f.tx, 1e-9); assertEquals(0.0, f.ty, 1e-9)
    }

    @Test fun `a 16_9 picture letterboxed in a 4_3 SD frame fills a 16_9 TV`() {
        // 720x576 PAL frame shown as 4:3 (768x576 display), picture 16:9 = rows 72..504
        val box = CropBars.Box(0.0, 1.0, 72.0 / 576, 504.0 / 576)
        val f = CropBars.fill(box, 1920.0, 1080.0, 768.0, 576.0)!!
        assertEquals(4.0 / 3, f.scale, 1e-9)
        assertEquals(0.0, f.ty, 1e-9)
    }

    @Test fun `an off-centre box is moved to the middle`() {
        // picture 4:3 at the left of a 16:9 frame, bars at the top too: x 0..0.75, y 0.1..1
        val box = CropBars.Box(0.0, 0.75, 0.1, 1.0)
        val f = CropBars.fill(box, 1600.0, 900.0, 1600.0, 900.0)!!
        // content 1200x810 -> scale min(1600/1200, 900/810) = 1.111
        assertEquals(900.0 / 810, f.scale, 1e-9)
        assertEquals((0.5 - 0.375) * 1600 * f.scale, f.tx, 1e-9)
        assertEquals((0.5 - 0.55) * 900 * f.scale, f.ty, 1e-9)
    }

    @Test fun `no zoom for a full frame or an implausible box`() {
        assertNull(CropBars.fill(CropBars.Box(0.0, 1.0, 0.0, 1.0), 1920.0, 1080.0, 1920.0, 1080.0))
        assertNull(CropBars.fill(CropBars.Box(0.0, 1.0, 0.005, 0.995), 1920.0, 1080.0, 1440.0, 1080.0))
        assertNull(CropBars.fill(CropBars.Box(0.4, 0.6, 0.4, 0.6), 1920.0, 1080.0, 1920.0, 1080.0))
        assertNull(CropBars.fill(null, 1920.0, 1080.0, 1920.0, 1080.0))
    }

    @Test fun `cropdetect values become a box and back`() {
        val b = CropBars.fromCrop(1280, 536, 0, 92, 1280, 720)!!
        assertEquals(92.0 / 720, b.y0, 0.0); assertEquals(628.0 / 720, b.y1, 0.0)
        assertEquals(CropBars.Crop(1280, 536, 0, 92), CropBars.crop(b, 1280, 720))
        assertEquals("1280x536+0+92", CropBars.crop(b, 1280, 720).toString())
    }

    @Test fun `cropdetect on a dark scene is ignored`() {
        assertNull(CropBars.fromCrop(1280, 40, 0, 340, 1280, 720))
        assertNull(CropBars.fromCrop(-1270, -710, 1278, 718, 1280, 720))
        assertNull(CropBars.fromCrop(null, 536, 0, 92, 1280, 720))
    }

    @Test fun `a crop is rounded outwards`() {
        val c = CropBars.crop(CropBars.Box(0.0, 1.0, 0.1271, 0.8729), 1280, 720)
        assertEquals(91, c.y); assertEquals(629 - 91, c.h)
    }

    @Test fun `sample size keeps the frame's shape`() {
        assertEquals(54, CropBars.sampleH(1920.0, 1080.0))
        assertEquals(72, CropBars.sampleH(1024.0, 768.0))
        assertEquals(16, CropBars.sampleH(4000.0, 100.0))
        assertEquals(0, CropBars.sampleH(0.0, 0.0))
    }
}
