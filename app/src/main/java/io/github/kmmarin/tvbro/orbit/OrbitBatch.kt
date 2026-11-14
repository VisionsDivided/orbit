// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.orbit

import io.github.kmmarin.tvbro.data.WatchState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * What the TV syncs, as the server had it: My list keys, film resume points, episode marks and show summaries.
 * [rev] is the server's revision of it (/v1/sync/state), null when it came from an older server's routes.
 */
data class SyncSnapshot(
    val rev: String?, val mylist: Set<String>, val resume: Map<String, ServerResume>,
    val episodes: List<ServerEpisode>, val shows: List<ServerShow>,
)

/**
 * The changes one sync sends, queued and sent together (/v1/sync/batch): state changes (My list, film resume
 * points) and episode changes. Older servers get them one request each.
 */
class Outbox {
    val ops = ArrayList<JsonObject>()
    val episodes = ArrayList<JsonObject>()
    val isEmpty get() = ops.isEmpty() && episodes.isEmpty()

    fun put(scope: String, key: String, value: JsonElement) {
        ops += JsonObject(mapOf("op" to JsonPrimitive("put"), "scope" to JsonPrimitive(scope), "key" to JsonPrimitive(key), "value" to value))
    }

    fun del(scope: String, key: String) {
        ops += JsonObject(mapOf("op" to JsonPrimitive("del"), "scope" to JsonPrimitive(scope), "key" to JsonPrimitive(key)))
    }
}

/** Pure helpers for the sync wire format (unit tested). */
object OrbitBatch {
    /** State changes per request: request bodies are capped at 1 MB and a My list entry is well under 2 KB. */
    const val MAX_OPS = 200
    /** Episode changes per request (the server takes 500). */
    const val MAX_EPISODES = 500

    data class Chunk(val ops: List<JsonObject>, val episodes: List<JsonObject>)

    /** The outbox split into requests: one for a normal sync, more only for a very large first sync. */
    fun chunks(ops: List<JsonObject>, episodes: List<JsonObject>): List<Chunk> {
        val a = ops.chunked(MAX_OPS)
        val b = episodes.chunked(MAX_EPISODES)
        return (0 until maxOf(a.size, b.size)).map { i -> Chunk(a.getOrElse(i) { emptyList() }, b.getOrElse(i) { emptyList() }) }
    }

    fun body(c: Chunk): JsonObject = JsonObject(mapOf("state" to JsonArray(c.ops), "episodes" to JsonArray(c.episodes)))

    /** A film resume point in the web format (seconds; the change's time in ms), with what the web app shows. */
    fun resumeValue(s: WatchState, meta: JsonObject?): JsonObject = JsonObject(buildMap {
        put("secs", JsonPrimitive(if (s.watched) s.durMs / 1000 else s.posMs / 1000)); put("dur", JsonPrimitive(s.durMs / 1000))
        put("at", JsonPrimitive(s.updated)); if (s.watched) put("done", JsonPrimitive(true))
        if (meta != null) put("meta", JsonObject(mapOf("title" to (meta["name"] ?: JsonPrimitive("")), "poster" to (meta["poster"] ?: JsonPrimitive("")),
            "kind" to JsonPrimitive("movie"), "id" to (meta["id"] ?: JsonPrimitive("")))))
    })

    /** An episode change for /v1/progress/episodes and /v1/sync/batch. */
    fun episodeItem(p: EpisodePush, meta: JsonObject?): JsonObject = JsonObject(buildMap {
        val s = p.state
        put("seriesId", JsonPrimitive(p.seriesId)); put("epId", JsonPrimitive(p.epId))
        put("secs", JsonPrimitive(if (s.watched) s.durMs / 1000 else s.posMs / 1000)); put("dur", JsonPrimitive(s.durMs / 1000))
        put("done", JsonPrimitive(s.watched)); put("at", JsonPrimitive(s.updated))
        meta?.get("name")?.let { put("name", it) }; meta?.get("poster")?.let { put("poster", it) }
    })

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull ?: 0L
    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false

    /** My list keys of a GET /api/state answer. */
    fun listKeys(st: JsonObject): Set<String> = (st["mylist"] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonObject)?.str("key") }.toSet()

    /** Resume points of a GET /api/state answer (entries without a time are skipped). */
    fun resume(st: JsonObject): Map<String, ServerResume> = (st["resume"] as? JsonObject).orEmpty().mapNotNull { (k, v) ->
        val o = v as? JsonObject ?: return@mapNotNull null
        val at = (o["at"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
        k to ServerResume(o.long("secs"), o.long("dur"), at, o.bool("done"))
    }.toMap()

    /** Episode marks and show summaries of a GET /v1/progress/shows answer. */
    fun shows(j: JsonObject): Pair<List<ServerEpisode>, List<ServerShow>> {
        val eps = (j["episodes"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            ServerEpisode(o.str("seriesId") ?: return@mapNotNull null, o.str("epId") ?: return@mapNotNull null,
                o.long("secs"), o.long("dur"), o.bool("done"), o.long("at"))
        }
        val shows = (j["shows"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val n = (o["next"] as? JsonObject)?.let { x ->
                ServerNext(x.str("id") ?: return@let null, (x["season"] as? JsonPrimitive)?.intOrNull ?: 0,
                    (x["num"] as? JsonPrimitive)?.intOrNull ?: 0, x.str("title"))
            }
            ServerShow(o.str("id") ?: return@mapNotNull null, o.str("key") ?: return@mapNotNull null, o.str("lastEp"), n,
                o.bool("finished"), o.bool("hidden"), o.long("at"))
        }
        return eps to shows
    }

    fun snapshot(rev: String?, state: JsonObject, progress: JsonObject): SyncSnapshot {
        val (eps, shows) = shows(progress)
        return SyncSnapshot(rev, listKeys(state), resume(state), eps, shows)
    }

    /**
     * A /v1/sync/state answer: the new snapshot, or [cached] when the server says nothing changed since its rev.
     * Null when the answer cannot be used (unchanged but nothing cached, or a different rev): fetch in full.
     */
    fun fromSyncAnswer(j: JsonObject, cached: SyncSnapshot?): SyncSnapshot? {
        val rev = j.str("rev") ?: return null
        if ((j["changed"] as? JsonPrimitive)?.booleanOrNull == false) return cached?.takeIf { it.rev == rev }
        val st = j["state"] as? JsonObject ?: return null
        val pr = j["progress"] as? JsonObject ?: return null
        return snapshot(rev, st, pr)
    }
}
