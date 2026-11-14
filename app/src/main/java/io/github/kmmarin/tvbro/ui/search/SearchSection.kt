// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kmmarin.tvbro.AppModel
import io.github.kmmarin.tvbro.R
import io.github.kmmarin.tvbro.data.DlRepo
import io.github.kmmarin.tvbro.data.ProgState
import io.github.kmmarin.tvbro.data.ProgTime
import io.github.kmmarin.tvbro.data.SearchStore
import io.github.kmmarin.tvbro.data.VodItem
import io.github.kmmarin.tvbro.ui.SafeFocus
import io.github.kmmarin.tvbro.ui.hhmm
import io.github.kmmarin.tvbro.ui.live.CardPill
import io.github.kmmarin.tvbro.ui.live.ChannelArtCard
import io.github.kmmarin.tvbro.ui.orbit5.CardInfo
import io.github.kmmarin.tvbro.ui.orbit5.CardSize
import io.github.kmmarin.tvbro.ui.orbit5.O5
import io.github.kmmarin.tvbro.ui.orbit5.O5Row
import io.github.kmmarin.tvbro.ui.orbit5.O5Shape
import io.github.kmmarin.tvbro.ui.orbit5.O5Space
import io.github.kmmarin.tvbro.ui.orbit5.O5Type
import io.github.kmmarin.tvbro.ui.orbit5.PosterCard
import io.github.kmmarin.tvbro.ui.orbit5.o5Focus
import io.github.kmmarin.tvbro.ui.rememberNow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A channel found by the search. */
@Immutable
data class ChanHit(val key: String, val name: String, val logo: String?, val now: String?, val progress: Float?, val fav: Boolean)

/** A programme found by the search: on now (Live now), later, or past with catch-up. */
@Immutable
data class ProgHit(val id: String, val channel: String, val logo: String?, val title: String, val meta: String, val progress: Float?, val state: ProgState)

/** A film or show found by the search. */
@Immutable
data class VodHit(val key: String, val title: String, val poster: String?, val meta: String?, val offline: Boolean)

/** Search results grouped as the page shows them. [status]: "Results for …", "No matches …", "Searching…". */
@Immutable
data class SearchGroups(
    val status: String, val channels: List<ChanHit> = emptyList(), val live: List<ProgHit> = emptyList(),
    val movies: List<VodHit> = emptyList(), val shows: List<VodHit> = emptyList(), val later: List<ProgHit> = emptyList(),
)

/** What the search page does (ids: channel key, programme id, title key). */
class SearchActions(
    val onQuery: (String) -> Unit = {}, val onDoneTyping: () -> Unit = {}, val onMic: () -> Unit = {},
    val onHistory: (String) -> Unit = {}, val onClearHistory: () -> Unit = {},
    val onChannel: (String) -> Unit = {}, val onChannelLong: (String) -> Unit = {}, val onChannelFocus: (String) -> Unit = {},
    val onProg: (String) -> Unit = {}, val onProgLong: (String) -> Unit = {}, val onProgFocus: (String) -> Unit = {},
    val onVod: (String) -> Unit = {}, val onVodLong: (String) -> Unit = {}, val onVodFocus: (String) -> Unit = {},
)

private val dayFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())

private fun airTime(start: Long, stop: Long, now: Long): String {
    val d = dayFmt.format(Date(start * 1000))
    return (if (d == dayFmt.format(Date(now * 1000))) "Today" else d) + " " + hhmm(start) + " – " + hhmm(stop)
}

/**
 * Search (top-bar magnifier) in direction 5, bound to the app: the same index search as before (channels, programmes,
 * films and shows; history; voice search), drawn as a big field and rows of cards. OK on a channel or a programme on
 * now watches it; OK on a later or catch-up programme opens its choices (watch catch-up, record, remind, series);
 * long press (Menu) opens the channel's, programme's or title's menu; yellow toggles My list for the focused item.
 */
