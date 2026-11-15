// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.discover

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kmmarin.tvbro.R
import io.github.kmmarin.tvbro.orbit.DiscoverApi
import io.github.kmmarin.tvbro.orbit.DiscoverPerson
import io.github.kmmarin.tvbro.orbit.DiscoverTitle
import io.github.kmmarin.tvbro.ui.Logos
import io.github.kmmarin.tvbro.ui.SafeFocus
import io.github.kmmarin.tvbro.ui.orbit5.CardInfo
import io.github.kmmarin.tvbro.ui.orbit5.CardSize
import io.github.kmmarin.tvbro.ui.orbit5.FocusRoom
import io.github.kmmarin.tvbro.ui.orbit5.ImgSize
import io.github.kmmarin.tvbro.ui.orbit5.NetImage
import io.github.kmmarin.tvbro.ui.orbit5.O5
import io.github.kmmarin.tvbro.ui.orbit5.O5Button
import io.github.kmmarin.tvbro.ui.orbit5.O5Row
import io.github.kmmarin.tvbro.ui.orbit5.O5Space
import io.github.kmmarin.tvbro.ui.orbit5.O5Type
import io.github.kmmarin.tvbro.ui.orbit5.PhonePosterCard
import io.github.kmmarin.tvbro.ui.orbit5.PhoneRowHeading
import io.github.kmmarin.tvbro.ui.orbit5.PhoneTouch
import io.github.kmmarin.tvbro.ui.orbit5.PhoneType
import io.github.kmmarin.tvbro.ui.orbit5.PosterCard
import io.github.kmmarin.tvbro.ui.orbit5.StatusBadge
import io.github.kmmarin.tvbro.ui.orbit5.o5Focus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** What the Discover search does (the screen binds it to [DiscoverFeed]; the shots pass stand-ins). */
class SearchActions(
    /** The field's text changed (the search runs once it settles). */
    val onType: (String) -> Unit = {},
    /** Search now (the keyboard's Search key). */
    val onSubmit: (String) -> Unit = {},
    val onClear: () -> Unit = {},
    val onMic: () -> Unit = {},
    /** Focus is near the end of the results. */
    val more: () -> Unit = {},
    /** OK on a person (null: back to all results). */
    val person: (DiscoverPerson?) -> Unit = {},
)

// ---------------------------------------------------------------- image prefetch

/** Loads the artwork of the next [ahead] cards past the last visible one of a lazy row, so cards show with their picture. */
@Composable
fun PrefetchImages(state: LazyListState, urls: List<String?>, target: Int = ImgSize.POSTER, ahead: Int = 8) {
    val list by rememberUpdatedState(urls)
    LaunchedEffect(state) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.distinctUntilChanged().collect { last ->
            if (last < 0) return@collect
            val l = list
            for (i in last + 1..minOf(l.size - 1, last + ahead)) l[i]?.let { u -> launch { Logos.load(u, target) } }
        }
    }
}

/** The same for a grid ([offset]: grid items before the first card). */
@Composable
fun PrefetchGridImages(state: LazyGridState, urls: List<String?>, offset: Int, target: Int = ImgSize.POSTER, ahead: Int = 16) {
    val list by rememberUpdatedState(urls)
    LaunchedEffect(state) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.distinctUntilChanged().collect { last ->
            if (last < 0) return@collect
            val l = list
            val from = maxOf(0, last - offset + 1)
            for (i in from..minOf(l.size - 1, from + ahead - 1)) l[i]?.let { u -> launch { Logos.load(u, target) } }
        }
    }
}

// ---------------------------------------------------------------- TV search field

