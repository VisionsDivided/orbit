// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.live

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kmmarin.tvbro.AppModel
import io.github.kmmarin.tvbro.Section
import io.github.kmmarin.tvbro.data.CatchupUrl
import io.github.kmmarin.tvbro.data.Channel
import io.github.kmmarin.tvbro.data.EpisodeNum
import io.github.kmmarin.tvbro.data.Programme
import io.github.kmmarin.tvbro.data.RecRepo
import io.github.kmmarin.tvbro.ui.LivePreview
import io.github.kmmarin.tvbro.ui.MyListScreen
import io.github.kmmarin.tvbro.ui.RecordingsScreen
import io.github.kmmarin.tvbro.ui.hhmm
import io.github.kmmarin.tvbro.ui.orbit5.O5
import io.github.kmmarin.tvbro.ui.orbit5.PhoneChip
import io.github.kmmarin.tvbro.ui.orbit5.PhoneChipRow
import io.github.kmmarin.tvbro.ui.orbit5.PhoneTouch
import io.github.kmmarin.tvbro.ui.orbit5.ProgressBar
import io.github.kmmarin.tvbro.ui.orbit5.SubMenuRow
import io.github.kmmarin.tvbro.ui.rememberNow
import io.github.kmmarin.tvbro.ui.rowMarks
import io.github.kmmarin.tvbro.ui.shell.LocalPhoneInsets

/** Phone Live TV state that outlives the page (the player's Guide action opens the guide tab). */
object PhoneLive {
    /** Live TV ▸ Guide is showing (else Channels). */
    var guide by mutableStateOf(false)
}

/** A row of the phone guide: one programme of the chosen channel. */
data class GuideEntry(val p: Programme, val live: Boolean, val past: Boolean, val catchup: Boolean, val rec: Boolean, val day: String?)

/**
 * The phone guide's programmes for a channel: what is on now and later; finished ones only while catch-up still has
 * them. [day] marks the first programme of each later day ("Tomorrow", "Sat 16").
 */
fun guideEntries(progs: Array<Programme>, ch: Channel, now: Long, catchup: (Programme) -> Boolean, rec: (Programme) -> Boolean): List<GuideEntry> {
    val tz = java.util.TimeZone.getDefault()
    fun dayOf(t: Long): Long = Math.floorDiv(t + tz.getOffset(t * 1000) / 1000, 86_400L)
    val today = dayOf(now)
    var lastDay = today
    val out = ArrayList<GuideEntry>()
    for (p in progs) {
        val past = p.stop <= now
        val cu = past && ch.catchup != null && catchup(p)
        if (past && !cu) continue
        val d = dayOf(p.start)
        val label = if (d != lastDay && d > today) {
            lastDay = d
            if (d == today + 1) "Tomorrow"
            else java.text.SimpleDateFormat("EEE d", java.util.Locale.getDefault()).format(java.util.Date(p.start * 1000))
        } else null
        out += GuideEntry(p, p.start <= now && !past, past, cu, rec(p), label)
    }
    return out
}

/**
 * Live TV on a phone (spec section 13, mockup "Phone · 5b" Live TV guide): the sub-menu chips (Channels · Guide ·
 * Recordings · My list), the live preview with what plays in it, the channel groups as chips, then either the channel
 * list with what's on now, or the guide: one channel at a time, its programmes down the screen. Upright the parts
 * stack; on its side the preview sits on the left and the list on the right.
 */
