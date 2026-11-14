// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.launch

/** Orbit direction 5 tokens (orbit-phase0/mockups/DESIGN-SPEC-5.md §1), for the desktop. */
object O5 {
    val bg = Color(0xFF05060C)
    val text = Color(0xFFF4F6FF)
    val muted = Color(0xA3E4E9FF)                  // rgba(228,233,255,.64)
    val accent = Color(0xFF2FD3FF)
    val violet = Color(0xFF8A5CFF)
    val rating = Color(0xFFFFD166)
    val line = Color(0x12FFFFFF)                   // rgba(255,255,255,.07)
    val live = Color(0xFFFF3B5C)
    val dialog = Color(0xF20A0C16)                 // rgba(10,12,22,.95)
    val dialogRing = Color(0x1FFFFFFF)             // rgba(255,255,255,.12)
    val chip = Color(0x800A0C16)                   // rgba(10,12,22,.5)
    val chipRing = Color(0x24FFFFFF)               // rgba(255,255,255,.14)
    val secondary = Color(0x29FFFFFF)              // rgba(255,255,255,.16)
    val brand = Brush.horizontalGradient(listOf(accent, violet))
}

/** A direction-5 button: primary white with dark text, secondary translucent; the focus ring is 3 px white. */
@Composable
fun OButton(label: String, primary: Boolean = false, requester: FocusRequester? = null, size: Int = 15, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(Modifier.tvItem(requester = requester, onFocus = {}, onClick = onClick).onFocusChanged { focused = it.hasFocus }
        .scale(if (focused) 1.05f else 1f)
        .background(if (primary) Color.White else O5.secondary, RoundedCornerShape(8.dp))
        .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(8.dp))
        .padding(22.dp, 10.dp)) {
        T(label, size, if (primary) O5.bg else O5.text, bold = true)
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Settings ▸ Orbit account
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun OField(label: String, v: String, secret: Boolean = false, requester: FocusRequester? = null, onEnter: () -> Unit = {}, set: (String) -> Unit) =
    OutlinedTextField(v, set, (if (requester != null) Modifier.focusRequester(requester) else Modifier).width(420.dp)
        .onFocusChanged { typing = it.isFocused }
        .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { onEnter(); true } else false },
        label = { T(label, 12) }, singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)

/** Sign in to the Orbit home server, or (signed in) the account, profile, sync state and Sign out. */
@Composable
fun OrbitAccountPage(app: AppState) {
    val o = app.orbit
    val s = o.saved
    if (o.linked) {
        T("Signed in to ${s.server}", 15)
        T("Account ${s.account} (${s.username}) · profile ${s.profile}" + if (s.kids) " (kids)" else "", 14, Theme.dim)
        if (o.syncStatus.isNotEmpty()) T(o.syncStatus, 13, Theme.dim)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Who's watching?") { app.stopPlayback(); o.picking = true }
            Btn("Sync now") { app.io { o.syncState() } }
            Btn("Sign out") {
                app.menu = MenuState("Sign out of Orbit?\nThe playlists, My list, resume points and show progress from the home server are removed from this computer. They stay in your account.",
                    listOf("Sign out" to { o.signOut() }, "Cancel" to {}))
            }
        }
        T("Playlists come from the home server; My list, resume points and show progress sync every minute and shortly after a change. " +
            "Playing counts towards the household's streams like a TV. The sign-in is stored encrypted (${Secrets.method}).", 12, Theme.dim, maxLines = 3)
        return
    }
    var server by remember { mutableStateOf(app.settings.orbitServer) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    val go = { o.signIn(server, user, pass); pass = "" }
    if (o.signedOut) T("The home server ended this computer's session. Sign in again.", 14, Theme.live, maxLines = 2)
    T("Sign in with your Orbit account to get the household's playlists, My list and progress on this computer, like on the TV.", 13, Theme.dim, maxLines = 2)
    OField("Home server (e.g. http://192.0.2.10:8750)", server, requester = first, onEnter = go) { server = it }
    OField("Username", user, onEnter = go) { user = it }
    OField("Password", pass, secret = true, onEnter = go) { pass = it }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Btn(if (o.busy) "Signing in…" else "Sign in") { if (!o.busy) go() }
    }
    if (o.message.isNotEmpty()) T(o.message, 13, if (o.busy) Theme.dim else Theme.live, maxLines = 3)
    T("Signing in replaces the playlists typed into this app with the home server's (so My list and progress match every device). " +
        "Signing out removes them again; device settings and recordings stay.", 12, Theme.dim, maxLines = 3)
}

