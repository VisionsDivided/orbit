// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.kmmarin.tvbro.orbit

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.MediaLoadData
import io.github.kmmarin.tvbro.data.PlaybackStats
import java.io.File

/**
 * Playback statistics on the TV (Phase 2: measure before tuning the buffer). One running session for live TV and one
 * for films / episodes, fed by the players' analytics events; finished sessions are kept (the last 100) for export.
 * Nothing here holds an address: streams are told apart by a hash of their URL.
 */
object PlayStats {
    val live = PlaybackStats()
    val vod = PlaybackStats()
    private val done = ArrayDeque<PlaybackStats.Snapshot>()

    @Synchronized fun keep(s: PlaybackStats.Snapshot?) {
        if (s == null || s.durationMs < 3000) return              // a channel played for under 3 s says nothing
        done.addLast(s)
        while (done.size > 100) done.removeFirst()
    }

    @Synchronized fun sessions(): List<PlaybackStats.Snapshot> = done.toList() + listOfNotNull(live.snapshot(), vod.snapshot())

    /** The analytics listener that feeds [s] from a player. */
    fun listener(s: PlaybackStats): AnalyticsListener = object : AnalyticsListener {
        override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) = s.playing(isPlaying)
        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) = s.firstFrame()
        override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> s.buffering(true)
                Player.STATE_READY, Player.STATE_IDLE, Player.STATE_ENDED -> s.buffering(false)
            }
        }
        override fun onDownstreamFormatChanged(eventTime: AnalyticsListener.EventTime, mediaLoadData: MediaLoadData) {
            val f = mediaLoadData.trackFormat ?: return
            if (mediaLoadData.trackType == C.TRACK_TYPE_VIDEO || mediaLoadData.trackType == C.TRACK_TYPE_DEFAULT) s.bitrate(f.bitrate)
        }
        override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) =
            s.network(bitrateEstimate)
        override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) =
            s.error(error.errorCodeName.removePrefix("ERROR_CODE_"))
    }

    /** Writes every kept session as JSON to the app's files folder (diag/), and returns the file. No addresses or logins. */
    fun export(ctx: Context): File {
        val dir = File(ctx.filesDir, "diag").apply { mkdirs() }
        val f = File(dir, "playback-stats-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) + ".json")
        val app = "Orbit " + io.github.kmmarin.tvbro.BuildConfig.VERSION_NAME
        f.writeText(PlaybackStats.toJson(sessions(), android.os.Build.MODEL ?: "Android", app))
        return f
    }
}