@Composable
fun PhoneLiveSection(m: AppModel) {
    val insets = LocalPhoneInsets.current
    val tabs = listOf("Channels", "Guide", "Recordings", "My list")
    val sel = when (m.section) { Section.RECORDINGS -> 2; Section.MYLIST -> 3; else -> if (PhoneLive.guide) 1 else 0 }
    val onTab: (Int) -> Unit = { i ->
        when (i) {
            0 -> { PhoneLive.guide = false; m.section = Section.TV }
            1 -> { PhoneLive.guide = true; m.section = Section.TV }
            2 -> m.section = Section.RECORDINGS
            else -> { m.refreshLists(); m.section = Section.MYLIST }
        }
    }
    when (m.section) {
        Section.RECORDINGS, Section.MYLIST -> Column(Modifier.fillMaxSize().padding(top = insets.top)) {
            SubMenuRow(tabs, sel, onTab)
            Box(Modifier.weight(1f).padding(bottom = insets.bottom)) { if (m.section == Section.RECORDINGS) RecordingsScreen(m) else MyListScreen(m) }
        }
        else -> {
            val now = rememberNow()
            val playing = m.current
            val guideCh = (m.focusCh ?: playing)?.let { c -> m.filtered.firstOrNull { it.key == c.key } } ?: m.filtered.firstOrNull()
            PhoneLiveView(
                tabs = tabs, selected = sel, onTab = onTab,
                preview = { mod -> LivePreview(m, playing, mod) },
                nowPlaying = playing?.let { c -> m.epg.nowNext(c, now).first?.title?.let { it to c.name } ?: ((c.name) to "") },
                groups = m.groupList, group = m.group, groupLabel = { groupTabLabel(m, it) },
                onGroup = { m.selectGroup(it) }, onGroupLong = { m.openGroupMenu(it) },
                guide = PhoneLive.guide, channels = m.filtered, programmes = { c -> m.epg.programmes(c) }, now = now,
                marks = { c -> rowMarks(m, c) },
                onChannel = { c -> m.focusCh = c; m.previewOrPlay(c) }, onChannelLong = { c -> m.openChannelMenu(c) },
                guideChannel = guideCh, onGuideChannel = { c -> m.focusCh = c; m.focusProg = null },
                catchup = { c, p -> CatchupUrl.supports(c, p.start, now) },
                recorded = { c, p -> LiveLogic.recordingOf(RecRepo.items, c.key, c.name, p) != null },
                onProgramme = { c, e ->
                    when {
                        e.live -> m.previewOrPlay(c)
                        e.past -> m.guidePlay(c, e.p)
                        else -> m.progMenu = c to e.p     // later: remind / record
                    }
                },
                onProgrammeLong = { c, e -> m.progMenu = c to e.p },
                emptyText = if (m.loading.isNotEmpty()) m.loading else "No channels in this group.",
            )
        }
    }
}

/** The phone Live TV page without the app model (the shot tests draw it). */
@Composable
fun PhoneLiveView(
    tabs: List<String>, selected: Int, onTab: (Int) -> Unit, preview: @Composable (Modifier) -> Unit, nowPlaying: Pair<String, String>?,
    groups: List<String>, group: String, groupLabel: (String) -> String, onGroup: (String) -> Unit, onGroupLong: (String) -> Unit,
    guide: Boolean, channels: List<Channel>, programmes: (Channel) -> Array<Programme>, now: Long, marks: (Channel) -> RowMarks,
    onChannel: (Channel) -> Unit, onChannelLong: (Channel) -> Unit,
    guideChannel: Channel?, onGuideChannel: (Channel) -> Unit, catchup: (Channel, Programme) -> Boolean, recorded: (Channel, Programme) -> Boolean,
    onProgramme: (Channel, GuideEntry) -> Unit, onProgrammeLong: (Channel, GuideEntry) -> Unit, emptyText: String = "No channels in this group.",
    listState: LazyListState = rememberLazyListState(),
) {
    val insets = LocalPhoneInsets.current
    BoxWithConstraints(Modifier.fillMaxSize().testTag("phone-live")) {
        val wide = maxWidth > maxHeight
        val top: @Composable (Modifier) -> Unit = { mod ->
            Column(mod) {
                SubMenuRow(tabs, selected, onTab)
                preview(Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp).fillMaxWidth().aspectRatio(16f / 9f).testTag("phone-live-preview"))
                nowPlaying?.let { (title, channel) ->
                    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallLive()
                        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = O5.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (channel.isNotEmpty()) Text(channel, fontSize = 11.sp, color = O5.Muted, maxLines = 1)
                    }
                }
            }
        }
        val list: @Composable (Modifier) -> Unit = { mod ->
            Column(mod) {
                PhoneChipRow(start = 14.dp) {
                    groups.forEach { g -> PhoneChip(groupLabel(g), g == group, onLongClick = { onGroupLong(g) }) { onGroup(g) } }
                }
                if (channels.isEmpty()) Text(emptyText, fontSize = 14.sp, color = O5.Muted, modifier = Modifier.padding(20.dp))
                else if (guide && guideChannel != null) PhoneGuide(channels, guideChannel, onGuideChannel, programmes(guideChannel), now,
                    { catchup(guideChannel, it) }, { recorded(guideChannel, it) }, { onProgramme(guideChannel, it) }, { onProgrammeLong(guideChannel, it) }, insets.bottom)
                else PhoneChannelList(channels, programmes, now, marks, onChannel, onChannelLong, insets.bottom, listState)
            }
        }
        if (wide) Row(Modifier.fillMaxSize().padding(top = insets.top)) {
            top(Modifier.weight(0.44f).fillMaxHeight())
            list(Modifier.weight(0.56f).fillMaxHeight())
        } else Column(Modifier.fillMaxSize().padding(top = insets.top)) {
            top(Modifier.fillMaxWidth())
            list(Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp))
        }
    }
}

