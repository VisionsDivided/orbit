// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.live

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.kmmarin.tvbro.ui.FocusMemory
import io.github.kmmarin.tvbro.ui.LocalInRail
import io.github.kmmarin.tvbro.ui.Logos
import io.github.kmmarin.tvbro.ui.SafeFocus
import io.github.kmmarin.tvbro.ui.orbit5.O5
import io.github.kmmarin.tvbro.ui.safeFocus

/** Live TV colours and type of direction 5 (spec sections 1, 9 and 10; mockup #s5 guide and player). */
object LiveStyle {
    val CellNow = Color(0x0FFFFFFF)            // rgba(255,255,255,.06): the programme on now
    val Line = O5.Line                         // grid lines
    val FmtBg = Color(0x2EF5D90A)              // rgba(245,217,10,.18)
    val FmtText = Color(0xFFFFE55C)
    val FmtRing = Color(0x80F5D90A)
    val Rec = Color(0xFFFF6B6B)
    val HintBg = Color(0xD1282204)             // rgba(40,34,4,.82)
    val HintRing = Color(0x99F5D90A)
    val HintText = Color(0xFFFFF3A8)
    val QuickCircle = Color(0x24FFFFFF)        // rgba(255,255,255,.14)
    val StreamRow = Color(0x0FFFFFFF)          // rgba(255,255,255,.06)

    private val family = FontFamily.SansSerif
    val Cell = TextStyle(fontFamily = family, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = O5.Text)
    val CellSmall = TextStyle(fontFamily = family, fontSize = 17.sp, color = O5.Muted)
    val Ruler = TextStyle(fontFamily = family, fontSize = 19.sp, color = O5.Muted, fontFeatureSettings = "tnum")
    val Tab = TextStyle(fontFamily = family, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    val Title = TextStyle(fontFamily = family, fontSize = 62.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.03).em, color = O5.Text)
    val PlayerTitle = Title.copy(fontSize = 72.sp)
    val Meta = TextStyle(fontFamily = family, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = O5.MetaText)
    val Small = TextStyle(fontFamily = family, fontSize = 20.sp, color = O5.Muted)
}

/**
 * A channel's tile (mockup `.chl`): the logo on a dark rounded tile, or, without a logo, the channel's initials on a
 * gradient picked from its name (always the same for a channel).
 */
@Composable
fun ChannelTile(logo: String?, name: String, size: Dp, modifier: Modifier = Modifier) {
    val bmp by produceState<Bitmap?>(null, logo) { value = if (logo.isNullOrEmpty()) null else Logos.load(logo) }
    val shape = RoundedCornerShape(size * 0.22f)
    val b = bmp
    if (b != null) {
        Box(modifier.size(size).clip(shape).background(Color(0x14FFFFFF)).border(1.dp, Color(0x2EFFFFFF), shape), contentAlignment = Alignment.Center) {
            Image(b.asImageBitmap(), null, Modifier.size(size).padding(size * 0.1f), contentScale = ContentScale.Fit)
        }
    } else {
        val h = ((name.hashCode() and 0x7fffffff) % 360).toFloat()
        val brush = remember(h) { Brush.linearGradient(listOf(Color.hsl(h, 0.75f, 0.55f), Color.hsl((h + 40f) % 360f, 0.70f, 0.32f))) }
        Box(modifier.size(size).clip(shape).background(brush).border(1.dp, Color(0x2EFFFFFF), shape), contentAlignment = Alignment.Center) {
            Text(initials(name), color = Color.White, fontWeight = FontWeight.Black, letterSpacing = (-0.02).em,
                fontSize = (size.value * if (initials(name).length > 2) 0.29f else 0.36f).sp, maxLines = 1)
        }
    }
}

/** "Vista Cinema" -> "VC", "Meridian News 24" -> "MN2", "BBC" -> "BBC". */
fun initials(name: String): String {
    val words = name.split(' ', '-', '_', '.').filter { it.isNotBlank() }
    if (words.isEmpty()) return "?"
    if (words.size == 1) return words[0].take(3).uppercase()
    return words.take(3).joinToString("") { it.take(1) }.uppercase()
}

/** The red LIVE pill with its white dot. */
@Composable
fun LivePill(modifier: Modifier = Modifier) {
    Row(modifier.background(O5.Live, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(Color.White, CircleShape))
        Text("LIVE", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, letterSpacing = 0.08.em, maxLines = 1)
    }
}

/** The yellow per-channel format tag ("HLS", "TS"): a channel whose stream format is set by hand. */
@Composable
fun FormatTag(text: String, modifier: Modifier = Modifier) {
    Box(modifier.background(LiveStyle.FmtBg, RoundedCornerShape(5.dp)).border(1.dp, LiveStyle.FmtRing, RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 2.dp)) {
        Text(text, color = LiveStyle.FmtText, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, letterSpacing = 0.06.em, maxLines = 1)
    }
}

/** "● REC" in the recording red. */
@Composable
fun RecMark(modifier: Modifier = Modifier, size: Int = 17) {
    Text("● REC", modifier, color = LiveStyle.Rec, fontWeight = FontWeight.Bold, fontSize = size.sp, maxLines = 1)
}

/** The yellow colour-key pill used inside hints ("Try HLS?"). */
@Composable
fun YellowKey(modifier: Modifier = Modifier) {
    Box(modifier.size(34.dp, 16.dp).background(O5.KeyYellow, RoundedCornerShape(8.dp)))
}

/**
 * Focus handling for guide cells and other flat items that draw their own focused look: like `tvFocus` / `o5Focus`
 * (remembered in [FocusMemory], Menu = long press, [focus] steers it) but it draws nothing. Read the focus from [src].
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.flatFocus(
    src: MutableInteractionSource, focus: SafeFocus? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
): Modifier = composed {
    val me = remember { SafeFocus() }
    val was = remember { booleanArrayOf(false) }
    val inRail = LocalInRail.current
    val f by src.collectIsFocusedAsState()
    SideEffect {
        if (f != was[0]) { was[0] = f; if (f) { FocusMemory.gained(); FocusMemory.focused(focus ?: me, inRail) } else FocusMemory.lost() }
    }
    DisposableEffect(Unit) { onDispose { if (was[0]) { was[0] = false; FocusMemory.lost() } } }
    this.safeFocus(focus ?: me)
        .onPreviewKeyEvent { e -> if (onLongClick != null && e.type == KeyEventType.KeyDown && e.key == Key.Menu) { onLongClick(); true } else false }
        .combinedClickable(interactionSource = src, indication = null, onLongClick = onLongClick, onClick = onClick)
}
