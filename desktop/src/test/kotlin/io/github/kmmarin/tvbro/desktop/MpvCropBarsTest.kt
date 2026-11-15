// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** "Fit, crop black bars" on mpv (MpvCropBars): against a pretend mpv, then (TVBRO_TEST_LIBMPV) against the real one. */
class MpvCropBarsTest {
    /** A pretend mpv: properties in a map, commands recorded. */
    private class Fake(videoCrop: Boolean = true) {
        val props = HashMap<String, String>().apply {
            put("pause", "no"); put("video-params/w", "1280"); put("video-params/h", "720"); put("video-params/dw", "1280"); put("video-params/dh", "720")
            if (videoCrop) put("video-crop", "")
        }
        val cmds = ArrayList<String>()
        val crop = MpvCropBars({ props[it] }, { k, v -> props[k] = v; cmds += "set $k=$v" }, { cmds += it.joinToString(" ") })
        fun meta(w: Int, h: Int, x: Int, y: Int) {
            props["vf-metadata/orbitcrop"] = """{"lavfi.cropdetect.x1":"$x","lavfi.cropdetect.w":"$w","lavfi.cropdetect.h":"$h","lavfi.cropdetect.x":"$x","lavfi.cropdetect.y":"$y"}"""
        }
    }

    @Test fun `metadata as mpv prints it`() {
        assertEquals(mapOf("w" to 1280, "h" to 536, "x" to 0, "y" to 92),
            MpvCropBars.parseMeta("""{"lavfi.cropdetect.x1":"0","lavfi.cropdetect.x2":"1279","lavfi.cropdetect.w":"1280","lavfi.cropdetect.h":"536","lavfi.cropdetect.x":"0","lavfi.cropdetect.y":"92"}"""))
        assertEquals(mapOf("w" to -1270), MpvCropBars.parseMeta("""lavfi.cropdetect.w=-1270"""))
        assertTrue(MpvCropBars.parseMeta(null).isEmpty())
        assertTrue(MpvCropBars.parseMeta("{}").isEmpty())
    }

    @Test fun `the filter is added and removed with the choice`() {
        val f = Fake()
        f.crop.enable(true)
        assertEquals(listOf("vf add @orbitcrop:lavfi=[cropdetect=limit=24:round=2:reset=0]"), f.cmds)
        f.crop.enable(true)
        assertEquals(1, f.cmds.size)
        f.crop.enable(false)
        assertEquals("vf remove @orbitcrop", f.cmds.last())
    }

    @Test fun `a letterboxed film is cropped with video-crop on a wide window, the box only grows, a new title starts again`() {
        val f = Fake()
        f.crop.enable(true)
        f.meta(1280, 536, 0, 92)
        f.crop.tick(10_000, 2560, 1080)                   // ultrawide window: worth it
        assertEquals("1280x536+0+92", f.props["video-crop"])
        f.meta(1280, 300, 0, 210)                          // a dark scene: smaller box, ignored by the union
        f.crop.tick(12_000, 2560, 1080)
        assertEquals("1280x536+0+92", f.props["video-crop"])
        f.crop.tick(12_500, 1920, 1080)                    // 16:9 window: cropping would gain nothing, full frame back
        assertEquals("", f.props["video-crop"])
        f.crop.tick(13_000, 2560, 1080)
        assertEquals("1280x536+0+92", f.props["video-crop"])
        f.crop.newFile()
        assertEquals("", f.props["video-crop"]); assertNull(f.crop.box)
    }

    @Test fun `no reading while paused or more often than every 2 s`() {
        val f = Fake()
        f.crop.enable(true)
        f.props["pause"] = "yes"; f.meta(1280, 536, 0, 92)
        f.crop.tick(10_000, 2560, 1080)
        assertNull(f.crop.box)
        f.props["pause"] = "no"
        f.crop.tick(11_000, 2560, 1080)
        assertNotNull(f.crop.box)
        f.meta(1000, 536, 140, 92)
        f.crop.tick(11_500, 2560, 1080)                    // too soon: not read
        assertEquals(0.0, f.crop.box!!.x0, 0.0)
    }

