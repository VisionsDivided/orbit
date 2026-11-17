// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.kmmarin.tvbro.data.ChannelFormats
import io.github.kmmarin.tvbro.data.Diag
import io.github.kmmarin.tvbro.data.Source
import io.github.kmmarin.tvbro.data.StreamChoice
import io.github.kmmarin.tvbro.data.TimeFmt
import io.github.kmmarin.tvbro.data.Watch
import io.github.kmmarin.tvbro.orbit.OrbitApi
import io.github.kmmarin.tvbro.orbit.OrbitBatch
import io.github.kmmarin.tvbro.orbit.OrbitError
import io.github.kmmarin.tvbro.orbit.OrbitLink
import io.github.kmmarin.tvbro.orbit.OrbitMe
import io.github.kmmarin.tvbro.orbit.OrbitProfile
import io.github.kmmarin.tvbro.orbit.OrbitSyncLogic
import io.github.kmmarin.tvbro.orbit.OrbitUnlink
import io.github.kmmarin.tvbro.orbit.Outbox
import io.github.kmmarin.tvbro.orbit.SyncSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** The desktop app's Orbit sign-in, kept encrypted through [Secrets] ("orbit.bin": Windows DPAPI, macOS Keychain). */
@Serializable
data class OrbitSaved(
    val server: String = "", val session: String = "", val csrf: String = "",
    val account: String = "", val username: String = "", val profile: String = "", val profileId: String = "", val kids: Boolean = false,
    val profiles: List<SavedProfile> = emptyList(),
    /** Ids of the playlists that came from the home server (removed again on sign-out). */
    val serverSources: Set<String> = emptySet(),
    /** My list as it was after the last sync (the base of the three-way merge). */
    val listBase: Set<String> = emptySet(),
    /** This app's stream id for the shared stream count (one per sign-in, so zapping never takes a second slot). */
    val streamSid: String = "",
) {
    val link: OrbitLink? get() = if (server.isEmpty() || session.isEmpty()) null else OrbitLink(server, session, csrf, account, username, profile, kids)

    fun with(me: OrbitMe) = copy(server = me.link.server, session = me.link.session, csrf = me.link.csrf, account = me.link.account,
        username = me.link.username, profile = me.link.profile, kids = me.link.kids, profileId = me.profileId,
        profiles = me.profiles.map { SavedProfile(it.id, it.name, it.kids) })
}

@Serializable
data class SavedProfile(val id: String, val name: String, val kids: Boolean)

/** Pure rules of the desktop's Orbit link (unit tested). */
object OrbitDesk {
    /** How this computer appears in the web app's "Your devices". */
    fun deviceName(os: Os, host: String?): String {
        val sys = when (os) { Os.WINDOWS -> "Windows"; Os.MAC -> "macOS"; Os.LINUX -> "Linux" }
        val h = host?.trim()?.take(30)?.takeIf { it.isNotEmpty() }
        return if (h != null) "Orbit on $sys ($h)" else "Orbit on $sys"
    }

    /**
     * A playlist from /v1/device/playlists as the app stores it. Its login may be a stand-in the home server hands out
     * (not the provider's own), and an address may be given relative to the home server ("/x/..."): it is made absolute.
     */
    fun localSource(s: Source, server: String): Source {
        fun abs(u: String) = if (u.startsWith("/") && !u.startsWith("//")) server.trimEnd('/') + u else u
        return s.copy(url = abs(s.url), epgUrl = abs(s.epgUrl), server = abs(s.server), enabled = true)
    }

    /** My list after a merge: the old order kept, new entries at the end. */
    fun ordered(old: List<String>, merged: Set<String>): List<String> = old.filter { it in merged } + merged.filter { it !in old }

    /** The playlists to remove when the server's arrive: on the first sign-in also those typed into this app (their keys would not match). */
    fun toRemove(local: List<Source>, mine: Set<String>, fromServer: List<Source>): Set<String> =
        (if (mine.isEmpty()) local.map { it.id }.toSet() else mine) - fromServer.map { it.id }.toSet()

