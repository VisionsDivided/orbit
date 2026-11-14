// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.kmmarin.tvbro.data.Diag
import io.github.kmmarin.tvbro.data.VodItem
import io.github.kmmarin.tvbro.data.Watch
import io.github.kmmarin.tvbro.orbit.CatalogApi
import io.github.kmmarin.tvbro.orbit.CatalogCollection
import io.github.kmmarin.tvbro.orbit.CatalogFeed
import io.github.kmmarin.tvbro.orbit.CatalogItem
import io.github.kmmarin.tvbro.orbit.CatalogPage
import io.github.kmmarin.tvbro.orbit.OrbitApi
import io.github.kmmarin.tvbro.orbit.OrbitError
import io.github.kmmarin.tvbro.orbit.OrbitLink

/**
 * Pure rules of the desktop's use of the Orbit catalogue (unit tested): catalog items as the app's VOD items, captions,
 * the source toggle and the sorts of Browse (DESIGN-SPEC-5 §5).
 */
object CatalogMap {
    /** Browse's source toggle: All · Local (Plex / Jellyfin) · IPTV, as the API's `source` values. */
    val SOURCES = listOf("all" to "All", "local" to "Local", "iptv" to "IPTV")
    val SORTS = listOf("added" to "Recently added", "released" to "Recently released", "title" to "Title", "rating" to "Rating")
    /** Browse loads this many titles at a time (the API allows up to 200). */
    const val PAGE = 120

    /** A Plex / Jellyfin title (played through the home server's /v1/media, not the playlist). */
    fun isMedia(c: CatalogItem): Boolean = c.source == "plex" || c.source == "jellyfin"

    /**
     * [c] as the app's VOD item. An IPTV title the playlists already have is that item (it knows how to play); else one
     * made from the catalog: key, playlist (or media connection) and id come from the key ("m|p0|7", "s|<conn>|<id>").
     */
    fun toVod(c: CatalogItem, local: (String) -> VodItem?): VodItem {
        if (!isMedia(c)) local(c.key)?.let { return if (it.poster.isNullOrBlank()) it.copy(poster = c.poster) else it }
        val p = c.key.split('|', limit = 3)
        return VodItem(c.key, p.getOrElse(1) { c.conn ?: "" }, if (c.isShow) "series" else "movie", p.getOrElse(2) { c.id }, c.title, c.poster,
            c.genres.firstOrNull() ?: "", c.rating, "")
    }

    /** The line under a card: a show's next episode, else how much of a film is left. */
    fun caption(c: CatalogItem): String? {
        val n = c.next
        if (n != null) return if (n.season > 0) "S${n.season} E${n.num}" else "E${n.num}"
        val s = c.progressSecs; val d = c.progressDur
        if (s != null && d != null && d > 0 && s > 0) return "${((d - s).coerceAtLeast(0) / 60).coerceAtLeast(1)} min left"
        return null
    }

    /** "#c3261b" pair to the backdrop mesh (the base a dark m1, as [Palette.of] makes it); null when not two colours. */
    fun mesh(colors: List<String>?): Palette.Mesh? {
        val c = colors?.mapNotNull(::rgb) ?: return null
        if (c.size < 2) return null
        val m1 = c[0]
        fun ch(s: Int) = ((m1 shr s and 255) * .09f).toInt()
        return Palette.Mesh(m1, c[1], (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0))
    }

    private fun rgb(s: String): Int? {
        val h = s.trim().removePrefix("#")
        if (h.length != 6) return null
        return h.toIntOrNull(16)?.let { it or (0xFF shl 24) }
    }

    /** "1,284 titles". */
    fun count(n: Int): String = "%,d %s".format(java.util.Locale.ROOT, n, if (n == 1) "title" else "titles")
}

/** A row of cards as the screens draw it: the VOD items with their captions. [landscape]: Continue watching. */
data class CineRowData(val id: String, val title: String, val items: List<Pair<VodItem, String?>>, val landscape: Boolean = false)

