// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.discover

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kmmarin.tvbro.AppModel
import io.github.kmmarin.tvbro.Screen
import io.github.kmmarin.tvbro.orbit.Badge
import io.github.kmmarin.tvbro.orbit.DiscoverApi
import io.github.kmmarin.tvbro.orbit.DiscoverRequest
import io.github.kmmarin.tvbro.orbit.DiscoverTitle
import io.github.kmmarin.tvbro.ui.FocusMemory
import io.github.kmmarin.tvbro.ui.MenuState
import io.github.kmmarin.tvbro.ui.SafeFocus
import io.github.kmmarin.tvbro.ui.home.HomeFeed
import io.github.kmmarin.tvbro.ui.home.Tile
import io.github.kmmarin.tvbro.ui.orbit5.BackdropSpec
import io.github.kmmarin.tvbro.ui.orbit5.CardInfo
import io.github.kmmarin.tvbro.ui.orbit5.CinematicBackdrop
import io.github.kmmarin.tvbro.ui.orbit5.ColorKeyActions
import io.github.kmmarin.tvbro.ui.orbit5.FocusRoom
import io.github.kmmarin.tvbro.ui.orbit5.ImgSize
import io.github.kmmarin.tvbro.ui.orbit5.KeyAction
import io.github.kmmarin.tvbro.ui.orbit5.LandscapeCard
import io.github.kmmarin.tvbro.ui.orbit5.NetImage
import io.github.kmmarin.tvbro.ui.orbit5.O5
import io.github.kmmarin.tvbro.ui.orbit5.O5Button
import io.github.kmmarin.tvbro.ui.orbit5.O5Row
import io.github.kmmarin.tvbro.ui.orbit5.O5Space
import io.github.kmmarin.tvbro.ui.orbit5.O5Type
import io.github.kmmarin.tvbro.ui.orbit5.PhoneButton
import io.github.kmmarin.tvbro.ui.orbit5.PhoneChip
import io.github.kmmarin.tvbro.ui.orbit5.PhoneChipRow
import io.github.kmmarin.tvbro.ui.orbit5.PhoneLandscapeCard
import io.github.kmmarin.tvbro.ui.orbit5.PhonePosterCard
import io.github.kmmarin.tvbro.ui.orbit5.PhoneRowHeading
import io.github.kmmarin.tvbro.ui.orbit5.PhoneType
import io.github.kmmarin.tvbro.ui.orbit5.PosterCard
import io.github.kmmarin.tvbro.ui.orbit5.StatusBadge
import io.github.kmmarin.tvbro.ui.orbit5.SubMenuRow
import io.github.kmmarin.tvbro.ui.shell.LocalIsPhone
import io.github.kmmarin.tvbro.ui.shell.LocalPhoneInsets
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Discover's sub-menu (spec section 11). */
val DiscoverTabs = listOf("For you", "Movies", "Shows", "Your requests")

/** What the Discover screen does with a title; the screen reads the freshest state through [current]. */
class DiscoverActions(
    val request: (DiscoverTitle) -> Unit = {},
    val play: (DiscoverTitle) -> Unit = {},
    /** Opens the title page. */
    val open: (DiscoverTitle) -> Unit = {},
    /** The More menu (blue, long press). */
    val more: (DiscoverTitle) -> Unit = {},
    val current: (DiscoverTitle) -> DiscoverTitle = { it },
    /** Focus reached card (index) of row (id): the row may load its next page. */
    val nearEnd: (String, Int) -> Unit = { _, _ -> },
    val canRequest: Boolean = true,
    val busy: String? = null,
    val retry: () -> Unit = {},
    val link: () -> Unit = {},
)

/**
 * Discover (top bar): trending, upcoming and popular titles and the account's requests through the household's Seerr
 * (server: the /v1/discover routes and /v1/requests; spec section 11), a search, and a title page. Rows load Seerr's next page
 * near their end; badges show Requested, Approved, Downloading N% · ~T and Available, and the requests are asked for
 * again every 20 s while something downloads (2 min otherwise). When Seerr is not set up it says where the owner
 * sets it up.
 */
