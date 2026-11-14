// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import kotlinx.serialization.Serializable

/** A playlist source: either an M3U URL (+ optional XMLTV URL) or an Xtream login. */
@Serializable
data class Source(
    val id: String,
    val name: String,
    val kind: String, // "m3u" or "xtream"
    val url: String = "",
    val epgUrl: String = "",
    val server: String = "",
    val user: String = "",
    val pass: String = "",
    val enabled: Boolean = true,
) {
    val isXtream: Boolean get() = kind == "xtream"
}

/** Catch-up description. [type] is xc, default, append, shift, flussonic... */
data class Catchup(val type: String, val template: String?)

data class Channel(
    val key: String,
    val sourceId: String,
    val name: String,
    val logo: String?,
    val group: String,
    val url: String,
    val tvgId: String?,
    val number: Int,
    val catchup: Catchup?,
    val catchupDays: Int,
    val idKey: String? = tvgId?.takeIf { it.isNotBlank() }?.let { "$sourceId|${it.lowercase()}" },
    val nameKey: String = "$sourceId|${name.lowercase()}",
)

/** Times are epoch seconds. [key] is the EPG key (source id | lowercase channel id or name). */
data class Programme(val key: String, val start: Long, val stop: Long, val title: String, val desc: String?, val sub: String? = null, val ep: String? = null)

open class EpgIndex(private val map: Map<String, Array<Programme>> = emptyMap()) {
    private val none = emptyArray<Programme>()

    open fun programmes(ch: Channel): Array<Programme> =
        (ch.idKey?.let { map[it] }) ?: map[ch.nameKey] ?: none

    fun nowNext(ch: Channel, now: Long): Pair<Programme?, Programme?> {
        val a = programmes(ch)
        if (a.isEmpty()) return null to null
        var lo = 0
        var hi = a.size - 1
        var idx = -1 // last programme with start <= now
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid].start <= now) { idx = mid; lo = mid + 1 } else hi = mid - 1
        }
        val cur = if (idx >= 0 && a[idx].stop > now) a[idx] else null
        val next = a.getOrNull(idx + 1)
        return cur to next
    }

    companion object {
        val EMPTY = EpgIndex(emptyMap())

        /**
         * One channel's programmes as a timeline with no overlaps. Some providers send overlapping entries (10:00-14:00
         * and 11:00-14:00) and put fillers ("Next Game Today") or inner entries inside a live game; drawn as they were,
         * blocks covered each other, focus went to a hidden block and the guide window jumped.
         *
         * The rules (the same in the web app's guide.js normalizeProgs and the server's parsers.normalize_programmes):
         * 1. entries with stop <= start are dropped;
         * 2. sorted by start, then the longer first (stable: equal ones keep their input order);
         * 3. of entries starting at the same time only the first (the longest) is kept;
         * 4. an entry lying fully inside an earlier kept entry with the same title is dropped (duplicates included);
         * 5. timeline overlay: at any moment the entry shown is the kept one covering it with the latest start, so when
         *    an inner entry ends the enclosing one resumes (Block 1-5, Match 2-3 -> Block 1-2, Match 2-3, Block 3-5;
         *    A 1-3, B 2-4 -> A 1-2, B 2-4); adjacent pieces of one entry are merged;
         * 6. zero-length pieces are dropped.
         * A piece is a copy of its entry with other start / stop; an entry shown whole is returned as it was. O(n log n):
         * one sweep with a stack of open entries (the TV runs it for thousands of channels).
         */
        fun tidy(a: Array<Programme>): Array<Programme> {
            if (a.isEmpty()) return a
            if (a.size == 1) return if (a[0].stop > a[0].start) a else emptyArray()
            val sorted = a.filter { it.stop > it.start }
                .sortedWith(compareBy<Programme> { it.start }.thenByDescending { it.stop - it.start })
            // the latest end of each title kept so far: a later-starting entry ending no later is inside one of them
            val reach = HashMap<String, Long>()
            val kept = ArrayList<Programme>(sorted.size)
            for (p in sorted) {
                if (kept.isNotEmpty() && kept.last().start == p.start) continue  // same start: the longest came first
                val r = reach[p.title]
                if (r != null && r >= p.stop) continue                           // inside a longer one, same title
                reach[p.title] = if (r == null) p.stop else maxOf(r, p.stop)
                kept += p
            }
            // pieces: entry index, start, stop
            val pi = IntArray(kept.size * 2 + 1)
            val ps = LongArray(pi.size)
            val pe = LongArray(pi.size)
            var n = 0
            fun emit(i: Int, s: Long, e: Long) {
                if (e <= s) return
                if (n > 0 && pi[n - 1] == i && pe[n - 1] == s) { pe[n - 1] = e; return }
                pi[n] = i; ps[n] = s; pe[n] = e; n++
            }
            val stack = IntArray(kept.size) // open entries, later starts on top; ones already ended go lazily
            var top = 0
            var cur = 0L
            fun advance(t: Long) { // Long.MAX_VALUE: to the end
                while (top > 0) {
                    val i = stack[top - 1]
                    val p = kept[i]
                    if (p.stop <= cur) { top--; continue }
                    val end = minOf(p.stop, t)
                    emit(i, cur, end)
                    cur = end
                    if (p.stop > t) break
                    top--
                }
                if (t > cur) cur = t
            }
            for (i in kept.indices) {
                if (top == 0) cur = kept[i].start
                advance(kept[i].start)
                stack[top++] = i
            }
            advance(Long.MAX_VALUE)
            return Array(n) { k ->
                val p = kept[pi[k]]
                if (ps[k] == p.start && pe[k] == p.stop) p else p.copy(start = ps[k], stop = pe[k])
            }
        }
    }
}
