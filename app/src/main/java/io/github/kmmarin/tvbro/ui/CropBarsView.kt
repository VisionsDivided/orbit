// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.PlayerView
import io.github.kmmarin.tvbro.data.CropBars

/**
 * "Fit, crop black bars" on a [PlayerView] (the web player's option): every [CropBars.SAMPLE_MS] while the video
 * plays, a 96 px wide copy of the frame is taken (PixelCopy from the SurfaceView, or TextureView.getBitmap), its
 * content box found on a background thread ([CropBars.contentBoxArgb]) and the largest box of the title kept; the
 * video surface is then scaled and moved so that box fills the view without cropping picture. Paused or hidden:
 * no sampling. A new title starts again from the plain fit. Only on while [active] (the aspect choice, full screen).
 */
class CropBarsView private constructor(private val pv: PlayerView) : Player.Listener, View.OnLayoutChangeListener, View.OnAttachStateChangeListener {
    private val main = Handler(Looper.getMainLooper())
    private var player: Player? = null
    private var box: CropBars.Box? = null
    private var bmp: Bitmap? = null
    @Volatile private var busy = false
    @Volatile private var gen = 0 // bumped on a new title: a sample taken before it is dropped
    private var attached = pv.isAttachedToWindow

    /** On while the aspect choice is "Fit, crop black bars" (and the view fills the screen). */
    var active = false
        set(v) { if (field != v) { field = v; schedule() } }

    private val tick = object : Runnable {
        override fun run() { sample(); main.postDelayed(this, CropBars.SAMPLE_MS) }
    }

    init {
        pv.addOnLayoutChangeListener(this)
        pv.addOnAttachStateChangeListener(this)
        // the zoomed surface reaches past the fitted content frame: no clipping to it (Android 13+ clips a SurfaceView
        // to its ancestors' bounds). The view only zooms when it fills the screen, so nothing else is covered.
        pv.clipChildren = false
        (pv.videoSurfaceView?.parent as? android.view.ViewGroup)?.clipChildren = false
    }

    private fun bind() {
        val p = pv.player
        if (p === player) return
        player?.removeListener(this)
        player = p; p?.addListener(this)
        reset()
    }

    private fun schedule() {
        bind()
        main.removeCallbacks(tick)
        if (active && attached) main.postDelayed(tick, 600) else clear()
        apply()
    }

    private fun reset() { gen++; box = null; apply() }

    private fun sample() {
        val p = player ?: return
        if (!active || busy || !p.isPlaying) return
        val vs = p.videoSize
        val dw = vs.width * vs.pixelWidthHeightRatio.toDouble(); val dh = vs.height.toDouble()
        val w = CropBars.SAMPLE_W; val h = CropBars.sampleH(dw, dh)
        if (h <= 0) return
        val b = bmp?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp = it }
        val g = gen
        when (val v = pv.videoSurfaceView) {
            is SurfaceView -> {
                if (!v.holder.surface.isValid) return
                busy = true
                try {
                    PixelCopy.request(v, b, { r -> if (r == PixelCopy.SUCCESS) analyse(b, g) else busy = false }, worker)
                } catch (e: Exception) { busy = false } // no surface yet, or one that cannot be read (protected content)
            }
            is TextureView -> {
                if (!v.isAvailable) return
                busy = true
                try { v.getBitmap(b); worker.post { analyse(b, g) } } catch (e: Exception) { busy = false }
            }
        }
    }

    /** On the worker thread. */
    private fun analyse(b: Bitmap, g: Int) {
        val found = try {
            val px = IntArray(b.width * b.height)
            b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            CropBars.contentBoxArgb(px, b.width, b.height)
        } catch (e: Exception) { null } finally { busy = false }
        if (found != null) main.post { if (g == gen && active) { box = found.union(box); apply() } }
    }

    /** Scales and moves the video surface so the box fills the view (or puts it back). */
    private fun apply() {
        val v = pv.videoSurfaceView ?: return
        val vs = player?.videoSize ?: VideoSize.UNKNOWN
        // only when the view fills the screen: a zoomed SurfaceView is not clipped to a smaller box (phone portrait)
        val root = pv.rootView
        val full = pv.width >= root.width * 0.9 && pv.height >= root.height * 0.9
        val f = if (!active || !full) null else CropBars.fill(box, pv.width.toDouble(), pv.height.toDouble(), vs.width * vs.pixelWidthHeightRatio.toDouble(), vs.height.toDouble())
        val s = (f?.scale ?: 1.0).toFloat()
        if (v.scaleX == s && v.translationX == (f?.tx ?: 0.0).toFloat() && v.translationY == (f?.ty ?: 0.0).toFloat()) return
        v.pivotX = v.width / 2f; v.pivotY = v.height / 2f
        v.scaleX = s; v.scaleY = s
        v.translationX = (f?.tx ?: 0.0).toFloat(); v.translationY = (f?.ty ?: 0.0).toFloat()
    }

    private fun clear() { main.removeCallbacks(tick) }

    // a new title: the plain fit until its own bars are found
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) reset()
    }
    override fun onVideoSizeChanged(videoSize: VideoSize) = apply()
    override fun onLayoutChange(v: View?, l: Int, t: Int, r: Int, b: Int, oldL: Int, oldT: Int, oldR: Int, oldB: Int) {
        if (r - l != oldR - oldL || b - t != oldB - oldT) main.post { apply() }
    }
    override fun onViewAttachedToWindow(v: View) { attached = true; schedule() }
    override fun onViewDetachedFromWindow(v: View) { attached = false; clear(); player?.removeListener(this); player = null }

    companion object {
        private val worker: Handler by lazy { Handler(HandlerThread("orbit-cropbars").apply { start() }.looper) }
        private val views = java.util.WeakHashMap<PlayerView, CropBarsView>()

        /** The crop helper of [pv], made on first use (it lives as long as the view). Main thread. */
        fun of(pv: PlayerView): CropBarsView = views.getOrPut(pv) { CropBarsView(pv) }
    }
}