@Composable
fun DiscoverSection(m: AppModel) {
    val feed = m.discover
    LaunchedEffect(m.orbit.link?.session, m.orbit.link?.profile) { feed.refresh() }
    LaunchedEffect(feed.state) {
        while (feed.state == DiscoverState.READY) { delay(if (feed.active) 20_000 else 120_000); feed.refreshRequests() }
    }
    val v = feed.title
    if (v != null) { DiscoverTitleScreen(m, feed, v); return }
    // the search runs once the typed text settles
    LaunchedEffect(feed.query) {
        val q = feed.query.trim()
        if (q.isEmpty()) return@LaunchedEffect
        delay(DiscoverLogic.SEARCH_SETTLE_MS)
        feed.runSearch(q)
    }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { feed.type(it); feed.runSearch(it, force = true) }
    }
    val sa = SearchActions(
        onType = { feed.type(it) }, onSubmit = { feed.runSearch(it, force = true) }, onClear = { feed.clearSearch() },
        onMic = {
            try {
                launcher.launch(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
            } catch (e: Exception) { m.toast("Voice search is not available on this device") }
        },
        more = { feed.searchMore() }, person = { feed.showPerson(it) },
    )
    val overlay = m.menu != null || m.prompt != null || m.progMenu != null
    BackHandler(enabled = feed.search != null && !overlay && m.screen == Screen.LIST) { if (feed.search?.person != null) feed.showPerson(null) else feed.clearSearch() }
    lateinit var actions: DiscoverActions
    actions = DiscoverActions(
        request = { feed.request(it) }, play = { playTitle(m, it) },
        open = { feed.ui.restore = true; feed.openTitle(it) }, more = { moreMenu(m, it, actions) },
        current = { feed.current(it) }, nearEnd = { id, i -> feed.nearEnd(id, i) },
        canRequest = feed.canRequest, busy = feed.busy, retry = { feed.refresh(force = true) },
        link = { m.orbit.clearMessage(); m.screen = Screen.ORBIT_LINK },
    )
    DiscoverContent(feed.state, feed.rows, feed.requests, actions, admin = feed.admin, ui = feed.ui, query = feed.query, search = feed.search, searchActions = sa)
}

/** The title's page or playback: a catalogue title opens like on Home (IPTV plays on the TV). */
internal fun playTitle(m: AppModel, t: DiscoverTitle) {
    val c = t.play ?: return
    io.github.kmmarin.tvbro.ui.home.open(m, Tile(c.key, c.title, source = c.source, show = c.isShow, vod = HomeFeed.vodOf(c), locked = c.locked), play = false)
}

private fun moreMenu(m: AppModel, t0: DiscoverTitle, a: DiscoverActions) {
    val t = a.current(t0)
    val items = ArrayList<Pair<String, () -> Unit>>()
    items += "Details" to { a.open(t) }
    if (t.play != null) items += "Play" to { a.play(t) }
    if (DiscoverApi.badge(t) == null && a.canRequest) items += "Request" to { a.request(t) }
    items += "Refresh" to { a.retry() }
    m.menu = MenuState(t.title, items)
}

/** Discover without the app model (the design shots draw it with stand-in data). */
@Composable
fun DiscoverContent(
    state: DiscoverState, rows: List<PagedRow>, requests: List<DiscoverRequest>, actions: DiscoverActions,
    admin: Boolean = false, ui: DiscoverUi = remember { DiscoverUi() }, query: String = "", search: DiscoverSearch? = null,
    searchActions: SearchActions = SearchActions(),
) {
    if (state != DiscoverState.READY) { DiscoverEmpty(state, admin, actions); return }
    if (LocalIsPhone.current) PhoneDiscover(rows, requests, actions, ui, query, search, searchActions)
    else TvDiscover(rows, requests, actions, ui, query, search, searchActions)
}

// ---------------------------------------------------------------- shared helpers

private fun typeName(t: DiscoverTitle) = if (t.isShow) "Show" else "Film"

