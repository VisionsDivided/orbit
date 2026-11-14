// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.live

import io.github.kmmarin.tvbro.data.ChannelFormats
import io.github.kmmarin.tvbro.data.Programme
import io.github.kmmarin.tvbro.data.Rec

/** Plain rules behind the Live TV screens (unit-tested in LiveLogicTest). */
object LiveLogic {
    /** The yellow key's cycle for a channel's stream format: Default -> TS -> HLS -> Default. */
    fun nextFormat(cur: String?): String? = when (cur) {
        null -> ChannelFormats.TS
        ChannelFormats.TS -> ChannelFormats.HLS
        else -> null
    }

    /** "Default", "TS" or "HLS". */
    fun formatLabel(fmt: String?): String = when (fmt) { ChannelFormats.TS -> "TS"; ChannelFormats.HLS -> "HLS"; else -> "Default" }

    /** What a channel actually plays as: its own choice, else the Settings default. */
    fun effectiveFormat(fmt: String?, globalHls: Boolean): String = when (fmt) {
        ChannelFormats.HLS -> "HLS"; ChannelFormats.TS -> "TS"; else -> if (globalHls) "HLS" else "TS"
    }

    /** A recording of [chKey] / [chName] that is scheduled or running and covers [p]. */
    private fun sameChannel(r: Rec, chKey: String, chName: String) = if (r.chKey.isNotEmpty()) r.chKey == chKey else r.channel == chName

    /**
     * The recording that covers at least half of [p] (recordings are padded by a few minutes, which must not mark the
     * programmes before and after), or null.
     */
    fun recordingOf(recs: List<Rec>, chKey: String, chName: String, p: Programme): Rec? {
        val ps = p.start * 1000; val pe = p.stop * 1000
        val dur = (pe - ps).coerceAtLeast(1)
        return recs.firstOrNull { r ->
            (r.status == "scheduled" || r.status == "recording") && sameChannel(r, chKey, chName) &&
                (minOf(if (r.endMs == 0L) Long.MAX_VALUE else r.endMs, pe) - maxOf(r.startMs, ps)) * 2 >= dur
        }
    }

    /** Whether the channel is being recorded now. */
    fun recordingNow(recs: List<Rec>, chKey: String, chName: String): Boolean =
        recs.any { it.status == "recording" && sameChannel(it, chKey, chName) }

    /** Fraction of [start, stop] passed at [now], 0..1. */
    fun progress(start: Long, stop: Long, now: Long): Float = ((now - start).toFloat() / (stop - start).coerceAtLeast(1)).coerceIn(0f, 1f)
}
