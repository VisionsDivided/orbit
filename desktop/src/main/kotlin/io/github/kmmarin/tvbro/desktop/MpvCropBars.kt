// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import io.github.kmmarin.tvbro.data.CropBars

/**
 * The aspect choice "Fit, crop black bars" with mpv's own detection: a labelled cropdetect filter (it sees the decoded
 * frame, before any crop) reports the picture's box; every [CropBars.SAMPLE_MS] while playing it is read from
 * `vf-metadata/orbitcrop`, the largest box of the title kept ([CropBars.Box.union]: a dark scene never zooms in
 * further) and applied with `video-crop` (mpv 0.38+) or, on older mpv, a labelled crop filter. mpv then fits the
 * cropped picture into the window, which fills it as far as the picture allows and never crops picture. Only when
 * [CropBars.fill] says the zoom is worth it for the window's size; otherwise nothing is cropped. The same on the
 * `wid` (Windows / Linux) and the software (macOS) path: both are mpv's own video output.
 */
class MpvCropBars(
    private val get: (String) -> String?,
    private val set: (String, String) -> Unit,
    private val cmd: (List<String>) -> Unit,
    /** Crop with `video-crop` (true) or a filter (false); null = video-crop where mpv has it. */
    useVideoCrop: Boolean? = null,
) {
    constructor(m: Mpv, useVideoCrop: Boolean? = null) : this(m::get, m::set, { a -> m.cmd(*a.toTypedArray()) }, useVideoCrop)

    var on = false
        private set
    /** The largest box of this title, or null. */
    var box: CropBars.Box? = null
        private set
    /** The crop now applied ("WxH+X+Y"), or null. */
    var applied: String? = null
        private set
    private var last = 0L
    /** mpv has `video-crop` (0.38+); null = not asked yet. */
    private var hasVideoCrop: Boolean? = useVideoCrop

    /** Turns the detection on or off; off puts the full frame back. */
    fun enable(v: Boolean) {
        if (v == on) return
        on = v
        if (v) { cmd(listOf("vf", "add", DETECT)); last = 0 }
        else { cmd(listOf("vf", "remove", "@$LABEL")); box = null; uncrop() }
    }

    /** A new title: the full frame until its own bars are found. */
    fun newFile() { box = null; uncrop() }

    /** Called often (the player's ticker); reads the detection every [CropBars.SAMPLE_MS]. [viewW] × [viewH]: the window. */
    fun tick(now: Long, viewW: Int, viewH: Int) {
        if (!on) return
        if (now - last >= CropBars.SAMPLE_MS && get("pause") != "yes") {
            last = now
            val (w, h) = size()
            val meta = parseMeta(get("vf-metadata/$LABEL"))
            CropBars.fromCrop(meta["w"], meta["h"], meta["x"], meta["y"], w, h)?.let { box = it.union(box) }
        }
        apply(viewW, viewH)
    }

    private fun apply(viewW: Int, viewH: Int) {
        val b = box ?: return uncrop()
        val (w, h) = size()
        // display size of the decoded frame (pixel aspect applied), before any filter: a crop filter must not change it
        val dw = get("video-params/dw")?.toDoubleOrNull() ?: w.toDouble(); val dh = get("video-params/dh")?.toDoubleOrNull() ?: h.toDouble()
        if (CropBars.fill(b, viewW.toDouble(), viewH.toDouble(), dw, dh) == null || w <= 0 || h <= 0) return uncrop()
        val c = CropBars.crop(b, w, h).toString()
        if (c == applied) return
        if (videoCrop()) set("video-crop", c)
        else {
            val p = CropBars.crop(b, w, h)
            if (applied != null) cmd(listOf("vf", "remove", "@$ZOOM"))
            cmd(listOf("vf", "add", "@$ZOOM:crop=${p.w}:${p.h}:${p.x}:${p.y}"))
        }
        applied = c
    }

    private fun uncrop() {
        if (applied == null) return
        if (videoCrop()) set("video-crop", "") else cmd(listOf("vf", "remove", "@$ZOOM"))
        applied = null
    }

    /** The decoded frame's size (what cropdetect sees). */
    private fun size(): Pair<Int, Int> {
        val w = (get("video-params/w") ?: get("width"))?.toIntOrNull() ?: 0
        val h = (get("video-params/h") ?: get("height"))?.toIntOrNull() ?: 0
        return w to h
    }

    private fun videoCrop(): Boolean = hasVideoCrop ?: (get("video-crop") != null).also { hasVideoCrop = it }

    companion object {
        const val LABEL = "orbitcrop"
        const val ZOOM = "orbitzoom"
        const val DETECT = "@$LABEL:lavfi=[cropdetect=limit=24:round=2:reset=0]"

        /**
         * w / h / x / y of `vf-metadata/orbitcrop` (lavfi.cropdetect.*), as mpv prints a map: JSON-like
         * {"lavfi.cropdetect.w":"1280",...}; missing or unreadable values are left out.
         */
        fun parseMeta(s: String?): Map<String, Int> {
            if (s.isNullOrBlank()) return emptyMap()
            val r = Regex("\"?lavfi\\.cropdetect\\.(w|h|x|y)\"?\\s*[:=]\\s*\"?(-?\\d+)")
            return r.findAll(s).associate { it.groupValues[1] to it.groupValues[2].toInt() }
        }
    }
}
