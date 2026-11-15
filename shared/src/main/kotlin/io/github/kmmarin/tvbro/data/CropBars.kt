// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The players' "Fit, crop black bars" (the web player's cropbars.js, ported): finds the picture inside a frame with
 * black bars baked into it (a 2.39:1 film encoded as a 16:9 frame) and zooms just enough to remove them. Real
 * picture is never cropped: the zoom only fills the screen as far as the content box allows, and the box only ever
 * grows within a title ([Box.union]), so a dark scene never zooms in further.
 */
object CropBars {
    /** A pixel is lit when r + g + b > 3 × this (0..255 per channel). */
    const val DARK = 24
    /** How often the TV app samples a frame, and how wide the sample is. */
    const val SAMPLE_MS = 2000L
    const val SAMPLE_W = 96

    /** The content box as fractions of the frame: x0 ≤ x < x1, y0 ≤ y < y1. */
    data class Box(val x0: Double, val x1: Double, val y0: Double, val y1: Double) {
        /** The smallest box holding both: the largest picture seen so far in this title. */
        fun union(o: Box?): Box = if (o == null) this else Box(min(x0, o.x0), max(x1, o.x1), min(y0, o.y0), max(y1, o.y1))
    }

    /**
     * The content box of a [w] × [h] frame, or null (no frame, or a scene too dark to tell). [rgbSum] gives r + g + b
     * of a pixel. Rows are lit when more than 2 % of their pixels are; columns are judged within the lit rows only.
     */
    fun contentBox(w: Int, h: Int, dark: Int = DARK, rgbSum: (x: Int, y: Int) -> Int): Box? {
        if (w <= 0 || h <= 0) return null
        val lim = dark * 3
        fun rowLit(y: Int): Boolean { var n = 0; for (x in 0 until w) if (rgbSum(x, y) > lim) n++; return n > w * 0.02 }
        fun colLit(x: Int, y0: Int, y1: Int): Boolean { var n = 0; for (y in y0..y1) if (rgbSum(x, y) > lim) n++; return n > (y1 - y0 + 1) * 0.02 }
        var y0 = 0; var y1 = h - 1
        while (y0 < h && !rowLit(y0)) y0++
        while (y1 > y0 && !rowLit(y1)) y1--
        if (y1 - y0 + 1 < h * 0.4) return null            // too dark to tell
        var x0 = 0; var x1 = w - 1
        while (x0 < w && !colLit(x0, y0, y1)) x0++
        while (x1 > x0 && !colLit(x1, y0, y1)) x1--
        if (x1 - x0 + 1 < w * 0.4) return null
        return Box(x0.toDouble() / w, (x1 + 1).toDouble() / w, y0.toDouble() / h, (y1 + 1).toDouble() / h)
    }

    /** [contentBox] of RGBA bytes (canvas ImageData layout, as in the web tests). */
    fun contentBoxRgba(data: ByteArray, w: Int, h: Int, dark: Int = DARK): Box? = contentBox(w, h, dark) { x, y ->
        val i = (y * w + x) * 4
        (data[i].toInt() and 0xff) + (data[i + 1].toInt() and 0xff) + (data[i + 2].toInt() and 0xff)
    }

    /** [contentBox] of ARGB ints (Android Bitmap.getPixels). */
    fun contentBoxArgb(px: IntArray, w: Int, h: Int, dark: Int = DARK): Box? = contentBox(w, h, dark) { x, y ->
        val c = px[y * w + x]
        ((c shr 16) and 0xff) + ((c shr 8) and 0xff) + (c and 0xff)
    }

    /**
     * How to show the box filling a [viewW] × [viewH] view in which the [videoW] × [videoH] frame (display size: pixel
     * aspect applied) is fitted and centred: scale about the view's centre by [scale], then move by [tx], [ty] view
     * pixels. Null = leave the picture as it is (no bars worth removing, or an implausible box).
     */
    data class Fill(val scale: Double, val tx: Double, val ty: Double)

    fun fill(box: Box?, viewW: Double, viewH: Double, videoW: Double, videoH: Double): Fill? {
        if (box == null || viewW <= 0 || viewH <= 0 || videoW <= 0 || videoH <= 0) return null
        val k = min(viewW / videoW, viewH / videoH)
        val fw = videoW * k; val fh = videoH * k
        val cw = (box.x1 - box.x0) * fw; val ch = (box.y1 - box.y0) * fh
        val sc = min(viewW / cw, viewH / ch)
        if (!(sc > 1.02 && sc < 3)) return null
        val dx = ((box.x0 + box.x1) / 2 - 0.5) * fw; val dy = ((box.y0 + box.y1) / 2 - 0.5) * fh
        return Fill(sc, -dx * sc, -dy * sc)
    }

    /**
     * The box from FFmpeg's cropdetect (lavfi.cropdetect.w / h / x / y, in video pixels), or null when it is missing
     * or too small to be real picture (a dark scene: cropdetect then reports a tiny or negative area).
     */
    fun fromCrop(w: Int?, h: Int?, x: Int?, y: Int?, videoW: Int, videoH: Int): Box? {
        if (w == null || h == null || x == null || y == null || videoW <= 0 || videoH <= 0) return null
        if (w < videoW * 0.4 || h < videoH * 0.4 || x < 0 || y < 0) return null
        val x1 = min(videoW, x + w); val y1 = min(videoH, y + h)
        return Box(x.toDouble() / videoW, x1.toDouble() / videoW, y.toDouble() / videoH, y1.toDouble() / videoH)
    }

    /** A crop in video pixels, as mpv's `video-crop` takes it: "WxH+X+Y". */
    data class Crop(val w: Int, val h: Int, val x: Int, val y: Int) {
        override fun toString() = "${w}x$h+$x+$y"
    }

    /** The pixels of [box] in a [videoW] × [videoH] frame, rounded outwards (never inside the picture). */
    fun crop(box: Box, videoW: Int, videoH: Int): Crop {
        val x0 = floor(box.x0 * videoW + 1e-6).toInt().coerceIn(0, videoW - 1)
        val y0 = floor(box.y0 * videoH + 1e-6).toInt().coerceIn(0, videoH - 1)
        val x1 = ceil(box.x1 * videoW - 1e-6).toInt().coerceIn(x0 + 1, videoW)
        val y1 = ceil(box.y1 * videoH - 1e-6).toInt().coerceIn(y0 + 1, videoH)
        return Crop(x1 - x0, y1 - y0, x0, y0)
    }

    /** Height of a [SAMPLE_W]-wide sample of a [videoW] × [videoH] frame. */
    fun sampleH(videoW: Double, videoH: Double, w: Int = SAMPLE_W): Int =
        if (videoW <= 0 || videoH <= 0) 0 else max(16, (w * videoH / videoW).roundToInt())
}
