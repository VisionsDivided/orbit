// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.orbit5

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/*
 * Orbit TV design, direction 5 "Cinematic + top bar" (docs/orbit/TV-UI.md; spec: DESIGN-SPEC-5.md).
 *
 * Units: inside [OrbitCanvas] one dp is one pixel of the 1920 x 1080 spec, whatever the TV's real resolution
 * (the density is set from the screen width). Write the spec's numbers directly: 96.dp margin, 112.sp hero title.
 * Screens written for the old density (the legacy tvBro screens) are wrapped in [LegacyDensity].
 */

/** Colour tokens (spec section 1). */
object O5 {
    val Bg = Color(0xFF05060C)
    val Text = Color(0xFFF4F6FF)
    val Muted = Color(0xA3E4E9FF)                  // rgba(228,233,255,.64)
    val Accent = Color(0xFF2FD3FF)
    val Violet = Color(0xFF8A5CFF)
    val Rating = Color(0xFFFFD166)
    val Line = Color(0x12FFFFFF)                   // rgba(255,255,255,.07)
    val Live = Color(0xFFFF3B5C)
    val ScrimPanel = Color(0xC705060C)             // rgba(5,6,12,.78)
    /** The colour-key legend: more solid than the spec's .78, so a row heading under it does not show through (BRAVIA). */
    val LegendPanel = Color(0xEB05060C)            // rgba(5,6,12,.92)
    val Dialog = Color(0xF20A0C16)                 // rgba(10,12,22,.95)
    val DialogBorder = Color(0x1FFFFFFF)
    val ChipBg = Color(0x800A0C16)                 // rgba(10,12,22,.5)
    val ChipBgStrong = Color(0x990A0C16)           // rgba(10,12,22,.6)
    val ChipRing = Color(0x24FFFFFF)               // inset 1px rgba(255,255,255,.14)
    val NavInactive = Color(0x99FFFFFF)            // rgba(255,255,255,.6)
    val PillInactive = Color(0xB8FFFFFF)           // rgba(255,255,255,.72)
    val SecondaryButton = Color(0x29FFFFFF)        // rgba(255,255,255,.16)
    val Synopsis = Color(0xDBF0F3FF)               // rgba(240,243,255,.86)
    val MetaText = Color(0xFFE9ECFF)
    val ProgressTrack = Color(0x38FFFFFF)          // rgba(255,255,255,.22)
    val Watched = Color(0xFF7FF0BB)
    val OnAccent = Color(0xFF02141C)
    val SourceLocal = Color(0xFF2FD3FF)
    val SourceIptv = Color(0xFFB49BFF)

    // remote colour keys (use these exact values everywhere)
    val KeyRed = Color(0xFFE5484D)
    val KeyGreen = Color(0xFF30A46C)
    val KeyYellow = Color(0xFFF5D90A)
    val KeyBlue = Color(0xFF3E63DD)

    val BrandGradient = Brush.horizontalGradient(listOf(Accent, Violet))
    val AvatarGradient = Brush.linearGradient(listOf(Accent, Violet))
}

/** Spacing (spec section 3), in spec pixels. */
object O5Space {
    val Side = 96.dp                // safe side margin: the content's left edge on every screen
    val TopBar = 120.dp
    val SubMenuTop = 118.dp
    val ContentUnderSubMenu = 204.dp
    val HeroTopHome = 196.dp
    val HeroTopSection = 214.dp
    val RowGapLandscape = 28.dp
    val RowGapPoster = 30.dp
    val RowHeadingGap = 20.dp
    val BetweenRows = 56.dp
}

/** Shapes. */
object O5Shape {
    val Card = RoundedCornerShape(10.dp)
    val Button = RoundedCornerShape(12.dp)
    val Pill = RoundedCornerShape(999.dp)
    val Dialog = RoundedCornerShape(20.dp)
}

/**
 * Type scale (spec section 2). The spec's Segoe UI Variable Display is not on Android TV and no font is bundled yet:
 * the system sans at the same weights is used.
 */
object O5Type {
    private val family = FontFamily.SansSerif
    private val tight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)
    val HeroTitle = TextStyle(fontFamily = family, fontSize = 112.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.035).em,
        lineHeight = 106.sp, lineHeightStyle = tight, color = O5.Text)
    val SectionHeroTitle = HeroTitle.copy(fontSize = 90.sp, lineHeight = 86.sp)
    val Eyebrow = TextStyle(fontFamily = family, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.14.em, color = O5.Muted)
    val Meta = TextStyle(fontFamily = family, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = O5.MetaText)
    val MetaPill = TextStyle(fontFamily = family, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = O5.MetaText)
    val Synopsis = TextStyle(fontFamily = family, fontSize = 28.sp, lineHeight = 40.sp, color = O5.Synopsis)
    val Button = TextStyle(fontFamily = family, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    val ButtonSection = Button.copy(fontSize = 26.sp)
    val Nav = TextStyle(fontFamily = family, fontSize = 27.sp, fontWeight = FontWeight.Bold)
    val Clock = TextStyle(fontFamily = family, fontSize = 32.sp, fontWeight = FontWeight.Light, fontFeatureSettings = "tnum", color = O5.Text)
    val SubPill = TextStyle(fontFamily = family, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    val Segment = TextStyle(fontFamily = family, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    val Dropdown = TextStyle(fontFamily = family, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = O5.MetaText)
    val RowHeading = TextStyle(fontFamily = family, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.01).em, color = O5.Text)
    val CardTitle = TextStyle(fontFamily = family, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = O5.Text)
    val CardMeta = TextStyle(fontFamily = family, fontSize = 20.sp, color = O5.Muted)
    val GridTitle = TextStyle(fontFamily = family, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = O5.Text)
    val GridMeta = TextStyle(fontFamily = family, fontSize = 17.sp, color = O5.Muted)
    val Legend = TextStyle(fontFamily = family, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color(0xDBFFFFFF))
    val Badge = TextStyle(fontFamily = family, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.06.em)
    val PickerTitle = TextStyle(fontFamily = family, fontSize = 72.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.03).em, color = O5.Text)
    val Body = TextStyle(fontFamily = family, fontSize = 24.sp, color = O5.Muted)
}

/** The density the app had before [OrbitCanvas] changed it (for [LegacyDensity]). */
val LocalBaseDensity = staticCompositionLocalOf<Density?> { null }

/** Spec width: the design is drawn for 1920 x 1080. */
const val SPEC_WIDTH = 1920f

/**
 * Lays out [content] in spec pixels: 1.dp = 1 px of the 1920 x 1080 design, scaled to the real screen width. Font
 * scale stays as the system has it.
 */
@Composable
fun OrbitCanvas(content: @Composable () -> Unit) {
    val base = LocalDensity.current
    if (LocalBaseDensity.current != null) { content(); return } // already inside one
    BoxWithConstraints {
        val widthPx = constraints.maxWidth.toFloat().takeIf { it > 0 && constraints.hasBoundedWidth } ?: (SPEC_WIDTH * base.density / 2f)
        val d = remember(widthPx, base.fontScale) { Density(widthPx / SPEC_WIDTH, base.fontScale) }
        CompositionLocalProvider(LocalBaseDensity provides base, LocalDensity provides d) { content() }
    }
}

/** Back to the device's own density inside an [OrbitCanvas]: for the legacy screens, written in real dp. */
@Composable
fun LegacyDensity(content: @Composable () -> Unit) {
    val base = LocalBaseDensity.current
    if (base == null) content() else CompositionLocalProvider(LocalDensity provides base, LocalBaseDensity provides null) { content() }
}
