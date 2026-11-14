// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.movies

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.github.kmmarin.tvbro.AppModel
import io.github.kmmarin.tvbro.data.SearchIndex
import io.github.kmmarin.tvbro.data.SearchStore
import io.github.kmmarin.tvbro.data.VodItem
import io.github.kmmarin.tvbro.orbit.CatalogApi
import io.github.kmmarin.tvbro.orbit.CatalogCollection
import io.github.kmmarin.tvbro.orbit.CatalogItem
import io.github.kmmarin.tvbro.orbit.CatalogNext
import io.github.kmmarin.tvbro.orbit.OrbitError
import io.github.kmmarin.tvbro.ui.orbit5.BrowseSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.WeakHashMap

/**
 * One film or show on the Movies / Shows pages and the title page, from the catalog or from the TV's own data.
 * [vod] is the title as the TV keeps it (My list, resume, playback). For Plex / Jellyfin titles it carries the
 * catalog key ("m|<connection>|<item>") so My list lines up, but the TV cannot play them yet ([media]).
 */
@Immutable
data class Title(
    val key: String,
    val title: String,
    val show: Boolean,
    val poster: String? = null,
    val backdrop: String? = null,
    val colors: List<String>? = null,
    val year: String? = null,
    val rating: String? = null,
    val genres: List<String> = emptyList(),
    /** "iptv" | "plex" | "jellyfin". */
    val source: String = "iptv",
    val progress: Float? = null,
    /** Caption under the card ("2024 · Drama", "S2 E4 · Ballast", "41 min left"). */
    val meta: String? = null,
    val vod: VodItem? = null,
    /** The catalog's own item id (IPTV VOD id, or the media server's item id) and media connection. */
    val id: String = "",
    val conn: String? = null,
    /** A catalog IPTV title of a PIN-locked category. */
    val locked: Boolean = false,
    val next: CatalogNext? = null,
) {
    val media: Boolean get() = source == "plex" || source == "jellyfin"
}

@Immutable
data class TitleRow(val id: String, val title: String, val items: List<Title>, val landscape: Boolean = false)

/** A collection card: from the catalog, or (local data) one of the provider's VOD categories ([category]). */
@Immutable
data class Coll(
    val id: String, val name: String, val count: Int, val poster: String?, val backdrop: String?, val colors: List<String>?,
    val source: String = "", val category: String? = null,
)

enum class SubTab(val label: String) { RECOMMENDED("Recommended"), BROWSE("Browse"), COLLECTIONS("Collections") }

enum class BrowseSort(val label: String, val param: String) {
    ADDED("Recently added", "added"), RELEASED("Recently released", "released"), TITLE("Title", "title"), RATING("Rating", "rating")
}

/** Mapping catalog and local titles to [Title] (pure; unit tested). */
object Titles {
    fun of(c: CatalogItem): Title {
        val next = c.next?.let { n -> listOfNotNull((if (n.season > 0) "S${n.season} " else "") + "E${n.num}", n.title?.takeIf { it.isNotBlank() }).joinToString(" · ") }
        val ps = c.progressSecs; val pd = c.progressDur   // locals: CatalogItem is in shared (no smart cast across modules)
        val left = if (!c.isShow && ps != null && pd != null && pd > ps && ps > 0)
            "${(pd - ps) / 60} min left" else null
        val meta = next ?: left ?: listOfNotNull(c.year, c.genres.firstOrNull()).joinToString(" · ").ifEmpty { null }
        return Title(
            c.key, c.title, c.isShow, c.poster, c.backdrop, c.colors, c.year, c.rating, c.genres, c.source,
            c.progress?.takeIf { it > 0f }, meta, vodOf(c), c.id, c.conn, c.locked, c.next,
        )
    }

    /**
     * The catalog title as the TV's own VodItem: IPTV "m|p0|123" -> source p0, id 123; Plex / Jellyfin keep their
     * key with the connection as the source (only My list uses it). Null for keys of another shape.
     */
    fun vodOf(c: CatalogItem): VodItem? {
        val p = c.key.split('|')
        if (p.size < 3 || (p[0] != "m" && p[0] != "s")) return null
        val kind = if (p[0] == "s") "series" else "movie"
        return if (c.source == "iptv") VodItem(c.key, p[1], kind, p.drop(2).joinToString("|"), c.title, c.poster, c.genres.firstOrNull() ?: "", c.rating, "")
        else VodItem(c.key, c.conn ?: p[1], kind, c.id.ifEmpty { p.drop(2).joinToString("|") }, c.title, c.poster, "", c.rating, "")
    }

