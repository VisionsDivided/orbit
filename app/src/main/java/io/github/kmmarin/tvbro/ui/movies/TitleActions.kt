// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.movies

import androidx.lifecycle.viewModelScope
import io.github.kmmarin.tvbro.AppModel
import io.github.kmmarin.tvbro.Screen
import io.github.kmmarin.tvbro.data.VodItem
import io.github.kmmarin.tvbro.orbit.OrbitApi
import io.github.kmmarin.tvbro.ui.MenuState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/*
 * What OK and the colour keys do with a title on the Movies / Shows pages (and the title page). IPTV titles open
 * tvBro's own title page and play as before; Plex / Jellyfin titles open the title page without playback (not built
 * on the TV yet).
 */

/**
 * Opens [t]'s title page, or with [play] starts a film straight away when its file type is known. A catalog IPTV
 * title is first looked up in the TV's own catalogue (its real category, so the parental lock applies, and its file
 * type); one not found there but marked locked asks for the PIN.
 */
fun openTitle(m: AppModel, t: Title, play: Boolean = false) {
    val feed = m.movies
    feed.remember(t)
    val v0 = t.vod
    if (t.media || v0 == null) { openMediaTitle(m, t); return }
    m.viewModelScope.launch {
        val local = if (m.isDirect(v0)) null else feed.resolveLocal(t.key)
        val v = local ?: v0
        val go = {
            if (play && !t.show && !m.isDirect(v) && v.ext.isNotEmpty()) {
                if (m.detail?.key != v.key) m.detailInfo = null    // playItem prefers the open page's file type
                m.playItem(v)
            } else m.openDetail(v)
        }
        if (local == null && t.locked && !m.isLocked(v) && m.prefs.pinHash.isNotEmpty()) m.askPin(go) else go()
    }
}

/** The title page of a Plex / Jellyfin title (details only; no playback on the TV yet). */
fun openMediaTitle(m: AppModel, t: Title) {
    m.movies.remember(t)
    val v = t.vod ?: VodItem(t.key, t.conn ?: "", if (t.show) "series" else "movie", t.id, t.title, t.poster, "", t.rating, "")
    m.detail = v; m.detailInfo = null; m.detailMsg = ""
    m.screen = Screen.DETAIL
}

fun inMyList(m: AppModel, t: Title): Boolean = t.key in m.favourites

fun toggleMyList(m: AppModel, t: Title) {
    val v = t.vod ?: return
    m.toggleFavKey(v)
}

/** Watched for the colour key and the More menu: films and episodes by key, shows as a whole. */
fun isWatched(m: AppModel, t: Title): Boolean =
    if (t.show) m.watch.seriesRow(t.key)?.finished == true else m.isWatched(t.key)

fun toggleWatched(m: AppModel, t: Title) {
    val now = !isWatched(m, t)
    if (t.media) { mediaWatched(m, t, now); return }
    val v = t.vod ?: return
    if (t.show) m.markSeries(v, now)
    else { m.markWatched(t.key, now); m.refreshLists(); m.toast(if (now) "Marked as watched" else "Marked as unwatched") }
}