private fun requestMeta(r: DiscoverRequest): String {
    val seasons = if (r.type == "tv" && r.seasons.isNotEmpty()) " · " + (if (r.seasons.size <= 3) r.seasons.joinToString(", ") { "S$it" } else "${r.seasons.size} seasons") else ""
    val by = r.by.takeIf { it.isNotBlank() }?.let { " · by $it" } ?: ""
    return (if (r.type == "tv") "Show" else "Film") + seasons + by
}

internal fun meta(t: DiscoverTitle) = listOfNotNull(t.year, typeName(t)).joinToString(" · ")

/** #1 trending title for the hero when nothing is focused. */
private fun featured(rows: List<PagedRow>): Pair<DiscoverTitle, String>? {
    val r = rows.firstOrNull { it.items.isNotEmpty() } ?: return null
    val what = if (r.id.startsWith("trending")) "#1 " + r.title.removeSuffix("s").lowercase().replaceFirstChar { it.uppercase() } else r.title
    return r.items.first() to what
}

private const val REQUESTS_ROW = "requests"

// ---------------------------------------------------------------- TV

/** Top of the rows area (the mockup's "Your requests" heading is at y = 500; a little lower to clear the focused button). */
private val RowsTop = 532.dp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TvDiscover(
    rows: List<PagedRow>, requests: List<DiscoverRequest>, a: DiscoverActions, ui: DiscoverUi,
    query: String, search: DiscoverSearch?, sa: SearchActions,
) {
    val tab = ui.tab
    val shown = remember(rows, tab) { DiscoverLogic.tabRows(rows, tab) }
    var focused by remember { mutableStateOf(ui.hero) }
    var inRows by remember { mutableStateOf(false) }
    val subject = (if (inRows) focused else null) ?: featured(shown.ifEmpty { rows }) ?: requests.firstOrNull()?.let { DiscoverApi.requestTitle(it) to "Your requests" }
    // the hero (and its backdrop) follow focus only once it settles: moving along a row loads nothing
    val settled by produceState(subject, subject?.first?.key) { delay(DiscoverLogic.HERO_SETTLE_MS); value = subject }
    val t = settled?.first?.let { a.current(it) }
    val badge = t?.let { DiscoverApi.badge(it) }
    val onCard = { x: DiscoverTitle, rowId: String, label: String ->
        focused = x to label; ui.hero = focused; ui.lastFocus = "$rowId|${x.key}"
    }

    val ft = focused?.first?.takeIf { inRows || search != null }?.let { a.current(it) }
    ColorKeyActions(
        red = ft?.takeIf { DiscoverApi.badge(it) == null && it.play == null && a.canRequest }?.let { x -> KeyAction("Request") { a.request(x) } },
        blue = ft?.let { x -> KeyAction("More") { a.more(x) } },
    )

    val scope = rememberCoroutineScope()
    val mainFr = remember { SafeFocus() }
    val restoreFr = remember { SafeFocus() }
    val searchFr = remember { SafeFocus() }
    val restoreKey = ui.lastFocus.takeIf { ui.restore }
    LaunchedEffect(Unit) {
        if (ui.restore) { repeat(20) { delay(60); if (restoreFr.request()) { ui.restore = false; return@LaunchedEffect } }; ui.restore = false }
        repeat(10) { delay(120); if (FocusMemory.anyFocus || mainFr.request()) return@LaunchedEffect }
    }

    Box(Modifier.fillMaxSize().testTag("discover")) {
        CinematicBackdrop(if (search != null) null else t?.let { BackdropSpec(it.backdrop ?: it.poster) }, Modifier.fillMaxSize(), dim = search != null)
        SubMenuRow(DiscoverTabs, tab, { ui.tab = it; if (search != null) sa.onClear() }, Modifier.padding(top = O5Space.SubMenuTop))
        DiscoverSearchField(query, sa, searchFr, Modifier.align(Alignment.TopEnd).padding(top = O5Space.SubMenuTop - 2.dp, end = O5Space.Side))
        if (search != null) {
            TvSearchResults(search, a, sa, ui.grid, onFocus = { x -> onCard(x, "search", "Search") }, focusKey = restoreKey?.substringAfter("search|", ""), focus = restoreFr)
            return@Box
        }
        Column(Modifier.padding(start = O5Space.Side, top = 192.dp).widthIn(max = 1200.dp)) {
            if (t != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(settled?.second?.uppercase() ?: "", style = O5Type.Eyebrow, color = O5.Accent, maxLines = 1)
                    Text(if (t.play != null) "· In your library" else badge?.let { "· " + it.label } ?: "· Not in your library", style = O5Type.Eyebrow, maxLines = 1)
                }
                Text(t.title, style = O5Type.SectionHeroTitle.copy(fontSize = 88.sp, lineHeight = 86.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp, bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    t.year?.let { Text(it, style = O5Type.Meta) }
                    t.rating?.let { Text("★ $it", style = O5Type.Meta, color = O5.Rating) }
                    Text(typeName(t), style = O5Type.Meta)
                }
            }
            Row(
                Modifier.padding(top = 22.dp).focusProperties { enter = { mainFr.target() } }.focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    t == null -> {}
                    t.play != null -> O5Button("Play", icon = Icons.Filled.PlayArrow, primary = true, compact = true, focus = mainFr) { a.play(t) }
                    badge == null && a.canRequest -> O5Button(if (a.busy == t.key) "Requesting…" else "Request", icon = Icons.Filled.Add, primary = true, compact = true, focus = mainFr,
                        modifier = Modifier.testTag("discover-request")) { a.request(t) }
                    else -> {}
                }
                if (t != null) O5Button("Details", icon = Icons.Filled.Info, compact = true, focus = if (t.play == null && (badge != null || !a.canRequest)) mainFr else null,
                    modifier = Modifier.testTag("discover-details")) { a.open(t) }
                if (t != null && badge != null) StatusBadge(badge)
            }
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(top = RowsTop - FocusRoom).onFocusChanged {
                if (inRows != it.hasFocus) { inRows = it.hasFocus; if (!it.hasFocus) scope.launch { ui.list.animateScrollToItem(0) } }
            }.testTag("discover-rows"),
            state = ui.list, contentPadding = PaddingValues(bottom = 420.dp), userScrollEnabled = false,
        ) {
            val showRequests = (tab == 0 || tab == 3) && requests.isNotEmpty()
            if (showRequests) item(key = REQUESTS_ROW) {
                val st = ui.row(REQUESTS_ROW)
                PrefetchImages(st, requests.map { r -> DiscoverApi.requestTitle(r).let { it.backdrop ?: it.poster } }, ImgSize.LANDSCAPE)
                O5Row("Your requests", Modifier.padding(bottom = O5Space.BetweenRows - FocusRoom * 2 + 20.dp)
                    .onFocusChanged { if (it.hasFocus) scope.launch { ui.list.animateScrollToItem(0) } }, gap = 26.dp, state = st) {
                    items(requests, key = { "r" + it.id + it.tmdb }) { r ->
                        val rt = DiscoverApi.requestTitle(r)
                        Column {
                            LandscapeCard(rt.backdrop ?: rt.poster, CardInfo(rt.title, requestMeta(r)), width = 300.dp, height = 168.dp,
                                focus = if (restoreKey == "$REQUESTS_ROW|${rt.key}") restoreFr else null,
                                onFocus = { onCard(rt, REQUESTS_ROW, "Your requests") }, onLongClick = { a.more(rt) }) { a.open(rt) }
                            StatusBadge(DiscoverApi.requestBadge(r), Modifier.padding(top = 12.dp))
                        }
                    }
                }
            }
            if (tab == 3 && requests.isEmpty()) item(key = "none") {
                Text("Nothing requested yet. Choose a title and press Request (or the red key).", style = O5Type.Body, modifier = Modifier.padding(start = O5Space.Side, top = FocusRoom))
            }
            itemsIndexed(shown, key = { _, r -> r.id }) { i, row ->
                val st = ui.row(row.id)
                PrefetchImages(st, row.items.map { it.poster })
                O5Row(row.title, Modifier.padding(bottom = O5Space.BetweenRows - FocusRoom * 2 + 20.dp)
                    .onFocusChanged { if (it.hasFocus) scope.launch { ui.list.animateScrollToItem(i + if (showRequests) 1 else 0) } }, gap = 24.dp, state = st) {
                    itemsIndexed(row.items, key = { _, x -> x.key }) { idx, x ->
                        val cur = a.current(x)
                        val b = DiscoverApi.badge(cur)
                        Column {
                            PosterCard(cur.poster, CardInfo(cur.title, meta(cur)), width = 160.dp, height = 240.dp,
                                focus = if (restoreKey == "${row.id}|${cur.key}") restoreFr else null,
                                onFocus = { onCard(cur, row.id, row.title); a.nearEnd(row.id, idx) }, onLongClick = { a.more(cur) }) { a.open(cur) }
                            if (b != null) StatusBadge(b, Modifier.padding(top = 8.dp).width(160.dp), small = true)
                        }
                    }
                }
            }
        }
    }
}