    /** Sign-in errors in words. */
    fun describe(e: Exception): String = when (e) {
        is OrbitError -> when (e.code) {
            403 -> if (e.message?.contains("password") == true) "Wrong username or password." else e.message ?: "The home server refused."
            409 -> "The home server has no accounts yet: set them up in the Orbit web app first (Settings ▸ Accounts)."
            421 -> "The home server does not accept this address. Add it to TVBRO_ALLOWED_HOSTS on the server."
            429 -> "Too many wrong passwords. Wait a minute and try again."
            else -> e.message ?: "The home server refused (${e.code})."
        }
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException ->
            "The home server cannot be reached. Check the address and that this computer is on the same network."
        else -> "Could not reach the home server (${e.javaClass.simpleName})."
    }
}

/** The stream limit is reached: the active streams, and the answer (the stream id to stop, "" = the oldest, null = cancel). */
/** [what]: the channel or title asked for; [verb]: "watch", or "record" for a recording started by hand. */
class StreamAsk(val max: Int, val rows: List<StreamChoice.Row>, val answer: CompletableDeferred<String?>, val what: String = "", val verb: String = "watch")

/**
 * The desktop app as an Orbit device, like a linked TV (app/.../orbit/OrbitModel.kt): signs in with an account and
 * profile, takes the household's playlists, syncs My list, resume points and show progress, joins the stream count,
 * and on sign-out or a 401 removes everything the account brought (OrbitUnlink). Without a sign-in the app is plain
 * tvBro, unchanged.
 */
class OrbitDesktop(private val app: AppState, private val scope: CoroutineScope, private val api: OrbitApi = OrbitApi(userAgent = "Orbit-Desktop")) {
    private val syncLock = Mutex()
    private val pendingFile = File(Paths.config, "orbit-wipe.pending")
    private val device = OrbitDesk.deviceName(Platform.os, runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull())

    var saved by mutableStateOf(OrbitSaved()); private set
    val link: OrbitLink? get() = saved.link
    val linked: Boolean get() = saved.link != null
    val profiles: List<OrbitProfile> get() = saved.profiles.map { OrbitProfile(it.id, it.name, it.kids) }

    /** "Who's watching?" is shown (after signing in, or from the profile menu). */
    var picking by mutableStateOf(false)
    /** The sign-in screen is shown. */
    var signingIn by mutableStateOf(false)
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    var syncStatus by mutableStateOf(""); private set
    /** The server ended this app's session (signed out elsewhere, or the account was disabled). */
    var signedOut by mutableStateOf(false); private set

    private var loop: Job? = null
    private var cached: SyncSnapshot? = null
    private var legacy = false

    /** Tests (design shots): a sign-in to show, without saving it or talking to any server. */
    internal fun showForTest(s: OrbitSaved) { saved = s }

    private fun persist(s: OrbitSaved) {
        saved = s
        try { Secrets.writeString("orbit", Json2.json.encodeToString(OrbitSaved.serializer(), s)) }
        catch (e: Exception) { Diag.log("Orbit: sign-in not saved (${e.javaClass.simpleName})") }
    }

    private fun load(): OrbitSaved = try {
        Secrets.readString("orbit").takeIf { it.isNotBlank() }?.let { Json2.json.decodeFromString(OrbitSaved.serializer(), it) } ?: OrbitSaved()
    } catch (e: Exception) { Diag.log("Orbit: saved sign-in unreadable (${e.javaClass.simpleName})"); OrbitSaved() }

    /** At start: finishes a sign-out the app was closed during, then checks the saved sign-in and starts syncing. */
    fun start() = scope.launch(Dispatchers.IO) {
        if (pendingFile.exists()) forget(signedOutByServer = false)
        val s = load()
        withContext(Dispatchers.Main) { saved = s }
        val l = s.link ?: return@launch
        // "Who's watching?" at start (Settings ▸ Profiles), from the profiles saved last time: no waiting for the server
        if (app.settings.askWho && s.profiles.size > 1) withContext(Dispatchers.Main) { picking = true }
        if (check(l)) startLoop()
    }

    // ---------- Manage profiles (POST /v1/profiles, /v1/profiles/delete; docs/orbit/API-v1.md) ----------
    /** The account's profiles again from the server (GET /v1/me). */
    suspend fun refreshProfiles() {
        val me = try { call { api.account(it) } } catch (e: Exception) { null } ?: return
        withContext(Dispatchers.Main) { if (saved.link?.session == me.link.session) persist(saved.with(me)) }
    }

