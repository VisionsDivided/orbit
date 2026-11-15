// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.discover

import androidx.compose.runtime.Immutable
import io.github.kmmarin.tvbro.orbit.DiscoverApi
import io.github.kmmarin.tvbro.orbit.DiscoverRow
import io.github.kmmarin.tvbro.orbit.DiscoverSeason
import io.github.kmmarin.tvbro.orbit.DiscoverTitle
import io.github.kmmarin.tvbro.orbit.DiscoverTitlePage
import io.github.kmmarin.tvbro.orbit.DownloadInfo
import io.github.kmmarin.tvbro.orbit.TitleRequest

/**
 * A Discover row that loads Seerr's next page near its end (`/v1/discover/rows/{src}?page=`, docs/orbit/API-v1.md).
 * [src] is the server's list (trending, movies, tv, upcoming); [type] keeps only films ("movie") or shows ("tv") of a
 * mixed list, or null for all. [page] of [pages] are loaded.
 */
@Immutable
data class PagedRow(
    val id: String, val title: String, val src: String, val type: String?,
    val items: List<DiscoverTitle> = emptyList(), val page: Int = 0, val pages: Int = 1,
) {
    val hasMore: Boolean get() = page < pages
    fun toRow() = DiscoverRow(id, title, items, page, pages)
}

/** Pure Discover rules (paging, search, title-page wording), shared by the TV and phone layouts and tested in DiscoverLogicTest. */
object DiscoverLogic {
    /** A row loads its next page when focus is this many cards from its end. */
    const val NEAR_END = 6
    /** A filtered row (only the films or only the shows of a mixed list) loads pages until it has this many titles… */
    const val MIN_FILL = 14
    /** …asking for at most this many pages in one go. */
    const val MAX_FILL_PAGES = 4
    /** The hero follows a focused card once focus has stayed on it this long. */
    const val HERO_SETTLE_MS = 400L
    /** A typed search runs once the text has not changed for this long. */
    const val SEARCH_SETTLE_MS = 400L
    /** A title page asks again this often while its download runs. */
    const val TITLE_POLL_MS = 30_000L
    /** Search results load more when focus is within this many cards of the end. */
    const val SEARCH_NEAR_END = 16

    /** The rows Discover shows, in order: (id, title, server list, type filter). */
    val ROWS = listOf(
        PagedRow("trending-movies", "Trending movies", "trending", "movie"),
        PagedRow("trending-shows", "Trending shows", "trending", "tv"),
        PagedRow("upcoming-movies", "Upcoming movies", "upcoming", "movie"),
        PagedRow("upcoming-shows", "Upcoming shows", "upcoming", "tv"),
        PagedRow("popular-movies", "Popular movies", "movies", "movie"),
        PagedRow("popular-shows", "Popular shows", "tv", "tv"),
    )

    /** The rows filled with the first page of each server list (`/v1/discover/rows`). */
    fun initialRows(raw: List<DiscoverRow>): List<PagedRow> = ROWS.map { def ->
        val src = raw.firstOrNull { it.id == def.src }
        if (src == null) def.copy(page = 0, pages = 0) else addToRow(def.copy(page = src.page, pages = src.pages), src.items)
    }

    /** [row] with [items] added: only its type, each title once. */
    fun addToRow(row: PagedRow, items: List<DiscoverTitle>): PagedRow {
        val seen = row.items.mapTo(HashSet()) { it.key }
        val fresh = items.filter { (row.type == null || it.type == row.type) && seen.add(it.key) }
        return if (fresh.isEmpty()) row else row.copy(items = row.items + fresh)
    }

    /** [row] after its page [page] of [pages] arrived with [items]. */
    fun withPage(row: PagedRow, page: Int, pages: Int, items: List<DiscoverTitle>): PagedRow =
        addToRow(row, items).copy(page = maxOf(row.page, page), pages = pages)

    /** Whether a row should load its next page now: more pages exist and focus is near its end. */
    fun wantsMore(row: PagedRow, index: Int, near: Int = NEAR_END): Boolean = row.hasMore && index >= row.items.size - near

    /** Whether a filtered row should keep loading after a page added [added] titles (it stops at [MIN_FILL]). */
    fun keepFilling(row: PagedRow, added: Int): Boolean = row.hasMore && row.type != null && row.items.size < MIN_FILL && added < NEAR_END

    /** The rows a sub-menu tab shows (0 For you, 1 Movies, 2 Shows, 3 Your requests: none). */
    fun tabRows(rows: List<PagedRow>, tab: Int): List<PagedRow> = when (tab) {
        1 -> rows.filter { it.type != "tv" }
        2 -> rows.filter { it.type != "movie" }
        3 -> emptyList()
        else -> rows
    }.filter { it.items.isNotEmpty() }

    /** Search results with [more] added, each title once. */
    fun addResults(have: List<DiscoverTitle>, more: List<DiscoverTitle>): List<DiscoverTitle> {
        val seen = have.mapTo(HashSet()) { it.key }
        return have + more.filter { seen.add(it.key) }
    }

