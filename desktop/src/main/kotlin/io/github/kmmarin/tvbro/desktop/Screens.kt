// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.kmmarin.tvbro.data.*

fun hhmm(sec: Long): String = TimeFmt.time(sec)

@Composable
private fun Logo(url: String?) = NetImage(url, Modifier.size(56.dp, 34.dp).clip(RoundedCornerShape(4.dp)))

@Composable
private fun Section(title: String) = T(title, 16, Theme.accent, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))

/** A poster card; [progress] draws a bar under it, [badge] a line under the title. */
@Composable
fun PosterCard(v: VodItem, app: AppState, width: androidx.compose.ui.unit.Dp? = null, badge: String? = null, progress: Float? = null, onClick: () -> Unit) {
    val w = app.watch.state(v.key)
    val fr = remember { FocusRequester() }
    // back from its page: the opened tile has the focus again
    LaunchedEffect(Unit) { if (app.lastOpened == v.key) { app.lastOpened = null; fr.settle() } }
    Column((if (width != null) Modifier.width(width) else Modifier).tvItem(onFocus = { app.favAction = { app.toggleList(v.key) } }, onMenu = { app.vodMenu(v) }, requester = fr, onClick = onClick).padding(6.dp)) {
        NetImage(v.poster, Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)), ContentScale.Crop)
        val p = progress ?: w?.takeIf { it.inProgress }?.fraction
        if (p != null) ProgressLine(p, Modifier.fillMaxWidth().padding(top = 2.dp))
        T(v.name, 13, maxLines = 2)
        Row {
            if (app.inList(v.key)) T("★ ", 12, Theme.accent)
            if (w?.watched == true) T("✓ Watched ", 12, Theme.ok)
            badge?.let { T(it, 12, Theme.dim) }
        }
    }
}

@Composable
private fun ContinueRow(app: AppState, shows: Boolean) {
    val data = app.watch.data // recompose on change
    val list: List<Pair<VodItem, String>> = remember(data, shows) {
        if (shows) app.watch.continueShows().map { (v, r) -> v to listOfNotNull(if (r.nextSeason > 0) "S${r.nextSeason} E${r.nextNum}" else "E${r.nextNum}", r.nextTitle).joinToString(" · ") }
        else app.watch.continueMovies().map { (v, w) -> v to "${Watch.clock(w.posMs)} of ${Watch.clock(w.durMs)}" }
    }
    if (list.isEmpty()) return
    Section("Continue watching")
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(list, key = { "c" + it.first.key }) { (v, line) ->
            val prog = if (shows) app.watch.series(v.key)?.nextEp?.let { id -> app.watch.state("e|${v.sourceId}|$id")?.takeIf { it.inProgress }?.fraction } else app.watch.state(v.key)?.fraction
            PosterCard(v, app, 140.dp, line, prog ?: 0f) { app.detail = v }
        }
    }
}

@Composable
fun VodBrowse(app: AppState, all: List<VodItem>, shows: Boolean) {
    if (all.isEmpty() && app.watch.data.snaps.isEmpty()) return Empty(app)
    val locked = app.settings.prefs.locked
    val cats = remember(all) { listOf("All") + all.map { it.category }.distinct() }
    var cat by remember(all) { mutableStateOf("All") }
    val open = app.pinOpen || app.settings.prefs.pinHash.isEmpty()
    val list = remember(all, cat, locked, open) {
        if (cat == "All") all.filter { it.category !in locked || open } else all.filter { it.category == cat }
    }
    Row(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.width(220.dp).fillMaxHeight()) {
            items(cats) { c ->
                Box(Modifier.fillMaxWidth().tvItem(onMenu = {
                    if (c != "All") app.menu = MenuState(c, listOf((if (c in locked) "Unlock category" else "Lock category (PIN)") to { app.toggleLock(c) }))
                }) { if (app.catLocked(c)) app.askPin { cat = c } else cat = c }.padding(8.dp)) {
                    T(c + if (c in locked) "  🔒" else "", 14, if (c == cat) Theme.accent else Theme.text)
                }
            }
        }
        LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (cat == "All") item(span = { GridItemSpan(maxLineSpan) }) { Column { ContinueRow(app, shows); Section(if (shows) "All shows" else "All movies") } }
            items(list, key = { it.key }) { v -> PosterCard(v, app) { app.detail = v } }
        }
    }
}

/**
 * The title page in direction 5 (DESIGN-SPEC-5, "Title detail"): the backdrop behind it (Home draws it), the eyebrow
 * with the source badge, the big title, the meta line, two lines of synopsis, the buttons and a muted status line;
 * a show then has its seasons as tabs and the episodes as landscape cards. Colour keys: My list, trailer, watched, more.
 */
