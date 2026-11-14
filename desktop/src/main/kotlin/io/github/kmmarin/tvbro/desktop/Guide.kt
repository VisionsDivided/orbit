// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.kmmarin.tvbro.data.*
import io.github.kmmarin.tvbro.data.TvNav.Zone

/** The guide in direction 5 (DESIGN-SPEC-5 "Live TV guide"; the spec's 1920 px sizes at about 0.66). */
private val LABEL_W = 250.dp
private val ROW_H = 60.dp
/** [GuideCtl.pending] start value for the channel name. */
private const val LABEL = Long.MIN_VALUE
/** [GuideCtl.pending] start value for the "No guide data" cell. */
private const val NO_DATA = Long.MIN_VALUE + 1

/** Guide colours (§1 and the mockup's EPG cells). */
private object G5 {
    val cell = Color(0x12FFFFFF)          // rgba(255,255,255,.07)
    val cellNow = Color(0x24FFFFFF)       // rgba(255,255,255,.14)
    val star = Color(0xFFFFD166)
    val rec = Color(0xFFFF6B6B)
}

/** A channel's logo, or its initials on a coloured square when it has none (the mockup's channel tiles). */
@Composable
fun ChannelLogo(c: Channel, size: Dp) {
    if (!c.logo.isNullOrBlank()) NetImage(c.logo, Modifier.size(size).clip(RoundedCornerShape(size * .22f)))
    else {
        val h = (c.name.hashCode() and 0xFFFF) % 360
        Box(Modifier.size(size).background(Brush.linearGradient(listOf(Color.hsl(h.toFloat(), .75f, .55f), Color.hsl(((h + 40) % 360).toFloat(), .7f, .32f))), RoundedCornerShape(size * .22f)),
            contentAlignment = Alignment.Center) {
            Text2(c.name.split(' ', '-').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase().ifEmpty { "?" }, (size.value * .36f).toInt(), Color.White, FontWeight.Black)
        }
    }
}

/** The accent progress line at the bottom of a programme on now. */
@Composable
fun CellProgress(f: Float, modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().height(3.dp).background(Color(0x38FFFFFF), CircleShape)) {
    Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).fillMaxHeight().background(O5.accent, CircleShape))
}

/**
 * A guide cell (§7: EPG cells don't scale; the focused one turns white with dark text). [content] gets whether the
 * cell has the focus.
 */
@Composable
fun EpgCellBox(modifier: Modifier, now: Boolean, past: Boolean = false, requester: FocusRequester? = null, onFocus: () -> Unit = {}, onMenu: (() -> Unit)? = null,
               onClick: () -> Unit, content: @Composable ColumnScope.(Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.padding(2.dp).onFocusChanged { focused = it.hasFocus }.tvItem(onFocus = onFocus, onMenu = onMenu, requester = requester, onClick = onClick)
        .background(if (focused) Color.White else if (now) G5.cellNow else G5.cell, RoundedCornerShape(6.dp))
        .then(if (past && !focused) Modifier.background(Color(0x5905060C), RoundedCornerShape(6.dp)) else Modifier)
        .padding(horizontal = 10.dp, vertical = 4.dp), verticalArrangement = Arrangement.Center) { content(focused) }
}

@Composable
fun Empty(app: AppState) = Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
    Text2(if (app.busy) "Loading…" else if (app.sources.isEmpty()) "No sources yet" else "Nothing here", 26, Color.White, FontWeight.ExtraBold)
    if (app.sources.isEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text2(if (app.orbit.linked) "The home server has no playlist yet." else "Sign in to Orbit to get the household's playlists, or add a playlist of your own.", 15, O5.muted)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!app.orbit.linked) OButton("Sign in to Orbit", primary = true) { app.openSettings(SetCat.SOURCES) }
            OButton("Add a playlist") { app.openSettings(SetCat.SOURCES) }
        }
    }
}

private fun nowSec() = System.currentTimeMillis() / 1000

/**
 * Focus moves inside the grid, decided by the shared [GuideWindow] rules (never into the past, Left at the window start
 * goes to the channel name). The time window lives in [AppState] so it survives leaving the screen.
 */
