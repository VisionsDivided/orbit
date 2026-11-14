// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.sun.jna.Callback
import com.sun.jna.CallbackThreadInitializer
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import io.github.kmmarin.tvbro.data.Diag
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** mpv_render_update_fn: called by mpv (any of its threads) when a new frame should be drawn. Must not call mpv. */
interface MpvUpdateCb : Callback { fun invoke(ctx: Pointer?) }

/**
 * Numbers and layout of mpv's render API (render.h) for the software renderer. Param numbers checked against the
 * render.h of mpv 0.33 - 0.40 (they are part of the ABI and never renumbered).
 */
object MpvRenderAbi {
    const val INVALID = 0
    const val API_TYPE = 1
    const val SW_SIZE = 17
    const val SW_FORMAT = 18
    const val SW_STRIDE = 19
    const val SW_POINTER = 20
    /** mpv_render_context_update(): a new frame should be rendered. */
    const val UPDATE_FRAME = 1L

    /** "rgb0": bytes R, G, B, unused; the same memory layout as Skia's [ColorType.RGB_888X] (alpha ignored). */
    const val FORMAT = "rgb0"

    /** sizeof(mpv_render_param) = {int type; void *data}: the int is padded to the pointer's alignment. */
    fun entrySize(ptr: Int = Native.POINTER_SIZE) = 2 * ptr

    /** Writes the params ([type] to [data]) into [m] at [offset], ending with the INVALID entry. Returns the size used. */
    fun write(m: Pointer, offset: Long, params: List<Pair<Int, Pointer?>>, ptr: Int = Native.POINTER_SIZE): Long {
        val e = entrySize(ptr)
        (params + (INVALID to null)).forEachIndexed { i, (t, d) ->
            m.setInt(offset + i.toLong() * e, t)
            m.setPointer(offset + i.toLong() * e + ptr, d)
        }
        return (params.size + 1).toLong() * e
    }

    fun cString(s: String): Memory {
        val b = s.toByteArray(Charsets.US_ASCII)
        return Memory(b.size + 1L).apply { write(0, b, 0, b.size); setByte(b.size.toLong(), 0) }
    }
}

/** Sizes of software frames: 4 bytes a pixel, rows padded to 64 bytes (mpv's fast path), the render size. */
object SwMath {
    const val BPP = 4
    const val ALIGN = 64
    const val DEFAULT_MAX_W = 1920
    const val DEFAULT_MAX_H = 1080

    data class Size(val w: Int, val h: Int)

    fun stride(w: Int): Int = (w * BPP + ALIGN - 1) / ALIGN * ALIGN
    fun bytes(w: Int, h: Int): Long = stride(w).toLong() * h

    /**
     * The size mpv renders at. It has the video box's aspect (mpv letterboxes the video in it, honouring the aspect
     * setting and panscan, and the ASS OSD is laid out in the box's aspect), scaled down so that it is no larger than
     * the box (in pixels), than [maxW] x [maxH], or than needed to show the video ([vidW] x [vidH]) at its own size.
     * Box unknown: the video's size within the cap; both unknown: 640 x 360.
     */
    fun target(boxW: Int, boxH: Int, vidW: Int, vidH: Int, maxW: Int = DEFAULT_MAX_W, maxH: Int = DEFAULT_MAX_H): Size {
        val bw: Int; val bh: Int
        if (boxW > 0 && boxH > 0) { bw = boxW; bh = boxH }
        else if (vidW > 0 && vidH > 0) { bw = vidW; bh = vidH }
        else { bw = 640; bh = 360 }
        var s = min(1.0, min(maxW.toDouble() / bw, maxH.toDouble() / bh))
        if (vidW > 0 && vidH > 0) {
            val fit = min(bw.toDouble() / vidW, bh.toDouble() / vidH) // how much the video is enlarged to fill the box
            if (fit > 1) s = min(s, 1 / fit)
        }
        return Size(max(2, (bw * s).roundToInt()), max(2, (bh * s).roundToInt()))
    }

    /** "1920x1080" -> 1920 x 1080 (TVBRO_SW_MAX); null when not a size. */
    fun parse(s: String?): Size? {
        val m = Regex("""\s*(\d{2,5})\s*[xX]\s*(\d{2,5})\s*""").matchEntire(s ?: return null) ?: return null
        return Size(m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }
}

/**
 * [COUNT] frame buffers big enough for the largest render size ([maxW] x [maxH]; about 8 MB each at 1920 x 1080),
 * allocated once per player (aligned to 64 bytes) and reused for every frame and every box size (no allocation
 * while the preview grows to full screen). Each has a ready-made render param block (only SW_POINTER differs; the
 * size and stride are shared and set by [size] before each render). Freed with [release], a little later: Compose
 * may still be drawing the last frame.
 */
class SwBuffers(val maxW: Int, val maxH: Int) {
    val capacity = SwMath.bytes(maxW, maxH)
    /** The size the next render uses. */
    var w = 0; private set
    var h = 0; private set
    var stride = 0; private set
    private val raw = Array(COUNT) { Memory(capacity + SwMath.ALIGN) }
    private val pixels: Array<Pointer> = Array(COUNT) { raw[it].align(SwMath.ALIGN) }
    private val sizeMem = Memory(8)
    /** Kept alive by every image over these buffers (Skia's makeWithoutCopy asks for an owner). */
    val owner: Data = Data.makeEmpty()
    private val format = MpvRenderAbi.cString(MpvRenderAbi.FORMAT)
    private val strideMem = Memory(Native.SIZE_T_SIZE.toLong())