/**
 * The Orbit catalogue for the desktop (docs/orbit/API-v1.md "Catalogue"): Home, Recommended, Browse and Collections
 * come from the home server while signed in. Signed out, or from a home server without the catalogue (404), every
 * screen uses the local IPTV catalogue as before; other errors also fall back for that screen.
 */
class DeskCatalog(private val app: AppState) {
    /** The catalog client (tests put one with a made-up server in). */
    internal var api = CatalogApi(OrbitApi(userAgent = "Orbit-Desktop"))

    /** The home server answered 404: it has no catalogue (an older server). Asked again after the next sign-in. */
    var missing by mutableStateOf(false)
    /** The last catalog item seen per key: its backdrop, colours, year and source for the hero and the title page. */
    val info = mutableStateMapOf<String, CatalogItem>()
    private var profile = ""

    /** The catalogue is asked (signed in, and the server has one). */
    val usable: Boolean get() = app.orbit.linked && !missing

    private fun keep(items: List<CatalogItem>) { for (i in items) info[i.key] = i }

    /** One call; null = use local data (signed out, 404, or the server not answering). */
    private suspend fun <T> call(what: String, f: (OrbitLink) -> T): T? {
        val pid = app.orbit.saved.profileId
        if (pid != profile) { profile = pid; missing = false }
        if (!usable) return null
        return try {
            app.orbit.call(f)
        } catch (e: OrbitError) {
            if (e.code == 404) { missing = true; Diag.log("Orbit: the home server has no catalogue; local titles") }
            else Diag.log("Orbit: catalog $what (${e.code})")
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { Diag.log("Orbit: catalog $what (${e.javaClass.simpleName})"); null }
    }

    suspend fun home(): CatalogFeed? = call("home") { api.home(it) }?.also { f -> keep(f.hero); f.rows.forEach { keep(it.items) } }
    suspend fun recommended(kind: String): CatalogFeed? = call("recommended") { api.recommended(it, kind) }?.also { f -> keep(f.hero); f.rows.forEach { keep(it.items) } }
    suspend fun browse(kind: String, source: String, genre: String, sort: String, offset: Int): CatalogPage? =
        call("browse") { api.browse(it, kind, source, genre, sort, offset, CatalogMap.PAGE) }?.also { keep(it.items) }
    suspend fun collections(kind: String): List<CatalogCollection>? = call("collections") { api.collections(it, kind) }
    suspend fun collection(id: String): Pair<CatalogCollection?, List<CatalogItem>>? = call("collection") { api.collection(it, id) }?.also { keep(it.second) }

    /** Catalog rows as card rows (Continue watching as landscape cards). */
    fun rows(f: CatalogFeed): List<CineRowData> = f.rows.map { r ->
        CineRowData(r.id, r.title, r.items.map { CatalogMap.toVod(it, app::findVod) to CatalogMap.caption(it) }, landscape = r.id == "continue")
    }

    fun vod(c: CatalogItem): VodItem = CatalogMap.toVod(c, app::findVod)

    /** Watched share of [v] for its card: the local resume point, else the catalogue's (Plex / Jellyfin keep their own). */
    fun progress(v: VodItem): Float? = app.watch.state(v.key)?.takeIf { it.inProgress }?.fraction ?: info[v.key]?.progress?.takeIf { it > 0f }

    /** Backdrop of [v]: the catalogue's, else the loaded detail's, else its poster. */
    fun art(v: VodItem): String? = info[v.key]?.backdrop ?: app.detailCache[v.key]?.backdrop ?: v.poster

    /** The left-over time of a film in words, for captions of local items. */
    fun left(posMs: Long, durMs: Long): String = if (durMs > 0) "${((durMs - posMs) / 60_000).coerceAtLeast(1)} min left" else Watch.clock(posMs)
}
