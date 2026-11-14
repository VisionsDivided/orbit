// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.use
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/** Reads the render params mpv would get (type -> data) from a param list ending with type 0. */
fun renderParams(p: Pointer): Map<Int, Pointer?> {
    val e = MpvRenderAbi.entrySize().toLong()
    val out = LinkedHashMap<Int, Pointer?>()
    var i = 0L
    while (true) { val t = p.getInt(i * e); if (t == 0) break; out[t] = p.getPointer(i * e + Native.POINTER_SIZE); i++ }
    return out
}

/** Colour (0xRRGGBB) of pixel [x], [y] in an "rgb0" frame. */
fun SwFrame.rgb(x: Int, y: Int): Int {
    val p = buffers.pixels(index)
    val o = y.toLong() * stride + x * 4L
    return ((p.getByte(o).toInt() and 0xff) shl 16) or ((p.getByte(o + 1).toInt() and 0xff) shl 8) or (p.getByte(o + 2).toInt() and 0xff)
}

fun near(want: Int, got: Int, tol: Int = 40): Boolean =
    (0..2).all { s -> Math.abs(((want shr (s * 8)) and 0xff) - ((got shr (s * 8)) and 0xff)) <= tol }

/**
 * A fake libmpv render API: records the calls and paints every frame red on the left half and blue on the right,
 * into exactly the size, stride and pointer it was given (so a wrong param layout shows up as a wrong picture).
 */
class FakeRender {
    var api: String? = null
    var callback: MpvUpdateCb? = null
    var format: String? = null
    var renders = 0
    val calls = mutableListOf<String>()
    val sizes = mutableListOf<Pair<Int, Int>>()
    val ctx = Memory(8)

    val lib: MpvLib = Proxy.newProxyInstance(MpvLib::class.java.classLoader, arrayOf(MpvLib::class.java)) { _, m, a ->
        synchronized(this) { calls += m.name }
        when (m.name) {
            "mpv_render_context_create" -> {
                api = renderParams(a[2] as Pointer)[MpvRenderAbi.API_TYPE]?.getString(0, "US-ASCII")
                (a[0] as PointerByReference).value = ctx; 0
            }
            "mpv_render_context_set_update_callback" -> { callback = a[1] as MpvUpdateCb?; null }
            "mpv_render_context_update" -> MpvRenderAbi.UPDATE_FRAME
            "mpv_render_context_render" -> { paint(renderParams(a[1] as Pointer)); 0 }
            "mpv_error_string" -> "fake error"
            else -> when (m.returnType) { Int::class.javaPrimitiveType -> 0; Long::class.javaPrimitiveType -> 0L; else -> null }
        }
    } as MpvLib

    private fun paint(p: Map<Int, Pointer?>) {
        val size = p[MpvRenderAbi.SW_SIZE]!!
        val w = size.getInt(0); val h = size.getInt(4)
        val sp = p[MpvRenderAbi.SW_STRIDE]!!
        val stride = if (Native.SIZE_T_SIZE == 8) sp.getLong(0) else sp.getInt(0).toLong()
        format = p[MpvRenderAbi.SW_FORMAT]!!.getString(0, "US-ASCII")
        val px = p[MpvRenderAbi.SW_POINTER]!!
        val row = ByteArray(w * 4)
        for (x in 0 until w) { val red = x < w / 2; row[x * 4] = if (red) -1 else 0; row[x * 4 + 2] = if (red) 0 else -1 }
        for (y in 0 until h) px.write(y * stride, row, 0, row.size)
        synchronized(this) { sizes += w to h; renders++ }
    }

    fun frame() = callback!!.invoke(null)
}

