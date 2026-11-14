// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import com.sun.jna.Memory
import com.sun.jna.Native
import org.junit.Assert.*
import org.junit.Test

/** Buffer math and render-param layout of the software video path (no libmpv needed). */
class SwMathTest {
    @Test fun strideIsFourBytesAPixelPaddedTo64() {
        assertEquals(64, SwMath.stride(1))
        assertEquals(64, SwMath.stride(16))
        assertEquals(128, SwMath.stride(17))
        assertEquals(1280, SwMath.stride(320))
        assertEquals(7680, SwMath.stride(1920)) // 1920 * 4 is already a multiple of 64
        assertEquals(5120, SwMath.stride(1279)) // 5116 rounded up
        for (w in 1..3000) { val s = SwMath.stride(w); assertTrue(s >= w * 4 && s % 64 == 0 && s - w * 4 < 64) }
        assertEquals(7680L * 1080, SwMath.bytes(1920, 1080))
    }

    @Test fun targetKeepsTheBoxAspect() {
        // the video fills the box at its own size or more: no larger than needed
        assertEquals(SwMath.Size(320, 240), SwMath.target(640, 480, 320, 240))
        // a wider box: the frame has the box's aspect (mpv letterboxes), scaled so the video is at its own size
        assertEquals(SwMath.Size(640, 240), SwMath.target(1280, 480, 320, 240))
        // a box smaller than the video: the box's size in pixels
        assertEquals(SwMath.Size(480, 270), SwMath.target(480, 270, 1920, 1080))
        // 4K box, 4K video: capped at 1920 x 1080
        assertEquals(SwMath.Size(1920, 1080), SwMath.target(3840, 2160, 3840, 2160))
        // Retina full screen (2880 x 1800 px) with 1080p: the video fits 1920 wide at 1:1 within the cap
        assertEquals(SwMath.Size(1728, 1080), SwMath.target(2880, 1800, 1920, 1080))
        // a raised cap for 4K tests
        assertEquals(SwMath.Size(3840, 2160), SwMath.target(3840, 2160, 3840, 2160, 3840, 2160))
        // unknown video: the box, capped
        assertEquals(SwMath.Size(1280, 720), SwMath.target(1280, 720, 0, 0))
        assertEquals(SwMath.Size(1920, 1080), SwMath.target(2560, 1440, 0, 0))
        // unknown box: the video within the cap; both unknown: 640 x 360
        assertEquals(SwMath.Size(1920, 1080), SwMath.target(0, 0, 3840, 2160))
        assertEquals(SwMath.Size(640, 360), SwMath.target(0, 0, 0, 0))
        // a tiny off-screen box never gives an empty frame
        assertEquals(SwMath.Size(2, 2), SwMath.target(1, 1, 1920, 1080))
    }

    @Test fun targetAlwaysFitsTheBuffers() {
        val b = SwMath.bytes(1920, 1080)
        for (bw in listOf(1, 17, 333, 1279, 1920, 2560, 3000, 3840, 5120)) for (bh in listOf(1, 9, 480, 1080, 1600, 2160, 4000)) {
            val t = SwMath.target(bw, bh, 1920, 1080)
            assertTrue("$bw x $bh -> $t", t.w <= 1920 && t.h <= 1080 && SwMath.bytes(t.w, t.h) <= b)
            if (t.w >= 50 && t.h >= 50) assertEquals("aspect of $bw x $bh", bw.toDouble() / bh, t.w.toDouble() / t.h, 0.03 * bw / bh)
        }
    }

    @Test fun parseMaxSize() {
        assertEquals(SwMath.Size(3840, 2160), SwMath.parse("3840x2160"))
        assertEquals(SwMath.Size(1280, 720), SwMath.parse(" 1280 X 720 "))
        assertNull(SwMath.parse("big")); assertNull(SwMath.parse(null)); assertNull(SwMath.parse("1x1"))
    }