/** `POST /v1/media/watched`: Plex / Jellyfin keep their own watched marks. */
fun mediaWatched(m: AppModel, t: Title, watched: Boolean) {
    val link = m.orbit.link ?: return
    val conn = t.conn ?: t.vod?.sourceId ?: return
    m.viewModelScope.launch {
        val ok = try {
            withContext(Dispatchers.IO) {
                OrbitApi().postJson(link, "/v1/media/watched", JsonObject(mapOf("conn" to JsonPrimitive(conn), "id" to JsonPrimitive(t.id), "watched" to JsonPrimitive(watched))))
            }; true
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { false }
        m.toast(if (!ok) "Could not reach ${sourceName(t.source)}" else if (watched) "Marked as watched" else "Marked as unwatched")
        if (ok) m.movies.of(t.show).recAt = 0
    }
}

fun sourceName(s: String) = when (s) { "plex" -> "Plex"; "jellyfin" -> "Jellyfin"; "iptv" -> "IPTV"; else -> s.replaceFirstChar { it.uppercase() } }

/** The More menu of a title on the pages (blue key, long press): tvBro's title menu plus the category lock. */
fun titleMenu(m: AppModel, t: Title) {
    val v = t.vod
    if (t.media || v == null) {
        m.menu = MenuState(t.title, listOfNotNull(
            "Details" to { openMediaTitle(m, t) },
            v?.let { (if (inMyList(m, t)) "Remove from My list" else "Add to My list") to { toggleMyList(m, t) } },
            (if (isWatched(m, t)) "Mark as unwatched" else "Mark as watched") to { toggleWatched(m, t) },
        ))
        return
    }
    val items = ArrayList<Pair<String, () -> Unit>>()
    items += (if (t.show) "Open" else "Details") to { openTitle(m, t) }
    if (!t.show && !m.isDirect(v)) items += "Play" to { openTitle(m, t, play = true) }
    items += (if (inMyList(m, t)) "Remove from My list" else "Add to My list") to { toggleMyList(m, t) }
    if (t.show) {
        items += "Mark show as watched" to { m.markSeries(v, true) }
        items += "Mark show as unwatched" to { m.markSeries(v, false) }
    } else {
        val w = m.isWatched(t.key)
        items += (if (w) "Mark as unwatched" else "Mark as watched") to { m.markWatched(t.key, !w); m.refreshLists() }
        if (!m.isDirect(v)) items += "Download" to {
            // catalog titles carry no file type: use the TV's own entry when it has one
            m.viewModelScope.launch { m.downloadMovie(m.movies.resolveLocal(v.key) ?: v) }
        }
    }
    if (v.category.isNotBlank() && v.category in m.vodCatNames) {
        val c = v.category
        items += (if (c in m.prefs.locked) "Unlock category $c" else "Lock category $c (PIN)") to { m.toggleLock(c) }
    }
    m.menu = MenuState(t.title, items)
}

// ---------------------------------------------------------------- Plex / Jellyfin details

/** What the title page shows of a Plex / Jellyfin title (`/v1/media/item`, `/v1/media/children`). */
data class MediaInfo(
    val overview: String? = null, val year: String? = null, val runtimeSecs: Long? = null, val rating: String? = null,
    val genres: List<String> = emptyList(), val backdrop: String? = null, val progressSecs: Long? = null, val durSecs: Long? = null,
    val watched: Boolean = false,
)

data class MediaEp(val id: String, val season: Int, val num: Int, val title: String, val image: String?, val runtimeSecs: Long?,
                   val progressSecs: Long?, val watched: Boolean, val overview: String?)

data class MediaSeason(val id: String, val num: Int, val title: String)

object MediaDetails {
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    fun info(o: JsonObject, server: String): MediaInfo {
        val prog = o["progress"] as? JsonObject
        val r = (o["rating"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 }?.let { if (it == Math.floor(it)) it.toInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", it) }
        return MediaInfo(
            o.str("overview")?.takeIf { it.isNotBlank() }, o.str("year")?.takeIf { it.isNotBlank() && it != "0" }, o.num("runtime"), r,
            (o["genres"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            OrbitApi.absolute(server, o.str("backdrop")), prog?.num("secs"), prog?.num("dur"),
            (prog?.get("watched") as? JsonPrimitive)?.contentOrNull == "true",
        )
    }

    fun episodes(a: JsonArray?, server: String): List<MediaEp> = a.orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val prog = o["progress"] as? JsonObject
        MediaEp(o.str("id") ?: return@mapNotNull null, (o["season"] as? JsonPrimitive)?.intOrNull ?: 0, (o["episode"] as? JsonPrimitive)?.intOrNull ?: 0,
            o.str("title") ?: "", OrbitApi.absolute(server, o.str("backdrop") ?: o.str("poster")), o.num("runtime"), prog?.num("secs"),
            (prog?.get("watched") as? JsonPrimitive)?.contentOrNull == "true", o.str("overview"))
    }

    fun seasons(a: JsonArray?): List<MediaSeason> = a.orEmpty().mapIndexedNotNull { i, e ->
        val o = e as? JsonObject ?: return@mapIndexedNotNull null
        if (o.str("kind") != null && o.str("kind") != "season") return@mapIndexedNotNull null
        MediaSeason(o.str("id") ?: return@mapIndexedNotNull null, (o["season"] as? JsonPrimitive)?.intOrNull ?: (i + 1), o.str("title") ?: "Season ${i + 1}")
    }

    /** Off the main thread. */
    fun load(m: AppModel, conn: String, id: String): MediaInfo? {
        val link = m.orbit.link ?: return null
        return info(OrbitApi().getJson(link, "/v1/media/item?conn=${enc(conn)}&id=${enc(id)}"), link.server)
    }

    fun children(m: AppModel, conn: String, id: String): JsonArray? {
        val link = m.orbit.link ?: return null
        return OrbitApi().getJson(link, "/v1/media/children?conn=${enc(conn)}&id=${enc(id)}")["items"] as? JsonArray
    }
}