class SwRendererTest {
    private fun waitFor(ms: Long = 5000, ok: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (ok()) return true; Thread.sleep(10) }
        return ok()
    }

    @Test fun rendersIntoTheBuffersAndStopsInOrder() {
        val f = FakeRender()
        val r = SwRenderer(f.lib, Memory(8))
        assertEquals("sw", f.api)
        assertNotNull(f.callback)
        r.box(640, 480); r.video(320, 240)
        assertTrue(waitFor { r.frame.value != null })
        f.frame()
        assertTrue(waitFor { r.frame.value?.let { it.w == 320 && it.h == 240 } == true })
        assertEquals("rgb0", f.format)
        val fr = r.claim()!!
        assertEquals(SwMath.stride(320), fr.stride)
        assertEquals(0xFF0000, fr.rgb(10, 10)); assertEquals(0xFF0000, fr.rgb(159, 239))
        assertEquals(0x0000FF, fr.rgb(160, 0)); assertEquals(0x0000FF, fr.rgb(319, 239))
        // a new box size renders again at once (also while paused), in the same buffers
        r.box(1280, 480)
        assertTrue(waitFor { r.frame.value?.w == 640 })
        assertEquals(240, r.frame.value!!.h)
        assertSame(fr.buffers, r.frame.value!!.buffers)
        r.stop()
        assertNull(r.frame.value)
        // the callback is taken away before the context is freed; nothing renders after stop
        val tail = f.calls.filter { it.startsWith("mpv_render_context_set") || it == "mpv_render_context_free" }
        assertEquals(listOf("mpv_render_context_set_update_callback", "mpv_render_context_set_update_callback", "mpv_render_context_free"), tail)
        assertNull(f.callback)
        val n = f.renders
        Thread.sleep(100); assertEquals(n, f.renders)
        assertTrue(fr.buffers.released)
        assertNull(fr.image()) // a stale frame is never drawn from freed memory
        r.stop() // twice is harmless
    }

    @Test fun manyFramesWithoutAllocation() {
        val f = FakeRender()
        val r = SwRenderer(f.lib, Memory(8))
        r.box(320, 240)
        val seen = HashSet<SwBuffers>()
        repeat(200) { f.frame(); Thread.sleep(1); r.frame.value?.let { seen += it.buffers }; if (it % 2 == 0) r.claim() }
        assertTrue(waitFor { f.renders > 20 })
        assertEquals(1, seen.size) // one set of buffers for the whole run
        r.stop()
    }

    @Test fun createFailureIsAnError() {
        val bad = Proxy.newProxyInstance(MpvLib::class.java.classLoader, arrayOf(MpvLib::class.java)) { _, m, _ ->
            when (m.name) { "mpv_render_context_create" -> -1; "mpv_error_string" -> "unsupported"; else -> null }
        } as MpvLib
        val e = assertThrows(IllegalStateException::class.java) { SwRenderer(bad, Memory(8)) }
        assertTrue(e.message!!.contains("unsupported"))
    }

    /** The frame drawn by Compose: scaled to the box (a 160 x 120 frame in a 320 x 240 box), colours unchanged. */
    @Test fun composeDrawsTheFrameScaledToTheBox() {
        val f = FakeRender()
        val r = SwRenderer(f.lib, Memory(8), SwMath.Size(160, 120))
        try {
            ImageComposeScene(320, 240) { SwVideoSurface(r, Modifier.fillMaxSize()) }.use { s ->
                s.render(0)
                // the first frame may come before the box size is known (640 x 360 within the cap: 160 x 90)
                assertTrue(waitFor { s.render(0); r.frame.value?.h == 120 })
                assertEquals(160, r.frame.value!!.w); assertEquals(120, r.frame.value!!.h)
                val img = s.render(1_000_000_000L)
                val bmp = Bitmap.makeFromImage(img)
                fun px(x: Int, y: Int) = bmp.getColor(x, y) and 0xFFFFFF
                assertTrue("left ${Integer.toHexString(px(40, 120))}", near(0xFF0000, px(40, 120), 8))
                assertTrue("left edge ${Integer.toHexString(px(150, 10))}", near(0xFF0000, px(150, 10), 8))
                assertTrue("right ${Integer.toHexString(px(280, 120))}", near(0x0000FF, px(280, 120), 8))
                assertTrue("right edge ${Integer.toHexString(px(170, 230))}", near(0x0000FF, px(170, 230), 8))
                assertEquals(0xFF, Color.getA(bmp.getColor(10, 10))) // opaque (the 4th byte of rgb0 is ignored)
            }
        } finally { r.stop() }
    }
}

