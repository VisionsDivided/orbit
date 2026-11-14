// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.kmmarin.tvbro.ui

import android.view.KeyEvent as AKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import io.github.kmmarin.tvbro.*
import io.github.kmmarin.tvbro.data.Net
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.text.font.FontWeight
import io.github.kmmarin.tvbro.ui.live.HlsHintChip
import io.github.kmmarin.tvbro.ui.live.LiveLogic
import io.github.kmmarin.tvbro.ui.live.LiveStyle
import io.github.kmmarin.tvbro.ui.live.O5Popup
import io.github.kmmarin.tvbro.ui.live.PlayerInfo
import io.github.kmmarin.tvbro.ui.live.PlayerInfoBlock
import io.github.kmmarin.tvbro.ui.live.PlayerTop
import io.github.kmmarin.tvbro.ui.live.QuickAction
import io.github.kmmarin.tvbro.ui.live.playerScrims
import io.github.kmmarin.tvbro.ui.orbit5.ColorKeyActions
import io.github.kmmarin.tvbro.ui.orbit5.ColorKeyLegend
import io.github.kmmarin.tvbro.ui.orbit5.KeyAction
import io.github.kmmarin.tvbro.ui.orbit5.O5
import io.github.kmmarin.tvbro.ui.orbit5.O5Shape
import io.github.kmmarin.tvbro.ui.orbit5.OrbitCanvas

/**
 * The live player's page on a phone: [PhonePlayerView] bound to the model. Its actions are the quick bar's live ones
 * (Favourite, Record, Format, Start over / Back to live, Guide, Info) plus More, which opens every option as a sheet;
 * under them the group's channels (tap switches). Full screen turns the phone sideways (and back).
 */
@Composable
private fun PhonePlayerPage(
    m: AppModel, ch: io.github.kmmarin.tvbro.data.Channel, now: Long, cur: io.github.kmmarin.tvbro.data.Programme?, next: io.github.kmmarin.tvbro.data.Programme?,
    clock: String, tech: String, status: String, controls: Boolean, hint: Boolean,
    onTap: () -> Unit, onHint: () -> Unit, onRec: () -> Unit, onMore: () -> Unit,
) {
    val act = LocalContext.current as? android.app.Activity
    val live = m.catchup == null && cur != null
    val info = PlayerInfo(
        channel = ch.name, sub = "Channel ${ch.number} · ${m.groupLabel(ch.group).removeSuffix("  *")}", logo = ch.logo, clock = clock,
        title = m.catchup?.title ?: cur?.title ?: "No guide information",
        time = if (m.catchup != null) "Catch-up" else cur?.let { p -> listOfNotNull(hhmm(p.start) + " – " + hhmm(p.stop), io.github.kmmarin.tvbro.data.EpisodeNum.line(p.ep, p.sub)).joinToString(" · ") },
        live = m.catchup == null, catchup = m.catchup != null,
        progress = if (live) LiveLogic.progress(cur!!.start, cur.stop, now) else null,
        start = if (live) hhmm(cur!!.start) else null, end = if (live) hhmm(cur!!.stop) else null,
        next = if (m.catchup == null) next?.let { "Next · ${hhmm(it.start)} ${it.title}" } else null,
        tech = tech.ifEmpty { null }, rec = m.activeRec(ch) != null,
    )
    val fav = ch.key in m.favourites
    val recNow = m.activeRec(ch) != null
    val fmt = m.channelFormat(ch.key)
    val actions = listOfNotNull(
        QuickAction("Favourite", icon = Icons.Filled.Star, tint = if (fav) O5.Rating else null, on = fav) { m.toggleFav(ch) },
        QuickAction(if (recNow) "Stop rec" else "Record", glyph = "●", tint = LiveStyle.Rec, on = recNow, onClick = onRec),
        if (io.github.kmmarin.tvbro.data.LiveFormat.canHls(ch.url)) QuickAction("Format · " + LiveLogic.effectiveFormat(fmt, m.liveHls),
            glyph = LiveLogic.effectiveFormat(fmt, m.liveHls), tint = if (fmt != null) LiveStyle.FmtText else null) { io.github.kmmarin.tvbro.ui.live.cycleFormat(m, ch) } else null,
        if (m.catchup != null) QuickAction("Back to live", glyph = "LIVE") { m.catchup = null }
        else if (cur != null && io.github.kmmarin.tvbro.data.CatchupUrl.supports(ch, cur.start, now)) QuickAction("Start over", icon = Icons.Filled.Refresh) {
            val u = io.github.kmmarin.tvbro.data.CatchupUrl.build(ch, cur.start, cur.stop, System.currentTimeMillis() / 1000)
            if (u == null) m.toast("Catch-up not available for this programme") else m.catchup = CatchupPlay(u, cur.title)
        } else null,
        QuickAction("Guide", icon = Icons.Filled.DateRange) { io.github.kmmarin.tvbro.ui.live.PhoneLive.guide = true; m.focusCh = ch; m.leavePlayer() },
        QuickAction("Info", icon = Icons.Filled.Info) { if (cur != null) m.progMenu = ch to cur else m.toast("No programme info") },
        QuickAction("More", glyph = "•••", onClick = onMore),
    )
    val list = m.filtered.ifEmpty { listOf(ch) }
    io.github.kmmarin.tvbro.ui.live.PhonePlayerView(
        info, actions, controls,
        io.github.kmmarin.tvbro.ui.live.PhonePlayerEvents(
            onTapVideo = onTap, onBack = { m.leavePlayer() },
            onFullscreen = {
                val wide = act?.resources?.configuration?.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                if (wide) {
                    act?.requestedOrientation = if (PhoneVideo.forcedLandscape) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                        else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                } else { PhoneVideo.forcedLandscape = true; act?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }
            },
            onPrev = { m.zap(-1) }, onNext = { m.zap(1) }, onHint = onHint,
            onVideoRect = { PhoneVideo.rect = it },
        ),
        hint = hint, status = status,
    ) {
        item(key = "channels-heading") { io.github.kmmarin.tvbro.ui.live.PhoneListHeading("Channels · " + m.groupLabel(m.group).removeSuffix("  *")) }
        items(list.size, key = { "ch-" + list[it].key }) { i ->
            val c = list[i]
            io.github.kmmarin.tvbro.ui.live.PhoneChannelRow(c, m.epg.programmes(c), now, rowMarks(m, c), { m.openChannelMenu(c) }) { if (c.key != ch.key) m.play(c) }
        }
    }
}

