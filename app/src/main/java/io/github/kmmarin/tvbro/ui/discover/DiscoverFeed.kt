// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.discover

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.github.kmmarin.tvbro.AppModel
import io.github.kmmarin.tvbro.orbit.DiscoverApi
import io.github.kmmarin.tvbro.orbit.DiscoverPerson
import io.github.kmmarin.tvbro.orbit.DiscoverRequest
import io.github.kmmarin.tvbro.orbit.DiscoverRow
import io.github.kmmarin.tvbro.orbit.DiscoverTitle
import io.github.kmmarin.tvbro.orbit.DiscoverTitlePage
import io.github.kmmarin.tvbro.orbit.OrbitError
import io.github.kmmarin.tvbro.orbit.OrbitLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What Discover can show. */
enum class DiscoverState { LOADING, NO_ORBIT, NOT_CONFIGURED, DOWN, READY }

/** A Discover search: what was asked, the results so far (pages loaded one by one), and the person opened, if any. */
@Immutable
data class DiscoverSearch(
    val q: String,
    val results: List<DiscoverTitle> = emptyList(),
    val people: List<DiscoverPerson> = emptyList(),
    val page: Int = 0,
    val pages: Int = 1,
    val total: Int? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val stale: Boolean = false,
    /** A person whose "known for" titles are shown instead of the results. */
    val person: DiscoverPerson? = null,
) {
    val hasMore: Boolean get() = page < pages
}

/** The open title page: [title] is drawn at once, [page] once `/v1/discover/title/...` answered. */
@Immutable
data class TitleView(
    val title: DiscoverTitle,
    val page: DiscoverTitlePage? = null,
    val error: String? = null,
    /** The season chooser is open (shows). */
    val choosing: Boolean = false,
    val pick: Set<Int> = emptySet(),
) {
    /** The page, or the title alone while it loads. */
    val shown: DiscoverTitlePage get() = page ?: DiscoverTitlePage(title)
}

/**
 * Where the Discover screen was, kept while a title page is open so Back returns to the same tab, rows, pages,
 * scroll positions, search and card. The lazy states live here (not in the composition) for that reason.
 */
class DiscoverUi {
    var tab by mutableIntStateOf(0)
    val list = LazyListState()
    val rowStates = HashMap<String, LazyListState>()
    val grid = LazyGridState()
    /** The card last focused ("rowId|type:tmdb") and whether focus goes back to it. */
    var lastFocus: String? = null
    var restore = false
    /** The title the hero shows (TV), with its eyebrow. */
    var hero: Pair<DiscoverTitle, String>? = null
    fun row(id: String) = rowStates.getOrPut(id) { LazyListState() }
}

/**
 * Discover's data: the Seerr rows (`/v1/discover/rows` and further pages, cached by the server), the account's
 * requests (`/v1/requests`, fresh on every call: download progress comes from there), the search and the open title
 * page. Kept in [AppModel] so reopening Discover, or coming back from a title page, shows the same state at once.
 */
class DiscoverFeed(private val m: AppModel, private val api: DiscoverApi = DiscoverApi()) {
    var state by mutableStateOf(DiscoverState.LOADING); private set
    var rows by mutableStateOf<List<PagedRow>>(emptyList()); private set
    var requests by mutableStateOf<List<DiscoverRequest>>(emptyList()); private set
    /** This account has a Seerr user (it may request). */
    var canRequest by mutableStateOf(true); private set
    /** The account may change the Seerr settings (owner or admin). */
    var admin by mutableStateOf(false); private set
    var busy by mutableStateOf<String?>(null); private set
    /** The search field's text; [search] the search that ran (null: the rows show). */
    var query by mutableStateOf(""); private set
    var search by mutableStateOf<DiscoverSearch?>(null); private set
    var title by mutableStateOf<TitleView?>(null); private set
    val ui = DiscoverUi()

    private var lastLoad = 0L
    private var forSession: String? = null
    private var job: Job? = null
    private var searchJob: Job? = null
    private var titleJob: Job? = null
    private val loading = HashSet<String>()
    private val pageCache = HashMap<String, Deferred<DiscoverRow>>()