/**
 * The software path against a real libmpv (TVBRO_TEST_LIBMPV=<path to libmpv-2.dll / libmpv.2.dylib>): plays
 * testdata/sw-pattern.mp4 (320 x 240, left half red, right half blue; made with ffmpeg, see desktop/README.md) through
 * vo=libmpv and checks the frames' size and colours, letterboxing in a wider box, and a clean stop and restart.
 */
class SwRealMpvTest {
    @Test fun realLibmpvRendersFrames() {
        val path = System.getenv("TVBRO_TEST_LIBMPV")
        assumeTrue(path != null && File(path).isFile)
        val lib = MpvLoader.open(path!!)
        val file = File("testdata/sw-pattern.mp4").absoluteFile
        assertTrue(file.isFile)
        repeat(2) { round -> // twice: a second player after the first was destroyed (zap / stop)
            val m = Mpv(lib, MpvVideo.SW, "auto-safe") { }
            try {
                m.set("ao", "null"); m.set("loop-file", "inf")
                val r = m.sw!!
                r.box(640, 480)
                m.play(file.path, trustedLocal = true)
                fun waitFrame(w: Int, h: Int): SwFrame? {
                    val end = System.currentTimeMillis() + 10_000
                    while (System.currentTimeMillis() < end) {
                        val f = r.frame.value
                        if (f != null && f.w == w && f.h == h) return r.claim()
                        Thread.sleep(20)
                    }
                    return null
                }
                val f = waitFrame(320, 240) ?: throw AssertionError("no 320x240 frame (round $round): ${r.frame.value?.let { "${it.w}x${it.h}" }}, rendered ${r.rendered}")
                assertTrue("red ${Integer.toHexString(f.rgb(80, 120))}", near(0xFF0000, f.rgb(80, 120)))
                assertTrue("blue ${Integer.toHexString(f.rgb(240, 120))}", near(0x0000FF, f.rgb(240, 120)))
                assertEquals("auto-copy-safe", m.get("hwdec"))
                // a 1280 x 480 box: 640 x 240 frame, the 4:3 video in the middle (x 160..480), black bars
                r.box(1280, 480)
                val g = waitFrame(640, 240) ?: throw AssertionError("no 640x240 frame")
                assertTrue("bar ${Integer.toHexString(g.rgb(40, 120))}", near(0x000000, g.rgb(40, 120)))
                assertTrue("red ${Integer.toHexString(g.rgb(240, 120))}", near(0xFF0000, g.rgb(240, 120)))
                assertTrue("blue ${Integer.toHexString(g.rgb(400, 120))}", near(0x0000FF, g.rgb(400, 120)))
                assertTrue("bar ${Integer.toHexString(g.rgb(600, 120))}", near(0x000000, g.rgb(600, 120)))
                // the aspect setting is honoured: 16:9 shows the video 427 x 240 (x 107..533), so x 150 is now picture
                m.set("video-aspect-override", "16:9")
                Thread.sleep(400)
                val a = r.claim()!!
                assertTrue("16:9 red ${Integer.toHexString(a.rgb(150, 120))}", near(0xFF0000, a.rgb(150, 120)))
                val n = r.rendered
                Thread.sleep(500)
                assertTrue("frames keep coming (${r.rendered - n} in 0.5 s)", r.rendered - n >= 5)
                println("sw real libmpv round $round: rendered=${r.rendered} dropped=${r.dropped} hwdec=${m.get("hwdec-current")}")
                m.cmd("stop")
                Thread.sleep(100)
            } finally { m.destroy() }
        }
    }
}
