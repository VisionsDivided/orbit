// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import java.util.Locale
import kotlin.math.roundToInt

/**
 * The player overlay in direction 5 (DESIGN-SPEC-5 §10), drawn by mpv's own OSD as ASS (the video is a native surface,
 * so Compose cannot draw over it). Coordinates are ASS units: 720 high, [w] wide (720 × the window's aspect), the
 * spec's 1920 × 1080 scaled by 2/3. Pure: the strings and the hit boxes are unit tested.
 */
object PlayerOsd {
    const val H = 720.0
    private const val K = 2.0 / 3.0                 // 1080 → 720
    val SIDE = 96 * K                               // the safe side margin, 64
    const val QA_Y = 846 * 2.0 / 3.0                // quick actions top, 564
    const val QA_D = 84 * 2.0 / 3.0                 // circle diameter, 56
    const val QA_W = 120 * 2.0 / 3.0                // item width (circle + label), 80
    private const val QA_GAP = 20 * 2.0 / 3.0

    /** One quick action: [glyph] in the circle, [label] under it; [tint] an ASS colour for the glyph (null = white). */
    data class Qa(val id: String, val label: String, val glyph: String, val tint: String? = null, val on: Boolean = false)

    /** One colour key of the legend: ASS colour, label. */
    data class Key(val color: String, val label: String)

    // ---- colours as ASS &HBBGGRR& ----
    const val WHITE = "&HFFFFFF&"
    const val BG = "&H0C0605&"                      // #05060c
    const val MUTED = "&HFFE9E4&"                   // rgba(228,233,255) (its alpha is set separately)
    const val LIVE = "&H5C3BFF&"                    // #ff3b5c
    const val ACCENT = "&HFFD32F&"                  // #2fd3ff
    const val REC = "&H6B6BFF&"                     // #ff6b6b
    const val RED = "&H4D48E5&"; const val GREEN = "&H6CA430&"; const val YELLOW = "&H0AD9F5&"; const val BLUE = "&HDD633E&"

    /** ASS alpha (00 opaque … FF clear) of an opacity 0..1. */
    fun alpha(opacity: Double): String = "&H%02X&".format(Locale.ROOT, ((1 - opacity.coerceIn(0.0, 1.0)) * 255).roundToInt())

    private fun n(d: Double) = d.roundToInt().toString()

    /** A rounded rectangle path of [w] × [h] (radius [r]) for an ASS drawing (\p1), origin top left. */
    fun roundRect(w: Double, h: Double, r0: Double): String {
        val r = r0.coerceAtMost(minOf(w, h) / 2)
        val c = r * 0.45                              // bezier handle for a quarter circle
        return "m ${n(r)} 0 l ${n(w - r)} 0 b ${n(w - c)} 0 ${n(w)} ${n(c)} ${n(w)} ${n(r)} l ${n(w)} ${n(h - r)} " +
            "b ${n(w)} ${n(h - c)} ${n(w - c)} ${n(h)} ${n(w - r)} ${n(h)} l ${n(r)} ${n(h)} b ${n(c)} ${n(h)} 0 ${n(h - c)} 0 ${n(h - r)} " +
            "l 0 ${n(r)} b 0 ${n(c)} ${n(c)} 0 ${n(r)} 0"
    }

    /** A filled shape event at (x, y). [border]: an outline colour and width (a ring). */
    fun box(x: Double, y: Double, w: Double, h: Double, r: Double, color: String, opacity: Double = 1.0, border: Pair<String, Double>? = null): String =
        "{\\an7\\pos(${n(x)},${n(y)})\\bord${border?.second?.let { n(it) } ?: "0"}\\shad0\\1c$color\\1a${alpha(opacity)}" +
            (border?.let { "\\3c${it.first}\\3a&H00&" } ?: "") + "\\p1}" + roundRect(w, h, r) + "{\\p0}"

    /** A text event: [an] the ASS alignment (7 top left, 9 top right, 5 centre…), [size] in ASS px. */
    fun text(x: Double, y: Double, s: String, size: Double, color: String = WHITE, opacity: Double = 1.0, bold: Boolean = false, an: Int = 7, light: Boolean = false): String =
        "{\\an$an\\pos(${n(x)},${n(y)})\\fs${n(size)}\\b${if (bold) 1 else 0}\\bord0\\shad0\\1c$color\\1a${alpha(opacity)}${if (light) "\\fnSegoe UI Light" else ""}}" + esc(s)

    /** User text safe inside ASS (no override blocks, no line breaks). */
    fun esc(s: String) = s.replace("\\", "/").replace("{", "(").replace("}", ")").replace("\n", " ")

    /** About how wide [s] is at [size] (for laying out pills; a sans font averages about half its size per letter). */
    fun textW(s: String, size: Double): Double = s.length * size * 0.52

    /** The bottom scrim (0deg, .94 → .66 at 40 % → clear at 66 %) and the top one (.62 → clear at 24 %) as bands. */
    fun scrims(w: Double): List<String> {
        val out = ArrayList<String>()
        val bands = 24
        for (i in 0 until bands) {                     // bottom: from 66 % up to the bottom edge
            val y0 = H * (1 - .66) + i * (H * .66 / bands)
            val t = (H - (y0 + H * .66 / bands / 2)) / H      // distance from the bottom, 0..0.66
            val o = if (t > .40) .66 * (.66 - t) / .26 else .66 + (.94 - .66) * (.40 - t) / .40
            out += box(0.0, y0, w, H * .66 / bands + 1, 0.0, "&H000000&", o)
        }
        for (i in 0 until 8) {                         // top
            val y0 = i * (H * .24 / 8)
            out += box(0.0, y0, w, H * .24 / 8 + 1, 0.0, "&H000000&", .62 * (1 - (i + .5) / 8))
        }
        return out
    }