/**
 * The search field of Discover's sub-menu row (TV): a mic button and a field. OK on the field starts typing (the
 * on-screen keyboard opens); letters from a remote keyboard go straight in, Backspace deletes. While typing, Down or
 * the keyboard's Search key ends typing; Back ends typing (a second Back clears the search, see the screen).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DiscoverSearchField(query: String, a: SearchActions, focus: SafeFocus, modifier: Modifier = Modifier, width: Dp = 520.dp) {
    val shape = RoundedCornerShape(30.dp)
    val text = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = O5.Text)
    var editing by remember { mutableStateOf(false) }
    var refocus by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val q by rememberUpdatedState(query)
    LaunchedEffect(editing) {
        if (editing) { delay(50); runCatching { fr.requestFocus() }; kb?.show() }
        else if (refocus) { refocus = false; delay(50); repeat(6) { if (focus.request()) return@LaunchedEffect; delay(40) } }
    }
    val stop = { editing = false; kb?.hide() }
    val frame: @Composable (Boolean, @Composable BoxScope.() -> Unit) -> Unit = { active, inner ->
        Row(
            Modifier.width(width).height(54.dp).background(O5.ChipBgStrong, shape).border(if (active) 2.dp else 1.dp, if (active) O5.Accent else O5.ChipRing, shape)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, null, tint = O5.Muted, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text("Search films, shows and people", style = text.copy(color = O5.Muted, fontWeight = FontWeight.Normal), maxLines = 1)
                inner()
            }
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier.size(54.dp).o5Focus(CircleShape, 27.dp, scaleTo = 1.06f, onClick = a.onMic).background(O5.ChipBgStrong, CircleShape)
                .border(1.dp, O5.ChipRing, CircleShape).testTag("discover-mic"),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Image(painterResource(R.drawable.ic_mic), "Voice search", Modifier.size(26.dp), colorFilter = ColorFilter.tint(O5.Text))
        }
        if (editing) {
            BackHandler { refocus = true; stop() }
            BasicTextField(
                value = query, onValueChange = { a.onType(it.take(100)) }, singleLine = true, textStyle = text, cursorBrush = SolidColor(O5.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { refocus = true; stop(); a.onSubmit(q) }, onDone = { refocus = true; stop(); a.onSubmit(q) }),
                modifier = Modifier.focusRequester(fr).testTag("discover-search")
                    .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) { stop(); false } else false },
                decorationBox = { inner -> frame(true) { inner() } },
            )
        } else {
            Box(
                Modifier
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        if (e.key == Key.Backspace || e.key == Key.Delete) return@onPreviewKeyEvent if (q.isNotEmpty()) { a.onType(q.dropLast(1)); true } else false
                        val ch = e.nativeKeyEvent.unicodeChar
                        if (ch >= 32 && !Character.isISOControl(ch) && q.length < 100) { a.onType(q + ch.toChar()); true } else false
                    }
                    .o5Focus(shape, 30.dp, focus = focus, scaleTo = 1.03f) { editing = true }
                    .testTag("discover-search"),
            ) { frame(false) { if (query.isNotEmpty()) Text(query, style = text, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
        }
    }
}

// ---------------------------------------------------------------- TV results

/** Top of the search results under the sub-menu row. */
private val ResultsTop = 196.dp

/**
 * Search results (TV): "Results for …", the People row (OK on a person shows what they are known for), then a poster
 * grid of films and shows with their status badges; more pages load when focus nears the end.
 */