@Composable
fun SearchSection(m: AppModel, onBack: () -> Unit) {
    BackHandler { onBack() }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { m.setSearch(it); m.rememberSearch(it) }
    }
    val speak = {
        try {
            launcher.launch(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
        } catch (e: Exception) { m.toast("Voice search is not available on this device") }
    }
    val ver = SearchStore.version.intValue
    LaunchedEffect(ver) { if (ver > 0) m.refreshSearch() }
    val now = rememberNow()
    val v = m.searchView
    // programme rows by id, for OK / menu / details
    val progs = remember(v) {
        v?.progs?.flatMap { (c, rows) -> rows.map { r -> "${c.key}|${r.p.start}" to (c to r) } }?.toMap() ?: emptyMap()
    }
    val vods = remember(v) { ((v?.movies ?: emptyList()) + (v?.series ?: emptyList())).associateBy { it.key } }
    val chans = remember(v) { v?.channels?.associateBy { it.key } ?: emptyMap() }
    var focusedProg by remember(v) { mutableStateOf<String?>(null) }
    val detail = focusedProg?.let { progs[it] }?.let { (c, r) ->
        val desc by produceState<String?>(null, r.p.key, r.p.start) { value = withContext(Dispatchers.IO) { m.progDesc(r.p) } }
        listOfNotNull(r.p.title, c.name, airTime(r.p.start, r.p.stop, now), desc).joinToString("  ·  ")
    }
    val groups = when {
        m.searchQ.isBlank() -> null
        v == null -> SearchGroups(if (SearchStore.building) "Searching (the search index is being built)…" else "Searching…")
        else -> {
            fun hit(c: io.github.kmmarin.tvbro.data.Channel, r: AppModel.ProgRow) = ProgHit(
                "${c.key}|${r.p.start}", c.name, c.logo, r.p.title,
                when (r.state) {
                    ProgState.NOW -> "${c.name} · ${ProgTime.minutesLeft(r.p.stop, now)} min left"
                    else -> airTime(r.p.start, r.p.stop, now) + " · " + c.name
                },
                if (r.state == ProgState.NOW) ProgTime.progress(r.p.start, r.p.stop, now) else null, r.state,
            )
            val all = v.progs.flatMap { (c, rows) -> rows.map { c to it } }
            val nothing = v.channels.isEmpty() && v.movies.isEmpty() && v.series.isEmpty() && v.progs.isEmpty()
            SearchGroups(
                status = (if (nothing) "No matches for “${v.q}”" else "Results for “${v.q}”") + "  ·  ${v.ms} ms" +
                    if (!v.indexReady) "  ·  the search index is not built yet (channels only)" else "",
                channels = v.channels.map { c ->
                    val cur = m.epg.nowNext(c, now).first
                    ChanHit(c.key, c.name, c.logo, cur?.title, cur?.let { ProgTime.progress(it.start, it.stop, now) }, c.key in m.favourites)
                },
                live = all.filter { it.second.state == ProgState.NOW }.map { (c, r) -> hit(c, r) },
                movies = v.movies.map { vodHit(it) }, shows = v.series.map { vodHit(it) },
                later = all.filter { it.second.state != ProgState.NOW }.sortedBy { it.second.p.start }.map { (c, r) -> hit(c, r) },
            )
        }
    }
    SearchView(
        query = m.searchQ, history = m.history, groups = groups, detail = detail,
        actions = SearchActions(
            onQuery = { m.setSearch(it) }, onDoneTyping = { m.rememberSearch() }, onMic = speak,
            onHistory = { h -> m.setSearch(h); m.rememberSearch(h) }, onClearHistory = { m.clearHistory() },
            onChannel = { k -> chans[k]?.let { m.rememberSearch(); m.play(it) } },
            onChannelLong = { k -> chans[k]?.let { m.openChannelMenu(it) } },
            onChannelFocus = { k -> focusedProg = null; m.rememberSearch(); chans[k]?.let { c -> m.favAction = { m.toggleFav(c) } } },
            onProg = { id ->
                progs[id]?.let { (c, r) -> if (r.state == ProgState.NOW) { m.rememberSearch(); m.play(c) } else { m.rememberSearch(); m.progActions(c, r) } }
            },
            onProgLong = { id -> progs[id]?.let { (c, r) -> m.progActions(c, r) } },
            onProgFocus = { id -> focusedProg = id; m.rememberSearch(); progs[id]?.let { (c, _) -> m.favAction = { m.toggleFav(c) } } },
            onVod = { k -> vods[k]?.let { m.rememberSearch(); m.openDetail(it) } },
            onVodLong = { k -> vods[k]?.let { io.github.kmmarin.tvbro.ui.vodMenu(m, it) } },
            onVodFocus = { k -> focusedProg = null; m.rememberSearch(); vods[k]?.let { item -> m.peekVod(item); m.favAction = { m.toggleFavKey(item) } } },
        ),
    )
}