@Composable
fun PlayerScreen(m: AppModel) {
    val ch = m.current
    if (ch == null) { m.screen = Screen.LIST; return }
    val ctx = LocalContext.current
    val now = rememberNow()

    // the shared live player: the video itself is drawn by LiveVideo (under this screen's overlays)
    val exo = rememberLiveExo(m)
    // (volume: LiveVideo, full screen always with sound)
    var status by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var altIdx by remember { mutableIntStateOf(0) }
    val alts = remember(ch.key, m.channels) { m.alternates(ch) }
    val altsNow = rememberUpdatedState(alts)
    LaunchedEffect(ch.key) { altIdx = 0 }
    val attempts = remember { intArrayOf(0) }

    DisposableEffect(exo) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                attempts[0]++
                status = "Reconnecting (${attempts[0]})..."
                if (attempts[0] % 3 == 0 && altsNow.value.isNotEmpty()) { altIdx++; status = "Trying alternate stream..." }
                reload++
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) { attempts[0] = 0; status = "" }
                else if (state == Player.STATE_BUFFERING && status.isEmpty()) status = "Buffering..."
                // a live stream never ends: the provider closed the connection, connect again (catch-up may end)
                else if (state == Player.STATE_ENDED && m.catchup == null) { attempts[0]++; status = "Reconnecting (${attempts[0]})..."; reload++ }
            }
        }
        io.github.kmmarin.tvbro.data.Busy.playing = true
        exo.addListener(l)
        exo.setVideoChangeFrameRateStrategy(if (m.matchFps) androidx.media3.common.C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS else androidx.media3.common.C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
        onDispose { io.github.kmmarin.tvbro.data.Busy.playing = false; exo.removeListener(l) }
    }

    val url = m.catchup?.url ?: if (altIdx == 0 || alts.isEmpty()) ch.url else alts[(altIdx - 1) % alts.size].url
    LaunchedEffect(url) { attempts[0] = 0; status = "" }
    LaunchedEffect(url, reload, m.liveGen) {
        val a = attempts[0]
        if (a > 0) delay(minOf(1000L shl (a - 1).coerceAtMost(4), 15_000L))
        // already playing this channel (opened from the preview): keep going, no re-buffer
        if (a == 0 && m.liveUrl == url && exo.playbackState != Player.STATE_IDLE) { exo.playWhenReady = true; m.liveKey = ch.key; return@LaunchedEffect }
        // Orbit: join the household's stream count (asks to stop the oldest viewer when every stream is in use)
        if (!m.orbit.claimStream("live", ch.name, ask = true)) { m.screen = Screen.LIST; return@LaunchedEffect }
        m.liveUrl = url; m.liveKey = ch.key
        liveLoad(exo, url, if (m.catchup != null) ch.name + " (catch-up)" else ch.name, m.channelFormat(ch.key))
    }

    var overlay by remember { mutableStateOf(true) }
    var bar by remember { mutableStateOf(false) }
    var tech by remember { mutableStateOf("") }
    LaunchedEffect(ch.key) { m.applyDelayFor(ch.key) }
    LaunchedEffect(overlay || bar) { while (overlay || bar) { tech = listOf(techInfo(exo), io.github.kmmarin.tvbro.orbit.PlayStats.live.snapshot()?.line().orEmpty()).filter { it.isNotEmpty() }.joinToString("\n"); delay(1000) } }
    // Orbit: a TS channel that stalls again and again in its first two minutes gets a small hint to try HLS (once per
    // channel, never a silent switch). It takes no focus: OK while it shows says yes, otherwise it goes away.
    var hlsHint by remember { mutableStateOf(false) }
    LaunchedEffect(ch.key, url) {
        hlsHint = false
        if (m.catchup != null || !io.github.kmmarin.tvbro.data.LiveFormat.canHls(url)) return@LaunchedEffect
        while (ch.key !in m.stallHinted) {
            delay(2000)
            // the channel chose a format, or Settings already says HLS: nothing to suggest
            if (m.channelFormat(ch.key) != null || m.liveHls) break
            if (m.liveKey != ch.key) continue
            val s = io.github.kmmarin.tvbro.orbit.PlayStats.live.snapshot() ?: continue
            val t = System.currentTimeMillis()
            if (io.github.kmmarin.tvbro.data.StallHint.suggest(s, t)) { m.stallHinted += ch.key; hlsHint = true; break }
            if (t - s.startedAt > io.github.kmmarin.tvbro.data.StallHint.WINDOW_MS) break
        }
    }
    LaunchedEffect(hlsHint) { if (hlsHint) { delay(20_000); hlsHint = false } }
    val acceptHls = { hlsHint = false; m.setChannelFormat(ch, io.github.kmmarin.tvbro.data.ChannelFormats.HLS) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(ch.key, tick, m.catchup) { overlay = true; delay(5000); overlay = false }

    var digits by remember { mutableStateOf("") }
    LaunchedEffect(digits) {
        if (digits.isNotEmpty()) {
            delay(1800)
            val n = digits.toIntOrNull()
            if (n != null && !m.zapNumber(n)) m.toast("No channel $n")
            digits = ""
        }
    }

    var recMenu by remember { mutableStateOf<List<Pair<String, () -> Unit>>?>(null) }
    BackHandler { if (bar) bar = false else if (recMenu != null) recMenu = null else m.leavePlayer() }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    LaunchedEffect(recMenu, bar) { if (recMenu == null && !bar) runCatching { fr.requestFocus() } }
    val (cur, next) = m.epg.nowNext(ch, now)
    /** Record (green, R, the quick action): stop the running recording, or the record choices. */
    val openRec: () -> Unit = {
        val a = m.activeRec(ch)
        recMenu = if (a != null) listOf("Stop recording" to { m.stopRec(a); recMenu = null })
        else {
            val c = m.epg.nowNext(ch, System.currentTimeMillis() / 1000).first
            listOfNotNull(
                "Record until stopped" to { m.recordNow(ch, 0); recMenu = null },
                "Record 30 minutes" to { m.recordNow(ch, 30); recMenu = null },
                "Record 1 hour" to { m.recordNow(ch, 60); recMenu = null },
                "Record 2 hours" to { m.recordNow(ch, 120); recMenu = null },
                c?.let { "Record until end of programme" to { m.recordNow(ch, 0, it.stop * 1000 + 60_000); recMenu = null } },
            )
        }
    }
    // Colour keys of the player overlay (spec section 9): Favourite, Record, Format TS / HLS (yellow also answers the
    // "Try HLS?" hint), Options (the channel menu)
    ColorKeyActions(
        red = KeyAction("Favourite") { m.toggleFav(ch) },
        green = KeyAction("Record") { openRec() },
        yellow = KeyAction("Format: TS / HLS") { if (hlsHint) acceptHls() else io.github.kmmarin.tvbro.ui.live.cycleFormat(m, ch) },
        blue = KeyAction("Options") { m.openChannelMenu(ch) },
    )
    val showInfo = overlay || bar
    var clock by remember { mutableStateOf(hhmm(System.currentTimeMillis() / 1000)) }
    LaunchedEffect(showInfo) { while (showInfo) { clock = hhmm(System.currentTimeMillis() / 1000); delay(5000) } }

    // Touch screens (no remote, maybe no Back button): a tap shows / hides the player bar, a swipe down leaves full
    // screen into the preview like Back (same animation, picture and sound go on). Remote keys are not affected.
    val swipeOut = with(androidx.compose.ui.platform.LocalDensity.current) { 120.dp.toPx() }
    // A phone (spec section 13) has its own touch page instead (PhonePlayer.kt): tap on the video shows / hides its
    // controls, the options open as a bottom sheet.
    val phone = io.github.kmmarin.tvbro.ui.shell.rememberIsPhone()
    Box(
        Modifier.fillMaxSize()
            .then(if (phone) Modifier else Modifier
                .pointerInput(Unit) { detectTapGestures(onTap = { if (recMenu == null) bar = !bar }) }
                .pointerInput(Unit) {
                    var dy = 0f
                    detectVerticalDragGestures(onDragStart = { dy = 0f }, onDragEnd = { if (dy > swipeOut && recMenu == null) { bar = false; m.leavePlayer() } }) { _, d -> dy += d }
                })
            .focusRequester(fr).countFocus().focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val code = e.nativeKeyEvent.keyCode
                if (recMenu != null || bar) return@onPreviewKeyEvent false
                when {
                    e.key == Key.R -> { openRec(); true }
                    code in AKeyEvent.KEYCODE_0..AKeyEvent.KEYCODE_9 -> { if (digits.length < 4) digits += (code - AKeyEvent.KEYCODE_0); true }
                    code in AKeyEvent.KEYCODE_NUMPAD_0..AKeyEvent.KEYCODE_NUMPAD_9 -> { if (digits.length < 4) digits += (code - AKeyEvent.KEYCODE_NUMPAD_0); true }
                    e.key == Key.DirectionUp || e.key == Key.ChannelUp -> { m.zap(1); true }
                    e.key == Key.ChannelDown -> { m.zap(-1); true }
                    e.key == Key.DirectionDown || e.key == Key.Menu -> { bar = true; true }
                    e.key == Key.DirectionLeft -> { m.leavePlayer(); true }
                    e.key == Key.DirectionRight -> { GuideReturn.toList = false; m.screen = Screen.GUIDE; true }
                    e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.ButtonA -> {
                        if (hlsHint) acceptHls() else if (overlay) overlay = false else tick++
                        true
                    }
                    e.key == Key.MediaPlayPause -> { exo.playWhenReady = !exo.playWhenReady; true }
                    else -> false
                }
            },
    ) {
        if (phone) PhonePlayerPage(m, ch, now, cur, next, clock, tech, status, overlay, hlsHint,
            onTap = { if (overlay) overlay = false else tick++ }, onHint = acceptHls, onRec = openRec, onMore = { bar = true })
        else OrbitCanvas {
            Box(Modifier.fillMaxSize()) {
                if (showInfo) {
                    val live = m.catchup == null && cur != null
                    val info = PlayerInfo(
                        channel = ch.name, sub = "Channel ${ch.number} · ${m.groupLabel(ch.group).removeSuffix("  *")}", logo = ch.logo, clock = clock,
                        title = m.catchup?.title ?: cur?.title ?: "No guide information",
                        time = if (m.catchup != null) "Catch-up" else cur?.let { p ->
                            listOfNotNull(hhmm(p.start) + " – " + hhmm(p.stop), io.github.kmmarin.tvbro.data.EpisodeNum.line(p.ep, p.sub)).joinToString(" · ")
                        },
                        live = m.catchup == null, catchup = m.catchup != null,
                        progress = if (live) LiveLogic.progress(cur!!.start, cur.stop, now) else null,
                        start = if (live) hhmm(cur!!.start) else null, end = if (live) hhmm(cur!!.stop) else null,
                        next = if (m.catchup == null) next?.let { "Next · ${hhmm(it.start)} ${it.title}" } else null,
                        tech = tech.ifEmpty { null }, rec = m.activeRec(ch) != null,
                    )
                    Box(Modifier.fillMaxSize().playerScrims())
                    PlayerTop(info)
                    PlayerInfoBlock(info, Modifier.padding(top = 612.dp))
                }
                if (hlsHint) HlsHintChip(Modifier.align(Alignment.TopEnd).padding(top = 618.dp, end = 96.dp)) { acceptHls() }
                if (status.isNotEmpty()) {
                    Text(status, color = O5.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = if (showInfo) 230.dp else 60.dp, end = 96.dp)
                            .background(O5.ScrimPanel, O5Shape.Pill).padding(horizontal = 22.dp, vertical = 10.dp))
                }
                if (digits.isNotEmpty()) {
                    Text(digits, color = O5.Text, fontSize = 96.sp, fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.align(Alignment.TopStart).padding(start = 96.dp, top = 170.dp)
                            .background(O5.ScrimPanel, RoundedCornerShape(20.dp)).padding(horizontal = 32.dp, vertical = 8.dp))
                }
                if (showInfo) ColorKeyLegend(Modifier.align(Alignment.BottomStart).padding(start = 96.dp, bottom = 28.dp),
                    hints = if (bar) listOf("OK" to "Select", "BACK" to "Close") else listOf("DOWN" to "Options", "BACK" to "Channels"))
            }
        }
        if (bar) {
            val fmt = m.channelFormat(ch.key)
            val fav = ch.key in m.favourites
            val recNow = m.activeRec(ch) != null
            val extra = buildMap {
                put("guide", QuickAction("Guide", icon = Icons.Filled.DateRange) { bar = false; GuideReturn.toList = false; m.screen = Screen.GUIDE })
                put("fav", QuickAction("Favourite", icon = Icons.Filled.Star, tint = if (fav) O5.Rating else null, on = fav, value = if (fav) "in My list" else null) { m.toggleFav(ch) })
                put("rec", QuickAction(if (recNow) "Stop rec" else "Record", glyph = "●", tint = LiveStyle.Rec, on = recNow) { bar = false; openRec() })
                if (m.catchup != null) put("startover", QuickAction("Back to live", glyph = "LIVE") { bar = false; m.catchup = null })
                else if (cur != null && io.github.kmmarin.tvbro.data.CatchupUrl.supports(ch, cur.start, now)) put("startover", QuickAction("Start over", icon = Icons.Filled.Refresh, value = cur.title) {
                    val t = System.currentTimeMillis() / 1000
                    val u = io.github.kmmarin.tvbro.data.CatchupUrl.build(ch, cur.start, cur.stop, t)
                    if (u == null) m.toast("Catch-up not available for this programme") else { bar = false; m.catchup = CatchupPlay(u, cur.title) }
                })
                if (io.github.kmmarin.tvbro.data.LiveFormat.canHls(ch.url)) put("format", QuickAction("Format · " + LiveLogic.effectiveFormat(fmt, m.liveHls),
                    glyph = LiveLogic.effectiveFormat(fmt, m.liveHls), tint = if (fmt != null) LiveStyle.FmtText else null, value = if (fmt == null) "default" else "this channel") {
                    io.github.kmmarin.tvbro.ui.live.cycleFormat(m, ch)
                })
                put("channels", QuickAction("Channels", icon = Icons.AutoMirrored.Filled.List) { bar = false; m.leavePlayer() })
                put("info", QuickAction("Info", icon = Icons.Filled.Info) { if (cur != null) m.progMenu = ch to cur else m.toast("No programme info") })
            }
            QuickBar(m, exo, m.livePv, true, extra, phone = phone) { bar = false }
        }
        if (phone) recMenu?.let { rows ->
            io.github.kmmarin.tvbro.ui.orbit5.PhoneSheet({ recMenu = null }) {
                Text("Record ${ch.name}", color = O5.Text, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(bottom = 10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { rows.forEach { (label, run) -> io.github.kmmarin.tvbro.ui.orbit5.PhoneSheetRow(label, onClick = run) } }
            }
        } else recMenu?.let { rows ->
            val first = remember { SafeFocus() }
            LaunchedEffect(Unit) { delay(100); first.request() }
            OrbitCanvas {
                Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                    O5Popup("Record ${ch.name}", rows, first)
                }
            }
        }
    }
}
