// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.movies

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kmmarin.tvbro.ui.SafeFocus
import io.github.kmmarin.tvbro.ui.orbit5.CardInfo
import io.github.kmmarin.tvbro.ui.orbit5.CardSize
import io.github.kmmarin.tvbro.ui.orbit5.FocusRoom
import io.github.kmmarin.tvbro.ui.orbit5.HeroData
import io.github.kmmarin.tvbro.ui.orbit5.LandscapeCard
import io.github.kmmarin.tvbro.ui.orbit5.O5
import io.github.kmmarin.tvbro.ui.orbit5.O5Button
import io.github.kmmarin.tvbro.ui.orbit5.O5Row
import io.github.kmmarin.tvbro.ui.orbit5.O5Space
import io.github.kmmarin.tvbro.ui.orbit5.O5Type
import io.github.kmmarin.tvbro.ui.orbit5.PosterCard
import io.github.kmmarin.tvbro.ui.orbit5.ProgressBar
import io.github.kmmarin.tvbro.ui.orbit5.SourceBadge
import kotlinx.coroutines.launch

/*
 * The Movies / Shows pages as plain views (data in, events out), so the JVM shot tests can draw them without the app
 * model. MoviesShowsSection binds them to the feed.
 */

/** What a card on these pages does: focus (hero / backdrop follow it), OK and long press / Menu. */
class TitleEvents(
    val onFocus: (Title) -> Unit = {},
    val onOpen: (Title) -> Unit = {},
    val onLong: (Title) -> Unit = {},
    val watched: (Title) -> Boolean = { false },
    /** The card focus returns to (back from a title's page). */
    val focusKey: String? = null,
    val focus: SafeFocus? = null,
)

/** Top of the Recommended rows (the first heading at y = 634 in the mockup). */
val RecRowsTop = 634.dp
/** The Browse grid starts directly under the sub-menu row (spec section 5). */
val GridTop = O5Space.ContentUnderSubMenu

/** Hero text of a title (eyebrow: the row it came from, or "Featured"). */
fun heroOf(t: Title, eyebrow: String, synopsis: String? = null, year: String? = null, runtime: String? = null, genres: List<String> = emptyList()): HeroData = HeroData(
    key = t.key, title = t.title, eyebrow = eyebrow, source = t.source, year = t.year ?: year, rating = t.rating,
    runtime = runtime, genres = t.genres.ifEmpty { genres }, synopsis = synopsis,
)

/**
 * Recommended (mockup "Movies · Recommended"): the hero over the rows. The hero buttons act on the hero's title; the
 * rows scroll so the focused row sits at the top of the rows area.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RecommendedView(
    hero: HeroData?, rows: List<TitleRow>, ev: TitleEvents, playLabel: String, inList: Boolean,
    onPlay: () -> Unit, onMyList: () -> Unit, modifier: Modifier = Modifier, message: String? = null,
    playFocus: SafeFocus? = null, listState: LazyListState = rememberLazyListState(), onRowsFocus: (Boolean) -> Unit = {},
    rowStates: Map<String, LazyListState> = emptyMap(),
) {
    val scope = rememberCoroutineScope()
    Box(modifier.fillMaxSize().testTag("movies-recommended")) {
        Column(Modifier.padding(start = O5Space.Side, top = O5Space.HeroTopSection).widthIn(max = 1100.dp)) {
            Crossfade(hero, animationSpec = tween(600), label = "section-hero") { d -> if (d != null) SectionHeroText(d) }
            Row(Modifier.padding(top = 28.dp).focusProperties { enter = { playFocus?.target() ?: androidx.compose.ui.focus.FocusRequester.Default } }.focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                O5Button(playLabel, icon = Icons.Filled.PlayArrow, primary = true, compact = true, focus = playFocus, onClick = onPlay)
                O5Button("My list", icon = if (inList) Icons.Filled.Check else Icons.Filled.Add, compact = true, onClick = onMyList)
            }
        }
        if (message != null) Text(message, style = O5Type.Body, modifier = Modifier.padding(start = O5Space.Side, top = RecRowsTop))
        LazyColumn(
            Modifier.fillMaxSize().padding(top = RecRowsTop - 8.dp).onFocusChanged {
                onRowsFocus(it.hasFocus)
                if (!it.hasFocus) scope.launch { listState.animateScrollToItem(0) }
            },
            state = listState, contentPadding = PaddingValues(bottom = 420.dp), userScrollEnabled = false,
        ) {
            itemsIndexed(rows, key = { _, r -> r.id }) { i, row ->
                O5Row(
                    row.title, Modifier.padding(bottom = O5Space.BetweenRows - FocusRoom * 2)
                        .onFocusChanged { if (it.hasFocus) scope.launch { listState.animateScrollToItem(i) } },
                    gap = if (row.landscape) O5Space.RowGapLandscape else O5Space.RowGapPoster,
                    state = rowStates[row.id] ?: rememberLazyListState(),
                ) {
                    items(row.items.size, key = { row.items[it].key }) { j ->
                        val t = row.items[j]
                        val f = if (t.key == ev.focusKey) ev.focus else null
                        if (row.landscape) {
                            LandscapeCard(
                                t.backdrop ?: t.poster, CardInfo(t.title, t.meta, progress = t.progress),
                                width = CardSize.LandscapeSectionW, height = CardSize.LandscapeSectionH, focus = f,
                                onFocus = { ev.onFocus(t) }, onLongClick = { ev.onLong(t) },
                            ) { ev.onOpen(t) }
                        } else {
                            PosterCard(
                                t.poster, CardInfo(t.title, t.meta, sources = listOf(t.source), watched = ev.watched(t)),
                                width = CardSize.RowPosterW, height = CardSize.RowPosterH, focus = f,
                                onFocus = { ev.onFocus(t) }, onLongClick = { ev.onLong(t) },
                            ) { ev.onOpen(t) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The section hero's text at the mockup's sizes (title 92 px, synopsis 26 px; tighter than Home's hero so the first
 * row's heading stays at y = 634).
 */
