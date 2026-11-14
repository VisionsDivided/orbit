// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kmmarin.tvbro.ui.SafeFocus
import io.github.kmmarin.tvbro.ui.focusTrap
import io.github.kmmarin.tvbro.ui.orbit5.O5
import io.github.kmmarin.tvbro.ui.orbit5.O5Shape
import io.github.kmmarin.tvbro.ui.orbit5.O5Type
import io.github.kmmarin.tvbro.ui.orbit5.o5Focus

/** What the live player's overlay shows (spec section 10). */
@Immutable
data class PlayerInfo(
    val channel: String,
    /** "Channel 104 · Movies". */
    val sub: String,
    val logo: String?,
    val clock: String,
    val title: String,
    /** "21:30 – 23:35 · S1 E4". */
    val time: String? = null,
    val live: Boolean = true,
    /** Catch-up of a programme instead of live ("CATCH-UP" pill). */
    val catchup: Boolean = false,
    val progress: Float? = null,
    val start: String? = null,
    val end: String? = null,
    val next: String? = null,
    /** Tech info and playback stats (resolution, codec, bitrate, stalls), one or two lines. */
    val tech: String? = null,
    val rec: Boolean = false,
)

private val bottomScrim = Brush.verticalGradient(0f to Color.Transparent, 0.34f to Color.Transparent, 0.60f to Color(0xA8000000), 1f to Color(0xF0000000))
private val topScrim = Brush.verticalGradient(0f to Color(0x9E000000), 0.24f to Color.Transparent)

/** Bottom scrim `rgba(0,0,0,.94) -> .66 at 40% -> transparent 66%` plus a top scrim to 24 %. */
fun Modifier.playerScrims(): Modifier = drawBehind { drawRect(bottomScrim); drawRect(topScrim) }

/** Top left: the channel's tile, name and number; top right: the clock (40 px, light) with the tech info under it. */
@Composable
fun PlayerTop(info: PlayerInfo, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(start = 96.dp, end = 96.dp, top = 60.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            ChannelTile(info.logo, info.channel, 72.dp)
            Column {
                Text(info.channel, color = O5.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(info.sub, style = LiveStyle.Small.copy(fontSize = 22.sp), maxLines = 1)
            }
        }
        Column(Modifier.align(Alignment.TopEnd), horizontalAlignment = Alignment.End) {
            Text(info.clock, color = O5.Text, fontSize = 40.sp, fontWeight = FontWeight.Light, maxLines = 1)
            info.tech?.let { Text(it, style = LiveStyle.Small.copy(fontSize = 17.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End), color = Color(0x99E4E9FF), maxLines = 3, modifier = Modifier.padding(top = 6.dp).widthIn(max = 760.dp)) }
        }
    }
}

/** The programme block at x = 96, y ≈ 612: LIVE and times, title 72 px, the scrubber, "Next · …", tech line. */
@Composable
fun PlayerInfoBlock(info: PlayerInfo, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(start = 96.dp, end = 96.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (info.catchup) Text("CATCH-UP", color = O5.OnAccent, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp,
                modifier = Modifier.background(O5.Accent, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
            else if (info.live) LivePill()
            info.time?.let { Text(it, style = LiveStyle.Small.copy(fontSize = 24.sp), maxLines = 1) }
            if (info.rec) RecMark(size = 22)
        }
        Text(info.title, style = LiveStyle.PlayerTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp, bottom = 18.dp))
        if (info.progress != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            info.start?.let { Text(it, color = O5.Text, fontSize = 22.sp, maxLines = 1) }
            Scrubber(info.progress, Modifier.weight(1f))
            info.end?.let { Text(it, color = O5.Text, fontSize = 22.sp, maxLines = 1) }
        }
        info.next?.let { Text(it, style = LiveStyle.Small.copy(fontSize = 22.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp)) }
    }
}

/** 8 px bar with a 22 px white knob (and a soft ring) at [fraction]. */
@Composable
fun Scrubber(fraction: Float, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.height(22.dp)) {
        val f = fraction.coerceIn(0f, 1f)
        Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(8.dp).background(O5.ProgressTrack, RoundedCornerShape(4.dp)))
        Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(f).height(8.dp).background(O5.Accent, RoundedCornerShape(4.dp)))
        Box(Modifier.offset(x = maxWidth * f - 11.dp).size(22.dp).drawBehind {
            drawCircle(Color(0x40FFFFFF), radius = size.minDimension / 2 + 6.dp.toPx())
            drawCircle(Color.White)
        })
    }
}

