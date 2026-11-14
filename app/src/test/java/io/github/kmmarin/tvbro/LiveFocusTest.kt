// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import io.github.kmmarin.tvbro.data.Channel
import io.github.kmmarin.tvbro.data.Programme
import io.github.kmmarin.tvbro.ui.ChannelsLayout
import io.github.kmmarin.tvbro.ui.GuideLayout
import io.github.kmmarin.tvbro.ui.SafeFocus
import io.github.kmmarin.tvbro.ui.live.EpgActions
import io.github.kmmarin.tvbro.ui.live.EpgGrid
import io.github.kmmarin.tvbro.ui.live.EpgWindow
import io.github.kmmarin.tvbro.ui.live.GroupTabs
import io.github.kmmarin.tvbro.ui.live.LiveInfoBar
import io.github.kmmarin.tvbro.ui.live.LiveInfoData
import io.github.kmmarin.tvbro.ui.live.NowFocus
import io.github.kmmarin.tvbro.ui.live.RowMarks
import io.github.kmmarin.tvbro.ui.orbit5.LocalShellEntry
import io.github.kmmarin.tvbro.ui.orbit5.NavTab
import io.github.kmmarin.tvbro.ui.orbit5.OrbitCanvas
import io.github.kmmarin.tvbro.ui.orbit5.SubMenuRow
import io.github.kmmarin.tvbro.ui.shell.ShellFrame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * D-pad on Live TV ▸ Channels (owner on the BRAVIA, 2026-11-14): Up from the guide's top row reached the sub-menu
 * and skipped the group tabs. Up from the top row now lands on the selected tab, and Up from the tabs on the sub-menu.
 * The page is built from the same pieces and positions as TvSection.
 */
@OptIn(ExperimentalTestApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class LiveFocusTest {
    @get:Rule val rule = createComposeRule()

    private val now = System.currentTimeMillis() / 1000
    private val t0 = GuideLayout.initialStart(now)
    private val channels = (1..12).map { Channel("k$it", "s", "Channel $it", null, "News", "http://192.0.2.10/live/u/p/$it.ts", null, it, null, 0) }
    private fun progs(c: Channel) = arrayOf(Programme(c.key, t0 - 600, t0 + 3000, "Show ${c.number}", null), Programme(c.key, t0 + 3000, t0 + 7200, "Later", null))

    private fun page() {
        rule.setContent {
            val im = LocalInputModeManager.current
            remember { im.requestInputMode(InputMode.Keyboard) }
            OrbitCanvas {
                ShellFrame(active = NavTab.LIVE, onTab = {}, onSearch = {}, onAvatar = {}, profileName = "Dani") {
                    val l = ChannelsLayout
                    val selTab = remember { SafeFocus() }
                    val chFocus = remember { SafeFocus() }
                    val entry = LocalShellEntry.current
                    SubMenuRow(listOf("Channels", "Guide", "Recordings", "My list"), 0, {}, Modifier.padding(top = 118.dp))
                    LiveInfoBar(LiveInfoData("1 · Channel 1", null, "Show 1", live = true, progress = 0.2f, fav = false), onFav = {},
                        Modifier.padding(start = 96.dp, top = l.InfoTop, end = 96.dp + l.PreviewW + 40.dp))
                    GroupTabs(listOf("Favourites", "All channels", "Recent", "News", "MENU"), "News", { it }, selTab, rememberLazyListState(),
                        {}, {}, {}, { chFocus.request() }, Modifier.padding(top = l.TabsTop, end = 96.dp + l.PreviewW + 24.dp),
                        onUp = { entry?.targets()?.firstOrNull()?.request() ?: false })
                    EpgGrid(
                        channels, ::progs, now, remember { EpgWindow(now) }, rememberLazyListState(), { "k1" }, chFocus, remember { NowFocus() },
                        EpgActions({ _, _ -> }, { _, _ -> }, {}, { _, _ -> }, { selTab.request() }, { RowMarks() }, { _, _ -> false }),
                        Modifier.padding(start = 96.dp, end = 96.dp, top = l.GridTop, bottom = 92.dp).fillMaxSize(), l.metrics,
                    )
                    LaunchedEffect(Unit) { repeat(20) { if (chFocus.request()) return@LaunchedEffect; kotlinx.coroutines.delay(30) } }
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(800)
        rule.waitForIdle()
    }

    private fun key(k: Key) { rule.onRoot().performKeyInput { pressKey(k) }; rule.waitForIdle() }

    @Test fun upFromTheFirstChannelReachesTheGroupTabsThenTheSubMenu() {
        page()
        rule.onNodeWithTag("ch-k1").assertIsFocused()
        key(Key.DirectionUp)
        rule.onNodeWithTag("tab-News").assertIsFocused()
        key(Key.DirectionRight)                                   // along the tabs to "All groups"
        rule.onNodeWithTag("tab-MENU").assertIsFocused()
        key(Key.DirectionUp)
        rule.onNodeWithTag("subpill-Channels").assertIsFocused()
    }

    @Test fun upFromTheProgrammeOnNowInTheFirstRowReachesTheTabs() {
        page()
        key(Key.DirectionRight)                                   // the programme on now
        key(Key.DirectionUp)
        rule.onNodeWithTag("tab-News").assertIsFocused()
        key(Key.DirectionDown)
        rule.onNodeWithTag("ch-k1").assertIsFocused()
    }

    @Test fun upFromALowerRowStaysInTheGrid() {
        page()
        key(Key.DirectionDown); key(Key.DirectionDown)
        rule.onNodeWithTag("ch-k3").assertIsFocused()
        key(Key.DirectionUp)
        rule.onNodeWithTag("ch-k2").assertIsFocused()
    }
}