// ---------------------------------------------------------------------------------------------------------------
// Who's watching? (orbit picker, DESIGN-SPEC-5 §8)
// ---------------------------------------------------------------------------------------------------------------

/** Avatar: a brand-gradient circle with the profile's initial. */
@Composable
fun Avatar(name: String, size: Dp, ring: Boolean = false, modifier: Modifier = Modifier) {
    Box(modifier.size(size).then(if (ring) Modifier.border(4.dp, Color.White, CircleShape) else Modifier).padding(if (ring) 6.dp else 0.dp)
        .clip(CircleShape).background(O5.brand), contentAlignment = Alignment.Center) {
        Text2(name.trim().take(1).uppercase().ifEmpty { "?" }, (size.value * .42f).toInt(), Color.White, FontWeight.ExtraBold)
    }
}

@Composable
fun Text2(s: String, size: Int, color: Color, weight: FontWeight = FontWeight.Normal, modifier: Modifier = Modifier, align: TextAlign? = null, maxLines: Int = 1) =
    androidx.compose.material3.Text(s, modifier, color = color, fontSize = size.sp, fontWeight = weight, maxLines = maxLines, textAlign = align,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, letterSpacing = if (size >= 40) (-0.03 * size).sp else androidx.compose.ui.unit.TextUnit.Unspecified)

/** The orbit mesh behind the picker: violet / cyan / deep blue (#6b45e6 / #2fd3ff / #06061a). */
fun Modifier.orbitMesh(m1: Color = Color(0xFF6B45E6), m2: Color = O5.accent, base: Color = Color(0xFF06061A)) = drawBehind {
    drawRect(base)
    fun blob(cx: Float, cy: Float, rx: Float, c: Color, a: Float, stop: Float) = drawRect(
        Brush.radialGradient(listOf(c.copy(alpha = a), Color.Transparent), Offset(cx, cy), rx * stop))
    blob(size.width, 0f, size.width * .72f, m1, .88f, .7f)
    blob(0f, size.height, size.width * .55f, m2, .22f, .72f)
    blob(0f, 0f, size.width * .6f, m1, .40f, .7f)
    blob(size.width, size.height, size.width * .6f, m2, .16f, .7f)
}

/**
 * "Who's watching?" (§8): the poster wall, the orbit mesh and, by the picker style (Settings ▸ Profiles), the orbit ring
 * with the chosen profile at 6 o'clock, or the classic single row. Left / Right choose, Enter (or a click) watches,
 * Down reaches the one "Manage profiles" button. No colour-key legend here.
 */