/** "This channel keeps buffering. Try HLS?" with the yellow key (spec section 10); tap = yes. */
@Composable
fun HlsHintChip(modifier: Modifier = Modifier, onAccept: () -> Unit = {}) {
    Row(
        modifier.background(LiveStyle.HintBg, O5Shape.Pill).border(2.dp, LiveStyle.HintRing, O5Shape.Pill)
            .pointerInput(Unit) { detectTapGestures(onTap = { onAccept() }) }
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        YellowKey()
        Text("This channel keeps buffering. Try HLS?", color = LiveStyle.HintText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A quick action of the player (84 px circle and label): [icon] or a short [glyph] inside, [value] under the label. */
class QuickAction(
    val label: String, val icon: ImageVector? = null, val glyph: String? = null, val value: String? = null,
    val tint: Color? = null, val on: Boolean = false, val onClick: () -> Unit,
)

/** The quick actions row at y ≈ 846 (gap 20); scrolls sideways so the focused one stays in view. */
@Composable
fun QuickActionRow(actions: List<QuickAction>, first: SafeFocus?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 96.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        actions.forEachIndexed { i, a -> QuickCircle(a, if (i == 0) first else null) }
    }
}

@Composable
private fun QuickCircle(a: QuickAction, focus: SafeFocus?) {
    var f by remember { mutableStateOf(false) }
    Column(Modifier.width(120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(84.dp).onFocusChanged { f = it.isFocused }
                .o5Focus(CircleShape, 42.dp, focus = focus, scaleTo = 1.08f, onClick = a.onClick)
                .background(if (f) Color.White else LiveStyle.QuickCircle, CircleShape)
                .then(if (a.on && !f) Modifier.border(2.dp, O5.Accent, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            val fg = if (f) O5.Bg else a.tint ?: Color.White
            if (a.icon != null) Icon(a.icon, null, tint = fg, modifier = Modifier.size(36.dp))
            else Text(a.glyph ?: a.label.take(1), color = fg, fontWeight = FontWeight.ExtraBold, fontSize = if (a.glyph == "\u25CF") 44.sp else if ((a.glyph?.length ?: 1) > 2) 22.sp else 30.sp, maxLines = 1)
        }
        Text(a.label, color = if (f) Color.White else O5.Muted, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
        a.value?.let { Text(it, color = if (f) O5.Accent else Color(0x80E4E9FF), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/**
 * A pop-up in direction 5's unified look (spec `dialog`: rgba(10,12,22,.95), 1 px ring, radius 20, padding 28 x 30):
 * a title and a column of choices. Focus stays inside.
 */
@Composable
fun O5Popup(title: String, rows: List<Pair<String, () -> Unit>>, first: SafeFocus?, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(
        modifier.widthIn(min = 560.dp, max = 900.dp).background(O5.Dialog, O5Shape.Dialog).border(1.dp, O5.DialogBorder, O5Shape.Dialog)
            .padding(horizontal = 30.dp, vertical = 28.dp).focusTrap(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, color = O5.Text, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2)
        subtitle?.let { Text(it, style = O5Type.Body.copy(fontSize = 21.sp)) }
        rows.forEachIndexed { i, (label, run) ->
            Box(
                Modifier.fillMaxWidth().o5Focus(RoundedCornerShape(14.dp), 14.dp, focus = if (i == 0) first else null, scaleTo = 1.02f, onClick = run)
                    .background(LiveStyle.StreamRow, RoundedCornerShape(14.dp)).padding(horizontal = 22.dp, vertical = 16.dp),
            ) { Text(label, color = O5.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** The whole overlay for previews and tests: scrims, top line, info block, hint, quick actions. */
@Composable
fun PlayerOverlayStill(info: PlayerInfo, actions: List<QuickAction>?, hint: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().playerScrims()) {
        PlayerTop(info)
        PlayerInfoBlock(info, Modifier.padding(top = 612.dp))
        if (hint) HlsHintChip(Modifier.align(Alignment.TopEnd).padding(top = 618.dp, end = 96.dp))
        if (actions != null) QuickActionRow(actions, null, Modifier.padding(top = 846.dp))
    }
}
