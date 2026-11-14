// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.movies

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kmmarin.tvbro.ui.orbit5.BrowseControls
import io.github.kmmarin.tvbro.ui.orbit5.BrowseSource
import io.github.kmmarin.tvbro.ui.orbit5.HeroData
import io.github.kmmarin.tvbro.ui.orbit5.ImgSize
import io.github.kmmarin.tvbro.ui.orbit5.NetImage
import io.github.kmmarin.tvbro.ui.orbit5.O5
import io.github.kmmarin.tvbro.ui.orbit5.PhoneBottomFade
import io.github.kmmarin.tvbro.ui.orbit5.PhoneButton
import io.github.kmmarin.tvbro.ui.orbit5.PhoneChip
import io.github.kmmarin.tvbro.ui.orbit5.PhoneChipRow
import io.github.kmmarin.tvbro.ui.orbit5.PhoneLandscapeCard
import io.github.kmmarin.tvbro.ui.orbit5.PhoneRowHeading
import io.github.kmmarin.tvbro.ui.orbit5.PhoneSegmented
import io.github.kmmarin.tvbro.ui.orbit5.PhoneSourceBadge
import io.github.kmmarin.tvbro.ui.orbit5.PhoneTouch
import io.github.kmmarin.tvbro.ui.orbit5.PhoneType
import io.github.kmmarin.tvbro.ui.orbit5.ProgressBar
import io.github.kmmarin.tvbro.ui.orbit5.SubMenuRow
import io.github.kmmarin.tvbro.ui.orbit5.bleed
import io.github.kmmarin.tvbro.ui.shell.LocalPhoneInsets

/*
 * Movies and Shows on a phone (spec section 13, mockup "Phone · 5b" Movies · Browse): the sub-menu as scrolling chips,
 * Browse's source toggle and genre / sort chips, a 3-column poster grid, Recommended rows and Collections. Plain
 * views (data in, events out) like MoviesViews, so the shot tests draw them; MoviesShowsSection binds them.
 * Everything scrolls (no D-pad focus); tap opens, long press opens the title's menu.
 */

/** Columns of the phone poster grid: 3 upright, more when there is room (a phone on its side). */
fun phoneGridColumns(width: Dp): Int = maxOf(3, ((width.value - 27f) / 133f).toInt())

/** Poster height on a phone (2:3). */
private fun posterH(w: Dp) = w * 1.5f

/**
 * The top of the phone Movies / Shows page: the sub-menu chips, then on Browse the source toggle `All | Local | IPTV`
 * and the genre / sort chips with the title count.
 */
@Composable
fun PhoneMoviesControls(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit, browse: BrowseControls?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        SubMenuRow(tabs, selected, onSelect)
        if (browse != null) {
            PhoneSegmented(BrowseSource.values().map { it.label }, browse.source.ordinal, { browse.onSource(BrowseSource.values()[it]) },
                Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 2.dp))
            PhoneChipRow {
                PhoneChip(browse.genre, false, trailing = Icons.Filled.KeyboardArrowDown, onClick = browse.onGenre)
                PhoneChip("Sort: " + browse.sort, false, trailing = Icons.Filled.KeyboardArrowDown, onClick = browse.onSort)
                browse.count?.let { Text(it, fontSize = 12.sp, color = O5.Muted, maxLines = 1, modifier = Modifier.padding(start = 4.dp, end = 12.dp)) }
            }
        }
    }
}

/** The page's backdrop on Browse and Collections: the mesh and artwork in the top 360 px, faded into the page. */
@Composable
fun PhoneTopBackdrop(alpha: Float = 0.5f, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxWidth().height(360.dp)) {
        Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }, content = content)
        PhoneBottomFade()
    }
}

