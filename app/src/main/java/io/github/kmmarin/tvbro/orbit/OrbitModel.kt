// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.orbit

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import io.github.kmmarin.tvbro.data.Diag
import io.github.kmmarin.tvbro.data.Source
import io.github.kmmarin.tvbro.data.Watch
import io.github.kmmarin.tvbro.data.WatchDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** A pairing in progress: the code shown on screen while the TV waits for approval. */
data class Pairing(val server: String, val code: String, val expires: Long)

/** What OrbitModel needs from the app (AppModel implements it); all calls on the main thread. */
interface OrbitHost {
    val favourites: Set<String>
    fun setFavourites(f: Set<String>)
    val sources: List<Source>
    /** Use these playlists (from the home server) instead of [remove]d ones. */
    fun replaceSources(list: List<Source>, remove: Set<String>)
    /** My list entry for the web app: {key, kind, name, logo / poster, id}, or null when unknown here. */
    fun describe(key: String): JsonObject?
    /** Another device took this TV's stream slot: stop live / film playback and say why. */
    fun stopPlayback(reason: String)
}

/**
 * Every stream is in use: each active stream, and the answer: the id of the stream to stop, "" = stop the oldest
 * viewer (servers without stream ids), null = cancel.
 */
data class StreamAsk(val max: Int, val streams: List<io.github.kmmarin.tvbro.data.StreamChoice.Row>,
                     val answer: kotlinx.coroutines.CompletableDeferred<String?>)

/**
 * Orbit state for the TV app: the home-server link, the pairing screen and sync (playlists, My list, film resume
 * points). Kept out of AppModel; AppModel holds one instance and attaches itself as [OrbitHost].
 */
class OrbitModel(private val app: Application, private val scope: CoroutineScope, private val api: OrbitApi = OrbitApi()) {
    private val store = OrbitStore(app)
    private var host: OrbitHost? = null
    private val syncLock = Mutex()

    var link by mutableStateOf<OrbitLink?>(null); private set
    var pairing by mutableStateOf<Pairing?>(null); private set
    var message by mutableStateOf(""); private set
    /** The server ended this TV's session (signed out from another device, or the account was disabled). */
    var signedOut by mutableStateOf(false); private set
    /** Set when a pairing has just finished, so the pairing screen can close itself. */
    var justLinked by mutableStateOf(false)
    /** Last sync result for Settings ("Synced 21:04" / an error). */
    var syncStatus by mutableStateOf(""); private set
    val lastServer: String get() = store.lastServer

    private var job: Job? = null
    private var loop: Job? = null
    private val device = "TV (" + (android.os.Build.MODEL ?: "Android").take(40) + ")"

    /** Called by AppModel once all its state exists: loads the link, checks it, starts syncing. */
    @OptIn(FlowPreview::class)
    fun attach(h: OrbitHost) {
        host = h
        scope.launch(Dispatchers.IO) {
            val l = store.load()
            withContext(Dispatchers.Main) { link = l }
            if (l != null && check(l)) startSyncLoop()
        }
        // a change to My list on the TV goes to the server shortly after (and pulls the other side's changes)
        scope.launch {
            snapshotFlow { h.favourites }.drop(1).debounce(1500).collect { if (link != null) scope.launch(Dispatchers.IO) { syncState() } }
        }
    }

    /** Names up to date; false when the server no longer knows this TV (signed out). Offline keeps the link. */
    private suspend fun check(l: OrbitLink): Boolean {
        try {
            val now = api.me(l)
            if (now == null) { forget(signedOutByServer = true); return false }
            if (now != l) { store.save(now); withContext(Dispatchers.Main) { link = now } }
        } catch (e: Exception) {
            Diag.log("Orbit: home server not reachable (${e.javaClass.simpleName})")
        }
        return true
    }

    private fun startSyncLoop() {
        loop?.cancel()
        loop = scope.launch(Dispatchers.IO) {
            syncPlaylists()
            var n = 0
            while (isActive && link != null) {
                syncState()
                delay(60_000)
                if (++n % 30 == 0) syncPlaylists()            // every half hour: playlists changed on the server
            }
        }
    }

