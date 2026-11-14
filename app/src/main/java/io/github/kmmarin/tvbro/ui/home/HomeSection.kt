// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.kmmarin.tvbro.AppModel
import io.github.kmmarin.tvbro.Screen
import io.github.kmmarin.tvbro.data.VodItem
import io.github.kmmarin.tvbro.ui.SafeFocus
import io.github.kmmarin.tvbro.ui.orbit5.BackdropSpec
import io.github.kmmarin.tvbro.ui.orbit5.CardInfo
import io.github.kmmarin.tvbro.ui.orbit5.CardSize
import io.github.kmmarin.tvbro.ui.orbit5.CinematicBackdrop
import io.github.kmmarin.tvbro.ui.orbit5.ColorKeyActions
import io.github.kmmarin.tvbro.ui.orbit5.FocusRoom
import io.github.kmmarin.tvbro.ui.orbit5.HeroBlock
import io.github.kmmarin.tvbro.ui.orbit5.HeroData
import io.github.kmmarin.tvbro.ui.orbit5.KeyAction
import io.github.kmmarin.tvbro.ui.orbit5.LandscapeCard
import io.github.kmmarin.tvbro.ui.orbit5.O5
import io.github.kmmarin.tvbro.ui.orbit5.O5Button
import io.github.kmmarin.tvbro.ui.orbit5.O5Row
import io.github.kmmarin.tvbro.ui.orbit5.O5Space
import io.github.kmmarin.tvbro.ui.orbit5.O5Type
import io.github.kmmarin.tvbro.ui.orbit5.PosterCard
import io.github.kmmarin.tvbro.ui.orbit5.O5Space.Side
import io.github.kmmarin.tvbro.ui.vodMenu
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Top of the rows area on Home (the first row's heading sits at y = 676 in the mockup). */
private val RowsTop = 676.dp

/**
 * Home: the cross-everything feed (spec section 6 and the mockup's Home). The hero shows the focused card's title
 * (and rotates through featured titles while focus is on its buttons); the backdrop cross-fades with it. Rows below:
 * Continue watching, My list, live channels, recently added (see [HomeFeed]).
 *
 * D-pad: Up from the first row goes to the hero buttons, Up from those to the top bar. Back with focus in the rows
 * goes back to the hero; Back on the hero leaves the app (the shell).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeSection(m: AppModel) {
    val feed = m.home
    LaunchedEffect(m.orbit.link?.profile) { feed.refresh(force = true) }
    LaunchedEffect(Unit) { feed.refresh() }
    val rows = feed.rows
    // the catalog's hero titles, else the first titles of the rows
    val heroList = feed.hero
    val featured = remember(rows, heroList) { heroList.ifEmpty { rows.flatMap { it.tiles }.filter { it.channel == null }.distinctBy { it.key } }.take(6) }

    var focused by remember { mutableStateOf<Pair<Tile, String>?>(null) }   // card and its row title
    var inRows by remember { mutableStateOf(false) }
    var featuredIdx by remember { mutableIntStateOf(0) }
    LaunchedEffect(inRows, featured) {
        if (inRows || featured.size < 2) return@LaunchedEffect
        while (true) { delay(9000); featuredIdx = (featuredIdx + 1) % featured.size }
    }
    val subject: Pair<Tile, String>? = if (inRows) focused else featured.getOrNull(featuredIdx % featured.size.coerceAtLeast(1))?.let { t ->
        t to (if (heroList.isNotEmpty()) "Featured" else rows.firstOrNull { r -> r.tiles.any { it.key == t.key } }?.title ?: "")
    }
    // the hero waits ~250 ms after a focus move (moving quickly along a row changes nothing)
    val settled by produceState(subject, subject?.first?.key) { delay(250); value = subject }
    // synopsis, year and backdrop of an IPTV title: the provider's details (cached on disk), as Movies does
    val tile = settled?.first
    LaunchedEffect(tile?.key) { tile?.vod?.takeIf { tile.backdrop == null || tile.year == null }?.let { m.peekVod(it) } }
    val peek = m.peek?.takeIf { tile?.vod != null && m.focusVod?.key == tile.key }
    val hero = tile?.let { t ->
        HeroData(
            key = t.key, title = t.title, eyebrow = settled?.second?.substringBefore(" · ") ?: "",
            source = if (t.channel != null) "" else t.source, year = t.year ?: peek?.year, rating = t.rating ?: peek?.rating?.takeIf { it != "0" },
            runtime = if (t.progress != null) t.meta else io.github.kmmarin.tvbro.data.VodFmt.runtime(peek?.duration),
            genres = t.genres.ifEmpty { peek?.genre?.split(',', '/')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList() },
            synopsis = peek?.plot ?: if (t.channel != null) "Now on ${t.title}: ${t.meta}" else null,
        )
    }
    val backdrop = tile?.let { BackdropSpec(it.backdrop ?: peek?.backdrop ?: it.poster, it.colors) }

    // colour keys for the focused card (spec section 9: My list, Play trailer, Mark watched, More)
    val ft = focused?.first.takeIf { inRows }
    ColorKeyActions(
        red = ft?.let { t -> KeyAction("My list") { t.vod?.let { m.toggleFavKey(it) } ?: t.channel?.let { m.toggleFav(it) }; feed.refresh(force = true) } },
        yellow = ft?.vod?.takeIf { !it.kind.equals("series", true) }?.let { v -> KeyAction("Mark watched") { m.markWatched(v.key, !m.isWatched(v.key)); feed.refresh(force = true) } },
        blue = ft?.let { t -> KeyAction("More") { t.vod?.let { vodMenu(m, it) } ?: t.channel?.let { m.openChannelMenu(it) } } },
    )

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val playFr = remember { SafeFocus() }
    // Back with focus in the rows: up to the hero first (Back on the hero leaves the app, see the shell)
    BackHandler(enabled = inRows && m.menu == null && m.prompt == null && m.progMenu == null && m.screen == Screen.LIST) {
        scope.launch { listState.scrollToItem(0) }
        playFr.request()
    }
    // first visit: focus on the hero's first button
    LaunchedEffect(Unit) { repeat(10) { delay(120); if (io.github.kmmarin.tvbro.ui.FocusMemory.anyFocus || playFr.request()) return@LaunchedEffect } }

    Box(Modifier.fillMaxSize().testTag("home")) {
        CinematicBackdrop(backdrop, Modifier.fillMaxSize(), settleMs = 0)
        HeroBlock(hero, Modifier.padding(start = Side, top = O5Space.HeroTopHome)) {
            // the buttons act on the hero's title; entering the row lands on the first one
            Row(Modifier.focusProperties { enter = { playFr.target() } }.focusGroup(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                val t = tile
                val primary = when {
                    t == null -> "Play"
                    t.channel != null -> "Watch"
                    t.show -> if (t.seriesRow != null) "Continue" else "Episodes"
                    t.progress != null -> "Resume"
                    else -> "Play"
                }
                O5Button(primary, icon = Icons.Filled.PlayArrow, primary = true, focus = playFr, modifier = Modifier.testTag("hero-play")) { t?.let { open(m, it, play = true) } }
                O5Button(if (t?.channel != null) "Guide" else "More info", icon = Icons.Filled.Info) {
                    if (t?.channel != null) { m.focusCh = t.channel; m.screen = Screen.GUIDE } else t?.let { open(m, it, play = false) }
                }
            }
        }
        if (!inRows && featured.size > 1) {
            Row(Modifier.align(Alignment.TopEnd).padding(top = 600.dp, end = Side), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                featured.indices.forEach { i ->
                    val on = i == featuredIdx % featured.size
                    Box(Modifier.size(if (on) 36.dp else 12.dp, 12.dp).background(if (on) Color.White else Color(0x59FFFFFF), RoundedCornerShape(6.dp)))
                }
            }
        }
        if (feed.loaded && rows.isEmpty()) {
            Text(
                "Nothing here yet. Watch something, add titles to My list, or link this TV to Orbit (Settings ▸ Home server).",
                style = O5Type.Body, modifier = Modifier.padding(start = Side, top = RowsTop),
            )
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(top = RowsTop - FocusRoom).onFocusChanged {
                if (inRows != it.hasFocus) { inRows = it.hasFocus; if (!it.hasFocus) scope.launch { listState.animateScrollToItem(0) } }
            }.testTag("home-rows"),
            state = listState, contentPadding = PaddingValues(bottom = 420.dp), userScrollEnabled = false,
        ) {
            itemsIndexed(rows, key = { _, r -> r.id }) { i, row ->
                O5Row(
                    row.title, Modifier.padding(bottom = O5Space.BetweenRows - FocusRoom * 2 + 30.dp)
                        .onFocusChanged { if (it.hasFocus) scope.launch { listState.animateScrollToItem(i) } },
                    gap = if (row.landscape) O5Space.RowGapLandscape else O5Space.RowGapPoster,
                ) {
                    items(row.tiles, key = { it.key }) { t ->
                        val onFocus = { focused = t to row.title }
                        val long = { t.vod?.let { vodMenu(m, it) } ?: t.channel?.let { m.openChannelMenu(it) } ?: Unit }
                        if (row.landscape) {
                            LandscapeCard(
                                if (t.channel != null) t.poster else (t.backdrop ?: t.poster),
                                CardInfo(t.title, t.meta, progress = t.progress), onFocus = onFocus, onLongClick = long,
                                contentScale = if (t.channel != null) ContentScale.Fit else ContentScale.Crop,
                            ) { open(m, t, play = t.channel != null) }
                        } else {
                            PosterCard(
                                t.poster, CardInfo(t.title, t.meta, watched = t.vod?.let { m.isWatched(it.key) } == true),
                                width = CardSize.RowPosterW, height = CardSize.RowPosterH, onFocus = onFocus, onLongClick = long,
                            ) { open(m, t, play = false) }
                        }
                    }
                }
            }
        }
    }
}

/** OK on a card or a hero button: channels play, titles open their page ([play]: films start playing). */
internal fun open(m: AppModel, t: Tile, play: Boolean, unlocked: Boolean = false) {
    t.channel?.let { m.play(it); return }
    // a catalog title of a PIN-locked category (its category name is not known here, so the PIN is asked each time)
    if (t.locked && !unlocked && m.prefs.pinHash.isNotEmpty()) { m.askPin { open(m, t, play, unlocked = true) }; return }
    val v: VodItem = t.vod ?: run { m.toast("${t.title} plays from ${t.source.replaceFirstChar { it.uppercase() }}, not on this TV yet"); return }
    // straight to playback only when the file type is known (catalog titles carry none: their page loads it)
    if (play && !t.show && !m.isDirect(v) && v.ext.isNotEmpty()) {
        if (m.detail?.key != v.key) m.detailInfo = null    // playItem prefers the open page's file type
        m.playItem(v)
    } else m.openDetail(v)
}
