// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.orbit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A catalog title (the catalog API contract, docs/orbit/API-v1.md "Catalog"). [key] uses the TV's own key scheme
 * ("m|p0|123", "s|p0|7"), so My list and resume line up with the local state.
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
    /** Resume point in seconds, or null. */
    val progressSecs: Long? = null,
    val progressDur: Long? = null,
) {
    val isShow: Boolean get() = kind == "show" || kind == "series"
    val progress: Float? get() = if (progressSecs != null && progressDur != null && progressDur > 0) (progressSecs.toFloat() / progressDur).coerceIn(0f, 1f) else null
}

data class CatalogRow(val id: String, val title: String, val items: List<CatalogItem>)
data class CatalogRecommended(val hero: List<CatalogItem>, val rows: List<CatalogRow>)
data class CatalogPage(val total: Int, val genres: List<String>, val items: List<CatalogItem>)
data class CatalogCollection(val id: String, val name: String, val count: Int, val poster: String?, val backdrop: String?, val colors: List<String>?)

/**
 * The catalog routes for the TV (signed in, per profile). Every call throws [OrbitError] on an error answer;
 * [OrbitError.code] 404 means the home server has no catalog yet (an older server): fall back to local data.
 * Call off the main thread.
 */
class CatalogApi(private val api: OrbitApi = OrbitApi()) {
    fun home(link: OrbitLink): List<CatalogRow> = parseRows(api.getJson(link, "/v1/catalog/home"))

    /** [kind] "movies" or "shows". */
    fun recommended(link: OrbitLink, kind: String): CatalogRecommended {
        val j = api.getJson(link, "/v1/catalog/$kind/recommended")
        return CatalogRecommended(items(j["hero"]), parseRows(j))
    }

    /** [source] all | local | iptv; [sort] added | released | title | rating. */
    fun browse(link: OrbitLink, kind: String, source: String = "all", genre: String = "", sort: String = "added", offset: Int = 0, limit: Int = 120): CatalogPage {
        val q = "source=${enc(source)}&genre=${enc(genre)}&sort=${enc(sort)}&offset=$offset&limit=$limit"
        val j = api.getJson(link, "/v1/catalog/$kind/browse?$q")
        return CatalogPage((j["total"] as? JsonPrimitive)?.intOrNull ?: 0, strings(j["genres"]), items(j["items"]))
    }

    fun collections(link: OrbitLink, kind: String): List<CatalogCollection> =
        (api.getJson(link, "/v1/catalog/$kind/collections")["collections"] as? JsonArray).orEmpty().mapNotNull { collection(it) }

    fun collection(link: OrbitLink, id: String): Pair<CatalogCollection?, List<CatalogItem>> {
        val j = api.getJson(link, "/v1/catalog/collections/${enc(id)}")
        return collection(j["collection"]) to items(j["items"])
    }

    companion object {
        private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.contentOrNull

        fun parseRows(j: JsonObject): List<CatalogRow> = (j["rows"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            CatalogRow(o.str("id") ?: "", o.str("title") ?: "", items(o["items"])).takeIf { it.items.isNotEmpty() }
        }

        fun items(e: JsonElement?): List<CatalogItem> = (e as? JsonArray).orEmpty().mapNotNull { item(it) }

        fun item(e: JsonElement?): CatalogItem? {
            val o = e as? JsonObject ?: return null
            val key = o.str("key") ?: return null
            val title = o.str("title") ?: return null
            val prog = o["progress"] as? JsonObject
            val rating = (o["rating"] as? JsonPrimitive)?.let { p ->
                p.doubleOrNull?.let { if (it <= 0) null else if (it == Math.floor(it)) it.toInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", it) }
                    ?: p.contentOrNull?.takeIf { it.isNotBlank() && p !is kotlinx.serialization.json.JsonNull }
            }
            return CatalogItem(
                key = key, kind = o.str("kind") ?: if (key.startsWith("s|")) "show" else "movie", title = title,
                year = (o["year"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" && it != "0" },
                poster = o.str("poster")?.takeIf { it.isNotBlank() }, backdrop = o.str("backdrop")?.takeIf { it.isNotBlank() },
                rating = rating, genres = strings(o["genres"]), source = o.str("source") ?: "iptv",
                added = (o["added"] as? JsonPrimitive)?.longOrNull ?: 0L, released = o.str("released"),
                colors = (o["colors"] as? JsonArray)?.let { strings(it) }?.takeIf { it.isNotEmpty() },
                progressSecs = (prog?.get("secs") as? JsonPrimitive)?.longOrNull ?: (prog?.get("secs") as? JsonPrimitive)?.doubleOrNull?.toLong(),
                progressDur = (prog?.get("dur") as? JsonPrimitive)?.longOrNull ?: (prog?.get("dur") as? JsonPrimitive)?.doubleOrNull?.toLong(),
            )
        }

        private fun collection(e: JsonElement?): CatalogCollection? {
            val o = e as? JsonObject ?: return null
            return CatalogCollection(o.str("id") ?: return null, o.str("name") ?: "", (o["count"] as? JsonPrimitive)?.intOrNull ?: 0,
                o.str("poster"), o.str("backdrop"), (o["colors"] as? JsonArray)?.let { strings(it) }?.takeIf { it.isNotEmpty() })
        }

        private fun strings(e: JsonElement?): List<String> = (e as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }
}
