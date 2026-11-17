// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.kmmarin.tvbro.data.Diag
import io.github.kmmarin.tvbro.data.VodItem
import io.github.kmmarin.tvbro.data.Watch
import io.github.kmmarin.tvbro.orbit.CatalogItem
import io.github.kmmarin.tvbro.orbit.MediaApi
import io.github.kmmarin.tvbro.orbit.MediaEp
import io.github.kmmarin.tvbro.orbit.MediaInfo
import io.github.kmmarin.tvbro.orbit.MediaPlayAnswer
import io.github.kmmarin.tvbro.orbit.MediaSeason
import io.github.kmmarin.tvbro.orbit.OrbitApi
import io.github.kmmarin.tvbro.orbit.OrbitError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A Plex / Jellyfin film or episode playing through the home server (PlayReq.media). [item]: the title page's item
 * (My list in the player); [hlsOnly]: the HLS fallback after the original file did not play.
 */
data class DeskMedia(val conn: String, val id: String, val source: String, val episode: Boolean, val answer: MediaPlayAnswer,
                     val item: VodItem? = null, val hlsOnly: Boolean = false)

/**
 * Plays Plex / Jellyfin titles in mpv through the home server (`POST /v1/media/play`, docs/orbit/API-v1.md "Playback"):
 * the original file when the server can, else HLS; the relay needs this app's session cookie and nothing else, so no
 * Plex / Jellyfin token reaches the computer. No IPTV stream slot is taken. Progress goes back every 10 s and on
 * pause / stop (`/v1/media/progress`), so the media server keeps the resume point and the watched mark.
 */
class DeskMediaPlay(private val app: AppState) {
    /** The client (tests put one with a made-up server in). */
    internal var api = MediaApi(OrbitApi(userAgent = "Orbit-Desktop"))

    /** A play request is on its way. */
    var starting by mutableStateOf(false); private set
    /** Why the last play did not start (under the title page's buttons); "" = nothing to say. */
    var message by mutableStateOf("")
    /** Bumped when a media title stops playing: the title page loads its marks and resume points again. */
    var rev by mutableStateOf(0); private set