    /** Sets the size of the next render (render thread only). [w] x [h] must fit ([SwMath.target] caps it). */
    fun size(w: Int, h: Int) {
        val s = SwMath.stride(w)
        require(w in 1..maxW && h in 1..maxH && s.toLong() * h <= capacity) { "$w x $h does not fit $maxW x $maxH" }
        this.w = w; this.h = h; stride = s
        sizeMem.setInt(0, w); sizeMem.setInt(4, h)
        if (Native.SIZE_T_SIZE == 8) strideMem.setLong(0, s.toLong()) else strideMem.setInt(0, s)
    }

    private val params: Array<Memory> = Array(COUNT) { i ->
        Memory(MpvRenderAbi.entrySize() * 5L).also {
            MpvRenderAbi.write(it, 0, listOf(MpvRenderAbi.SW_SIZE to sizeMem, MpvRenderAbi.SW_FORMAT to format,
                MpvRenderAbi.SW_STRIDE to strideMem, MpvRenderAbi.SW_POINTER to pixels[i]))
        }
    }
    @Volatile var released = false
        private set

    fun pixels(i: Int): Pointer = pixels[i]
    fun address(i: Int): Long = Pointer.nativeValue(pixels[i])
    fun params(i: Int): Pointer = params[i]

    fun release(delayMs: Long = FREE_DELAY_MS) {
        if (released) return
        released = true
        val free = { raw.forEach { it.close() }; params.forEach { it.close() }; sizeMem.close(); format.close(); owner.close(); strideMem.close() }
        if (delayMs <= 0) free() else reaper.schedule(free, delayMs, TimeUnit.MILLISECONDS)
    }

    companion object {
        /** One shown, one waiting to be shown, one being rendered. */
        const val COUNT = 3
        const val FREE_DELAY_MS = 1500L
        private val reaper = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sw-video-free").apply { isDaemon = true } }
    }
}

/** A rendered frame: [w] x [h] pixels, rows [stride] bytes apart, in buffer [index] of [buffers]. [seq] counts frames. */
class SwFrame(val buffers: SwBuffers, val index: Int, val w: Int, val h: Int, val stride: Int, val seq: Long) {

    /**
     * A Skia image over the buffer (no copy); null once the buffers are released. A new image each time, so a GPU
     * texture made from an older frame is never reused.
     */
    fun image(): Image? {
        if (buffers.released) return null
        return Data.makeWithoutCopy(buffers.address(index), (stride.toLong() * h).toInt(), buffers.owner).use {
            Image.makeRaster(ImageInfo(w, h, ColorType.RGB_888X, ColorAlphaType.OPAQUE), it, stride)
        }
    }
}

/**
 * Which buffer mpv renders into next: never the one Compose draws ([displayed]) nor the one waiting to be drawn
 * ([pending]). A frame still waiting when the next one is ready is dropped (the UI is behind). Compose takes the
 * frame to draw with [claim], atomically, so a buffer is never written while it is drawn.
 */
class SwRing(val count: Int = SwBuffers.COUNT) {
    var displayed = -1; private set
    var pending = -1; private set
    var dropped = 0L; private set

