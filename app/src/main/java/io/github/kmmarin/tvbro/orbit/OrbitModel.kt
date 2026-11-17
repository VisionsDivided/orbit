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
import kotlinx.coroutines.NonCancellable
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
import kotlinx.serialization.json.contentOrNull

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
    /**
     * Unlinked: forget everything the account brought onto the TV (My list and favourites, recently watched and
     * their title snapshots, the last channel, search history). The playlists and watch state are removed separately.
     */
    fun clearAccountData()
    /** Per-channel stream formats of the linked profile (synced through its server prefs, FormatSync.kt). */
    val channelFormats: io.github.kmmarin.tvbro.data.ChannelFormats get() = io.github.kmmarin.tvbro.data.ChannelFormats()
    fun setChannelFormats(f: io.github.kmmarin.tvbro.data.ChannelFormats) {}
    /** Live TV's pinned groups of the linked profile and whether they changed here since the last sync (GroupPinSync.kt). */
    val groupPins: List<String> get() = emptyList()
    val groupPinsDirty: Boolean get() = false
    /** Store [pins] as synced: the server's copy, or the TV's own once sent (still dirty if they changed meanwhile). */
    fun setGroupPinsSynced(pins: List<String>) {}
}

/**
 * Every stream is in use: each active stream, and the answer: the id of the stream to stop, "" = stop the oldest
 * viewer (servers without stream ids), null = cancel. [want]: what this TV wants to play ("Vista Cinema").
 */
data class StreamAsk(val max: Int, val streams: List<io.github.kmmarin.tvbro.data.StreamChoice.Row>,
                     val answer: kotlinx.coroutines.CompletableDeferred<String?>, val want: String = "")

/**
 * Orbit state for the TV app: the home-server link, the pairing screen and sync (playlists, My list, film resume
 * points). Kept out of AppModel; AppModel holds one instance and attaches itself as [OrbitHost].
 */
class OrbitModel(private val app: Application, private val scope: CoroutineScope, private val api: OrbitApi = OrbitApi()) {
    private val store = OrbitStore(app)
    private var host: OrbitHost? = null
    private val syncLock = Mutex()

    var link by mutableStateOf<OrbitLink?>(null); private set
    /** The stored link has been read at start ([link] null now means: not linked). */
    var loaded by mutableStateOf(false); private set
    var pairing by mutableStateOf<Pairing?>(null); private set
    var message by mutableStateOf(""); private set
    /** The server ended this TV's session (signed out from another device, or the account was disabled). */
    var signedOut by mutableStateOf(false); private set
    /** Set when a pairing has just finished, so the pairing screen can close itself. */
    var justLinked by mutableStateOf(false)
    /** Right after linking: the pairing screen offers to name this TV ("Kitchen") before it closes; skipping is fine. */
    var nameStep by mutableStateOf(false)
    /** The last "Name this TV" result for the screen showing the field ("" = nothing to say). */
    var nameMessage by mutableStateOf(""); private set
    /** What the name field offers before anything is typed: the TV's model. */
    val defaultName: String = io.github.kmmarin.tvbro.data.DeviceName.default(android.os.Build.MODEL)
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
            if (store.wipePending) forget(signedOutByServer = false)     // an unlink the app was closed during
            val l = store.load()
            withContext(Dispatchers.Main) { link = l; loaded = true }
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
    /** The server's state as of the last download, with its revision: reused while the server says "unchanged". */
    private var cached: SyncSnapshot? = null
    /** The server has no /v1/sync routes (an older home server): the routes of one request per item. */
    private var legacy = false

    /** The household's playlists from the server replace the TV's (same ids as the server, so keys line up). */
    private suspend fun syncPlaylists() {
        val gone = syncLock.withLock {
            val l = link ?: return@withLock false
            val h = host ?: return@withLock false
            try {
                val list = api.playlists(l)
                if (list.isEmpty()) { withContext(Dispatchers.Main) { syncStatus = "The home server has no playlist yet" }; return@withLock false }
                withContext(Dispatchers.Main) {
                    if (link !== l) return@withContext                  // unlinked meanwhile
                    val mine = store.serverSources
                    // first link: playlists typed in on this TV are replaced too (their keys would not match the server's)
                    val remove = if (mine.isEmpty()) h.sources.map { it.id }.toSet() else mine
                    val same = h.sources.map { it.copy(enabled = true) }.toSet() == list.toSet()
                    if (!same) h.replaceSources(list, remove - list.map { it.id }.toSet())
                    store.serverSources = list.map { it.id }.toSet()
                }
            } catch (e: OrbitError) {
                if (e.code == 401) return@withLock true
                Diag.log("Orbit: playlists (${e.code})")
            } catch (e: Exception) { Diag.log("Orbit: playlists (${e.javaClass.simpleName})") }
            false
        }
        if (gone) forget(signedOutByServer = true)
    }

    /**
     * My list (three-way merge), film resume points and show progress (newest wins) with this TV's profile on the
     * server. One small request when nothing changed on the server; the TV's own changes go in one batch.
     */
    suspend fun syncState() {
        val gone = syncLock.withLock { syncOnce() }
        if (gone) forget(signedOutByServer = true)
    }

