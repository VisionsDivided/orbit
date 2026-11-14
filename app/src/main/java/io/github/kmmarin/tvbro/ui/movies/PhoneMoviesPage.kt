// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.movies

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.kmmarin.tvbro.AppModel
import io.github.kmmarin.tvbro.Screen
import io.github.kmmarin.tvbro.data.VodFmt
import io.github.kmmarin.tvbro.ui.orbit5.BackdropSpec
import io.github.kmmarin.tvbro.ui.orbit5.BrowseSource
import io.github.kmmarin.tvbro.ui.orbit5.CinematicBackdrop

/**
 * Movies / Shows on a phone, bound to the feed: the same tabs, data and actions as the TV page ([MoviesShowsSection]),
 * drawn by the phone views in PhoneMovies.kt. No focus or colour keys: tap opens a title, long press its menu (My list,
 * trailer, watched, …); the hero's buttons play and add to My list.
 */
@Composable
internal fun PhoneKindPage(m: AppModel, feed: MoviesFeed, k: KindState, local: Boolean) {
    KindLoading(m, feed, k, local)
    val overlay = m.menu != null || m.prompt != null || m.progMenu != null
    val events = TitleEvents(
        onOpen = { t -> k.lastKey = t.key; k.restore = true; openTitle(m, t) },
        onLong = { t -> titleMenu(m, t) },
        watched = { t -> m.watch.rev.intValue; isWatched(m, t) },
    )
    val header: @Composable () -> Unit = {
        PhoneMoviesControls(
            SubTab.values().map { it.label }, k.tab.ordinal,
            onSelect = { i -> val t = SubTab.values()[i]; if (t == SubTab.COLLECTIONS && k.tab == SubTab.COLLECTIONS) k.openColl = null; k.tab = t },
            browse = if (k.tab == SubTab.BROWSE) browseControls(m, feed, k, local) else null,
        )
    }
    // back from a title's page: the list is where it was (the grid scrolls to the title), then the flag is cleared
    LaunchedEffect(k.tab) { if (k.restore) { kotlinx.coroutines.delay(400); k.restore = false } }

    when (k.tab) {
        SubTab.RECOMMENDED -> {
            val t = k.hero.firstOrNull() ?: k.rows.firstOrNull()?.items?.firstOrNull()
            LaunchedEffect(t?.key) { t?.vod?.takeIf { !t.media && (t.backdrop == null || t.year == null) }?.let { m.peekVod(it) } }
            val peek = m.peek?.takeIf { t?.vod != null && m.focusVod?.key == t?.key }
            val hero = t?.let { heroOf(it, if (k.hero.isNotEmpty()) "Featured" else k.rows.firstOrNull()?.title ?: "", peek?.plot, peek?.year, VodFmt.runtime(peek?.duration)) }
            PhoneRecommendedView(
                hero, k.rows, events, playLabel = playLabel(m, t), inList = t?.let { m.favourites.contains(it.key) } == true,
                onPlay = { t?.let { k.lastKey = null; openTitle(m, it, play = true) } },
                onMyList = { t?.let { toggleMyList(m, it); feed.refreshRecommended(k, force = true) } },
                header = header,
                backdrop = { CinematicBackdrop(t?.let { BackdropSpec(it.backdrop ?: peek?.backdrop ?: it.poster, it.colors) }, Modifier.fillMaxSize(), settleMs = 0) },
                message = if (k.recLoaded && k.rows.isEmpty()) "Nothing here yet. Watch something or add titles to My list." else null,
                state = rememberLazyListState(),
            )
        }
        SubTab.BROWSE -> {
            val gridState = rememberLazyGridState()
            val vod = m.vodFiltered
            val localItems = remember(vod) { if (local) vod.map { Titles.of(it) } else emptyList() }
            val items: List<Title> = if (local) localItems else k.items
            Box(Modifier.fillMaxSize()) {
                if (local) {
                    val shown = if (k.source == BrowseSource.LOCAL) emptyList() else items
                    PhoneTopBackdrop { CinematicBackdrop(shown.firstOrNull()?.let { BackdropSpec(it.backdrop ?: it.poster, it.colors) }, Modifier.fillMaxSize(), settleMs = 0) }
                    val msg = when {
                        k.source == BrowseSource.LOCAL -> "Plex and Jellyfin titles come from the Orbit home server (Settings ▸ Home server)."
                        m.vodMsg.isNotEmpty() -> m.vodMsg
                        items.isEmpty() -> "Nothing here."
                        else -> null
                    }
                    PhoneBrowseView(shown, events, m.vodHasMore, { m.vodMore() }, header, message = msg, state = gridState)
                } else {
                    PhoneTopBackdrop { CinematicBackdrop(items.firstOrNull()?.let { BackdropSpec(it.backdrop ?: it.poster, it.colors) }, Modifier.fillMaxSize(), settleMs = 0) }
                    PhoneBrowseView(items, events, k.hasMore, { feed.loadBrowse(k, more = true) }, header,
                        message = k.browseMsg.ifEmpty { if (!k.browseLoaded) "Loading…" else null }, state = gridState)
                }
            }
            PhoneRestoreGrid(k, items, gridState)
        }
        SubTab.COLLECTIONS -> {
            val open = k.openColl
            BackHandler(enabled = open != null && !overlay && m.screen == Screen.LIST) { k.openColl = null }
            if (open == null) {
                Box(Modifier.fillMaxSize()) {
                    PhoneTopBackdrop { CinematicBackdrop(k.collections.firstOrNull()?.let { BackdropSpec(it.backdrop ?: it.poster, it.colors) }, Modifier.fillMaxSize(), settleMs = 0) }
                    PhoneCollectionsView(
                        k.collections, k.shows,
                        onOpen = { c ->
                            if (c.category != null) {   // local data: the category's titles in Browse
                                k.pickedCategory = true; m.selectVodCat(c.category); k.tab = SubTab.BROWSE
                            } else feed.openCollection(k, c)
                        },
                        onLong = { c -> c.category?.let { m.vodCatMenu(it) } },
                        header = header,
                        message = if (!k.collLoaded) "Loading…" else if (k.collections.isEmpty()) "No collections yet." else null,
                    )
                }
            } else {
                PhoneCollectionView(
                    open, k.collItems, k.shows, events, onPlay = { t -> k.lastKey = t.key; k.restore = true; openTitle(m, t, play = true) }, header = header,
                    backdrop = { CinematicBackdrop(BackdropSpec(open.backdrop ?: open.poster, open.colors), Modifier.fillMaxSize(), settleMs = 0) },
                    message = k.collMsg.ifEmpty { null },
                )
            }
        }
    }
}

/** Back from a title's page into Browse: the grid scrolls to the title (a row above it stays in view). */
@Composable
private fun PhoneRestoreGrid(k: KindState, items: List<Title>, state: LazyGridState) {
    LaunchedEffect(Unit) {
        if (!k.restore) return@LaunchedEffect
        val i = items.indexOfFirst { it.key == k.lastKey }
        // (+1: the controls are the grid's first item)
        if (i >= 0) state.scrollToItem((i + 1 - 3).coerceAtLeast(0))
    }
}
