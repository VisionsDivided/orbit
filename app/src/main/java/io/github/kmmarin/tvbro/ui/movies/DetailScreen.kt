// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.movies

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kmmarin.tvbro.AppModel
import io.github.kmmarin.tvbro.Screen
import io.github.kmmarin.tvbro.Section
import io.github.kmmarin.tvbro.data.DlRepo
import io.github.kmmarin.tvbro.data.Episode
import io.github.kmmarin.tvbro.data.VodFmt
import io.github.kmmarin.tvbro.data.VodItem
import io.github.kmmarin.tvbro.data.Watch
import io.github.kmmarin.tvbro.ui.MenuState
import io.github.kmmarin.tvbro.ui.SafeFocus
import io.github.kmmarin.tvbro.ui.orbit5.BackdropSpec
import io.github.kmmarin.tvbro.ui.orbit5.CardInfo
import io.github.kmmarin.tvbro.ui.orbit5.CardSize
import io.github.kmmarin.tvbro.ui.orbit5.CinematicBackdrop
import io.github.kmmarin.tvbro.ui.orbit5.ColorKeyActions
import io.github.kmmarin.tvbro.ui.orbit5.ColorKeyLegend
import io.github.kmmarin.tvbro.ui.orbit5.FocusRoom
import io.github.kmmarin.tvbro.ui.orbit5.KeyAction
import io.github.kmmarin.tvbro.ui.orbit5.LandscapeCard
import io.github.kmmarin.tvbro.ui.orbit5.NavTab
import io.github.kmmarin.tvbro.ui.orbit5.O5
import io.github.kmmarin.tvbro.ui.orbit5.O5Button
import io.github.kmmarin.tvbro.ui.orbit5.O5Row
import io.github.kmmarin.tvbro.ui.orbit5.O5Shape
import io.github.kmmarin.tvbro.ui.orbit5.O5Space
import io.github.kmmarin.tvbro.ui.orbit5.O5Type
import io.github.kmmarin.tvbro.ui.orbit5.OrbitCanvas
import io.github.kmmarin.tvbro.ui.orbit5.SourceBadge
import io.github.kmmarin.tvbro.ui.orbit5.o5Focus
import io.github.kmmarin.tvbro.ui.shell.LocalIsPhone
import io.github.kmmarin.tvbro.ui.shell.ShellFrame
import io.github.kmmarin.tvbro.ui.shell.rememberIsPhone
import androidx.compose.runtime.CompositionLocalProvider
import io.github.kmmarin.tvbro.ui.shell.openTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------- the view (plain data; shot tests draw it)

/** The text of a title page. */
@Immutable
data class DetailData(
    val title: String,
    /** "Series" / "Film". */
    val eyebrow: String,
    /** Source badges: "iptv", "plex", "jellyfin". */
    val sources: List<String> = emptyList(),
    val year: String? = null,
    val rating: String? = null,
    /** "1h 58m" (film) or "3 Seasons" (show). */
    val length: String? = null,
    val genres: List<String> = emptyList(),
    val synopsis: String? = null,
    /** The muted line under the buttons ("28 min left · Ballast · In My list"). */
    val status: String? = null,
    /** A note (loading, an error, "plays from Plex"). */
    val note: String? = null,
    val cast: String? = null,
    val director: String? = null,
)

/** A button of the title page; [enabled] false draws it muted ("Play (coming soon)") and OK does nothing. */
class DetailButton(val label: String, val icon: ImageVector? = null, val primary: Boolean = false, val enabled: Boolean = true, val onClick: () -> Unit = {})

/** An episode card. */
@Immutable
data class EpCard(val key: String, val title: String, val meta: String, val image: String?, val progress: Float? = null, val watched: Boolean = false)

