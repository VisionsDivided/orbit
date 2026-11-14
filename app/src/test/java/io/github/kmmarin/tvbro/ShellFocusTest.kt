// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kmmarin.tvbro.ui.orbit5.NavTab
import io.github.kmmarin.tvbro.ui.orbit5.OrbitCanvas
import io.github.kmmarin.tvbro.ui.orbit5.o5Focus
import io.github.kmmarin.tvbro.ui.shell.ShellFrame
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** D-pad focus between the top bar and a section (ui/shell/OrbitShell.kt ShellFrame), on the JVM with Robolectric. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class ShellFocusTest {
    @get:Rule val rule = createComposeRule()

    private var opened by mutableStateOf<NavTab?>(null)

    private fun shell(active: NavTab = NavTab.MOVIES) {
        rule.setContent {
            OrbitCanvas {
                ShellFrame(active = active, onTab = { opened = it }, onSearch = {}, onAvatar = {}, profileName = "Dani") {
                    Column(Modifier.padding(start = 96.dp, top = 300.dp), verticalArrangement = Arrangement.spacedBy(60.dp)) {
                        for (r in 0..1) Row(horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                            for (i in 0..5) Box(Modifier.size(180.dp, 120.dp).o5Focus { }.testTag("card-$r-$i"))
                        }
                    }
                }
            }
        }
    }

    private fun key(k: Key) { rule.onRoot().performKeyInput { pressKey(k) }; rule.waitForIdle() }

    @Test fun upFromTheSectionLandsOnTheCurrentTabNotTheNearest() {
        shell(NavTab.MOVIES)
        rule.onNodeWithTag("card-0-5").requestFocus()   // far right, under the clock, not under "Movies"
        rule.waitForIdle()
        key(Key.DirectionUp)
        rule.onNodeWithTag("nav-Movies").assertIsFocused()
    }

    @Test fun downFromTheBarReturnsToTheLastItem() {
        shell(NavTab.LIVE)
        rule.onNodeWithTag("card-1-3").requestFocus()
        rule.waitForIdle()
        key(Key.DirectionUp)            // row 0
        key(Key.DirectionUp)            // the bar
        rule.onNodeWithTag("nav-Live TV").assertIsFocused()
        key(Key.DirectionLeft)          // along the bar
        rule.onNodeWithTag("nav-Shows").assertIsFocused()
        key(Key.DirectionDown)
        rule.onNodeWithTag("card-0-3").assertIsFocused()
    }

    @Test fun okOnATabOpensIt() {
        shell(NavTab.HOME)
        rule.onNodeWithTag("card-0-0").requestFocus()
        rule.waitForIdle()
        key(Key.DirectionUp)
        rule.onNodeWithTag("nav-Home").assertIsFocused()
        key(Key.DirectionRight)
        key(Key.DirectionCenter)
        assertEquals(NavTab.MOVIES, opened)
    }
}