    @Test fun renderParamLayout() {
        assertEquals(16, MpvRenderAbi.entrySize(8)) // {int; pad; void*} on 64-bit
        assertEquals(8, MpvRenderAbi.entrySize(4))
        val a = Memory(8); val b = Memory(8)
        val m = Memory(64)
        assertEquals(48L, MpvRenderAbi.write(m, 0, listOf(17 to a, 20 to b), 8))
        assertEquals(17, m.getInt(0)); assertEquals(a, m.getPointer(8))
        assertEquals(20, m.getInt(16)); assertEquals(b, m.getPointer(24))
        assertEquals(0, m.getInt(32)); assertNull(m.getPointer(40)) // MPV_RENDER_PARAM_INVALID ends the list
        assertEquals("rgb0", MpvRenderAbi.cString("rgb0").getString(0, "US-ASCII"))
    }

    @Test fun buffersAreAlignedAndShareOneSize() {
        val b = SwBuffers(320, 240)
        try {
            for (i in 0 until SwBuffers.COUNT) assertEquals(0L, b.address(i) % 64)
            assertEquals(3, (0 until SwBuffers.COUNT).map { b.address(it) }.distinct().size)
            b.size(200, 100)
            assertEquals(SwMath.stride(200), b.stride)
            val e = MpvRenderAbi.entrySize()
            val p = b.params(1)
            assertEquals(MpvRenderAbi.SW_SIZE, p.getInt(0))
            assertEquals(200, p.getPointer(Native.POINTER_SIZE.toLong()).getInt(0))
            assertEquals(100, p.getPointer(Native.POINTER_SIZE.toLong()).getInt(4))
            assertEquals(MpvRenderAbi.SW_FORMAT, p.getInt(e.toLong()))
            assertEquals("rgb0", p.getPointer(e + Native.POINTER_SIZE.toLong()).getString(0, "US-ASCII"))
            assertEquals(MpvRenderAbi.SW_STRIDE, p.getInt(2L * e))
            val sp = p.getPointer(2L * e + Native.POINTER_SIZE)
            assertEquals(SwMath.stride(200).toLong(), if (Native.SIZE_T_SIZE == 8) sp.getLong(0) else sp.getInt(0).toLong())
            assertEquals(MpvRenderAbi.SW_POINTER, p.getInt(3L * e))
            assertEquals(b.address(1), com.sun.jna.Pointer.nativeValue(p.getPointer(3L * e + Native.POINTER_SIZE)))
            assertEquals(0, p.getInt(4L * e))
            assertThrows(IllegalArgumentException::class.java) { b.size(321, 240) }
        } finally { b.release(0) }
        assertTrue(b.released)
    }

    @Test fun ringNeverHandsOutTheDrawnOrWaitingBuffer() {
        val r = SwRing()
        val a = r.acquire(); r.publish(a)
        assertEquals(a, r.claim()) // drawn
        val b = r.acquire(); assertNotEquals(a, b); r.publish(b)
        val c = r.acquire(); assertTrue(c != a && c != b); r.publish(c) // UI behind: b dropped
        assertEquals(1L, r.dropped)
        val d = r.acquire(); assertTrue(d != a && d != c) // a still on screen, c waiting
        assertEquals(c, r.claim()); assertEquals(c, r.claim()) // nothing new: the same again
        repeat(100) {
            val i = r.acquire(); assertNotEquals(r.displayed, i); assertNotEquals(r.pending, i); r.publish(i)
            if (it % 3 == 0) r.claim()
        }
    }

    @Test fun swHwdecUsesCopyBack() {
        assertEquals("auto-copy-safe", MpvVideo.swHwdec("auto-safe"))
        assertEquals("videotoolbox-copy", MpvVideo.swHwdec("videotoolbox"))
        assertEquals("videotoolbox-copy", MpvVideo.swHwdec("videotoolbox-copy"))
        assertEquals("d3d11va-copy", MpvVideo.swHwdec("d3d11va"))
        assertEquals("no", MpvVideo.swHwdec("no"))
        assertEquals("v4l2m2m-copy,auto-copy-safe", MpvVideo.swHwdec("v4l2m2m-copy,auto-safe"))
    }
}