@Composable
fun WhoScreen(app: AppState) {
    val o = app.orbit
    val profiles = o.profiles
    if (profiles.isEmpty()) { LaunchedEffect(Unit) { o.picking = false }; return }
    var sel by remember { mutableStateOf(profiles.indexOfFirst { it.id == o.saved.profileId }.coerceAtLeast(0)) }
    val classic = app.settings.pickerStyle == "classic"
    val fr = remember { FocusRequester() }
    val manageFr = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { fr.settle() }
    val manage = {
        o.picking = false
        app.openSettings(SetCat.PROFILES)
        scope.launch { repeat(20) { app.manageProfiles?.let { f -> f(); return@launch }; kotlinx.coroutines.delay(50) } }
        Unit
    }
    val posters = remember(app.movies, app.series) { (app.movies + app.series).mapNotNull { it.poster }.filter { it.startsWith("http") }.take(40) }
    BoxWithConstraints(Modifier.fillMaxSize().orbitMesh()) {
        val w = maxWidth; val h = maxHeight
        val k = (w.value / 1920f).coerceAtMost(h.value / 1080f)           // the spec's 1920×1080 scaled to the window
        // poster wall at 16 %, rotated −6° and scaled 1.15
        if (posters.isNotEmpty()) Box(Modifier.fillMaxSize().alpha(.16f).rotate(-6f).scale(1.15f)) {
            val cols = 10; val pw = w / cols
            Column { for (r in 0 until 5) Row { for (c in 0 until cols) NetImage(posters[(r * cols + c) % posters.size], Modifier.width(pw).height(pw * 1.5f), ContentScale.Crop) } }
        }
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0x8C05060C), Color(0xEB05060C)), radius = w.value * 1.4f * .72f)))
        Column(Modifier.fillMaxWidth().padding(top = (64 * k).dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text2("Who's watching?", (72 * k).toInt().coerceAtLeast(30), O5.text, FontWeight.ExtraBold)
            Spacer(Modifier.height(6.dp))
            Text2("Profile picker style: ${if (classic) "Classic" else "Orbit"} · change it in Settings › Profiles", (24 * k).toInt().coerceAtLeast(13), O5.muted)
        }
        // the profiles take the keys; Down goes to Manage profiles
        Box(Modifier.fillMaxSize().focusRequester(fr).focusable().onKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (e.key) {
                Key.DirectionLeft -> { sel = (sel - 1).mod(profiles.size); true }
                Key.DirectionRight -> { sel = (sel + 1).mod(profiles.size); true }
                Key.DirectionDown -> { runCatching { manageFr.requestFocus() }; true }
                Key.Enter, Key.NumPadEnter, Key.Spacebar -> { o.chooseProfile(profiles[sel].id); true }
                Key.Escape, Key.Backspace -> { if (o.saved.profileId.isNotEmpty()) o.picking = false; true }
                else -> false
            }
        }) {
            val pick = { i: Int -> if (sel == i) o.chooseProfile(profiles[i].id) else sel = i }
            if (classic) ClassicRow(profiles, sel, k, w.value, h.value, pick)
            else OrbitRing(profiles, sel, k, w.value, h.value, pick)
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = (44 * k).dp).onKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && (e.key == Key.DirectionUp || e.key == Key.Escape)) { runCatching { fr.requestFocus() }; true } else false
        }) { OButton("⚙  Manage profiles", requester = manageFr, size = (24 * k).toInt().coerceAtLeast(14), onClick = manage) }
        if (o.busy) Text2("Switching…", 13, O5.accent, modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp))
    }
}

/** A profile under the pointer: a click picks it (a second click, or one on the chosen one, watches). */
private fun Modifier.pickOnClick(onPick: () -> Unit) = pointerInput(Unit) {
    awaitPointerEventScope { while (true) { val ev = awaitPointerEvent(); if (ev.type == PointerEventType.Press) onPick() } }
}