@Composable
fun DetailScreen(app: AppState, v: VodItem) {
    val repo = remember(v) { app.repo(v.sourceId) }
    var d by remember(v) { mutableStateOf(app.detailCache[v.key]) }
    var err by remember(v) { mutableStateOf<String?>(null) }
    LaunchedEffect(v) {
        try { withContext(Dispatchers.IO) { repo?.detail(v) }?.let { d = it; app.detailCache[v.key] = it } } catch (e: Exception) { if (d == null) err = Net.describe(e) }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(v, d != null) { first.settle() }
    LaunchedEffect(v, app.inList(v.key)) { app.colorKeys = vodKeys(app, v).let { k -> ColorKeys(k.red, k.green, k.yellow, k.blue, listOf("Enter" to "Play", "Esc" to "Back")) } }
    val data = app.watch.data // recompose on watch changes
    val info = app.catalog.info[v.key]
    val media = info != null && CatalogMap.isMedia(info)
    val dd = d
    val eps = dd?.episodes.orEmpty()
    val st = app.watch.state(v.key)
    Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 30.dp)) {
        Eyebrow(app, v, if (v.kind == "series") "Series" else "Film")
        Spacer(Modifier.height(8.dp))
        Text2(v.name, 64, Color.White, FontWeight.ExtraBold, Modifier.widthIn(max = 820.dp), maxLines = 2)
        Spacer(Modifier.height(8.dp))
        val seasons = eps.map { it.season }.distinct().sortedWith(compareBy { if (it <= 0) Int.MAX_VALUE else it })
        MetaLine(app, v, dd, extra = seasons.count { it > 0 }.takeIf { it > 0 }?.let { "$it Season" + if (it > 1) "s" else "" })
        dd?.plot?.let { Spacer(Modifier.height(10.dp)); Text2(it, 15, Color(0xDDF4F6FF), modifier = Modifier.widthIn(max = 680.dp), maxLines = 2) }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                media -> OButton("▶  Play", primary = true, requester = first) { app.toast("Plex and Jellyfin titles play on the TV and in the Orbit web app for now") }
                v.kind != "series" && repo != null -> {
                    OButton(if (st?.inProgress == true) "▶  Resume ${Watch.clock(st.posMs)}" else "▶  Play", primary = true, requester = first) { app.openMovie(v, repo, d) }
                    if (st?.inProgress == true || st?.watched == true) OButton("↺  From the start") { app.openMovie(v, repo, d, fromStart = true) }
                    OButton(if (st?.watched == true) "Mark unwatched" else "✓  Mark watched") { app.markMovie(v, st?.watched != true) }
                }
                v.kind == "series" && eps.isNotEmpty() -> {
                    val t = remember(data, eps) { app.resumeTarget(v, eps) }
                    if (t != null) {
                        OButton("▶  " + t.second, primary = true, requester = first) { app.playEpisode(v, eps, t.first) }
                        if (app.watch.state(WatchStore.epKey(v, t.first))?.inProgress == true) OButton("↺  From the start") { app.playEpisode(v, eps, t.first, fromStart = true) }
                    } else OButton("▶  Watched all · play S${Watch.ordered(eps).first().season} E${Watch.ordered(eps).first().num}", primary = true, requester = first) {
                        app.playEpisode(v, eps, Watch.ordered(eps).first(), fromStart = true)
                    }
                    OButton("Watched…") {
                        app.menu = MenuState(v.name, listOf("Mark show as watched" to { app.markEpisodes(v, eps, eps, true) }, "Mark show as unwatched" to { app.markEpisodes(v, eps, eps, false) }))
                    }
                }
                else -> OButton(if (dd == null && err == null) "Loading…" else "▶  Play", primary = true, requester = first) {
                    if (repo == null) app.toast("The playlist of this title is not available") else if (v.kind != "series") app.openMovie(v, repo, d)
                }
            }
            OButton(if (app.inList(v.key)) "✓  My list" else "+  My list") { app.toggleList(v.key) }
        }
        // the muted status line under the buttons (§ title detail): progress, next episode, My list
        val status = listOfNotNull(
            st?.takeIf { it.inProgress }?.let { app.catalog.left(it.posMs, it.durMs) },
            app.watch.series(v.key)?.takeIf { it.nextEp != null }?.let { r -> (if (r.nextSeason > 0) "Next S${r.nextSeason} E${r.nextNum}" else "Next E${r.nextNum}") + (r.nextTitle?.let { " · $it" } ?: "") },
            if (app.inList(v.key)) "My list ✓" else null,
            dd?.director?.let { "Director: $it" }, dd?.cast?.let { "Cast: $it" },
        ).joinToString(" · ")
        if (status.isNotEmpty()) Text2(status, 13, O5.muted, modifier = Modifier.padding(top = 10.dp).widthIn(max = 900.dp))
        err?.let { Text2("Could not load the details: $it", 13, O5.live, modifier = Modifier.padding(top = 6.dp)) }
        if (eps.isNotEmpty()) {
            val row = app.watch.series(v.key)
            val startSeason = row?.nextSeason?.takeIf { it in seasons } ?: seasons.first()
            var season by remember(v) { mutableStateOf(startSeason) }
            Spacer(Modifier.weight(1f))
            // season tabs (§2 row heading size; the active one white with the accent underline)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 6.dp)) {
                items(seasons) { s ->
                    val inS = eps.filter { it.season == s }
                    val all = inS.all { app.watch.state(WatchStore.epKey(v, it))?.watched == true }
                    SeasonTab((if (s <= 0) "Specials" else "Season $s") + if (all) " ✓" else "", s == season,
                        onMenu = { app.menu = MenuState("Season $s", listOf("Mark season $s as watched" to { app.markEpisodes(v, eps, inS, true) }, "Mark season $s as unwatched" to { app.markEpisodes(v, eps, inS, false) })) }) { season = s }
                }
            }
            LazyRow(Modifier.padding(bottom = 56.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(top = 10.dp, bottom = 6.dp, end = 56.dp)) {
                items(eps.filter { it.season == season }.sortedBy { it.num }, key = { it.id }) { e ->
                    val es = app.watch.state(WatchStore.epKey(v, e))
                    val cap = when {
                        es?.inProgress == true -> app.catalog.left(es.posMs, es.durMs)
                        es?.watched == true -> "Watched"
                        else -> (if (row?.nextEp == e.id) "Next · " else "") + (VodFmt.runtime(e.duration) ?: "")
                    }
                    LandCard("E${e.num} · " + VodFmt.cleanTitle(v.name, e.title), e.image ?: dd?.backdrop ?: v.poster, 220.dp, cap.ifBlank { null },
                        es?.takeIf { it.inProgress }?.fraction, es?.watched == true, onMenu = { app.episodeMenu(v, eps, e) }) { app.playEpisode(v, eps, e) }
                }
            }
        }
    }
}

