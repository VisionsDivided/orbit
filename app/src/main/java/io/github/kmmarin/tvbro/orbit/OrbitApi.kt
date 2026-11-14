// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.orbit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** This TV's link to an Orbit home server (stored encrypted, see OrbitStore). */
data class OrbitLink(
    val server: String, val session: String, val csrf: String,
    val account: String, val username: String, val profile: String, val kids: Boolean,
)

data class PairStart(val pairId: String, val code: String, val expires: Long, val interval: Int)

sealed class PairPoll {
    object Pending : PairPoll()
    data class Approved(val link: OrbitLink) : PairPoll()
    data class Gone(val message: String) : PairPoll()
}

/** An answer the server gave on purpose (its "error" text is safe to show). */
class OrbitError(val code: Int, message: String) : IOException(message)

/** The home server's /v1 API (docs/orbit/API-v1.md) as far as the TV needs it. */
class OrbitApi(private val client: OkHttpClient = DEFAULT) {
    companion object {
        // no redirects: the session cookie and CSRF token are set by hand and OkHttp would carry them to any host
        val DEFAULT: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
        private val JSON = "application/json".toMediaType()
        private const val COOKIE = "tvbro_s"
        /** The Orbit home server's port (docs/orbit/DEPLOY.md). */
        const val DEFAULT_PORT = 8750

        /**
         * "192.0.2.10:8750", "http://host:8750/" or "https://orbit.example.com" -> "http://192.0.2.10:8750" etc.;
         * null for anything that is not a plain http(s) server address (paths, queries and logins are refused).
         */
        fun normalizeServer(input: String): String? {
            var s = input.trim()
            if (s.isEmpty() || s.any { it.isWhitespace() }) return null
            if (!s.contains("://")) s = "http://$s"
            val u = s.toHttpUrlOrNull() ?: return null
            if (u.scheme != "http" && u.scheme != "https") return null
            if (u.username.isNotEmpty() || u.password.isNotEmpty() || u.query != null || u.fragment != null) return null
            if (u.encodedPath != "/") return null
            // no port typed on plain http: the Orbit home server's own port (8750), not 80 (an easy mistake on a remote)
            val authority = s.substringAfter("://").substringBefore('/')
            val portTyped = if (authority.startsWith("[")) authority.contains("]:") else authority.contains(':')
            if (!portTyped && u.scheme == "http") return u.newBuilder().port(DEFAULT_PORT).build().toString().trimEnd('/')
            return u.toString().trimEnd('/')
        }

        /** What the TV's QR code opens: the web app's "Link a TV" dialog with the code filled in. */
        fun approveUrl(server: String, code: String) = "$server/#pair:$code"

        internal fun linkFrom(server: String, session: String, csrf: String, me: JsonObject): OrbitLink {
            val acc = me["account"]?.jsonObject
            val prof = (me["profile"] as? JsonObject)
            return OrbitLink(
                server, session, csrf,
                account = acc?.str("displayName") ?: "", username = acc?.str("username") ?: "",
                profile = prof?.str("name") ?: "", kids = (prof?.get("kids") as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }

        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    }

    private fun call(server: String, path: String, body: String?, link: OrbitLink? = null): JsonObject {
        val b = Request.Builder().url(server + path).header("User-Agent", "Orbit-TV")
        if (body != null) b.post(body.toRequestBody(JSON)).header("X-TvBro", "1")
        if (link != null) b.header("Cookie", "$COOKIE=${link.session}").header("X-CSRF-Token", link.csrf)
        client.newCall(b.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
            if (!r.isSuccessful) {
                val msg = (obj["error"] as? JsonPrimitive)?.contentOrNull ?: "HTTP ${r.code}"
                throw OrbitError(r.code, msg)
            }
            return obj
        }
    }

    fun start(server: String, device: String): PairStart {
        val j = call(server, "/v1/pair/start", Json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("device" to JsonPrimitive(device)))))
        return PairStart(
            pairId = (j["pairId"] as JsonPrimitive).content, code = (j["code"] as JsonPrimitive).content,
            expires = (j["expires"] as? JsonPrimitive)?.longOrNull ?: 0L, interval = (j["interval"] as? JsonPrimitive)?.intOrNull ?: 3,
        )
    }

