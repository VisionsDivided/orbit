// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.kmmarin.tvbro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.kmmarin.tvbro.*
import io.github.kmmarin.tvbro.data.Channel
import io.github.kmmarin.tvbro.data.Programme
import io.github.kmmarin.tvbro.data.RecRepo
import io.github.kmmarin.tvbro.ui.live.EpgActions
import io.github.kmmarin.tvbro.ui.live.EpgGrid
import io.github.kmmarin.tvbro.ui.live.EpgWindow
import io.github.kmmarin.tvbro.ui.live.GroupTabs
import io.github.kmmarin.tvbro.ui.live.LiveInfo
import io.github.kmmarin.tvbro.ui.live.LiveLogic
import io.github.kmmarin.tvbro.ui.live.LiveStyle
import io.github.kmmarin.tvbro.ui.live.NowFocus
import io.github.kmmarin.tvbro.ui.live.RowMarks
import io.github.kmmarin.tvbro.ui.live.groupTabLabel
import io.github.kmmarin.tvbro.ui.live.liveInfoOf

/**
 * Spot of the live preview: the shared live player (LiveVideo) is drawn over it. It plays the chosen channel
 * (m.current, set with OK), never just the focused one (user 2026-11-11: moving around must not switch channels).
 */
@Composable
fun LivePreview(m: AppModel, ch: Channel?, modifier: Modifier) {
    val exo = rememberLiveExo(m)
    // (the volume is set by LiveVideo for whichever surface shows: sound with the picture)
    LaunchedEffect(ch?.key, m.previewOn, m.streamsFull(), m.liveGen) {
        if (m.screen != Screen.LIST) return@LaunchedEffect // the full guide shows what already plays, it starts nothing
        if (ch == null || !m.previewOn || m.streamsFull()) return@LaunchedEffect // LiveVideo stops the player when hidden
        // already playing this channel (back from full screen, catch-up or an alternate stream included): keep picture
        // and sound exactly as they are, no re-buffer
        if (m.liveKey == ch.key && m.liveUrl != null && exo.playbackState != androidx.media3.common.Player.STATE_IDLE) { exo.playWhenReady = true; return@LaunchedEffect }
        if (!m.orbit.claimStream("live", ch.name, ask = false)) return@LaunchedEffect   // Orbit: every stream in use
        m.liveUrl = ch.url; m.liveKey = ch.key
        liveLoad(exo, ch.url, ch.name, m.channelFormat(ch.key))
    }
    // a preview stream that fails (dropped connection, provider hiccup) tries again, like full screen does
    DisposableEffect(exo) {
        val l = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (m.screen == Screen.LIST && m.liveKey != null) { m.liveUrl = null; m.liveKey = null; m.previewRetry++ }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_READY && m.previewRetry > 0) m.previewRetry = 0
                // a live stream that "ends" lost its connection (the provider closed it): connect again
                if (state == androidx.media3.common.Player.STATE_ENDED && m.screen == Screen.LIST && m.liveKey != null && m.catchup == null) {
                    m.liveUrl = null; m.liveKey = null; m.previewRetry++
                }
            }
        }
        exo.addListener(l)
        onDispose { exo.removeListener(l) }
    }
    LaunchedEffect(m.previewRetry) {
        if (m.previewRetry == 0 || ch == null || m.screen != Screen.LIST || !m.previewOn || m.streamsFull()) return@LaunchedEffect
        delay(minOf(2000L * m.previewRetry, 15_000L))
        if (m.liveKey != null) return@LaunchedEffect // something else started a stream meanwhile
        if (!m.orbit.claimStream("live", ch.name, ask = false)) return@LaunchedEffect   // Orbit: every stream in use
        m.liveUrl = ch.url; m.liveKey = ch.key
        liveLoad(exo, ch.url, ch.name, m.channelFormat(ch.key))
    }
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.shadow(40.dp, shape).clip(shape).background(Color.Black).onGloballyPositioned { m.previewRect = it.boundsInRoot() }, contentAlignment = Alignment.Center) {
        val msg = when {
            !m.previewOn -> "Preview off"
            m.streamsFull() -> "Preview paused: a recording uses the stream (limit ${m.maxStreams})"
            m.screen == Screen.GUIDE && m.liveUrl == null -> ch?.name ?: ""
            else -> null
        }
        if (msg != null) Text(msg, style = LiveStyle.Small, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
    }
}

