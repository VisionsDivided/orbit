// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.movies

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.kmmarin.tvbro.AppModel
import io.github.kmmarin.tvbro.CAT_MYLIST
import io.github.kmmarin.tvbro.CAT_RECENT
import io.github.kmmarin.tvbro.Screen
import io.github.kmmarin.tvbro.Section
import io.github.kmmarin.tvbro.VOD_ALL
import io.github.kmmarin.tvbro.data.VodFmt
import io.github.kmmarin.tvbro.ui.MenuState
import io.github.kmmarin.tvbro.ui.SafeFocus
import io.github.kmmarin.tvbro.ui.orbit5.BackdropSpec
import io.github.kmmarin.tvbro.ui.orbit5.BrowseControls
import io.github.kmmarin.tvbro.ui.orbit5.BrowseSource
import io.github.kmmarin.tvbro.ui.orbit5.CinematicBackdrop
import io.github.kmmarin.tvbro.ui.orbit5.ColorKeyActions
import io.github.kmmarin.tvbro.ui.orbit5.KeyAction
import io.github.kmmarin.tvbro.ui.orbit5.O5Space
import io.github.kmmarin.tvbro.ui.orbit5.SubMenuRow
import io.github.kmmarin.tvbro.ui.shell.LocalIsPhone
import io.github.kmmarin.tvbro.vodCatLabel
import kotlinx.coroutines.delay

/**
 * Movies and Shows (top bar; [AppModel.section] says which), direction 5: the sub-menu row
 * `Recommended · Browse [All | Local | IPTV] · Collections … Any genre ▾ · Sort ▾ · N titles` over the page.
 *
 * - Recommended: hero plus rows (`/v1/catalog/{kind}/recommended`).
 * - Browse: the paged poster grid (`/v1/catalog/{kind}/browse`), loading more as focus nears the end.
 * - Collections: the grid of collections, then a collection's page (Back closes it).
 *
 * Not linked, or a server without the catalog (404) or out of reach: the same pages from the TV's own data
 * (Continue watching, My list, recently added, History; Browse by the provider's categories, which the genre menu
 * lists; Collections lists those categories).
 */
@Composable
fun MoviesShowsSection(m: AppModel) {
    val feed = m.movies
    val shows = m.section == Section.SHOWS
    val k = feed.of(shows)
    LaunchedEffect(m.orbit.link?.session, m.orbit.link?.profile) { feed.linkChanged(m.orbit.link?.let { it.session + "/" + it.profile }) }
    val local = feed.local
    key(shows) { KindPage(m, feed, k, local) }
}

/** Loads what the page shows (TV and phone). */
@Composable
internal fun KindLoading(m: AppModel, feed: MoviesFeed, k: KindState, local: Boolean) {
    LaunchedEffect(k.tab, local, k.recLoaded, k.browseLoaded, k.collLoaded) {
        when (k.tab) {
            SubTab.RECOMMENDED -> feed.refreshRecommended(k)
            SubTab.BROWSE -> if (!local && !k.browseLoaded && !k.loadingPage) feed.loadBrowse(k)
            SubTab.COLLECTIONS -> feed.loadCollections(k)
        }
    }
    // local Browse shows every title until a category is picked (the TV's own list opened on My list / History)
    LaunchedEffect(local, k.tab, m.vodCatNames) {
        if (local && k.tab == SubTab.BROWSE && m.vodCatNames.isNotEmpty() && !k.pickedCategory && m.vodCat != VOD_ALL) m.selectVodCat(VOD_ALL)
    }
}

