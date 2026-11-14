// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kmmarin.tvbro.*
import io.github.kmmarin.tvbro.R
import io.github.kmmarin.tvbro.data.DlRepo
import io.github.kmmarin.tvbro.data.Episode
import io.github.kmmarin.tvbro.data.RecRepo
import io.github.kmmarin.tvbro.data.VodDetail
import io.github.kmmarin.tvbro.data.VodFmt
import io.github.kmmarin.tvbro.data.VodItem
import io.github.kmmarin.tvbro.data.Watch
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A focusable card that scales up a little while focused (shared by My list and search result rows). */
@Composable
fun Card(m: AppModel, width: Dp, fav: (() -> Unit)?, onFocus: () -> Unit = {}, long: (() -> Unit)? = fav, mod: Modifier = Modifier, click: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    var f by remember { mutableStateOf(false) }
    Column(
        mod.width(width).padding(4.dp)
            .onFocusChanged { f = it.isFocused; if (it.isFocused) { m.favAction = fav; onFocus() } }
            .scale(if (f) 1.05f else 1f)
            .tvFocus(onLongClick = long, onClick = click).padding(5.dp),
        content = content,
    )
}

@Composable
fun ProgressLine(f: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(4.dp).background(Color(0xFF2A3240))) { Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).height(4.dp).background(Accent)) }
}

