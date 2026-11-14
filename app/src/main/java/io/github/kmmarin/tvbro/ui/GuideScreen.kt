// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.kmmarin.tvbro.*
import io.github.kmmarin.tvbro.ui.live.LiveBackdrop
import io.github.kmmarin.tvbro.ui.live.LiveColorKeys
import io.github.kmmarin.tvbro.ui.orbit5.ColorKeyLegend
import io.github.kmmarin.tvbro.ui.orbit5.OrbitCanvas
import io.github.kmmarin.tvbro.ui.orbit5.SubPill

/** Where Back from the full guide goes: the Live TV page when it was opened from there, else full screen. */
object GuideReturn { var toList = false }

/**
 * The full-screen guide (direction 5, mockup #s5 "Live TV guide"): the focused programme and what plays at the top,
 * the group tabs, then more rows of the same guide as Live TV ▸ Channels. OK plays the channel full screen, or a
 * finished programme as catch-up; long press (Menu) opens the channel or programme menu. Colour keys: Favourite,
 * Record, Format TS / HLS, Channel options.
 */
@Composable
fun GuideScreen(m: AppModel) {
    BackHandler { m.screen = if (GuideReturn.toList || m.current == null) Screen.LIST else Screen.PLAYER; GuideReturn.toList = false }
    OrbitCanvas {
        val now = rememberNow()
        val g = rememberGuideFocus(m)
        // opens at the playing channel (else the focused one), on its programme on now
        LaunchedEffect(Unit) {
            val c = m.current ?: return@LaunchedEffect
            if (m.filtered.any { it.key == c.key }) { m.focusCh = c; m.focusProg = null; m.groupFocus[m.group] = c.key }
        }
        LiveColorKeys(m) { g.groupFocused }
        Box(Modifier.fillMaxSize()) {
            LiveBackdrop(m, Modifier.fillMaxSize())
            GuideInfo(m, now, Modifier.padding(start = 96.dp, top = 56.dp).width(1100.dp), compact = true)
            LivePreview(m, m.current, Modifier.align(Alignment.TopEnd).padding(end = 96.dp, top = 48.dp).size(480.dp, 270.dp))
            Row(Modifier.fillMaxWidth().padding(top = 350.dp, end = 96.dp), verticalAlignment = Alignment.CenterVertically) {
                GuideGroupTabs(m, g, Modifier.weight(1f))
                SubPill("Search guide", false) { m.screen = Screen.GUIDE_SEARCH }
            }
            GuideGrid(m, g, now, { ch, p -> m.guidePlay(ch, p) },
                Modifier.padding(start = 96.dp, end = 96.dp, top = 410.dp, bottom = 92.dp).fillMaxSize(), startOnNow = true)
            ColorKeyLegend(Modifier.align(Alignment.BottomStart).padding(start = 96.dp, bottom = 28.dp),
                hints = listOf("OK" to "Watch", "BACK" to "Close"))
        }
    }
}