/**
 * The title page in the cinematic style (mockup "Title detail"): eyebrow, title, meta, synopsis, the buttons (the
 * first is Play / Resume), a status line; then for shows the season tabs and that season's episodes, for films the
 * cast and director. Call inside the shell frame, over the backdrop.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DetailView(
    d: DetailData, buttons: List<DetailButton>, modifier: Modifier = Modifier,
    seasons: List<String> = emptyList(), season: Int = 0, onSeason: (Int) -> Unit = {}, onSeasonLong: (Int) -> Unit = {},
    episodes: List<EpCard> = emptyList(), onEpFocus: (EpCard?) -> Unit = {}, onEp: (EpCard) -> Unit = {}, onEpLong: (EpCard) -> Unit = {},
    playFocus: SafeFocus? = null, epFocusKey: String? = null, epFocus: SafeFocus? = null, rowState: LazyListState = rememberLazyListState(),
    onBack: () -> Unit = {}, watched: DetailButton? = null,
) {
    // a phone: the touch layout (PhoneDetail.kt); [onBack] and [watched] are only used there
    if (LocalIsPhone.current) {
        PhoneDetailView(d, buttons, onBack, modifier, watched, seasons, season, onSeason, onSeasonLong, episodes, onEp, onEpLong)
        return
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 150.dp, bottom = 120.dp).testTag("detail")) {
        Column(Modifier.padding(start = O5Space.Side).widthIn(max = 1150.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                d.sources.forEach { SourceBadge(it) }
                Text(d.eyebrow.uppercase(), style = O5Type.Eyebrow, maxLines = 1)
            }
            Text(
                d.title, style = O5Type.HeroTitle.copy(fontSize = if (d.title.length > 24) 80.sp else 104.sp, lineHeight = if (d.title.length > 24) 78.sp else 100.sp),
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 16.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                d.year?.let { Text(it, style = O5Type.Meta, maxLines = 1) }
                d.rating?.let { Text("★ $it", style = O5Type.Meta, color = O5.Rating, maxLines = 1) }
                d.length?.let { Text(it, style = O5Type.Meta, maxLines = 1) }
                d.genres.take(2).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { Text(it, style = O5Type.Meta, maxLines = 1) }
            }
            d.synopsis?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = O5Type.Synopsis.copy(fontSize = 26.sp, lineHeight = 38.sp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 16.dp).widthIn(max = 1000.dp))
            }
            // the ends of the button row are edges (Right from the last button went down to the season tabs)
            Row(
                Modifier.padding(top = 30.dp).focusProperties {
                    enter = { playFocus?.target() ?: FocusRequester.Default }
                    exit = { dir -> if (dir == FocusDirection.Left || dir == FocusDirection.Right) FocusRequester.Cancel else FocusRequester.Default }
                }.focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                buttons.forEachIndexed { i, b ->
                    val f = if (i == 0) playFocus else null
                    if (b.enabled) O5Button(b.label, icon = b.icon, primary = b.primary, focus = f, onClick = b.onClick, modifier = Modifier.testTag("detail-btn-$i"))
                    else MutedButton(b.label, b.icon, f)
                }
            }
            d.status?.takeIf { it.isNotEmpty() }?.let { Text(it, style = O5Type.Body.copy(fontSize = 20.sp), maxLines = 1, modifier = Modifier.padding(top = 14.dp)) }
            d.note?.takeIf { it.isNotEmpty() }?.let { Text(it, style = O5Type.Body.copy(fontSize = 20.sp), color = O5.MetaText, maxLines = 2, modifier = Modifier.padding(top = 8.dp)) }
        }
        if (seasons.isNotEmpty() || episodes.isNotEmpty()) {
            if (seasons.size > 1 || (seasons.size == 1 && episodes.isNotEmpty())) SeasonTabs(seasons, season, onSeason, onSeasonLong, Modifier.padding(start = O5Space.Side, top = 44.dp))
            O5Row(null, Modifier.padding(top = 30.dp - FocusRoom).onFocusChanged { if (!it.hasFocus) onEpFocus(null) }, gap = 26.dp, state = rowState) {
                items(episodes.size, key = { episodes[it].key }) { i ->
                    val e = episodes[i]
                    LandscapeCard(
                        e.image, CardInfo(e.title, e.meta, watched = e.watched, progress = e.progress), width = CardSize.EpisodeW, height = CardSize.EpisodeH,
                        focus = if (e.key == epFocusKey) epFocus else null, onFocus = { onEpFocus(e) }, onLongClick = { onEpLong(e) },
                    ) { onEp(e) }
                }
            }
        } else if (d.cast != null || d.director != null) {
            Column(Modifier.padding(start = O5Space.Side, top = 56.dp).widthIn(max = 1300.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                d.cast?.let { Text("Cast  ·  $it", style = O5Type.Body, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                d.director?.let { Text("Director  ·  $it", style = O5Type.Body, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/** A button that cannot be used yet: focusable (the row stays reachable) but muted, and OK does nothing. */
