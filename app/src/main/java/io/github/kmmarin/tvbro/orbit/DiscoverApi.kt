// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.orbit

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Sonarr / Radarr progress of a title Seerr knows (API-v1.md `Download`). */
@Immutable
data class DownloadInfo(val percent: Int, val size: Long, val sizeLeft: Long, /** ISO time or null. */ val eta: String?, /** Queue entries (e.g. episodes). */ val items: Int = 1)

/** A Seerr title (API-v1.md "Discover and requests", `Title`). */
@Immutable
data class DiscoverTitle(
    val type: String,                 // "movie" | "tv"
    val tmdb: Long,
    val title: String,
    val year: String? = null,
    val overview: String = "",
    val poster: String? = null,
    val backdrop: String? = null,
    val rating: String? = null,
    /** available | partial | processing | pending | requestable */
    val status: String = "requestable",
    /** pending | approved | declined | failed | completed, or null. */
    val requestStatus: String? = null,
    val download: DownloadInfo? = null,
    /** The title in this profile's catalogue (Play instead of Request), or null. */
    val play: CatalogItem? = null,
) {
    val key: String get() = "$type:$tmdb"
    val isShow: Boolean get() = type == "tv"
}

/** One of the account's requests (`Request`). */
@Immutable
data class DiscoverRequest(
    val id: Long, val type: String, val tmdb: Long, val status: String, val mediaStatus: String, val created: String,
    val seasons: List<Int>, val by: String, val download: DownloadInfo?, val title: DiscoverTitle?,
)

/** A row of `/v1/discover/rows` (or one page of it): [page] of [pages] (Seerr sends 20 titles a page; 0 pages = not loaded). */
data class DiscoverRow(val id: String, val title: String, val items: List<DiscoverTitle>, val page: Int = 1, val pages: Int = 1)

/** A person in the search results (`Person`); [photo] already routed through the home server. */
@Immutable
data class DiscoverPerson(val id: Long, val name: String, val photo: String?, val knownFor: List<DiscoverTitle>)

/** One page of `GET /v1/discover/search`. [total]: matches over all pages, or null. */
data class DiscoverSearchPage(
    val results: List<DiscoverTitle>, val people: List<DiscoverPerson>, val page: Int, val pages: Int, val total: Int?, val stale: Boolean,
)

/** A season of a show on its title page, with its own Seerr status. */
@Immutable
data class DiscoverSeason(val season: Int, val episodes: Int, val name: String, val status: String)

@Immutable
data class CastMember(val id: Long, val name: String, val character: String, /** Through the home server, or null. */ val photo: String?)

/** A request on a title page (`TitlePage.requests`): [created] ISO time, [by] the Seerr user's display name. */
@Immutable
data class TitleRequest(val id: Long, val status: String, val created: String, val by: String, val seasons: List<Int>)

/** `GET /v1/discover/title/{movie|tv}/{tmdb}`: everything a Discover title page shows (API-v1.md `TitlePage`). */
@Immutable
data class DiscoverTitlePage(
    val title: DiscoverTitle,
    val genres: List<String> = emptyList(),
    val tagline: String = "",
    /** "YYYY-MM-DD" or null. */
    val date: String? = null,
    /** Minutes (a show's episode length), or null. */
    val runtime: Int? = null,
    val cast: List<CastMember> = emptyList(),
    /** A youtube.com watch link, or null. */
    val trailer: String? = null,
    val requests: List<TitleRequest> = emptyList(),
    val seerrUrl: String? = null,
    val seasons: List<DiscoverSeason> = emptyList(),
    val seasonCount: Int = 0,
    val episodeCount: Int = 0,
    val networks: List<String> = emptyList(),
    val showStatus: String = "",
)

/** `GET /v1/discover/status`: [configured] Seerr is set up on the home server; [linked] this account may request. */
data class DiscoverStatus(val configured: Boolean, val reachable: Boolean, val linked: Boolean, val admin: Boolean)

/** What a Discover card or hero says about a title (spec section 1, "Discover status badges"). */
enum class BadgeKind { REQUESTED, APPROVED, DOWNLOADING, AVAILABLE, PENDING }