    @Synchronized fun acquire(): Int = (0 until count).first { it != displayed && it != pending }
    @Synchronized fun publish(i: Int) { if (pending >= 0 && pending != i) dropped++; pending = i }
    /** The buffer to draw: the waiting one (it becomes the displayed one), else the displayed one again; -1 = none. */
    @Synchronized fun claim(): Int { if (pending >= 0) { displayed = pending; pending = -1 }; return displayed }
    @Synchronized fun reset() { displayed = -1; pending = -1 }
}

/**
 * mpv's render API in software mode (vo=libmpv): mpv's update callback only wakes [thread]; the thread asks mpv
 * whether a frame is due, renders it into a reused buffer sized by [SwMath.target] and publishes it; [frame] (Compose
 * state) changes with every new frame and [SwVideoSurface] draws what [claim] returns. Created after mpv_initialize;
 * [stop] before mpv_terminate_destroy.
 */
class SwRenderer(private val lib: MpvLib, mpv: Pointer, private val maxSize: SwMath.Size = defaultMax()) {
    private var ctx: Pointer? = null
    private val wake = Semaphore(0)
    @Volatile private var running = true
    @Volatile private var force = false
    @Volatile private var boxW = 0
    @Volatile private var boxH = 0
    @Volatile private var vidW = 0
    @Volatile private var vidH = 0
    // guarded by ring's lock
    private var buffers: SwBuffers? = null
    private val frames = arrayOfNulls<SwFrame>(SwBuffers.COUNT)
    private var last: SwFrame? = null
    private val ring = SwRing()
    private var seq = 0L
    private val _frame = mutableStateOf<SwFrame?>(null)
    /** The newest frame (Compose state, set from the render thread): read it to redraw on each frame. */
    val frame: State<SwFrame?> get() = _frame
    /** Frames rendered / dropped because Compose had not drawn the one before. */
    @Volatile var rendered = 0L; private set
    val dropped: Long get() = ring.dropped

    // kept in a field: JNA must not lose the callback while mpv may call it
    private val callback = object : MpvUpdateCb { override fun invoke(ctx: Pointer?) { if (wake.availablePermits() == 0) wake.release() } }
    private val thread: Thread

    init {
        val api = MpvRenderAbi.cString("sw")
        val params = Memory(MpvRenderAbi.entrySize() * 2L)
        MpvRenderAbi.write(params, 0, listOf(MpvRenderAbi.API_TYPE to api))
        val res = PointerByReference()
        val r = lib.mpv_render_context_create(res, mpv, params)
        api.close(); params.close()
        if (r < 0) throw IllegalStateException("mpv_render_context_create (sw): ${lib.mpv_error_string(r)}")
        ctx = res.value
        Native.setCallbackThreadInitializer(callback, CallbackThreadInitializer(true, false, "mpv-render-update"))
        lib.mpv_render_context_set_update_callback(ctx!!, callback, null)
        thread = Thread(::loop, "mpv-sw-render").apply { isDaemon = true; start() }
    }

    /** The video box in pixels (Compose). A new size redraws the current frame (also while paused). */
    fun box(w: Int, h: Int) { if (w != boxW || h != boxH) { boxW = w; boxH = h; force = true; wake.release() } }

    /** The video's display size (mpv's dwidth / dheight), from the event thread. */
    fun video(w: Int, h: Int) { if (w != vidW || h != vidH) { vidW = w; vidH = h; force = true; wake.release() } }

    /** The frame Compose should draw now (UI thread); its buffer is not written to until a newer frame is claimed. */
    fun claim(): SwFrame? = synchronized(ring) {
        val i = ring.claim()
        (if (i >= 0) frames[i] else null)?.also { last = it } ?: last
    }

    private fun loop() {
        while (running) {
            try { wake.tryAcquire(250, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { break }
            wake.drainPermits()
            if (!running) break
            val c = ctx ?: break
            val due = lib.mpv_render_context_update(c) and MpvRenderAbi.UPDATE_FRAME != 0L
            if (!due && !force) continue
            force = false
            val t = SwMath.target(boxW, boxH, vidW, vidH, maxSize.w, maxSize.h)
            val (b, i) = synchronized(ring) {
                val b = buffers ?: SwBuffers(maxSize.w, maxSize.h).also { buffers = it }
                b to ring.acquire()
            }
            b.size(t.w, t.h)
            val r = lib.mpv_render_context_render(c, b.params(i))
            if (r < 0 || !running) continue
            rendered++
            val f = SwFrame(b, i, t.w, t.h, b.stride, ++seq)
            synchronized(ring) { frames[i] = f; ring.publish(i) }
            _frame.value = f
        }
    }

    /** Stops the thread, frees the render context (before the mpv core is destroyed) and, a little later, the buffers. */
    fun stop() {
        val c = ctx ?: return
        running = false
        wake.release()
        if (Thread.currentThread() !== thread) thread.join(3000)
        ctx = null
        lib.mpv_render_context_set_update_callback(c, null, null)
        lib.mpv_render_context_free(c)
        _frame.value = null
        synchronized(ring) { buffers?.release(); buffers = null; frames.fill(null); last = null; ring.reset() }
        Diag.log("sw video: $rendered frames rendered, ${ring.dropped} dropped")
    }

    companion object {
        /** TVBRO_SW_MAX=WxH (or -Dtvbro.swMax) raises or lowers the 1920 x 1080 cap (e.g. 3840x2160 for 4K tests). */
        fun defaultMax(): SwMath.Size = SwMath.parse(System.getProperty("tvbro.swMax") ?: System.getenv("TVBRO_SW_MAX"))
            ?: SwMath.Size(SwMath.DEFAULT_MAX_W, SwMath.DEFAULT_MAX_H)
    }
}