@Composable
private fun MutedButton(text: String, icon: ImageVector?, focus: SafeFocus?) {
    Row(
        Modifier.o5Focus(O5Shape.Button, 12.dp, focus = focus) {}.background(Color(0x1FFFFFFF), O5Shape.Button).padding(horizontal = 40.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = Color(0x8CFFFFFF), modifier = Modifier.size(30.dp))
        Text(text, style = O5Type.Button, color = Color(0x8CFFFFFF), maxLines = 1)
    }
}

/** Season tabs (26 px / 700, 44 px apart; the selected one white with a 4 px accent underline). Focus selects. */
@Composable
private fun SeasonTabs(seasons: List<String>, selected: Int, onSelect: (Int) -> Unit, onLong: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        seasons.forEachIndexed { i, s ->
            val on = i == selected
            Column(
                Modifier.o5Focus(O5Shape.Button, 10.dp, scaleTo = 1.04f, onFocus = { onSelect(i) }, onLongClick = { onLong(i) }) { onSelect(i) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(s, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = if (on) Color.White else Color(0x80FFFFFF), maxLines = 1)
                Box(Modifier.padding(top = 8.dp).height(4.dp).width(if (on) 48.dp else 0.dp).background(O5.Accent, O5Shape.Pill))
            }
        }
    }
}

// ---------------------------------------------------------------- the screen (Screen.DETAIL)

/**
 * The title page of a film or show (Screen.DETAIL), under the top bar. IPTV titles: everything tvBro's page did
 * (play / resume / play from start, episodes by season with their marks, trailer, My list, watched marks, downloads,
 * external player, season options; the PIN was asked when the page opened). Plex / Jellyfin titles: their details,
 * seasons and episodes, with a disabled "Play (coming soon)" (playback through those servers is not on the TV yet).
 */
@Composable
fun DetailScreen(m: AppModel) {
    val item = m.detail
    if (item == null) { LaunchedEffect(Unit) { m.screen = Screen.LIST }; return }
    val overlay = m.menu != null || m.prompt != null || m.progMenu != null
    BackHandler(enabled = !overlay) { m.screen = Screen.LIST }
    val series = item.kind == "series"
    val known = m.movies.known(item.key)
    // a title the TV cannot play: Plex / Jellyfin, or a source not on this TV (from My list)
    val media = known?.media ?: (!m.isDirect(item) && m.sources.none { it.id == item.sourceId } && (m.sources.isNotEmpty() || m.orbit.link != null))
    // the same APK on a phone: the touch title page, full screen (no top bar; Back at top left)
    if (rememberIsPhone()) {
        CompositionLocalProvider(LocalIsPhone provides true) {
            Box(Modifier.fillMaxSize().background(O5.Bg)) { if (media) MediaDetail(m, item, known) else IptvDetail(m, item, known) }
        }
        return
    }
    OrbitCanvas {
        ShellFrame(
            active = if (series) NavTab.SHOWS else NavTab.MOVIES, onTab = { t -> openTab(m, t) },
            onSearch = { m.screen = Screen.LIST; m.section = Section.SEARCH },
            onAvatar = { m.settingsBack = Screen.DETAIL; m.screen = Screen.SETTINGS },
            profileName = m.orbit.link?.profile?.ifEmpty { null } ?: "Orbit", legend = false,
        ) {
            if (media) MediaDetail(m, item, known) else IptvDetail(m, item, known)
            ColorKeyLegend(Modifier.align(Alignment.BottomStart).padding(start = O5Space.Side, bottom = 28.dp), hints = listOf("OK" to "Play"))
        }
    }
}