/** Not set up, not linked, down or loading: a calm page saying what to do (spec: friendly empty state). */
@Composable
private fun DiscoverEmpty(state: DiscoverState, admin: Boolean, a: DiscoverActions) {
    val phone = LocalIsPhone.current
    val (title, body) = when (state) {
        DiscoverState.NOT_CONFIGURED -> "Discover needs Seerr" to (
            if (admin) "Set up Seerr in Orbit on your phone or computer: Settings ▸ Discover. Enter your Seerr address (for example " +
                "http://192.0.2.20:5055) and its API key (in Seerr: Settings ▸ General). Trending films and shows and everyone's requests then show here."
            else "Discover shows what is trending and lets you request films and shows through Seerr. Ask whoever runs your Orbit home server " +
                "to set it up (in Orbit on a phone or computer: Settings ▸ Discover).")
        DiscoverState.NO_ORBIT -> "Discover" to "Link this ${if (phone) "phone" else "TV"} to your Orbit home server to see trending films and shows and request what is not here yet."
        DiscoverState.DOWN -> "Seerr is not answering" to "The home server cannot reach Seerr right now. Try again in a moment."
        else -> "Discover" to "Loading…"
    }
    val fr = remember { SafeFocus() }
    LaunchedEffect(state) { repeat(10) { delay(120); if (FocusMemory.anyFocus || fr.request()) return@LaunchedEffect } }
    if (phone) {
        Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = LocalPhoneInsets.current.top + 24.dp).testTag("discover-empty")) {
            Text(title, style = PhoneType.H1)
            Text(body, style = PhoneType.Body, modifier = Modifier.padding(top = 12.dp))
            when (state) {
                DiscoverState.NO_ORBIT -> PhoneButton("Link to Orbit", Modifier.padding(top = 18.dp), primary = true) { a.link() }
                DiscoverState.DOWN, DiscoverState.NOT_CONFIGURED -> PhoneButton("Try again", Modifier.padding(top = 18.dp)) { a.retry() }
                else -> {}
            }
        }
        return
    }
    Box(Modifier.fillMaxSize().testTag("discover-empty")) {
        CinematicBackdrop(null, Modifier.fillMaxSize())
        Column(Modifier.padding(start = O5Space.Side, top = O5Space.HeroTopSection).widthIn(max = 1150.dp)) {
            Text(title, style = O5Type.SectionHeroTitle)
            Text(body, style = O5Type.Synopsis, modifier = Modifier.padding(top = 28.dp))
            Row(Modifier.padding(top = 38.dp)) {
                when (state) {
                    DiscoverState.NO_ORBIT -> O5Button("Link this TV", primary = true, compact = true, focus = fr) { a.link() }
                    DiscoverState.DOWN, DiscoverState.NOT_CONFIGURED -> O5Button("Try again", icon = Icons.Filled.Refresh, compact = true, focus = fr) { a.retry() }
                    else -> {}
                }
            }
        }
    }
}