    /** Rows and requests. The rows come again after 10 minutes (the server caches them as long) or when [force]d. */
    fun refresh(force: Boolean = false) {
        val link = m.orbit.link
        if (link == null) { state = DiscoverState.NO_ORBIT; rows = emptyList(); requests = emptyList(); return }
        val now = System.currentTimeMillis()
        if (!force && forSession == link.session && state == DiscoverState.READY) {
            if (now - lastLoad < 10 * 60_000) { if (now - lastLoad > 60_000) refreshRequests(); return }
        }
        if (forSession != link.session) {
            rows = emptyList(); requests = emptyList(); state = DiscoverState.LOADING; search = null; query = ""; title = null
            ui.rowStates.clear(); ui.lastFocus = null; ui.hero = null
        }
        forSession = link.session
        lastLoad = now
        pageCache.clear(); loading.clear()
        job?.cancel()
        job = m.viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                try { Result.success(api.rows(link)) } catch (e: Exception) { Result.failure(e) }
            }
            r.onSuccess { raw ->
                rows = DiscoverLogic.initialRows(raw)
                state = DiscoverState.READY
                // Trending shows out of a mostly-film list (and the like): fill the filtered rows from further pages
                rows.filter { DiscoverLogic.keepFilling(it, 0) }.forEach { loadMore(it.id) }
            }
            r.onFailure { e ->
                if (e is CancellationException) throw e
                state = when ((e as? OrbitError)?.code) {
                    409 -> DiscoverState.NOT_CONFIGURED
                    404 -> DiscoverState.NOT_CONFIGURED        // a home server without Discover
                    401 -> DiscoverState.NO_ORBIT
                    else -> if (rows.isEmpty()) DiscoverState.DOWN else state
                }
                if (state == DiscoverState.NOT_CONFIGURED) admin = runCatching { withContext(Dispatchers.IO) { api.status(link) }.admin }.getOrDefault(false)
            }
            if (state == DiscoverState.READY) loadRequests()
        }
    }

    // ---------------------------------------------------------------- rows that load more

    /** Focus reached card [index] of row [id]: loads the next page when it is near the end. */
    fun nearEnd(id: String, index: Int) {
        val row = rows.firstOrNull { it.id == id } ?: return
        if (DiscoverLogic.wantsMore(row, index)) loadMore(id)
    }

    /** The next page(s) of row [id]; a filtered row keeps going until it has [DiscoverLogic.MIN_FILL] titles. */
    fun loadMore(id: String) {
        val link = m.orbit.link ?: return
        if (id in loading) return
        val start = rows.firstOrNull { it.id == id }?.takeIf { it.hasMore } ?: return
        loading += id
        m.viewModelScope.launch {
            try {
                var asked = 0
                var row = start
                while (row.hasMore && asked < DiscoverLogic.MAX_FILL_PAGES) {
                    val d = srcPage(link, row.src, row.page + 1).await(); asked++
                    val before = row.items.size
                    row = DiscoverLogic.withPage(row, d.page, d.pages, d.items)
                    rows = rows.map { if (it.id == id) row else it }
                    if (!DiscoverLogic.keepFilling(row, row.items.size - before)) break
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                // a failed page: stop loading this row for now (focus near the end asks again)
            } finally { loading -= id }
        }
    }

    /** One page of a server list, shared by the rows that filter it (Trending movies and shows share "trending"). */
    private fun srcPage(link: OrbitLink, src: String, page: Int): Deferred<DiscoverRow> =
        pageCache.getOrPut("$src:$page") {
            m.viewModelScope.async(Dispatchers.IO) { api.rowPage(link, src, page) }.also { d ->
                d.invokeOnCompletion { e -> if (e != null) m.viewModelScope.launch { pageCache.remove("$src:$page") } }
            }
        }

    // ---------------------------------------------------------------- requests

    /** Only the requests (live download progress). */
    fun refreshRequests() {
        if (state != DiscoverState.READY || m.orbit.link == null) return
        m.viewModelScope.launch { loadRequests() }
    }

    private suspend fun loadRequests() {
        val link = m.orbit.link ?: return
        try {
            val (list, linked) = withContext(Dispatchers.IO) { api.requests(link) }
            requests = list; canRequest = linked
        } catch (e: CancellationException) { throw e } catch (e: Exception) { /* keep the last list */ }
    }

    /** True while something asked for is still on its way (the screen then polls the requests more often). */
    val active: Boolean get() = requests.any { r -> r.download != null || r.mediaStatus == "processing" || r.status == "approved" }

    /** The freshest state of [t]: the request list's copy (fetched each time) over the cached row's. */
    fun current(t: DiscoverTitle): DiscoverTitle {
        val r = requests.firstOrNull { it.type == t.type && it.tmdb == t.tmdb } ?: return t
        val rt = r.title ?: return t.copy(download = r.download ?: t.download)
        return t.copy(status = rt.status, requestStatus = rt.requestStatus ?: t.requestStatus, download = r.download ?: t.download, play = rt.play ?: t.play)
    }

    /**
     * Request from a row, the hero or a search result: a film is asked for at once; a show opens its title page with
     * the season chooser (like Seerr).
     */
    fun request(t: DiscoverTitle) {
        if (t.isShow) { openTitle(t, choose = true); return }
        send(t, null)
    }

    /** POST /v1/requests for [t] (the [seasons] of a show, or all open ones); the outcome as a toast. */
    fun send(t: DiscoverTitle, seasons: List<Int>?) {
        val link = m.orbit.link ?: return m.toast("Link this TV to Orbit first")
        if (!canRequest) return m.toast(NOT_LINKED)
        if (busy != null) return
        busy = t.key
        m.viewModelScope.launch {
            val msg = try {
                val (now, created) = withContext(Dispatchers.IO) { api.request(link, t, seasons) }
                if (now != null) patch(now)
                val what = if (t.isShow && seasons != null) " · " + DiscoverLogic.seasonsText(seasons, DiscoverLogic.requestable(title?.page?.seasons.orEmpty()).size) else ""
                if (created) "Requested: ${t.title}$what" else "${t.title} is already requested or available"
            } catch (e: OrbitError) {
                when (e.code) {
                    409 -> NOT_LINKED
                    503 -> "Seerr cannot be reached right now"
                    else -> "Not requested: " + (e.message ?: "the request was refused")
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { "The home server did not answer" }
            busy = null
            m.toast(msg)
            title?.let { v -> if (v.title.key == t.key) { title = v.copy(choosing = false, pick = emptySet()); loadTitle(quiet = true) } }
            loadRequests()
        }
    }

    /** The new state of a title (after a request) in the rows, the search and the open page. */
    private fun patch(t: DiscoverTitle) {
        fun fix(x: DiscoverTitle) = if (x.key == t.key) x.copy(status = t.status, requestStatus = t.requestStatus, download = t.download ?: x.download, play = t.play ?: x.play) else x
        rows = rows.map { r -> if (r.items.any { it.key == t.key }) r.copy(items = r.items.map(::fix)) else r }
        search = search?.let { s -> s.copy(results = s.results.map(::fix)) }
        title = title?.let { v -> if (v.title.key == t.key) v.copy(title = fix(v.title), page = v.page?.let { p -> p.copy(title = fix(p.title)) }) else v }
    }

    // ---------------------------------------------------------------- search

    /** The field's text changed (the screen runs [runSearch] once it settles); empty text goes back to the rows. */
    fun type(text: String) {
        query = text
        if (text.isBlank()) clearSearch(keepText = true)
    }

    /** Runs a search for [q] (1–100 characters); the same search again does nothing unless [force]d. */
    fun runSearch(q0: String, force: Boolean = false) {
        val q = q0.trim().take(100)
        val link = m.orbit.link ?: return
        if (q.isEmpty()) { clearSearch(keepText = true); return }
        if (!force && search?.q == q) return
        query = q0
        searchJob?.cancel()
        search = DiscoverSearch(q)
        ui.grid.requestScrollToItem(0)
        searchJob = m.viewModelScope.launch { loadSearch(link, q) }
    }

    /** The next page of results (focus near the end of the grid). */
    fun searchMore() {
        val s = search ?: return
        val link = m.orbit.link ?: return
        if (s.loading || !s.hasMore || s.person != null) return
        search = s.copy(loading = true)
        searchJob = m.viewModelScope.launch { loadSearch(link, s.q) }
    }

    private suspend fun loadSearch(link: OrbitLink, q: String) {
        val s0 = search ?: return
        try {
            val d = withContext(Dispatchers.IO) { api.search(link, q, s0.page + 1) }
            val s = search?.takeIf { it.q == q } ?: return
            search = s.copy(
                results = DiscoverLogic.addResults(s.results, d.results), people = (s.people + d.people).distinctBy { it.id },
                page = d.page, pages = d.pages, total = if (d.page == 1) d.total else s.total, loading = false, stale = d.stale, error = null,
            )
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            val s = search?.takeIf { it.q == q } ?: return
            val why = (e as? OrbitError)?.let { if (it.code == 503) "Seerr cannot be reached right now" else it.message } ?: "The home server did not answer"
            if (s.results.isEmpty() && s.people.isEmpty()) search = s.copy(loading = false, error = why, pages = s.page)
            else { search = s.copy(loading = false, pages = s.page); m.toast("Could not load more: $why") }
        }
    }

    /** Shows what [p] is known for (OK on a person); null goes back to all results. */
    fun showPerson(p: DiscoverPerson?) { search = search?.copy(person = p) }

    fun clearSearch(keepText: Boolean = false) {
        searchJob?.cancel()
        search = null
        if (!keepText) query = ""
    }

    // ---------------------------------------------------------------- title page

    /** Opens the title page of [t] (drawn at once from [t]); [choose] opens the season chooser of a show at once. */
    fun openTitle(t: DiscoverTitle, choose: Boolean = false) {
        title = TitleView(current(t), choosing = choose && t.isShow)
        loadTitle()
    }

    fun closeTitle() { titleJob?.cancel(); title = null }

    /** `/v1/discover/title/...` for the open page; [quiet] keeps errors to a toast (a refresh while it is open). */
    fun loadTitle(quiet: Boolean = false) {
        val v = title ?: return
        val link = m.orbit.link ?: return
        titleJob?.cancel()
        titleJob = m.viewModelScope.launch {
            try {
                val p = withContext(Dispatchers.IO) { api.titlePage(link, v.title.type, v.title.tmdb) }
                val cur = title?.takeIf { it.title.key == v.title.key } ?: return@launch
                if (p == null) { if (!quiet) title = cur.copy(error = "Seerr does not know this title"); return@launch }
                val pick = if (cur.choosing && cur.pick.isEmpty()) DiscoverLogic.requestable(p.seasons).map { it.season }.toSet() else cur.pick
                title = cur.copy(title = p.title, page = p, error = null, pick = pick)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                val cur = title?.takeIf { it.title.key == v.title.key } ?: return@launch
                val why = when ((e as? OrbitError)?.code) { 404 -> "Seerr does not know this title"; 503 -> "Seerr cannot be reached right now"; else -> "Could not load this title" }
                if (quiet || cur.page != null) m.toast(why) else title = cur.copy(error = why)
            }
        }
    }

    /** Request on the title page: a film at once, a show opens (or sends) the season chooser. */
    fun requestOpen() {
        val v = title ?: return
        val p = v.shown
        if (!DiscoverLogic.canRequest(p) && v.page != null) return
        if (!v.title.isShow) { send(v.title, null); return }
        if (v.page == null) { title = v.copy(choosing = true); return }
        title = v.copy(choosing = true, pick = v.pick.ifEmpty { DiscoverLogic.requestable(p.seasons).map { it.season }.toSet() })
    }

    fun toggleSeason(n: Int) { title = title?.let { it.copy(pick = DiscoverLogic.toggleSeason(it.pick, n, it.shown.seasons)) } }
    fun toggleAllSeasons() { title = title?.let { it.copy(pick = DiscoverLogic.toggleAll(it.pick, it.shown.seasons)) } }
    fun cancelChooser() { title = title?.copy(choosing = false, pick = emptySet()) }

    /** Sends the chosen seasons. */
    fun sendChosen() {
        val v = title ?: return
        val open = DiscoverLogic.requestable(v.shown.seasons).map { it.season }.toSet()
        val chosen = v.pick.filter { it in open }.sorted()
        if (chosen.isEmpty()) { m.toast("Choose at least one season"); return }
        send(v.title, chosen)
    }

    companion object {
        const val NOT_LINKED = "Your account is not linked to a Seerr user yet: ask the owner to link it"

        /** The rows as plain [DiscoverRow]s (tests, older callers). */
        fun shapeRows(raw: List<DiscoverRow>): List<DiscoverRow> = DiscoverLogic.initialRows(raw).filter { it.items.isNotEmpty() }.map { it.toRow() }
    }
}
