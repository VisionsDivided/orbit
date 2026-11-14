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
        item { TvButton("Unlink this TV", Modifier.padding(vertical = 6.dp)) { o.unlink() } }
    }
    if (o.message.isNotEmpty()) item { Text(o.message, color = Ok, fontSize = 15.sp, modifier = Modifier.padding(vertical = 4.dp)) }
}

/** Link this TV: enter the home server's address once, then approve the code on a phone or computer. */
@Composable
fun OrbitLinkScreen(m: AppModel) {
    val o = m.orbit
    // back where it was opened from: Settings, or the first-run Add source screen when there is no playlist yet
    val back = if (m.sources.isEmpty()) Screen.ADD_SOURCE else Screen.SETTINGS
    BackHandler { o.cancelPairing(); m.screen = back }
    LaunchedEffect(o.justLinked) { if (o.justLinked) { o.justLinked = false; m.screen = back } }
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
                TvButton("Cancel") { o.cancelPairing(); m.screen = back }
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
                TvButton("Cancel", Modifier.padding(top = 24.dp).focusRequester(cancelFr)) { o.cancelPairing(); m.screen = back }
            }
        }
    }
}
