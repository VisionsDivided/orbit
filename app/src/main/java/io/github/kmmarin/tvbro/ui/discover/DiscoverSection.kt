// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.discover

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.kmmarin.tvbro.AppModel
import io.github.kmmarin.tvbro.ui.orbit5.CinematicBackdrop
import io.github.kmmarin.tvbro.ui.orbit5.O5Space
import io.github.kmmarin.tvbro.ui.orbit5.O5Type

/**
 * Discover (top bar): trending titles and requests through Seerr (server: /v1/discover, /v1/requests). Owned by the
 * Discover / Settings / phone follow-up; a placeholder until then.
 */
@Composable
fun DiscoverSection(m: AppModel) {
    Box(Modifier.fillMaxSize()) {
        CinematicBackdrop(null, Modifier.fillMaxSize())
        Column(Modifier.padding(start = O5Space.Side, top = O5Space.HeroTopHome).widthIn(max = 1100.dp)) {
            Text("Discover", style = O5Type.SectionHeroTitle)
            Text(
                "Trending films and shows, and requests for what is not here yet, come to the TV in the next update. " +
                    "Until then, request titles in Orbit on a phone or computer.",
                style = O5Type.Synopsis, modifier = Modifier.padding(top = 28.dp),
            )
        }
    }
}
