// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Xtream live streams exist as MPEG-TS (".ts", one long connection) and as HLS (".m3u8", short segments). Some
 * providers deliver HLS much more steadily: measured on a real provider (2026-11-14), the TS stream of one channel
 * paused up to 12.9 s while its HLS segments arrived about 4x faster than real time. The player may prefer HLS;
 * recordings keep TS (they append the raw stream to a file).
 */
object LiveFormat {
    private val xtreamLiveTs = Regex("""(/live/[^/?#]+/[^/?#]+/[^/?#.]+)\.ts($|[?#])""")

    /** The HLS address of an Xtream live TS address; anything else (M3U links, catch-up, other formats) unchanged. */
    fun toHls(url: String): String = xtreamLiveTs.replace(url) { m -> m.groupValues[1] + ".m3u8" + m.groupValues[2] }

    /** Only Xtream live TS addresses have an HLS twin. */
    fun canHls(url: String): Boolean = xtreamLiveTs.containsMatchIn(url)

    /** HLS for this channel? Its own choice ([ChannelFormats.TS] / [ChannelFormats.HLS]) wins over the global setting. */
    fun useHls(override: String?, globalHls: Boolean): Boolean = when (override) {
        ChannelFormats.HLS -> true
        ChannelFormats.TS -> false
        else -> globalHls
    }

    /** The address the player opens for a live channel. */
    fun playUrl(url: String, override: String?, globalHls: Boolean): String = if (useHls(override, globalHls)) toHls(url) else url
}

/**
 * Per-channel stream format of one profile ("ts" or "hls"; a channel not listed follows the global setting). Synced as
 * one value in the profile's server prefs (key [PREF_KEY]): `{"at": ms, "channels": {"<channel key>": "hls"}}`, the
 * newer copy wins as a whole (a person changes these rarely, and from one TV at a time).
 */
data class ChannelFormats(val at: Long = 0L, val channels: Map<String, String> = emptyMap()) {
    operator fun get(key: String): String? = channels[key]

    /** [key] set to [fmt] (null / anything else = back to the global setting), stamped [now]. */
    fun with(key: String, fmt: String?, now: Long): ChannelFormats {
        val m = LinkedHashMap(channels)
        m.remove(key)
        if (fmt == TS || fmt == HLS) m[key] = fmt
        while (m.size > MAX) m.remove(m.keys.first())               // oldest choices go first
        return ChannelFormats(now, m)
    }

    /** Sync: which copy to keep. [toServer] = the local copy is newer and must be sent. */
    data class Merge(val result: ChannelFormats, val toServer: Boolean)

    fun toJson(): JsonObject = JsonObject(mapOf("at" to JsonPrimitive(at),
        "channels" to JsonObject(channels.mapValues { JsonPrimitive(it.value) })))

    companion object {
        const val TS = "ts"
        const val HLS = "hls"
        const val PREF_KEY = "tvChannelFormats"
        /** Fits the server's 16 KB value limit with room to spare. */
        const val MAX = 400

        fun fromJson(e: JsonElement?): ChannelFormats {
            val o = e as? JsonObject ?: return ChannelFormats()
            val at = (o["at"] as? JsonPrimitive)?.longOrNull ?: 0L
            val ch = (o["channels"] as? JsonObject).orEmpty().mapNotNull { (k, v) ->
                val f = (v as? JsonPrimitive)?.contentOrNull
                if (k.isNotEmpty() && k.length <= 300 && (f == TS || f == HLS)) k to f else null
            }.take(MAX).toMap()
            return ChannelFormats(at, ch)
        }

        fun merge(local: ChannelFormats, server: ChannelFormats?): Merge = when {
            server == null || server.at < local.at -> Merge(local, local.at > 0)
            server.at > local.at -> Merge(server, false)
            else -> Merge(local, false)
        }
    }
}

/**
 * A gentle hint, never a switch: a TS channel that stalls again and again in its first two minutes may play better
 * as HLS (the 2026-11-14 measurements). The TV shows "This channel keeps buffering. Try HLS for it?" once per channel.
 */
object StallHint {
    const val WINDOW_MS = 120_000L
    const val MIN_STALLS = 3

    fun suggest(s: PlaybackStats.Snapshot?, nowMs: Long): Boolean =
        s != null && s.kind == "live" && s.firstFrameMs != null && s.stalls >= MIN_STALLS && nowMs - s.startedAt <= WINDOW_MS
}
