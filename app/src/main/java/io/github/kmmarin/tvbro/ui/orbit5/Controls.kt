// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.orbit5

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kmmarin.tvbro.ui.SafeFocus

/**
 * Hero / dialog button (spec section 2 and 6): primary = white with dark text, secondary = 16 % white.
 * [compact] is the section-page size (26 px, 18 x 36).
 */
@Composable
fun O5Button(
    text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, primary: Boolean = false, compact: Boolean = false,
    focus: SafeFocus? = null, onFocus: (() -> Unit)? = null, onClick: () -> Unit,
) {
    val fg = if (primary) O5.Bg else Color.White
    // a primary button (the hero's Play) is a way into the page from the top bar
    val own = androidx.compose.runtime.remember { SafeFocus() }
    val f = focus ?: if (primary) own else null
    if (primary) RegisterShellEntry(f!!, ShellEntry.HERO)
    Row(
        modifier.o5Focus(O5Shape.Button, 12.dp, focus = f, onFocus = onFocus, onClick = onClick)
            .background(if (primary) Color.White else O5.SecondaryButton, O5Shape.Button)
            .padding(horizontal = if (compact) 36.dp else 40.dp, vertical = if (compact) 18.dp else 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(if (compact) 28.dp else 30.dp))
        Text(text, style = if (compact) O5Type.ButtonSection else O5Type.Button, color = fg, maxLines = 1)
    }
}

/** Source badge in eyebrows and on posters: Local (cyan) or IPTV (violet), or any other label in cyan. */
@Composable
fun SourceBadge(source: String, modifier: Modifier = Modifier) {
    val iptv = source.equals("iptv", true)
    val label = when (source.lowercase()) { "iptv" -> "IPTV"; "plex", "jellyfin", "local" -> "Local"; else -> source }
    Box(modifier.background(if (iptv) O5.SourceIptv else O5.SourceLocal, RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 5.dp)) {
        Text(label.uppercase(), style = O5Type.Badge, color = if (iptv) Color(0xFF140A2E) else Color(0xFF03141C), maxLines = 1)
    }
}

/** Outlined meta pill ("4K", "HDR"): 2 px border at 50 % white, radius 6. */
@Composable
fun MetaPill(text: String) {
    Box(Modifier.border(2.dp, Color(0x80FFFFFF), RoundedCornerShape(6.dp)).padding(horizontal = 9.dp, vertical = 1.dp)) {
        Text(text, style = O5Type.MetaPill, maxLines = 1)
    }
}

/** 6 px progress bar: 22 % white track, accent fill. */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    Box(modifier.height(height).clip(RoundedCornerShape(height / 2)).background(O5.ProgressTrack)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(O5.Accent, RoundedCornerShape(height / 2)))
    }
}

/** Watched mark: a 34 px dark circle with a mint check, top right of a card. */
@Composable
fun BoxScope.WatchedMark() {
    Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(34.dp).background(Color(0x9E000000), CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Check, null, tint = O5.Watched, modifier = Modifier.size(20.dp))
    }
}

/** Profile avatar: a circle in [brush] (the brand gradient by default) with the name's initial. */
@Composable
fun Avatar(name: String, size: Dp, modifier: Modifier = Modifier, brush: Brush = O5.AvatarGradient) {
    Box(modifier.size(size).clip(CircleShape).background(brush), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase().ifEmpty { "?" }, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.42f).sp, maxLines = 1)
    }
}

/** Avatar colours for profiles other than the brand one, picked from the name so they stay the same. */
object AvatarColors {
    private val pairs = listOf(
        Color(0xFF8892B0) to Color(0xFF39425F), Color(0xFFFF9A76) to Color(0xFFC8507F), Color(0xFF8DE0B0) to Color(0xFF2A8F84),
        Color(0xFF5FB6F0) to Color(0xFF2C5DA8), Color(0xFFFFC46B) to Color(0xFFC7702A), Color(0xFFC59BFF) to Color(0xFF6A3FD8),
    )
    fun brush(name: String, index: Int): Brush =
        if (index == 0) O5.AvatarGradient else pairs[(name.hashCode() and 0x7fffffff) % pairs.size].let { Brush.linearGradient(listOf(it.first, it.second)) }
}

/** Chip text style helper for pills drawn by other components. */
internal fun pillStyle(active: Boolean, base: TextStyle) = base.copy(color = if (active) O5.Bg else O5.PillInactive, fontWeight = if (active) FontWeight.Bold else base.fontWeight)
