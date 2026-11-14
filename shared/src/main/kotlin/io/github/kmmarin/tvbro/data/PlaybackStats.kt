// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

/**
 * Playback statistics for one stream session, to compare buffering on real channels before tuning anything:
 * time to first frame, stalls (count, total, longest), stream bitrate, network throughput, reconnects, errors.
 * A stall is buffering after the first frame; buffering before it is part of the start time. A restart of the same
 * stream (after an error or the provider closing it) is a reconnect in the same session, not a new one.
 * Platform neutral: the players feed it events, [now] is the clock in ms. Never holds a URL (they carry logins).
 */
class PlaybackStats(private val now: () -> Long = System::currentTimeMillis) {
    data class Snapshot(
        val kind: String, val label: String, val startedAt: Long, /** time actually playing */ val durationMs: Long,
        val firstFrameMs: Long?, val stalls: Int, val stallMs: Long, val longestStallMs: Long,
        val bitrateKbps: Int, val networkKbps: Int, val reconnects: Int, val errors: Int, val lastError: String?,
    ) {
        /** One line for the stream info screen. */
        fun line(): String {
            val parts = ArrayList<String>()
            parts += "start " + (firstFrameMs?.let { secs(it) } ?: "…")
            parts += if (stalls == 0) "no stalls" else "stalls $stalls (${secs(stallMs)}, longest ${secs(longestStallMs)})"
            if (bitrateKbps > 0) parts += "%.1f Mbps".format(bitrateKbps / 1000.0)
            if (networkKbps > 0) parts += "net %.1f Mbps".format(networkKbps / 1000.0)
            if (reconnects > 0) parts += "reconnects $reconnects"
            if (errors > 0) parts += "errors $errors" + (lastError?.let { " ($it)" } ?: "")
            return parts.joinToString("  |  ")
        }

        private fun secs(ms: Long) = "%.1f s".format(ms / 1000.0)
    }

    private var key: Int? = null
    private var kind = ""
    private var label = ""
    private var startedAt = 0L
    private var loadAt = 0L
    private var firstFrame: Long? = null
    private var stallSince: Long? = null
    private var stalls = 0
    private var stallMs = 0L
    private var longest = 0L
    private var bitrate = 0
    private var network = 0
    private var reconnects = 0
    private var errors = 0
    private var lastError: String? = null
    private var erroredSinceLoad = false
    private var playingSince: Long? = null
    private var playMs = 0L

    val active: Boolean @Synchronized get() = key != null

    /**
     * A stream (re)starts. [streamKey] identifies the stream without its URL (e.g. the URL's hash). The same key
     * again = a reconnect in this session; another key = a new session (the old one is returned to keep).
     */
    @Synchronized fun begin(streamKey: Int, kind: String, label: String): Snapshot? {
        val t = now()
        if (key == streamKey) {
            reconnects++
            endStall(t)
            endPlay(t)
            loadAt = t
            erroredSinceLoad = false
            return null
        }
        val old = if (key != null) snapshotAt(t) else null
        key = streamKey; this.kind = kind; this.label = label.take(80)
        startedAt = t; loadAt = t; firstFrame = null; stallSince = null
        stalls = 0; stallMs = 0; longest = 0; bitrate = 0; network = 0; reconnects = 0; errors = 0; lastError = null
        playingSince = null; playMs = 0
        erroredSinceLoad = false
        return old
    }

    /** The session ends (player stopped): its final numbers, or null when none was running. */
    @Synchronized fun end(): Snapshot? {
        if (key == null) return null
        val s = snapshotAt(now())
        key = null
        return s
    }

    @Synchronized fun firstFrame() {
        val t = now()
        if (firstFrame == null) firstFrame = t - loadAt
        endStall(t)
    }

    /** The player is buffering (true) or playing again (false). */
    @Synchronized fun buffering(on: Boolean) {
        val t = now()
        if (on) {
            if (firstFrame != null && stallSince == null) { stallSince = t; stalls++ }
        } else endStall(t)
    }

    /** The player is actually playing (true) or not (paused, buffering, stopped): [Snapshot.durationMs] is playing time. */
    @Synchronized fun playing(on: Boolean) {
        val t = now()
        if (on) { if (playingSince == null) playingSince = t } else endPlay(t)
    }

    @Synchronized fun bitrate(bps: Int) { if (bps > 0) bitrate = bps / 1000 }

    @Synchronized fun network(bps: Long) { if (bps > 0) network = (bps / 1000).toInt() }

    @Synchronized fun error(code: String) {
        errors++
        lastError = code.take(40)
        erroredSinceLoad = true
        endStall(now())
    }

    @Synchronized fun snapshot(): Snapshot? = if (key == null) null else snapshotAt(now())

    private fun endPlay(t: Long) {
        val s = playingSince ?: return
        playMs += t - s
        playingSince = null
    }

    private fun endStall(t: Long) {
        val s = stallSince ?: return
        val d = t - s
        stallMs += d
        if (d > longest) longest = d
        stallSince = null
    }

    private fun snapshotAt(t: Long): Snapshot {
        val open = stallSince?.let { t - it } ?: 0L                // a stall still going counts up to now
        val played = playMs + (playingSince?.let { t - it } ?: 0L)
        return Snapshot(kind, label, startedAt, played, firstFrame, stalls, stallMs + open, maxOf(longest, open),
            bitrate, network, reconnects, errors, lastError)
    }

    companion object {
        /** Sessions as JSON for export: names and numbers only, never addresses or logins. */
        fun toJson(list: List<Snapshot>, device: String, app: String): String {
            fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""
            val rows = list.joinToString(",\n    ") { s ->
                "{\"kind\":${q(s.kind)},\"channel\":${q(s.label)},\"startedAt\":${s.startedAt},\"durationMs\":${s.durationMs}," +
                    "\"firstFrameMs\":${s.firstFrameMs ?: "null"},\"stalls\":${s.stalls},\"stallMs\":${s.stallMs}," +
                    "\"longestStallMs\":${s.longestStallMs},\"bitrateKbps\":${s.bitrateKbps},\"networkKbps\":${s.networkKbps}," +
                    "\"reconnects\":${s.reconnects},\"errors\":${s.errors},\"lastError\":${s.lastError?.let(::q) ?: "null"}}"
            }
            return "{\n  \"app\": ${q(app)},\n  \"device\": ${q(device)},\n  \"sessions\": [\n    $rows\n  ]\n}\n"
        }
    }
}