    suspend fun addProfile(name: String, kids: Boolean) {
        message = ""
        try { call { api.post(it, "/v1/profiles", JsonObject(mapOf("name" to JsonPrimitive(name), "kids" to JsonPrimitive(kids)))) }; refreshProfiles(); app.toast("Profile $name added") }
        catch (e: OrbitError) { message = e.message ?: "Could not add the profile" }
        catch (e: Exception) { message = OrbitDesk.describe(e) }
    }

    suspend fun deleteProfile(id: String) {
        message = ""
        if (id == saved.profileId) { message = "Switch to another profile before removing this one"; return }
        try { call { api.post(it, "/v1/profiles/delete", JsonObject(mapOf("id" to JsonPrimitive(id)))) }; refreshProfiles(); app.toast("Profile removed") }
        catch (e: OrbitError) { message = e.message ?: "Could not remove the profile" }
        catch (e: Exception) { message = OrbitDesk.describe(e) }
    }

    private suspend fun check(l: OrbitLink): Boolean {
        try {
            val me = api.account(l)
            if (me == null) { forget(signedOutByServer = true); return false }
            withContext(Dispatchers.Main) { if (saved.link == l) persist(saved.with(me)) }
        } catch (e: Exception) { Diag.log("Orbit: home server not reachable (${e.javaClass.simpleName})") }
        return true
    }

    // ---------- signing in ----------
    fun signIn(input: String, username: String, password: String) {
        val server = OrbitApi.normalizeServer(input) ?: run { message = "Enter the home server's address, e.g. http://192.0.2.10:8750"; return }
        if (username.isBlank() || password.isEmpty()) { message = "Enter your Orbit username and password."; return }
        busy = true; message = "Signing in…"
        app.update { it.copy(orbitServer = server) }
        scope.launch(Dispatchers.IO) {
            try {
                if (!api.hasAccounts(server)) {
                    withContext(Dispatchers.Main) { message = "This home server has no Orbit accounts yet (tvBro mode). Set them up in its web app first." }
                    return@launch
                }
                val me = api.login(server, username.trim(), password, device)
                // signed in as someone else without signing out first: the previous account's data goes, as on sign-out
                saved.link?.let { old -> api.logout(old); forget(signedOutByServer = false) }
                cached = null; legacy = false
                withContext(Dispatchers.Main) {
                    persist(OrbitSaved(streamSid = "dk" + java.util.UUID.randomUUID().toString().replace("-", "")).with(me))
                    signedOut = false; signingIn = false; message = ""
                    picking = me.profiles.size > 1                       // one profile: nothing to pick
                    if (!picking) app.toast("Signed in as ${me.link.account}")
                }
                startLoop()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { message = OrbitDesk.describe(e) }
            } finally { withContext(Dispatchers.Main) { busy = false } }
        }
    }