    fun of(v: VodItem, meta: String? = v.category.takeIf { it.isNotBlank() }): Title = Title(
        v.key, v.name, v.kind == "series", v.poster, rating = v.rating?.takeIf { it.isNotBlank() && it != "0" }, meta = meta, vod = v, id = v.id,
    )

    fun ofColl(c: CatalogCollection) = Coll(c.id, c.name, c.count, c.poster, c.backdrop, c.colors, c.source)

    /** "1,284 titles" / "1 title". */
    fun count(n: Int, more: Boolean = false): String =
        java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(n) + (if (more) "+" else "") + if (n == 1 && !more) " title" else " titles"

    /** "2001 – 2011", "2005" or null. */
    fun years(items: List<Title>): String? {
        val y = items.mapNotNull { it.year?.toIntOrNull() }
        if (y.isEmpty()) return null
        val a = y.min(); val b = y.max()
        return if (a == b) "$a" else "$a – $b"
    }

    /** The two most common genres of [items]. */
    fun topGenres(items: List<Title>): List<String> =
        items.flatMap { it.genres }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(2).map { it.key }
}

/** Movies or Shows: what each sub-page shows and where the user was. */
class KindState(val shows: Boolean) {
    var tab by mutableStateOf(SubTab.RECOMMENDED)

    // Recommended
    var hero by mutableStateOf<List<Title>>(emptyList())
    var rows by mutableStateOf<List<TitleRow>>(emptyList())
    var recLoaded by mutableStateOf(false)
    internal var recAt = 0L

    // Browse (catalog)
    var source by mutableStateOf(BrowseSource.ALL)
    var genre by mutableStateOf("")
    var sort by mutableStateOf(BrowseSort.ADDED)
    var items by mutableStateOf<List<Title>>(emptyList())
    var total by mutableStateOf(0)
    var genres by mutableStateOf<List<String>>(emptyList())
    var browseLoaded by mutableStateOf(false)
    var browseMsg by mutableStateOf("")
    internal var loadingPage = false
    /** Local data: the user picked a category (otherwise Browse shows all titles). */
    internal var pickedCategory = false

    // Collections
    var collections by mutableStateOf<List<Coll>>(emptyList())
    var collLoaded by mutableStateOf(false)
    var openColl by mutableStateOf<Coll?>(null)
    var collItems by mutableStateOf<List<Title>>(emptyList())
    var collMsg by mutableStateOf("")

    /** The title last focused on these pages; with [restore] focus goes back to it (back from its page). */
    var lastKey: String? = null
    var restore = false

    val hasMore: Boolean get() = items.size < total
    val kind: String get() = if (shows) "shows" else "movies"
}

/**
 * State of the Movies and Shows sections, kept while a title's page is open: the catalog (`/v1/catalog/{kind}/…`)
 * when the TV is linked to a server that has it, else the TV's own data (see [local]).
 */
class MoviesFeed(private val m: AppModel, private val api: CatalogApi = CatalogApi()) {
    val movies = KindState(false)
    val shows = KindState(true)
    fun of(shows: Boolean) = if (shows) this.shows else movies

    /** The server answered 404 for the catalog: local data until the link changes. */
    private var noCatalogFor by mutableStateOf<String?>(null)
    private var profile: String? = null

    /** A profile switch or a new link: everything is loaded again. */
    fun linkChanged(key: String?) {
        if (key == profile) return
        profile = key
        for (k in listOf(movies, shows)) {
            k.recLoaded = false; k.rows = emptyList(); k.hero = emptyList()
            k.browseLoaded = false; k.items = emptyList(); k.total = 0; k.genres = emptyList()
            k.collLoaded = false; k.collections = emptyList(); k.openColl = null; k.collItems = emptyList()
        }
    }
    /** The last catalog call failed (unreachable): local data, tried again on the next refresh. */
    var offline by mutableStateOf(false); private set

    /** True when the pages are built from the TV's own data (not linked, or no catalog on the server). */
    val local: Boolean get() { val l = m.orbit.link; return l == null || noCatalogFor == l.session || offline }

