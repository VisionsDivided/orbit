// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.orbit5

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Room around cards inside a lazy row: the focus outline (10 px) and the 1.07 scale draw outside a card, and lazy
 * rows clip to their bounds.
 */
val FocusRoom = 26.dp

/**
 * A titled row of cards (spec section 3: heading, 20 px, cards; first card at the 96 px content edge). Entering the
 * row from above or below returns to the card last focused in it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun O5Row(
    title: String?, modifier: Modifier = Modifier, gap: Dp = O5Space.RowGapLandscape, state: LazyListState = rememberLazyListState(),
    startPadding: Dp = O5Space.Side, content: LazyListScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) Text(title, style = O5Type.RowHeading, maxLines = 1, modifier = Modifier.padding(start = startPadding, bottom = O5Space.RowHeadingGap - FocusRoom / 2))
        LazyRow(
            Modifier.fillMaxWidth().focusRestorer(), state = state,
            contentPadding = PaddingValues(start = startPadding, end = O5Space.Side, top = FocusRoom, bottom = FocusRoom),
            horizontalArrangement = Arrangement.spacedBy(gap), content = content,
        )
    }
}