@Composable
private fun IptvDetail(m: AppModel, item: VodItem, known: Title?) {
    val ctx = LocalContext.current
    val info = m.detailInfo
    val rev = m.watch.rev.intValue
    var tick by remember { mutableIntStateOf(0) }
    val series = item.kind == "series"
    // Series: the remembered episode (in progress, or the next unwatched one after it); first episode when finished.
    val target: Episode? = remember(info, rev) {
        info?.episodes?.takeIf { it.isNotEmpty() }?.let { l -> Watch.resume(l, { m.epState(item, it) }, m.watch.seriesRow(item.key)?.lastEp) ?: Watch.ordered(l).first() }
    }
    val bySeason = remember(info) { Watch.ordered(info?.episodes ?: emptyList()).groupBy { it.season } }
    val seasonNums = bySeason.keys.toList()
    var season by remember(item.key, target?.season, seasonNums.size) { mutableIntStateOf(seasonNums.indexOf(target?.season).coerceAtLeast(0)) }
    val eps = seasonNums.getOrNull(season)?.let { bySeason[it] } ?: emptyList()
    var focusedEp by remember { mutableStateOf<Episode?>(null) }

    val st = remember(rev, tick, target) { if (series) target?.let { m.epState(item, it) } else m.watch.state(item.key) }
    val pos = if (st?.inProgress == true) st.posMs else 0L
    val durSecs = if (series) VodFmt.seconds(target?.duration) else VodFmt.seconds(info?.duration)
    val left = (st?.durMs?.takeIf { it > 0 }?.div(1000) ?: durSecs) - pos / 1000
    val label = if (series) {
        target?.let { (if (pos > 0) "Resume " else "Play ") + Watch.code(it) } ?: "Play"
    } else if (pos > 0) "Resume" else "Play"
    val allWatched = series && info != null && info.episodes.isNotEmpty() && info.episodes.all { m.epState(item, it)?.watched == true }
    val inList = item.key in m.favourites
    val trailer = info?.trailer?.takeIf { it.isNotBlank() }

    val play = { if (series) target?.let { m.playEpisode(item, it) } else m.playItem(item); Unit }
    val restart = {
        if (series) target?.let { m.restartEpisode(item, it) } else { m.store.setWatched(item.key, false); m.playItem(item) }; Unit
    }
    val seasonMenu = { i: Int ->
        val sn = seasonNums.getOrNull(i); val list = sn?.let { bySeason[it] } ?: emptyList()
        if (list.isNotEmpty()) m.menu = MenuState(if (sn!! > 0) "Season $sn" else "Episodes", listOf(
            "Mark season as watched" to { m.markEpisodes(item, list, true) },
            "Mark season as unwatched" to { m.markEpisodes(item, list, false) },
            "Download season" to { m.downloadSeason(item, list) },
        ))
    }
    val epMenu = { e: Episode ->
        val watched = m.epState(item, e)?.watched == true
        m.menu = MenuState("${Watch.code(e)} ${VodFmt.cleanTitle(item.name, e.title)}", listOf(
            "Play" to { m.playEpisode(item, e) },
            "Play from start" to { m.restartEpisode(item, e) },
            (if (watched) "Mark as unwatched" else "Mark as watched") to { m.markEpisodes(item, listOf(e), !watched) },
            (if (item.key in m.favourites) "Remove show from My list" else "Add show to My list") to { m.toggleFavKey(item) },
            "Download episode" to { m.downloadEpisode(item, e) },
        ))
    }
    val more = {
        val l = ArrayList<Pair<String, () -> Unit>>()
        if (series) {
            if (info != null && info.episodes.isNotEmpty()) l += (if (allWatched) "Mark show as unwatched" else "Mark show as watched") to { m.markEpisodes(item, info.episodes, !allWatched) }
            seasonNums.getOrNull(season)?.let { sn -> if (eps.isNotEmpty()) l += (if (sn > 0) "Season $sn options" else "Episode options") to { seasonMenu(season) } }
        } else {
            val w = m.isWatched(item.key)
            l += (if (w) "Mark as unwatched" else "Mark as watched") to { m.markWatched(item.key, !w); tick++ }
            l += (if (DlRepo.has(item.key)) "Offline copy saved" else "Download") to { m.downloadMovie(item) }
        }
        if (pos > 0) l += "Play from start" to restart
        l += "External player" to { m.urlFor(item, target)?.let { m.openExternal(it) } ?: m.toast("No address for this title") }
        if (item.category.isNotBlank() && item.category in m.vodCatNames) {
            val c = item.category
            l += (if (c in m.prefs.locked) "Unlock category $c" else "Lock category $c (PIN)") to { m.toggleLock(c) }
        }
        m.menu = MenuState(item.name, l)
    }

    ColorKeyActions(
        red = KeyAction("My list") { m.toggleFavKey(item) },
        green = trailer?.let { KeyAction("Play trailer") { openTrailer(ctx, m, it) } },
        yellow = KeyAction("Mark watched") {
            val e = focusedEp
            when {
                e != null -> { val w = m.epState(item, e)?.watched == true; m.markEpisodes(item, listOf(e), !w) }
                series -> if (info != null && info.episodes.isNotEmpty()) m.markEpisodes(item, info.episodes, !allWatched)
                else -> { val w = m.isWatched(item.key); m.markWatched(item.key, !w); m.toast(if (w) "Marked as unwatched" else "Marked as watched"); tick++ }
            }
        },
        blue = KeyAction("More") { focusedEp?.let { epMenu(it) } ?: more() },
        enabled = m.screen == Screen.DETAIL,
    )

    val genres = known?.genres?.takeIf { it.isNotEmpty() } ?: info?.genre?.split(',', '/')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    val d = DetailData(
        title = item.name, eyebrow = if (series) "Series" else "Film", sources = listOf(known?.source ?: "iptv"),
        year = info?.year ?: known?.year, rating = (info?.rating ?: known?.rating ?: item.rating)?.takeIf { it.isNotBlank() && it != "0" },
        length = if (series) seasonNums.size.takeIf { it > 0 }?.let { "$it Season" + if (it > 1) "s" else "" } else VodFmt.runtime(info?.duration),
        genres = genres, synopsis = info?.plot,
        status = listOfNotNull(
            if (pos > 0 && left > 60) "${VodFmt.fmt(left)} left" else if (pos > 0) Watch.clock(pos) else null,
            if (series) target?.let { VodFmt.cleanTitle(item.name, it.title) }?.takeIf { it.isNotBlank() } else null,
            if (inList) "My list ✓" else null,
            if (!series && DlRepo.has(item.key)) "Offline copy saved" else null,
            if (!series && m.isWatched(item.key)) "Watched" else if (allWatched) "Watched" else null,
        ).joinToString(" · "),
        note = m.detailMsg.takeIf { it.isNotEmpty() },
        cast = info?.cast, director = info?.director,
    )
    val buttons = buildList {
        add(DetailButton(label, Icons.Filled.PlayArrow, primary = true, onClick = play))
        if (pos > 0) add(DetailButton("Play from start", Icons.Filled.Refresh, onClick = restart))
        add(DetailButton("My list", if (inList) Icons.Filled.Check else Icons.Filled.Add) { m.toggleFavKey(item) })
        trailer?.let { add(DetailButton("Trailer", Icons.Filled.Star) { openTrailer(ctx, m, it) }) }
        add(DetailButton("More", Icons.Filled.MoreVert, onClick = more))
    }
    val epCards = remember(eps, rev) {
        eps.map { e ->
            val k = m.epKey(item, e)
            val s = m.watch.state(k)
            val durMs = (s?.durMs ?: 0L).takeIf { it > 0 } ?: VodFmt.seconds(e.duration) * 1000
            val w = s?.watched == true
            val meta = when {
                w -> "Watched"
                s != null && s.inProgress && durMs > s.posMs -> "${(durMs - s.posMs) / 60_000} min left"
                durMs > 0 -> "${durMs / 60_000} min"
                else -> ""
            } + if (DlRepo.has(k)) " · Offline" else ""
            EpCard(e.id, "E${e.num} · ${VodFmt.cleanTitle(item.name, e.title)}", meta, e.image ?: info?.backdrop ?: item.poster,
                if (s != null && s.inProgress && durMs > 0) s.posMs.toFloat() / durMs else if (w) 1f else null, w)
        }
    }
    // the phone's round "Watched" action: what yellow does without a focused episode (the film, or the whole show)
    val filmWatched = !series && m.isWatched(item.key)
    val watchedButton = DetailButton(if (filmWatched || allWatched) "Unwatched" else "Watched", Icons.Filled.Check) {
        if (series) { if (info != null && info.episodes.isNotEmpty()) m.markEpisodes(item, info.episodes, !allWatched) }
        else { m.markWatched(item.key, !filmWatched); m.toast(if (filmWatched) "Marked as unwatched" else "Marked as watched"); tick++ }
    }
    val playFocus = remember { SafeFocus() }
    val epFocus = remember { SafeFocus() }
    val rowState = rememberLazyListState()
    LaunchedEffect(item.key, info != null) { repeat(10) { delay(100); if (playFocus.request()) return@LaunchedEffect } }
    LaunchedEffect(season, target?.id) {
        val i = eps.indexOfFirst { it.id == target?.id }
        if (i > 0) rowState.scrollToItem(i)
    }
    Box(Modifier.fillMaxSize()) {
        DetailBackdrop(BackdropSpec(info?.backdrop ?: known?.backdrop ?: item.poster, known?.colors))
        DetailView(
            d, buttons,
            seasons = if (series) seasonNums.map { if (it > 0) "Season $it" else "Episodes" } else emptyList(), season = season,
            onSeason = { season = it }, onSeasonLong = seasonMenu,
            episodes = epCards, onEpFocus = { c -> focusedEp = c?.let { x -> eps.firstOrNull { it.id == x.key } } },
            onEp = { c -> eps.firstOrNull { it.id == c.key }?.let { m.playEpisode(item, it) } },
            onEpLong = { c -> eps.firstOrNull { it.id == c.key }?.let { epMenu(it) } },
            playFocus = playFocus, epFocusKey = target?.id, epFocus = epFocus, rowState = rowState,
            onBack = { m.screen = Screen.LIST }, watched = watchedButton,
        )
    }
}