    /** Titles seen in the catalog by key: the title page uses their artwork, colours and source. */
    private val known = object : LinkedHashMap<String, Title>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Title>?) = size > 600
    }
    fun remember(t: Title) { known[t.key] = t }
    fun known(key: String?): Title? = key?.let { known[it] }

    private var recJob: Job? = null
    private var browseJob: Job? = null
    private var collJob: Job? = null

    private suspend fun <T> call(block: (io.github.kmmarin.tvbro.orbit.OrbitLink) -> T): T? {
        val link = m.orbit.link ?: return null
        if (noCatalogFor == link.session) return null
        return try { withContext(Dispatchers.IO) { block(link) }.also { offline = false } }
        catch (e: OrbitError) { if (e.code == 404) noCatalogFor = link.session else offline = true; null }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { offline = true; null }
    }

    // ---------------------------------------------------------------- Recommended

    fun refreshRecommended(k: KindState, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && k.recLoaded && now - k.recAt < 60_000) return
        k.recAt = now
        recJob?.cancel()
        recJob = m.viewModelScope.launch {
            val feed = call { api.recommended(it, k.kind) }
            if (feed != null) {
                k.hero = feed.hero.map { Titles.of(it) }.onEach { remember(it) }
                k.rows = feed.rows.map { r -> TitleRow(r.id, r.title, r.items.map { Titles.of(it) }.onEach { remember(it) }, landscape = r.id == "continue") }
                    .filter { it.items.isNotEmpty() }
            } else {
                k.hero = emptyList()
                k.rows = localRows(k.shows)
            }
            k.recLoaded = true
        }
    }

    /** Local Recommended: Continue watching, My list, recently added (newest provider ids), History. */
    private suspend fun localRows(shows: Boolean): List<TitleRow> {
        val out = ArrayList<TitleRow>()
        m.refreshLists()
        val kind = if (shows) "series" else "movie"
        val cont = ArrayList<Title>()
        if (shows) {
            for ((v, r) in m.continueShows()) {
                val st = m.watch.state("e|${v.sourceId}|${r.nextEp}")
                val code = (if (r.nextSeason > 0) "S${r.nextSeason} " else "") + "E${r.nextNum}"
                cont += Titles.of(v, listOfNotNull(code, r.nextTitle?.takeIf { it.isNotBlank() }).joinToString(" · "))
                    .copy(progress = if (st != null && st.inProgress && st.durMs > 0) st.fraction else null)
            }
        } else {
            for (v in m.histSnaps.filter { it.kind != "series" && !m.isWatched(it.key) && m.store.pos(it.key) > 5000 }) {
                val pos = m.store.pos(v.key); val dur = m.store.dur(v.key)
                val left = if (dur > pos) (dur - pos) / 60_000 else 0
                cont += Titles.of(v, if (left > 0) "$left min left" else "Resume").copy(progress = if (dur > 0) pos.toFloat() / dur else null)
            }
        }
        fun allowed(t: Title) = t.vod?.let { !m.isLocked(it) } ?: true
        cont.filter(::allowed).distinctBy { it.key }.takeIf { it.isNotEmpty() }?.let { out += TitleRow("continue", "Continue watching", it, landscape = true) }
        m.mySnaps.filter { it.kind == kind }.map { Titles.of(it) }.filter(::allowed).takeIf { it.isNotEmpty() }?.let { out += TitleRow("mylist", "My list", it) }
        val recent = withContext(Dispatchers.IO) { recentlyAdded(if (shows) SearchIndex.SERIES else SearchIndex.MOVIE) }
        recent.map { Titles.of(it) }.filter(::allowed).takeIf { it.isNotEmpty() }?.let { out += TitleRow("recent", "Recently added", it) }
        m.histSnaps.filter { it.kind == kind }.map { Titles.of(it) }.filter(::allowed).takeIf { it.isNotEmpty() }?.let { out += TitleRow("history", "History", it) }
        return out
    }

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

    /** The TV's own entry for an IPTV title (real category for the parental lock, and the file type), or null. */
    suspend fun resolveLocal(key: String): VodItem? = withContext(Dispatchers.IO) {
        try {
            SearchStore.use(m.getApplication<android.app.Application>()) { db ->
                var v: VodItem? = null
                db.query("SELECT k,src,xid,name,img,cat,rating,ext,kind FROM item WHERE k=? LIMIT 1", arrayOf(key)) { r ->
                    v = VodItem(r.str(0) ?: "", r.str(1) ?: "", if (r.long(8).toInt() == SearchIndex.SERIES) "series" else "movie", r.str(2) ?: "",
                        r.str(3) ?: "", r.str(4), r.str(5) ?: "", r.str(6), r.str(7) ?: "")
                }
                v
            }
        } catch (e: Exception) { null }
    }

    // ---------------------------------------------------------------- Browse (catalog)

    /** Loads Browse from the start (filters changed) or, with [more], the next page. */
    fun loadBrowse(k: KindState, more: Boolean = false) {
        if (more && (k.loadingPage || !k.hasMore)) return
        if (!more) { browseJob?.cancel(); k.items = emptyList(); k.total = 0; k.browseLoaded = false; k.browseMsg = "" }
        k.loadingPage = true
        val offset = k.items.size
        val src = k.source.param; val genre = k.genre; val sort = k.sort.param
        browseJob = m.viewModelScope.launch {
            try {
                val page = call { api.browse(it, k.kind, src, genre, sort, offset, PAGE) }
                if (page == null) { k.browseLoaded = true; return@launch }
                if (k.source.param != src || k.genre != genre || k.sort.param != sort) return@launch
                val add = page.items.map { Titles.of(it) }.onEach { remember(it) }
                k.items = if (more) k.items + add else add
                k.total = page.total
                if (page.genres.isNotEmpty() || !more) k.genres = page.genres
                k.browseLoaded = true
                if (k.items.isEmpty()) k.browseMsg = "Nothing here" + if (genre.isNotEmpty()) " in $genre." else "."
            } finally { k.loadingPage = false }
        }
    }

    // ---------------------------------------------------------------- Collections

    fun loadCollections(k: KindState, force: Boolean = false) {
        if (k.collLoaded && !force) return
        collJob?.cancel()
        collJob = m.viewModelScope.launch {
            val list = call { api.collections(it, k.kind) }
            k.collections = list?.map { Titles.ofColl(it) } ?: localCategories(k.shows)
            k.collLoaded = true
        }
    }

    /** Local data: the provider's VOD categories, with their size and the first title's poster. */
    private suspend fun localCategories(shows: Boolean): List<Coll> = withContext(Dispatchers.IO) {
        val kind = if (shows) SearchIndex.SERIES else SearchIndex.MOVIE
        val fromDb = try {
            SearchStore.use(m.getApplication<android.app.Application>()) { db ->
                val out = ArrayList<Coll>()
                db.query("SELECT cat, COUNT(*), (SELECT img FROM item j WHERE j.kind=i.kind AND j.cat=i.cat AND j.img IS NOT NULL AND j.img<>'' ORDER BY j.id LIMIT 1) " +
                    "FROM item i WHERE kind=? GROUP BY cat ORDER BY MIN(id)", arrayOf(kind.toString())) { r ->
                    val c = r.str(0) ?: return@query
                    out += Coll("cat:$c", c, r.long(1).toInt(), r.str(2), null, null, "iptv", category = c)
                }
                out
            }
        } catch (e: Exception) { null }
        fromDb?.takeIf { it.isNotEmpty() } ?: m.vodCatNames.map { Coll("cat:$it", it, 0, null, null, null, "iptv", category = it) }
    }

    fun openCollection(k: KindState, c: Coll) {
        k.openColl = c; k.collItems = emptyList(); k.collMsg = "Loading…"
        collJob?.cancel()
        collJob = m.viewModelScope.launch {
            val r = call { api.collection(it, c.id) }
            if (r == null) { k.collMsg = "Could not load this collection."; return@launch }
            r.first?.let { k.openColl = Titles.ofColl(it) }
            k.collItems = r.second.map { Titles.of(it) }.onEach { remember(it) }
            k.collMsg = if (k.collItems.isEmpty()) "Nothing in this collection." else ""
        }
    }

    companion object {
        const val PAGE = 96   // 12 rows of 8
    }
}

private val feeds = WeakHashMap<AppModel, MoviesFeed>()

/** The Movies / Shows state of this app model (kept while the title page is open). */
val AppModel.movies: MoviesFeed get() = synchronized(feeds) { feeds.getOrPut(this) { MoviesFeed(this) } }