/** Channel tile: logo, name and what is on now with a progress line. */
@Composable
fun ChannelTile(m: AppModel, c: io.github.kmmarin.tvbro.data.Channel, now: Long, fav: (() -> Unit)? = { m.toggleFav(c) }, onFocus: () -> Unit = {}) {
    val cur = m.epg.nowNext(c, now).first
    Card(m, 210.dp, fav, onFocus, long = { m.openChannelMenu(c) }, click = { m.play(c) }) {
        Box(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(6.dp)).background(Surface), contentAlignment = Alignment.Center) {
            Logo(c.logo, c.name, 64.dp)
            if (c.key in m.favourites) Badge("*", Modifier.align(Alignment.TopStart).padding(4.dp))
            Text(c.number.toString(), color = TextDim, fontSize = 12.sp, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        Text(c.name, color = TextMain, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Text(cur?.title ?: "No guide data", color = TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (cur != null) ProgressLine(io.github.kmmarin.tvbro.data.ProgTime.progress(cur.start, cur.stop, now), Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun MyRow(title: String, count: Int, content: LazyListScope.() -> Unit) {
    Column(Modifier.padding(top = 10.dp)) {
        Text("$title  ($count)", color = TextMain, fontSize = 18.sp, modifier = Modifier.padding(start = 6.dp, bottom = 2.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 2.dp), content = content)
    }
}

@Composable
fun MyListScreen(m: AppModel) {
    LaunchedEffect(Unit) { m.refreshLists() }
    val now = rememberNow()
    val favCh = m.shown.filter { it.key in m.favourites }
    val groups = m.groupList.filter { "g|$it" in m.favourites }
    val recs = RecRepo.items.filter { "r|${it.id}" in m.favourites }
    val movies = m.mySnaps.filter { it.kind == "movie" && it.key in m.favourites }
    val series = m.mySnaps.filter { it.kind == "series" && it.key in m.favourites }
    val reminders = io.github.kmmarin.tvbro.data.Reminders.items.sortedBy { it.startMs }
    val rev = m.watch.rev.intValue
    val showList = remember(rev, m.histSnaps) { m.continueShows() }
    val shows = showList.associate { it.first.key to it.second }
    // recent titles: movies not finished, shows still in progress (the next episode on the card)
    val cont = m.histSnaps.filter { if (it.kind == "series") it.key in shows else !m.isWatched(it.key) } +
        showList.map { it.first }.filter { s -> m.histSnaps.none { it.key == s.key } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        item { Text("My list", color = Accent, fontSize = 28.sp) }
        item { Text("Add anything with long-press OK, the yellow or bookmark key, or gamepad Y.", color = TextDim, fontSize = 13.sp) }
        if (cont.isNotEmpty()) item {
            MyRow("Continue watching", cont.size) {
                items(cont, key = { it.key }) { v ->
                    val sr = shows[v.key]
                    if (sr != null) ContinueShowCard(m, v, sr) else {
                        val pos = remember(v.key, rev) { m.store.pos(v.key) }; val dur = remember(v.key, rev) { m.store.dur(v.key) }
                        Card(m, 140.dp, { m.toggleFavKey(v) }, long = { vodMenu(m, v) }, click = { m.openDetail(v) }) {
                            Box {
                                Poster(v.poster, v.name, 130.dp, 184.dp)
                                if (pos > 5000 && dur > 0) ProgressLine(pos.toFloat() / dur, Modifier.align(Alignment.BottomStart))
                            }
                            Text(v.name, color = TextMain, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
        if (favCh.isNotEmpty()) item { MyRow("Channels", favCh.size) { items(favCh) { c -> ChannelTile(m, c, now) } } }
        if (movies.isNotEmpty()) item { MyRow("Movies", movies.size) { items(movies) { v -> PosterTile(m, v, Modifier) } } }
        if (series.isNotEmpty()) item { MyRow("Shows", series.size) { items(series) { v -> PosterTile(m, v, Modifier) } } }
        if (reminders.isNotEmpty()) item {
            MyRow("Programmes and reminders", reminders.size) {
                items(reminders, key = { it.id }) { r ->
                    val ch = m.channels.firstOrNull { it.key == r.chKey }
                    Card(m, 230.dp, null, click = {
                        m.menu = MenuState(r.title, listOfNotNull(
                            ch?.let { "Watch ${it.name}" to { m.play(it) } },
                            "Cancel reminder" to { m.cancelReminder(r.id) },
                        ))
                    }) {
                        Box(Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(6.dp)).background(Surface).padding(8.dp)) {
                            Text(r.title, color = TextMain, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Badge("Reminder", Modifier.align(Alignment.BottomEnd))
                        }
                        Text(r.channel, color = TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        Text(io.github.kmmarin.tvbro.data.TimeFmt.dayTime(r.startMs), color = TextDim, fontSize = 12.sp)
                    }
                }
            }
        }
        if (recs.isNotEmpty()) item {
            MyRow("Recordings", recs.size) {
                items(recs, key = { it.id }) { r ->
                    Card(m, 230.dp, { m.toggleFavRec(r) }, click = { if (r.status == "done") m.playRec(r) else m.toast("Not finished yet") }) {
                        Box(Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(6.dp)).background(Surface).padding(8.dp)) {
                            Text(r.title, color = TextMain, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Image(painterResource(R.drawable.ic_nav_rec), null, Modifier.size(18.dp).align(Alignment.BottomEnd), colorFilter = ColorFilter.tint(if (r.status == "recording") Color(0xFFE57373) else TextDim))
                        }
                        Text(r.channel + "  ·  " + r.status, color = TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        if (groups.isNotEmpty()) item {
            MyRow("Groups", groups.size) {
                items(groups) { g ->
                    Card(m, 190.dp, { m.toggleFavGroup(g) }, click = { m.selectGroup(g); m.section = Section.TV }) {
                        Box(Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(6.dp)).background(Surface).padding(8.dp), contentAlignment = Alignment.CenterStart) {
                            Text(m.prefs.groupNames[g] ?: g, color = TextMain, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Text("${m.shown.count { it.group == g || it.key in (m.prefs.custom[g] ?: emptyList()) }} channels", color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        if (favCh.isEmpty() && movies.isEmpty() && series.isEmpty() && reminders.isEmpty() && recs.isEmpty() && groups.isEmpty() && cont.isEmpty())
            item { Text("Nothing here yet.", color = TextDim, fontSize = 16.sp, modifier = Modifier.padding(top = 20.dp)) }
    }
}
// ---------------------------------------------------------------- shared pieces

@Composable
fun Poster(url: String?, name: String, w: Dp, h: Dp) {
    val bmp by produceState<Bitmap?>(null, url) { value = if (url != null) Logos.load(url, 200) else null }
    Box(Modifier.size(w, h).clip(RoundedCornerShape(6.dp)).background(Surface), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(name.take(1).uppercase(), color = TextDim, fontSize = 22.sp)
    }
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    Box(modifier.background(Accent, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text, color = Bg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** Long-press menu for a movie or show (the context menu of a title anywhere). */
fun vodMenu(m: AppModel, item: VodItem) {
    val series = item.kind == "series"
    val items = ArrayList<Pair<String, () -> Unit>>()
    items += (if (series) "Open" else "Details") to { m.openDetail(item) }
    if (!series && !m.isDirect(item)) items += "Play" to { m.playItem(item) }
    items += (if (item.key in m.favourites) "Remove from My list" else "Add to My list") to { m.toggleFavKey(item) }
    if (series) {
        items += "Mark show as watched" to { m.markSeries(item, true) }
        items += "Mark show as unwatched" to { m.markSeries(item, false) }
    } else {
        val w = m.isWatched(item.key)
        items += (if (w) "Mark as unwatched" else "Mark as watched") to { m.markWatched(item.key, !w); m.refreshLists() }
        if (!m.isDirect(item)) items += "Download" to { m.downloadMovie(item) }
    }
    m.menu = MenuState(item.name, items)
}

/** A show in "Continue watching": poster, next episode code and title, progress of that episode. */
@Composable
fun ContinueShowCard(m: AppModel, v: VodItem, r: io.github.kmmarin.tvbro.data.SeriesRow, mod: Modifier = Modifier) {
    val st = m.watch.state("e|${v.sourceId}|${r.nextEp}")
    Card(m, 140.dp, { m.toggleFavKey(v) }, onFocus = { m.peekVod(v) }, long = { vodMenu(m, v) }, mod = mod, click = { m.openDetail(v) }) {
        Box {
            Poster(v.poster, v.name, 130.dp, 184.dp)
            Badge((if (r.nextSeason > 0) "S${r.nextSeason} " else "") + "E${r.nextNum}", Modifier.align(Alignment.TopStart).padding(4.dp))
            if (st != null && st.inProgress && st.durMs > 0) ProgressLine(st.fraction, Modifier.align(Alignment.BottomStart))
        }
        Text(v.name, color = TextMain, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Text(r.nextTitle ?: "", color = TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PosterTile(m: AppModel, item: VodItem, mod: Modifier) {
    var f by remember { mutableStateOf(false) }
    Column(
        mod.width(130.dp).padding(4.dp)
            .onFocusChanged { f = it.isFocused; if (it.isFocused) { m.peekVod(item); m.favAction = { m.toggleFavKey(item) } } }
            .scale(if (f) 1.06f else 1f)
            .tvFocus(onLongClick = { vodMenu(m, item) }) { m.openDetail(item) }.padding(5.dp),
    ) {
        Box {
            Poster(item.poster, item.name, 120.dp, 170.dp)
            item.rating?.let { Badge(it, Modifier.align(Alignment.TopEnd).padding(4.dp)) }
            if (item.key in m.favourites) Badge("*", Modifier.align(Alignment.TopStart).padding(4.dp))
            if (DlRepo.has(item.key)) Badge("Offline", Modifier.align(Alignment.BottomStart).padding(4.dp))
        }
        Text(item.name, color = TextMain, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}