@Composable
private fun SectionHeroText(d: HeroData) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (d.source.isNotEmpty()) SourceBadge(d.source)
            if (d.eyebrow.isNotEmpty()) Text(d.eyebrow.uppercase(), style = O5Type.Eyebrow, maxLines = 1)
        }
        Text(d.title, style = O5Type.SectionHeroTitle.copy(fontSize = 92.sp, lineHeight = 88.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(vertical = 16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            d.year?.let { Text(it, style = O5Type.Meta, maxLines = 1) }
            d.rating?.let { Text("★ $it", style = O5Type.Meta, color = O5.Rating, maxLines = 1) }
            d.runtime?.let { Text(it, style = O5Type.Meta, maxLines = 1) }
            d.genres.take(2).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { Text(it, style = O5Type.Meta, maxLines = 1) }
        }
        Text(d.synopsis?.takeIf { it.isNotBlank() } ?: " ", style = O5Type.Synopsis.copy(fontSize = 26.sp, lineHeight = 38.sp), maxLines = 2, minLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 16.dp).widthIn(max = 980.dp))
    }
}

/** Caption of a grid poster: "2024 · Drama" (catalog) or the category (local data). */
fun gridMeta(t: Title): String? = if (t.year != null || t.genres.isNotEmpty()) listOfNotNull(t.year, t.genres.firstOrNull()).joinToString(" · ") else t.meta

/**
 * The Browse poster grid (spec section 7: 180 x 270, 8 per row, gaps 34 x 30) directly under the sub-menu row.
 * [onNearEnd] runs as focus nears the loaded end (the next page).
 */
@Composable
fun BrowseGridView(
    items: List<Title>, ev: TitleEvents, hasMore: Boolean, onNearEnd: () -> Unit, modifier: Modifier = Modifier,
    message: String? = null, state: LazyGridState = rememberLazyGridState(),
) {
    Box(modifier.fillMaxSize().testTag("movies-browse")) {
        if (message != null && items.isEmpty()) Text(message, style = O5Type.Body, modifier = Modifier.padding(start = O5Space.Side, top = GridTop + 20.dp))
        PosterGrid(state) {
            itemsIndexed(items, key = { _, t -> t.key }) { i, t ->
                if (hasMore && i >= items.size - 24) LaunchedEffect(items.size) { onNearEnd() }
                PosterCard(
                    t.poster, CardInfo(t.title, gridMeta(t), sources = listOf(t.source), watched = ev.watched(t), progress = t.progress?.takeIf { !t.show }),
                    focus = if (t.key == ev.focusKey) ev.focus else null, onFocus = { ev.onFocus(t) }, onLongClick = { ev.onLong(t) },
                ) { ev.onOpen(t) }
            }
        }
    }
}

@Composable
private fun PosterGrid(state: LazyGridState, content: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit) {
    // 8 x 180 + 7 x 30 = 1650 px from the 96 px edge
    LazyVerticalGrid(
        GridCells.Fixed(8), Modifier.fillMaxSize().padding(top = GridTop - FocusRoom), state = state,
        contentPadding = PaddingValues(start = O5Space.Side, end = 1920.dp - O5Space.Side - 1650.dp, top = FocusRoom, bottom = 160.dp),
        horizontalArrangement = Arrangement.spacedBy(30.dp), verticalArrangement = Arrangement.spacedBy(34.dp), content = content,
    )
}