    fun poll(server: String, pairId: String): PairPoll {
        val j = try {
            call(server, "/v1/pair/poll", Json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("pairId" to JsonPrimitive(pairId)))))
        } catch (e: OrbitError) {
            if (e.code == 404) return PairPoll.Gone(e.message ?: "expired")
            throw e
        }
        if ((j["status"] as? JsonPrimitive)?.contentOrNull != "approved") return PairPoll.Pending
        val session = (j["session"] as JsonPrimitive).content
        val csrf = (j["csrf"] as JsonPrimitive).content
        return PairPoll.Approved(linkFrom(server, session, csrf, j["me"]!!.jsonObject))
    }

    /** The link with current names, or null when the server no longer knows this TV's session (401). */
    fun me(link: OrbitLink): OrbitLink? = try {
        val j = call(link.server, "/v1/me", null, link)
        linkFrom(link.server, link.session, (j["csrf"] as? JsonPrimitive)?.contentOrNull ?: link.csrf, j)
    } catch (e: OrbitError) {
        if (e.code == 401) null else throw e
    }

    fun logout(link: OrbitLink) {
        runCatching { call(link.server, "/v1/auth/logout", "{}", link) }
    }

    /** The household's playlists (linked TVs only): ids match the server's ("p0", "p1" …), so keys line up. */
    fun playlists(link: OrbitLink): List<io.github.kmmarin.tvbro.data.Source> {
        val j = call(link.server, "/v1/device/playlists", null, link)
        val arr = j["playlists"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            val kind = o.str("kind") ?: return@mapNotNull null
            if (kind != "xtream" && kind != "m3u") return@mapNotNull null
            io.github.kmmarin.tvbro.data.Source(id = id, name = o.str("name") ?: id, kind = kind, url = o.str("url") ?: "",
                epgUrl = o.str("epgUrl") ?: "", server = o.str("server") ?: "", user = o.str("user") ?: "", pass = o.str("pass") ?: "")
        }
    }

    /** Who uses the provider's streams now (tvBro's stream count, shared by TVs, browsers and recordings). */
    /** [id]: the stream's public id, to name it in [streamStart] (empty from servers before 2026-11-14). */
    data class StreamUse(val sid: String, val label: String, val what: String, val recording: Boolean,
                         val id: String = "", val profile: String = "")
    data class StreamStatus(val max: Int, val count: Int, val active: List<StreamUse>)

    fun streams(link: OrbitLink): StreamStatus = parseStreams(call(link.server, "/v1/streams", null, link))

    private fun parseStreams(j: JsonObject): StreamStatus {
        val act = (j["active"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            StreamUse(o.str("sid") ?: "", o.str("label") ?: "", o.str("what") ?: "", (o["recording"] as? JsonPrimitive)?.booleanOrNull ?: false,
                o.str("id") ?: "", o.str("profile") ?: "")
        }
        return StreamStatus((j["max"] as? JsonPrimitive)?.intOrNull ?: 1, (j["count"] as? JsonPrimitive)?.intOrNull ?: act.size, act)
    }

    private fun streamCall(link: OrbitLink, op: String, sid: String, label: String = "", what: String = "", replace: String? = null): JsonObject {
        val m = mutableMapOf<String, kotlinx.serialization.json.JsonElement>("sid" to JsonPrimitive(sid), "label" to JsonPrimitive(label), "what" to JsonPrimitive(what))
        if (!replace.isNullOrEmpty()) m["replace"] = JsonPrimitive(replace)
        return call(link.server, "/api/stream/$op", Json.encodeToString(JsonObject.serializer(), JsonObject(m)), link)
    }

    sealed class StreamStart {
        object Ok : StreamStart()
        /** Every slot is held by a recording. */
        object Recording : StreamStart()
        /** The stream chosen to stop has ended and no slot is free: nothing changed; [now] is the current list. */
        data class Changed(val now: StreamStatus) : StreamStart()
    }

    /** Takes a slot: when all are in use, the stream whose id is [replace] stops (none given: the oldest viewer). */
    fun streamStart(link: OrbitLink, sid: String, label: String, what: String, replace: String? = null): StreamStart {
        val j = streamCall(link, "start", sid, label, what, replace)
        if ((j["ok"] as? JsonPrimitive)?.booleanOrNull == true) return StreamStart.Ok
        if ((j["changed"] as? JsonPrimitive)?.booleanOrNull == true) return StreamStart.Changed(parseStreams(j["streams"] as? JsonObject ?: JsonObject(emptyMap())))
        return StreamStart.Recording
    }

    /** Keeps the slot; returns why it was taken away, or null while it is still ours. */
    fun streamHeartbeat(link: OrbitLink, sid: String): String? =
        (streamCall(link, "heartbeat", sid)["stopped"] as? JsonPrimitive)?.contentOrNull

    fun streamStop(link: OrbitLink, sid: String) { runCatching { streamCall(link, "stop", sid) } }

    /** Episode marks and show summaries of this TV's profile (/v1/progress/shows). */
    fun showsState(link: OrbitLink): Pair<List<ServerEpisode>, List<ServerShow>> =
        OrbitBatch.shows(call(link.server, "/v1/progress/shows", null, link))

    /**
     * Everything the TV syncs, in one request (/v1/sync/state). With [cached], the server answers only "unchanged"
     * when nothing was written since its rev, and [cached] is returned. Null from an older server without the route
     * (then use [state] and [showsState]).
     */
    fun syncState(link: OrbitLink, cached: SyncSnapshot?): SyncSnapshot? {
        val since = cached?.rev?.let { "?since=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""
        val j = try { call(link.server, "/v1/sync/state$since", null, link) } catch (e: OrbitError) {
            if (e.code == 404) return null
            throw e
        }
        OrbitBatch.fromSyncAnswer(j, cached)?.let { return it }
        // an answer the TV cannot use with what it has: ask for everything
        return OrbitBatch.fromSyncAnswer(call(link.server, "/v1/sync/state", null, link), null)
            ?: throw OrbitError(502, "The home server sent an unexpected sync answer")
    }

    /**
     * Queued changes in as few requests as possible (/v1/sync/batch, each applied in one transaction). False from an
     * older server without the route, before anything was sent (then send them one by one).
     */
    fun batch(link: OrbitLink, out: Outbox): Boolean {
        for ((i, c) in OrbitBatch.chunks(out.ops, out.episodes).withIndex()) {
            try {
                call(link.server, "/v1/sync/batch", Json.encodeToString(JsonObject.serializer(), OrbitBatch.body(c)), link)
            } catch (e: OrbitError) {
                if (e.code == 404 && i == 0) return false
                throw e
            }
        }
        return true
    }

    /** Episode changes from this TV; the server keeps the newest per episode. */
    fun pushEpisodes(link: OrbitLink, items: List<JsonObject>) {
        for (chunk in items.chunked(500)) {
            val body = JsonObject(mapOf("items" to kotlinx.serialization.json.JsonArray(chunk)))
            call(link.server, "/v1/progress/episodes", Json.encodeToString(JsonObject.serializer(), body), link)
        }
    }

    /** A signed-in GET of [path] (catalog, profiles: CatalogApi.kt, OrbitProfiles.kt). Errors as [OrbitError]. */
    internal fun getJson(link: OrbitLink, path: String): JsonObject = call(link.server, path, null, link)

    /** A signed-in POST of [body] to [path]. */
    internal fun postJson(link: OrbitLink, path: String, body: JsonObject): JsonObject =
        call(link.server, path, Json.encodeToString(JsonObject.serializer(), body), link)

    /** This TV's profile state on the server: My list and resume points (see docs/orbit/API-v1.md, step 4). */
    fun state(link: OrbitLink): JsonObject = call(link.server, "/api/state", null, link)

    fun statePut(link: OrbitLink, scope: String, key: String, value: kotlinx.serialization.json.JsonElement) {
        val body = JsonObject(mapOf("op" to JsonPrimitive("put"), "key" to JsonPrimitive(key), "value" to value))
        call(link.server, "/api/state/$scope", Json.encodeToString(JsonObject.serializer(), body), link)
    }

    fun stateDel(link: OrbitLink, scope: String, key: String) {
        val body = JsonObject(mapOf("op" to JsonPrimitive("del"), "key" to JsonPrimitive(key)))
        call(link.server, "/api/state/$scope", Json.encodeToString(JsonObject.serializer(), body), link)
    }
}