    // ---------- sync ----------
    /** The household's playlists from the server replace the TV's (same ids as the server, so keys line up). */
    private suspend fun syncPlaylists() {
        val l = link ?: return
        val h = host ?: return
        try {
            val list = api.playlists(l)
            if (list.isEmpty()) { withContext(Dispatchers.Main) { syncStatus = "The home server has no playlist yet" }; return }
            withContext(Dispatchers.Main) {
                val mine = store.serverSources
                // first link: playlists typed in on this TV are replaced too (their keys would not match the server's)
                val remove = if (mine.isEmpty()) h.sources.map { it.id }.toSet() else mine
                val same = h.sources.map { it.copy(enabled = true) }.toSet() == list.toSet()
                if (!same) h.replaceSources(list, remove - list.map { it.id }.toSet())
                store.serverSources = list.map { it.id }.toSet()
            }
        } catch (e: OrbitError) {
            if (e.code == 401) forget(signedOutByServer = true) else Diag.log("Orbit: playlists (${e.code})")
        } catch (e: Exception) { Diag.log("Orbit: playlists (${e.javaClass.simpleName})") }
    }

    /** My list (three-way merge) and film resume points (newest wins) with this TV's profile on the server. */
    suspend fun syncState() = syncLock.withLock {
        val l = link ?: return@withLock
        val h = host ?: return@withLock
        if (store.serverSources.isEmpty()) return@withLock          // keys only match once the playlists come from the server
        try {
            val st = api.state(l)
            // My list
            val serverKeys = (st["mylist"] as? kotlinx.serialization.json.JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.get("key")?.let { k -> (k as? JsonPrimitive)?.contentOrNull } }.toSet()
            val local = withContext(Dispatchers.Main) { h.favourites }
            val m = OrbitSyncLogic.mergeList(local, store.listBase, serverKeys)
            for (k in m.add) {
                val v = withContext(Dispatchers.Main) { h.describe(k) } ?: JsonObject(mapOf("key" to JsonPrimitive(k)))
                api.statePut(l, "mylist", k, v)
            }
            for (k in m.remove) api.stateDel(l, "mylist", k)
            store.listBase = m.result
            val merged = OrbitSyncLogic.withLocalOnly(local, m.result)
            withContext(Dispatchers.Main) { if (merged != h.favourites) h.setFavourites(merged) }
            // film resume points
            val watch = WatchDb.get(app)
            val server = (st["resume"] as? JsonObject).orEmpty().mapNotNull { (k, v) ->
                val o = v as? JsonObject ?: return@mapNotNull null
                val at = (o["at"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
                k to ServerResume((o["secs"] as? JsonPrimitive)?.longOrNull ?: 0, (o["dur"] as? JsonPrimitive)?.longOrNull ?: 0, at,
                    (o["done"] as? JsonPrimitive)?.booleanOrNull ?: false)
            }.toMap()
            val plan = OrbitSyncLogic.planResume(watch.allStates(), server, Watch.MIN_RESUME_MS)
            withContext(Dispatchers.Main) { for ((k, s) in plan.toLocal) watch.putState(k, s) }
            for ((k, s) in plan.toServer) {
                val meta = withContext(Dispatchers.Main) { h.describe(k) }
                val v = buildMap<String, kotlinx.serialization.json.JsonElement> {
                    put("secs", JsonPrimitive(if (s.watched) s.durMs / 1000 else s.posMs / 1000)); put("dur", JsonPrimitive(s.durMs / 1000))
                    put("at", JsonPrimitive(s.updated)); if (s.watched) put("done", JsonPrimitive(true))
                    if (meta != null) put("meta", JsonObject(mapOf("title" to (meta["name"] ?: JsonPrimitive("")), "poster" to (meta["poster"] ?: JsonPrimitive("")),
                        "kind" to JsonPrimitive("movie"), "id" to (meta["id"] ?: JsonPrimitive("")))))
                }
                api.statePut(l, "resume", k, JsonObject(v))
            }
            syncShows(l, h, watch)
            val t = io.github.kmmarin.tvbro.data.TimeFmt.time(System.currentTimeMillis() / 1000)
            withContext(Dispatchers.Main) { syncStatus = "Synced $t" }
        } catch (e: OrbitError) {
            if (e.code == 401) forget(signedOutByServer = true)
            else withContext(Dispatchers.Main) { syncStatus = "Sync failed: ${e.message}" }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { syncStatus = "Home server not reachable; will try again" }
        }
    }

    /**
     * Show progress: episode marks newest-wins both ways, then the server's show summaries (next episode, finished)
     * for the TV's Continue watching where the server knows newer. The server recomputes a show's next episode
     * after changes from here, so the TV gets it on the next sync.
     */
    private suspend fun syncShows(l: OrbitLink, h: OrbitHost, watch: WatchDb) {
        val (serverEps, serverShows) = api.showsState(l)
        val local = watch.allStates().filterKeys { it.startsWith("e|") }
        val plan = OrbitSyncLogic.planEpisodes(local, watch::seriesKeyOf, serverEps, Watch.MIN_RESUME_MS)
        withContext(Dispatchers.Main) { for ((k, v) in plan.toLocal) watch.putState(k, v.first, v.second) }
        if (plan.toServer.isNotEmpty()) {
            val items = plan.toServer.map { p ->
                val meta = withContext(Dispatchers.Main) { h.describe(p.seriesKey) }
                val s = p.state
                JsonObject(buildMap {
                    put("seriesId", JsonPrimitive(p.seriesId)); put("epId", JsonPrimitive(p.epId))
                    put("secs", JsonPrimitive(if (s.watched) s.durMs / 1000 else s.posMs / 1000)); put("dur", JsonPrimitive(s.durMs / 1000))
                    put("done", JsonPrimitive(s.watched)); put("at", JsonPrimitive(s.updated))
                    meta?.get("name")?.let { put("name", it) }; meta?.get("poster")?.let { put("poster", it) }
                })
            }
            api.pushEpisodes(l, items)
        }
        val rows = OrbitSyncLogic.planShows(watch.allSeries().associateBy { it.key }, serverShows)
        withContext(Dispatchers.Main) { for (r in rows) watch.putSeries(r) }
    }

    /** Sign-out from the server or unlink: forget the session and remove the playlists that came from the server. */
    private suspend fun forget(signedOutByServer: Boolean) {
        store.clear()
        val ids = store.serverSources
        store.serverSources = emptySet()
        withContext(Dispatchers.Main) {
            loop?.cancel(); loop = null
            link = null; signedOut = signedOutByServer
            if (ids.isNotEmpty()) host?.replaceSources(emptyList(), ids)
        }
    }

    // ---------- the provider's stream limit, shared with browsers and recordings (Phase 2) ----------
    /** Shown while the TV asks whether to stop another viewer. */
    var streamAsk by mutableStateOf<StreamAsk?>(null); private set
    private val holders = mutableSetOf<String>()
    private var beat: Job? = null

    /**
     * Before IPTV plays on this TV (linked only): joins the household's stream count. [tag] says who holds the slot
     * ("live", "vod"); one TV is one slot however often it zaps. When every stream is in use, [ask] = true lists
     * them and asks which one to stop (a player opening), [ask] = false just answers false (the preview stays off).
     * The home server not answering never blocks playback.
     */
    suspend fun claimStream(tag: String, what: String, ask: Boolean): Boolean {
        val l = link ?: return true
        if (beat?.isActive == true) { synchronized(holders) { holders += tag }; return true }
        val sid = store.streamSid
        try {
            var st = withContext(Dispatchers.IO) { api.streams(l) }
            var tries = 0
            while (true) {
                val mine = st.active.any { it.sid.isNotEmpty() && sid.startsWith(it.sid) }
                var replace: String? = null
                if (!mine && st.count >= st.max) {
                    if (!ask) return false
                    val rows = io.github.kmmarin.tvbro.data.StreamChoice.rows(st.active.map { u ->
                        io.github.kmmarin.tvbro.data.StreamChoice.Use(u.id, u.label, u.profile, u.what, u.recording)
                    })
                    val q = StreamAsk(st.max, rows, kotlinx.coroutines.CompletableDeferred())
                    withContext(Dispatchers.Main) { streamAsk = q }
                    replace = (try { q.answer.await() } finally { withContext(Dispatchers.Main) { if (streamAsk === q) streamAsk = null } })
                        ?: return false
                }
                when (val r = withContext(Dispatchers.IO) { api.streamStart(l, sid, device, what, replace) }) {
                    OrbitApi.StreamStart.Ok -> break
                    OrbitApi.StreamStart.Recording -> {
                        withContext(Dispatchers.Main) { host?.stopPlayback("Every stream is in use by a recording on the home server") }
                        return false
                    }
                    // the chosen stream ended and someone else took the slot: ask again with the list as it is now
                    is OrbitApi.StreamStart.Changed -> { if (++tries >= 3) return false; st = r.now }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Diag.log("Orbit: stream count not reachable (${e.javaClass.simpleName}); playing anyway")
            return true
        }
        synchronized(holders) { holders += tag }
        startBeat(l, sid)
        return true
    }

    /** [stop]: the id of the stream to stop ("" = the oldest viewer), or null to cancel. */
    fun answerStreamAsk(stop: String?) { streamAsk?.answer?.complete(stop) }

    private fun startBeat(l: OrbitLink, sid: String) {
        beat?.cancel()
        beat = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(10_000)
                val why = try { api.streamHeartbeat(l, sid) } catch (e: Exception) { null }
                if (why != null) {
                    synchronized(holders) { holders.clear() }
                    withContext(Dispatchers.Main) {
                        host?.stopPlayback(if (why == "recording") "Stopped: a recording needs the stream" else "Stopped: another device started watching")
                    }
                    break
                }
            }
        }
    }

    /** [tag] stopped playing; the slot is given back when nothing on this TV plays any more. */
    fun releaseStream(tag: String) {
        val empty = synchronized(holders) { holders -= tag; holders.isEmpty() }
        if (!empty || beat == null) return
        beat?.cancel(); beat = null
        val l = link ?: return
        scope.launch(Dispatchers.IO) { api.streamStop(l, store.streamSid) }
    }

    // ---------- pairing ----------
    fun startPairing(input: String) {
        val server = OrbitApi.normalizeServer(input)
        if (server == null) { message = "Enter the home server's address, e.g. http://192.0.2.10:8750"; return }
        cancelPairing()
        message = "Asking the home server for a code…"
        store.lastServer = server
        job = scope.launch(Dispatchers.IO) {
            val p = try { api.start(server, device) } catch (e: Exception) {
                withContext(Dispatchers.Main) { message = describe(e) }
                return@launch
            }
            withContext(Dispatchers.Main) { pairing = Pairing(server, p.code, p.expires); message = "" }
            while (isActive) {
                delay(p.interval.coerceIn(2, 10) * 1000L)
                val r = try { api.poll(server, p.pairId) } catch (e: Exception) {
                    withContext(Dispatchers.Main) { message = describe(e) + " (still trying)" }
                    continue
                }
                when (r) {
                    PairPoll.Pending -> if (message.isNotEmpty()) withContext(Dispatchers.Main) { message = "" }
                    is PairPoll.Approved -> {
                        store.save(r.link)
                        store.listBase = emptySet()
                        withContext(Dispatchers.Main) {
                            link = r.link; signedOut = false; pairing = null; justLinked = true
                            message = "Linked: ${r.link.account}, profile ${r.link.profile}"
                        }
                        startSyncLoop()
                        return@launch
                    }
                    is PairPoll.Gone -> {
                        withContext(Dispatchers.Main) { pairing = null; message = "The code expired. Get a new one." }
                        return@launch
                    }
                }
            }
        }
    }

    fun cancelPairing() {
        job?.cancel(); job = null
        pairing = null
    }

    fun unlink() {
        val l = link ?: return
        cancelPairing()
        scope.launch(Dispatchers.IO) {
            api.logout(l)
            forget(signedOutByServer = false)
            withContext(Dispatchers.Main) { message = "This TV is unlinked; the playlists from the home server were removed" }
        }
    }

    fun clearMessage() { message = "" }

    private fun describe(e: Exception): String = when (e) {
        is OrbitError -> when (e.code) {
            409 -> "The home server has no accounts yet: set them up in Orbit on a computer first (Settings ▸ Accounts)."
            421 -> "The home server does not accept this address. Add it to TVBRO_ALLOWED_HOSTS on the server."
            429 -> "Too many codes at once. Try again in a minute."
            else -> e.message ?: "The home server refused (${e.code})"
        }
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException ->
            "The home server cannot be reached. Check the address and that the TV is on the same network."
        else -> "Could not reach the home server (${e.javaClass.simpleName})"
    }
}