/** Collections: a grid of collection posters (catalog), or of the provider's categories (local data). */
@Composable
fun CollectionsGridView(
    colls: List<Coll>, shows: Boolean, onFocus: (Coll) -> Unit, onOpen: (Coll) -> Unit, onLong: (Coll) -> Unit = {},
    modifier: Modifier = Modifier, message: String? = null, focusId: String? = null, focus: SafeFocus? = null,
    state: LazyGridState = rememberLazyGridState(),
) {
    Box(modifier.fillMaxSize().testTag("movies-collections")) {
        if (message != null && colls.isEmpty()) Text(message, style = O5Type.Body, modifier = Modifier.padding(start = O5Space.Side, top = GridTop + 20.dp))
        PosterGrid(state) {
            itemsIndexed(colls, key = { _, c -> c.id }) { _, c ->
                val what = if (c.category != null) "titles" else if (shows) "shows" else "films"
                PosterCard(
                    c.poster, CardInfo(c.name, if (c.count > 0) "${c.count} " + (if (c.count == 1) what.removeSuffix("s") else what) else null,
                        sources = listOfNotNull(c.source.takeIf { it.isNotEmpty() })),
                    focus = if (c.id == focusId) focus else null, onFocus = { onFocus(c) }, onLongClick = { onLong(c) },
                ) { onOpen(c) }
            }
        }
    }
}

/** Summary of a collection for its page: watched count and the next title to watch. */
data class CollSummary(val watched: Int, val total: Int, val next: Title?, val nextIndex: Int)

fun collSummary(items: List<Title>, watched: (Title) -> Boolean): CollSummary {
    val w = items.count(watched)
    val i = items.indexOfFirst { !watched(it) }
    return CollSummary(w, items.size, items.getOrNull(i), i)
}

/**
 * A collection's page (mockup "Movies · Collection"): eyebrow, name, meta, the watched bar, Continue / Play from the
 * first, then its titles in release order with their numbers.
 */
@Composable
fun CollectionView(
    coll: Coll, items: List<Title>, shows: Boolean, ev: TitleEvents, onPlay: (Title) -> Unit, modifier: Modifier = Modifier,
    message: String? = null, playFocus: SafeFocus? = null,
) {
    val sum = collSummary(items, ev.watched)
    val noun = if (shows) "shows" else "films"
    Box(modifier.fillMaxSize().testTag("movies-collection")) {
        Column(Modifier.padding(start = O5Space.Side, top = 196.dp).width(1200.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("COLLECTIONS ›", style = O5Type.Eyebrow, maxLines = 1)
                items.map { it.source }.ifEmpty { listOfNotNull(coll.source.takeIf { it.isNotEmpty() }) }.map { if (it == "iptv") "iptv" else "local" }.distinct()
                    .sortedBy { if (it == "local") 0 else 1 }.forEach { SourceBadge(it) }
            }
            Text(coll.name, style = O5Type.SectionHeroTitle.copy(fontSize = 96.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 14.dp))
            val meta = listOfNotNull("${items.size.takeIf { it > 0 } ?: coll.count} $noun", Titles.years(items), Titles.topGenres(items).joinToString(" · ").ifEmpty { null })
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) { meta.forEach { Text(it, style = O5Type.Meta, maxLines = 1) } }
            if (items.isNotEmpty()) {
                Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    ProgressBar(sum.watched.toFloat() / sum.total, Modifier.width(420.dp))
                    Text("${sum.watched} of ${sum.total} watched" + (sum.next?.let { " · next up: ${if (shows) "show" else "film"} ${sum.nextIndex + 1}" } ?: ""),
                        style = O5Type.Body.copy(fontSize = 22.sp), maxLines = 1)
                }
            }
            if (message != null) Text(message, style = O5Type.Body, modifier = Modifier.padding(top = 18.dp))
            if (items.isNotEmpty()) {
                Row(Modifier.padding(top = 30.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    val next = sum.next
                    if (next != null && sum.watched > 0) O5Button("Continue · ${next.title}", icon = Icons.Filled.PlayArrow, primary = true, compact = true, focus = playFocus) { onPlay(next) }
                    else O5Button("Play", icon = Icons.Filled.PlayArrow, primary = true, compact = true, focus = playFocus) { onPlay(items.first()) }
                    if (sum.watched > 0) O5Button("Play from ${if (shows) "show" else "film"} 1", icon = Icons.Filled.Refresh, compact = true) { onPlay(items.first()) }
                }
            }
        }
        O5Row(null, Modifier.padding(top = 636.dp - FocusRoom), gap = 32.dp) {
            items(items.size, key = { items[it].key }) { i ->
                val t = items[i]
                val w = ev.watched(t)
                val meta = listOfNotNull(t.year, if (t.source == "iptv") "IPTV" else "Local", if (w) "watched" else t.meta?.takeIf { it.endsWith("left") }).joinToString(" · ")
                PosterCard(
                    t.poster, CardInfo(t.title, meta, watched = w, progress = t.progress?.takeIf { !w && !t.show }, number = i + 1),
                    width = CardSize.CollectionPosterW, height = CardSize.CollectionPosterH, focus = if (t.key == ev.focusKey) ev.focus else null,
                    onFocus = { ev.onFocus(t) }, onLongClick = { ev.onLong(t) },
                ) { ev.onOpen(t) }
            }
        }
    }
}

/** Muted note for a page (loading, nothing found, needs the home server). */
@Composable
fun PageNote(text: String, modifier: Modifier = Modifier) {
    Text(text, style = O5Type.Body, color = O5.Muted, modifier = modifier)
}
