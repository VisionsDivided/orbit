// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.movies

import androidx.compose.runtime.Composable
import io.github.kmmarin.tvbro.AppModel
import io.github.kmmarin.tvbro.ui.BrowseScreen
import io.github.kmmarin.tvbro.ui.orbit5.O5Space
import io.github.kmmarin.tvbro.ui.shell.LegacyPage

/**
 * Movies and Shows (top bar; [AppModel.vodSeries] says which). Owned by the Movies / Shows / Detail follow-up, which
 * replaces this with the sub-menu (Recommended · Browse · Collections) and the direction-5 pages. For now tvBro's
 * browse screen (categories and titles) below the top bar, unchanged.
 */
@Composable
fun MoviesShowsSection(m: AppModel) {
    LegacyPage(O5Space.TopBar) { BrowseScreen(m) }
}