private class GuideCtl(val app: AppState) {
    /** Channel key and programme start (or [LABEL] / [NO_DATA]) that should take the focus once composed. */
    var pending by mutableStateOf<Pair<String, Long>?>(null)
    fun setWin(w: Long) {
        val now = nowSec()
        val n = GuideWindow.clampWin(w, now)
        app.winStart = n; app.following = n == GuideWindow.initialStart(now)
    }
    fun left(ch: Channel, p: Programme) {
        val m = GuideWindow.leftOf(app.epg.programmes(ch), p, app.winStart, nowSec())
        setWin(m.win); pending = ch.key to (m.prog?.start ?: LABEL)
    }
    fun right(ch: Channel, p: Programme) {
        val m = GuideWindow.rightOf(app.epg.programmes(ch), p, app.winStart, nowSec())
        setWin(m.win); pending = ch.key to (m.prog?.start ?: p.start)
    }
    /** Right from a channel name: the programme on now, the window back at now. */
    fun fromLabel(ch: Channel) {
        val m = GuideWindow.rightOfLabel(app.epg.programmes(ch), nowSec())
        setWin(m.win); pending = ch.key to (m.prog?.start ?: NO_DATA)
    }
}

/** Colour keys of the guide (§9): red Favourite, green Record, yellow Format TS / HLS (this channel), blue Channel options. */
private fun guideKeys(app: AppState, ch: Channel, p: Programme?) = ColorKeys(
    red = ColorKey(if (app.inList(ch.key)) "Unfavourite" else "Favourite") { app.toggleList(ch.key) },
    green = ColorKey(if (app.recorder.active(ch) != null && (p == null || ProgTime.classify(ch, p.start, p.stop, nowSec()) == ProgState.NOW)) "Stop recording" else "Record") { app.recordKey(ch, p) },
    yellow = ColorKey("Format: ${app.formatLabel(ch)}") { app.cycleFormat(ch) },
    blue = ColorKey("Channel options") { app.channelMenu(ch) },
    hints = listOf("Enter" to "Watch"))