/** A poster in the phone grid: [w] wide (2:3), source badges and the watched mark inside, a one-line caption. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhoneGridPoster(
    image: String?, title: String, w: Dp, modifier: Modifier = Modifier, meta: String? = null, sources: List<String> = emptyList(),
    watched: Boolean = false, progress: Float? = null, number: Int? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
) {
    Column(modifier.width(w).clip(RoundedCornerShape(8.dp)).combinedClickable(onLongClick = onLongClick, onClick = onClick)) {
        Box(Modifier.size(w, posterH(w)).clip(RoundedCornerShape(8.dp)).background(Color(0xFF111111))) {
            NetImage(image, ImgSize.POSTER, Modifier.fillMaxSize(), ContentScale.Crop) { PosterNoArt(title) }
            if (sources.isNotEmpty()) Row(Modifier.align(Alignment.BottomStart).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                sources.forEach { PhoneSourceBadge(it) }
            }
            if (watched) Text("✓", Modifier.align(Alignment.TopEnd).padding(6.dp).background(O5.Watched, RoundedCornerShape(999.dp)).padding(horizontal = 6.dp),
                color = O5.Bg, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
            number?.let {
                Text("$it", Modifier.align(Alignment.TopStart).padding(6.dp).background(Color(0xB805060C), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            }
            progress?.let { ProgressBar(it, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 4.dp), height = 3.dp) }
        }
        Text(title, style = PhoneType.CardTitle.copy(fontSize = 11.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        meta?.let { Text(it, style = PhoneType.CardMeta, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun PosterNoArt(title: String) {
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1B2133), Color(0xFF0B0E18)))), contentAlignment = Alignment.TopStart) {
        Text(title.uppercase(), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 3,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp))
    }
}

/**
 * A phone grid (posters or collections): [header] spans the full width at the top (the controls), then the cells,
 * 20 px from the sides, 13 x 14 px apart, under the phone header and above the bottom bar.
 */
@Composable
private fun PhoneGrid(
    header: @Composable () -> Unit, message: String?, state: LazyGridState, tag: String,
    cells: LazyGridScope.(cellW: Dp) -> Unit,
) {
    val insets = LocalPhoneInsets.current
    BoxWithConstraints(Modifier.fillMaxSize().testTag(tag)) {
        val cols = phoneGridColumns(maxWidth)
        val cellW = (maxWidth - 40.dp - 13.dp * (cols - 1)) / cols
        LazyVerticalGrid(
            GridCells.Fixed(cols), Modifier.fillMaxSize(), state = state,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = insets.top, bottom = insets.bottom + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "controls", span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.bleed(20.dp)) { header() } }
            if (message != null) item(key = "message", span = { GridItemSpan(maxLineSpan) }) {
                Text(message, style = PhoneType.Body, modifier = Modifier.padding(vertical = 12.dp))
            }
            cells(cellW)
        }
    }
}

/** Browse on a phone: the controls, then the poster grid; [onNearEnd] runs near the loaded end (the next page). */
@Composable
fun PhoneBrowseView(
    items: List<Title>, ev: TitleEvents, hasMore: Boolean, onNearEnd: () -> Unit, header: @Composable () -> Unit,
    message: String? = null, state: LazyGridState = rememberLazyGridState(),
) {
    PhoneGrid(header, message?.takeIf { items.isEmpty() }, state, "phone-browse") { w ->
        itemsIndexed(items, key = { _, t -> t.key }) { i, t ->
            if (hasMore && i >= items.size - 12) LaunchedEffect(items.size) { onNearEnd() }
            PhoneGridPoster(
                t.poster, t.title, w, sources = listOf(t.source), watched = ev.watched(t), progress = t.progress?.takeIf { !t.show },
                onLongClick = { ev.onLong(t) },
            ) { ev.onOpen(t) }
        }
    }
}

