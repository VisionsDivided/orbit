// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.orbit

import io.github.kmmarin.tvbro.data.Watch
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

// ---------------------------------------------------------------- title pages (/v1/media/item, /v1/media/children)

/** What a title page shows of a Plex / Jellyfin film or show (`/v1/media/item`). */
data class MediaInfo(
    val overview: String? = null, val year: String? = null, val runtimeSecs: Long? = null, val rating: String? = null,
    val genres: List<String> = emptyList(), val backdrop: String? = null, val progressSecs: Long? = null, val durSecs: Long? = null,
    val watched: Boolean = false,
)

/** An episode of a season (`/v1/media/children` of a season). */
data class MediaEp(val id: String, val season: Int, val num: Int, val title: String, val image: String?, val runtimeSecs: Long?,
                   val progressSecs: Long?, val watched: Boolean, val overview: String?) {
    /** "S1 E2" (no season: "E2"). */
    val code: String get() = if (season > 0) "S$season E$num" else "E$num"
    /** Stopped part-way (more than 5 s in, not watched, not at the end). */
    val inProgress: Boolean get() = !watched && MediaApi.startAt(progressSecs, runtimeSecs ?: 0, false) > 0
}

data class MediaSeason(val id: String, val num: Int, val title: String)

// ---------------------------------------------------------------- playback (/v1/media/play, /v1/media/progress)

data class MediaAudio(val id: String, val label: String, val lang: String?, val default: Boolean)
/** An external subtitle (WebVTT) of the play answer; [url] is absolute, on the home server. */
data class MediaSubtitle(val id: String, val label: String, val lang: String?, val url: String)
data class MediaNextUp(val id: String, val title: String)

/**
 * The answer of `POST /v1/media/play` (docs/orbit/API-v1.md "Media servers" ▸ "Playback"). [url] and the subtitle
 * addresses are absolute and always on the home server (the relay): loading them needs the session cookie
 * ([MediaApi.streamHeaders]) and never carries a Plex / Jellyfin token.
 */
data class MediaPlayback(
    val session: String, val url: String, val kind: String, val container: String? = null, val mime: String? = null,
    val durationSecs: Long = 0, val resumeSecs: Long? = null, val title: String = "", val subtitle: String? = null,
    val audio: List<MediaAudio> = emptyList(), val subtitles: List<MediaSubtitle> = emptyList(), val next: MediaNextUp? = null,
) {
    /** HLS (a transcode or remux by the media server); else the original file (direct play, with Range). */
    val isHls: Boolean get() = kind == "hls"
}

/** Why playing did not start, as the apps say it. */
enum class MediaProblemKind { SIGNED_OUT, RELINK, UNREACHABLE, NOT_PLAYABLE, OTHER }
data class MediaProblem(val kind: MediaProblemKind, val message: String)

/**
 * The /v1/media routes the TV and the desktop app use to show and play Plex / Jellyfin titles through the home server.
 * Every call throws [OrbitError] for an error answer (or an IOException when the server does not answer); call off the
 * main thread.
 */
class MediaApi(private val api: OrbitApi = OrbitApi()) {
    fun item(link: OrbitLink, conn: String, id: String): MediaInfo =
        info(api.getJson(link, "/v1/media/item?conn=${enc(conn)}&id=${enc(id)}"), link.server)

    /** The seasons of a show or the episodes of a season, as JSON (see [seasons], [episodes]). */
    fun children(link: OrbitLink, conn: String, id: String): JsonArray? =
        api.getJson(link, "/v1/media/children?conn=${enc(conn)}&id=${enc(id)}")["items"] as? JsonArray

    /**
     * Starts playing film or episode [id] of connection [conn] (`POST /v1/media/play`). [client] "tv" or "desktop" asks
     * for the original file when the server can (direct play), else HLS; [caps] tells the server what this device plays
     * ([HLS_ONLY]: never the original file, the fallback after a direct-play failure).
     */
    fun play(link: OrbitLink, conn: String, id: String, client: String, caps: JsonObject? = null): MediaPlayback {
        val body = buildMap<String, JsonElement> {
            put("conn", JsonPrimitive(conn)); put("id", JsonPrimitive(id)); put("client", JsonPrimitive(client))
            if (caps != null) put("caps", caps)
        }
        return parse(api.postJson(link, "/v1/media/play", JsonObject(body)), link.server)
    }

