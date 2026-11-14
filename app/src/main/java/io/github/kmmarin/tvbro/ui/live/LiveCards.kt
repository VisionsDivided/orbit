// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.kmmarin.tvbro.ui.SafeFocus
import io.github.kmmarin.tvbro.ui.orbit5.CardSize
import io.github.kmmarin.tvbro.ui.orbit5.O5
import io.github.kmmarin.tvbro.ui.orbit5.O5Shape
import io.github.kmmarin.tvbro.ui.orbit5.O5Type
import io.github.kmmarin.tvbro.ui.orbit5.ProgressBar
import io.github.kmmarin.tvbro.ui.orbit5.o5Focus

/**
 * A landscape card for something on a channel (search results, recordings): the channel's logo on a dark gradient
 * picked from its name (channels have no artwork), a status badge top left, a mark top right, an optional progress bar,
 * and the title and meta under it like [io.github.kmmarin.tvbro.ui.orbit5.LandscapeCard]. Direction-5 focus.
 */
@Composable
fun ChannelArtCard(
    logo: String?, channel: String, title: String, meta: String?, modifier: Modifier = Modifier,
    width: Dp = CardSize.LandscapeSectionW, height: Dp = CardSize.LandscapeSectionH,
    progress: Float? = null, badge: (@Composable BoxScope.() -> Unit)? = null, mark: String? = null,
    /** The channel's name under its logo (when the caption below is about something else). */
    label: String? = null,
    focus: SafeFocus? = null, onFocus: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
) {
    val h = ((channel.hashCode() and 0x7fffffff) % 360).toFloat()
    val bg = remember(h) { Brush.linearGradient(listOf(Color.hsl(h, 0.45f, 0.24f), Color.hsl((h + 40f) % 360f, 0.5f, 0.09f))) }
    Column(modifier.width(width)) {
        Box(Modifier.size(width, height).o5Focus(focus = focus, onFocus = onFocus, onLongClick = onLongClick, onClick = onClick).clip(O5Shape.Card).background(bg)) {
            if (label == null) ChannelTile(logo, channel, height * 0.46f, Modifier.align(Alignment.Center))
            else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                ChannelTile(logo, channel, height * 0.40f)
                Text(label, color = Color(0xD9FFFFFF), fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp).width(width - 40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            badge?.invoke(this)
            mark?.let { Text(it, color = O5.Rating, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) }
            progress?.let { ProgressBar(it, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) }
        }
        Text(title, style = O5Type.CardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp))
        meta?.let { Text(it, style = O5Type.CardMeta, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
    }
}

/** A small status pill for cards ("● REC", "Scheduled", "Catch-up"), top left. */
@Composable
fun BoxScope.CardPill(text: String, bg: Color, fg: Color = Color.White) {
    Box(Modifier.align(Alignment.TopStart).padding(10.dp).background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, color = fg, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, letterSpacing = 0.06.em, maxLines = 1)
    }
}
