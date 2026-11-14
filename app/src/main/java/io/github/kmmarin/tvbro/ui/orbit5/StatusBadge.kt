// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.orbit5

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kmmarin.tvbro.orbit.Badge
import io.github.kmmarin.tvbro.orbit.BadgeKind

/** Discover status colours (spec section 1, "Discover status badges"): background, text, inset ring. */
private fun colors(k: BadgeKind): Triple<Color, Color, Color> = when (k) {
    BadgeKind.REQUESTED -> Triple(Color(0x388A5CFF), Color(0xFFD2C2FF), Color(0x998A5CFF))
    BadgeKind.APPROVED -> Triple(Color(0x2950A0FF), Color(0xFFA9CFFF), Color(0x8C50A0FF))
    BadgeKind.DOWNLOADING -> Triple(Color(0x12FFFFFF), Color.White, Color(0x992FD3FF))
    BadgeKind.AVAILABLE -> Triple(Color(0x2946DC96), Color(0xFF7FF0BB), Color(0x8046DC96))
    BadgeKind.PENDING -> Triple(Color(0x29FFBE50), Color(0xFFFFD27A), Color(0x80FFBE50))
}

/**
 * A Discover status pill (spec section 1): Requested, Approved, Downloading N% · ~T (filled to N %), Available,
 * Pending. TV: 20 px bold, padding 9 x 16, 2 px inset ring. [small]: the phone size (11 px, 5 x 9).
 */
@Composable
fun StatusBadge(badge: Badge, modifier: Modifier = Modifier, small: Boolean = false) {
    val (bg, fg, ring) = colors(badge.kind)
    val fill = if (badge.kind == BadgeKind.DOWNLOADING) badge.fraction.coerceIn(0f, 1f) else 0f
    Row(
        modifier
            .background(bg, O5Shape.Pill)
            .drawBehind {
                if (fill > 0f) drawRoundRect(Color(0x6B2FD3FF), size = Size(size.width * fill, size.height), cornerRadius = CornerRadius(size.height / 2))
            }
            .border(if (small) 1.dp else 2.dp, ring, O5Shape.Pill)
            .padding(horizontal = if (small) 9.dp else 16.dp, vertical = if (small) 5.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (small) 4.dp else 8.dp),
    ) {
        val icon = when (badge.kind) {
            BadgeKind.AVAILABLE, BadgeKind.APPROVED -> Icons.Filled.Check
            BadgeKind.DOWNLOADING -> Icons.Filled.KeyboardArrowDown
            else -> Icons.Filled.DateRange
        }
        Icon(icon, null, tint = fg, modifier = Modifier.size(if (small) 11.dp else 20.dp))
        Text(badge.label, color = fg, fontSize = if (small) 11.sp else 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
