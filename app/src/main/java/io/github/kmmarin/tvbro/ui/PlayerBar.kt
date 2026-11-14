// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.kmmarin.tvbro.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.foundation.border
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import io.github.kmmarin.tvbro.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.text.font.FontWeight
import io.github.kmmarin.tvbro.ui.live.QuickAction
import io.github.kmmarin.tvbro.ui.live.QuickActionRow
import io.github.kmmarin.tvbro.ui.live.playerScrims
import io.github.kmmarin.tvbro.ui.orbit5.O5
import io.github.kmmarin.tvbro.ui.orbit5.OrbitCanvas
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ASPECTS = listOf("Normal" to AspectRatioFrameLayout.RESIZE_MODE_FIT, "Fill" to AspectRatioFrameLayout.RESIZE_MODE_FILL, "Zoom" to AspectRatioFrameLayout.RESIZE_MODE_ZOOM, "16:9" to AspectRatioFrameLayout.RESIZE_MODE_FILL, "4:3" to AspectRatioFrameLayout.RESIZE_MODE_FILL)

/** Applies the saved caption size and background to a player view. */
fun applyCaptions(pv: PlayerView, m: AppModel) {
    val size = when (m.store.captionSize) { 0 -> 0.04f; 2 -> 0.075f; else -> 0.0533f }
    val bg = when (m.store.captionBg) { 0 -> android.graphics.Color.TRANSPARENT; 2 -> 0xFF000000.toInt(); else -> 0x99000000.toInt() }
    pv.subtitleView?.setFractionalTextSize(size)
    pv.subtitleView?.setStyle(CaptionStyleCompat(android.graphics.Color.WHITE, bg, android.graphics.Color.TRANSPARENT,
        if (m.store.captionBg == 0) CaptionStyleCompat.EDGE_TYPE_OUTLINE else CaptionStyleCompat.EDGE_TYPE_NONE, android.graphics.Color.BLACK, null))
    pv.resizeMode = ASPECTS[m.aspectMode.coerceIn(0, ASPECTS.size - 1)].second
}

private fun lang(code: String?): String? = code?.takeIf { it.isNotBlank() && it != "und" }?.let { Locale(it).displayLanguage.ifBlank { it } }

private fun layout(n: Int) = when (n) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> if (n > 0) "${n}ch" else "" }

private fun trackRows(exo: ExoPlayer, type: Int, offLabel: String?, chosen: (lang: String?, on: Boolean) -> Unit = { _, _ -> }, close: () -> Unit): List<Pair<String, () -> Unit>> {
    val rows = ArrayList<Pair<String, () -> Unit>>()
    for (g in exo.currentTracks.groups) {
        if (g.type != type) continue
        for (i in 0 until g.length) {
            val f = g.getTrackFormat(i)
            val name = when (type) {
                C.TRACK_TYPE_VIDEO -> "${f.width}x${f.height}" + (if (f.frameRate > 0) " ${"%.0f".format(f.frameRate)} fps" else "") + (if (f.bitrate > 0) "  ${f.bitrate / 1000} kbps" else "")
                C.TRACK_TYPE_AUDIO -> listOfNotNull(f.label, lang(f.language), layout(f.channelCount).ifEmpty { null }).distinct().joinToString(" ").ifBlank { "Track ${i + 1}" }
                else -> listOfNotNull(f.label, lang(f.language)).distinct().joinToString(" ").ifBlank { "Track ${i + 1}" } +
                    (if (f.sampleMimeType?.contains("cea-608", true) == true) "  (closed captions, CEA-608)" else if (f.sampleMimeType?.contains("cea-708", true) == true) "  (closed captions, CEA-708)" else "")
            }
            rows += ((if (g.isTrackSelected(i)) "* " else "  ") + name) to {
                var b = exo.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, listOf(i)))
                if (type == C.TRACK_TYPE_TEXT) b = b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                exo.trackSelectionParameters = b.build(); chosen(f.language, true); close()
            }
        }
    }
    if (type == C.TRACK_TYPE_VIDEO) rows.add(0, "  Automatic" to {
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build(); close()
    })
    if (offLabel != null) rows += offLabel to {
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build(); chosen(null, false); close()
    }
    return rows
}

