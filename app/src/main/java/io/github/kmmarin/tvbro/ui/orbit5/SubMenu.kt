// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.orbit5

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import io.github.kmmarin.tvbro.ui.SafeFocus

/** Movies / Shows source toggle values (catalog `source=` parameter). */
enum class BrowseSource(val label: String, val param: String) { ALL("All", "all"), LOCAL("Local", "local"), IPTV("IPTV", "iptv") }

/**
 * The Browse tab's controls (spec section 5): the source toggle inline next to the Browse pill, and on the right the
 * genre and sort dropdowns and the title count. The dropdowns only report the click: the screen opens its own menu.
 */
data class BrowseControls(
    val source: BrowseSource,
    val onSource: (BrowseSource) -> Unit,
    val genre: String = "Any genre",
    val onGenre: () -> Unit = {},
    val sort: String = "Recently added",
    val onSort: () -> Unit = {},
    /** "1,284 titles"; null hides it. */
    val count: String? = null,
)

/**
 * The sub-menu row under the top bar (spec section 5): pills at x = 96, y = 118, gap 12. [browseTab] is the index of
 * the tab that carries [browse] (shown only while that tab is selected; the row then spans the full width).
 *
 * D-pad: Left / Right along the row, including the toggle segments and dropdowns; Up goes to the top bar (the shell
 * does that); Down to the content. Entering the row from below lands on the selected pill.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SubMenuRow(
    tabs: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier,
    browseTab: Int = -1, browse: BrowseControls? = null,
) {
    if (io.github.kmmarin.tvbro.ui.shell.LocalIsPhone.current) { PhoneSubMenu(tabs, selected, onSelect, modifier); return }
    val sel = remember { SafeFocus() }
    RegisterShellEntry(sel, ShellEntry.SUB_MENU)   // Down from the top bar on a first visit lands here
    val wide = browse != null && selected == browseTab
    Row(
        modifier.then(if (wide) Modifier.fillMaxWidth() else Modifier).padding(start = O5Space.Side, end = O5Space.Side)
            .focusProperties { enter = { sel.target() } }.focusGroup(),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        tabs.forEachIndexed { i, t ->
            val active = i == selected
            if (wide && i == browseTab) {
                // Browse group: the active pill and the source segments inside one chip
                Row(
                    Modifier.background(O5.ChipBg, O5Shape.Pill).border(1.dp, O5.ChipRing, O5Shape.Pill).padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SubPill(t, true, sel) { onSelect(i) }
                    Row(Modifier.background(Color(0x14FFFFFF), O5Shape.Pill).padding(3.dp)) {
                        for (s in BrowseSource.values()) Segment(s.label, s == browse!!.source) { browse.onSource(s) }
                    }
                }
            } else SubPill(t, active, if (active) sel else null) { onSelect(i) }
        }
        if (wide) {
            Spacer(Modifier.weight(1f))
            Dropdown(browse!!.genre, browse.onGenre)
            Dropdown("Sort: " + browse.sort, browse.onSort)
            browse.count?.let { Text(it, style = O5Type.Dropdown, color = O5.Muted, maxLines = 1, modifier = Modifier.padding(start = 8.dp)) }
        }
    }
}

/** A sub-menu pill: 22 px, 10 x 24, translucent with a thin ring; active = white with dark text. */
@Composable
fun SubPill(text: String, active: Boolean, focus: SafeFocus? = null, onClick: () -> Unit) {
    Box(
        Modifier.o5Focus(O5Shape.Pill, 30.dp, focus = focus, scaleTo = 1.05f, onClick = onClick)
            .background(if (active) Color.White else O5.ChipBg, O5Shape.Pill)
            .then(if (active) Modifier else Modifier.border(1.dp, O5.ChipRing, O5Shape.Pill))
            .padding(horizontal = 24.dp, vertical = 10.dp).testTag("subpill-$text"),
    ) { Text(text, style = pillStyle(active, O5Type.SubPill), maxLines = 1) }
}

/** A segment of a segmented control (19 px, 6 x 16); selected = accent with dark text. */
@Composable
fun Segment(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.o5Focus(O5Shape.Pill, 24.dp, scaleTo = 1.05f, onClick = onClick)
            .background(if (selected) O5.Accent else Color.Transparent, O5Shape.Pill).padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(text, style = O5Type.Segment, color = if (selected) O5.OnAccent else O5.PillInactive,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1)
    }
}

/** Dropdown pill (20 px, 10 x 18) with a chevron. */
@Composable
fun Dropdown(text: String, onClick: () -> Unit) {
    Row(
        Modifier.o5Focus(O5Shape.Pill, 30.dp, scaleTo = 1.05f, onClick = onClick)
            .background(O5.ChipBgStrong, O5Shape.Pill).border(1.dp, O5.ChipRing, O5Shape.Pill).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = O5Type.Dropdown, maxLines = 1)
        Spacer(Modifier.width(10.dp))
        Icon(Icons.Filled.KeyboardArrowDown, null, tint = O5.MetaText, modifier = Modifier.size(20.dp))
    }
}

/** The sub-menu on a phone (spec section 13): scrolling chips (13 px, 7 x 13), white when selected, 20 px margins. */
@Composable
private fun PhoneSubMenu(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    PhoneChipRow(modifier) {
        tabs.forEachIndexed { i, t -> PhoneChip(t, i == selected) { onSelect(i) } }
    }
}