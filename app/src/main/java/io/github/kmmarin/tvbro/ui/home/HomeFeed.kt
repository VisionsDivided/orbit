// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.home

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.github.kmmarin.tvbro.AppModel
import io.github.kmmarin.tvbro.data.Channel
import io.github.kmmarin.tvbro.data.SearchIndex
import io.github.kmmarin.tvbro.data.SearchStore
import io.github.kmmarin.tvbro.data.SeriesRow
import io.github.kmmarin.tvbro.data.VodItem
import io.github.kmmarin.tvbro.orbit.CatalogApi
import io.github.kmmarin.tvbro.orbit.CatalogItem
import io.github.kmmarin.tvbro.orbit.OrbitError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One card on Home: a film or show (local or from the catalog), or a live channel. */
@Immutable
data class Tile(
    val key: String,
    val title: String,
    val meta: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val colors: List<String>? = null,
    /** "iptv" | "plex" | "jellyfin" | "" (channels). */
    val source: String = "iptv",
    val progress: Float? = null,
    val year: String? = null,
    val rating: String? = null,
    val genres: List<String> = emptyList(),
    val show: Boolean = false,
    /** The title as the TV plays it (null for Plex / Jellyfin titles, which the TV cannot play yet). */
    val vod: VodItem? = null,
    val channel: Channel? = null,
    val seriesRow: SeriesRow? = null,
    /** A catalog title in a PIN-locked category: opening it asks for the PIN. */
    val locked: Boolean = false,
    /** The catalog's own item id and media connection (Plex / Jellyfin titles open their page with these). */
    val id: String = "",
    val conn: String? = null,
)

/** A row on Home; [landscape] rows use 16:9 cards (Continue watching, live channels). */
@Immutable
data class HomeRow(val id: String, val title: String, val tiles: List<Tile>, val landscape: Boolean = false)

/**
 * The Home feed: `GET /v1/catalog/home` when the TV is linked, else (or when the server has no catalog yet: 404) the
 * TV's own data: Continue watching from the watch state, My list, recently added from the stored catalogue, and the
 * favourite channels with what is on now. Refreshed when Home opens (at most once a minute) and after a change.
 */
class HomeFeed(private val m: AppModel, private val api: CatalogApi = CatalogApi()) {
    var rows by mutableStateOf<List<HomeRow>>(emptyList()); private set
    /** The catalog's hero titles (empty with local data: Home then features the first cards). */
    var hero by mutableStateOf<List<Tile>>(emptyList()); private set
    var fromServer by mutableStateOf(false); private set
    var loaded by mutableStateOf(false); private set
    private var lastLoad = 0L
    private var job: Job? = null
    /** The server answered 404 for the catalog: do not ask again until the link changes. */
    private var noCatalogFor: String? = null

    fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && loaded && now - lastLoad < 60_000) return
        lastLoad = now
        job?.cancel()
        job = m.viewModelScope.launch {
            val link = m.orbit.link
            val server = if (link != null && noCatalogFor != link.session) {
                try { withContext(Dispatchers.IO) { api.home(link) } }
                catch (e: OrbitError) { if (e.code == 404) noCatalogFor = link.session; null }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { null }
            } else null
            val r = if (server != null) {
                fromServer = true
                hero = server.hero.map { tileOf(it) }
                // the live row goes after Continue watching and My list, as with local data
                val list = server.rows.map { row -> HomeRow(row.id, row.title, row.items.map { tileOf(it) }, landscape = row.id == "continue") }
                    .filter { it.tiles.isNotEmpty() }.toMutableList()
                liveRow()?.let { list.add(list.indexOfFirst { it.id != "continue" && it.id != "mylist" }.let { i -> if (i < 0) list.size else i }, it) }
                list
            } else {
                fromServer = false
                hero = emptyList()
                localRows()
            }
            rows = r; loaded = true
        }
    }

    // ---- catalog items ----
    private fun tileOf(c: CatalogItem): Tile {
        val vod = if (c.source == "iptv") vodOf(c) else null
        val next = c.next?.let { n -> listOfNotNull((if (n.season > 0) "S${n.season} " else "") + "E${n.num}", n.title?.takeIf { it.isNotBlank() }).joinToString(" · ") }
        val ps = c.progressSecs; val pd = c.progressDur   // locals: CatalogItem is in shared (no smart cast across modules)
        val left = if (!c.isShow && ps != null && pd != null && pd > ps) "${(pd - ps) / 60} min left" else null
        val meta = next ?: left ?: listOfNotNull(c.year, c.genres.firstOrNull()).joinToString(" · ").ifEmpty { null }
        return Tile(c.key, c.title, meta, c.poster, c.backdrop, c.colors, c.source, c.progress, c.year, c.rating, c.genres, c.isShow, vod, locked = c.locked, id = c.id, conn = c.conn)
    }

    // ---- local data ----
    private suspend fun localRows(): List<HomeRow> {
        val out = ArrayList<HomeRow>()
        m.refreshLists()
        continueRow()?.let { out += it }
        val mine = m.mySnaps.map { vodTile(it) }.filter { allowed(it) }
        if (mine.isNotEmpty()) out += HomeRow("mylist", "My list", mine)
        liveRow()?.let { out += it }
        val (movies, shows) = withContext(Dispatchers.IO) { recentlyAdded(SearchIndex.MOVIE) to recentlyAdded(SearchIndex.SERIES) }
        movies.map { vodTile(it) }.filter { allowed(it) }.takeIf { it.isNotEmpty() }?.let { out += HomeRow("recent-movies", "Recently added movies", it) }
        shows.map { vodTile(it) }.filter { allowed(it) }.takeIf { it.isNotEmpty() }?.let { out += HomeRow("recent-shows", "Recently added shows", it) }
        return out
    }

    /** Films not finished and shows in progress (the next episode on the card), newest first, like My list's row. */
    private fun continueRow(): HomeRow? {
        val showList = m.continueShows()
        val films = m.histSnaps.filter { it.kind != "series" && !m.isWatched(it.key) && m.store.pos(it.key) > 5000 }
        val tiles = ArrayList<Tile>()
        for (v in films) {
            val pos = m.store.pos(v.key); val dur = m.store.dur(v.key)
            val left = if (dur > pos) (dur - pos) / 60_000 else 0
            tiles += vodTile(v).copy(progress = if (dur > 0) pos.toFloat() / dur else null, meta = if (left > 0) "$left min left" else "Resume")
        }
        for ((v, r) in showList) {
            val st = m.watch.state("e|${v.sourceId}|${r.nextEp}")
            val code = (if (r.nextSeason > 0) "S${r.nextSeason} " else "") + "E${r.nextNum}"
            tiles += vodTile(v).copy(meta = listOfNotNull(code, r.nextTitle?.takeIf { it.isNotBlank() }).joinToString(" · "),
                progress = if (st != null && st.inProgress && st.durMs > 0) st.fraction else null, seriesRow = r)
        }
        val visible = tiles.filter { allowed(it) }.distinctBy { it.key }
        return if (visible.isEmpty()) null else HomeRow("continue", "Continue watching · films and shows", visible, landscape = true)
    }

    private fun liveRow(): HomeRow? {
        val now = System.currentTimeMillis() / 1000
        val favs = m.shown.filter { it.key in m.favourites && !m.isLocked(it) }.take(20)
        if (favs.isEmpty()) return null
        return HomeRow("live", "Live now on your favourites", favs.map { c ->
            val cur = m.epg.nowNext(c, now).first
            Tile("c|" + c.key, c.name, cur?.title ?: "No guide data", poster = c.logo, source = "", channel = c,
                progress = cur?.let { io.github.kmmarin.tvbro.data.ProgTime.progress(it.start, it.stop, now) })
        }, landscape = true)
    }

    private fun vodTile(v: VodItem) = Tile(
        v.key, v.name, v.category.takeIf { it.isNotBlank() }, v.poster, null, null, "iptv", null, null, v.rating?.takeIf { it.isNotBlank() && it != "0" },
        emptyList(), v.kind == "series", v,
    )

    /** Parental lock: titles of locked categories stay off Home until unlocked. */
    private fun allowed(t: Tile): Boolean = t.vod?.let { !m.isLocked(it) } ?: true

    /** Newest titles of the stored catalogue: the provider's highest ids (the catalogue stores no "added" date). */
    private fun recentlyAdded(kind: Int, limit: Int = 30): List<VodItem> = try {
        SearchStore.use(m.getApplication<android.app.Application>()) { db ->
            val out = ArrayList<VodItem>()
            db.query("SELECT k,src,xid,name,img,cat,rating,ext FROM item WHERE kind=? ORDER BY CAST(xid AS INTEGER) DESC LIMIT $limit", arrayOf(kind.toString())) { r ->
                out += VodItem(r.str(0) ?: "", r.str(1) ?: "", if (kind == SearchIndex.SERIES) "series" else "movie", r.str(2) ?: "", r.str(3) ?: "",
                    r.str(4), r.str(5) ?: "", r.str(6), r.str(7) ?: "")
            }
            out.filter { it.key.isNotEmpty() }
        } ?: emptyList()
    } catch (e: Exception) { emptyList() }

    companion object {
        /** A catalog IPTV title as the TV's own VodItem ("m|p0|123" -> source p0, id 123); null for other keys. */
        fun vodOf(c: CatalogItem): VodItem? {
            val p = c.key.split('|')
            if (p.size < 3 || (p[0] != "m" && p[0] != "s")) return null
            return VodItem(c.key, p[1], if (p[0] == "s") "series" else "movie", p.drop(2).joinToString("|"), c.title, c.poster,
                c.genres.firstOrNull() ?: "", c.rating, "")
        }
    }
}