/** Seek bar for movies and episodes: Left/Right skip 10 s, holding the key accelerates. */
@Composable
private fun SeekBar(exo: ExoPlayer, mod: Modifier) {
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { pos = exo.currentPosition; dur = exo.duration.coerceAtLeast(0); delay(500) } }
    var f by remember { mutableStateOf(false) }
    fun t(ms: Long) = "%d:%02d:%02d".format(ms / 3600000, ms / 60000 % 60, ms / 1000 % 60)
    Column(
        mod.onFocusChanged { f = it.isFocused }.onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val step = 10_000L * (1 + e.nativeKeyEvent.repeatCount / 4).coerceAtMost(12)
            when (e.key) {
                Key.DirectionLeft -> { exo.seekTo((exo.currentPosition - step).coerceAtLeast(0)); pos = exo.currentPosition; true }
                Key.DirectionRight -> { exo.seekTo(exo.currentPosition + step); pos = exo.currentPosition; true }
                else -> false
            }
        }.countFocus().focusable()
            .background(if (f) Color(0x1FFFFFFF) else Color.Transparent, RoundedCornerShape(14.dp))
            .border(if (f) 3.dp else 0.dp, if (f) Color.White else Color.Transparent, RoundedCornerShape(14.dp)).padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(t(pos) + " / " + t(dur) + if (f) "    Left/Right: 10 s (hold to speed up)" else "", color = O5.Text, fontSize = 22.sp)
        io.github.kmmarin.tvbro.ui.live.Scrubber(if (dur > 0) pos.toFloat() / dur else 0f, Modifier.fillMaxWidth().padding(top = 10.dp))
    }
}

/**
 * Quick settings of the player (direction 5, spec section 10): a row of 84 px circles along the bottom, each with its
 * label and current value. Live TV order: Guide, Favourite, Record, Audio, Subtitles, Start over, Format, Channels,
 * Sleep timer, Info, then Video, Audio delay, Aspect, Settings ([extra] brings the live-only ones, by id: guide, fav,
 * rec, startover, format, channels, info). Films and episodes get the seek bar and their playback controls first.
 * Hides itself after 5 s without a key press, never while one of its menus is open.
 */
