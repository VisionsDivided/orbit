// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kmmarin.tvbro.ui.orbit5.O5
import io.github.kmmarin.tvbro.ui.orbit5.O5Shape
import io.github.kmmarin.tvbro.ui.orbit5.PhoneAction
import io.github.kmmarin.tvbro.ui.orbit5.PhoneRoundButton
import io.github.kmmarin.tvbro.ui.orbit5.ProgressBar

/** Material "fullscreen" and "fullscreen_exit". */
private fun icon(name: String, d: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
    addPath(PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.White))
}.build()

val FullscreenIcon: ImageVector by lazy { icon("fs", "M7,14L5,14v5h5v-2L7,17v-3zM5,10h2L7,7h3L10,5L5,5v5zM17,17h-3v2h5v-5h-2v3zM14,5v2h3v3h2L19,5h-5z") }
val FullscreenExitIcon: ImageVector by lazy { icon("fsx", "M5,16h3v3h2v-5L5,14v2zM8,8L5,8v2h5L10,5L8,5v3zM14,19h2v-3h3v-2h-5v5zM16,8L16,5h-2v5h5L19,8h-3z") }

/** The phone player's buttons. */
@Immutable
class PhonePlayerEvents(
    val onTapVideo: () -> Unit = {}, val onBack: () -> Unit = {}, val onFullscreen: () -> Unit = {},
    val onPrev: () -> Unit = {}, val onNext: () -> Unit = {}, val onHint: () -> Unit = {},
    /** Where the video is drawn upright (null on its side: full screen). */
    val onVideoRect: (Rect?) -> Unit = {},
)

/**
 * The live player on a phone (spec section 13, mockup "Phone · 5b" Player). Upright: the video across the top (16:9),
 * then the channel, the programme (title, times, next), the "Try HLS?" chip, the round actions and [below] (the
 * group's channels: tap to switch). On its side: the video full screen. A tap on the video shows or hides its controls
 * (Back, LIVE, full screen, progress; on its side also the programme, channel up / down and the actions); they hide
 * again after a few seconds. [video] is drawn under the controls (the app's video is a separate layer under this
 * screen; the shots draw a still).
 */
