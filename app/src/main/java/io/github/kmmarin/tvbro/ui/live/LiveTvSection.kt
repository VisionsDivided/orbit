// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.live

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.kmmarin.tvbro.AppModel
import io.github.kmmarin.tvbro.Screen
import io.github.kmmarin.tvbro.Section
import io.github.kmmarin.tvbro.ui.MyListScreen
import io.github.kmmarin.tvbro.ui.RecordingsScreen
import io.github.kmmarin.tvbro.ui.TvSection
import io.github.kmmarin.tvbro.ui.orbit5.O5Space
import io.github.kmmarin.tvbro.ui.orbit5.SubMenuRow
import io.github.kmmarin.tvbro.ui.shell.LegacyPage

/**
 * Live TV (top bar). Owned by the Live TV / player follow-up: for now the sub-menu row over tvBro's own screens
 * (the TV screen with its preview and mini guide, the full guide, recordings, My list), unchanged inside.
 */
@Composable
fun LiveTvSection(m: AppModel) {
    val tabs = listOf("Channels", "Guide", "Recordings", "My list")
    val sel = when (m.section) { Section.RECORDINGS -> 2; Section.MYLIST -> 3; else -> 0 }
    Box(Modifier.fillMaxSize()) {
        LegacyPage(190.dp) {
            when (m.section) {
                Section.RECORDINGS -> RecordingsScreen(m)
                Section.MYLIST -> MyListScreen(m)
                else -> TvSection(m)
            }
        }
        SubMenuRow(tabs, sel, onSelect = { i ->
            when (i) {
                0 -> m.section = Section.TV
                1 -> m.screen = Screen.GUIDE
                2 -> m.section = Section.RECORDINGS
                else -> { m.refreshLists(); m.section = Section.MYLIST }
            }
        }, Modifier.padding(top = O5Space.SubMenuTop))
    }
}