/** A season tab: bold text, white with a 3 px accent underline when chosen, muted otherwise; a white ring on focus. */
@Composable
private fun SeasonTab(label: String, on: Boolean, onMenu: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.onFocusChanged { focused = it.hasFocus }.tvItem(onMenu = onMenu, onClick = onClick).padding(10.dp, 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text2(label, 17, if (on || focused) Color.White else Color(0x80FFFFFF), FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.width(if (on) 36.dp else 0.dp).height(3.dp).background(if (on) O5.accent else Color.Transparent, RoundedCornerShape(2.dp)))
    }
}

@Composable
fun MyListScreen(app: AppState) {
    val keys = app.settings.myList.toSet()
    val ch = app.shown.filter { it.key in keys }
    val groups = keys.filter { it.startsWith("g|") }.map { it.removePrefix("g|") }.filter { it in app.groups }
    val all = (app.movies + app.series).associateBy { it.key }
    val vod = keys.mapNotNull { all[it] ?: app.watch.snapOf(it) }
    val recs = app.recorder.items.filter { "r|${it.id}" in keys || it.status == "recording" }
    val rem = app.settings.reminders
    val hasContinue = app.watch.continueShows().isNotEmpty() || app.watch.continueMovies().isNotEmpty()
    if (ch.isEmpty() && vod.isEmpty() && groups.isEmpty() && recs.isEmpty() && rem.isEmpty() && !hasContinue)
        return Column { T("My list is empty", 18); T("Add channels, groups, movies, shows and recordings with their menu (hold Enter, right click, or Y on a channel).", 14, Theme.dim, maxLines = 3) }
    LazyColumn {
        item { ContinueRow(app, true) }
        item { ContinueRow(app, false) }
        if (ch.isNotEmpty()) item {
            Section("Channels")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ch, key = { it.key }) { c ->
                    val cur = app.epg.nowNext(c, app.now).first
                    Column(Modifier.width(170.dp).tvItem(onMenu = { app.channelMenu(c) }) { app.playChannel(c) }.padding(6.dp)) {
                        Logo(c.logo); T(c.name, 13, bold = true)
                        T(cur?.title ?: "No guide data", 12, Theme.dim)
                        if (cur != null) ProgressLine(ProgTime.progress(cur.start, cur.stop, app.now), Modifier.fillMaxWidth().padding(top = 2.dp))
                    }
                }
            }
        }
        if (groups.isNotEmpty()) item {
            Section("Groups")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(groups) { g -> Box(Modifier.tvItem(onMenu = { app.groupMenu(g) }) { app.selectGroup(g); app.tab = Tab.TV }.background(Theme.surface, RoundedCornerShape(6.dp)).padding(16.dp, 12.dp)) { T(app.groupLabel(g), 14) } }
            }
        }
        val mv = vod.filter { it.kind != "series" }; val sh = vod.filter { it.kind == "series" }
        if (mv.isNotEmpty()) item { Section("Movies"); LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(mv, key = { it.key }) { v -> PosterCard(v, app, 140.dp) { app.detail = v } } } }
        if (sh.isNotEmpty()) item { Section("Shows"); LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(sh, key = { it.key }) { v -> PosterCard(v, app, 140.dp) { app.detail = v } } } }
        if (rem.isNotEmpty()) item {
            Section("Programmes (reminders)")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rem, key = { it.id }) { r ->
                    Column(Modifier.width(200.dp).tvItem(onMenu = { app.menu = MenuState(r.title, listOf("Remove reminder" to { app.reminderDone(r, false) })) }) {
                        app.shown.firstOrNull { it.key == r.chKey }?.let(app::playChannel)
                    }.background(Theme.surface, RoundedCornerShape(6.dp)).padding(10.dp)) {
                        T(r.title, 14, bold = true); T("${r.channel} · ${TimeFmt.dayTime(r.startMs, "EEE")}", 12, Theme.dim)
                    }
                }
            }
        }
        if (recs.isNotEmpty()) item {
            Section("Recordings")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(recs, key = { it.id }) { r -> RecCard(app, r, Modifier.width(220.dp)) } }
        }
    }
}

