// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import io.github.kmmarin.tvbro.data.LiveGroups.Node
import io.github.kmmarin.tvbro.data.LiveGroups.Row
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveGroupsTest {
    /** About 200 groups like the owner's provider (fictional names): folders by prefix, a few plain groups. */
    private val groups: List<String> = buildList {
        add("Ambiance | Channels"); add("Sports | NHL Teams"); add("Kids"); add("UK | News")
        for (i in 1..23) add("Sports | League $i")
        add("Sports | NFL Replays")
        for (i in 1..17) add("UK | Regional $i")
        for (i in 1..31) add("US | Network $i")
        for (c in listOf("FR", "DE", "ES", "IT", "NL", "PT", "PL", "SE", "NO", "DK")) for (i in 1..12) add("$c | Channels $i")
        add("Music"); add("24/7 Movies")
    }

    @Test fun realisticGroupsFoldByPrefix() {
        assertTrue(groups.size in 190..240)
        val nodes = LiveGroups.fold(groups)
        val sports = nodes.filterIsInstance<Node.Folder>().first { it.name == "Sports" }
        assertEquals(25, sports.groups.size)
        assertEquals(LiveGroups.Group("Sports | NHL Teams", "NHL Teams"), sports.groups.first())
        assertEquals("NFL Replays", sports.groups.last().label)
        assertEquals(18, nodes.filterIsInstance<Node.Folder>().first { it.name == "UK" }.groups.size)
        assertEquals(31, nodes.filterIsInstance<Node.Folder>().first { it.name == "US" }.groups.size)
        // plain groups stay at the top level; order follows the provider's (a folder sits where its first group was)
        assertEquals(listOf("Ambiance", "Sports", "Kids", "UK", "US"), nodes.take(5).map { (it as? Node.Folder)?.name ?: (it as Node.Single).group.name })
        assertEquals(listOf("Music", "24/7 Movies"), nodes.takeLast(2).map { (it as Node.Single).group.name })
        // every group appears exactly once
        assertEquals(groups.size, nodes.sumOf { if (it is Node.Folder) it.groups.size else 1 })
    }

    @Test fun splitRules() {
        assertEquals("Sports" to "NHL Teams", LiveGroups.split("Sports | NHL Teams"))
        assertEquals("UK" to "Sports | HD", LiveGroups.split("UK | Sports | HD"))   // the first " | " only
        assertNull(LiveGroups.split("Sports|NHL"))                                   // no spaces: not a prefix
        assertNull(LiveGroups.split(" | Orphan"))
        assertNull(LiveGroups.split("Trailing | "))
        assertNull(LiveGroups.split("News"))
    }

    @Test fun singleGroupPrefixIsStillAFolder() {
        val n = LiveGroups.fold(listOf("Ambiance | Channels", "News"))
        assertEquals(Node.Folder("Ambiance", listOf(LiveGroups.Group("Ambiance | Channels", "Channels"))), n[0])
    }

    @Test fun rowsOpenOnlyExpandedFolders() {
        val nodes = LiveGroups.fold(listOf("Sports | NHL Teams", "Sports | NFL Replays", "Kids", "UK | News"))
        assertEquals(listOf(Row.FolderRow("Sports", 2, false), Row.GroupRow(LiveGroups.Group("Kids", "Kids"), false), Row.FolderRow("UK", 1, false)),
            LiveGroups.rows(nodes, emptySet()))
        val open = LiveGroups.rows(nodes, setOf("Sports"))
        assertEquals(5, open.size)
        assertEquals(Row.FolderRow("Sports", 2, true), open[0])
        assertEquals(Row.GroupRow(LiveGroups.Group("Sports | NHL Teams", "NHL Teams"), true), open[1])
    }

    @Test fun searchFindsGroupsAndChannels() {
        val gs = groups + "Sports | NHL Hockey Extra"
        val names = listOf("Arcadia Hockey Night", "Vista Cinema", "NHL Hockey 1", "Harbor Kids")
        val r = LiveGroups.search("  HOCKEY ", gs, names)
        assertEquals(Row.Heading("Groups"), r[0])
        assertEquals("Sports | NHL Hockey Extra", (r[1] as Row.GroupRow).group.label)
        assertEquals(Row.Heading("Channels"), r[2])
        assertEquals(listOf(0, 2), r.drop(3).map { (it as Row.ChannelRow).index })
        assertTrue(LiveGroups.search(" ", gs, names).isEmpty())
        // renamed groups match their new name too
        assertEquals(1, LiveGroups.search("puck", listOf("Sports | NHL Teams"), emptyList(), label = { "Puck" }).count { it is Row.GroupRow })
    }

    @Test fun searchCapsChannels() {
        val names = List(15_800) { "Channel $it" }
        val t = System.nanoTime()
        val r = LiveGroups.search("channel", emptyList(), names, maxChannels = 300)
        assertEquals(301, r.size)                                                    // heading + 300
        assertTrue("search took too long", System.nanoTime() - t < 2_000_000_000L)
    }

    @Test fun quickTabs() {
        val fixed = listOf("Favourites", "All channels", "Recent")
        val have = setOf("Sports | NHL Teams", "UK | News", "Kids", "Music")
        // gone groups drop out; the chosen group shows after the pins; the menu tab is last
        assertEquals(listOf("Favourites", "All channels", "Recent", "Sports | NHL Teams", "Kids", "UK | News", "ALL"),
            LiveGroups.quickTabs(fixed, listOf("Sports | NHL Teams", "Gone", "Kids"), have, "UK | News", "ALL"))
        assertEquals(listOf("Favourites", "All channels", "Recent", "Kids", "ALL"),
            LiveGroups.quickTabs(fixed, listOf("Kids"), have, "Kids", "ALL"))
        assertEquals(fixed + "ALL", LiveGroups.quickTabs(fixed, emptyList(), have, "All channels", "ALL"))
    }
}