/** Collections on a phone: the controls, then the collection posters with their size. */
@Composable
fun PhoneCollectionsView(
    colls: List<Coll>, shows: Boolean, onOpen: (Coll) -> Unit, onLong: (Coll) -> Unit, header: @Composable () -> Unit,
    message: String? = null, state: LazyGridState = rememberLazyGridState(),
) {
    PhoneGrid(header, message?.takeIf { colls.isEmpty() }, state, "phone-collections") { w ->
        itemsIndexed(colls, key = { _, c -> c.id }) { _, c ->
            val what = if (c.category != null) "titles" else if (shows) "shows" else "films"
            PhoneGridPoster(
                c.poster, c.name, w, meta = if (c.count > 0) "${c.count} " + (if (c.count == 1) what.removeSuffix("s") else what) else null,
                sources = listOfNotNull(c.source.takeIf { it.isNotEmpty() }), onLongClick = { onLong(c) },
            ) { onOpen(c) }
        }
    }
}

/**
 * Recommended on a phone: the featured title over its backdrop (eyebrow, title, meta, Play / Resume and My list), then
 * the rows (Continue watching as 200 x 112 cards, the others as 108 x 162 posters). The controls sit at the top over
 * the backdrop.
 */
@Composable
fun PhoneRecommendedView(
    hero: HeroData?, rows: List<TitleRow>, ev: TitleEvents, playLabel: String, inList: Boolean, onPlay: () -> Unit, onMyList: () -> Unit,
    header: @Composable () -> Unit, backdrop: @Composable BoxScope.() -> Unit, message: String? = null, state: LazyListState = rememberLazyListState(),
) {
    val insets = LocalPhoneInsets.current
    LazyColumn(Modifier.fillMaxSize().testTag("phone-recommended"), state = state, contentPadding = PaddingValues(bottom = insets.bottom + 20.dp)) {
        item(key = "hero") {
            Box(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(470.dp)) { Box(Modifier.fillMaxSize(), content = backdrop); PhoneBottomFade(170.dp) }
                Column(Modifier.padding(top = insets.top)) {
                    header()
                    Spacer(Modifier.height(170.dp))
                    if (hero != null) Column(Modifier.padding(horizontal = 20.dp).widthIn(max = 560.dp)) {
                        if (hero.eyebrow.isNotEmpty()) Text(hero.eyebrow.uppercase(), style = PhoneType.Eyebrow, maxLines = 1)
                        Text(hero.title, style = PhoneType.HeroTitle.copy(fontSize = 34.sp, lineHeight = 36.sp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
                        PhoneMeta(hero.year, hero.rating, hero.runtime, hero.genres.firstOrNull())
                        hero.synopsis?.takeIf { it.isNotBlank() }?.let {
                            Text(it, fontSize = 13.sp, lineHeight = 19.sp, color = Color(0xD1EBEEFF), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                        }
                        Row(Modifier.padding(top = 14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PhoneButton(playLabel, Modifier.weight(1f).testTag("phone-movies-play"), icon = Icons.Filled.PlayArrow, primary = true, onClick = onPlay)
                            PhoneButton("My list", Modifier.weight(1f), icon = if (inList) Icons.Filled.Check else Icons.Filled.Add, onClick = onMyList)
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
        if (message != null) item(key = "message") { Text(message, style = PhoneType.Body, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
        items(rows, key = { it.id }) { row ->
            Column(Modifier.padding(bottom = 22.dp)) {
                PhoneRowHeading(row.title)
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(row.items, key = { it.key }) { t ->
                        if (row.landscape) PhoneLandscapeCard(t.backdrop ?: t.poster, t.title, t.meta, progress = t.progress, onLongClick = { ev.onLong(t) }) { ev.onOpen(t) }
                        else PhoneGridPoster(t.poster, t.title, 108.dp, sources = listOf(t.source), watched = ev.watched(t), onLongClick = { ev.onLong(t) }) { ev.onOpen(t) }
                    }
                }
            }
        }
    }
}

/** The phone meta line (mockup `.p-meta`): year, rating in yellow, length, genre; 12.5 px muted, 9 px apart. */
@Composable
fun PhoneMeta(year: String?, rating: String?, length: String?, genre: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
        year?.takeIf { it.isNotBlank() }?.let { Text(it, style = PhoneType.Meta, maxLines = 1) }
        rating?.takeIf { it.isNotBlank() }?.let { Text("★ $it", style = PhoneType.Meta, color = O5.Rating, maxLines = 1) }
        length?.takeIf { it.isNotBlank() }?.let { Text(it, style = PhoneType.Meta, maxLines = 1) }
        genre?.takeIf { it.isNotBlank() }?.let { Text(it, style = PhoneType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/**
 * A collection's page on a phone: eyebrow, name, meta, the watched bar, Continue / Play (and Play from film 1), then its
 * titles in order as rows (number, poster, title, year and source).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhoneCollectionView(
    coll: Coll, items: List<Title>, shows: Boolean, ev: TitleEvents, onPlay: (Title) -> Unit, header: @Composable () -> Unit,
    backdrop: @Composable BoxScope.() -> Unit, message: String? = null,
) {
    val insets = LocalPhoneInsets.current
    val sum = collSummary(items, ev.watched)
    val noun = if (shows) "shows" else "films"
    Box(Modifier.fillMaxSize().testTag("phone-collection")) {
        PhoneTopBackdrop(0.8f, backdrop)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = insets.top, bottom = insets.bottom + 20.dp)) {
            item(key = "head") {
                Column {
                    header()
                    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 90.dp)) {
                        Text("COLLECTIONS ›", style = PhoneType.Eyebrow, maxLines = 1)
                        Text(coll.name, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = O5.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
                        val meta = listOfNotNull("${items.size.takeIf { it > 0 } ?: coll.count} $noun", Titles.years(items), Titles.topGenres(items).firstOrNull())
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) { meta.forEach { Text(it, style = PhoneType.Meta, maxLines = 1) } }
                        if (items.isNotEmpty()) {
                            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ProgressBar(sum.watched.toFloat() / sum.total, Modifier.width(120.dp), height = 4.dp)
                                Text("${sum.watched} of ${sum.total} watched", style = PhoneType.Meta, maxLines = 1)
                            }
                            val next = sum.next
                            Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (next != null && sum.watched > 0) PhoneButton("Continue · ${next.title}", Modifier.fillMaxWidth(), icon = Icons.Filled.PlayArrow, primary = true) { onPlay(next) }
                                else PhoneButton("Play", Modifier.fillMaxWidth(), icon = Icons.Filled.PlayArrow, primary = true) { onPlay(items.first()) }
                                if (sum.watched > 0) PhoneButton("Play from ${if (shows) "show" else "film"} 1", Modifier.fillMaxWidth(), icon = Icons.Filled.Refresh) { onPlay(items.first()) }
                            }
                        }
                        if (message != null) Text(message, style = PhoneType.Body, modifier = Modifier.padding(top = 12.dp))
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
            itemsIndexed(items, key = { _, t -> t.key }) { i, t ->
                val w = ev.watched(t)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = PhoneTouch).combinedClickable(onLongClick = { ev.onLong(t) }) { ev.onOpen(t) }
                        .padding(horizontal = 20.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("${i + 1}", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = O5.Muted, modifier = Modifier.width(20.dp))
                    Box(Modifier.size(54.dp, 81.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF111111))) {
                        NetImage(t.poster, ImgSize.POSTER, Modifier.fillMaxSize(), ContentScale.Crop) { PosterNoArt(t.title) }
                        t.progress?.takeIf { !w && !t.show }?.let { ProgressBar(it, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(4.dp), height = 3.dp) }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(t.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = O5.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(t.year, if (t.source == "iptv") "IPTV" else "Local", if (w) "watched" else t.meta?.takeIf { it.endsWith("left") }).joinToString(" · "),
                            style = PhoneType.CardMeta, maxLines = 1)
                    }
                    if (w) Text("✓", color = O5.Watched, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                }
            }
        }
    }
}