    /** One sync; true when the server no longer knows this TV (401). Call with [syncLock] held. */
    private suspend fun syncOnce(): Boolean {
        val l = link ?: return false
        val h = host ?: return false
        if (store.serverSources.isEmpty()) return false          // keys only match once the playlists come from the server
        try {
            val snap = download(l)
            val watch = WatchDb.get(app)
            val out = Outbox()
            // My list
            val local = withContext(Dispatchers.Main) { h.favourites }
            val m = OrbitSyncLogic.mergeList(local, store.listBase, snap.mylist)
            for (k in m.add) out.put("mylist", k, withContext(Dispatchers.Main) { h.describe(k) } ?: JsonObject(mapOf("key" to JsonPrimitive(k))))
            for (k in m.remove) out.del("mylist", k)
            // film resume points
            val films = OrbitSyncLogic.planResume(watch.allStates(), snap.resume, Watch.MIN_RESUME_MS)
            for ((k, s) in films.toServer) out.put("resume", k, OrbitBatch.resumeValue(s, withContext(Dispatchers.Main) { h.describe(k) }))
            // show progress: episode marks
            val eps = OrbitSyncLogic.planEpisodes(watch.allStates().filterKeys { it.startsWith("e|") }, watch::seriesKeyOf,
                snap.episodes, Watch.MIN_RESUME_MS)
            for (p in eps.toServer) out.episodes += OrbitBatch.episodeItem(p, withContext(Dispatchers.Main) { h.describe(p.seriesKey) })
            if (!out.isEmpty) send(l, out)
            syncChannelFormats(api, l, h, snap.prefs)
            syncGroupPins(api, l, h, snap.prefs)
            // what came from the server, once the TV's changes are on it
            withContext(Dispatchers.Main) {
                if (link !== l) return@withContext                      // unlinked meanwhile: keep nothing
                store.listBase = m.result
                val merged = OrbitSyncLogic.withLocalOnly(local, m.result)
                if (merged != h.favourites) h.setFavourites(merged)
                for ((k, s) in films.toLocal) watch.putState(k, s)
                for ((k, v) in eps.toLocal) watch.putState(k, v.first, v.second)
                // the server's show summaries (next episode, finished) where it knows newer; it recomputes a show's
                // next episode after changes from here, so the TV gets that on a later sync
                for (r in OrbitSyncLogic.planShows(watch.allSeries().associateBy { it.key }, snap.shows)) watch.putSeries(r)
                syncStatus = "Synced " + io.github.kmmarin.tvbro.data.TimeFmt.time(System.currentTimeMillis() / 1000)
            }
        } catch (e: OrbitError) {
            if (e.code == 401) return true
            withContext(Dispatchers.Main) { syncStatus = "Sync failed: ${e.message}" }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { syncStatus = "Home server not reachable; will try again" }
        }
        return false
    }

    /** The server's state: the cached copy while its revision is unchanged, else a fresh one. */
    private fun download(l: OrbitLink): SyncSnapshot {
        if (!legacy) {
            api.syncState(l, cached)?.let { cached = it; return it }
            legacy = true
            Diag.log("Orbit: home server without /v1/sync; syncing item by item")
        }
        val (eps, shows) = api.showsState(l)
        val st = api.state(l)
        return SyncSnapshot(null, OrbitBatch.listKeys(st), OrbitBatch.resume(st), eps, shows, st["prefs"] as? JsonObject)
    }

    /** The queued changes: one batch request, or one request per item on an older server. */
    private fun send(l: OrbitLink, out: Outbox) {
        if (!legacy && api.batch(l, out)) return
        legacy = true
        for (op in out.ops) {
            val scope = (op["scope"] as JsonPrimitive).content
            val key = (op["key"] as JsonPrimitive).content
            if ((op["op"] as JsonPrimitive).content == "del") api.stateDel(l, scope, key) else api.statePut(l, scope, key, op["value"]!!)
        }
        if (out.episodes.isNotEmpty()) api.pushEpisodes(l, out.episodes)
    }

    /**
     * Unlink, or the server ended this TV's session: everything the account brought onto the TV goes (session and
     * tokens, the playlists from the server, My list and favourites, resume points, show progress, recently watched),
     * so the next account starts clean. Marked first and finished at the next start if the app is closed half-way.
     */
    private suspend fun forget(signedOutByServer: Boolean) = withContext(NonCancellable) {   // it may cancel its own caller (the loop)
        store.wipePending = true
        withContext(Dispatchers.Main) {
            loop?.cancel(); loop = null
            beat?.cancel(); beat = null
            synchronized(holders) { holders.clear() }
            link = null; signedOut = signedOutByServer; nameStep = false
        }
        syncLock.withLock {                       // after any sync still running, so nothing it fetched comes back
            cached = null; legacy = false
            val h = host
            withContext(Dispatchers.Main) {
                try { OrbitUnlink.wipe(object : OrbitUnlink.Steps {
                    override fun setPending(on: Boolean) { store.wipePending = on }
                    override fun clearSession() = store.clear()
                    override fun serverSources() = store.serverSources
                    override fun removeSources(ids: Set<String>) { h?.replaceSources(emptyList(), ids) }
                    override fun forgetServerSources() { store.serverSources = emptySet() }
                    override fun clearWatchState() = WatchDb.get(app).clearAll()
                    override fun clearAccountData() { h?.clearAccountData() }
                }) } catch (e: Exception) { Diag.log("Orbit: unlink cleanup stopped (${e.javaClass.simpleName}); finished at the next start") }
            }
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
                        io.github.kmmarin.tvbro.data.StreamChoice.Use(u.id, u.label, u.profile, u.what, u.recording, u.device, u.since)
                    })
                    val q = StreamAsk(st.max, rows, kotlinx.coroutines.CompletableDeferred(), what)
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