@Composable
fun TvScreen(app: AppState) {
    if (app.channels.isEmpty()) return Empty(app)
    val now = app.now
    val list = app.filtered
    val g = remember(app) { GuideCtl(app) }
    val scope = rememberCoroutineScope()
    // Follow the clock; a window time has left behind snaps to now, and an ended focused programme hands the
    // focus to the one on now (the focused block never disappears under the focus).
    LaunchedEffect(now) {
        val n = GuideWindow.onTick(app.winStart, app.following, now)
        if (n != app.winStart) g.setWin(n)
        val ch = app.focusCh; val fp = app.focusProg
        if (app.tvZone == Zone.GRID && ch != null && fp != null && fp.stop <= app.winStart)
            g.pending = ch.key to (GuideWindow.afterEnd(app.epg.programmes(ch), fp, now)?.start ?: LABEL)
    }
    val winStart = app.winStart
    // nothing focused yet (first start): the channel watched last, in the preview and focused
    val last = remember(list) { list.firstOrNull { it.key == app.settings.lastChannel } }
    val focus = app.focusCh?.let { f -> list.firstOrNull { it.key == f.key } ?: f } ?: app.playing?.live ?: last ?: list.firstOrNull()

    // The preview plays the current channel and never follows focus. Nothing played yet: the last channel starts
    // (a locked one waits for Enter and the PIN).
    LaunchedEffect(Unit) {
        if (app.playing == null && !app.full && app.settings.previewOn)
            (app.shown.firstOrNull { it.key == app.settings.lastChannel } ?: focus)?.let { app.preview(it) }
    }

    val keys = list.map { it.key }
    val targetKey = keys.getOrNull(TvNav.entry(keys, focus?.key, app.playing?.live?.key))
    val labelFr = remember { FocusRequester() }
    val groupFr = remember { FocusRequester() }
    val groupState = rememberLazyListState()
    val listState = rememberLazyListState(app.tvScroll[app.group]?.first ?: 0, app.tvScroll[app.group]?.second ?: 0)
    DisposableEffect(app.group) { val gr = app.group; onDispose { app.tvScroll[gr] = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset } }

    /** Left from a channel row (or Down from the top bar): the group tabs, on the selected group. */
    fun toGroups() { scope.launch {
        val i = app.groups.indexOf(app.group)
        if (i >= 0 && groupState.layoutInfo.visibleItemsInfo.none { it.index == i }) groupState.scrollToItem((i - 1).coerceAtLeast(0))
        if (!groupFr.settle()) app.railFr.settle()
    } }
    // Down from the top bar: back to the zone (and item) the guide had focused last.
    DisposableEffect(labelFr) {
        val ret = {
            when (TvNav.right(Zone.RAIL, app.tvZone)) {
                Zone.GROUPS -> toGroups()
                Zone.GRID -> { val c = app.focusCh; val p = app.focusProg; if (c != null && targetKey == c.key) g.pending = c.key to (p?.start ?: NO_DATA) else scope.launch { labelFr.settle() } }
                else -> scope.launch { labelFr.settle() }
            }
            true
        }
        app.railReturn = ret
        onDispose { if (app.railReturn === ret) app.railReturn = null; app.previewRect = null }
    }
    // Entering (from full screen, the top bar, another section): same group and scroll, the focused or playing
    // channel visible and focused. [AppState.focusTick] re-runs it when full screen shrinks back into the preview.
    LaunchedEffect(app.focusTick) {
        val idx = keys.indexOf(targetKey)
        if (idx >= 0) {
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == idx && it.offset >= 0 }) listState.scrollToItem((idx - 2).coerceAtLeast(0))
            // back on the screen: the zone it had (leaving full screen sets the channel name)
            val c = app.focusCh; val p = app.focusProg
            when {
                app.tvZone == Zone.GROUPS -> { toGroups(); return@LaunchedEffect }
                app.tvZone == Zone.GRID && c != null && c.key == targetKey && p != null -> { g.pending = c.key to p.start; return@LaunchedEffect }
            }
            if (labelFr.settle()) return@LaunchedEffect
        }
        groupFr.settle()
    }

    Column(Modifier.fillMaxSize()) {
        Header(app, focus, now)
        // the channel groups as tabs (§ guide): Enter or a click shows the group; Down goes to its channels
        LazyRow(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp), state = groupState, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(app.groups) { i, gr ->
                val sel = gr == app.group
                GroupTab(app.groupLabel(gr), sel, if (sel) groupFr else null,
                    onKey = { k ->
                        when (k) {
                            Key.DirectionDown -> { if (runCatching { labelFr.requestFocus() }.isFailure) false else true }
                            Key.DirectionLeft -> if (i == 0) { scope.launch { app.railFr.settle() }; true } else false
                            Key.DirectionRight -> i == app.groups.size - 1      // the last tab: nothing further
                            else -> false
                        }
                    },
                    onFocus = {
                        app.tvZone = Zone.GROUPS
                        app.colorKeys = if (gr == ChannelView.ALL || gr == ChannelView.FAV) ColorKeys(hints = listOf("Enter" to "Show")) else ColorKeys(
                            red = ColorKey(if (app.inList("g|$gr")) "Unpin group" else "Pin group") { app.toggleList("g|$gr") },
                            blue = ColorKey("Group options") { app.groupMenu(gr) }, hints = listOf("Enter" to "Show"))
                    },
                    onMenu = { app.groupMenu(gr) }) { app.selectGroup(gr) }
            }
        }
        Ruler(winStart, now)
        if (list.isEmpty()) Text2(if (app.busy) "Loading…" else "No channels in this group.", 14, O5.muted, modifier = Modifier.padding(16.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(list, key = { it.key }) { ch -> GuideRow(app, g, ch, now, winStart, if (ch.key == targetKey) labelFr else null, ::toGroups) }
            }
            // the "now" line (§1 live red), on the same scale as the cells
            Box(Modifier.padding(start = LABEL_W).fillMaxSize()) {
                BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
                    val dpm = maxWidth.value / GuideWindow.WINDOW_MIN
                    if (now in winStart until winStart + GuideWindow.WINDOW_SEC) Box(Modifier.offset(GuideWindow.tickX(now, winStart, dpm).dp - 1.dp, 0.dp).width(2.dp).fillMaxHeight().background(O5.live))
                }
            }
        }
    }
}

