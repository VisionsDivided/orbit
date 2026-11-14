// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.live

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.kmmarin.tvbro.R
import io.github.kmmarin.tvbro.data.Channel
import io.github.kmmarin.tvbro.data.Programme
import io.github.kmmarin.tvbro.ui.GuideLayout
import io.github.kmmarin.tvbro.ui.SafeFocus
import io.github.kmmarin.tvbro.ui.hhmm
import io.github.kmmarin.tvbro.ui.orbit5.O5

/** What a channel row shows next to the name: favourite star, own stream format (HLS tag), recording, playing. */
@Immutable
data class RowMarks(val fav: Boolean = false, val format: String? = null, val rec: Boolean = false, val playing: Boolean = false)

/**
 * The guide's visible time window: [GuideLayout.WINDOW_MIN] minutes from [start]. It follows the clock until the user
 * moves it, never shows the past, and returns to now when it was left in the past.
 */
@Stable
class EpgWindow(now: Long) {
    var start by mutableLongStateOf(GuideLayout.initialStart(now))
        private set
    private var follow by mutableStateOf(true)
    val end: Long get() = start + GuideLayout.WINDOW_MIN * 60L
    val atNow: Boolean get() = start == GuideLayout.initialStart(System.currentTimeMillis() / 1000)

    /** The clock moved on: keep up with it (or come back to now from the past). */
    fun tick(now: Long) { if (follow || start < GuideLayout.initialStart(now)) { start = GuideLayout.initialStart(now); follow = true } }
    fun shift(d: Long, now: Long) { start = GuideLayout.shifted(start, d, now); follow = start == GuideLayout.initialStart(now) }
    fun ensure(p: Programme, now: Long) {
        val n = GuideLayout.revealNoPast(p.start, p.stop, start, now)
        if (n != start) { start = n; follow = n == GuideLayout.initialStart(now) }
    }
    fun toNow(now: Long) { start = GuideLayout.initialStart(now); follow = true }
}

/** Channel whose on-now programme should take focus once it is composed (after the window moved back to now). */
class NowFocus { var pending by mutableStateOf<String?>(null) }

/** What the guide does with the remote; the screen decides (the TV page previews, the full guide plays). */
class EpgActions(
    val onFocus: (Channel, Programme?) -> Unit,
    /** OK on a channel name or programme (null: the channel itself). */
    val onOpen: (Channel, Programme?) -> Unit,
    val onChannelMenu: (Channel) -> Unit,
    val onProgrammeMenu: (Channel, Programme) -> Unit,
    /** Left from a channel name. */
    val toGroups: () -> Boolean,
    /** A row's marks; read model state here (each row follows only its own). */
    val marks: (Channel) -> RowMarks,
    /** Whether a recording covers this programme. */
    val recorded: (Channel, Programme) -> Boolean,
)

/** Layout of the grid in spec pixels (mockup: channel column 390, rows 80, ruler 44; Live TV ▸ Channels uses rows of 64). */
@Immutable
data class EpgMetrics(val labelW: Dp = 390.dp, val rowH: Dp = 80.dp, val rulerH: Dp = 44.dp)

/**
 * The direction-5 EPG grid (mockup #s5 Live TV guide): a time ruler, the "now" line, and one row per channel with
 * its name column and the programmes of the visible window. Programme cells don't scale; the focused one turns white
 * with dark text.
 *
 * Performance: the grid is laid out once per size (dp per minute is worked out here, not per row); a row reads only
 * its own channel's data and marks, and each cell keeps its own focus state, so moving focus redraws two cells and the
 * page's info block, not the grid. Rows recompose together only when the window moves or the clock ticks.
 */