/** What each channel row shows next to its name. */
fun rowMarks(m: AppModel, ch: Channel): RowMarks = RowMarks(
    fav = ch.key in m.favourites,
    format = m.channelFormat(ch.key)?.let { LiveLogic.formatLabel(it) },
    rec = LiveLogic.recordingNow(RecRepo.items, ch.key, ch.name),
    playing = m.current?.key == ch.key,
)

/**
 * Guide state shared by the TV page (Live TV ▸ Channels) and the full guide: the time window, group tabs, the channel
 * list's scroll and focus, and the rules for where focus goes (Right from the groups: the channel last focused in the
 * group, else the playing one, else the first; Left from a channel: its group; Right from a channel: on now).
 */
@Stable
class GuideFocus(val m: AppModel, val scope: kotlinx.coroutines.CoroutineScope, val listState: androidx.compose.foundation.lazy.LazyListState,
                 val groupState: androidx.compose.foundation.lazy.LazyListState) {
    val groupSel = SafeFocus()
    val chFocus = SafeFocus()
    val nowFocus = NowFocus()
    /** The group tab that holds focus (colour keys act on the group), null when focus is in the channels. */
    var groupFocused by mutableStateOf<String?>(null)
    /** The side menu of all groups ("All groups" tab). */
    val menu = io.github.kmmarin.tvbro.ui.live.GroupMenuState()
    /** The "All groups" tab (focus returns there when the menu closes with Back). */
    val menuTab = SafeFocus()

    /** After a group or channel was picked in the side menu: into its channels, else the selected tab. */
    fun afterMenu() {
        scope.launch {
            delay(60)
            if (m.filtered.isEmpty()) { toGroups(); return@launch }
            val t = targetKey()
            val idx = m.filtered.indexOfFirst { it.key == t }
            if (idx >= 0) runCatching { listState.scrollToItem((idx - 2).coerceAtLeast(0)) }
            repeat(15) { if (chFocus.request()) return@launch; delay(40) }
            toGroups()
        }
    }

    /** Back in the side menu: focus back on the "All groups" tab. */
    fun menuClosed() { scope.launch { repeat(15) { if (menuTab.request()) return@launch; delay(40) } } }

    fun targetKey(): String? {
        val list = m.filtered
        return (m.groupFocus[m.group]?.takeIf { k -> list.any { it.key == k } })
            ?: (m.focusCh?.key?.takeIf { k -> list.any { it.key == k } })
            ?: (m.current?.key?.takeIf { k -> list.any { it.key == k } }) ?: list.firstOrNull()?.key
    }

    /** Focus into the channel list at [targetKey] (scrolled into view first when needed). */
    fun enterChannels(): Boolean {
        if (m.filtered.isEmpty()) return false
        if (!chFocus.request()) scope.launch {
            val t = targetKey()
            val idx = m.filtered.indexOfFirst { it.key == t }
            if (idx >= 0) runCatching { listState.scrollToItem((idx - 2).coerceAtLeast(0)) }
            repeat(15) { if (chFocus.request()) return@launch; delay(40) }
        }
        return true
    }

    /** Left from a channel: its own (selected) group, scrolled into view first when needed. */
    fun toGroups(): Boolean {
        if (!groupSel.request()) scope.launch {
            val gi = m.liveTabs.indexOf(m.group)
            if (gi >= 0) runCatching { groupState.scrollToItem((gi - 2).coerceAtLeast(0)) }
            repeat(15) { if (groupSel.request()) return@launch; delay(40) }
        }
        return true
    }
}