@Composable
private fun KindPage(m: AppModel, feed: MoviesFeed, k: KindState, local: Boolean) {
    // the same APK on a phone: touch layout (PhoneMoviesPage.kt)
    if (LocalIsPhone.current) { PhoneKindPage(m, feed, k, local); return }
    val ctx = LocalContext.current
    var focused by remember { mutableStateOf<Title?>(null) }
    var focusedColl by remember { mutableStateOf<Coll?>(null) }
    var eyebrow by remember { mutableStateOf("") }
    var inRows by remember { mutableStateOf(false) }
    val restoreFocus = remember { SafeFocus() }
    val playFocus = remember { SafeFocus() }
    val overlay = m.menu != null || m.prompt != null || m.progMenu != null

    // loading what the page shows
    KindLoading(m, feed, k, local)
    val events = TitleEvents(
        onFocus = { t -> focused = t; k.lastKey = t.key; m.favAction = { toggleMyList(m, t) } },   // bookmark / Y keep toggling My list
        onOpen = { t -> k.lastKey = t.key; k.restore = true; openTitle(m, t) },
        onLong = { t -> titleMenu(m, t) },
        watched = { t -> m.watch.rev.intValue; isWatched(m, t) },
        focusKey = k.lastKey.takeIf { k.restore }, focus = restoreFocus,
    )
    // back from a title's page: focus returns to its card
    LaunchedEffect(k.tab) {
        if (!k.restore) return@LaunchedEffect
        repeat(30) { delay(60); if (restoreFocus.request()) { k.restore = false; return@LaunchedEffect } }
        k.restore = false
    }

    // the focused title, settled (hero, backdrop and colour keys wait ~250 ms after a focus move)
    val heroList = k.hero
    val featured = heroList.firstOrNull() ?: k.rows.firstOrNull()?.items?.firstOrNull()
    val subject: Title? = when (k.tab) {
        SubTab.RECOMMENDED -> if (inRows) focused else featured
        SubTab.BROWSE -> focused
        SubTab.COLLECTIONS -> if (k.openColl != null) focused else null
    }
    val settled by produceState(subject, subject?.key) { delay(250); value = subject }
    LaunchedEffect(settled?.key) { settled?.vod?.takeIf { !settled!!.media && (settled!!.backdrop == null || settled!!.year == null) }?.let { m.peekVod(it) } }
    val peek = m.peek?.takeIf { settled?.vod != null && m.focusVod?.key == settled?.key }
    val backdrop = when {
        k.tab == SubTab.COLLECTIONS && k.openColl == null -> focusedColl?.let { BackdropSpec(it.backdrop ?: it.poster, it.colors) }
        k.tab == SubTab.COLLECTIONS && settled == null -> k.openColl?.let { BackdropSpec(it.backdrop ?: it.poster, it.colors) }
        else -> settled?.let { BackdropSpec(it.backdrop ?: peek?.backdrop ?: it.poster, it.colors) }
    }

    // colour keys (spec section 9): My list, Play trailer, Mark watched, More, for the focused card or the hero's title
    val keyTitle = settled
    val trailer = peek?.trailer?.takeIf { it.isNotBlank() }
    ColorKeyActions(
        red = keyTitle?.vod?.let { KeyAction("My list") { toggleMyList(m, keyTitle); if (k.tab == SubTab.RECOMMENDED) feed.refreshRecommended(k, force = true) } },
        green = trailer?.let { KeyAction("Play trailer") { openTrailer(ctx, m, it) } },
        yellow = keyTitle?.let { KeyAction("Mark watched") { toggleWatched(m, it) } },
        blue = keyTitle?.let { KeyAction("More") { titleMenu(m, it) } },
        enabled = m.screen == Screen.LIST,
    )

    Box(Modifier.fillMaxSize()) {
        CinematicBackdrop(backdrop, Modifier.fillMaxSize())
        when (k.tab) {
            SubTab.RECOMMENDED -> {
                val hero = settled?.let { heroOf(it, if (inRows) eyebrow else if (heroList.isNotEmpty()) "Featured" else k.rows.firstOrNull()?.title ?: "", peek?.plot, peek?.year, VodFmt.runtime(peek?.duration)) }
                val t = settled
                val listState = rememberLazyListState()
                val rowStates = remember { HashMap<String, LazyListState>() }
                k.rows.forEach { r -> if (r.id !in rowStates) rowStates[r.id] = LazyListState() }
                RecommendedView(
                    hero, k.rows,
                    TitleEvents(
                        onFocus = { x -> events.onFocus(x); eyebrow = k.rows.firstOrNull { r -> r.items.any { it.key == x.key } }?.title ?: "" },
                        onOpen = events.onOpen, onLong = events.onLong, watched = events.watched, focusKey = events.focusKey, focus = events.focus,
                    ),
                    playLabel = playLabel(m, t), inList = t?.let { m.favourites.contains(it.key) } == true,
                    onPlay = { t?.let { k.lastKey = null; openTitle(m, it, play = true) } },
                    onMyList = { t?.let { toggleMyList(m, it); feed.refreshRecommended(k, force = true) } },
                    message = if (k.recLoaded && k.rows.isEmpty()) "Nothing here yet. Watch something or add titles to My list." else null,
                    playFocus = playFocus, listState = listState, onRowsFocus = { inRows = it }, rowStates = rowStates,
                )
                // a restored card further down: scroll its row into view first
                LaunchedEffect(Unit) {
                    if (!k.restore) return@LaunchedEffect
                    val key = k.lastKey ?: return@LaunchedEffect
                    val ri = k.rows.indexOfFirst { r -> r.items.any { it.key == key } }
                    if (ri < 0) return@LaunchedEffect
                    listState.scrollToItem(ri)
                    rowStates[k.rows[ri].id]?.scrollToItem(k.rows[ri].items.indexOfFirst { it.key == key }.coerceAtLeast(0))
                }
            }
            SubTab.BROWSE -> {
                val gridState = rememberLazyGridState()
                if (local) {
                    val vod = m.vodFiltered
                    val items = remember(vod) { vod.map { Titles.of(it) } }
                    val msg = when {
                        k.source == BrowseSource.LOCAL -> "Plex and Jellyfin titles come from the Orbit home server (Settings ▸ Home server)."
                        m.vodMsg.isNotEmpty() -> m.vodMsg
                        items.isEmpty() -> "Nothing here."
                        else -> null
                    }
                    BrowseGridView(if (k.source == BrowseSource.LOCAL) emptyList() else items, events, m.vodHasMore, { m.vodMore() }, message = msg, state = gridState)
                    RestoreGrid(k, items, gridState)
                } else {
                    BrowseGridView(k.items, events, k.hasMore, { feed.loadBrowse(k, more = true) },
                        message = k.browseMsg.ifEmpty { if (!k.browseLoaded) "Loading…" else null }, state = gridState)
                    RestoreGrid(k, k.items, gridState)
                }
            }
            SubTab.COLLECTIONS -> {
                val open = k.openColl
                BackHandler(enabled = open != null && !overlay && m.screen == Screen.LIST) { k.openColl = null; focused = null }
                if (open == null) {
                    CollectionsGridView(
                        k.collections, k.shows, onFocus = { focusedColl = it },
                        onOpen = { c ->
                            if (c.category != null) {   // local data: the category's titles in Browse
                                k.pickedCategory = true; m.selectVodCat(c.category); k.tab = SubTab.BROWSE
                            } else feed.openCollection(k, c)
                        },
                        onLong = { c -> c.category?.let { m.vodCatMenu(it) } },
                        message = if (!k.collLoaded) "Loading…" else if (k.collections.isEmpty()) "No collections yet." else null,
                    )
                } else {
                    val cf = remember { SafeFocus() }
                    CollectionView(open, k.collItems, k.shows, events, onPlay = { t -> k.lastKey = t.key; k.restore = true; openTitle(m, t, play = true) },
                        message = k.collMsg.ifEmpty { null }, playFocus = cf)
                    LaunchedEffect(open.id, k.collItems.isNotEmpty()) { if (!k.restore && k.collItems.isNotEmpty()) repeat(10) { delay(80); if (cf.request()) return@LaunchedEffect } }
                }
            }
        }
        SubMenuRow(
            SubTab.values().map { it.label }, k.tab.ordinal,
            onSelect = { i -> val t = SubTab.values()[i]; if (t == SubTab.COLLECTIONS && k.tab == SubTab.COLLECTIONS) k.openColl = null; k.tab = t; focused = null },
            Modifier.padding(top = O5Space.SubMenuTop), browseTab = SubTab.BROWSE.ordinal,
            browse = browseControls(m, feed, k, local),
        )
    }
}

