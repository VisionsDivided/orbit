// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kmmarin.tvbro.data.StreamChoice
import io.github.kmmarin.tvbro.ui.SafeFocus
import io.github.kmmarin.tvbro.ui.focusTrap
import io.github.kmmarin.tvbro.ui.hhmm
import io.github.kmmarin.tvbro.ui.orbit5.O5
import io.github.kmmarin.tvbro.ui.orbit5.O5Shape
import io.github.kmmarin.tvbro.ui.orbit5.o5Focus

/** The prompt's title: "The stream is in use", "Both streams are in use", "All 3 streams are in use". */
fun streamPromptTitle(max: Int): String = when {
    max <= 1 -> "The stream is in use"
    max == 2 -> "Both streams are in use"
    else -> "All $max streams are in use"
}

/** The explanation under the title. */
fun streamPromptText(max: Int, want: String): String =
    "Your provider allows $max at a time. Choose which one to stop" + (if (want.isNotBlank()) " to watch ${want.trim()} here." else " to watch here.")

/** A secondary dialog button (16 % white, radius 12, bold) with the direction-5 focus. */
@Composable
fun PromptButton(text: String, fontSize: TextUnit, padH: Dp, padV: Dp, focus: SafeFocus? = null, onClick: () -> Unit) {
    Box(
        Modifier.o5Focus(O5Shape.Button, 12.dp, focus = focus, scaleTo = 1.05f, onClick = onClick)
            .background(O5.SecondaryButton, O5Shape.Button).padding(horizontal = padH, vertical = padV),
    ) { Text(text, color = Color.White, fontSize = fontSize, fontWeight = FontWeight.Bold, maxLines = 1) }
}

/**
 * The stream-limit prompt (spec section 10): a dialog at the top right (right 96, y 132, 900 wide) listing every
 * active stream with its device name, what it plays and since when, each with its own "Stop <name> and watch";
 * recordings have no button (they keep going). Cancel at the bottom right holds the first focus: no stream is ever
 * picked for the viewer. [oldestOnly]: a server from before stream ids, which can only stop the oldest viewer.
 */
@Composable
fun StreamPromptCard(
    max: Int, want: String, rows: List<StreamChoice.Row>, oldestOnly: Boolean, cancelFocus: SafeFocus?,
    onStop: (String) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier,
) {
    Column(
        modifier.width(900.dp).shadow(60.dp, O5Shape.Dialog).background(O5.Dialog, O5Shape.Dialog).border(1.dp, O5.DialogBorder, O5Shape.Dialog)
            .padding(start = 30.dp, end = 30.dp, top = 28.dp, bottom = 26.dp).focusTrap(),
    ) {
        Text(streamPromptTitle(max), color = O5.Text, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
        Text(streamPromptText(max, want), color = O5.Muted, fontSize = 21.sp, lineHeight = 30.sp, modifier = Modifier.padding(top = 8.dp))
        for (r in rows) {
            Row(
                Modifier.padding(top = 12.dp).fillMaxWidth().background(LiveStyle.StreamRow, RoundedCornerShape(14.dp)).padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                val channel = r.what.substringBefore(" · ").ifBlank { r.name }
                ChannelTile(null, channel, 60.dp)
                Column(Modifier.weight(1f)) {
                    Text(r.name, color = O5.Text, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(r.what.ifEmpty { "Watching" }, color = O5.Muted, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                    val since = if (r.recording) "Recording · keeps going" else if (r.since > 0) "Watching since ${hhmm(r.since)}" else null
                    since?.let { Text(it, color = O5.Muted, fontSize = 17.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp)) }
                }
                if (r.stoppable) PromptButton(r.button, 20.sp, 20.dp, 13.dp) { onStop(r.id) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)) {
            if (oldestOnly) PromptButton("Stop the oldest and watch here", 22.sp, 34.dp, 14.dp) { onStop("") }
            PromptButton("Cancel", 22.sp, 34.dp, 14.dp, focus = cancelFocus, onClick = onCancel)
        }
    }
}