@Composable
fun TvSearchResults(
    s: DiscoverSearch, a: DiscoverActions, sa: SearchActions, grid: LazyGridState,
    onFocus: (DiscoverTitle) -> Unit, focusKey: String?, focus: SafeFocus,
) {
    val list = s.person?.knownFor ?: s.results
    val headerItems = if (s.person == null && s.people.isNotEmpty()) 2 else 1
    PrefetchGridImages(grid, list.map { it.poster }, headerItems)
    val backFr = remember { SafeFocus() }
    LaunchedEffect(s.person?.id) { if (s.person != null) repeat(8) { delay(60); if (backFr.request()) return@LaunchedEffect } }
    // 8 x 180 + 7 x 30 = 1650 px from the 96 px edge
    LazyVerticalGrid(
        GridCells.Fixed(8), Modifier.fillMaxSize().padding(top = ResultsTop - FocusRoom).testTag("discover-results"), state = grid,
        contentPadding = PaddingValues(start = O5Space.Side, end = 1920.dp - O5Space.Side - 1650.dp, top = FocusRoom, bottom = 200.dp),
        horizontalArrangement = Arrangement.spacedBy(30.dp), verticalArrangement = Arrangement.spacedBy(30.dp),
    ) {
        item(key = "head", span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                if (s.person != null) {
                    O5Button("All results", icon = Icons.AutoMirrored.Filled.ArrowBack, compact = true, focus = backFr) { sa.person(null) }
                    Text("Known for: ${s.person.name}", style = O5Type.RowHeading, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Text("Results for “${s.q}”", style = O5Type.RowHeading, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    s.total?.takeIf { s.page >= 1 }?.let { Text("$it found", style = O5Type.CardMeta, maxLines = 1) }
                    if (s.stale) Text("Seerr cannot be reached: earlier results", style = O5Type.CardMeta, color = O5.Rating, maxLines = 1)
                }
            }
        }
        if (s.person == null && s.people.isNotEmpty()) item(key = "people", span = { GridItemSpan(maxLineSpan) }) {
            O5Row("People", Modifier.padding(top = 6.dp), gap = 30.dp, startPadding = 0.dp) {
                items(s.people, key = { "p" + it.id }) { p -> PersonCard(p) { sa.person(p) } }
            }
        }
        itemsIndexed(list, key = { _, t -> "s" + t.key }) { i, x ->
            if (s.person == null && s.hasMore && i >= list.size - DiscoverLogic.SEARCH_NEAR_END) LaunchedEffect(list.size) { sa.more() }
            val t = a.current(x)
            val b = DiscoverApi.badge(t)
            Column {
                PosterCard(
                    t.poster, CardInfo(t.title, meta(t)), focus = if (t.key == focusKey) focus else null,
                    onFocus = { onFocus(t) }, onLongClick = { a.more(t) },
                ) { a.open(t) }
                if (b != null) StatusBadge(b, Modifier.padding(top = 8.dp).width(CardSize.GridPosterW), small = true)
            }
        }
        val note = when {
            s.loading && list.isEmpty() -> "Searching…"
            s.error != null -> s.error
            !s.loading && list.isEmpty() && (s.person != null || s.people.isEmpty()) ->
                if (s.person != null) "Seerr lists no films or shows for ${s.person.name}." else "Seerr found nothing for “${s.q}”."
            else -> null
        }
        if (note != null) item(key = "note", span = { GridItemSpan(maxLineSpan) }) { Text(note, style = O5Type.Body) }
    }
}

/** A person (TV): a round photo, the name and two titles they are known for. */
@Composable
private fun PersonCard(p: DiscoverPerson, onClick: () -> Unit) {
    Column(Modifier.width(170.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(150.dp).o5Focus(CircleShape, 75.dp, scaleTo = 1.06f, onClick = onClick).clip(CircleShape).background(Color(0xFF1B2133)).testTag("person-${p.id}")) {
            Face(p.name, p.photo, 150.dp, 54.sp)
        }
        Text(p.name, style = O5Type.GridTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        knownFor(p)?.let { Text(it, style = O5Type.GridMeta, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) }
    }
}

/** A round face: the photo, else the first letter of the name. */
@Composable
internal fun Face(name: String, photo: String?, size: Dp, letter: androidx.compose.ui.unit.TextUnit) {
    NetImage(photo, ImgSize.AVATAR, Modifier.size(size), ContentScale.Crop) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), color = O5.Text, fontSize = letter, fontWeight = FontWeight.Bold)
        }
    }
}

internal fun knownFor(p: DiscoverPerson) = p.knownFor.take(2).joinToString(", ") { it.title }.ifEmpty { null }

// ---------------------------------------------------------------- phone