    /**
     * Plays film / episode [id] of connection [conn] from [source] ("plex"). [fromStart] ignores the resume point;
     * [hlsOnly] asks for HLS and [atSecs] starts there (the fallback goes on where it stopped).
     */
    fun play(conn: String, id: String, source: String, episode: Boolean, item: VodItem? = null, fromStart: Boolean = false,
             hlsOnly: Boolean = false, atSecs: Long? = null) {
        if (starting) return
        if (!app.orbit.linked) return app.toast("Sign in to Orbit to play titles from ${name(source)}")
        starting = true; message = ""
        app.io {
            val (p, err) = try {
                (app.orbit.call { api.play(it, conn, id, "desktop", if (hlsOnly) MediaApi.HLS_ONLY else null, api.speedKbps(it)) } to null)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { null to e }
            app.runOnUi {
                starting = false
                when {
                    p != null -> {
                        val start = atSecs ?: MediaApi.startAt(p.resumeSecs, p.durationSecs, fromStart)
                        val title = listOfNotNull(p.title.ifBlank { null }, p.subtitle).joinToString(" · ").ifEmpty { item?.name ?: name(source) }
                        app.setPlaying(PlayReq(p.url, title, startSec = start, vod = item, media = DeskMedia(conn, id, source, episode, p, item, hlsOnly)))
                        app.full = true
                    }
                    else -> {
                        // null without an error: signed out (the server ended this app's session)
                        val pr = if (err == null) MediaApi.problem(OrbitError(401, ""), name(source)) else MediaApi.problem(err, name(source))
                        Diag.log("media play: ${pr.kind} (${(err as? OrbitError)?.code ?: err?.javaClass?.simpleName ?: "signed out"})")
                        message = pr.message
                        app.toast(pr.message)
                    }
                }
            }
        }
    }

    /** Play on a title page / hero / menu: a film from where it stopped, a show its next episode (the catalog's). */
    fun playTitle(v: VodItem, c: CatalogItem?, fromStart: Boolean = false) {
        val conn = c?.conn ?: v.sourceId
        val src = c?.source ?: "plex"
        if (v.kind != "series") return play(conn, c?.id?.ifEmpty { null } ?: v.id, src, false, v, fromStart)
        val n = c?.next ?: run { app.detail = v; return }
        play(conn, n.id, src, true, v, fromStart)
    }

    /** Reports [state] of [m] at [positionSecs] (off the UI thread; a failed report is only logged). */
    fun report(m: DeskMedia, positionSecs: Long, state: String) {
        app.io {
            try { app.orbit.call { api.progress(it, m.answer.session, positionSecs, state) } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { Diag.log("media progress: ${(e as? OrbitError)?.code ?: e.javaClass.simpleName}") }
        }
        if (state == MediaApi.STOPPED) app.runOnUi { rev++ }
    }

    // ---- the title page's loads ----
    suspend fun info(conn: String, id: String): MediaInfo? = app.orbit.call { api.item(it, conn, id) }
    suspend fun seasons(conn: String, id: String): List<MediaSeason> = app.orbit.call { MediaApi.seasons(api.children(it, conn, id)) }.orEmpty()
    suspend fun episodes(conn: String, seasonId: String): List<MediaEp> = app.orbit.call { MediaApi.episodes(api.children(it, conn, seasonId), it.server) }.orEmpty()

    companion object {
        fun name(source: String) = when (source) { "plex" -> "Plex"; "jellyfin" -> "Jellyfin"; else -> "the media server" }

        /** The mpv header that carries the session cookie (only for addresses on the home server; else none). */
        fun headerFields(link: io.github.kmmarin.tvbro.orbit.OrbitLink?, url: String): String =
            MediaApi.streamHeaders(link, url)?.entries?.filter { (k, v) -> (k + v).none { it == ',' || it == '\n' || it == '\r' } }
                ?.joinToString(",") { (k, v) -> "$k: $v" } ?: ""
    }
}

/** The catalog's share watched (no seconds known yet): part-way, not at the credits. */
private fun resumable(p: Float?) = p != null && p > 0.002f && p < 0.9f

/**
 * The title page of a Plex / Jellyfin film or show (direction 5 "Title detail", like an IPTV title's): Play / Resume
 * and From the start, My list, the status line; a show's seasons as tabs and the episodes as landscape cards.
 */
@Composable
fun MediaDetailScreen(app: AppState, v: VodItem, c: CatalogItem) {
    val mp = app.media
    val series = v.kind == "series"
    val conn = c.conn ?: v.sourceId
    val id = c.id.ifEmpty { v.id }
    val rev = mp.rev
    var info by remember(v) { mutableStateOf<MediaInfo?>(null) }
    var err by remember(v) { mutableStateOf<String?>(null) }
    LaunchedEffect(v, rev) {
        try { info = withContext(Dispatchers.IO) { mp.info(conn, id) } } catch (e: CancellationException) { throw e } catch (e: Exception) { err = MediaApi.problem(e, DeskMediaPlay.name(c.source)).message }
    }
    var seasons by remember(v) { mutableStateOf<List<MediaSeason>>(emptyList()) }
    LaunchedEffect(v) { if (series) seasons = try { mp.seasons(conn, id) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() } }
    val next = c.next
    var season by remember(v, seasons.size) { mutableStateOf(seasons.indexOfFirst { it.num == next?.season }.coerceAtLeast(0)) }
    val seasonId = seasons.getOrNull(season)?.id
    var eps by remember(v) { mutableStateOf<List<MediaEp>>(emptyList()) }
    LaunchedEffect(seasonId, rev) { eps = if (seasonId == null) emptyList() else try { mp.episodes(conn, seasonId) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() } }
    val first = remember { FocusRequester() }
    LaunchedEffect(v) { first.settle() }
    LaunchedEffect(v, app.inList(v.key)) {
        app.colorKeys = ColorKeys(ColorKey(if (app.inList(v.key)) "Remove from My list" else "My list") { app.toggleList(v.key) }, null, null,
            ColorKey("More") { app.vodMenu(v) }, listOf("Enter" to "Play", "Esc" to "Back"))
    }
    val i = info
    // a show's Play: its next episode (the catalog's), else the first one of this season stopped part-way or not watched
    val nextEp = eps.firstOrNull { it.id == next?.id }
    val target: Triple<String, String, Boolean>? = when {
        !series -> null
        next != null && nextEp?.watched != true -> Triple(next.id, if (next.season > 0) "S${next.season} E${next.num}" else "E${next.num}", nextEp?.inProgress ?: resumable(c.progress))
        else -> (eps.firstOrNull { it.inProgress } ?: eps.firstOrNull { !it.watched } ?: eps.firstOrNull())?.let { Triple(it.id, it.code, it.inProgress) }
    }
    val filmResume = !series && if (i != null) !i.watched && MediaApi.startAt(i.progressSecs, i.durSecs ?: i.runtimeSecs ?: 0, false) > 0 else resumable(c.progress)
    Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 30.dp)) {
        Eyebrow(app, v, if (series) "Series" else "Film")
        Spacer(Modifier.height(8.dp))
        Text2(v.name, 64, Color.White, FontWeight.ExtraBold, Modifier.widthIn(max = 820.dp), maxLines = 2)
        Spacer(Modifier.height(8.dp))
        MetaLine(app, v, null, extra = if (series) seasons.size.takeIf { it > 0 }?.let { "$it Season" + if (it > 1) "s" else "" }
            else i?.runtimeSecs?.takeIf { it > 0 }?.let { io.github.kmmarin.tvbro.data.VodFmt.fmt(it) })
        i?.overview?.let { Spacer(Modifier.height(10.dp)); Text2(it, 15, Color(0xDDF4F6FF), modifier = Modifier.widthIn(max = 680.dp), maxLines = 2) }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                mp.starting -> OButton("Starting…", primary = true, requester = first) {}
                !series -> {
                    OButton(if (filmResume) "▶  Resume" + (i?.progressSecs?.let { " " + Watch.clock(it * 1000) } ?: "") else "▶  Play", primary = true, requester = first) {
                        mp.play(conn, id, c.source, false, v)
                    }
                    if (filmResume) OButton("↺  From the start") { mp.play(conn, id, c.source, false, v, fromStart = true) }
                }
                target != null -> {
                    OButton("▶  " + (if (target.third) "Resume " else "Play ") + target.second, primary = true, requester = first) { mp.play(conn, target.first, c.source, true, v) }
                    if (target.third) OButton("↺  From the start") { mp.play(conn, target.first, c.source, true, v, fromStart = true) }
                }
                else -> OButton("Loading…", primary = true, requester = first) {}
            }
            OButton(if (app.inList(v.key)) "✓  My list" else "+  My list") { app.toggleList(v.key) }
        }
        val status = listOfNotNull(
            if (filmResume) i?.progressSecs?.let { p -> (i.durSecs ?: i.runtimeSecs)?.takeIf { it > p }?.let { app.catalog.left(p * 1000, it * 1000) } } else null,
            target?.let { t -> "Next " + t.second + (nextEp?.title ?: next?.title?.takeIf { t.first == next.id })?.let { " · $it" }.orEmpty() },
            if (app.inList(v.key)) "My list ✓" else null, if (i?.watched == true) "Watched" else null,
        ).joinToString(" · ")
        if (status.isNotEmpty()) Text2(status, 13, O5.muted, modifier = Modifier.padding(top = 10.dp).widthIn(max = 900.dp))
        (mp.message.ifEmpty { null } ?: err)?.let { Text2(it, 13, O5.live, modifier = Modifier.padding(top = 6.dp).widthIn(max = 900.dp), maxLines = 2) }
        if (series && seasons.isNotEmpty()) {
            Spacer(Modifier.weight(1f))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 6.dp)) {
                items(seasons.size) { s ->
                    SeasonTab(seasons[s].title, s == season, onMenu = {}) { season = s }
                }
            }
            LazyRow(Modifier.padding(bottom = 56.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(top = 10.dp, bottom = 6.dp, end = 56.dp)) {
                items(eps, key = { it.id }) { e ->
                    val left = e.progressSecs?.let { p -> e.runtimeSecs?.takeIf { it > p }?.let { app.catalog.left(p * 1000, it * 1000) } }
                    val cap = when {
                        e.inProgress && left != null -> left
                        e.watched -> "Watched"
                        else -> (if (target?.first == e.id) "Next · " else "") + (e.runtimeSecs?.let { "${it / 60} min" } ?: "")
                    }
                    LandCard("E${e.num} · ${e.title}", e.image ?: i?.backdrop ?: v.poster, 220.dp, cap.ifBlank { null },
                        if (e.inProgress) e.progressSecs?.let { p -> e.runtimeSecs?.takeIf { it > 0 }?.let { p.toFloat() / it } } else null, e.watched,
                        onMenu = {
                            app.menu = MenuState("${e.code} · ${e.title}", listOf(
                                (if (e.inProgress) "Resume" else "Play") to { mp.play(conn, e.id, c.source, true, v) },
                                "Play from start" to { mp.play(conn, e.id, c.source, true, v, fromStart = true) },
                            ))
                        }) { mp.play(conn, e.id, c.source, true, v) }
                }
            }
        }
    }
}