@Composable
private fun RecCard(app: AppState, r: Rec, modifier: Modifier) =
    Column(modifier.tvItem(onMenu = { app.recordingMenu(r) }) { if (r.status == "done") app.playRecording(r) else app.recordingMenu(r) }.background(Theme.surface, RoundedCornerShape(6.dp)).padding(10.dp)) {
        T(r.title, 14, bold = true)
        T("${r.channel} · ${TimeFmt.dayTime(r.startMs, "EEE d MMM")}" + if (r.endMs > 0) " – ${TimeFmt.time(r.endMs / 1000)}" else " (until stopped)", 12, Theme.dim)
        val (txt, col) = when (r.status) {
            "recording" -> "● Recording" to Theme.live
            "scheduled" -> (if (r.seriesId != null) "Scheduled (series)" else "Scheduled") to Theme.accent
            "done" -> ("Recorded" + if (r.bytes > 0) " · ${r.bytes / (1024 * 1024)} MB" else "") to Theme.ok
            else -> "Failed" to Theme.live
        }
        T(txt + (if (r.file.startsWith("smb://")) " · on the share" else ""), 12, col)
        if (r.note.isNotBlank()) T(r.note, 11, Theme.dim, maxLines = 2)
        val w = app.watch.state("r|${r.id}")
        if (w?.inProgress == true) ProgressLine(w.fraction, Modifier.fillMaxWidth().padding(top = 3.dp))
    }

@Composable
fun RecordingsScreen(app: AppState) {
    val recs = app.recorder.items.sortedWith(compareBy<Rec>({ when (it.status) { "recording" -> 0; "scheduled" -> 1; else -> 2 } }, { if (it.status == "scheduled") it.startMs else -it.startMs }))
    val rules = app.recorder.rules
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            T("Recordings", 20, Theme.accent)
            T(RecTarget.describe(app.settings) + "   ·   change in Settings > Recording", 12, Theme.dim)
            T("Recordings run while Orbit is open. From the guide: hold Enter (or right click) on a programme > Record / Record series.", 12, Theme.dim, maxLines = 2)
        }
        if (rules.isNotEmpty()) {
            item { Section("Series recordings") }
            items(rules, key = { "rule" + it.id }) { r ->
                Row(Modifier.fillMaxWidth().tvItem(onMenu = { app.menu = MenuState(r.title, listOf("Cancel series recording" to { app.recorder.cancelSeries(r) })) }) {
                    app.menu = MenuState(r.title, listOf("Cancel series recording" to { app.recorder.cancelSeries(r) }))
                }.padding(8.dp)) {
                    T("Every \"${r.title}\"", 15, bold = true, modifier = Modifier.weight(1f))
                    T("on ${r.channel} · airings booked 48 h ahead · ${app.recorder.items.count { it.seriesId == r.id && it.status == "scheduled" }} scheduled", 12, Theme.dim)
                }
            }
        }
        if (recs.isEmpty()) item { T("No recordings yet.", 14, Theme.dim, modifier = Modifier.padding(top = 12.dp)) }
        else item { Section("All") }
        items(recs, key = { it.id }) { r -> RecCard(app, r, Modifier.fillMaxWidth()) }
    }
}
