// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kmmarin.tvbro.AppModel
import io.github.kmmarin.tvbro.GROUP_FAV
import io.github.kmmarin.tvbro.data.Channel
import io.github.kmmarin.tvbro.data.EpisodeNum
import io.github.kmmarin.tvbro.data.LiveFormat
import io.github.kmmarin.tvbro.data.Programme
import io.github.kmmarin.tvbro.data.RecRepo
import io.github.kmmarin.tvbro.ui.SafeFocus
import io.github.kmmarin.tvbro.ui.hhmm
import io.github.kmmarin.tvbro.ui.orbit5.O5
import io.github.kmmarin.tvbro.ui.orbit5.O5Shape
import io.github.kmmarin.tvbro.ui.orbit5.ProgressBar
import io.github.kmmarin.tvbro.ui.orbit5.o5Focus

/** The programme block above the guide (mockup: channel line with LIVE, title, times and next, progress, stream). */
@Immutable
data class LiveInfoData(
    val channel: String,
    val logo: String?,
    val title: String,
    val time: String? = null,
    val live: Boolean = false,
    val progress: Float? = null,
    val next: String? = null,
    val episode: String? = null,
    val desc: String? = null,
    /** "Stream: TS · default"; null hides it. */
    val stream: String? = null,
    val rec: Boolean = false,
    /** "OK: watch catch-up" and other hints under the block. */
    val hint: String? = null,
    /** The channel is in Favourites (the info bar's Favourite button). */
    val fav: Boolean = false,
)

/** What the info block shows for [ch] and [p] (p null: the programme on now). */
fun liveInfoOf(m: AppModel, ch: Channel?, p: Programme?, now: Long): LiveInfoData {
    if (ch == null) return LiveInfoData("", null, if (m.loading.isNotEmpty()) m.loading else "No channel")
    val (cur, nxt) = m.epg.nowNext(ch, now)
    val prog = p ?: cur
    val live = prog != null && prog.start <= now && prog.stop > now
    val after = if (prog == null) null else if (prog === cur) nxt else m.epg.programmes(ch).firstOrNull { it.start >= prog.stop }
    val fmt = m.channelFormat(ch.key)
    val hint = when {
        prog != null && prog.stop <= now -> if (ch.catchup != null) "OK: watch catch-up" else "No catch-up for this channel"
        else -> null
    }
    return LiveInfoData(
        channel = "${ch.number} · ${ch.name}", logo = ch.logo, title = prog?.title ?: "No guide data",
        time = prog?.let { hhmm(it.start) + " – " + hhmm(it.stop) + if (live) "   ${(it.stop - now) / 60} min left" else if (it.stop <= now) "   (finished)" else "" },
        live = live, progress = if (live) LiveLogic.progress(prog!!.start, prog.stop, now) else null,
        next = after?.let { "Next: ${hhmm(it.start)} ${it.title}" },
        episode = prog?.let { EpisodeNum.line(it.ep, it.sub) }, desc = prog?.desc,
        stream = if (LiveFormat.canHls(ch.url)) "Stream: " + LiveLogic.effectiveFormat(fmt, m.liveHls) + if (fmt == null) " · default" else " · this channel" else null,
        rec = prog != null && LiveLogic.recordingOf(RecRepo.items, ch.key, ch.name, prog) != null,
        hint = hint, fav = ch.key in m.favourites,
    )
}