    @Test fun `older mpv without video-crop gets a crop filter`() {
        val f = Fake(videoCrop = false)
        f.crop.enable(true)
        f.meta(960, 720, 160, 0)                            // 4:3 picture pillarboxed in the frame...
        f.props["video-params/dw"] = "1280"
        f.crop.tick(10_000, 960, 1080)                      // ...on a tall window
        assertEquals("vf add @orbitzoom:crop=960:720:160:0", f.cmds.last())
        f.crop.enable(false)
        assertEquals("vf remove @orbitzoom", f.cmds.last())
    }

    @Test fun `a dark scene alone never crops`() {
        val f = Fake()
        f.crop.enable(true)
        f.meta(1280, 40, 0, 340)
        f.crop.tick(10_000, 2560, 1080)
        assertNull(f.crop.box); assertNull(f.crop.applied)
    }
}

/**
 * The real thing (TVBRO_TEST_LIBMPV=<libmpv-2.dll / libmpv.2.dylib>): testdata/letterbox-239.mp4 is testsrc2 at
 * 1280 x 536 padded to 1280 x 720 (bars of 92 rows; ffmpeg command in desktop/README.md). cropdetect must find the
 * picture, and on a 2.39:1 box the software renderer must then show picture to the edges, not bars.
 */
class MpvCropBarsRealTest {
    @Test fun realLibmpvCropsTheBars() = run(null)

    /** mpv before 0.38 has no video-crop: the crop filter instead (forced here). */
    @Test fun realLibmpvCropsWithAFilter() = run(false)

    private fun run(useVideoCrop: Boolean?) {
        val path = System.getenv("TVBRO_TEST_LIBMPV")
        assumeTrue(path != null && File(path).isFile)
        val lib = MpvLoader.open(path!!)
        val file = File("testdata/letterbox-239.mp4").absoluteFile
        assertTrue(file.isFile)
        val m = Mpv(lib, MpvVideo.SW, if (useVideoCrop == null) "auto-safe" else "no") { } // auto-safe: copy-back hwdec, as on macOS
        try {
            m.set("ao", "null"); m.set("loop-file", "inf")
            val r = m.sw!!
            r.box(1280, 536)
            val crop = MpvCropBars(m, useVideoCrop)
            crop.enable(true)
            m.play(file.path, trustedLocal = true)
            var t = 0L
            val end = System.currentTimeMillis() + 15_000
            while (crop.applied == null && System.currentTimeMillis() < end) { Thread.sleep(250); t += 2000; crop.tick(t, 1280, 536) }
            println("mpv ${m.get("mpv-version")} hwdec=${m.get("hwdec-current")}: video-crop=${m.get("video-crop")} meta=${m.get("vf-metadata/orbitcrop")} box=${crop.box} applied=${crop.applied}")
            assertEquals("1280x536+0+92", crop.applied)
            if (useVideoCrop == false) assertTrue(m.get("vf").orEmpty().contains("orbitzoom"))
            Thread.sleep(600)
            val f = r.claim() ?: throw AssertionError("no frame")
            // rows near the top and bottom of the box are picture now (were black bars before the crop)
            fun lit(y: Int) = (0 until f.w step 8).count { x -> val c = f.rgb(x, y); ((c shr 16) and 0xff) + ((c shr 8) and 0xff) + (c and 0xff) > 72 }
            val top = lit(3); val bottom = lit(f.h - 4)
            println("frame ${f.w}x${f.h}: lit top=$top bottom=$bottom of ${f.w / 8}")
            assertTrue("top row is picture ($top)", top > f.w / 8 / 2)
            assertTrue("bottom row is picture ($bottom)", bottom > f.w / 8 / 2)
            crop.enable(false)
            Thread.sleep(600)
            val g = r.claim() ?: throw AssertionError("no frame after crop off")
            val bar = (0 until g.w step 8).count { x -> val c = g.rgb(x, 3); ((c shr 16) and 0xff) + ((c shr 8) and 0xff) + (c and 0xff) > 72 }
            println("crop off: lit top=$bar (frame ${g.w}x${g.h})")
            assertTrue("crop off: the top is a bar again ($bar)", bar < g.w / 8 / 10)
        } finally { m.destroy() }
    }
}