// ---------------------------------------------------------------- phone

/** Columns of the phone's search grid: 3 upright, more on its side. */
internal fun phoneSearchColumns(widthDp: Int): Int = (widthDp / 130).coerceIn(3, 8)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhoneDiscover(
    rows: List<PagedRow>, requests: List<DiscoverRequest>, a: DiscoverActions, ui: DiscoverUi,
    query: String, search: DiscoverSearch?, sa: SearchActions,
) {
    val tab = ui.tab
    val shown = remember(rows, tab) { DiscoverLogic.tabRows(rows, tab) }
    val insets = LocalPhoneInsets.current
    val cols = phoneSearchColumns(androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp)
    LazyColumn(Modifier.fillMaxSize().testTag("discover"), state = ui.list, contentPadding = PaddingValues(top = insets.top + 4.dp, bottom = insets.bottom + 20.dp)) {
        item(key = "tabs") {
            PhoneChipRow(Modifier.padding(vertical = 6.dp)) {
                DiscoverTabs.forEachIndexed { i, s -> PhoneChip(s, i == tab) { ui.tab = i; if (search != null) sa.onClear() } }
            }
        }
        item(key = "field") { PhoneSearchField(query, sa, Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 4.dp)) }
        if (search != null) { phoneSearchResults(search, a, sa, cols); return@LazyColumn }
        featured(shown.ifEmpty { rows })?.let { (t0, what) ->
            val t = a.current(t0)
            item(key = "hero") {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 22.dp)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color(0xFF111111)).combinedClickable { a.open(t) }) {
                        NetImage(t.backdrop ?: t.poster, ImgSize.BACKDROP, Modifier.fillMaxSize(), ContentScale.Crop)
                    }
                    Text(what.uppercase(), style = PhoneType.Eyebrow, color = O5.Accent, modifier = Modifier.padding(top = 12.dp))
                    Text(t.title, style = PhoneType.H1, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.padding(top = 4.dp)) {
                        t.year?.let { Text(it, style = PhoneType.Meta) }
                        t.rating?.let { Text("★ $it", style = PhoneType.Meta, color = O5.Rating) }
                        Text(typeName(t), style = PhoneType.Meta)
                    }
                    val b = DiscoverApi.badge(t)
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        when {
                            t.play != null -> PhoneButton("Play", icon = Icons.Filled.PlayArrow, primary = true) { a.play(t) }
                            b == null && a.canRequest -> PhoneButton(if (a.busy == t.key) "Requesting…" else "Request", icon = Icons.Filled.Add, primary = true) { a.request(t) }
                            b != null -> StatusBadge(b)
                            else -> {}
                        }
                        PhoneButton("Details", icon = Icons.Filled.Info) { a.open(t) }
                    }
                }
            }
        }
        if ((tab == 0 || tab == 3) && requests.isNotEmpty()) item(key = REQUESTS_ROW) {
            Column(Modifier.padding(bottom = 22.dp)) {
                PhoneRowHeading("Your requests")
                LazyRow(state = ui.row(REQUESTS_ROW), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(requests, key = { "r" + it.id + it.tmdb }) { r ->
                        val rt = DiscoverApi.requestTitle(r)
                        PhoneLandscapeCard(rt.backdrop ?: rt.poster, rt.title, requestMeta(r), below = {
                            StatusBadge(DiscoverApi.requestBadge(r), Modifier.padding(top = 6.dp), small = true)
                        }, onLongClick = { a.more(rt) }) { a.open(rt) }
                    }
                }
            }
        }
        if (tab == 3 && requests.isEmpty()) item(key = "none") {
            Text("Nothing requested yet. Open a title to request it.", style = PhoneType.Body, modifier = Modifier.padding(20.dp))
        }
        items(shown, key = { it.id }) { row ->
            Column(Modifier.padding(bottom = 22.dp)) {
                PhoneRowHeading(row.title)
                val st = ui.row(row.id)
                PrefetchImages(st, row.items.map { it.poster })
                LazyRow(state = st, contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(row.items, key = { _, x -> x.key }) { idx, x ->
                        // a phone has no focus: the row loads more when its end comes into view
                        if (idx >= row.items.size - DiscoverLogic.NEAR_END) LaunchedEffect(row.items.size) { a.nearEnd(row.id, idx) }
                        val cur = a.current(x)
                        val b: Badge? = DiscoverApi.badge(cur)
                        PhonePosterCard(cur.poster, cur.title, meta(cur), below = b?.let { { StatusBadge(it, Modifier.padding(top = 5.dp), small = true) } },
                            onLongClick = { a.more(cur) }) { a.open(cur) }
                    }
                }
            }
        }
    }
}