@Composable
fun PhonePlayerView(
    info: PlayerInfo, actions: List<QuickAction>, controls: Boolean, ev: PhonePlayerEvents, modifier: Modifier = Modifier,
    hint: Boolean = false, status: String = "", video: @Composable BoxScope.() -> Unit = {}, below: LazyListScope.() -> Unit = {},
) {
    val status0 = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    BoxWithConstraints(modifier.fillMaxSize().testTag("phone-player")) {
        val wide = maxWidth > maxHeight
        LaunchedEffect(wide) { if (wide) ev.onVideoRect(null) }
        if (!wide) Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.fillMaxWidth().height(status0).background(Color.Black))
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).onGloballyPositioned { ev.onVideoRect(it.boundsInRoot()) }
                    .pointerInput(Unit) { detectTapGestures(onTap = { ev.onTapVideo() }) }.testTag("phone-player-video"),
            ) {
                video()
                if (controls) {
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x66000000), 0.3f to Color.Transparent, 0.6f to Color.Transparent, 1f to Color(0xB3000000))))
                    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        PhoneRoundButton(Icons.Filled.KeyboardArrowDown, "Back to Live TV", onClick = ev.onBack)
                        LiveBadge(info)
                        Spacer(Modifier.weight(1f))
                        PhoneRoundButton(FullscreenIcon, "Full screen", modifier = Modifier.testTag("phone-player-fullscreen"), onClick = ev.onFullscreen)
                    }
                    info.progress?.let { ProgressBar(it, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp), height = 3.dp) }
                }
                if (status.isNotEmpty()) StatusChip(status, Modifier.align(Alignment.Center))
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f).background(O5.Bg), contentPadding = PaddingValues(bottom = nav + 16.dp)) {
                item(key = "info") {
                    Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ChannelTile(info.logo, info.channel, 44.dp)
                            Column(Modifier.weight(1f)) {
                                Text(info.channel, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = O5.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(info.sub, fontSize = 12.sp, color = O5.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            PhoneRoundButton(Icons.Filled.KeyboardArrowUp, "Previous channel", onClick = ev.onPrev)
                            PhoneRoundButton(Icons.Filled.KeyboardArrowDown, "Next channel", onClick = ev.onNext)
                        }
                        Row(Modifier.padding(top = 12.dp, bottom = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(info.title, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp, color = O5.Text,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (info.rec) Text("● REC", color = LiveStyle.Rec, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        listOfNotNull(info.time, info.next).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                            Text(it, fontSize = 12.5.sp, color = O5.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 8.dp))
                        }
                        if (hint) PhoneHlsChip(Modifier.padding(top = 12.dp), ev.onHint)
                        info.tech?.let { Text(it.replace("\n", " · "), fontSize = 10.5.sp, color = Color(0x80E4E9FF), maxLines = 2, modifier = Modifier.padding(top = 10.dp, end = 8.dp)) }
                    }
                }
                item(key = "actions") { ActionRow(actions, Modifier.padding(top = 16.dp, bottom = 8.dp)) }
                below()
            }
        } else Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { ev.onTapVideo() }) }.testTag("phone-player-video")) {
            video()
            if (controls) {
                Box(Modifier.fillMaxSize().playerScrims())
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = status0 + 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhoneRoundButton(Icons.Filled.KeyboardArrowDown, "Back to Live TV", onClick = ev.onBack)
                    ChannelTile(info.logo, info.channel, 36.dp)
                    Column(Modifier.weight(1f)) {
                        Text(info.channel, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = O5.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(info.sub, fontSize = 12.sp, color = O5.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    PhoneRoundButton(Icons.Filled.KeyboardArrowUp, "Previous channel", onClick = ev.onPrev)
                    PhoneRoundButton(Icons.Filled.KeyboardArrowDown, "Next channel", onClick = ev.onNext)
                    Text(info.clock, fontSize = 18.sp, fontWeight = FontWeight.Light, color = O5.Text, modifier = Modifier.padding(horizontal = 6.dp))
                    PhoneRoundButton(FullscreenExitIcon, "Leave full screen", onClick = ev.onFullscreen)
                }
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = nav + 10.dp)) {
                    Column(Modifier.padding(horizontal = 24.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LiveBadge(info, Modifier)
                            info.time?.let { Text(it, fontSize = 12.5.sp, color = O5.Muted, maxLines = 1) }
                            if (info.rec) Text("● REC", color = LiveStyle.Rec, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(info.title, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = O5.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        if (info.progress != null) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            info.start?.let { Text(it, fontSize = 11.sp, color = O5.Text) }
                            ProgressBar(info.progress, Modifier.weight(1f), height = 3.dp)
                            info.end?.let { Text(it, fontSize = 11.sp, color = O5.Text) }
                        }
                        info.next?.let { Text(it, fontSize = 12.sp, color = O5.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
                    }
                    ActionRow(actions, Modifier.padding(top = 10.dp))
                }
            }
            if (hint) PhoneHlsChip(Modifier.align(Alignment.TopEnd).padding(top = status0 + 64.dp, end = 16.dp), ev.onHint)
            if (status.isNotEmpty()) StatusChip(status, Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun LiveBadge(info: PlayerInfo, modifier: Modifier = Modifier.padding(start = 4.dp)) {
    if (info.catchup) SmallLive(modifier, text = "CATCH-UP", color = O5.Accent, fg = O5.OnAccent)
    else if (info.live) SmallLive(modifier)
}

@Composable
private fun ActionRow(actions: List<QuickAction>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        actions.forEach { a -> PhoneQuickAction(a) }
    }
}

/** A quick action of the player as a phone round action (icon or glyph, label, accent ring when on). */
@Composable
fun PhoneQuickAction(a: QuickAction, modifier: Modifier = Modifier) {
    PhoneAction(a.label, modifier.testTag("phone-qa-${a.label}"), icon = a.icon, glyph = a.glyph, tint = a.tint ?: Color.White, on = a.on, onClick = a.onClick)
}

@Composable
private fun StatusChip(text: String, modifier: Modifier) {
    Text(text, modifier.background(O5.ScrimPanel, O5Shape.Pill).padding(horizontal = 14.dp, vertical = 7.dp), color = O5.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
}

/** "Keeps buffering. Try HLS? Switch" (spec section 13: a small yellow chip under the programme info); tap = yes. */
@Composable
fun PhoneHlsChip(modifier: Modifier = Modifier, onAccept: () -> Unit = {}) {
    Row(
        modifier.background(Color(0xE6282204), O5Shape.Pill).border(1.5.dp, LiveStyle.HintRing, O5Shape.Pill)
            .pointerInput(Unit) { detectTapGestures(onTap = { onAccept() }) }
            .padding(horizontal = 14.dp, vertical = 12.dp).testTag("phone-hls-chip"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Keeps buffering. Try HLS?", color = LiveStyle.HintText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text("Switch", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** The "Channels · group" heading over the player's channel list. */
@Composable
fun PhoneListHeading(text: String) {
    Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = O5.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp))
}
