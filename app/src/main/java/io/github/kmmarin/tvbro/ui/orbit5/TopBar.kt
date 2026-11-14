// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.orbit5

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.kmmarin.tvbro.R
import io.github.kmmarin.tvbro.ui.LocalInRail
import io.github.kmmarin.tvbro.ui.SafeFocus
import kotlinx.coroutines.delay

/** The five sections on the top bar, in order. */
enum class NavTab(val label: String) { HOME("Home"), MOVIES("Movies"), SHOWS("Shows"), LIVE("Live TV"), DISCOVER("Discover") }

/**
 * The top bar (spec section 4): logo, Home · Movies · Shows · Live TV · Discover, then search, clock and avatar on the
 * right. 120 px high, no background (the page's top scrim keeps it legible).
 *
 * Focus: entering the bar (Up from the page) lands on the active item ([currentFocus]); Down runs [onDown] (the shell
 * moves focus into the page, checking `stillInBar` after each try) and, when focus is still in the bar, moves to the
 * nearest item below. Items are not
 * remembered as places in the page ([LocalInRail]).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TopBar(
    active: NavTab?, onTab: (NavTab) -> Unit, onSearch: () -> Unit, onAvatar: () -> Unit,
    profileName: String, currentFocus: SafeFocus, modifier: Modifier = Modifier,
    searchActive: Boolean = false, settingsActive: Boolean = false, onDown: (stillInBar: () -> Boolean) -> Unit = {},
) {
    // whether focus is still in the bar: a focus request cannot report failure, so Down checks where focus went
    val inBar = remember { booleanArrayOf(false) }
    CompositionLocalProvider(LocalInRail provides true) {
        Row(
            modifier.fillMaxWidth().height(O5Space.TopBar).padding(horizontal = O5Space.Side)
                // Down: into the page by the shell's rules (a focusProperties exit redirect did not move focus there);
                // when focus is still in the bar after that, the normal search for the nearest item below
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown || e.key != Key.DirectionDown) return@onPreviewKeyEvent false
                    onDown { inBar[0] }
                    !inBar[0]
                }
                .onFocusChanged { inBar[0] = it.hasFocus }
                .focusProperties {
                    enter = { currentFocus.target() }
                    exit = { d -> if (d == FocusDirection.Up) FocusRequester.Cancel else FocusRequester.Default }
                }.focusGroup().testTag("topbar"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(R.drawable.orbit_wordmark), "Orbit", Modifier.height(46.dp).width(140.dp))
            Spacer(Modifier.width(72.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(54.dp), verticalAlignment = Alignment.CenterVertically) {
                for (t in NavTab.values()) {
                    val on = t == active
                    NavItem(t.label, on, if (on || (active == null && t == NavTab.HOME && !searchActive && !settingsActive)) currentFocus else null) { onTab(t) }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.o5Focus(CircleShape, 30.dp, focus = if (searchActive) currentFocus else null, onClick = onSearch).padding(8.dp).testTag("topbar-search"),
                ) { Icon(Icons.Filled.Search, "Search", tint = if (searchActive) O5.Accent else Color.White, modifier = Modifier.size(34.dp)) }
                Clock()
                Box(
                    Modifier.o5Focus(CircleShape, 28.dp, focus = if (settingsActive) currentFocus else null, onClick = onAvatar)
                        // in Settings: a double ring outside the avatar (0 0 0 4px bg, 0 0 0 8px white)
                        .then(if (settingsActive) Modifier.drawBehind {
                            drawCircle(Color.White, size.minDimension / 2 + 8.dp.toPx()); drawCircle(O5.Bg, size.minDimension / 2 + 4.dp.toPx())
                        } else Modifier)
                        .testTag("topbar-avatar"),
                ) { Avatar(profileName, 56.dp) }
            }
        }
    }
}

@Composable
private fun NavItem(label: String, active: Boolean, focus: SafeFocus?, onClick: () -> Unit) {
    Box(
        Modifier.o5Focus(O5Shape.Pill, 30.dp, focus = focus, scaleTo = 1.05f, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .drawWithContent {
                drawContent()
                if (active) {
                    // 4 px accent underline 8 px below the text, with a soft glow (no blur: a few wider, fainter lines)
                    val y = size.height + 4.dp.toPx()
                    val inset = 14.dp.toPx()
                    for ((grow, a) in listOf(8f to 0.10f, 5f to 0.18f, 2.5f to 0.30f)) {
                        val g = grow.dp.toPx()
                        drawRoundRect(O5.Accent.copy(alpha = a), Offset(inset - g, y - g), Size(size.width - 2 * inset + 2 * g, 4.dp.toPx() + 2 * g), CornerRadius(4.dp.toPx() + g))
                    }
                    drawRoundRect(O5.Accent, Offset(inset, y), Size(size.width - 2 * inset, 4.dp.toPx()), CornerRadius(2.dp.toPx()))
                }
            }
            .testTag("nav-$label"),
    ) { Text(label, style = O5Type.Nav, color = if (active) Color.White else O5.NavInactive, maxLines = 1) }
}

/** Clock, 32 px light with tabular figures; ticks on the minute. Time format follows Settings. */
@Composable
fun Clock(modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000 - System.currentTimeMillis() % 60_000 + 50); now = System.currentTimeMillis() } }
    Text(io.github.kmmarin.tvbro.data.TimeFmt.time(now / 1000), style = O5Type.Clock, maxLines = 1, modifier = modifier)
}
