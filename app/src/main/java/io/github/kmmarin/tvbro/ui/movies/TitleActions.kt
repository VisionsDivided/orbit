// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.movies

import androidx.lifecycle.viewModelScope
import io.github.kmmarin.tvbro.AppModel
import io.github.kmmarin.tvbro.Screen
import io.github.kmmarin.tvbro.data.VodItem
import io.github.kmmarin.tvbro.orbit.MediaApi
import io.github.kmmarin.tvbro.orbit.MediaEp
import io.github.kmmarin.tvbro.orbit.MediaInfo
import io.github.kmmarin.tvbro.orbit.MediaPlayback
import io.github.kmmarin.tvbro.orbit.MediaSeason
import io.github.kmmarin.tvbro.orbit.OrbitApi
import io.github.kmmarin.tvbro.ui.MenuState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * What OK and the colour keys do with a title on the Movies / Shows pages (and the title page). IPTV titles open
 * tvBro's own title page and play as before; Plex / Jellyfin titles open their title page and play through the home
 * server (orbit/MediaPlayback.kt).
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
    if (t.media || v0 == null) { openMediaTitle(m, t); if (play && t.media) playMediaTitle(m, t); return }
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

/** The title page of a Plex / Jellyfin title (details, seasons, episodes; Play / Resume through the home server). */
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
        if (ok) { m.movies.of(t.show).recAt = 0; MediaPlayback.rev++ }
    }
}

fun sourceName(s: String) = when (s) { "plex" -> "Plex"; "jellyfin" -> "Jellyfin"; "iptv" -> "IPTV"; else -> s.replaceFirstChar { it.uppercase() } }

/** The More menu of a title on the pages (blue key, long press): tvBro's title menu plus the category lock. */
fun titleMenu(m: AppModel, t: Title) {
    val v = t.vod
    if (t.media || v == null) {
        m.menu = MenuState(t.title, listOfNotNull(
            "Details" to { openMediaTitle(m, t) },
            if (t.media && (!t.show || t.next != null)) "Play" to { openMediaTitle(m, t); playMediaTitle(m, t) } else null,
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

// ---------------------------------------------------------------- Plex / Jellyfin details and playback

/** The title page's loads (shared/MediaApi.kt: `/v1/media/item`, `/v1/media/children`). Off the main thread. */
object MediaDetails {
    fun seasons(a: JsonArray?): List<MediaSeason> = MediaApi.seasons(a)
    fun episodes(a: JsonArray?, server: String): List<MediaEp> = MediaApi.episodes(a, server)

    fun load(m: AppModel, conn: String, id: String): MediaInfo? {
        val link = m.orbit.link ?: return null
        return MediaApi(OrbitApi()).item(link, conn, id)
    }

    fun children(m: AppModel, conn: String, id: String): JsonArray? {
        val link = m.orbit.link ?: return null
        return MediaApi(OrbitApi()).children(link, conn, id)
    }
}

/**
 * Play on a Plex / Jellyfin title (Home's hero, the pages' Play): a film from its resume point, a show's next episode
 * (the catalog's `next`); a show without one opens its page, where an episode is picked.
 */
fun playMediaTitle(m: AppModel, t: Title) {
    val conn = t.conn ?: t.vod?.sourceId ?: return
    if (!t.show) { MediaPlayback.play(m, conn, t.id, t.source, episode = false); return }
    val n = t.next ?: return
    MediaPlayback.play(m, conn, n.id, t.source, episode = true)
}
