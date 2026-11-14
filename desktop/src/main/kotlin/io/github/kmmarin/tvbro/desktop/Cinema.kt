// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.kmmarin.tvbro.data.TimeFmt
import io.github.kmmarin.tvbro.data.VodItem
import io.github.kmmarin.tvbro.data.Watch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Orbit direction 5 "Cinematic + top bar" for the desktop (DESIGN-SPEC-5): the top bar, the sub-menu row, the
 * colour-matched backdrop, poster cards with the focus ring, and the Home, Movies / Shows and Discover screens.
 * Sizes are the spec's 1920×1080 ones scaled to a 1280-wide window (about 0.62).
 */

// ---------------------------------------------------------------------------------------------------------------
// Colours of a backdrop (§6: m1 dominant, m2 secondary, mb dark base from m1)
// ---------------------------------------------------------------------------------------------------------------

/** Pure colour extraction (unit tested): ARGB pixels in, the mesh colours out. */
object Palette {
    data class Mesh(val m1: Int, val m2: Int, val base: Int)

    val DEFAULT = Mesh(0xFF6B45E6.toInt(), 0xFF2FD3FF.toInt(), 0xFF06061A.toInt())

    private fun hsv(c: Int): FloatArray {
        val r = (c shr 16 and 255) / 255f; val g = (c shr 8 and 255) / 255f; val b = (c and 255) / 255f
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val d = mx - mn
        val h = when { d == 0f -> 0f; mx == r -> 60 * (((g - b) / d) % 6); mx == g -> 60 * ((b - r) / d + 2); else -> 60 * ((r - g) / d + 4) }
        return floatArrayOf(if (h < 0) h + 360 else h, if (mx == 0f) 0f else d / mx, mx)
    }

    private fun scale(c: Int, f: Float): Int {
        fun ch(s: Int) = ((c shr s and 255) * f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun of(px: IntArray): Mesh {
        if (px.isEmpty()) return DEFAULT
        val n = 24
        val weight = FloatArray(n); val sum = Array(n) { LongArray(3) }; val cnt = IntArray(n)
        for (c in px) {
            val (h, s, v) = hsv(c).let { Triple(it[0], it[1], it[2]) }
            if (v < .18f || s < .22f) continue                      // near black, grey or white: not a colour
            val i = ((h / 360f) * n).toInt().coerceIn(0, n - 1)
            weight[i] += s * v; cnt[i]++
            sum[i][0] += (c shr 16 and 255).toLong(); sum[i][1] += (c shr 8 and 255).toLong(); sum[i][2] += (c and 255).toLong()
        }
        fun avg(i: Int) = (0xFF shl 24) or ((sum[i][0] / cnt[i]).toInt() shl 16) or ((sum[i][1] / cnt[i]).toInt() shl 8) or (sum[i][2] / cnt[i]).toInt()
        val top = weight.indices.maxByOrNull { weight[it] }?.takeIf { weight[it] > 0f } ?: return DEFAULT.copy(base = 0xFF05060C.toInt())
        val m1 = avg(top)
        // the secondary colour: the strongest hue at least 3 buckets (45°) away, else a light version of m1
        val second = weight.indices.filter { val d = kotlin.math.abs(it - top); minOf(d, n - d) >= 3 && weight[it] > 0f }.maxByOrNull { weight[it] }
        val m2 = second?.let { avg(it) } ?: scale(m1, 1.6f)
        return Mesh(m1, m2, scale(m1, .09f))
    }

    fun of(img: ImageBitmap): Mesh = try {
        val pm = img.toPixelMap()
        val step = maxOf(1, minOf(pm.width, pm.height) / 32)
        val out = ArrayList<Int>()
        var y = 0
        while (y < pm.height) { var x = 0; while (x < pm.width) { out += argbOf(pm[x, y]); x += step }; y += step }
        of(out.toIntArray())
    } catch (e: Exception) { DEFAULT }

    private fun argbOf(c: Color): Int = ((c.alpha * 255).toInt() shl 24) or ((c.red * 255).toInt() shl 16) or ((c.green * 255).toInt() shl 8) or (c.blue * 255).toInt()
}

// ---------------------------------------------------------------------------------------------------------------
// The backdrop (§6): mesh, artwork top-right fading into it, vignettes; cross-fades 900 ms after a 250 ms wait
// ---------------------------------------------------------------------------------------------------------------

fun Modifier.mesh(m: Palette.Mesh) = orbitMesh(Color(m.m1), Color(m.m2), Color(m.base))

@Composable
fun Backdrop(art: String?, modifier: Modifier = Modifier, strongBottom: Boolean = false) {
    var shown by remember { mutableStateOf(art) }
    LaunchedEffect(art) { if (shown != null) delay(250); shown = art }
    Crossfade(shown, modifier.fillMaxSize(), animationSpec = tween(900)) { url -> BackdropLayer(url, strongBottom) }
}

@Composable
private fun BackdropLayer(url: String?, strongBottom: Boolean) {
    var img by remember(url) { mutableStateOf(url?.let { Images.cached(it, 1280) }) }
    LaunchedEffect(url) { if (img == null && url != null && url.startsWith("http")) img = Images.load(url, 1280) }
    val i = img
    val mesh = remember(i) { if (i == null) Palette.DEFAULT else Palette.of(i) }
    Box(Modifier.fillMaxSize().mesh(mesh)) {
        if (i != null) Image(i, null, Modifier.fillMaxWidth(.74f).fillMaxHeight(.84f).align(Alignment.TopEnd)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                // radial-gradient(130% 120% at 100% 0%, #000 36%, transparent 76%)
                drawRect(Brush.radialGradient(0f to Color.Black, .36f to Color.Black, .76f to Color.Transparent,
                    center = Offset(size.width, 0f), radius = maxOf(size.width * 1.3f, size.height * 1.2f)), blendMode = BlendMode.DstIn)
            }, contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0x80000000), .45f to Color(0x29000000), .7f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(if (strongBottom) Brush.verticalGradient(0f to Color.Transparent, .52f to Color(0xB804050A), 1f to Color(0xE604050A))
            else Brush.verticalGradient(.55f to Color.Transparent, 1f to Color(0x8C000000))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x80000000), .16f to Color.Transparent)))
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Top bar (§4) and sub-menu row (§5)
// ---------------------------------------------------------------------------------------------------------------

