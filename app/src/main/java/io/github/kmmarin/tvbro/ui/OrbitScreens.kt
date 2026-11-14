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
import kotlinx.coroutines.delay

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

/** Link this TV: enter the home server's address once, then approve the code on a phone or computer. */
@Composable
fun OrbitLinkScreen(m: AppModel) {
    val o = m.orbit
    // where to go when this screen closes, decided at that moment (the playlist from the home server may arrive just
    // before or just after the pairing finishes): opened on first run -> the channels once there is a playlist, else
    // Add source; opened from Settings -> Settings
    val firstRun = remember { m.sources.isEmpty() }
    val back = { if (!firstRun) Screen.SETTINGS else if (m.sources.isEmpty()) Screen.ADD_SOURCE else Screen.LIST }
    BackHandler { o.cancelPairing(); m.screen = back() }
    LaunchedEffect(o.justLinked) { if (o.justLinked) { o.justLinked = false; m.screen = back() } }
    val p = o.pairing
    if (p == null) {
        var server by remember { mutableStateOf(o.lastServer.ifEmpty { "http://" }) }
        val fieldFr = remember { FocusRequester() }
        val getFr = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(250); runCatching { fieldFr.requestFocus() } }
        Column(Modifier.fillMaxSize().background(Bg).padding(40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Link this TV to Orbit", color = Accent, fontSize = 28.sp)
            Text("Enter the address of your Orbit home server once (ask whoever set it up). The TV then shows a code to approve " +
                "on your phone or computer; no password is typed on the TV.", color = TextMain, fontSize = 17.sp)
            // Down from the field always reaches "Get a code" (not whichever button is nearest below)
            TvField("Home server address (e.g. http://192.0.2.10:8750)", server, { server = it }, requester = fieldFr,
                modifier = Modifier.focusProperties { down = getFr })
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
 * Every stream of the provider is in use (TVs, browsers and recordings share the count): asks whether to stop the
 * oldest viewer and watch here. OK on the first button = yes; Back or Cancel = no.
 */
@Composable
fun OrbitStreamPrompt(m: AppModel) {
    val a = m.orbit.streamAsk ?: return
    BackHandler { m.orbit.answerStreamAsk(false) }
    val yesFr = remember { FocusRequester() }
    LaunchedEffect(a) { delay(150); runCatching { yesFr.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(720.dp).background(Color(0xFF141A22)).padding(28.dp).focusTrap(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (a.max == 1) "The stream is in use" else "All ${a.max} streams are in use", color = Accent, fontSize = 26.sp)
            Text("Your provider allows ${a.max} at a time. Watching now:", color = TextMain, fontSize = 17.sp)
            for (u in a.inUse) Text("•  $u", color = TextMain, fontSize = 17.sp, modifier = Modifier.padding(start = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 14.dp)) {
                TvButton(if (a.oldest != null) "Stop ${a.oldest.take(40)} and watch here" else "Watch here", Modifier.focusRequester(yesFr)) {
                    m.orbit.answerStreamAsk(true)
                }
                TvButton("Cancel") { m.orbit.answerStreamAsk(false) }
            }
        }
    }
}