/** The guide's focus state, kept across group changes; each group remembers its own scroll. */
@Composable
fun rememberGuideFocus(m: AppModel): GuideFocus {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState(m.tvScroll[m.group]?.first ?: 0, m.tvScroll[m.group]?.second ?: 0)
    val groupState = rememberLazyListState()
    val g = remember { GuideFocus(m, scope, listState, groupState) }
    var shownGroup by remember { mutableStateOf(m.group) }
    DisposableEffect(m.group) { val gr = m.group; onDispose { m.tvScroll[gr] = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset } }
    // another group in the list: its own remembered scroll (not the previous group's)
    LaunchedEffect(m.group) {
        if (shownGroup == m.group) return@LaunchedEffect
        shownGroup = m.group
        val s = m.tvScroll[m.group]
        runCatching { listState.scrollToItem(s?.first ?: 0, s?.second ?: 0) }
    }
    LaunchedEffect(m.group, m.liveTabs) { // also after a move: the moved group stays in view
        val gi = m.liveTabs.indexOf(m.group)
        if (gi >= 0 && groupState.layoutInfo.visibleItemsInfo.none { it.index == gi }) groupState.scrollToItem((gi - 2).coerceAtLeast(0))
        // after a move from the group menu: focus back on the moved (selected) group
        if (m.groupMoved) { m.groupMoved = false; repeat(2) { delay(200); g.groupSel.request() } }
    }
    DisposableEffect(g) { m.railRight = g.chFocus; onDispose { if (m.railRight === g.chFocus) m.railRight = null } }
    LaunchedEffect(m.group, m.groupReady) { if (m.groupReady) m.store.lastGroup = m.group }
    return g
}

/**
 * The quick-select tabs of the guide (owner feedback 2026-11-14): Favourites, All, Recent, the pinned groups, the
 * chosen group, then "All groups" which opens the side menu (OK). Focusing any other tab shows its channels.
 */
@Composable
fun GuideGroupTabs(m: AppModel, g: GuideFocus, modifier: Modifier = Modifier) {
    val entry = io.github.kmmarin.tvbro.ui.orbit5.LocalShellEntry.current
    GroupTabs(
        m.liveTabs, m.group, { groupTabLabel(m, it) }, g.groupSel, g.groupState,
        onFocus = { gr ->
            g.groupFocused = gr
            if (gr != GROUP_MENU) { m.favAction = { m.toggleFavGroup(gr) }; if (gr !in m.prefs.locked || gr in m.unlocked) m.group = gr }
        },
        onClick = { gr -> if (gr == GROUP_MENU) g.menu.show() else m.selectGroup(gr) }, onLong = { gr -> m.openGroupMenu(gr) },
        onDown = { g.enterChannels() }, modifier = modifier, extraFocus = GROUP_MENU to g.menuTab,
        // Up: the sub-menu's active pill (in the shell), not the info header's Favourite button
        onUp = { entry?.targets()?.firstOrNull()?.request() ?: false },
    )
}

/** The side menu over the page while it is open ([top]: below the top bar inside the shell). */
@Composable
fun GuideGroupMenu(m: AppModel, g: GuideFocus, top: Dp, legend: Boolean = false) {
    if (g.menu.open) io.github.kmmarin.tvbro.ui.live.GroupSideMenu(m, g.menu, top, onChosen = { g.afterMenu() }, onClosed = { g.menuClosed() }, legend = legend)
}

/** The grid of the guide, with what OK does decided by [open]; [startOnNow]: first focus on the programme on now. */
@Composable
fun GuideGrid(m: AppModel, g: GuideFocus, now: Long, open: (Channel, Programme?) -> Unit, modifier: Modifier = Modifier, startOnNow: Boolean = false,
              metrics: io.github.kmmarin.tvbro.ui.live.EpgMetrics = io.github.kmmarin.tvbro.ui.live.EpgMetrics()) {
    val win = remember { EpgWindow(now) }
    val openNow by rememberUpdatedState(open)
    // Follow the clock; a window left in the past (the TV screen can stay open for hours) returns to now.
    LaunchedEffect(now) { win.tick(now) }
    val actions = remember(g) {
        EpgActions(
            onFocus = { ch, p -> g.groupFocused = null; m.focusCh = ch; m.focusProg = p; m.favAction = { m.toggleFav(ch) }; m.groupFocus[m.group] = ch.key },
            onOpen = { ch, p -> openNow(ch, p) },
            onChannelMenu = { ch -> m.openChannelMenu(ch) },
            onProgrammeMenu = { ch, p -> m.progMenu = ch to p },
            toGroups = { g.toGroups() },
            marks = { ch -> rowMarks(m, ch) },
            recorded = { ch, p -> LiveLogic.recordingOf(RecRepo.items, ch.key, ch.name, p) != null },
        )
    }
    EpgGrid(
        m.filtered, { ch -> m.epg.programmes(ch) }, now, win, g.listState, { g.targetKey() }, g.chFocus, g.nowFocus, actions, modifier, metrics,
        emptyText = if (m.loading.isNotEmpty()) m.loading else "No channels in this group.",
    )
    // first focus: the focused (or playing) channel, else the selected group
    LaunchedEffect(m.channels.isNotEmpty()) {
        if (m.channels.isEmpty()) return@LaunchedEffect
        val k = m.focusCh?.key ?: m.current?.key
        val idx = if (k == null) -1 else m.filtered.indexOfFirst { it.key == k }
        if (idx >= 0) {
            val vis = g.listState.layoutInfo.visibleItemsInfo
            if (vis.none { it.index == idx && it.offset >= 0 }) g.listState.scrollToItem((idx - 2).coerceAtLeast(0))
            // (the full guide then moves on to the channel's programme on now)
            repeat(20) { if (g.chFocus.request()) { if (startOnNow && k != null) g.nowFocus.pending = k; return@LaunchedEffect }; delay(50) }
        }
        delay(250); g.groupSel.request()
    }
}