    // ---------------------------------------------------------------- title page

    /** The seasons that can still be asked for. */
    fun requestable(seasons: List<DiscoverSeason>): List<DiscoverSeason> = seasons.filter { it.status == "requestable" }

    /** Whether the title page offers Request: not in the catalogue, and a film nobody asked for or a show with open seasons. */
    fun canRequest(p: DiscoverTitlePage): Boolean = p.title.play == null && when {
        p.title.isShow && p.seasons.isNotEmpty() -> requestable(p.seasons).isNotEmpty()
        else -> DiscoverApi.badge(p.title) == null
    }

    /** "Request" or, for a show with some seasons already there or asked for, "Request more". */
    fun requestLabel(p: DiscoverTitlePage): String =
        if (p.title.isShow && p.seasons.any { it.status != "requestable" }) "Request more" else "Request"

    /** "all seasons" / "season 2" / "seasons 1, 3" for the chosen season numbers ([open]: how many can be asked for). */
    fun seasonsText(list: List<Int>, open: Int): String {
        val l = list.sorted()
        return when {
            l.size > 1 && l.size == open -> "all seasons"
            l.size == 1 -> "season ${l[0]}"
            else -> "seasons " + l.joinToString(", ")
        }
    }

    /** The season chooser's toggle: picking a closed season does nothing. */
    fun toggleSeason(pick: Set<Int>, n: Int, seasons: List<DiscoverSeason>): Set<Int> {
        if (requestable(seasons).none { it.season == n }) return pick
        return if (n in pick) pick - n else pick + n
    }

    /** "All seasons": every open season, or none when they were all picked. */
    fun toggleAll(pick: Set<Int>, seasons: List<DiscoverSeason>): Set<Int> {
        val open = requestable(seasons).map { it.season }.toSet()
        return if (open.isNotEmpty() && pick.containsAll(open)) emptySet() else open
    }

    /** "2 h 5 min", "48 min", or null. */
    fun runtimeText(min: Int?): String? = when {
        min == null || min <= 0 -> null
        min < 60 -> "$min min"
        min % 60 == 0 -> "${min / 60} h"
        else -> "${min / 60} h ${min % 60} min"
    }

    /** The meta line: year, runtime (films) or seasons and episodes (shows), up to three genres. Rating is drawn apart. */
    fun metaParts(p: DiscoverTitlePage): List<String> = listOfNotNull(
        p.title.year,
        if (p.title.isShow) p.seasonCount.takeIf { it > 0 }?.let { n -> "$n season${if (n == 1) "" else "s"}" + (if (p.episodeCount > 0) " · ${p.episodeCount} episodes" else "") }
        else runtimeText(p.runtime),
        p.genres.take(3).joinToString(" · ").ifEmpty { null },
    )

    private val REQ_WORD = mapOf("pending" to "waiting for approval", "approved" to "approved", "declined" to "declined", "failed" to "failed")

    /** "Requested by Sam on 1 Nov 2026 · season 2 · approved" for the open requests (completed ones left out), at most 3. */
    fun requestLines(list: List<TitleRequest>, locale: java.util.Locale = java.util.Locale.getDefault(), zone: java.util.TimeZone = java.util.TimeZone.getDefault()): List<String> =
        list.filter { it.status != "completed" }.take(3).map { r ->
            val day = dayText(r.created, locale, zone)
            buildString {
                append("Requested by ").append(r.by.ifBlank { "someone" })
                if (day != null) append(" on ").append(day)
                if (r.seasons.isNotEmpty()) append(" · ").append(if (r.seasons.size == 1) "season ${r.seasons[0]}" else "seasons " + r.seasons.joinToString(", "))
                (REQ_WORD[r.status] ?: r.status.ifBlank { null })?.let { append(" · ").append(it) }
            }
        }

    /** "14 Nov 2026" of an ISO time, or null. */
    fun dayText(iso: String, locale: java.util.Locale = java.util.Locale.getDefault(), zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String? {
        val ms = DiscoverApi.isoMs(iso) ?: return null
        return java.text.SimpleDateFormat("d MMM yyyy", locale).apply { timeZone = zone }.format(java.util.Date(ms))
    }

    /** The download bar's second line: "1.2 GB of 4.0 GB · 3 files". */
    fun downloadDetail(d: DownloadInfo): String = listOfNotNull(
        if (d.size > 0) "${bytes(d.size - d.sizeLeft)} of ${bytes(d.size)}" else null,
        if (d.items > 1) "${d.items} files" else null,
    ).joinToString(" · ")

    fun bytes(n: Long): String = if (n >= 1_000_000_000) String.format(java.util.Locale.ROOT, "%.1f GB", n / 1e9) else "${maxOf(1, Math.round(n / 1e6))} MB"

    /** Whether a title page keeps asking while it is open (a download runs or Seerr is processing it). */
    fun shouldPoll(t: DiscoverTitle): Boolean = t.download != null || t.status == "processing"
}