@Composable
fun QuickBar(m: AppModel, exo: ExoPlayer, pv: PlayerView?, live: Boolean, extra: Map<String, QuickAction> = emptyMap(), onClose: () -> Unit) {
    BackHandler { onClose() }
    val first = remember { SafeFocus() }
    val seekFr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(100); if (live) first.request() else runCatching { seekFr.requestFocus() } }
    var tick by remember { mutableIntStateOf(0) }
    val now = remember(tick) { System.currentTimeMillis() }
    LaunchedEffect(Unit) { while (true) { delay(15_000); tick++ } }
    val v = exo.videoFormat
    val a = exo.audioFormat
    val textOff = exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
    val subSel = exo.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }?.getTrackFormat(0)
    val sleepLeft = if (m.sleepAt > 0) ((m.sleepAt - now) / 60000 + 1).toInt() else 0
    fun pick(title: String, rows: List<Pair<String, () -> Unit>>) { if (rows.isEmpty()) m.toast("Nothing to choose") else m.menu = MenuState(title, rows) }
    var chips by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { chips = techInfo(exo); delay(1500) } }
    val dk = if (live) m.current?.key else m.vodPlay?.key
    fun delayMenu() {
        m.menu = MenuState("Audio delay: %+d ms".format(m.audioDelayMs), listOf(
            "+50 ms (audio later)" to { m.changeAudioDelay(50, dk); delayMenu() },
            "-50 ms (audio earlier)" to { m.changeAudioDelay(-50, dk); delayMenu() },
            "+250 ms" to { m.changeAudioDelay(250, dk); delayMenu() },
            "-250 ms" to { m.changeAudioDelay(-250, dk); delayMenu() },
            "Reset to 0" to { m.changeAudioDelay(0, dk); delayMenu() },
        ))
    }
    var lastKey by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lastKey, m.menu, m.prompt) {
        if (m.menu != null || m.prompt != null) return@LaunchedEffect
        delay(5_000); onClose()
    }
    val std = linkedMapOf(
        "audio" to QuickAction("Audio", glyph = "♪", value = listOfNotNull(lang(a?.language), a?.channelCount?.let { layout(it) }?.ifEmpty { null }).joinToString(" ").ifBlank { "Default" }) {
            pick("Audio track", trackRows(exo, C.TRACK_TYPE_AUDIO, null, { l, _ -> if (!live) m.rememberTrack(true, l, true) }) { m.menu = null })
        },
        "subs" to QuickAction("Subtitles", glyph = "CC", value = if (textOff || subSel == null) "Off" else (lang(subSel.language) ?: subSel.label ?: "On")) {
            pick("Subtitles", trackRows(exo, C.TRACK_TYPE_TEXT, "  Off", { l, on -> if (!live) m.rememberTrack(false, l, on) }) { m.menu = null })
        },
        "sleep" to QuickAction("Sleep timer", glyph = "Zz", value = if (sleepLeft > 0) "$sleepLeft min" else "Off", on = sleepLeft > 0) {
            pick("Sleep timer", listOf(0, 15, 30, 60, 90).map { (if (it == 0) "  Off" else "  $it minutes") to { m.setSleep(it) } })
        },
        "video" to QuickAction("Video", glyph = "HD", value = if (v != null && v.width > 0) "${v.width}x${v.height}" else "Auto") {
            pick("Video quality", trackRows(exo, C.TRACK_TYPE_VIDEO, null) { m.menu = null })
        },
        "delay" to QuickAction("Audio delay", glyph = "±", value = "%+d ms".format(m.audioDelayMs)) { delayMenu() },
        "aspect" to QuickAction("Aspect", glyph = "▭", value = ASPECTS[m.aspectMode.coerceIn(0, ASPECTS.size - 1)].first) {
            pick("Aspect / zoom", ASPECTS.mapIndexed { i, p -> ((if (i == m.aspectMode) "* " else "  ") + p.first) to { m.aspectMode = i; pv?.resizeMode = p.second } })
        },
        "settings" to QuickAction("Settings", icon = Icons.Filled.Settings) { onClose(); if (live) m.settingsBack = Screen.PLAYER; m.screen = Screen.SETTINGS },
    )
    val actions: List<QuickAction> = if (live) {
        listOf("guide", "fav", "rec", "audio", "subs", "startover", "format", "channels", "sleep", "info", "video", "delay", "aspect", "settings")
            .mapNotNull { extra[it] ?: std[it] }
    } else {
        listOfNotNull(
            QuickAction("Restart", icon = Icons.Filled.Refresh, value = "from the start") { exo.seekTo(0); onClose() },
            QuickAction("Rewind", glyph = "-10", value = "10 s") { exo.seekTo((exo.currentPosition - 10_000).coerceAtLeast(0)) },
            QuickAction("Forward", glyph = "+10", value = "10 s") { exo.seekTo(exo.currentPosition + 10_000) },
            QuickAction(if (exo.playWhenReady) "Pause" else "Play", icon = if (exo.playWhenReady) null else Icons.Filled.PlayArrow, glyph = "II") { exo.playWhenReady = !exo.playWhenReady },
            if (m.vodPlay?.key?.startsWith("e|") == true) QuickAction("Previous", glyph = "◀", value = "episode") { onClose(); m.stepEpisode(-1) } else null,
            if (m.vodPlay?.key?.startsWith("e|") == true) QuickAction("Next", glyph = "▶", value = "episode") { onClose(); m.stepEpisode(1) } else null,
        ) + listOf("audio", "subs", "video", "delay", "aspect", "sleep", "settings").mapNotNull { std[it] }
    }
    OrbitCanvas {
        Box(Modifier.fillMaxSize().onPreviewKeyEvent { lastKey = System.currentTimeMillis(); false }) {
            if (!live) {
                // films and episodes: their own scrim, date and seek bar (live TV draws its overlay around the bar)
                Box(Modifier.fillMaxSize().playerScrims())
                Text(io.github.kmmarin.tvbro.data.TimeFmt.dayTime(now, "EEE d MMM "), color = O5.Text, fontSize = 32.sp, fontWeight = FontWeight.Light,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 60.dp, end = 96.dp))
                Column(Modifier.padding(start = 78.dp, end = 78.dp, top = 690.dp)) {
                    SeekBar(exo, Modifier.fillMaxWidth().focusRequester(seekFr))
                    if (chips.isNotEmpty()) Text(chips, color = O5.Muted, fontSize = 17.sp, maxLines = 2, modifier = Modifier.padding(start = 18.dp, top = 6.dp))
                }
            }
            QuickActionRow(actions, first, Modifier.padding(top = 846.dp))
        }
    }
}