/** The info block. [compact] is the full guide's smaller version (title 52 px). */
@Composable
fun LiveInfo(d: LiveInfoData, modifier: Modifier = Modifier, compact: Boolean = false) {
    Column(modifier.widthIn(max = 1100.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (d.channel.isNotEmpty()) ChannelTile(d.logo, d.channel.substringAfter(" · "), 56.dp)
            Text(d.channel, style = LiveStyle.Meta.copy(color = O5.Text), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (d.live) LivePill()
            if (d.rec) RecMark(size = 20)
        }
        Text(d.title, style = if (compact) LiveStyle.Title.copy(fontSize = 52.sp) else LiveStyle.Title, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 16.dp, bottom = 10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            d.time?.let { Text(it, style = LiveStyle.Meta, maxLines = 1) }
            d.episode?.let { Text(it, style = LiveStyle.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 380.dp)) }
            d.next?.let { Text(it, style = LiveStyle.Meta, color = O5.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (d.progress != null || d.stream != null) Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            if (d.progress != null) ProgressBar(d.progress, Modifier.width(480.dp))
            d.stream?.let { Text(it, style = LiveStyle.Small, maxLines = 1) }
        }
        val extra = d.hint ?: d.desc
        if (extra != null && !compact) Text(extra, style = LiveStyle.Small, color = if (d.hint != null) O5.Accent else O5.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp))
        else if (d.hint != null) Text(d.hint, style = LiveStyle.Small, color = O5.Accent, maxLines = 1, modifier = Modifier.padding(top = 10.dp))
    }
}

/** A group's tab label: Favourites with its star, locked groups marked, groups in My list with a star after. */
fun groupTabLabel(m: AppModel, g: String): String {
    if (g == io.github.kmmarin.tvbro.GROUP_RECENT) return "Recent"
    if (g == io.github.kmmarin.tvbro.GROUP_MENU) return "All groups ☰"
    val base = m.groupLabel(g)
    val fav = base.endsWith("  *")
    return (if (g == GROUP_FAV) "★ " else "") + base.removeSuffix("  *") + if (fav) " ★" else ""
}

/**
 * The channel groups as tabs over the guide (mockup: "★ Favourites · All channels · Entertainment …"): 24 px bold,
 * inactive at half white, the selected group white with the accent underline. Focus enters at the selected group;
 * focusing a group shows its channels; Down goes into the channels ([onDown]).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GroupTabs(
    groups: List<String>, selected: String, label: (String) -> String, selFocus: SafeFocus, state: LazyListState,
    onFocus: (String) -> Unit, onClick: (String) -> Unit, onLong: (String) -> Unit, onDown: () -> Boolean, modifier: Modifier = Modifier,
    /** A tab steered from outside besides the selected one (the "All groups" tab, where Back from the side menu lands). */
    extraFocus: Pair<String, SafeFocus>? = null,
    /** Up from a tab (Live TV: the sub-menu's active pill); false = the normal focus search. */
    onUp: () -> Boolean = { false },
) {
    LazyRow(
        modifier.focusProperties { enter = { selFocus.target() } }.focusGroup(), state = state,
        contentPadding = PaddingValues(start = 82.dp, end = 82.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(groups, key = { it }) { g -> // keyed: a moved group keeps its focus
            val sel = g == selected
            Box(
                Modifier.onPreviewKeyEvent { e -> e.type == KeyEventType.KeyDown && ((e.key == Key.DirectionDown && onDown()) || (e.key == Key.DirectionUp && onUp())) }
                    .testTag("tab-$g")
                    .o5Focus(O5Shape.Pill, 30.dp, focus = if (sel) selFocus else extraFocus?.takeIf { it.first == g }?.second, scaleTo = 1.04f,
                        onFocus = { onFocus(g) }, onLongClick = { onLong(g) }) { onClick(g) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(label(g), style = LiveStyle.Tab, color = if (sel) Color.White else Color(0x80FFFFFF), maxLines = 1,
                    modifier = if (sel) Modifier.drawBehind {
                        drawRoundRect(O5.Accent, topLeft = Offset(0f, size.height + 6.dp.toPx()), size = Size(size.width, 4.dp.toPx()), cornerRadius = CornerRadius(2.dp.toPx()))
                    } else Modifier)
            }
        }
    }
}

/**
 * The compact info header of Live TV ▸ Channels (owner feedback 2026-11-14: the full block took 40% of the screen):
 * the channel line with LIVE and a Favourite button, a 40 px title on one line, times / progress / next / stream on
 * one line, and a one-line description (or the catch-up hint). About 170 px high, so 8 guide rows fit at 1080p.
 */
@Composable
fun LiveInfoBar(d: LiveInfoData, onFav: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (d.channel.isNotEmpty()) ChannelTile(d.logo, d.channel.substringAfter(" · "), 40.dp)
            Text(d.channel, style = LiveStyle.Meta.copy(color = O5.Text, fontSize = 22.sp), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 520.dp))
            if (d.live) LivePill()
            if (d.rec) RecMark(size = 18)
            if (onFav != null && d.channel.isNotEmpty()) FavButton(d.fav, onFav)
        }
        Text(d.title, style = LiveStyle.Title.copy(fontSize = 40.sp, letterSpacing = (-0.02).em), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 4.dp).height(34.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            d.time?.let { Text(it, style = LiveStyle.Meta.copy(fontSize = 21.sp), maxLines = 1, softWrap = false) }
            if (d.progress != null) ProgressBar(d.progress, Modifier.width(220.dp))
            d.episode?.let { Text(it, style = LiveStyle.Meta.copy(fontSize = 21.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp)) }
            d.next?.let { Text(it, style = LiveStyle.Meta.copy(fontSize = 21.sp), color = O5.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
            d.stream?.let { Text(it, style = LiveStyle.Small.copy(fontSize = 18.sp), maxLines = 1, softWrap = false) }
        }
        val extra = d.hint ?: d.desc
        Text(extra ?: "", style = LiveStyle.Small, color = if (d.hint != null) O5.Accent else O5.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp))
    }
}

/** "☆ Favourite" / "★ Favourited" next to the channel name. */
@Composable
fun FavButton(fav: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.o5Focus(O5Shape.Pill, 22.dp, scaleTo = 1.05f, onClick = onClick)
            .background(if (fav) Color(0x29FFD166) else Color(0x14FFFFFF), O5Shape.Pill)
            .border(1.dp, if (fav) Color(0x80FFD166) else Color(0x2EFFFFFF), O5Shape.Pill)
            .padding(horizontal = 14.dp, vertical = 5.dp),
    ) {
        Text(if (fav) "★ Favourited" else "☆ Favourite", color = if (fav) O5.Rating else O5.Text, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
    }
}