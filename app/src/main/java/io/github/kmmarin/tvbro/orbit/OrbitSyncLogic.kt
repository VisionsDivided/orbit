// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.orbit

import io.github.kmmarin.tvbro.data.WatchState

/** A resume point as the home server keeps it (web format: seconds, time of the change in ms). */
data class ServerResume(val secs: Long, val dur: Long, val at: Long, val done: Boolean)

/** Pure rules for syncing My list and film resume points with the home server (unit tested). */
object OrbitSyncLogic {
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
