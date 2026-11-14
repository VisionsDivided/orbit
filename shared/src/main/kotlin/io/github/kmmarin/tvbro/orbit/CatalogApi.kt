// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.orbit

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

/** A show's next episode in the catalog (`next`). */
data class CatalogNext(val id: String, val season: Int, val num: Int, val title: String?)

/**
 * A catalog title (docs/orbit/API-v1.md "Catalogue"). [key] uses the TV's own key scheme ("m|p0|123", "s|p0|7";
 * Plex / Jellyfin "m|<connection>|<item>"), so My list and resume line up with the local state. Artwork addresses
 * are absolute (the server's relative `/img?…` made absolute; loading them needs the session, see
 * [OrbitApi.imageHeaders]).
 */
data class CatalogItem(
    val key: String,
    val kind: String,                 // "movie" | "show"
    val title: String,
    val year: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val rating: String? = null,
    val genres: List<String> = emptyList(),
    val source: String = "iptv",      // "iptv" | "plex" | "jellyfin"
    val added: Long = 0,
    val released: String? = null,
    /** The two backdrop colours ("#c3261b"), or null: the client works them out from the image. */
    val colors: List<String>? = null,
    /** Resume point in seconds (a show: where its next episode stopped), or null. */
    val progressSecs: Long? = null,
    val progressDur: Long? = null,
    /** The item's own id: IPTV VOD id ("7", "p1~7") or the media server's item id. */
    val id: String = "",
    /** Media connection id (Plex / Jellyfin), else null. */
    val conn: String? = null,
    /** IPTV item in a PIN-locked category (playing asks for the PIN). */
    val locked: Boolean = false,
    val next: CatalogNext? = null,
) {
    val isShow: Boolean get() = kind == "show" || kind == "series"
    val progress: Float? get() = if (progressSecs != null && progressDur != null && progressDur > 0) (progressSecs.toFloat() / progressDur).coerceIn(0f, 1f) else null
}

data class CatalogRow(val id: String, val title: String, val items: List<CatalogItem>)
/** Home and the Recommended pages: hero titles and rows. */
data class CatalogFeed(val hero: List<CatalogItem>, val rows: List<CatalogRow>)
data class CatalogPage(val total: Int, val genres: List<String>, val items: List<CatalogItem>)
data class CatalogCollection(val id: String, val name: String, val count: Int, val poster: String?, val backdrop: String?, val colors: List<String>?,
                             val source: String = "", val kind: String = "movie")

/**
 * The catalog routes for the TV and the desktop app (signed in, per profile). Every call throws [OrbitError] on an error answer;
 * [OrbitError.code] 404 means the home server has no catalog yet (an older server): fall back to local data.
 * Call off the main thread.
 */
class CatalogApi(private val api: OrbitApi = OrbitApi()) {
    /** `GET /v1/catalog/home`: hero and rows (continue, mylist, recent, released, genre:<x>, unwatched). */
    fun home(link: OrbitLink): CatalogFeed = feed(api.getJson(link, "/v1/catalog/home"), link.server)

    /** [kind] "movies" or "shows". */
    fun recommended(link: OrbitLink, kind: String): CatalogFeed = feed(api.getJson(link, "/v1/catalog/$kind/recommended"), link.server)

    /** [source] all | local | iptv | plex | jellyfin; [sort] added | released | title | rating; [limit] 1..200. */
    fun browse(link: OrbitLink, kind: String, source: String = "all", genre: String = "", sort: String = "added", offset: Int = 0, limit: Int = 60): CatalogPage {
        val q = "source=${enc(source)}&genre=${enc(genre)}&sort=${enc(sort)}&offset=$offset&limit=${limit.coerceIn(1, 200)}"
        val j = api.getJson(link, "/v1/catalog/$kind/browse?$q")
        return CatalogPage((j["total"] as? JsonPrimitive)?.intOrNull ?: 0, strings(j["genres"]), items(j["items"], link.server))
    }

    fun collections(link: OrbitLink, kind: String): List<CatalogCollection> =
        (api.getJson(link, "/v1/catalog/$kind/collections")["collections"] as? JsonArray).orEmpty().mapNotNull { collection(it, link.server) }

    fun collection(link: OrbitLink, id: String): Pair<CatalogCollection?, List<CatalogItem>> {
        val j = api.getJson(link, "/v1/catalog/collections/${enc(id)}")
        return collection(j["collection"], link.server) to items(j["items"], link.server)
    }

    companion object {
        private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

        fun feed(j: JsonObject, server: String) = CatalogFeed(items(j["hero"], server), parseRows(j, server))

        fun parseRows(j: JsonObject, server: String): List<CatalogRow> = (j["rows"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            CatalogRow(o.str("id") ?: "", o.str("title") ?: "", items(o["items"], server)).takeIf { it.items.isNotEmpty() }
        }

        fun items(e: JsonElement?, server: String): List<CatalogItem> = (e as? JsonArray).orEmpty().mapNotNull { item(it, server) }

        fun item(e: JsonElement?, server: String): CatalogItem? {
            val o = e as? JsonObject ?: return null
            val key = o.str("key") ?: return null
            val title = o.str("title") ?: return null
            val prog = o["progress"] as? JsonObject
            val rating = (o["rating"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { p ->
                p.doubleOrNull?.let { if (it <= 0) null else if (it == Math.floor(it)) it.toInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", it) }
                    ?: p.contentOrNull?.takeIf { it.isNotBlank() }
            }
            fun num(p: JsonElement?) = (p as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
            val next = (o["next"] as? JsonObject)?.let { n ->
                n.str("id")?.let { CatalogNext(it, (n["season"] as? JsonPrimitive)?.intOrNull ?: 0, (n["num"] as? JsonPrimitive)?.intOrNull ?: 0, n.str("title")) }
            }
            return CatalogItem(
                key = key, kind = o.str("kind") ?: if (key.startsWith("s|")) "show" else "movie", title = title,
                year = o.str("year")?.takeIf { it.isNotBlank() && it != "0" },
                poster = OrbitApi.absolute(server, o.str("poster")), backdrop = OrbitApi.absolute(server, o.str("backdrop")),
                rating = rating, genres = strings(o["genres"]), source = o.str("source") ?: "iptv",
                added = num(o["added"]) ?: 0L, released = o.str("released"),
                colors = (o["colors"] as? JsonArray)?.let { strings(it) }?.takeIf { it.isNotEmpty() },
                progressSecs = num(prog?.get("secs")), progressDur = num(prog?.get("dur")),
                id = o.str("id") ?: "", conn = o.str("conn"),
                locked = (o["locked"] as? JsonPrimitive)?.booleanOrNull ?: false, next = next,
            )
        }

        private fun collection(e: JsonElement?, server: String): CatalogCollection? {
            val o = e as? JsonObject ?: return null
            return CatalogCollection(o.str("id") ?: return null, o.str("name") ?: "", (o["count"] as? JsonPrimitive)?.intOrNull ?: 0,
                OrbitApi.absolute(server, o.str("poster")), OrbitApi.absolute(server, o.str("backdrop")),
                (o["colors"] as? JsonArray)?.let { strings(it) }?.takeIf { it.isNotEmpty() }, o.str("source") ?: "", o.str("kind") ?: "movie")
        }

        private fun strings(e: JsonElement?): List<String> = (e as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.contentOrNull }
    }
}