/** The orbit ring (§8): an ellipse round the planet; the chosen profile at the bottom, the others along the ring. */
@Composable
private fun OrbitRing(profiles: List<io.github.kmmarin.tvbro.orbit.OrbitProfile>, sel: Int, k: Float, w: Float, h: Float, pick: (Int) -> Unit) {
    val step = min(0.7f, (2 * PI / profiles.size).toFloat())
    // the orbit ring: centre (960, 546), radii 660 × 250 at 1920×1080
    val cx = w / 2; val cy = h * 546 / 1080
    val rx = 660 * k; val ry = 250 * k
    Box(Modifier.fillMaxSize().drawBehind {
        val d = density
        drawOval(Color(0x408A5CFF), Offset((cx - rx - 8) * d, (cy - ry - 8) * d), Size((rx + 8) * 2 * d, (ry + 8) * 2 * d), style = Stroke(16 * d))
        drawOval(Color(0x42FFFFFF), Offset((cx - rx) * d, (cy - ry) * d), Size(rx * 2 * d, ry * 2 * d), style = Stroke(3 * d))
    })
    Image2(Modifier.size((460 * k).dp).offset((cx - 230 * k).dp, (cy - 230 * k).dp))
    profiles.forEachIndexed { i, p ->
        // the selected profile sits at 6 o'clock, the others alternate right and left of it along the ring
        val n = profiles.size
        val dist = ((i - sel).mod(n)).let { if (it > n / 2) it - n else it }
        val off by animateFloatAsState(dist.toFloat(), tween(400))
        val a = PI / 2 - off * step
        val px = cx + rx * cos(a).toFloat(); val py = cy + ry * sin(a).toFloat()
        val focused = i == sel
        val size = (if (focused) 200 else 150) * k
        val front = sin(a) > -0.2                                   // behind the planet: dimmed
        Column(Modifier.offset((px - size / 2).dp, (py - size / 2).dp).width(size.dp).alpha(if (front) 1f else .45f).pickOnClick { pick(i) },
            horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(p.name, size.dp, ring = focused)
            Spacer(Modifier.height((10 * k).dp))
            Text2(p.name + if (p.kids) " · Kids" else "", ((if (focused) 32 else 26) * k).toInt().coerceAtLeast(12),
                if (focused) Color.White else O5.muted, if (focused) FontWeight.ExtraBold else FontWeight.SemiBold, align = TextAlign.Center)
        }
    }
}