    /**
     * An IPTV film or episode from this TV's facade address [url], after [claimStream]: a converted copy that fits the
     * connection when the TV is away from home (speed test, then `POST /v1/vod/play`), or null to play [url] as it is
     * (at home, unlinked, an older server, any error).
     */
    suspend fun convertedFilm(url: String): String? {
        val l = link ?: return null
        if (!url.startsWith(l.server + "/xt/")) return null
        return withContext(Dispatchers.IO) {
            try { api.vodPlay(l, url, store.streamSid, api.speedKbps(l)) } catch (e: Exception) { null }
        }?.also { Diag.log("Orbit: film converted for this connection") }
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
                        // linked again without unlinking first: the previous account's data goes, as on unlink
                        link?.let { old -> api.logout(old); forget(signedOutByServer = false) }
                        store.save(r.link)
                        store.listBase = emptySet()
                        cached = null; legacy = false
                        withContext(Dispatchers.Main) {
                            link = r.link; signedOut = false; pairing = null; nameMessage = ""; nameStep = true; justLinked = true
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

    /**
     * Names this TV on the home server ("Kitchen"): other devices then see it by that name in the stream-limit prompt
     * and the device lists. [done] runs on the main thread once the server took it.
     */
    fun saveName(input: String, done: () -> Unit = {}) {
        val l = link ?: return
        val name = io.github.kmmarin.tvbro.data.DeviceName.clean(input)
        if (name == null) { nameMessage = "Use ${io.github.kmmarin.tvbro.data.DeviceName.HELP}."; return }
        nameMessage = "Saving…"
        scope.launch(Dispatchers.IO) {
            var ok = false
            val msg = try {
                val saved = api.setName(l, name)
                withContext(Dispatchers.Main) {
                    val cur = link
                    if (cur != null && cur.session == l.session) { val now = cur.copy(deviceName = saved); store.save(now); link = now }
                }
                ok = true
                "This TV is now called $saved"
            } catch (e: OrbitError) {
                if (e.code == 404) "The home server is too old to name TVs; update Orbit on the server" else e.message ?: "Not saved (${e.code})"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) { describe(e) }
            withContext(Dispatchers.Main) {
                nameMessage = msg
                if (ok) done()
            }
        }
    }

    fun clearNameMessage() { nameMessage = "" }

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
            withContext(Dispatchers.Main) { message = "This TV is unlinked; the playlists, My list and watch progress from the home server were removed" }
        }
    }

    fun clearMessage() { message = "" }

    /**
     * Who's watching?: this TV continues as profile [id] (of the same account). The current profile's changes are
     * sent first; then everything the old profile brought onto the TV goes (My list, favourites, recently watched,
     * resume points, show progress, as on unlink, the playlists stay) and the new profile's state is synced in.
     * Returns how it ended ([ProfileSwitch]): leaving a kids profile may need the parental PIN ([pin]). Call from a
     * coroutine on the main thread.
     */
    suspend fun switchProfile(id: String, pin: String? = null): ProfileSwitch {
        val l = link ?: return ProfileSwitch.Failed("This TV is not linked to Orbit")
        withContext(Dispatchers.IO) { runCatching { syncState() } }
        val body = if (pin != null) mapOf("id" to JsonPrimitive(id), "pin" to JsonPrimitive(pin)) else mapOf("id" to JsonPrimitive(id))
        val me = try {
            withContext(Dispatchers.IO) { api.postJson(l, "/v1/profiles/select", JsonObject(body)) }
        } catch (e: Exception) { return ProfileSwitch.of(e, describe(e)) }
        val nl = OrbitApi.linkFrom(l.server, l.session, (me["csrf"] as? JsonPrimitive)?.content ?: l.csrf, me)
        syncLock.withLock {
            if (link !== l) return ProfileSwitch.Failed("This TV was unlinked meanwhile")
            store.save(nl)
            store.listBase = emptySet()
            cached = null; legacy = false
            host?.clearAccountData()
            WatchDb.get(app).clearAll()
            link = nl
        }
        withContext(Dispatchers.IO) { syncState() }
        return ProfileSwitch.Done((me["warning"] as? JsonPrimitive)?.contentOrNull)
    }

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
