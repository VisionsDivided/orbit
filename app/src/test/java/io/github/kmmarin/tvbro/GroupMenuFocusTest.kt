// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import androidx.compose.runtime.remember
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kmmarin.tvbro.data.LiveGroups
import io.github.kmmarin.tvbro.ui.live.GroupMenuActions
import io.github.kmmarin.tvbro.ui.live.GroupMenuState
import io.github.kmmarin.tvbro.ui.live.GroupMenuView
import io.github.kmmarin.tvbro.ui.orbit5.OrbitCanvas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** D-pad in Live TV's side menu of groups (ui/live/GroupMenu.kt), on the JVM with Robolectric. */
@OptIn(ExperimentalTestApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class GroupMenuFocusTest {
    @get:Rule val rule = createComposeRule()

    private val groups = listOf("Ambiance | Channels", "Sports | NHL Teams", "Sports | NFL Replays", "Kids") + (1..200).map { "US | Network $it" }
    private val state = GroupMenuState()
    private val picked = mutableListOf<String>()

    private fun menu() {
        rule.setContent {
            val im = LocalInputModeManager.current
            remember { im.requestInputMode(InputMode.Keyboard) }
            OrbitCanvas {
                val nodes = remember { LiveGroups.fold(groups) }
                GroupMenuView(
                    state.query, { state.query = it }, LiveGroups.rows(nodes, state.expanded), { null }, emptySet(), "Kids", { it }, groups.size, state,
                    GroupMenuActions(
                        onFolder = { state.toggle(it) }, onGroup = { picked += it }, onChannel = {}, onPin = {}, onFocusGroup = { state.focusedGroup = it },
                        onCollapse = { f -> state.expanded = state.expanded - f; state.pendingFolder = f }, onClose = {},
                    ),
                    top = 120.dp,
                )
            }
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
    }

    private fun key(k: Key) { rule.onRoot().performKeyInput { pressKey(k) }; rule.waitForIdle() }

    @Test fun opensInTheSearchFieldThenDownRightOkPicks() {
        menu()
        rule.onNodeWithTag("group-menu-search").assertIsFocused()
        key(Key.DirectionDown)
        rule.onNodeWithTag("menu-f-Ambiance").assertIsFocused()
        key(Key.DirectionDown)
        rule.onNodeWithTag("menu-f-Sports").assertIsFocused()
        key(Key.DirectionRight)                                   // opens the folder, focus stays on it
        assertEquals(setOf("Sports"), state.expanded)
        rule.onNodeWithTag("menu-f-Sports").assertIsFocused()
        key(Key.DirectionDown)
        rule.onNodeWithTag("menu-g-Sports | NHL Teams").assertIsFocused()
        assertEquals("Sports | NHL Teams", state.focusedGroup)    // what Red would pin
        key(Key.DirectionCenter)
        assertEquals(listOf("Sports | NHL Teams"), picked)
    }

    @Test fun leftOnAGroupClosesItsFolderAndFocusIsKeptInThePanel() {
        menu()
        key(Key.DirectionDown); key(Key.DirectionDown); key(Key.DirectionRight); key(Key.DirectionDown)
        rule.onNodeWithTag("menu-g-Sports | NHL Teams").assertIsFocused()
        key(Key.DirectionLeft)
        rule.mainClock.advanceTimeBy(200); rule.waitForIdle()
        assertTrue(state.expanded.isEmpty())
        rule.onNodeWithTag("menu-f-Sports").assertIsFocused()
        key(Key.DirectionLeft)                                    // a closed folder: nothing, focus stays
        rule.onNodeWithTag("menu-f-Sports").assertIsFocused()
        key(Key.DirectionUp); key(Key.DirectionUp); key(Key.DirectionUp)   // past the search field: stays in the panel
        rule.onNodeWithTag("group-menu-search").assertIsFocused()
    }

    @Test fun twoHundredGroupsScrollLazily() {
        menu()
        key(Key.DirectionDown); key(Key.DirectionDown); key(Key.DirectionDown); key(Key.DirectionDown) // Ambiance, Sports, Kids, US
        rule.onNodeWithTag("menu-f-US").assertIsFocused()
        key(Key.DirectionRight)
        repeat(30) { key(Key.DirectionDown) }
        rule.onNodeWithTag("menu-g-US | Network 30").assertIsFocused()
    }
}
