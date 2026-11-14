// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Live TV's channel groups in the side menu (owner feedback 2026-11-14: 229 groups, finding hockey took minutes).
 * Groups named "Sports | NHL Teams" fold into a folder "Sports" holding "NHL Teams"; a group without " | " stays at the
 * top level. The web app mirrors these rules in JS (web/frontend), so both show the same folders.
 */
object LiveGroups {
    const val SEP = " | "

    /** A group in the menu: its full name and what the row shows (the part after the folder prefix). */
    data class Group(val name: String, val label: String)

    sealed class Node {
        /** A group with no folder prefix. */
        data class Single(val group: Group) : Node()
        /** Groups sharing the text before " | " ([name]), in their original order. */
        data class Folder(val name: String, val groups: List<Group>) : Node()
    }

    /** One row of the side menu, in order. */
    sealed class Row {
        data class FolderRow(val name: String, val count: Int, val expanded: Boolean) : Row()
        /** [inFolder]: drawn indented, under its folder. */
        data class GroupRow(val group: Group, val inFolder: Boolean) : Row()
        data class Heading(val text: String) : Row()
        /** A channel found by the search (the index into the list given to [search]). */
        data class ChannelRow(val index: Int) : Row()
    }

    /** "Sports | NHL Teams" -> ("Sports", "NHL Teams"); null when there is no prefix (or an empty one on either side). */
    fun split(group: String): Pair<String, String>? {
        val i = group.indexOf(SEP)
        if (i <= 0) return null
        val head = group.substring(0, i).trim()
        val tail = group.substring(i + SEP.length).trim()
        return if (head.isEmpty() || tail.isEmpty()) null else head to tail
    }

    /**
     * The groups as folders and single groups. Order follows [groups]: a folder sits where its first group was, and
     * its groups keep their order. Every prefix makes a folder, even one holding a single group (the same everywhere).
     */
    fun fold(groups: List<String>): List<Node> {
        val out = ArrayList<Any>()                       // Group (single) or the folder's name
        val folders = LinkedHashMap<String, MutableList<Group>>()
        val seen = HashSet<String>()
        for (g in groups) {
            if (!seen.add(g)) continue
            val s = split(g)
            if (s == null) { out += Group(g, g); continue }
            val list = folders[s.first]
            if (list == null) { folders[s.first] = mutableListOf(Group(g, s.second)); out += FolderKey(s.first) }
            else list += Group(g, s.second)
        }
        return out.map { if (it is FolderKey) Node.Folder(it.name, folders.getValue(it.name)) else Node.Single(it as Group) }
    }

    private data class FolderKey(val name: String)

    /** The menu's rows with [expanded] folders open (no search). */
    fun rows(nodes: List<Node>, expanded: Set<String>): List<Row> {
        val out = ArrayList<Row>(nodes.size)
        for (n in nodes) when (n) {
            is Node.Single -> out += Row.GroupRow(n.group, false)
            is Node.Folder -> {
                val open = n.name in expanded
                out += Row.FolderRow(n.name, n.groups.size, open)
                if (open) n.groups.mapTo(out) { Row.GroupRow(it, true) }
            }
        }
        return out
    }

    /** Lower-cased, trimmed search text; blank = no search. */
    fun norm(q: String): String = q.trim().lowercase()

    /**
     * Search results for [q] (any case): the groups whose full name (or [label], a renamed group's name) contains it,
     * then the channels whose name contains it, at most [maxChannels]. Groups show their full name. Runs in a few
     * milliseconds on 15,800 channels; the TV still calls it off the main thread.
     */
    fun search(q: String, groups: List<String>, channelNames: List<String>, label: (String) -> String = { it }, maxChannels: Int = 300): List<Row> {
        val n = norm(q)
        if (n.isEmpty()) return emptyList()
        val g = groups.distinct().filter { it.lowercase().contains(n) || label(it).lowercase().contains(n) }
        val out = ArrayList<Row>()
        if (g.isNotEmpty()) {
            out += Row.Heading("Groups")
            g.mapTo(out) { Row.GroupRow(Group(it, label(it)), false) }
        }
        val ch = ArrayList<Row>()
        for (i in channelNames.indices) {
            if (channelNames[i].lowercase().contains(n)) { ch += Row.ChannelRow(i); if (ch.size >= maxChannels) break }
        }
        if (ch.isNotEmpty()) { out += Row.Heading("Channels"); out += ch }
        return out
    }

    /**
     * The quick-select tabs over the guide: Favourites, All, Recent, the pinned groups that still exist (at most
     * [GroupPins.MAX]), the [current] group when it is none of those (chosen from the side menu, so the selected tab
     * shows), then the side menu's tab [allGroups].
     */
    fun quickTabs(fixed: List<String>, pinned: List<String>, available: Collection<String>, current: String, allGroups: String): List<String> {
        val have = available as? Set<String> ?: available.toHashSet()
        val pins = pinned.filter { it in have && it !in fixed }.distinct().take(GroupPins.MAX)
        val extra = if (current in fixed || current in pins || current !in have) emptyList() else listOf(current)
        return fixed + pins + extra + allGroups
    }
}

/**
 * The groups a profile pinned as quick-select tabs: full group names, most recent first, at most [MAX]. Stored in the
 * profile's server prefs under [PREF_KEY] as a JSON array, shared by the web app and the TV.
 *
 * The array carries no time stamp, so the TV keeps a "changed here since the last sync" flag ([merge]).
 */
object GroupPins {
    const val PREF_KEY = "liveGroupsPinned"
    const val MAX = 8

    /** Pins or unpins [group]: pinning puts it first; the oldest pin goes past [MAX]. */
    fun toggle(pins: List<String>, group: String): List<String> =
        if (group in pins) pins - group else (listOf(group) + pins).take(MAX)

    fun fromJson(e: JsonElement?): List<String>? {
        val a = e as? JsonArray ?: return null
        return a.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
            .filter { it.isNotEmpty() && it.length <= 300 }.distinct().take(MAX)
    }

    fun toJson(pins: List<String>): JsonArray = JsonArray(pins.take(MAX).map { JsonPrimitive(it) })

    /** Sync: the list to keep, and whether to send it to the server. */
    data class Merge(val result: List<String>, val toServer: Boolean)

    /**
     * [dirty]: the TV changed its pins since the last sync, so its copy wins and goes to the server (unless the server
     * already has the same list). Otherwise the server's copy wins; no copy on the server keeps the TV's.
     */
    fun merge(local: List<String>, dirty: Boolean, server: List<String>?): Merge = when {
        dirty -> Merge(local, server != local)
        server == null -> Merge(local, false)
        else -> Merge(server, false)
    }
}
