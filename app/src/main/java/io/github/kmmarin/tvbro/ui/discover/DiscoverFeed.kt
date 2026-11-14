// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.discover

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.github.kmmarin.tvbro.AppModel
import io.github.kmmarin.tvbro.orbit.DiscoverApi
import io.github.kmmarin.tvbro.orbit.DiscoverRequest
import io.github.kmmarin.tvbro.orbit.DiscoverRow
import io.github.kmmarin.tvbro.orbit.DiscoverTitle
import io.github.kmmarin.tvbro.orbit.OrbitError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What Discover can show. */
enum class DiscoverState { LOADING, NO_ORBIT, NOT_CONFIGURED, DOWN, READY }

/**
 * Discover's data: the Seerr rows (`/v1/discover/rows`, cached by the server) and the account's requests
 * (`/v1/requests`, fresh on every call: download progress comes from there). Kept in [AppModel] so reopening Discover
 * shows the last answer at once.
 */
class DiscoverFeed(private val m: AppModel, private val api: DiscoverApi = DiscoverApi()) {
    var state by mutableStateOf(DiscoverState.LOADING); private set
    var rows by mutableStateOf<List<DiscoverRow>>(emptyList()); private set
    var requests by mutableStateOf<List<DiscoverRequest>>(emptyList()); private set
    /** This account has a Seerr user (it may request). */
    var canRequest by mutableStateOf(true); private set
    /** The account may change the Seerr settings (owner or admin). */
    var admin by mutableStateOf(false); private set
    var busy by mutableStateOf<String?>(null); private set
    private var lastLoad = 0L
    private var forSession: String? = null
    private var job: Job? = null

    /** Rows and requests; at most once a minute unless [force]. */
    fun refresh(force: Boolean = false) {
        val link = m.orbit.link
        if (link == null) { state = DiscoverState.NO_ORBIT; rows = emptyList(); requests = emptyList(); return }
        val now = System.currentTimeMillis()
        if (!force && forSession == link.session && state == DiscoverState.READY && now - lastLoad < 60_000) return
        if (forSession != link.session) { rows = emptyList(); requests = emptyList(); state = DiscoverState.LOADING }
        forSession = link.session
        lastLoad = now
        job?.cancel()
        job = m.viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                try { Result.success(api.rows(link)) } catch (e: Exception) { Result.failure(e) }
            }
            r.onSuccess { rows = shapeRows(it); state = DiscoverState.READY }
            r.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
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
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { /* keep the last list */ }
    }

    /** True while something asked for is still on its way (the screen then polls the requests more often). */
    val active: Boolean get() = requests.any { r -> r.download != null || r.mediaStatus == "processing" || r.status == "approved" }

    /** The freshest state of [t]: the request list's copy (fetched each time) over the cached row's. */
    fun current(t: DiscoverTitle): DiscoverTitle {
        val r = requests.firstOrNull { it.type == t.type && it.tmdb == t.tmdb } ?: return t
        val rt = r.title ?: return t.copy(download = r.download)
        return t.copy(status = rt.status, requestStatus = rt.requestStatus ?: t.requestStatus, download = r.download, play = rt.play ?: t.play)
    }

    /** POST /v1/requests for [t]; the outcome as a toast, then rows and requests again. */
    fun request(t: DiscoverTitle) {
        val link = m.orbit.link ?: return m.toast("Link this TV to Orbit first")
        if (busy != null) return
        busy = t.key
        m.viewModelScope.launch {
            val msg = try {
                val (_, created) = withContext(Dispatchers.IO) { api.request(link, t) }
                if (created) "Requested ${t.title}" else "${t.title} is already requested or available"
            } catch (e: OrbitError) {
                when (e.code) {
                    409 -> "Your account is not linked to a Seerr user yet: ask the owner to link it"
                    503 -> "Seerr cannot be reached right now"
                    else -> e.message ?: "The request was refused"
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "The home server did not answer" }
            busy = null
            m.toast(msg)
            refresh(force = true)
        }
    }

    companion object {
        /**
         * The server's rows (trending and upcoming mix films and shows; popular comes as films and TV) as the spec's
         * rows: Trending movies, Trending shows, Upcoming movies, Upcoming shows, Popular (films and shows
         * interleaved). Empty rows are left out.
         */
        fun shapeRows(raw: List<DiscoverRow>): List<DiscoverRow> {
            fun row(id: String) = raw.firstOrNull { it.id == id }?.items.orEmpty()
            val trending = row("trending"); val upcoming = row("upcoming")
            val films = row("movies"); val tv = row("tv")
            val popular = ArrayList<DiscoverTitle>()
            for (i in 0 until maxOf(films.size, tv.size)) { films.getOrNull(i)?.let { popular += it }; tv.getOrNull(i)?.let { popular += it } }
            return listOf(
                DiscoverRow("trending-movies", "Trending movies", trending.filter { !it.isShow }),
                DiscoverRow("trending-shows", "Trending shows", trending.filter { it.isShow }),
                DiscoverRow("upcoming-movies", "Upcoming movies", upcoming.filter { !it.isShow }),
                DiscoverRow("upcoming-shows", "Upcoming shows", upcoming.filter { it.isShow }),
                DiscoverRow("popular", "Popular", popular.distinctBy { it.key }),
            ).filter { it.items.isNotEmpty() }
        }
    }
}