    /** `POST /v1/media/progress`: [state] [PLAYING], [PAUSED] or [STOPPED] (stopped ends the session on the server). */
    fun progress(link: OrbitLink, session: String, positionSecs: Long, state: String) {
        api.postJson(link, "/v1/media/progress", JsonObject(mapOf(
            "session" to JsonPrimitive(session), "positionSecs" to JsonPrimitive(positionSecs.coerceAtLeast(0)), "state" to JsonPrimitive(state))))
    }

    companion object {
        const val PLAYING = "playing"
        const val PAUSED = "paused"
        const val STOPPED = "stopped"
        /** Resume points this close to the start are not offered (as IPTV films: more than 5 s in). */
        const val MIN_RESUME_SECS = 5L
        /** `caps` for the HLS fallback: this device could not play the original file. */
        val HLS_ONLY: JsonObject = JsonObject(mapOf("directPlay" to JsonPrimitive(false)))

        private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
        private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.booleanOrNull ?: (it.contentOrNull == "true") } ?: false

        fun info(o: JsonObject, server: String): MediaInfo {
            val prog = o["progress"] as? JsonObject
            val r = (o["rating"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 }?.let { if (it == Math.floor(it)) it.toInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", it) }
            return MediaInfo(
                o.str("overview")?.takeIf { it.isNotBlank() }, o.str("year")?.takeIf { it.isNotBlank() && it != "0" }, o.num("runtime"), r,
                (o["genres"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                OrbitApi.absolute(server, o.str("backdrop")), prog?.num("secs"), prog?.num("dur"), prog?.bool("watched") ?: false,
            )
        }

        fun episodes(a: JsonArray?, server: String): List<MediaEp> = a.orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val prog = o["progress"] as? JsonObject
            MediaEp(o.str("id") ?: return@mapNotNull null, (o["season"] as? JsonPrimitive)?.intOrNull ?: 0, (o["episode"] as? JsonPrimitive)?.intOrNull ?: 0,
                o.str("title") ?: "", OrbitApi.absolute(server, o.str("backdrop") ?: o.str("poster")), o.num("runtime") ?: prog?.num("dur"), prog?.num("secs"),
                prog?.bool("watched") ?: false, o.str("overview"))
        }

        fun seasons(a: JsonArray?): List<MediaSeason> = a.orEmpty().mapIndexedNotNull { i, e ->
            val o = e as? JsonObject ?: return@mapIndexedNotNull null
            if (o.str("kind") != null && o.str("kind") != "season") return@mapIndexedNotNull null
            MediaSeason(o.str("id") ?: return@mapIndexedNotNull null, (o["season"] as? JsonPrimitive)?.intOrNull ?: (i + 1), o.str("title") ?: "Season ${i + 1}")
        }

        /**
         * [url] from the server made absolute; null unless it is on the home server itself (a relative path, or an
         * absolute address under [server]): the session cookie goes with these requests, so nothing else is loaded.
         */
        fun onServer(server: String, url: String?): String? = when {
            url.isNullOrBlank() || url.any { it.isISOControl() || it.isWhitespace() } -> null
            url.startsWith("/") && !url.startsWith("//") -> server + url
            url.startsWith("$server/") -> url
            else -> null
        }

        /** The play answer; [OrbitError] 502 when it lacks the session or a playable address on the home server. */
        fun parse(j: JsonObject, server: String): MediaPlayback {
            val session = j.str("session")?.takeIf { it.isNotBlank() } ?: throw OrbitError(502, "The home server sent no play session")
            val url = onServer(server, j.str("url")) ?: throw OrbitError(502, "The home server sent no address to play")
            val kind = j.str("kind") ?: if (url.substringBefore('?').endsWith(".m3u8")) "hls" else "file"
            val audio = (j["audio"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val id = o.str("id") ?: return@mapNotNull null
                MediaAudio(id, o.str("label") ?: o.str("lang") ?: "Track $id", o.str("lang"), o.bool("default"))
            }
            val subs = (j["subtitles"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val id = o.str("id") ?: return@mapNotNull null
                MediaSubtitle(id, o.str("label") ?: o.str("lang") ?: "Subtitles $id", o.str("lang"), onServer(server, o.str("url")) ?: return@mapNotNull null)
            }
            val next = (j["next"] as? JsonObject)?.let { n -> n.str("id")?.let { MediaNextUp(it, n.str("title") ?: "") } }
            return MediaPlayback(session, url, kind, j.str("container"), j.str("mime"), j.num("durationSecs") ?: 0, j.num("resumeSecs"),
                j.str("title") ?: "", j.str("subtitle")?.takeIf { it.isNotBlank() }, audio, subs, next)
        }

        /** The session cookie for a play / subtitle address on the home server; null for any other address. */
        fun streamHeaders(link: OrbitLink?, url: String): Map<String, String>? = OrbitApi.imageHeaders(link, url)

        /**
         * Where playing starts, in seconds: 0 when [fromStart], when the resume point is in the first 5 s, or when it is
         * where a play-through counts as watched (as IPTV films and episodes resume on the TV and the desktop).
         */
        fun startAt(resumeSecs: Long?, durationSecs: Long, fromStart: Boolean): Long {
            val r = resumeSecs ?: return 0
            if (fromStart || r <= MIN_RESUME_SECS) return 0
            if (durationSecs > 0 && Watch.countsAsWatched(r * 1000, durationSecs * 1000)) return 0
            return r
        }

        /** What the viewer is told when playing [what] from [source] ("Plex") failed with [e]. */
        fun problem(e: Throwable, source: String): MediaProblem {
            val src = source.ifBlank { "the media server" }
            if (e !is OrbitError) return MediaProblem(MediaProblemKind.UNREACHABLE, "The Orbit home server is not answering. Check that it is on, then try again.")
            val said = e.message?.takeIf { it.isNotBlank() && !it.startsWith("HTTP ") }
            return when (e.code) {
                401 -> MediaProblem(MediaProblemKind.SIGNED_OUT, "This device is no longer signed in to Orbit. Sign in again.")
                409 -> MediaProblem(MediaProblemKind.RELINK, said ?: "The sign-in to $src is no longer valid. Link $src again in the Orbit web app (Settings ▸ Media servers).")
                502, 503, 504 -> MediaProblem(MediaProblemKind.UNREACHABLE, said ?: "$src cannot be reached from the home server right now.")
                400, 404, 415, 422 -> MediaProblem(MediaProblemKind.NOT_PLAYABLE, said ?: "This title cannot be played from $src.")
                else -> MediaProblem(MediaProblemKind.OTHER, said ?: "Could not play this title (${e.code}).")
            }
        }
    }
}

/**
 * When to report progress (`/v1/media/progress`): every [intervalMs] while playing, at once when playing pauses or
 * goes on, and "stopped" once at the end. [tick] is called often (every second or so) with the player's state.
 */
class MediaProgressClock(private val intervalMs: Long = 10_000) {
    private var last: String? = null
    private var lastAt = 0L
    private var stopped = false

    /** The state to send now, or null. */
    fun tick(nowMs: Long, playing: Boolean): String? {
        if (stopped) return null
        val s = if (playing) MediaApi.PLAYING else MediaApi.PAUSED
        // nothing until it first plays (a paused first state is the player still opening)
        if (last == null && !playing) return null
        if (s != last || (playing && nowMs - lastAt >= intervalMs)) { last = s; lastAt = nowMs; return s }
        return null
    }

    /** "stopped" the first time (also before anything played: the server then ends the session and its transcode), then null. */
    fun stop(): String? {
        if (stopped) return null
        stopped = true
        return MediaApi.STOPPED
    }
}
