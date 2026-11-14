// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kmmarin.tvbro.AppModel
import io.github.kmmarin.tvbro.Screen
import io.github.kmmarin.tvbro.orbit.OrbitApi
import io.github.kmmarin.tvbro.orbit.ServerFinder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Ok = Color(0xFFA5D6A7)

/** Settings ▸ Home server: link this TV to an Orbit account, or show and end the link. */
fun LazyListScope.orbitSection(m: AppModel) {
    val o = m.orbit
    item { Text("Orbit home server", color = TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)) }
    val l = o.link
    if (l == null) {
        item {
            Text("Link this TV to your Orbit account on your home server. Each profile then has its own My list, favourite " +
                "channels and resume points, shared with your other devices.", color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(vertical = 3.dp))
        }
        if (o.signedOut) item { Text("This TV was signed out from another device. Link it again to use your account.", color = Accent, fontSize = 14.sp) }
        item { TvButton("Link this TV…", Modifier.padding(vertical = 6.dp)) { o.clearMessage(); m.screen = Screen.ORBIT_LINK } }
    } else {
        item { Text("Linked to ${l.server}", color = TextMain, fontSize = 17.sp, modifier = Modifier.padding(vertical = 3.dp)) }
        item {
            Text("Signed in as ${l.account} (${l.username}), profile ${l.profile}${if (l.kids) " (kids)" else ""}",
                color = TextMain, fontSize = 17.sp, modifier = Modifier.padding(vertical = 3.dp))
        }
        item { NameThisTv(m) }
        item {
            Text("Change the profile or sign this TV out from Orbit on a phone or computer (Settings ▸ Accounts ▸ Your devices), " +
                "or unlink it here.", color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(vertical = 3.dp))
        }
        item {
            Text("Playlists come from the home server; My list, favourite channels and film resume points sync with your " +
                "profile. ${o.syncStatus}", color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(vertical = 3.dp))
        }
        item { TvButton("Unlink this TV", Modifier.padding(vertical = 6.dp)) { o.unlink() } }
    }
    if (o.message.isNotEmpty()) item { Text(o.message, color = Ok, fontSize = 15.sp, modifier = Modifier.padding(vertical = 4.dp)) }
}

/**
 * Settings ▸ Home server ▸ Name this TV: the name other devices see in the stream-limit prompt ("Stop Kitchen and
 * watch") and in the device lists. Empty until named; the field then offers the TV's model.
 */