/** Back from a title's page into Browse: the grid scrolls to the title first. */
@Composable
private fun RestoreGrid(k: KindState, items: List<Title>, state: androidx.compose.foundation.lazy.grid.LazyGridState) {
    LaunchedEffect(Unit) {
        if (!k.restore) return@LaunchedEffect
        val i = items.indexOfFirst { it.key == k.lastKey }
        if (i >= 0) state.scrollToItem((i - 8).coerceAtLeast(0))
    }
}

/** The primary hero button's label for [t]. */
fun playLabel(m: AppModel, t: Title?): String = when {
    t == null -> "Play"
    t.media -> "Details"
    t.show -> if (t.next != null || m.watch.seriesRow(t.key)?.nextEp != null) "Continue" else "Episodes"
    t.progress != null -> "Resume"
    else -> "Play"
}

internal fun browseControls(m: AppModel, feed: MoviesFeed, k: KindState, local: Boolean): BrowseControls {
    val onSource: (BrowseSource) -> Unit = { s -> if (s != k.source) { k.source = s; if (!local) feed.loadBrowse(k) } }
    if (local) {
        val cats = listOf(VOD_ALL, CAT_MYLIST, CAT_RECENT) + m.vodCatNames
        val label = if (m.vodCat == VOD_ALL) "Any genre" else vodCatLabel(m.vodCat, k.shows)
        val n = if (k.source == BrowseSource.LOCAL) 0 else m.vodFiltered.size
        return BrowseControls(
            k.source, onSource, genre = label,
            onGenre = {
                m.menu = MenuState("Category", cats.map { c ->
                    val name = if (c == VOD_ALL) "Any genre" else vodCatLabel(c, k.shows)
                    (name + if (c in m.prefs.locked) " (locked)" else "") to { k.pickedCategory = true; m.selectVodCat(c) }
                })
            },
            sort = "Provider order", onSort = { m.toast("Sorting needs the Orbit home server") },
            count = Titles.count(n, more = m.vodHasMore),
        )
    }
    return BrowseControls(
        k.source, onSource, genre = k.genre.ifEmpty { "Any genre" },
        onGenre = {
            m.menu = MenuState("Genre", (listOf("") + k.genres).map { g ->
                (g.ifEmpty { "Any genre" }) to { if (g != k.genre) { k.genre = g; feed.loadBrowse(k) } }
            })
        },
        sort = k.sort.label,
        onSort = { m.menu = MenuState("Sort", BrowseSort.values().map { s -> s.label to { if (s != k.sort) { k.sort = s; feed.loadBrowse(k) } } }) },
        count = if (k.browseLoaded) Titles.count(k.total) else null,
    )
}

/** Opens a trailer address in the app that handles it (YouTube), as tvBro's title page did. */
fun openTrailer(ctx: Context, m: AppModel, url: String) {
    val u = if (url.startsWith("http")) url else "https://www.youtube.com/watch?v=$url"
    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (e: Exception) { m.toast("No app to open the trailer") }
}