@Immutable
data class Badge(val kind: BadgeKind, val label: String, /** 0..1 for DOWNLOADING. */ val fraction: Float = 0f)

/**
 * The Seerr routes of the home server for the TV (signed in). Every call throws [OrbitError] on an error answer: 409
 * on rows when Seerr is not set up, 409 on a request when the account has no Seerr user, 503 while Seerr is down.
 * Call off the main thread.
 */
class DiscoverApi(private val api: OrbitApi = OrbitApi()) {
    fun status(link: OrbitLink): DiscoverStatus {
        val j = api.getJson(link, "/v1/discover/status")
        return DiscoverStatus(j.bool("configured"), j.bool("reachable"), j.bool("linked"), j["keySet"] != null)
    }

    fun rows(link: OrbitLink): List<DiscoverRow> = parseRows(api.getJson(link, "/v1/discover/rows"), link.server)

    /** One further page of a row (`/v1/discover/rows/{id}?page=`): trending, movies, tv or upcoming. */
    fun rowPage(link: OrbitLink, id: String, page: Int): DiscoverRow =
        parseRow(api.getJson(link, "/v1/discover/rows/${enc(id)}?page=$page"), link.server)

    /** Films, shows and people for [q] (1–100 characters), one page at a time. */
    fun search(link: OrbitLink, q: String, page: Int = 1): DiscoverSearchPage =
        parseSearch(api.getJson(link, "/v1/discover/search?q=${enc(q.take(100))}&page=$page"), link.server)

    /** A title page; [OrbitError] 404 when Seerr does not know the title. */
    fun titlePage(link: OrbitLink, type: String, tmdb: Long): DiscoverTitlePage? =
        parseTitlePage(api.getJson(link, "/v1/discover/title/${if (type == "tv") "tv" else "movie"}/$tmdb"), link.server)

    /** The account's own requests, newest first; [linked] false when it has no Seerr user. */
    fun requests(link: OrbitLink): Pair<List<DiscoverRequest>, Boolean> {
        val j = api.getJson(link, "/v1/requests")
        return parseRequests(j, link.server) to j.bool("linked", true)
    }

    /**
     * Asks for [t]: a film, or the [seasons] of a show (null: every season neither available nor requested). Returns
     * the new state of the title and whether a request was made.
     */
    fun request(link: OrbitLink, t: DiscoverTitle, seasons: List<Int>? = null): Pair<DiscoverTitle?, Boolean> {
        val j = api.postJson(link, "/v1/requests", requestBody(t, seasons))
        return title(j["title"], link.server) to j.bool("created")
    }