/** The phone's search field (under the chips): type, the keyboard's Search key searches at once; × clears. */
@Composable
fun PhoneSearchField(query: String, a: SearchActions, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    val text = TextStyle(fontSize = 15.sp, color = O5.Text)
    BasicTextField(
        value = query, onValueChange = { a.onType(it.take(100)) }, singleLine = true, textStyle = text, cursorBrush = SolidColor(O5.Accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { a.onSubmit(query) }),
        modifier = modifier.fillMaxWidth().testTag("discover-search"),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = PhoneTouch).background(Color(0x1AFFFFFF), shape).padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, null, tint = O5.Muted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text("Search films, shows and people", style = text.copy(color = O5.Muted), maxLines = 1)
                    inner()
                }
                if (query.isNotEmpty()) Box(Modifier.size(PhoneTouch).clip(CircleShape).clickable(onClick = a.onClear), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, "Clear the search", tint = O5.Muted, modifier = Modifier.size(20.dp))
                }
                else Box(Modifier.size(PhoneTouch).clip(CircleShape).clickable(onClick = a.onMic), contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.Image(painterResource(R.drawable.ic_mic), "Voice search", Modifier.size(20.dp), colorFilter = ColorFilter.tint(O5.Muted))
                }
            }
        },
    )
}

/** Search results on a phone, as items of the page's list: People, then a poster grid of [cols] columns. */
@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.phoneSearchResults(s: DiscoverSearch, a: DiscoverActions, sa: SearchActions, cols: Int) {
    val list = s.person?.knownFor ?: s.results
    item(key = "s-head") {
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (s.person != null) {
                Box(Modifier.size(PhoneTouch).clip(CircleShape).clickable { sa.person(null) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "All results", tint = O5.Text, modifier = Modifier.size(22.dp))
                }
                Text("Known for: ${s.person.name}", style = PhoneType.RowHeading, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text("Results for “${s.q}”", style = PhoneType.RowHeading, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                s.total?.let { Text("$it found", style = PhoneType.Meta, maxLines = 1) }
            }
        }
    }
    if (s.person == null && s.people.isNotEmpty()) item(key = "s-people") {
        Column(Modifier.padding(bottom = 16.dp)) {
            PhoneRowHeading("People")
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(s.people, key = { "p" + it.id }) { p ->
                    Column(Modifier.width(84.dp).clip(RoundedCornerShape(10.dp)).combinedClickable { sa.person(p) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(72.dp).clip(CircleShape).background(Color(0xFF1B2133))) { Face(p.name, p.photo, 72.dp, 26.sp) }
                        Text(p.name, style = PhoneType.CardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        knownFor(p)?.let { Text(it, style = PhoneType.CardMeta, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
    val chunks = list.chunked(cols)
    items(chunks.size, key = { "s-row-$it-" + chunks[it].first().key }) { r ->
        if (s.person == null && s.hasMore && r >= chunks.size - 3) LaunchedEffect(list.size) { sa.more() }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (c in 0 until cols) {
                val x = chunks[r].getOrNull(c)
                Box(Modifier.weight(1f)) {
                    if (x != null) {
                        val t = a.current(x)
                        val b = DiscoverApi.badge(t)
                        androidx.compose.foundation.layout.BoxWithConstraints {
                            PhonePosterCard(t.poster, t.title, meta(t), width = maxWidth, height = maxWidth * 1.5f,
                                below = b?.let { { StatusBadge(it, Modifier.padding(top = 5.dp), small = true) } }, onLongClick = { a.more(t) }) { a.open(t) }
                        }
                    }
                }
            }
        }
    }
    val note = when {
        s.loading && list.isEmpty() -> "Searching…"
        s.error != null -> s.error
        !s.loading && list.isEmpty() && (s.person != null || s.people.isEmpty()) ->
            if (s.person != null) "Seerr lists no films or shows for ${s.person.name}." else "Seerr found nothing for “${s.q}”."
        else -> null
    }
    if (note != null) item(key = "s-note") { Text(note, style = PhoneType.Body, modifier = Modifier.padding(20.dp)) }
}