@Composable
private fun NameThisTv(m: AppModel) {
    val o = m.orbit
    val l = o.link ?: return
    var name by remember(l.deviceName) { mutableStateOf(l.deviceName.ifEmpty { o.defaultName }) }
    DisposableEffect(Unit) { onDispose { o.clearNameMessage() } }
    Column(Modifier.padding(vertical = 6.dp)) {
        Text("Name this TV" + if (l.deviceName.isEmpty()) " (not named yet)" else ": ${l.deviceName}", color = TextMain, fontSize = 17.sp)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvField("Shown on your other devices, e.g. Kitchen or Bedroom", name, { name = it.take(io.github.kmmarin.tvbro.data.DeviceName.MAX + 8) },
                modifier = Modifier.weight(1f))
            TvButton("Save name") { o.saveName(name) }
        }
        if (o.nameMessage.isNotEmpty()) Text(o.nameMessage, color = Ok, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Right after linking: name this TV (or skip; the field offers the TV's model). */
@Composable
private fun OrbitNameStep(m: AppModel, close: () -> Unit) {
    val o = m.orbit
    var name by remember { mutableStateOf(o.link?.deviceName?.ifEmpty { null } ?: o.defaultName) }
    val fieldFr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(250); runCatching { fieldFr.requestFocus() } }
    val finish = { o.nameStep = false; o.clearNameMessage(); close() }
    BackHandler { finish() }
    Column(Modifier.fillMaxSize().background(Bg).padding(40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Linked. Name this TV?", color = Accent, fontSize = 28.sp)
        Text("Give it the name of its room, like Kitchen or Bedroom. When every stream is in use, other devices offer " +
            "\"Stop Kitchen and watch\" by this name. You can change it later in Settings ▸ Home server.", color = TextMain, fontSize = 17.sp)
        TvField("Name (${io.github.kmmarin.tvbro.data.DeviceName.HELP})", name, { name = it.take(io.github.kmmarin.tvbro.data.DeviceName.MAX + 8) },
            requester = fieldFr, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton("Save") { o.saveName(name) { finish() } }
            TvButton("Skip") { finish() }
        }
        if (o.nameMessage.isNotEmpty()) Text(o.nameMessage, color = Accent, fontSize = 16.sp)
    }
}

/** Link this TV: enter the home server's address once, then approve the code on a phone or computer. */
@Composable
fun OrbitLinkScreen(m: AppModel) {
    val o = m.orbit
    // where to go when this screen closes, decided at that moment (the playlist from the home server may arrive just
    // before or just after the pairing finishes): opened on first run -> the channels once there is a playlist, else
    // Add source; opened from Settings -> Settings
    val firstRun = remember { m.sources.isEmpty() }
    val back = { if (!firstRun) Screen.SETTINGS else if (m.sources.isEmpty()) Screen.ADD_SOURCE else Screen.LIST }
    // linked: the name step first (it closes the screen when saved or skipped)
    LaunchedEffect(o.justLinked) { if (o.justLinked) { o.justLinked = false; if (!o.nameStep) m.screen = back() } }
    if (o.nameStep && o.link != null) { OrbitNameStep(m) { m.screen = back() }; return }
    BackHandler { o.cancelPairing(); m.screen = back() }
    val p = o.pairing
    if (p == null) {
        var server by remember { mutableStateOf(o.lastServer.ifEmpty { "http://" }) }
        val fieldFr = remember { FocusRequester() }
        val getFr = remember { FocusRequester() }
        val firstFoundFr = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(250); runCatching { fieldFr.requestFocus() } }
        // "Find my server": a TCP scan of the TV's own /24 network for /v1/hello on port 8750 (orbit/ServerFinder.kt)
        var finding by remember { mutableStateOf(false) }
        var found by remember { mutableStateOf<ServerFinder.Result?>(null) }
        val scope = rememberCoroutineScope()
        Column(Modifier.fillMaxSize().background(Bg).padding(40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Link this TV to Orbit", color = Accent, fontSize = 28.sp)
            Text("Enter the address of your Orbit home server once (ask whoever set it up), or let the TV find it on your " +
                "network. The TV then shows a code to approve on your phone or computer; no password is typed on the TV.",
                color = TextMain, fontSize = 17.sp)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Down from the field always reaches "Get a code" (not whichever button is nearest below)
                TvField("Home server address, e.g. 192.0.2.10 (port 8750 is added when none is typed)", server, { server = it }, requester = fieldFr,
                    modifier = Modifier.weight(1f).focusProperties { down = if (found?.servers.isNullOrEmpty()) getFr else firstFoundFr })
                TvButton(if (finding) "Searching…" else "Find my server", Modifier.focusProperties { down = if (found?.servers.isNullOrEmpty()) getFr else firstFoundFr }) {
                    if (finding) return@TvButton
                    finding = true; found = null
                    scope.launch {
                        val r = try { ServerFinder.scan() } finally { finding = false }
                        found = r
                        if (r.servers.isNotEmpty()) { delay(100); runCatching { firstFoundFr.requestFocus() } }
                    }
                }
            }
            if (finding) Text("Looking for Orbit servers on this TV's network (a few seconds)…", color = TextDim, fontSize = 15.sp)
            found?.let { r ->
                when {
                    r.network == null -> Text("This TV has no home-network address to search from. Type the server's address instead.", color = Accent, fontSize = 15.sp)
                    r.servers.isEmpty() -> Text("No Orbit server answered on ${r.network} (port ${OrbitApi.DEFAULT_PORT}). Type its address instead, " +
                        "or check that the server is running.", color = Accent, fontSize = 15.sp)
                    else -> {
                        Text("Found on ${r.network}: choose one", color = TextDim, fontSize = 15.sp)
                        r.servers.forEachIndexed { i, s ->
                            val label = "${s.host}:${io.github.kmmarin.tvbro.data.ServerScan.PORT}" +
                                if (s.hostOk) "" else "  (add ${s.host}:${io.github.kmmarin.tvbro.data.ServerScan.PORT} to TVBRO_ALLOWED_HOSTS on the server first)"
                            TvButton(label, if (i == 0) Modifier.focusRequester(firstFoundFr) else Modifier) {
                                server = io.github.kmmarin.tvbro.data.ServerScan.fieldValue(s.host)
                                found = null
                                scope.launch { delay(100); runCatching { getFr.requestFocus() } }
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("Get a code", Modifier.focusRequester(getFr)) { o.startPairing(server) }
                TvButton("Cancel") { o.cancelPairing(); m.screen = back() }
            }
            if (o.message.isNotEmpty()) Text(o.message, color = Accent, fontSize = 16.sp)
        }
    } else {
        val cancelFr = remember { FocusRequester() }
        LaunchedEffect(p.code) { delay(250); runCatching { cancelFr.requestFocus() } }
        Row(Modifier.fillMaxSize().background(Bg).padding(40.dp), verticalAlignment = Alignment.CenterVertically) {
            // 280 dp is about 35 cm on a 55" TV: scans from the sofa
            QrCode(OrbitApi.approveUrl(p.server, p.code), Modifier.size(280.dp))
            Spacer(Modifier.width(40.dp))
            Column {
                Text("Link this TV", color = Accent, fontSize = 28.sp)
                Text("1. On your phone or computer, open Orbit and sign in.", color = TextMain, fontSize = 18.sp, modifier = Modifier.padding(top = 16.dp))
                Text("2. Scan the code, or go to Settings ▸ Accounts ▸ Link a TV and enter:", color = TextMain, fontSize = 18.sp)
                Text(p.code, color = TextMain, fontSize = 56.sp, modifier = Modifier.padding(vertical = 10.dp))
                Text("3. Choose the profile for this TV.", color = TextMain, fontSize = 18.sp)
                Text("Waiting for approval… The code works once and expires at ${hhmm(p.expires)}.", color = TextDim, fontSize = 15.sp,
                    modifier = Modifier.padding(top = 12.dp))
                if (o.message.isNotEmpty()) Text(o.message, color = Accent, fontSize = 15.sp)
                TvButton("Cancel", Modifier.padding(top = 24.dp).focusRequester(cancelFr)) { o.cancelPairing(); m.screen = back() }
            }
        }
    }
}

/** Settings ▸ About: playback statistics of recent streams (Phase 2: measure buffering before tuning), with export. */
fun LazyListScope.playbackStatsSection() {
    item {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        var tick by remember { mutableIntStateOf(0) }
        var saved by remember { mutableStateOf("") }
        val sessions = remember(tick) { io.github.kmmarin.tvbro.orbit.PlayStats.sessions().takeLast(8).reversed() }
        Column(Modifier.padding(vertical = 6.dp)) {
            Text("Playback stats (start time, stalls, bitrate, reconnects)", color = TextDim, fontSize = 15.sp, modifier = Modifier.padding(bottom = 4.dp))
            if (sessions.isEmpty()) Text("Nothing yet: watch a channel or a film for a while.", color = TextDim, fontSize = 13.sp)
            for (s in sessions) {
                Text("${hhmm(s.startedAt / 1000)}  ${s.label.ifEmpty { s.kind }}  (${s.durationMs / 60_000} min)", color = TextMain, fontSize = 13.sp)
                Text(s.line(), color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp, bottom = 2.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                TvButton("Refresh") { tick++ }
                TvButton("Export") {
                    saved = runCatching { io.github.kmmarin.tvbro.orbit.PlayStats.export(ctx).name }.getOrElse { "export failed" }
                }
            }
            if (saved.isNotEmpty()) Text("Saved $saved in the app's diag folder (names and numbers only, no addresses or logins)",
                color = Ok, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/**
 * Every stream of the provider is in use (TVs, browsers and recordings share the count): lists each one with its own
 * "Stop <name> and watch" (Up / Down moves between them, OK chooses), recordings without a button, then Cancel.
 * Back or Cancel = keep everything as it is.
 */
@Composable
fun OrbitStreamPrompt(m: AppModel) {
    val a = m.orbit.streamAsk ?: return
    BackHandler { m.orbit.answerStreamAsk(null) }
    val firstFr = remember { FocusRequester() }
    LaunchedEffect(a) { delay(150); runCatching { firstFr.requestFocus() } }
    val stoppable = a.streams.filter { it.stoppable }
    // a server from before stream ids: it can only stop the oldest viewer
    val oldestOnly = stoppable.isEmpty() && a.streams.any { !it.recording }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(760.dp).background(Color(0xFF141A22)).padding(28.dp).focusTrap(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (a.max == 1) "The stream is in use" else "All ${a.max} streams are in use", color = Accent, fontSize = 26.sp)
            Text("Your provider allows ${a.max} at a time. Choose which one to stop:", color = TextMain, fontSize = 17.sp)
            var first = true
            for (r in a.streams) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, color = TextMain, fontSize = 18.sp)
                        Text(r.what.ifEmpty { "Watching" } + if (r.recording) "  (recording, keeps going)" else "",
                            color = TextDim, fontSize = 14.sp)
                    }
                    if (r.stoppable) {
                        val mod = if (first) Modifier.focusRequester(firstFr) else Modifier
                        first = false
                        TvButton(r.button, mod) { m.orbit.answerStreamAsk(r.id) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 14.dp)) {
                if (oldestOnly) TvButton("Stop the oldest and watch here", Modifier.focusRequester(firstFr)) { m.orbit.answerStreamAsk("") }
                TvButton("Cancel", if (stoppable.isEmpty() && !oldestOnly) Modifier.focusRequester(firstFr) else Modifier) { m.orbit.answerStreamAsk(null) }
            }
        }
    }
}