    companion object {
        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        private fun JsonObject.bool(k: String, def: Boolean = false) = (this[k] as? JsonPrimitive)?.booleanOrNull ?: def
        private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

        private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
        private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

        /** The body of `POST /v1/requests`: seasons only for a show and only when chosen. */
        fun requestBody(t: DiscoverTitle, seasons: List<Int>?): JsonObject = JsonObject(buildMap {
            put("type", JsonPrimitive(t.type)); put("tmdb", JsonPrimitive(t.tmdb))
            if (t.isShow && seasons != null) put("seasons", JsonArray(seasons.sorted().map { JsonPrimitive(it) }))
        })

        fun parseRows(j: JsonObject, server: String): List<DiscoverRow> = (j["rows"] as? JsonArray).orEmpty().mapNotNull { e ->
            (e as? JsonObject)?.let { parseRow(it, server) }
        }

        fun parseRow(o: JsonObject, server: String): DiscoverRow {
            val page = (o.int("page") ?: 1).coerceAtLeast(1)
            return DiscoverRow(o.str("id") ?: "", o.str("title") ?: "", titles(o["items"], server), page, (o.int("pages") ?: page).coerceAtLeast(0))
        }

        fun parseSearch(j: JsonObject, server: String): DiscoverSearchPage {
            val people = (j["people"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                DiscoverPerson(o.long("id") ?: return@mapNotNull null, o.str("name") ?: "", image(server, o.str("photo")), titles(o["knownFor"], server))
            }
            val page = (j.int("page") ?: 1).coerceAtLeast(1)
            return DiscoverSearchPage(titles(j["results"], server), people, page, (j.int("pages") ?: page).coerceAtLeast(0), j.int("total"), j.bool("stale"))
        }

        fun parseTitlePage(j: JsonObject, server: String): DiscoverTitlePage? {
            val t = title(j, server) ?: return null
            fun strings(k: String) = (j[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
            val seasons = (j["seasons"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val n = o.int("season") ?: return@mapNotNull null
                DiscoverSeason(n, o.int("episodes") ?: 0, o.str("name")?.takeIf { it.isNotBlank() } ?: "Season $n", o.str("status") ?: "requestable")
            }.filter { it.season > 0 }
            val cast = (j["cast"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                CastMember(o.long("id") ?: 0, o.str("name") ?: return@mapNotNull null, o.str("character") ?: "", image(server, o.str("photo")))
            }
            val requests = (j["requests"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                TitleRequest(o.long("id") ?: 0, o.str("status") ?: "", o.str("created") ?: "", o.str("by") ?: "",
                    (o["seasons"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull })
            }
            // the trailer is always a youtube.com watch page (built by the server); anything else is dropped
            val trailer = (j["trailer"] as? JsonObject)?.str("url")?.takeIf { it.startsWith("https://www.youtube.com/watch?v=") }
            val seerr = j.str("seerrUrl")?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            return DiscoverTitlePage(
                t, strings("genres"), j.str("tagline") ?: "", j.str("date"), j.int("runtime")?.takeIf { it > 0 }, cast, trailer, requests, seerr,
                seasons, j.int("seasonCount") ?: seasons.size, j.int("episodeCount") ?: 0, strings("networks"), j.str("showStatus") ?: "",
            )
        }

        private val TMDB = Regex("""^https://image\.tmdb\.org/t/p/(w92|w154|w185|w300|w342|w500|w780|w1280|original)/([A-Za-z0-9_-]{1,100}\.(?:jpg|jpeg|png|webp))$""")

        /**
         * Where the app loads a Discover image from. The home server signs every poster / backdrop into a relative
         * `/img?u=…&s=…` link, loaded from the server with the session cookie ([OrbitApi.imageHeaders]); cast and
         * people photos come as raw TMDB URLs and go through `/v1/discover/image` (API-v1.md), so only the server
         * talks to TMDB. Anything else (an unexpected absolute address) is dropped.
         */
        fun image(server: String, url: String?): String? {
            if (url.isNullOrBlank()) return null
            if (url.startsWith("/")) return server + url
            return tmdbPath(url)?.let { server + "/v1/discover/image?p=" + enc(it) }
        }

        /** "/w185/abc.jpg" of a TMDB artwork URL with an allowed size and a plain file name, else null. */
        fun tmdbPath(url: String): String? = TMDB.matchEntire(url)?.let { "/" + it.groupValues[1] + "/" + it.groupValues[2] }

        fun titles(e: JsonElement?, server: String) = (e as? JsonArray).orEmpty().mapNotNull { title(it, server) }

        fun download(e: JsonElement?): DownloadInfo? {
            val o = e as? JsonObject ?: return null
            val pct = (o["percent"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
            return DownloadInfo(Math.round(pct).toInt().coerceIn(0, 100), o.long("size") ?: 0, o.long("sizeLeft") ?: 0, o.str("eta"), (o.int("items") ?: 1).coerceAtLeast(1))
        }

        fun title(e: JsonElement?, server: String): DiscoverTitle? {
            val o = e as? JsonObject ?: return null
            val type = o.str("type")?.takeIf { it == "movie" || it == "tv" } ?: return null
            val tmdb = o.long("tmdb") ?: return null
            val rating = (o["rating"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 }?.let { String.format(java.util.Locale.ROOT, "%.1f", it) }
            return DiscoverTitle(
                type, tmdb, o.str("title") ?: "", o.str("year")?.takeIf { it.isNotBlank() && it != "0" }, o.str("overview") ?: "",
                image(server, o.str("poster")), image(server, o.str("backdrop")), rating, o.str("status") ?: "requestable", o.str("requestStatus"),
                download(o["download"]), CatalogApi.item(o["play"], server),
            )
        }

        fun parseRequests(j: JsonObject, server: String): List<DiscoverRequest> = (j["requests"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val type = o.str("type") ?: return@mapNotNull null
            DiscoverRequest(
                o.long("id") ?: 0, type, o.long("tmdb") ?: return@mapNotNull null, o.str("status") ?: "", o.str("mediaStatus") ?: "",
                o.str("created") ?: "", (o["seasons"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull },
                (o["requestedBy"] as? JsonObject)?.str("name") ?: "", download(o["download"]), title(o["title"], server),
            )
        }

        /**
         * The badge for [t] (null: nothing asked for yet, the card offers Request). The catalogue having the title, or
         * Seerr calling it available, wins; then a running download; then the request's state.
         */
        fun badge(t: DiscoverTitle, nowMs: Long = System.currentTimeMillis()): Badge? = when {
            t.play != null || t.status == "available" -> Badge(BadgeKind.AVAILABLE, "Available")
            t.download != null -> Badge(BadgeKind.DOWNLOADING, downloadLabel(t.download, nowMs), t.download.percent / 100f)
            t.status == "partial" -> Badge(BadgeKind.AVAILABLE, "Partly available")
            t.requestStatus == "approved" || t.status == "processing" -> Badge(BadgeKind.APPROVED, "Approved")
            t.requestStatus == "pending" -> Badge(BadgeKind.REQUESTED, "Requested")
            t.status == "pending" -> Badge(BadgeKind.PENDING, "Pending")
            else -> null
        }

        /** A request's badge: its title's, else what the request itself says (declined and failed in amber). */
        fun requestBadge(r: DiscoverRequest, nowMs: Long = System.currentTimeMillis()): Badge =
            badge(requestTitle(r), nowMs) ?: when (r.status) {
                "declined" -> Badge(BadgeKind.PENDING, "Declined")
                "failed" -> Badge(BadgeKind.PENDING, "Failed")
                "approved" -> Badge(BadgeKind.APPROVED, "Approved")
                else -> Badge(BadgeKind.REQUESTED, "Requested")
            }

        /** The title of [r] with the request's own (fresh) download and state. */
        fun requestTitle(r: DiscoverRequest): DiscoverTitle =
            (r.title ?: DiscoverTitle(r.type, r.tmdb, "TMDb ${r.tmdb}", status = r.mediaStatus.ifEmpty { "requestable" }))
                .copy(download = r.download, requestStatus = r.status.takeIf { it in setOf("pending", "approved", "declined", "failed", "completed") } ?: r.title?.requestStatus)

        /** "Downloading 42% · ~18 min" (the time left from [DownloadInfo.eta]; left out when unknown or past). */
        fun downloadLabel(d: DownloadInfo, nowMs: Long = System.currentTimeMillis()): String {
            val left = d.eta?.let { isoMs(it) }?.let { (it - nowMs) / 60_000 }?.takeIf { it >= 0 }
            val t = when {
                left == null -> null
                left < 1 -> "~1 min"
                left < 60 -> "~$left min"
                left < 24 * 60 -> "~${left / 60} h" + if (left % 60 >= 5) " ${left % 60} min" else ""
                else -> "~${left / 1440} d"
            }
            return "Downloading ${d.percent}%" + (t?.let { " · $it" } ?: "")
        }

        /** Milliseconds of an ISO 8601 time with a zone ("2026-11-14T10:20:00.000Z", "+01:00"), or null. */
        fun isoMs(s: String): Long? {
            // (java.time needs API 26; the app runs from 24)
            val m = ISO.matchEntire(s.trim()) ?: return null
            val g = m.groupValues
            val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            c.clear()
            c.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].ifEmpty { "0" }.toInt())
            val zone = g[7]
            val off = if (zone.isEmpty() || zone == "Z") 0 else {
                val d = zone.substring(1).replace(":", "")
                (if (zone[0] == '-') -1 else 1) * (d.take(2).toInt() * 60 + d.drop(2).ifEmpty { "0" }.toInt()) * 60_000L
            }
            return c.timeInMillis - off
        }

        private val ISO = Regex("""(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d)(?::(\d\d)(?:\.\d+)?)?(Z|[+-]\d\d:?\d\d)?""")
    }
}
