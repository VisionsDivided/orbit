// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.orbit

import io.github.kmmarin.tvbro.data.SeriesRow
import io.github.kmmarin.tvbro.data.WatchState

/** A resume point as the home server keeps it (web format: seconds, time of the change in ms). */
data class ServerResume(val secs: Long, val dur: Long, val at: Long, val done: Boolean)

/** An episode mark as the home server keeps it (/v1/progress/shows). */
data class ServerEpisode(val seriesId: String, val epId: String, val secs: Long, val dur: Long, val done: Boolean, val at: Long)

/** The server's next episode of a show. */
data class ServerNext(val id: String, val season: Int, val num: Int, val title: String?)

/** A show summary as the home server keeps it. */
data class ServerShow(val id: String, val key: String, val lastEp: String?, val next: ServerNext?, val finished: Boolean,
                      val hidden: Boolean, val at: Long)

/** An episode change to send: the TV's episode key, the server's series and episode ids, and the state. */
data class EpisodePush(val key: String, val seriesKey: String, val seriesId: String, val epId: String, val state: WatchState)

/** Pure rules for syncing My list, film resume points and show progress with the home server (unit tested). */
object OrbitSyncLogic {
    // ---- ids: the TV says "s|p0|7" / "e|p0|101", the server says series "7" (first playlist) or "p1~7" ----
    fun seriesIdFromKey(key: String): String? {
        val p = key.split('|')
        if (p.size != 3 || p[0] != "s" || p[1].isEmpty() || p[2].isEmpty()) return null
        return if (p[1] == "p0") p[2] else "${p[1]}~${p[2]}"
    }

    fun seriesKeyFromId(id: String): String = if ('~' in id) "s|${id.substringBefore('~')}|${id.substringAfter('~')}" else "s|p0|$id"

    fun episodeKey(seriesId: String, epId: String): String = "e|${if ('~' in seriesId) seriesId.substringBefore('~') else "p0"}|$epId"

    private fun epIdOf(key: String): String? = key.split('|').takeIf { it.size == 3 && it[0] == "e" }?.get(2)

    data class EpisodePlan(val toLocal: Map<String, Pair<WatchState, String>>, val toServer: List<EpisodePush>)

    /** Episode marks, newest wins per episode (same 2 s rule as films). [seriesOf] gives an episode key's series key. */
    fun planEpisodes(local: Map<String, WatchState>, seriesOf: (String) -> String?, server: List<ServerEpisode>, minResumeMs: Long): EpisodePlan {
        val toLocal = HashMap<String, Pair<WatchState, String>>()
        val byKey = server.associateBy { episodeKey(it.seriesId, it.epId) }
        for ((k, s) in byKey) {
            val l = local[k]
            if (l == null || s.at > l.updated + 2000) {
                val st = if (s.done) WatchState(0, s.dur * 1000, true, s.at) else WatchState(s.secs * 1000, s.dur * 1000, false, s.at)
                toLocal[k] = st to seriesKeyFromId(s.seriesId)
            }
        }
        val toServer = ArrayList<EpisodePush>()
        for ((k, l) in local) {
            if (!k.startsWith("e|") || l.updated <= 0) continue
            if (!l.watched && l.posMs < minResumeMs) continue
            val s = byKey[k]
            if (s != null && l.updated <= s.at + 2000) continue
            val sk = seriesOf(k) ?: continue                    // without its series the server cannot place it
            val sid = seriesIdFromKey(sk) ?: continue
            val ep = epIdOf(k) ?: continue
            toServer += EpisodePush(k, sk, sid, ep, l)
        }
        return EpisodePlan(toLocal, toServer)
    }

    /** Show rows (Continue watching, next episode) from the server where it knows newer than the TV. */
    fun planShows(local: Map<String, SeriesRow>, server: List<ServerShow>): List<SeriesRow> = server.mapNotNull { s ->
        val l = local[s.key]
        if (l != null && s.at <= l.updated + 2000) return@mapNotNull null
        val n = s.next
        (l ?: SeriesRow(s.key)).copy(
            updated = s.at, lastEp = s.lastEp ?: l?.lastEp, nextEp = n?.id, nextSeason = n?.season ?: 0, nextNum = n?.num ?: 0,
            nextTitle = n?.title, finished = s.finished, active = !s.finished && !s.hidden && n != null,
        )
    }

    /**
     * Keys that sync: channels ("p0|123"), films ("m|p0|7") and shows ("s|p0|9"). Groups ("g|…"), recordings ("r|…")
     * and channel-VOD ("v|…") exist only on the TV.
     */
    fun syncable(key: String): Boolean = !(key.startsWith("g|") || key.startsWith("r|") || key.startsWith("v|"))

    data class ListMerge(val add: Set<String>, val remove: Set<String>, val result: Set<String>)

    /**
     * Three-way merge of My list. [base] is the synced list after the last sync, so changes made on either side
     * while the other was away are both kept: added on the TV -> added on the server, removed on the TV -> removed
     * there, and everything changed on the server comes to the TV. [add] / [remove] are what to send.
     */
    fun mergeList(local: Set<String>, base: Set<String>, server: Set<String>): ListMerge {
        val l = local.filter(::syncable).toSet()
        val added = l - base
        val removed = base - l
        val result = (server + added) - removed
        return ListMerge(add = added - server, remove = removed intersect server, result = result)
    }

    /** The TV's whole list after a merge: the synced part plus what only exists on the TV. */
    fun withLocalOnly(local: Set<String>, synced: Set<String>): Set<String> = synced + local.filterNot(::syncable)

    data class ResumePlan(val toLocal: Map<String, WatchState>, val toServer: Map<String, WatchState>)

    /** Films only ("m|"): episodes have their own show-progress API on the server. */
    fun resumeKey(key: String) = key.startsWith("m|")

    /** Newest wins, per film; differences under 2 s are the same change seen twice. */
    fun planResume(local: Map<String, WatchState>, server: Map<String, ServerResume>, minResumeMs: Long): ResumePlan {
        val toLocal = HashMap<String, WatchState>()
        val toServer = HashMap<String, WatchState>()
        for ((k, s) in server) {
            if (!resumeKey(k)) continue
            val l = local[k]
            if (l == null || s.at > l.updated + 2000) {
                toLocal[k] = if (s.done) WatchState(0, s.dur * 1000, true, s.at) else WatchState(s.secs * 1000, s.dur * 1000, false, s.at)
            }
        }
        for ((k, l) in local) {
            if (!resumeKey(k) || l.updated <= 0) continue
            if (!l.watched && l.posMs < minResumeMs) continue
            val s = server[k]
            if (s == null || l.updated > s.at + 2000) toServer[k] = l
        }
        return ResumePlan(toLocal, toServer)
    }
}
