// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

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
}