/** A group tab: bold, white with a 3 px accent underline when chosen, muted otherwise; a white ring on focus. */
@Composable
private fun GroupTab(label: String, sel: Boolean, requester: FocusRequester?, onKey: (Key) -> Boolean, onFocus: () -> Unit, onMenu: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.onFocusChanged { focused = it.hasFocus }
        .onKeyEvent { e -> e.type == KeyEventType.KeyDown && onKey(e.key) }
        .tvItem(onFocus = onFocus, onMenu = onMenu, requester = requester, onClick = onClick).padding(10.dp, 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text2(label, 16, if (sel || focused) Color.White else Color(0x80FFFFFF), FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Box(Modifier.width(if (sel) 30.dp else 0.dp).height(3.dp).background(if (sel) O5.accent else Color.Transparent, RoundedCornerShape(2.dp)))
    }
}

/** The info block (focused programme) on the left and the live preview on the right. */
@Composable
private fun Header(app: AppState, focus: Channel?, now: Long) {
    Row(Modifier.fillMaxWidth().height(196.dp).padding(top = 4.dp)) {
        // the info block describes the focused item; the preview keeps the current channel
        val prog = app.focusProg?.takeIf { focus != null } ?: focus?.let { app.epg.nowNext(it, now).first }
        Column(Modifier.weight(1f).padding(end = 24.dp)) {
            if (focus != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChannelLogo(focus, 36.dp)
                Text2((if (focus.number > 0) "${focus.number} · " else "") + focus.name, 16, Color.White, FontWeight.Bold)
                val st = prog?.let { ProgTime.classify(focus, it.start, it.stop, now) }
                if (st == ProgState.NOW) LivePill()
                if (app.playing?.live?.key == focus.key && app.playing?.archive == null) Text2("▶ Playing", 13, Theme.ok, FontWeight.Bold)
                if (app.recorder.active(focus) != null) Text2("● REC", 13, G5.rec, FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text2(prog?.title ?: focus?.let { "No guide data" } ?: "No channel", 40, Color.White, FontWeight.ExtraBold)
            if (prog != null && focus != null) {
                val st = ProgTime.classify(focus, prog.start, prog.stop, now)
                val next = app.epg.nowNext(focus, now).second?.takeIf { st == ProgState.NOW }
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text2(TimeFmt.time(prog.start) + " – " + TimeFmt.time(prog.stop), 15, Color(0xFFE9ECFF), FontWeight.SemiBold)
                    when (st) {
                        ProgState.NOW -> Text2("${ProgTime.minutesLeft(prog.stop, now)} min left", 15, Color(0xFFE9ECFF), FontWeight.SemiBold)
                        ProgState.PAST_CATCHUP -> Text2("⟲ catch-up", 15, O5.accent, FontWeight.SemiBold)
                        ProgState.PAST -> Text2("finished", 15, O5.muted)
                        ProgState.FUTURE -> Text2(TimeFmt.dayTime(prog.start * 1000, "EEE").substringBefore(' '), 15, Color(0xFFE9ECFF), FontWeight.SemiBold)
                    }
                    EpisodeNum.line(prog.ep, prog.sub)?.let { Text2(it, 15, Color(0xFFE9ECFF), FontWeight.SemiBold) }
                    next?.let { Text2("Next: ${it.title}", 15, O5.muted) }
                }
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (st == ProgState.NOW) Box(Modifier.width(320.dp)) { CellProgress(ProgTime.progress(prog.start, prog.stop, now)) }
                    Text2("Stream: " + app.formatLabel(focus), 13, O5.muted)
                }
                prog.desc?.let { Text2(it, 13, O5.muted, modifier = Modifier.padding(top = 8.dp), maxLines = 2) }
            }
        }
        // the shared player is drawn over this spot (VideoHost)
        Box(Modifier.size(333.dp, 187.dp).background(Color.Black, RoundedCornerShape(10.dp))
            .onGloballyPositioned { app.previewRect = it.boundsInRoot() }) {
            if (app.playing == null) Text2(if (app.settings.previewOn) "…" else "Preview off", 13, O5.muted, modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun Ruler(winStart: Long, now: Long) {
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        Text2(java.text.SimpleDateFormat("EEE d MMM").format(java.util.Date(now * 1000)), 13, O5.muted, FontWeight.SemiBold, Modifier.width(LABEL_W).padding(start = 8.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            val dpm = maxWidth.value / GuideWindow.WINDOW_MIN
            var tk = winStart
            while (tk < winStart + GuideWindow.WINDOW_SEC) {
                Row(Modifier.offset(GuideWindow.tickX(tk, winStart, dpm).dp, 0.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(2.dp).height(16.dp).background(O5.line))
                    Text2("  " + TimeFmt.time(tk), 14, O5.muted)
                }
                tk += GuideWindow.STEP_SEC
            }
            // the time now on a red badge above the now line
            if (now in winStart until winStart + GuideWindow.WINDOW_SEC) Box(Modifier.offset(GuideWindow.tickX(now, winStart, dpm).dp - 22.dp, 0.dp).align(Alignment.CenterStart)
                .background(O5.live, RoundedCornerShape(4.dp)).padding(5.dp, 2.dp)) { Text2(TimeFmt.time(now), 11, Color.White, FontWeight.Bold) }
        }
    }
}

@Composable
private fun GuideRow(app: AppState, g: GuideCtl, ch: Channel, now: Long, winStart: Long, entryFr: FocusRequester?, toGroups: () -> Unit) {
    val playing = app.playing?.live?.key == ch.key
    val vis = remember(ch.key, winStart, app.epg) { GuideWindow.visible(app.epg.programmes(ch), winStart) }
    val ownLabel = remember { FocusRequester() }
    val labelFr = entryFr ?: ownLabel
    val noDataFr = remember { FocusRequester() }
    val blockFr = remember(ch.key) { HashMap<Long, FocusRequester>() }
    // focus asked for by a move (Left / Right / the clock), once the target is composed
    LaunchedEffect(g.pending, vis) {
        val mine = g.pending ?: return@LaunchedEffect
        val (k, start) = mine
        if (k != ch.key) return@LaunchedEffect
        // a programme no longer in the window falls back to the channel name: the focus never gets lost
        val fr = when {
            start == LABEL -> labelFr
            vis.isEmpty() -> noDataFr
            else -> blockFr[start]?.takeIf { vis.any { it.start == start } } ?: labelFr
        }
        // asked again until the item reports the focus (a block re-laid out by the window move can drop a first
        // request); any focus in this row clears [GuideCtl.pending]
        repeat(20) {
            if (g.pending !== mine) return@LaunchedEffect
            runCatching { fr.requestFocus() }
            delay(50)
        }
        if (g.pending === mine) g.pending = null
    }
    val fmt = app.channelFormat(ch.key)
    val rec = app.recorder.active(ch) != null
    Row(Modifier.fillMaxWidth().height(ROW_H)) {
        var lf by remember { mutableStateOf(false) }
        Row(Modifier.width(LABEL_W).fillMaxHeight().padding(2.dp).onFocusChanged { lf = it.hasFocus }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) false
                else if (e.key == Key.DirectionRight) { g.fromLabel(ch); true }
                else if (e.key == Key.DirectionLeft) { toGroups(); true }
                else false
            }
            .tvItem(onFocus = { g.pending = null; app.focusCh = ch; app.focusProg = null; app.tvZone = Zone.LABEL; app.favAction = { app.toggleList(ch.key) }; app.colorKeys = guideKeys(app, ch, null) },
                onMenu = { app.channelMenu(ch) }, requester = labelFr) { app.enterChannel(ch) }
            .background(if (lf) Color.White else Color.Transparent, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val tx = if (lf) O5.bg else Color.White
            Text2(if (ch.number > 0) ch.number.toString() else "", 13, if (lf) Color(0x9905060C) else O5.muted, FontWeight.SemiBold, Modifier.width(30.dp))
            ChannelLogo(ch, 32.dp)
            Text2((if (playing) "▶ " else "") + ch.name + (if (ch.key in app.settings.prefs.lockedCh) "  🔒" else ""), 14, if (playing && !lf) O5.accent else tx, FontWeight.SemiBold, Modifier.weight(1f))
            if (app.inList(ch.key)) Text2("★", 13, if (lf) Color(0xFFB8860B) else G5.star)
            if (fmt != null) FormatTag(fmt)
            if (rec) Text2("●", 12, G5.rec, FontWeight.Bold)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            val dpm = maxWidth.value / GuideWindow.WINDOW_MIN
            if (vis.isEmpty()) Box(Modifier.fillMaxSize()
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) false
                    else if (e.key == Key.DirectionLeft) { g.pending = ch.key to LABEL; true }
                    else e.key == Key.DirectionRight // nothing to the right; no jump to another row
                }) {
                EpgCellBox(Modifier.fillMaxSize(), now = false, requester = noDataFr,
                    onFocus = { g.pending = null; app.focusCh = ch; app.focusProg = null; app.tvZone = Zone.GRID; app.colorKeys = guideKeys(app, ch, null) },
                    onMenu = { app.channelMenu(ch) }, onClick = { app.enterChannel(ch) }) { f -> Text2("No guide data", 13, if (f) O5.bg else O5.muted) }
            }
            for (p in vis) {
                val bw = GuideWindow.block(p.start, p.stop, winStart, dpm) ?: continue
                // keyed by start time: the focused block keeps its focus when the window moves
                key(p.start) { GuideBlock(app, g, ch, p, bw.first, bw.second, now, blockFr.getOrPut(p.start) { FocusRequester() }) }
            }
        }
    }
}

@Composable
private fun GuideBlock(app: AppState, g: GuideCtl, ch: Channel, p: Programme, x: Float, w: Float, now: Long, fr: FocusRequester) {
    val st = ProgTime.classify(ch, p.start, p.stop, now)
    val rec = app.recorder.scheduledFor(ch, p) != null || (st == ProgState.NOW && app.recorder.active(ch) != null)
    Box(Modifier.offset(x.dp, 0.dp).width(w.dp).fillMaxHeight()
        .onKeyEvent { e ->
            // Left / Right stay in the row (shared rules); Up / Down go to the spatially nearest block (Home)
            if (e.type != KeyEventType.KeyDown) false
            else if (e.key == Key.DirectionLeft) { g.left(ch, p); true }
            else if (e.key == Key.DirectionRight) { g.right(ch, p); true }
            else false
        }) {
        EpgCellBox(Modifier.fillMaxSize(), now = st == ProgState.NOW, past = st == ProgState.PAST, requester = fr,
            onFocus = { g.pending = null; app.focusCh = ch; app.focusProg = p; app.tvZone = Zone.GRID; app.favAction = { app.toggleList(ch.key) }; app.colorKeys = guideKeys(app, ch, p) },
            onMenu = { app.programmeMenu(ch, p) }, onClick = { app.guidePlay(ch, p) }) { f ->
            Text2((if (app.reminderFor(ch, p) != null) "⏰ " else "") + p.title + if (st == ProgState.PAST_CATCHUP) " ⟲" else "", 14,
                if (f) O5.bg else if (st == ProgState.PAST) O5.muted else Color.White, FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (rec) Text2("● REC", 11, G5.rec, FontWeight.Bold)
                Text2(TimeFmt.time(p.start) + " – " + TimeFmt.time(p.stop) + (EpisodeNum.line(p.ep, p.sub)?.let { "  $it" } ?: ""), 11, if (f) Color(0xB305060C) else O5.muted)
            }
            if (st == ProgState.NOW && !f) CellProgress(ProgTime.progress(p.start, p.stop, now), Modifier.padding(top = 3.dp))
        }
    }
}