/** The classic picker (§8 "Classic"): one centred row of avatars, the chosen one larger with the white ring. */
@Composable
private fun ClassicRow(profiles: List<io.github.kmmarin.tvbro.orbit.OrbitProfile>, sel: Int, k: Float, w: Float, h: Float, pick: (Int) -> Unit) {
    Row(Modifier.fillMaxSize().padding(bottom = (60 * k).dp), horizontalArrangement = Arrangement.spacedBy((56 * k).dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        profiles.forEachIndexed { i, p ->
            val focused = i == sel
            val sz by androidx.compose.animation.core.animateDpAsState(((if (focused) 230 else 190) * k).dp, tween(250))
            Column(Modifier.width((240 * k).dp).pickOnClick { pick(i) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size((240 * k).dp), contentAlignment = Alignment.Center) { Avatar(p.name, sz, ring = focused) }
                Spacer(Modifier.height((14 * k).dp))
                Text2(p.name + if (p.kids) " · Kids" else "", ((if (focused) 32 else 26) * k).toInt().coerceAtLeast(12),
                    if (focused) Color.White else O5.muted, if (focused) FontWeight.ExtraBold else FontWeight.SemiBold, align = TextAlign.Center)
            }
        }
    }
}

/** Orbit artwork bundled with the app (copies of the branding folder's webp files in desktop/src/main/resources), or null. */
object OrbitArt {
    private val cache = HashMap<String, androidx.compose.ui.graphics.ImageBitmap?>()
    fun get(name: String): androidx.compose.ui.graphics.ImageBitmap? = synchronized(cache) {
        cache.getOrPut(name) {
            runCatching { OrbitArt::class.java.getResourceAsStream("/$name")?.use { org.jetbrains.skia.Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap() } }.getOrNull()
        }
    }
}

/** The planet logo in the middle of the orbit (branding/orbit-planet.webp), else a drawn planet. */
@Composable
private fun Image2(modifier: Modifier) {
    val img = remember { OrbitArt.get("orbit-planet.webp") }
    if (img != null) androidx.compose.foundation.Image(img, null, modifier)
    else Box(modifier.drawBehind {
        val r = size.minDimension * .26f
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(Brush.radialGradient(listOf(Color(0xFF8A5CFF), Color(0xFF2B1A6E)), c - Offset(r * .3f, r * .3f), r * 1.4f), r, c)
        drawOval(Color(0xCC2FD3FF), Offset(c.x - r * 1.7f, c.y - r * .32f), Size(r * 3.4f, r * .64f), style = Stroke(r * .08f))
    })
}


// ---------------------------------------------------------------------------------------------------------------
// Stream limit (DESIGN-SPEC-5 §10): one row and button per stream, Cancel focused by default
// ---------------------------------------------------------------------------------------------------------------

/** Size of the stream prompt for [rows] streams (the spec's 900 px wide dialog at 2/3). */
fun streamAskSize(rows: Int) = androidx.compose.ui.unit.DpSize(600.dp, (150 + rows * 98).coerceAtMost(640).dp)

/**
 * The prompt as its own small window (it has to stay above the native video), at the top right of [owner] (right 96,
 * y 132 in the spec), else centred on the screen.
 */
@Composable
fun StreamAskDialog(app: AppState, owner: java.awt.Window? = null) {
    val q = app.orbit.streamAsk ?: return
    key(q) {
        val size = streamAskSize(q.rows.size)
        val pos = owner?.takeIf { it.isShowing }?.let { w ->
            val d = w.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
            WindowPosition((w.x / d + w.width / d - 64 - size.width.value).dp, (w.y / d + 88).dp)
        } ?: WindowPosition(Alignment.Center)
        val st = rememberDialogState(position = pos, size = size)
        val cancel = { app.orbit.answerStreamAsk(null) }
        DialogWindow(onCloseRequest = cancel, state = st, title = "Orbit", undecorated = true, transparent = true, resizable = false) {
            StreamAskContent(app, q, cancel)
        }
    }
}

/** The prompt's content (also rendered off-screen in the design shots). */
@Composable
fun StreamAskContent(app: AppState, q: StreamAsk, cancel: () -> Unit) {
    val rows = q.rows
    val fr = remember { FocusRequester() }
    LaunchedEffect(q) { fr.settle() }                         // never pre-select a stream
    val fm = androidx.compose.ui.platform.LocalFocusManager.current
    Column(Modifier.fillMaxSize().background(O5.dialog, RoundedCornerShape(14.dp)).border(1.dp, O5.dialogRing, RoundedCornerShape(14.dp)).padding(20.dp, 18.dp)
        .onKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) false else when (e.key) {
                Key.Escape, Key.Backspace -> { cancel(); true }
                Key.DirectionUp -> fm.moveFocus(androidx.compose.ui.focus.FocusDirection.Up)
                Key.DirectionDown -> fm.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
                Key.DirectionLeft -> fm.moveFocus(androidx.compose.ui.focus.FocusDirection.Left)
                Key.DirectionRight -> fm.moveFocus(androidx.compose.ui.focus.FocusDirection.Right)
                else -> false
            }
        }) {
        Text2(if (q.max == 2) "Both streams are in use" else "All ${q.max} streams are in use", 20, O5.text, FontWeight.ExtraBold)
        Spacer(Modifier.height(5.dp))
        Text2("Your provider allows ${q.max} at a time. Choose which one to stop to ${q.verb}" + (q.what.takeIf { it.isNotBlank() }?.let { " $it" } ?: "") + " here.",
            14, O5.muted, maxLines = 2)
        LazyColumn(Modifier.weight(1f, fill = false).padding(top = 4.dp)) {
            itemsIndexed(rows) { _, r ->
                Row(Modifier.padding(top = 8.dp).fillMaxWidth().background(Color(0x0FFFFFFF), RoundedCornerShape(10.dp)).padding(13.dp, 11.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                    Avatar(r.name, 40.dp)
                    Column(Modifier.weight(1f)) {
                        Text2(r.name, 16, O5.text, FontWeight.ExtraBold)
                        if (r.what.isNotEmpty()) Text2(r.what, 13, O5.muted)
                        if (r.since > 0) Text2((if (r.recording) "Recording since " else "Watching since ") + io.github.kmmarin.tvbro.data.TimeFmt.time(r.since), 11, O5.muted)
                    }
                    if (r.stoppable) OButton("■  " + if (q.verb == "watch") r.button else "Stop ${r.name.take(40)} and ${q.verb}", size = 13) { app.orbit.answerStreamAsk(r.id) }
                    else Text2(if (r.recording) "Recording" else "", 13, O5.muted)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            if (rows.none { it.stoppable }) OButton("Stop the oldest and watch", size = 14) { app.orbit.answerStreamAsk("") }
            OButton("Cancel", requester = fr, size = 15, onClick = cancel)
        }
    }
}