    /** Left x of quick action [i]. */
    fun qaX(i: Int): Double = SIDE + i * (QA_W + QA_GAP)

    /** The quick action under ASS point ([x], [y]), or -1. */
    fun qaAt(x: Double, y: Double, count: Int): Int {
        if (y < QA_Y - 6 || y > QA_Y + QA_D + 30) return -1
        for (i in 0 until count) if (x >= qaX(i) - 4 && x <= qaX(i) + QA_W + 4) return i
        return -1
    }

    /** The quick-action row: circles with glyphs and labels; [sel] white with a ring (the focus look, §7). */
    fun quickActions(items: List<Qa>, sel: Int): List<String> {
        val out = ArrayList<String>()
        items.forEachIndexed { i, q ->
            val cx = qaX(i) + QA_W / 2
            val x = cx - QA_D / 2
            val focused = i == sel
            if (focused) out += box(x - 6, QA_Y - 6, QA_D + 12, QA_D + 12, (QA_D + 12) / 2, WHITE, 0.0, WHITE to 3.0)
            out += box(x, QA_Y, QA_D, QA_D, QA_D / 2, if (focused) WHITE else WHITE, if (focused) 1.0 else .14)
            val gs = if (q.glyph.length >= 3) 15.0 else if (q.glyph.length == 2) 18.0 else 24.0
            out += text(cx, QA_Y + QA_D / 2, q.glyph, gs, if (focused) BG else q.tint ?: WHITE, bold = true, an = 5)
            if (q.on && !focused) out += box(x + QA_D - 14, QA_Y + 2, 12.0, 12.0, 6.0, ACCENT)
            out += text(cx, QA_Y + QA_D + 9, q.label, 13.0, if (focused) WHITE else MUTED, if (focused) 1.0 else .64, an = 8)
        }
        return out
    }

    /** The LIVE pill at (x, y); returns its events and width. */
    fun livePill(x: Double, y: Double): Pair<List<String>, Double> {
        val w = 50.0
        return listOf(box(x, y, w, 20.0, 4.0, LIVE), box(x + 8, y + 8, 5.0, 5.0, 2.5, WHITE), text(x + 17, y + 10, "LIVE", 11.0, WHITE, bold = true, an = 4)) to w
    }

    /** The scrubber (§10): an 8 px bar (5 here) with a 22 px knob (15 here) and the times either side. */
    fun scrubber(y: Double, w: Double, f: Double, left: String, right: String): List<String> {
        val x0 = SIDE + 58; val x1 = w - SIDE - 58
        val p = f.coerceIn(0.0, 1.0)
        val kx = x0 + (x1 - x0) * p
        return listOf(text(SIDE, y, left, 15.0, an = 4), text(w - SIDE, y, right, 15.0, an = 6),
            box(x0, y - 2.5, x1 - x0, 5.0, 2.5, WHITE, .22), box(x0, y - 2.5, (x1 - x0) * p, 5.0, 2.5, ACCENT),
            box(kx - 11, y - 11, 22.0, 22.0, 11.0, WHITE, .25), box(kx - 7.5, y - 7.5, 15.0, 15.0, 7.5, WHITE))
    }

    /** The colour-key legend (§9) at the bottom left, with button hints after a divider. */
    fun legend(keys: List<Key>, hints: List<Pair<String, String>>): List<String> {
        val fs = 13.0
        var w = 34.0
        for (k in keys) w += 25 + 7 + textW(k.label, fs) + 20
        if (hints.isNotEmpty()) { w += 14; for ((b, l) in hints) w += textW(b, 10.0) + 12 + 6 + textW(l, fs) + 14 }
        val y = H - 19 - 30
        val out = arrayListOf(box(SIDE, y, w, 30.0, 15.0, BG, .78))
        var x = SIDE + 17
        for (k in keys) {
            out += box(x, y + 9, 25.0, 11.0, 5.5, k.color)
            out += text(x + 32, y + 15, k.label, fs, WHITE, .86, bold = true, an = 4)
            x += 25 + 7 + textW(k.label, fs) + 20
        }
        if (hints.isNotEmpty()) {
            out += box(x - 6, y + 7, 1.0, 16.0, 0.0, WHITE, .18); x += 10
            for ((b, l) in hints) {
                val bw = textW(b, 10.0) + 12
                out += box(x, y + 7, bw, 16.0, 3.0, BG, 0.0, WHITE to 1.0)
                out += text(x + bw / 2, y + 15, b, 10.0, WHITE, bold = true, an = 5)
                out += text(x + bw + 6, y + 15, l, fs, WHITE, .55, bold = true, an = 4)
                x += bw + 6 + textW(l, fs) + 14
            }
        }
        return out
    }

    /** The buffering hint chip (§10) at the right of the info block, with the yellow key pill. */
    fun hintChip(w: Double, y: Double, s: String): List<String> {
        val fs = 15.0
        val cw = 22 + 23 + 10 + textW(s, fs) + 22
        val x = w - SIDE - cw
        return listOf(box(x, y, cw, 34.0, 17.0, "&H042228&", .82, YELLOW to 1.5), box(x + 16, y + 11.5, 23.0, 11.0, 5.5, YELLOW),
            text(x + 16 + 23 + 10, y + 17, s, fs, "&HA8F3FF&", bold = true, an = 4))
    }

    /** A dark panel (info, Up next): rounded, the dialog colour with its thin ring. */
    fun panel(x: Double, y: Double, w: Double, h: Double): String = box(x, y, w, h, 14.0, "&H160C0A&", .95, "&HFFFFFF&" to 0.0)
}