/** The small LIVE pill of the phone (9 px, 3 x 6). */
@Composable
fun SmallLive(modifier: Modifier = Modifier, text: String = "LIVE", color: Color = O5.Live, fg: Color = Color.White) {
    Text(text, modifier.background(color, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        color = fg, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.7.sp, maxLines = 1)
}

/** The tiny yellow format tag ("HLS" / "TS") of a channel with its own stream format. */
@Composable
private fun SmallTag(text: String) {
    Text(text, Modifier.background(LiveStyle.FmtBg, RoundedCornerShape(4.dp)).border(1.dp, LiveStyle.FmtRing, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
        color = LiveStyle.FmtText, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
}

/**
 * The phone channel list: number, tile, name with its marks (★ favourite, HLS / TS, ● REC), what's on now with its
 * progress and times, and what's next. The channel in the preview is marked. Tap previews (tap again, or tap the
 * preview, for full screen); long press opens the channel menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhoneChannelList(
    channels: List<Channel>, programmes: (Channel) -> Array<Programme>, now: Long, marks: (Channel) -> RowMarks,
    onTap: (Channel) -> Unit, onLong: (Channel) -> Unit, bottom: Dp, state: LazyListState = rememberLazyListState(),
) {
    LazyColumn(Modifier.fillMaxSize().testTag("phone-channels"), state = state, contentPadding = PaddingValues(bottom = bottom + 12.dp)) {
        items(channels.size) { i ->
            val ch = channels[i]
            val progs = programmes(ch)
            val cur = progs.firstOrNull { it.start <= now && it.stop > now }
            val next = progs.firstOrNull { it.start >= (cur?.stop ?: now) }
            val mk = marks(ch)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).background(if (mk.playing) Color(0x0FFFFFFF) else Color.Transparent)
                    .combinedClickable(onLongClick = { onLong(ch) }) { onTap(ch) }
                    .drawBehind { drawLine(O5.Line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(if (ch.number > 0) "${ch.number}" else "", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = O5.Muted, maxLines = 1, modifier = Modifier.width(30.dp))
                ChannelTile(ch.logo, ch.name, 40.dp)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(ch.name, fontSize = 12.sp, color = if (mk.playing) O5.Accent else O5.Muted, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (mk.fav) Text("★", color = O5.Rating, fontSize = 11.sp)
                        mk.format?.takeIf { it != "Default" }?.let { SmallTag(it) }
                        if (mk.rec) Text("● REC", color = LiveStyle.Rec, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                    Text(cur?.title ?: "No guide information", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (cur != null) O5.Text else O5.Muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
                    if (cur != null) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProgressBar(LiveLogic.progress(cur.start, cur.stop, now), Modifier.width(56.dp), height = 3.dp)
                        Text(hhmm(cur.start) + " – " + hhmm(cur.stop) + (next?.let { "  ·  Next ${hhmm(it.start)} ${it.title}" } ?: ""),
                            fontSize = 11.sp, color = O5.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/**
 * The phone guide: the group's channels as tiles along the top (tap one to show its programmes), then that channel's
 * programmes down the screen: start time, title, episode or description, what's on now lit with its progress,
 * finished ones dimmed while catch-up has them. Tap: now = watch, finished = catch-up, later = remind / record menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhoneGuide(
    channels: List<Channel>, ch: Channel, onChannel: (Channel) -> Unit, progs: Array<Programme>, now: Long,
    catchup: (Programme) -> Boolean, recorded: (Programme) -> Boolean, onTap: (GuideEntry) -> Unit, onLong: (GuideEntry) -> Unit, bottom: Dp,
) {
    val entries = remember(progs, ch.key, now / 60) { guideEntries(progs, ch, now, catchup, recorded) }
    val tiles = rememberLazyListState()
    val list = rememberLazyListState()
    LaunchedEffect(ch.key) {
        val i = channels.indexOfFirst { it.key == ch.key }
        if (i >= 0 && tiles.layoutInfo.visibleItemsInfo.none { it.index == i }) tiles.scrollToItem((i - 2).coerceAtLeast(0))
        val n = entries.indexOfFirst { it.live || !it.past }
        list.scrollToItem((n - 1).coerceAtLeast(0))
    }
    Column(Modifier.fillMaxSize().testTag("phone-guide")) {
        LazyRow(state = tiles, contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(channels, key = { it.key }) { c ->
                val on = c.key == ch.key
                Column(
                    Modifier.width(64.dp).clip(RoundedCornerShape(10.dp)).background(if (on) Color(0x1FFFFFFF) else Color.Transparent)
                        .combinedClickable { onChannel(c) }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(if (on) Modifier.border(2.dp, Color.White, RoundedCornerShape(11.dp)).padding(2.dp) else Modifier.padding(2.dp)) { ChannelTile(c.logo, c.name, 40.dp) }
                    Text(if (c.number > 0) "${c.number}" else c.name, fontSize = 10.sp, color = if (on) Color.White else O5.Muted, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
        Text(ch.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = O5.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp))
        if (entries.isEmpty()) Text("No guide information for this channel.", fontSize = 14.sp, color = O5.Muted, modifier = Modifier.padding(20.dp))
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = bottom + 12.dp)) {
            itemsIndexed(entries, key = { i, e -> "$i-${e.p.start}" }) { _, e ->
                Column {
                    e.day?.let { Text(it.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, color = O5.Muted, modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp)) }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = PhoneTouch + 8.dp).background(if (e.live) LiveStyle.CellNow else Color.Transparent)
                            .combinedClickable(onLongClick = { onLong(e) }) { onTap(e) }
                            .drawBehind { drawLine(O5.Line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
                            .padding(horizontal = 20.dp, vertical = 9.dp).alpha(if (e.past) 0.6f else 1f),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Column(Modifier.width(48.dp)) {
                            Text(hhmm(e.p.start), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (e.live) O5.Accent else O5.Text, maxLines = 1)
                            Text(hhmm(e.p.stop), fontSize = 11.sp, color = O5.Muted, maxLines = 1)
                        }
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (e.live) SmallLive()
                                if (e.catchup) SmallLive(text = "CATCH-UP", color = O5.Accent, fg = O5.OnAccent)
                                Text(e.p.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = O5.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                if (e.rec) Text("● REC", color = LiveStyle.Rec, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                            (EpisodeNum.line(e.p.ep, e.p.sub) ?: e.p.desc)?.takeIf { it.isNotBlank() }?.let {
                                Text(it, fontSize = 12.sp, color = O5.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                            }
                            if (e.live) ProgressBar(LiveLogic.progress(e.p.start, e.p.stop, now), Modifier.padding(top = 6.dp).fillMaxWidth(0.6f), height = 3.dp)
                        }
                    }
                }
            }
        }
    }
}
