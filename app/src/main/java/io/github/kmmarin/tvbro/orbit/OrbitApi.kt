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
        val DEFAULT: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
        private val JSON = "application/json".toMediaType()
        private const val COOKIE = "tvbro_s"

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