private fun vodHit(x: VodItem) = VodHit(x.key, x.name, x.poster, x.rating?.let { "★ $it" }, DlRepo.has(x.key))

/**
 * The search page without the app model (the shot tests draw it): under the top bar, a mic button and a big field;
 * before typing the search history as chips; after, rows of cards (Channels, Live now, Movies, Shows, Later and
 * catch-up). [detail] is the focused programme's line (title, channel, time, description).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
fun SearchView(query: String, history: List<String>, groups: SearchGroups?, detail: String?, actions: SearchActions, autoFocus: Boolean = true) {
    val fieldFr = remember { SafeFocus() }
    LaunchedEffect(Unit) { if (autoFocus) { delay(150); repeat(8) { if (fieldFr.request()) return@LaunchedEffect; delay(50) } } }
    Column(Modifier.fillMaxSize().testTag("search")) {
        // Up from the results comes back to the field (not the mic, the nearest)
        Row(Modifier.padding(start = O5Space.Side, end = O5Space.Side, top = 150.dp).focusProperties { enter = { fieldFr.target() } }.focusGroup(),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(84.dp).o5Focus(CircleShape, 42.dp, scaleTo = 1.06f, onClick = actions.onMic).background(Color(0x1FFFFFFF), CircleShape).testTag("search-mic"),
                contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Image(painterResource(R.drawable.ic_mic), "Voice search", Modifier.size(38.dp), colorFilter = ColorFilter.tint(O5.Text))
            }
            Spacer(Modifier.width(24.dp))
            BigField(query, actions.onQuery, actions.onDoneTyping, fieldFr, Modifier.weight(1f))
        }
        Text(detail ?: groups?.status ?: "", style = O5Type.Body.copy(fontSize = 20.sp), color = if (detail != null) O5.MetaText else O5.Muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = O5Space.Side + 108.dp, end = O5Space.Side, top = 14.dp).height(30.dp))
        if (groups == null) History(history, actions)
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 10.dp, bottom = 140.dp)) {
            if (groups.channels.isNotEmpty()) item("ch") {
                O5Row("Channels  ${groups.channels.size}") {
                    items(groups.channels, key = { it.key }) { c ->
                        ChannelArtCard(c.logo, c.name, c.name, c.now ?: "No guide data", progress = c.progress, mark = if (c.fav) "★" else null,
                            onFocus = { actions.onChannelFocus(c.key) }, onLongClick = { actions.onChannelLong(c.key) }) { actions.onChannel(c.key) }
                    }
                }
            }
            if (groups.live.isNotEmpty()) item("live") { ProgRow("Live now  ${groups.live.size}", groups.live, actions) }
            if (groups.movies.isNotEmpty()) item("movies") { VodRow("Movies  ${groups.movies.size}", groups.movies, actions) }
            if (groups.shows.isNotEmpty()) item("shows") { VodRow("Shows  ${groups.shows.size}", groups.shows, actions) }
            if (groups.later.isNotEmpty()) item("later") { ProgRow("Later and catch-up  ${groups.later.size}", groups.later, actions) }
        }
    }
}

@Composable
private fun ProgRow(title: String, list: List<ProgHit>, a: SearchActions) {
    O5Row(title) {
        items(list, key = { it.id }) { p ->
            ChannelArtCard(p.logo, p.channel, p.title, p.meta, progress = p.progress,
                badge = {
                    when (p.state) {
                        ProgState.NOW -> CardPill("● LIVE", O5.Live)
                        ProgState.PAST_CATCHUP -> CardPill("Catch-up", Color(0xCC1B7FA3))
                        else -> CardPill("Upcoming", Color(0x99000000))
                    }
                },
                onFocus = { a.onProgFocus(p.id) }, onLongClick = { a.onProgLong(p.id) }) { a.onProg(p.id) }
        }
    }
}

@Composable
private fun VodRow(title: String, list: List<VodHit>, a: SearchActions) {
    O5Row(title, gap = O5Space.RowGapPoster) {
        items(list, key = { it.key }) { v ->
            PosterCard(v.poster, CardInfo(v.title, listOfNotNull(v.meta, if (v.offline) "Offline" else null).joinToString(" · ").ifEmpty { null }),
                width = CardSize.RowPosterW, height = CardSize.RowPosterH,
                onFocus = { a.onVodFocus(v.key) }, onLongClick = { a.onVodLong(v.key) }) { a.onVod(v.key) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun History(history: List<String>, a: SearchActions) {
    Column(Modifier.padding(start = O5Space.Side, end = O5Space.Side, top = 20.dp)) {
        Text("Recent searches", style = O5Type.RowHeading)
        if (history.isEmpty()) Text("Your searches will appear here. Search channels, programmes, movies and shows.", style = O5Type.Body.copy(fontSize = 20.sp),
            color = O5.Muted, modifier = Modifier.padding(top = 14.dp))
        else FlowRow(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            history.forEach { h -> Chip(h) { a.onHistory(h) } }
            Chip("Clear history", muted = true) { a.onClearHistory() }
        }
    }
}

@Composable
private fun Chip(text: String, muted: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.o5Focus(O5Shape.Pill, 30.dp, scaleTo = 1.05f, onClick = onClick)
            .background(if (muted) Color.Transparent else O5.ChipBg, O5Shape.Pill).border(1.dp, O5.ChipRing, O5Shape.Pill)
            .padding(horizontal = 24.dp, vertical = 11.dp),
    ) { Text(text, style = O5Type.SubPill, color = if (muted) O5.Muted else O5.Text, maxLines = 1) }
}

/**
 * The big search field: a focusable box (OK starts typing and the on-screen keyboard opens; letters from a remote
 * keyboard go straight in); while typing, Down or the keyboard's Search key ends typing.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BigField(query: String, onQuery: (String) -> Unit, onDone: () -> Unit, focus: SafeFocus, modifier: Modifier) {
    val shape = RoundedCornerShape(42.dp)
    val text = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold, color = O5.Text)
    var editing by remember { mutableStateOf(false) }
    var refocus by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val q by rememberUpdatedState(query)
    LaunchedEffect(editing) {
        if (editing) { delay(50); runCatching { fr.requestFocus() }; kb?.show() }
        else if (refocus) { refocus = false; delay(50); repeat(6) { if (focus.request()) return@LaunchedEffect; delay(40) } }
    }
    val stop = { editing = false; kb?.hide(); onDone() }
    val frame: @Composable (Boolean, @Composable BoxScope.() -> Unit) -> Unit = { active, inner ->
        Row(Modifier.fillMaxWidth().height(84.dp).background(Color(0x17FFFFFF), shape).border(if (active) 3.dp else 1.dp, if (active) O5.Accent else Color(0x33FFFFFF), shape)
            .padding(horizontal = 30.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, tint = O5.Muted, modifier = Modifier.size(38.dp))
            Spacer(Modifier.width(18.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text("Search channels, programmes, movies and shows", style = text.copy(color = O5.Muted, fontWeight = FontWeight.Normal), maxLines = 1)
                inner()
            }
        }
    }
    if (editing) {
        BackHandler { refocus = true; stop() }
        BasicTextField(
            value = query, onValueChange = onQuery, singleLine = true, textStyle = text, cursorBrush = SolidColor(O5.Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { refocus = true; stop() }, onDone = { refocus = true; stop() }),
            modifier = modifier.focusRequester(fr).testTag("search-field")
                .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) { stop(); false } else false },
            decorationBox = { inner -> frame(true) { inner() } },
        )
    } else {
        Box(
            modifier
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (e.key == Key.Backspace || e.key == Key.Delete) return@onPreviewKeyEvent if (q.isNotEmpty()) { onQuery(q.dropLast(1)); true } else false
                    val ch = e.nativeKeyEvent.unicodeChar
                    if (ch >= 32 && !Character.isISOControl(ch)) { onQuery(q + ch.toChar()); true } else false
                }
                .o5Focus(shape, 42.dp, focus = focus, scaleTo = 1.02f) { editing = true }
                .testTag("search-field"),
        ) { frame(false) { if (query.isNotEmpty()) Text(query, style = text, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
    }
}