class GroupPinsTest {
    @Test fun toggleKeepsMostRecentFirstAndAtMostEight() {
        var p = emptyList<String>()
        for (i in 1..10) p = GroupPins.toggle(p, "G$i")
        assertEquals(listOf("G10", "G9", "G8", "G7", "G6", "G5", "G4", "G3"), p)
        p = GroupPins.toggle(p, "G7")
        assertFalse("G7" in p)
        assertEquals(7, p.size)
    }

    @Test fun jsonRoundTripAndBadValues() {
        val p = listOf("Sports | NHL Teams", "UK | News")
        assertEquals("[\"Sports | NHL Teams\",\"UK | News\"]", GroupPins.toJson(p).toString())
        assertEquals(p, GroupPins.fromJson(GroupPins.toJson(p)))
        assertEquals(listOf("A", "B"), GroupPins.fromJson(Json.parseToJsonElement("[\"A\", 3, \"\", \"A\", \"B\", null]")))
        assertNull(GroupPins.fromJson(Json.parseToJsonElement("{\"a\":1}")))
        assertNull(GroupPins.fromJson(null))
        assertEquals(8, GroupPins.fromJson(Json.parseToJsonElement((1..12).joinToString(",", "[", "]") { "\"g$it\"" }))!!.size)
    }

    @Test fun mergeLocalChangesWinThenTheServer() {
        val a = listOf("A"); val b = listOf("B", "A")
        assertEquals(GroupPins.Merge(b, true), GroupPins.merge(b, dirty = true, server = a))      // pinned here: send
        assertEquals(GroupPins.Merge(b, false), GroupPins.merge(b, dirty = true, server = b))     // already there
        assertEquals(GroupPins.Merge(b, true), GroupPins.merge(b, dirty = true, server = null))   // first pin ever
        assertEquals(GroupPins.Merge(b, false), GroupPins.merge(a, dirty = false, server = b))    // pinned on the web
        assertEquals(GroupPins.Merge(emptyList(), false), GroupPins.merge(a, dirty = false, server = emptyList()))
        assertEquals(GroupPins.Merge(a, false), GroupPins.merge(a, dirty = false, server = null))
    }
}