@Composable
fun EpgGrid(
    channels: List<Channel>,
    programmes: (Channel) -> Array<Programme>,
    now: Long,
    win: EpgWindow,
    listState: LazyListState,
    /** The row whose name takes [chFocus] (where focus comes back to). */
    targetKey: () -> String?,
    chFocus: SafeFocus,
    nowFocus: NowFocus,
    actions: EpgActions,
    modifier: Modifier = Modifier,
    metrics: EpgMetrics = EpgMetrics(),
    emptyText: String = "No channels in this group.",
) {
    val numW = remember(channels) { numberWidth(channels.maxOfOrNull { it.number } ?: 0) }
    BoxWithConstraints(modifier) {
        val dpm = ((maxWidth - metrics.labelW).value / GuideLayout.WINDOW_MIN).coerceAtLeast(0.1f)
        Column(Modifier.fillMaxSize()) {
            Ruler(win, metrics, dpm)
            if (channels.isEmpty()) Text(emptyText, style = LiveStyle.Small, modifier = Modifier.padding(start = 12.dp, top = 24.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState) {
                itemsIndexed(channels) { i, ch ->
                    EpgRow(ch, i == 0, programmes, now, win, dpm, metrics, numW, targetKey, chFocus, nowFocus, actions)
                }
            }
        }
        // the now line over the rows, with its time on the ruler
        val x = GuideLayout.tickX(now, win.start, dpm)
        if (now in win.start..win.end) {
            Box(Modifier.padding(start = metrics.labelW).offset(x = x.dp - 1.5.dp, y = metrics.rulerH - 10.dp).width(3.dp).fillMaxHeight().background(O5.Live).zIndex(4f))
            Box(Modifier.padding(start = metrics.labelW).offset(x = x.dp - 30.dp).width(60.dp).height(metrics.rulerH - 10.dp).zIndex(4f), contentAlignment = Alignment.BottomCenter) {
                Text(hhmm(now), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, softWrap = false,
                    modifier = Modifier.background(O5.Live, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
    }
}

/** Half-hour marks on the same scale as the cells. */
@Composable
private fun Ruler(win: EpgWindow, metrics: EpgMetrics, dpm: Float) {
    Box(Modifier.fillMaxWidth().height(metrics.rulerH).padding(start = metrics.labelW).clipToBounds()) {
        var tk = win.start
        while (tk < win.end) {
            Box(Modifier.offset(x = GuideLayout.tickX(tk, win.start, dpm).dp).fillMaxHeight().drawBehind {
                drawRect(LiveStyle.Line, topLeft = Offset(0f, size.height * 0.2f), size = Size(2.dp.toPx(), size.height * 0.6f))
            }.padding(start = 10.dp), contentAlignment = Alignment.CenterStart) {
                Text(hhmm(tk), style = LiveStyle.Ruler, maxLines = 1, softWrap = false)
            }
            tk += 1800
        }
    }
}

@Composable
private fun EpgRow(
    ch: Channel, first: Boolean, programmes: (Channel) -> Array<Programme>, now: Long, win: EpgWindow, dpm: Float, metrics: EpgMetrics, numW: Dp,
    targetKey: () -> String?, chFocus: SafeFocus, nowFocus: NowFocus, actions: EpgActions,
) {
    val isTarget by remember(ch.key) { derivedStateOf { targetKey() == ch.key } }
    val nowFr = remember { SafeFocus() }
    // this row's channel name: where Left from the first block in the window goes (never into the past)
    val labelFr = remember { FocusRequester() }
    val toLabel = { runCatching { labelFr.requestFocus() }; true }
    LaunchedEffect(nowFocus.pending, nowFr.isAttached) { if (nowFocus.pending == ch.key && nowFr.request()) nowFocus.pending = null }
    // Right from a channel name: the programme on now (the window jumps back to now first when it was moved away)
    val toNow = {
        if (!win.atNow) { win.toNow(System.currentTimeMillis() / 1000); nowFocus.pending = ch.key } else nowFr.request()
        true
    }
    Row(Modifier.fillMaxWidth().height(metrics.rowH)) {
        ChannelCell(ch, actions.marks(ch), numW, if (isTarget) chFocus else null, labelFr, minOf(49f, metrics.rowH.value - 15f).dp,
            Modifier.width(metrics.labelW).fillMaxHeight()
                // Right: this row's programme on now (never a programme in another row); Left: the groups
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) false
                    else when (e.key) {
                        Key.DirectionRight -> toNow(); Key.DirectionLeft -> actions.toGroups()
                        // Up from the top row: the selected group tab (the focus search skipped the tab row on the TV)
                        Key.DirectionUp -> first && actions.toGroups()
                        else -> false
                    }
                },
            onFocus = { actions.onFocus(ch, null) }, onLong = { actions.onChannelMenu(ch) }, onClick = { actions.onOpen(ch, null) })
        Box(Modifier.weight(1f).fillMaxHeight()) {
            ProgrammeRow(ch, first, programmes(ch), now, win, dpm, nowFr, toLabel, actions)
        }
    }
}

@Composable
private fun ProgrammeRow(
    ch: Channel, first: Boolean, progs: Array<Programme>, now: Long, win: EpgWindow, dpm: Float, nowFr: SafeFocus, toLabel: () -> Boolean, actions: EpgActions,
) {
    val winStart = win.start; val winEnd = win.end
    val total = dpm * GuideLayout.WINDOW_MIN
    // "No guide data" blocks take focus too (OK: the channel), so Up/Down never skip a row or jump far away
    val noData: @Composable (Float, Float, SafeFocus?) -> Unit = { x, w, fr ->
        Cell(x, w, "No guide data", null, past = false, live = false, progress = null, rec = false, focus = fr,
            keys = { e -> if (e == Key.DirectionLeft && x < 1f) toLabel() else if (e == Key.DirectionUp && first) actions.toGroups() else false },
            onFocus = { actions.onFocus(ch, null) }, onLong = { actions.onChannelMenu(ch) }, onClick = { actions.onOpen(ch, null) })
    }
    val inWindow = progs.any { it.stop > winStart && it.start < winEnd }
    if (!inWindow) { noData(0f, total, nowFr); return }
    var cursor = winStart
    // the programme that Right from the channel name lands on: on now, else the next one in the window
    val nowP = progs.firstOrNull { it.stop > now && it.stop > winStart && it.start < winEnd }
    for (p in progs) {
        val b = GuideLayout.block(p.start, p.stop, winStart, winEnd, dpm, gap = 0f) ?: continue
        val s = maxOf(p.start, winStart)
        if (s > cursor + 120) GuideLayout.block(cursor, s, winStart, winEnd, dpm, gap = 0f)?.let { g ->
            if (g.second > 60f) key("gap$cursor") { noData(g.first, g.second, null) }
        }
        key(p.start) {
            val live = p.start <= now && p.stop > now
            val past = p.stop <= now
            val small = if (b.second > 150f) hhmm(p.start) + " – " + hhmm(p.stop) else null
            Cell(
                b.first, b.second, (if (p.start < winStart) "‹ " else "") + p.title, small, past, live,
                progress = if (live) LiveLogic.progress(p.start, p.stop, now) else null,
                rec = actions.recorded(ch, p), focus = if (p === nowP) nowFr else null,
                keys = { k ->
                    when (k) {
                        Key.DirectionRight -> { val nx = progs.firstOrNull { it.start >= p.stop }; if (nx != null && nx.start >= winEnd) { win.shift(1800, now); true } else false }
                        // Left from the first block in the window: the window moves back only when that stays in the present,
                        // else focus goes to the channel name (the past is never shown)
                        Key.DirectionLeft -> {
                            val pv = progs.lastOrNull { it.stop <= p.start }
                            if (pv != null && pv.stop <= winStart && pv.stop > GuideLayout.initialStart(now) && GuideLayout.shifted(winStart, -1800, now) != winStart) { win.shift(-1800, now); true }
                            else if (p.start <= winStart + 120 || pv == null || pv.stop <= winStart) {
                                // the channel name, with the window back at now (where Right from the name goes again)
                                if (winStart != GuideLayout.initialStart(now)) win.shift(-48 * 3600L, now)
                                toLabel()
                            } else false
                        }
                        Key.DirectionUp -> first && actions.toGroups()
                        else -> false
                    }
                },
                onFocus = { actions.onFocus(ch, p); win.ensure(p, System.currentTimeMillis() / 1000) },
                onLong = { actions.onProgrammeMenu(ch, p) }, onClick = { actions.onOpen(ch, p) },
            )
        }
        cursor = minOf(p.stop, winEnd)
    }
    if (winEnd > cursor + 120) GuideLayout.block(cursor, winEnd, winStart, winEnd, dpm, gap = 0f)?.let { g ->
        if (g.second > 60f) key("gapEnd") { noData(g.first, g.second, null) }
    }
}

private val CellShape = RoundedCornerShape(8.dp)

/** Draws the focused cell: white fill inside the 3 px padding, and a 5 px white outline 2 px outside it. */
private fun Modifier.cellFocusLook(focused: Boolean, inset: Dp = 3.dp): Modifier = drawWithContent {
    if (focused) {
        val i = inset.toPx(); val r = 8.dp.toPx()
        drawRoundRect(Color.White, topLeft = Offset(i, i), size = Size(size.width - 2 * i, size.height - 2 * i), cornerRadius = CornerRadius(r))
        val w = 5.dp.toPx(); val o = 2.dp.toPx() + w / 2 - i
        drawRoundRect(Color.White, topLeft = Offset(-o, -o), size = Size(size.width + 2 * o, size.height + 2 * o),
            cornerRadius = CornerRadius(r + o + i), style = Stroke(w))
    }
    drawContent()
}

@Composable
private fun Cell(
    x: Float, w: Float, title: String, small: String?, past: Boolean, live: Boolean, progress: Float?, rec: Boolean, focus: SafeFocus?,
    keys: (Key) -> Boolean, onFocus: () -> Unit, onLong: () -> Unit, onClick: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    val dim = if (past && !f) 0.55f else 1f
    Box(
        Modifier.offset(x = x.dp).width(w.dp).fillMaxHeight().zIndex(if (f) 3f else 0f)
            .drawBehind {
                // idle: a thin line on the left; on now: a faint fill
                if (live) drawRect(LiveStyle.CellNow, topLeft = Offset(3.dp.toPx(), 3.dp.toPx()), size = Size(size.width - 6.dp.toPx(), size.height - 6.dp.toPx()))
                drawRect(LiveStyle.Line, topLeft = Offset(3.dp.toPx(), 3.dp.toPx()), size = Size(2.dp.toPx(), size.height - 6.dp.toPx()))
            }
            .cellFocusLook(f)
            .onPreviewKeyEvent { e -> e.type == KeyEventType.KeyDown && keys(e.key) }
            .onFocusChanged { if (it.isFocused) onFocus() }
            .flatFocus(src, focus, onLongClick = onLong, onClick = onClick),
    ) {
        Column(Modifier.fillMaxSize().clipToBounds().padding(horizontal = 17.dp), verticalArrangement = Arrangement.Center) {
            Text(title, style = LiveStyle.Cell, color = (if (f) O5.Bg else O5.Text).copy(alpha = dim), maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
            if (small != null || rec) Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                if (rec) { RecMark(); Spacer(Modifier.width(8.dp)) }
                if (small != null) Text(small, style = LiveStyle.CellSmall, color = if (f) Color(0xFF333333) else O5.Muted.copy(alpha = O5.Muted.alpha * dim), maxLines = 1, softWrap = false)
            }
        }
        if (progress != null && !f) Box(
            Modifier.align(Alignment.BottomStart).padding(start = 5.dp, bottom = 5.dp).height(4.dp)
                .width(((w - 10f) * progress).coerceAtLeast(0f).dp).background(O5.Accent, RoundedCornerShape(2.dp)),
        )
    }
}

/** The channel column: number, tile, name, then the marks (star, HLS tag, REC, catch-up). */
@Composable
private fun ChannelCell(
    ch: Channel, marks: RowMarks, numW: Dp, focus: SafeFocus?, labelFr: FocusRequester, tile: Dp, modifier: Modifier,
    onFocus: () -> Unit, onLong: () -> Unit, onClick: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    val fg = if (f) O5.Bg else O5.Text
    Row(
        modifier.zIndex(if (f) 3f else 0f)
            .drawBehind { drawRect(LiveStyle.Line, topLeft = Offset(0f, size.height - 1.dp.toPx()), size = Size(size.width, 1.dp.toPx())) }
            .cellFocusLook(f)
            .focusRequester(labelFr)
            .testTag("ch-" + ch.key)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .flatFocus(src, focus, onLongClick = onLong, onClick = onClick)
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(ch.number.toString(), style = LiveStyle.Cell.copy(fontFeatureSettings = "tnum"), color = if (f) Color(0xFF333333) else O5.Muted,
            modifier = Modifier.width(numW), maxLines = 1, softWrap = false)
        ChannelTile(ch.logo, ch.name, tile)
        Text(ch.name, style = LiveStyle.Cell, color = if (marks.playing && !f) O5.Accent else fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        if (marks.rec) RecMark(size = 15)
        if (marks.fav) Text("★", color = if (f) Color(0xFFB8860B) else O5.Rating, fontSize = 17.sp, maxLines = 1)
        marks.format?.let { FormatTag(it) }
        if (ch.catchup != null) Image(painterResource(R.drawable.ic_catchup), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(if (f) Color(0xFF333333) else O5.Muted))
    }
}

/** Width of the channel-number column for numbers up to [max] (22 px digits; at least 2.4 em). */
fun numberWidth(max: Int): Dp = (max.coerceAtLeast(1).toString().length * 14 + 4).coerceAtLeast(50).dp