    /** "Who's watching?": this app's session switches to [id]; the new profile's state replaces the old one's. */
    fun chooseProfile(id: String) {
        val l = link ?: return
        if (id == saved.profileId) { picking = false; return }
        busy = true
        scope.launch(Dispatchers.IO) {
            try {
                val me = api.selectProfile(l, id)
                syncLock.withLock {
                    cached = null
                    withContext(Dispatchers.Main) {
                        // another profile: its My list and progress, not the last one's
                        persist(saved.with(me).copy(listBase = emptySet()))
                        app.clearProfileData(); app.watch.clearAll()
                        picking = false
                        app.toast("Watching as ${me.link.profile}")
                    }
                }
                syncState()
            } catch (e: OrbitError) {
                if (e.code == 401) forget(signedOutByServer = true) else withContext(Dispatchers.Main) { app.toast(e.message ?: "Profile not changed") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { app.toast(OrbitDesk.describe(e)) }
            } finally { withContext(Dispatchers.Main) { busy = false } }
        }
    }

    fun signOut() {
        val l = link ?: return
        scope.launch(Dispatchers.IO) {
            api.logout(l)
            forget(signedOutByServer = false)
            withContext(Dispatchers.Main) { app.toast("Signed out: the playlists, My list and progress from the home server were removed") }
        }
    }

    // ---------- sync ----------
    private fun startLoop() {
        loop?.cancel()
        loop = scope.launch(Dispatchers.IO) {
            syncPlaylists()
            var n = 0
            while (isActive && link != null) {
                syncState()
                delay(60_000)
                if (++n % 30 == 0) syncPlaylists()
            }
        }
    }

    /** Soon after a change on this computer (My list, a resume point): sync, so other devices see it. */
    private var soon: Job? = null
    fun changed() {
        if (link == null) return
        soon?.cancel()
        soon = scope.launch(Dispatchers.IO) { delay(1500); syncState() }
    }

    /** The household's playlists replace this app's (same ids as the server, so keys line up). */
    private suspend fun syncPlaylists() {
        val gone = syncLock.withLock {
            val l = link ?: return@withLock false
            try {
                val list = api.playlists(l).map { OrbitDesk.localSource(it, l.server) }
                if (list.isEmpty()) { withContext(Dispatchers.Main) { syncStatus = "The home server has no playlist yet" }; return@withLock false }
                withContext(Dispatchers.Main) {
                    if (link != l) return@withContext
                    val remove = OrbitDesk.toRemove(app.sources, saved.serverSources, list)
                    val same = app.sources.map { it.copy(enabled = true) }.toSet() == list.toSet()
                    if (!same) app.replaceSources(list, remove)
                    persist(saved.copy(serverSources = list.map { it.id }.toSet()))
                }
            } catch (e: OrbitError) {
                if (e.code == 401) return@withLock true
                Diag.log("Orbit: playlists (${e.code})")
            } catch (e: Exception) { Diag.log("Orbit: playlists (${e.javaClass.simpleName})") }
            false
        }
        if (gone) forget(signedOutByServer = true)
    }

    suspend fun syncState() {
        val gone = syncLock.withLock { syncOnce() }
        if (gone) forget(signedOutByServer = true)
    }

    /** One sync (My list three-way merge, film resume points, episode marks, show rows); true on 401. */
    private suspend fun syncOnce(): Boolean {
        val l = link ?: return false
        if (saved.serverSources.isEmpty()) return false          // keys only match once the playlists come from the server
        try {
            val snap = download(l)
            val out = Outbox()
            val (localList, states, seriesOf, rows) = withContext(Dispatchers.Main) {
                val w = app.watch
                val items = w.data.items
                Quad(app.settings.myList.toSet(), w.allStates(), items.mapNotNull { (k, v) -> v.series?.let { k to it } }.toMap(), w.data.series)
            }
            val m = OrbitSyncLogic.mergeList(localList, saved.listBase, snap.mylist)
            for (k in m.add) out.put("mylist", k, withContext(Dispatchers.Main) { app.describe(k) } ?: JsonObject(mapOf("key" to JsonPrimitive(k))))
            for (k in m.remove) out.del("mylist", k)
            val films = OrbitSyncLogic.planResume(states, snap.resume, Watch.MIN_RESUME_MS)
            for ((k, s) in films.toServer) out.put("resume", k, OrbitBatch.resumeValue(s, withContext(Dispatchers.Main) { app.describe(k) }))
            val eps = OrbitSyncLogic.planEpisodes(states.filterKeys { it.startsWith("e|") }, seriesOf::get, snap.episodes, Watch.MIN_RESUME_MS)
            for (p in eps.toServer) out.episodes += OrbitBatch.episodeItem(p, withContext(Dispatchers.Main) { app.describe(p.seriesKey) })
            if (!out.isEmpty) send(l, out)
            syncFormats(l, snap)
            withContext(Dispatchers.Main) {
                if (link != l) return@withContext                      // signed out meanwhile: keep nothing
                persist(saved.copy(listBase = m.result))
                val merged = OrbitSyncLogic.withLocalOnly(app.settings.myList.toSet(), m.result)
                if (merged != app.settings.myList.toSet()) app.update { it.copy(myList = OrbitDesk.ordered(it.myList, merged)) }
                val shows = OrbitSyncLogic.planShows(rows.mapValues { WatchStore.toShared(it.value) }, snap.shows)
                    .map { WatchStore.fromShared(it, app.watch.data.series[it.key]) }
                app.watch.applyRemote(films.toLocal.mapValues { it.value to null } + eps.toLocal.mapValues { it.value.first to it.value.second }, shows)
                syncStatus = "Synced " + TimeFmt.time(System.currentTimeMillis() / 1000)
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

    /**
     * The per-channel live stream formats (TS / HLS) follow the profile like on the TV (app/.../orbit/FormatSync.kt):
     * one value in the profile's server prefs ("tvChannelFormats"); the newer copy wins as a whole.
     */
    private suspend fun syncFormats(l: OrbitLink, snap: SyncSnapshot) {
        val local = withContext(Dispatchers.Main) { app.channelFormats() }
        val server = snap.prefs?.get(ChannelFormats.PREF_KEY)?.let { ChannelFormats.fromJson(it) }
        val m = ChannelFormats.merge(local, server)
        if (m.toServer) api.statePut(l, "prefs", ChannelFormats.PREF_KEY, m.result.toJson())
        else if (m.result != local) withContext(Dispatchers.Main) { if (link == l) app.setChannelFormats(m.result) }
    }

    private data class Quad(val list: Set<String>, val states: Map<String, io.github.kmmarin.tvbro.data.WatchState>,
                            val seriesOf: Map<String, String>, val rows: Map<String, SeriesRow>)

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

    private fun send(l: OrbitLink, out: Outbox) {
        if (!legacy && api.batch(l, out)) return
        legacy = true
        for (op in out.ops) {
            val sc = (op["scope"] as JsonPrimitive).content
            val key = (op["key"] as JsonPrimitive).content
            if ((op["op"] as JsonPrimitive).content == "del") api.stateDel(l, sc, key) else api.statePut(l, sc, key, op["value"]!!)
        }
        if (out.episodes.isNotEmpty()) api.pushEpisodes(l, out.episodes)
    }

    /** Sign-out or a 401: everything the account brought goes (OrbitUnlink); finished at the next start if interrupted. */
    private suspend fun forget(signedOutByServer: Boolean) = withContext(NonCancellable) {
        runCatching { pendingFile.writeText("1") }
        withContext(Dispatchers.Main) {
            loop?.cancel(); loop = null; soon?.cancel()
            beat?.cancel(); beat = null; holding = false
            recBeats.values.forEach { it.cancel() }; recBeats.clear()
            streamAsk?.answer?.complete(null)
            picking = false
            signedOut = signedOutByServer
        }
        syncLock.withLock {
            cached = null; legacy = false
            withContext(Dispatchers.Main) {
                val ids = saved.serverSources
                try {
                    OrbitUnlink.wipe(object : OrbitUnlink.Steps {
                        override fun setPending(on: Boolean) { if (on) runCatching { pendingFile.writeText("1") } else pendingFile.delete() }
                        override fun clearSession() = persist(OrbitSaved(serverSources = ids))
                        override fun serverSources() = ids
                        override fun removeSources(ids: Set<String>) { if (app.playing != null) app.stopPlayback(); app.replaceSources(emptyList(), ids) }
                        override fun forgetServerSources() = persist(OrbitSaved())
                        override fun clearWatchState() = app.watch.clearAll()
                        override fun clearAccountData() = app.clearProfileData()
                    })
                } catch (e: Exception) { Diag.log("Orbit: sign-out cleanup stopped (${e.javaClass.simpleName}); finished at the next start") }
                if (signedOutByServer) app.toast("Signed out by the home server: sign in again in Settings ▸ Orbit account")
            }
        }
    }

    // ---------- Discover (Seerr through the home server, API-v1.md "Discover and requests") ----------
    /** A signed-in GET (null when not signed in); a 401 signs out. Errors other than 401 are thrown. */
    suspend fun get(path: String): JsonObject? {
        val l = link ?: return null
        return try { withContext(Dispatchers.IO) { api.get(l, path) } } catch (e: OrbitError) {
            if (e.code == 401) { forget(signedOutByServer = true); return null }
            throw e
        }
    }

    /** A signed-in call off the UI thread (catalogue, profiles); a 401 signs out and answers null, other errors are thrown. */
    suspend fun <T> call(f: (OrbitLink) -> T): T? {
        val l = link ?: return null
        return try { withContext(Dispatchers.IO) { f(l) } } catch (e: OrbitError) {
            if (e.code == 401) { forget(signedOutByServer = true); return null }
            throw e
        }
    }

    /** Asks Seerr for a film or show as this account (POST /v1/requests); the answer's message for the viewer. */
    suspend fun request(type: String, tmdb: Long): String {
        val l = link ?: return "Sign in to Orbit first"
        return try {
            val j = withContext(Dispatchers.IO) { api.post(l, "/v1/requests", JsonObject(mapOf("type" to JsonPrimitive(type), "tmdb" to JsonPrimitive(tmdb)))) }
            if ((j["created"] as? JsonPrimitive)?.content == "true") "Requested" else "Already available or requested"
        } catch (e: OrbitError) {
            if (e.code == 401) { forget(signedOutByServer = true); "Signed out" } else e.message ?: "Request refused"
        } catch (e: Exception) { OrbitDesk.describe(e) }
    }

    // ---------- the provider's stream limit, shared with TVs, browsers and recordings ----------
    var streamAsk by mutableStateOf<StreamAsk?>(null); private set
    /** This app holds a slot (one however often it zaps). */
    var holding = false; private set
    private var beat: Job? = null

    /**
     * Before IPTV plays (signed in only): joins the household's stream count. When every stream is in use, [ask] lists
     * them and asks which one to stop; without [ask] (the TV screen's preview) it just answers false. The home server
     * not answering never blocks playback.
     */
    /**
     * An IPTV film or episode from this computer's facade address [url], after [claimStream]: a converted copy that fits
     * the connection away from home (speed test, then `POST /v1/vod/play`), or null to play [url] as it is.
     */
    suspend fun convertedFilm(url: String): String? {
        val l = link ?: return null
        val sid = saved.streamSid.takeIf { it.isNotEmpty() } ?: return null
        if (!url.startsWith(l.server + "/xt/")) return null
        return withContext(Dispatchers.IO) { try { api.vodPlay(l, url, sid, api.speedKbps(l)) } catch (e: Exception) { null } }
    }

    suspend fun claimStream(what: String, ask: Boolean): Boolean {
        val l = link ?: return true
        if (holding && beat?.isActive == true) return true
        val sid = saved.streamSid.ifEmpty { ("dk" + java.util.UUID.randomUUID().toString().replace("-", "")).also { persist(saved.copy(streamSid = it)) } }
        try {
            var st = withContext(Dispatchers.IO) { api.streams(l) }
            var tries = 0
            while (true) {
                val mine = st.active.any { it.sid.isNotEmpty() && sid.startsWith(it.sid) }
                var replace: String? = null
                if (!mine && st.count >= st.max) {
                    if (!ask) return false
                    val q = StreamAsk(st.max, StreamChoice.rows(st.active.map { StreamChoice.Use(it.id, it.label, it.profile, it.what, it.recording, it.device, it.since) }), CompletableDeferred(), what)
                    streamAsk = q
                    replace = (try { q.answer.await() } finally { if (streamAsk === q) streamAsk = null }) ?: return false
                }
                when (val r = withContext(Dispatchers.IO) { api.streamStart(l, sid, device, what, replace) }) {
                    OrbitApi.StreamStart.Ok -> break
                    OrbitApi.StreamStart.Recording -> { app.toast("Every stream is in use by a recording on the home server"); return false }
                    is OrbitApi.StreamStart.Changed -> { if (++tries >= 3) return false; st = r.now }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: OrbitError) {
            if (e.code == 401) { scope.launch(Dispatchers.IO) { forget(signedOutByServer = true) }; return true }
            Diag.log("Orbit: stream count refused (${e.code}); playing anyway"); return true
        } catch (e: Exception) {
            Diag.log("Orbit: stream count not reachable (${e.javaClass.simpleName}); playing anyway"); return true
        }
        holding = true
        startBeat(l, sid)
        return true
    }

    fun answerStreamAsk(stop: String?) { streamAsk?.answer?.complete(stop) }

    private fun startBeat(l: OrbitLink, sid: String) {
        beat?.cancel()
        beat = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(10_000)
                val why = try { api.streamHeartbeat(l, sid) } catch (e: Exception) { null }
                if (why != null) {
                    withContext(Dispatchers.Main) {
                        holding = false
                        if (app.playing != null) app.stopPlayback()
                        app.toast(if (why == "recording") "Stopped: a recording needs the stream" else "Stopped: another device started watching")
                    }
                    break
                }
            }
        }
    }

    // ---------- recordings on this computer hold a slot of their own, as the home server's recordings do ----------
    private val recBeats = HashMap<String, Job>()

    /** The stream id of recording [id]: its own (not the player's), so playing and recording count as two streams. */
    internal fun recSid(id: String): String = "dr" + id.take(8).padEnd(8, '0') + saved.streamSid.removePrefix("dk").ifEmpty { "0".repeat(24) }.take(24)

    /**
     * Before recording [id] starts (signed in only): takes a slot of the household's stream count. When every slot is in
     * use, [ask] lists the streams and asks which to stop (a recording started by hand); without [ask] (a scheduled
     * one) the home server stops the oldest viewer, as for its own recordings. False when every slot is held by
     * recordings or the viewer cancelled. The home server not answering never stops a recording.
     */
    suspend fun claimRecording(id: String, title: String, ask: Boolean): Boolean {
        val l = link ?: return true
        val sid = recSid(id)
        try {
            var st = withContext(Dispatchers.IO) { api.streams(l) }
            var tries = 0
            while (true) {
                val mine = st.active.any { it.sid.isNotEmpty() && sid.startsWith(it.sid) }
                var replace: String? = null
                if (!mine && st.count >= st.max && ask) {
                    val q = StreamAsk(st.max, StreamChoice.rows(st.active.map { StreamChoice.Use(it.id, it.label, it.profile, it.what, it.recording, it.device, it.since) }),
                        CompletableDeferred(), title, verb = "record")
                    withContext(Dispatchers.Main) { streamAsk = q }
                    replace = (try { q.answer.await() } finally { withContext(Dispatchers.Main) { if (streamAsk === q) streamAsk = null } }) ?: return false
                }
                when (val r = withContext(Dispatchers.IO) { api.streamStart(l, sid, "$device · recording", "Recording: $title", replace) }) {
                    OrbitApi.StreamStart.Ok -> break
                    OrbitApi.StreamStart.Recording -> return false
                    is OrbitApi.StreamStart.Changed -> { if (++tries >= 3) return false; st = r.now }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: OrbitError) {
            if (e.code == 401) { scope.launch(Dispatchers.IO) { forget(signedOutByServer = true) }; return true }
            Diag.log("Orbit: stream count refused the recording (${e.code}); recording anyway"); return true
        } catch (e: Exception) {
            Diag.log("Orbit: stream count not reachable (${e.javaClass.simpleName}); recording anyway"); return true
        }
        withContext(Dispatchers.Main) {
            recBeats.remove(id)?.cancel()
            recBeats[id] = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    delay(10_000)
                    val why = try { api.streamHeartbeat(l, sid) } catch (e: Exception) { null }
                    if (why != null) {
                        // another device took this slot: the recording goes on (stopping it would lose it)
                        withContext(Dispatchers.Main) { app.toast("Another device took the stream of the recording \"$title\"; it goes on, but the provider may cut it") }
                        break
                    }
                }
            }
        }
        return true
    }

    /** Recording [id] ended: its slot is given back. */
    fun releaseRecording(id: String) {
        val b = recBeats.remove(id) ?: return
        b.cancel()
        val l = link ?: return
        val sid = recSid(id)
        scope.launch(Dispatchers.IO) { runCatching { api.streamStop(l, sid) } }
    }

    /** Nothing plays any more: the slot is given back. */
    fun releaseStream() {
        if (!holding) return
        holding = false
        beat?.cancel(); beat = null
        val l = link ?: return
        val sid = saved.streamSid
        scope.launch(Dispatchers.IO) { api.streamStop(l, sid) }
    }
}