/** Plex / Jellyfin (or a source not on this TV): details, seasons and episodes from `/v1/media`, no playback yet. */
@Composable
private fun MediaDetail(m: AppModel, item: VodItem, known: Title?) {
    val series = item.kind == "series"
    val conn = known?.conn ?: item.sourceId
    val id = known?.id?.ifEmpty { null } ?: item.id
    val src = known?.source?.takeIf { it == "plex" || it == "jellyfin" }
    val info by produceState<MediaInfo?>(null, conn, id) {
        value = if (m.orbit.link == null || conn.isEmpty()) null else try { withContext(Dispatchers.IO) { MediaDetails.load(m, conn, id) } } catch (e: Exception) { null }
    }
    val seasons by produceState<List<MediaSeason>>(emptyList(), conn, id, series) {
        value = if (!series || m.orbit.link == null) emptyList() else try { withContext(Dispatchers.IO) { MediaDetails.seasons(MediaDetails.children(m, conn, id)) } } catch (e: Exception) { emptyList() }
    }
    var season by remember(item.key) { mutableIntStateOf(0) }
    val seasonId = seasons.getOrNull(season)?.id
    val eps by produceState<List<MediaEp>>(emptyList(), seasonId) {
        value = if (seasonId == null) emptyList() else try { withContext(Dispatchers.IO) { MediaDetails.episodes(MediaDetails.children(m, conn, seasonId), m.orbit.link?.server ?: "") } } catch (e: Exception) { emptyList() }
    }
    val title = (known ?: Titles.of(item, null)).copy(vod = item, conn = conn, id = id)
    val inList = item.key in m.favourites
    val name = src?.let { sourceName(it) }
    ColorKeyActions(
        red = KeyAction("My list") { m.toggleFavKey(item) },
        yellow = if (src != null) KeyAction("Mark watched") { mediaWatched(m, title, !(info?.watched ?: false)) } else null,
        blue = KeyAction("More") { titleMenu(m, title.copy(source = src ?: "plex")) },
        enabled = m.screen == Screen.DETAIL,
    )
    val i = info
    val d = DetailData(
        title = item.name, eyebrow = if (series) "Series" else "Film", sources = listOfNotNull(src),
        year = i?.year ?: known?.year, rating = i?.rating ?: known?.rating,
        length = if (series) seasons.size.takeIf { it > 0 }?.let { "$it Season" + if (it > 1) "s" else "" } else i?.runtimeSecs?.takeIf { it > 0 }?.let { VodFmt.fmt(it) },
        genres = i?.genres?.ifEmpty { null } ?: known?.genres ?: emptyList(), synopsis = i?.overview,
        status = listOfNotNull(
            i?.progressSecs?.takeIf { it > 0 && (i.durSecs ?: 0) > it }?.let { "${VodFmt.fmt(i.durSecs!! - it)} left" },
            if (inList) "My list ✓" else null, if (i?.watched == true) "Watched" else null,
        ).joinToString(" · "),
        note = if (name != null) "Plays from $name. Playback through $name is not on the TV yet." else "This title's source is not on this TV.",
    )
    val buttons = listOf(
        DetailButton(if (name != null) "Play (coming soon)" else "Play", Icons.Filled.PlayArrow, primary = true, enabled = false),
        DetailButton("My list", if (inList) Icons.Filled.Check else Icons.Filled.Add) { m.toggleFavKey(item) },
        DetailButton("More", Icons.Filled.MoreVert) { titleMenu(m, title.copy(source = src ?: "plex")) },
    )
    val cards = eps.map { e ->
        EpCard(e.id, "E${e.num} · ${e.title}", if (e.watched) "Watched" else e.runtimeSecs?.let { "${it / 60} min" } ?: "", e.image,
            e.progressSecs?.let { p -> e.runtimeSecs?.takeIf { it > 0 }?.let { p.toFloat() / it } }, e.watched)
    }
    val playFocus = remember { SafeFocus() }
    LaunchedEffect(item.key) { repeat(10) { delay(100); if (playFocus.request()) return@LaunchedEffect } }
    Box(Modifier.fillMaxSize()) {
        DetailBackdrop(BackdropSpec(i?.backdrop ?: known?.backdrop ?: item.poster, known?.colors))
        DetailView(
            d, buttons, seasons = seasons.map { it.title }, season = season, onSeason = { season = it },
            episodes = cards, onEp = { m.toast("Playing from ${name ?: "this source"} is not on the TV yet") }, playFocus = playFocus,
            onBack = { m.screen = Screen.LIST },
            watched = if (src != null) DetailButton(if (i?.watched == true) "Unwatched" else "Watched", Icons.Filled.Check) { mediaWatched(m, title, !(i?.watched ?: false)) } else null,
        )
    }
}