/** The info block about the focused programme (its own scope: moving focus recomposes this, not the grid). */
@Composable
fun GuideInfo(m: AppModel, now: Long, modifier: Modifier = Modifier, compact: Boolean = false) {
    val ch = m.focusCh ?: m.current ?: m.filtered.firstOrNull()
    LiveInfo(liveInfoOf(m, ch, m.focusProg, now), modifier, compact)
}

/** The compact info header of Live TV ▸ Channels, with its Favourite button for the channel shown. */
@Composable
fun GuideInfoBar(m: AppModel, now: Long, modifier: Modifier = Modifier) {
    val ch = m.focusCh ?: m.current ?: m.filtered.firstOrNull()
    io.github.kmmarin.tvbro.ui.live.LiveInfoBar(liveInfoOf(m, ch, m.focusProg, now), ch?.let { c -> { m.toggleFav(c) } }, modifier)
}

/** Live TV ▸ Channels in spec pixels under the sub-menu row: compact header, a 400 x 225 preview, 8+ rows of 64. */
object ChannelsLayout {
    val InfoTop = 182.dp
    val PreviewTop = 178.dp
    val PreviewW = 400.dp
    val PreviewH = 225.dp
    val TabsTop = 356.dp
    val GridTop = 414.dp
    val metrics = io.github.kmmarin.tvbro.ui.live.EpgMetrics(rowH = 64.dp)
}

/**
 * Live TV ▸ Channels (direction 5, mockup #s5 "Live TV guide"): the focused programme and the live preview at the
 * top, the channel groups as tabs, then the guide. Inside the shell, under the sub-menu row (spec pixels).
 * OK on a channel or its programme on now previews it; OK on the channel in the preview opens it full screen.
 */
@Composable
fun TvSection(m: AppModel) {
    val now = rememberNow()
    val g = rememberGuideFocus(m)
    io.github.kmmarin.tvbro.ui.live.LiveColorKeys(m) { g.groupFocused }
    val l = ChannelsLayout
    Box(Modifier.fillMaxSize()) {
        GuideInfoBar(m, now, Modifier.padding(start = 96.dp, top = l.InfoTop, end = 96.dp + l.PreviewW + 40.dp))
        LivePreview(m, m.current, Modifier.align(Alignment.TopEnd).padding(end = 96.dp, top = l.PreviewTop).size(l.PreviewW, l.PreviewH))
        GuideGroupTabs(m, g, Modifier.padding(top = l.TabsTop, end = 96.dp + l.PreviewW + 24.dp))
        GuideGrid(m, g, now, { ch, p -> if (p == null || p.stop > System.currentTimeMillis() / 1000) m.previewOrPlay(ch) else m.guidePlay(ch, p) },
            Modifier.padding(start = 96.dp, end = 96.dp, top = l.GridTop, bottom = 92.dp).fillMaxSize(), metrics = l.metrics)
        GuideGroupMenu(m, g, top = io.github.kmmarin.tvbro.ui.orbit5.O5Space.TopBar)
    }
}