/** The top bar's sections, left to right; My list, Recordings and Settings are under the avatar. */
val NAV = listOf(Tab.HOME, Tab.MOVIES, Tab.SHOWS, Tab.TV, Tab.DISCOVER)

@Composable
private fun NavItem(label: String, active: Boolean, requester: FocusRequester?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.onFocusChanged { focused = it.hasFocus }.tvItem(requester = requester, onClick = onClick).padding(10.dp, 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text2(label, 17, if (active || focused) Color.White else Color(0x99FFFFFF), FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Box(Modifier.width(if (active) 28.dp else 0.dp).height(3.dp).then(if (active) Modifier.shadow(8.dp, CircleShape, ambientColor = O5.accent, spotColor = O5.accent) else Modifier)
            .background(if (active) O5.accent else Color.Transparent, CircleShape))
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TopBar(app: AppState) {
    val o = app.orbit
    val inSettings = app.tab == Tab.SETTINGS || app.tab == Tab.MYLIST || app.tab == Tab.RECORDINGS
    Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 56.dp).focusProperties { enter = { app.railFr } }.focusGroup(),
        verticalAlignment = Alignment.CenterVertically) {
        val logo = remember { OrbitArt.get("orbit-logo-horizontal.webp") }
        if (logo != null) Image(logo, "Orbit", Modifier.height(28.dp).widthIn(max = 140.dp), contentScale = ContentScale.Fit)
        else Text2("Orbit", 24, Color.White, FontWeight.ExtraBold)
        Spacer(Modifier.width(40.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            for (t in NAV) {
                val label = t.label + if (t == Tab.TV && app.recorder.items.any { it.status == "recording" }) " ●" else ""
                NavItem(label, app.tab == t && app.detail == null, if (app.tab == t || (inSettings && t == Tab.HOME && app.tab != Tab.SEARCH)) app.railFr else null) {
                    app.detail = null; app.tab = t
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
            if (app.busy) Text2(app.status.take(40), 12, O5.muted)
            Box(Modifier.tvItem(requester = if (app.tab == Tab.SEARCH) app.railFr else null) { app.detail = null; app.tab = Tab.SEARCH }.padding(6.dp)) { SearchGlyph(20.dp, app.tab == Tab.SEARCH) }
            Text2(TimeFmt.time(app.now), 20, Color.White, FontWeight.Light)
            val name = if (o.linked) o.saved.profile else "tvBro"
            Box(Modifier.tvItem(onClick = { profileMenu(app) }).padding(3.dp)) {
                Box(Modifier.size(36.dp).then(if (inSettings) Modifier.border(2.dp, Color.White, CircleShape).padding(3.dp) else Modifier)) { Avatar(name, if (inSettings) 30.dp else 36.dp) }
            }
        }
    }
}

/** A magnifier drawn with two shapes (no icon font needed). */
@Composable
private fun SearchGlyph(size: androidx.compose.ui.unit.Dp, active: Boolean) = Box(Modifier.size(size).drawBehind {
    val c = if (active) Color.White else Color(0xCCFFFFFF)
    val r = this.size.minDimension * .34f
    drawCircle(c, r, Offset(r + 2, r + 2), style = androidx.compose.ui.graphics.drawscope.Stroke(this.size.minDimension * .1f))
    drawLine(c, Offset(r * 1.75f + 2, r * 1.75f + 2), Offset(this.size.width - 1, this.size.height - 1), this.size.minDimension * .12f)
})

/** The avatar's menu: profile, My list, Recordings, Settings, sign out. */
fun profileMenu(app: AppState) {
    val o = app.orbit
    val items = ArrayList<Pair<String, () -> Unit>>()
    if (o.linked && o.profiles.size > 1) items += "Who's watching? (now ${o.saved.profile})" to { app.stopPlayback(); o.picking = true }
    items += "My list" to { app.detail = null; app.tab = Tab.MYLIST }
    items += "Recordings" to { app.detail = null; app.tab = Tab.RECORDINGS }
    items += "Settings" to { app.detail = null; app.tab = Tab.SETTINGS; app.settingsPage = 0 }
    if (o.linked) items += "Sign out of Orbit" to { app.detail = null; app.tab = Tab.SETTINGS; app.settingsPage = 1 }
    else items += "Sign in to Orbit…" to { app.detail = null; app.tab = Tab.SETTINGS; app.settingsPage = 1 }
    app.menu = MenuState(if (o.linked) "${o.saved.profile} · ${o.saved.account}" else "tvBro (not signed in to Orbit)", items)
}

/** A sub-menu pill (§5): white when active, translucent with a thin ring otherwise. */
@Composable
fun Pill(label: String, active: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(Modifier.onFocusChanged { focused = it.hasFocus }.tvItem(onClick = onClick).clip(CircleShape)
        .background(if (active) Color.White else O5.chip, CircleShape).border(if (focused) 2.dp else 1.dp, if (focused) Color.White else O5.chipRing, CircleShape)
        .padding(16.dp, 7.dp)) {
        Text2(label, 14, if (active) O5.bg else O5.text, if (active) FontWeight.Bold else FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Cards (§7): 5 px white ring with an offset, scale 1.07, a deep shadow
// ---------------------------------------------------------------------------------------------------------------

@Composable
fun CineCard(v: VodItem, app: AppState, width: androidx.compose.ui.unit.Dp = 112.dp, caption: String? = null, progress: Float? = null,
             onFocus: () -> Unit = {}, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (focused) 1.07f else 1f, tween(220))
    val w = app.watch.state(v.key)
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (app.lastOpened == v.key) { app.lastOpened = null; fr.settle() } }
    Column(Modifier.width(width).zIndex(if (focused) 1f else 0f)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).graphicsLayer { scaleX = sc; scaleY = sc }
            .onFocusChanged { focused = it.hasFocus; if (it.hasFocus) onFocus() }
            .tvItem(onFocus = { app.favAction = { app.toggleList(v.key) } }, onMenu = { app.vodMenu(v) }, requester = fr, onClick = onClick)
            .then(if (focused) Modifier.shadow(24.dp, RoundedCornerShape(8.dp)).border(3.dp, Color.White, RoundedCornerShape(9.dp)).padding(4.dp) else Modifier)
            .clip(RoundedCornerShape(7.dp))) {
            NetImage(v.poster, Modifier.fillMaxSize(), ContentScale.Crop)
            if (w?.watched == true) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).background(Color(0x9E000000), CircleShape), contentAlignment = Alignment.Center) {
                Text2("✓", 12, Color(0xFF7FF0BB), FontWeight.Bold)
            }
            val p = progress ?: w?.takeIf { it.inProgress }?.fraction
            if (p != null && p > 0f) Box(Modifier.align(Alignment.BottomCenter).padding(8.dp).fillMaxWidth().height(4.dp).background(Color(0x38FFFFFF), CircleShape)) {
                Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).fillMaxHeight().background(O5.accent, CircleShape))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text2(v.name, 13, if (focused) Color.White else O5.text, FontWeight.SemiBold)
        if (caption != null) Text2(caption, 11, O5.muted)
    }
}

@Composable
fun CineRow(title: String, items: List<Pair<VodItem, String?>>, app: AppState, progress: (VodItem) -> Float? = { null }, onFocus: (VodItem) -> Unit) {
    if (items.isEmpty()) return
    Column(Modifier.padding(bottom = 18.dp)) {
        Text2(title, 18, O5.text, FontWeight.Bold, Modifier.padding(start = 56.dp, bottom = 10.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 56.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            items(items, key = { title + it.first.key }) { (v, cap) -> CineCard(v, app, caption = cap, progress = progress(v), onFocus = { onFocus(v) }) { app.detail = v } }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Hero (§6): eyebrow, title, meta, synopsis, buttons
// ---------------------------------------------------------------------------------------------------------------

/** Detail of [v] (plot, year, backdrop) once loaded; loaded at most once per title. */
@Composable
fun rememberDetail(app: AppState, v: VodItem?): io.github.kmmarin.tvbro.data.VodDetail? {
    var d by remember(v?.key) { mutableStateOf(v?.let { app.detailCache[it.key] }) }
    LaunchedEffect(v?.key) {
        if (v == null || d != null) return@LaunchedEffect
        delay(300)
        val repo = app.repo(v.sourceId) ?: return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { repo.detail(v) } }.getOrNull()?.let { app.detailCache[v.key] = it; d = it }
    }
    return d
}

@Composable
fun Hero(app: AppState, v: VodItem, eyebrow: String, compact: Boolean = false) {
    val d = rememberDetail(app, v)
    val st = app.watch.state(v.key)
    Column(Modifier.padding(start = 56.dp, end = 56.dp).widthIn(max = 720.dp)) {
        Text2(eyebrow.uppercase(), 12, O5.muted, FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text2(v.name, if (compact) 44 else 56, Color.White, FontWeight.ExtraBold, maxLines = 2)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            (d?.rating ?: v.rating)?.takeIf { it.isNotBlank() && it != "0" }?.let { Text2("★ $it", 15, O5.rating, FontWeight.SemiBold) }
            d?.year?.let { Text2(it, 15, O5.text, FontWeight.SemiBold) }
            io.github.kmmarin.tvbro.data.VodFmt.runtime(d?.duration)?.let { Text2(it, 15, O5.text, FontWeight.SemiBold) }
            Box(Modifier.border(1.5.dp, Color(0x80FFFFFF), RoundedCornerShape(4.dp)).padding(6.dp, 1.dp)) { Text2(if (v.kind == "series") "SHOW" else "FILM", 11, O5.text, FontWeight.Bold) }
            Text2("IPTV", 12, Color(0xFFB49BFF), FontWeight.Bold)
        }
        d?.plot?.let { Spacer(Modifier.height(8.dp)); Text2(it, 15, Color(0xDDF4F6FF), maxLines = 2) }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OButton(if (st?.inProgress == true) "▶  Resume ${Watch.clock(st.posMs)}" else "▶  Play", primary = true) { heroPlay(app, v) }
            OButton("More info") { app.detail = v }
            OButton(if (app.inList(v.key)) "✓  My list" else "+  My list") { app.toggleList(v.key) }
        }
    }
}

private fun heroPlay(app: AppState, v: VodItem) {
    if (v.kind == "series") { app.detail = v; return }
    val repo = app.repo(v.sourceId) ?: return app.toast("The playlist of this film is not available")
    app.openMovie(v, repo, app.detailCache[v.key])
}

// ---------------------------------------------------------------------------------------------------------------
// Screens
// ---------------------------------------------------------------------------------------------------------------

/** The film / show whose backdrop and hero show (the focused card, after a short wait). */
class HeroState { var item by mutableStateOf<VodItem?>(null) }

@Composable
fun HomeScreen(app: AppState, hero: HeroState) {
    val data = app.watch.data
    val keys = app.settings.myList
    val rows = remember(data, keys, app.movies, app.series) {
        val cs = app.watch.continueShows().map { (v, r) -> v to (if (r.nextSeason > 0) "S${r.nextSeason} E${r.nextNum}" else "E${r.nextNum}") }
        val cm = app.watch.continueMovies().map { (v, w) -> v to "${Watch.clock(w.posMs)} of ${Watch.clock(w.durMs)}" }
        val mine = keys.mapNotNull { k -> app.findVod(k) ?: app.watch.snapOf(k) }.map { it to null }
        listOf("Continue watching" to (cm + cs).take(30), "My list" to mine.take(40),
            "Recently added films" to app.movies.takeLast(30).reversed().map { it to null },
            "Recently added shows" to app.series.takeLast(30).reversed().map { it to null })
    }
    val first = rows.firstNotNullOfOrNull { it.second.firstOrNull()?.first }
    LaunchedEffect(first?.key) { if (hero.item == null) hero.item = first }
    if (first == null) return Empty(app)
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(.42f), contentAlignment = Alignment.BottomStart) {
            hero.item?.let { Hero(app, it, if (rows[0].second.any { p -> p.first.key == it.key }) "Continue watching" else if (it.kind == "series") "Show" else "Film") }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(.58f), contentPadding = PaddingValues(top = 16.dp)) {
            items(rows.filter { it.second.isNotEmpty() }, key = { it.first }) { (title, list) ->
                CineRow(title, list, app, progress = { v -> if (v.kind == "series") app.watch.series(v.key)?.nextEp?.let { id -> app.watch.state("e|${v.sourceId}|$id")?.takeIf { it.inProgress }?.fraction } else null }) { hero.item = it }
            }
        }
    }
}

enum class VodPage(val label: String) { RECOMMENDED("Recommended"), BROWSE("Browse"), COLLECTIONS("Collections") }

/** Movies or Shows: the sub-menu row, then Recommended (hero + rows), Browse (grid) or Collections (categories). */
@Composable
fun VodSection(app: AppState, all: List<VodItem>, shows: Boolean, hero: HeroState) {
    var page by remember(shows) { mutableStateOf(VodPage.RECOMMENDED) }
    var genre by remember(shows) { mutableStateOf<String?>(null) }
    var sort by remember(shows) { mutableStateOf(0) }
    val sorts = listOf("Recently added", "Title", "Rating")
    val open = app.pinOpen || app.settings.prefs.pinHash.isEmpty()
    val locked = app.settings.prefs.locked
    val usable = remember(all, locked, open) { all.filter { it.category !in locked || open } }
    val cats = remember(usable) { usable.groupBy { it.category }.entries.sortedByDescending { it.value.size } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 56.dp, end = 56.dp, top = 2.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            for (p in VodPage.entries) Pill(p.label, page == p) { page = p }
            if (page == VodPage.BROWSE) {
                Spacer(Modifier.weight(1f))
                Pill((genre ?: "Any genre") + "  ▾", false) {
                    app.menu = MenuState("Genre", listOf<Pair<String, () -> Unit>>("Any genre" to { genre = null }) +
                        cats.map { (c, l) -> "$c  (${l.size})" to { if (app.catLocked(c)) app.askPin { genre = c } else genre = c } })
                }
                Pill("Sort: ${sorts[sort]}  ▾", false) { app.menu = MenuState("Sort", sorts.mapIndexed { i, s -> s to { sort = i } }) }
                val n = if (genre == null) usable.size else usable.count { it.category == genre }
                Text2("%,d titles".format(n), 13, O5.muted)
            }
        }
        if (all.isEmpty()) return@Column Box(Modifier.padding(horizontal = 56.dp)) { Empty(app) }
        when (page) {
            VodPage.RECOMMENDED -> {
                val cont = remember(app.watch.data, shows) {
                    if (shows) app.watch.continueShows().map { (v, r) -> v to "S${r.nextSeason} E${r.nextNum}" }
                    else app.watch.continueMovies().map { (v, w) -> v to "${Watch.clock(w.posMs)} of ${Watch.clock(w.durMs)}" }
                }
                val rows = remember(usable, cont) {
                    listOf("Continue watching" to cont, "Recently added" to usable.takeLast(30).reversed().map { it to null },
                        "Top rated" to usable.sortedByDescending { it.rating?.toDoubleOrNull() ?: 0.0 }.take(30).map { it to null }) +
                        cats.take(6).map { (c, l) -> "Top in $c" to l.sortedByDescending { it.rating?.toDoubleOrNull() ?: 0.0 }.take(30).map { it to null } }
                }
                LaunchedEffect(shows) { hero.item = rows.firstNotNullOfOrNull { it.second.firstOrNull()?.first } }
                Box(Modifier.fillMaxWidth().weight(.4f), contentAlignment = Alignment.BottomStart) {
                    hero.item?.let { Hero(app, it, if (shows) "Shows" else "Films", compact = true) }
                }
                LazyColumn(Modifier.fillMaxWidth().weight(.6f), contentPadding = PaddingValues(top = 14.dp)) {
                    items(rows.filter { it.second.isNotEmpty() }, key = { it.first }) { (t, l) -> CineRow(t, l, app) { hero.item = it } }
                }
            }
            VodPage.BROWSE -> {
                val list = remember(usable, genre, sort) {
                    val l = if (genre == null) usable else usable.filter { it.category == genre }
                    when (sort) { 1 -> l.sortedBy { it.name.lowercase() }; 2 -> l.sortedByDescending { it.rating?.toDoubleOrNull() ?: 0.0 }; else -> l.reversed() }
                }
                LazyVerticalGrid(GridCells.Adaptive(118.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(56.dp, 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    items(list, key = { it.key }) { v -> CineCard(v, app, 112.dp, onFocus = { hero.item = v }) { app.detail = v } }
                }
            }
            VodPage.COLLECTIONS -> {
                LazyVerticalGrid(GridCells.Adaptive(200.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(56.dp, 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(cats, key = { it.key }) { (c, l) ->
                        var focused by remember { mutableStateOf(false) }
                        Box(Modifier.height(120.dp).onFocusChanged { focused = it.hasFocus; if (it.hasFocus) hero.item = l.first() }
                            .tvItem(onMenu = { app.menu = MenuState(c, listOf((if (c in locked) "Unlock category" else "Lock category (PIN)") to { app.toggleLock(c) })) }) {
                                if (app.catLocked(c)) app.askPin { genre = c; page = VodPage.BROWSE } else { genre = c; page = VodPage.BROWSE }
                            }
                            .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier).clip(RoundedCornerShape(10.dp))) {
                            NetImage(l.first().poster, Modifier.fillMaxSize(), ContentScale.Crop)
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x33000000), 1f to Color(0xDD000000))))
                            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                                Text2(c + if (c in locked) "  🔒" else "", 16, Color.White, FontWeight.ExtraBold)
                                Text2("${l.size} titles", 12, O5.muted)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Discover (§11): rows from the household's Seerr through the home server
// ---------------------------------------------------------------------------------------------------------------

data class DTitle(val type: String, val tmdb: Long, val title: String, val year: String?, val overview: String?, val poster: String?,
                  val backdrop: String?, val rating: String?, val status: String)

object DiscoverParse {
    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    fun title(o: JsonObject): DTitle? {
        val type = o.s("type") ?: return null
        val tmdb = (o["tmdb"] as? JsonPrimitive)?.longOrNull ?: return null
        return DTitle(type, tmdb, o.s("title") ?: "", o.s("year"), o.s("overview"), o.s("poster"), o.s("backdrop"), o.s("rating"), o.s("status") ?: "requestable")
    }
    fun rows(j: JsonObject): List<Pair<String, List<DTitle>>> = (j["rows"] as? JsonArray).orEmpty().mapNotNull { r ->
        val o = r as? JsonObject ?: return@mapNotNull null
        (o.s("title") ?: "") to (o["items"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::title) }
    }
    /** The status badge (§1 "Discover status badges"): label, background, text colour. */
    fun badge(status: String): Triple<String, Color, Color> = when (status) {
        "available" -> Triple("Available", Color(0x2946DC96), Color(0xFF7FF0BB))
        "partial" -> Triple("Partly available", Color(0x2946DC96), Color(0xFF7FF0BB))
        "processing" -> Triple("Approved", Color(0x2950A0FF), Color(0xFFA9CFFF))
        "pending" -> Triple("Requested", Color(0x388A5CFF), Color(0xFFD2C2FF))
        else -> Triple("Request", O5.violet, Color.White)
    }
}

@Composable
fun DiscoverScreen(app: AppState, setArt: (String?) -> Unit) {
    val o = app.orbit
    if (!o.linked) return Column(Modifier.padding(horizontal = 56.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text2("Discover", 44, Color.White, FontWeight.ExtraBold)
        Text2("Sign in to Orbit to browse what's new and ask for films and shows through your household's Seerr.", 16, O5.muted, maxLines = 2)
        OButton("Sign in to Orbit", primary = true) { app.tab = Tab.SETTINGS; app.settingsPage = 1 }
    }
    var rows by remember { mutableStateOf<List<Pair<String, List<DTitle>>>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var focus by remember { mutableStateOf<DTitle?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(o.saved.profileId) {
        err = null
        rows = try { o.get("/v1/discover/rows")?.let(DiscoverParse::rows) } catch (e: Exception) {
            err = if (e is io.github.kmmarin.tvbro.orbit.OrbitError && e.code == 503) "Seerr cannot be reached right now." else OrbitDesk.describe(e); null
        }
        if (focus == null) focus = rows?.firstOrNull()?.second?.firstOrNull()
    }
    LaunchedEffect(focus) { setArt(focus?.backdrop ?: focus?.poster) }
    val r = rows
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(.46f), contentAlignment = Alignment.BottomStart) {
            val f = focus
            Column(Modifier.padding(horizontal = 56.dp).widthIn(max = 720.dp)) {
                if (f == null) { Text2("Discover", 44, Color.White, FontWeight.ExtraBold); err?.let { Text2(it, 15, O5.muted, maxLines = 2) } ?: if (r == null) Text2("Loading…", 15, O5.muted) else Unit }
                else {
                    Text2((if (f.type == "tv") "Show" else "Film").uppercase() + " · TRENDING", 12, O5.muted, FontWeight.Bold)
                    Text2(f.title, 48, Color.White, FontWeight.ExtraBold, maxLines = 2)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        f.rating?.let { Text2("★ $it", 15, O5.rating, FontWeight.SemiBold) }; f.year?.let { Text2(it, 15, O5.text, FontWeight.SemiBold) }
                    }
                    f.overview?.let { Text2(it, 15, Color(0xDDF4F6FF), maxLines = 2) }
                    Spacer(Modifier.height(12.dp))
                    val (label, _, _) = DiscoverParse.badge(f.status)
                    if (f.status == "requestable") OButton("Request", primary = true) { scope.launch { app.toast(o.request(f.type, f.tmdb)) } }
                    else Text2(label, 15, DiscoverParse.badge(f.status).third, FontWeight.Bold)
                }
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(.54f), contentPadding = PaddingValues(top = 14.dp)) {
            items(r.orEmpty().filter { it.second.isNotEmpty() }, key = { it.first }) { (title, list) ->
                Column(Modifier.padding(bottom = 18.dp)) {
                    Text2(title, 18, O5.text, FontWeight.Bold, Modifier.padding(start = 56.dp, bottom = 10.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 56.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        items(list, key = { title + it.type + it.tmdb }) { t -> DiscoverCard(t, onFocus = { focus = t }) {
                            if (t.status == "requestable") app.menu = MenuState(t.title, listOf("Request ${if (t.type == "tv") "this show" else "this film"}" to { scope.launch { app.toast(o.request(t.type, t.tmdb)) } }, "Cancel" to {}))
                            else app.toast("${t.title}: ${DiscoverParse.badge(t.status).first}")
                        } }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverCard(t: DTitle, onFocus: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (focused) 1.07f else 1f, tween(220))
    val (label, bg, fg) = DiscoverParse.badge(t.status)
    Column(Modifier.width(112.dp).zIndex(if (focused) 1f else 0f)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).graphicsLayer { scaleX = sc; scaleY = sc }
            .onFocusChanged { focused = it.hasFocus; if (it.hasFocus) onFocus() }.tvItem(onClick = onClick)
            .then(if (focused) Modifier.shadow(24.dp, RoundedCornerShape(8.dp)).border(3.dp, Color.White, RoundedCornerShape(9.dp)).padding(4.dp) else Modifier)
            .clip(RoundedCornerShape(7.dp))) { NetImage(t.poster, Modifier.fillMaxSize(), ContentScale.Crop) }
        Spacer(Modifier.height(6.dp))
        Text2(t.title, 13, O5.text, FontWeight.SemiBold)
        Box(Modifier.padding(top = 3.dp).background(if (t.status == "requestable") Color.Transparent else bg, RoundedCornerShape(5.dp))
            .then(if (t.status == "requestable") Modifier.background(O5.brand, RoundedCornerShape(5.dp)) else Modifier).padding(6.dp, 1.dp)) { Text2(label, 10, fg, FontWeight.Bold) }
    }
}
