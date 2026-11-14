// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.orbit5

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import io.github.kmmarin.tvbro.R

/*
 * Phone 5b (spec section 13). Phone sizes are CSS px of a 390 x 844 viewport, written here as dp (the phone's own
 * density: no OrbitCanvas on phones).
 */

/** Phone type scale (spec section 2, "Phone"). */
object PhoneType {
    private val family = FontFamily.SansSerif
    val H1 = TextStyle(fontFamily = family, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em, color = O5.Text)
    val HeroTitle = TextStyle(fontFamily = family, fontSize = 40.sp, lineHeight = 40.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.035).em, color = O5.Text)
    val Eyebrow = TextStyle(fontFamily = family, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.14.em, color = O5.Muted)
    val Meta = TextStyle(fontFamily = family, fontSize = 12.5.sp, color = O5.Muted)
    val RowHeading = TextStyle(fontFamily = family, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = O5.Text)
    val Button = TextStyle(fontFamily = family, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    val CardTitle = TextStyle(fontFamily = family, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = O5.Text)
    val CardMeta = TextStyle(fontFamily = family, fontSize = 11.sp, color = O5.Muted)
    val Nav = TextStyle(fontFamily = family, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    val Body = TextStyle(fontFamily = family, fontSize = 15.sp, lineHeight = 21.sp, color = O5.Muted)
}

/**
 * Phone primary / secondary button (spec section 13): primary (Play, Resume) = the brand gradient with white text and
 * a violet shadow; secondary = 14 % white. 15 px bold, 11 x 18, radius 10; at least 48 dp high for touch.
 */
@Composable
fun PhoneButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, primary: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .then(if (primary) Modifier.shadow(14.dp, shape, ambientColor = Color(0x738A5CFF), spotColor = Color(0x738A5CFF)) else Modifier)
            .background(if (primary) O5.BrandGradient else SolidColor(Color(0x24FFFFFF)), shape)
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = PhoneTouch)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text(text, style = PhoneType.Button, color = Color.White, maxLines = 1)
    }
}

/** Material "explore" (a compass), for the Discover tab. */
val CompassIcon: ImageVector by lazy {
    ImageVector.Builder("compass", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(PathParser().parsePathString(
            "M12,10.9c-0.61,0 -1.1,0.49 -1.1,1.1s0.49,1.1 1.1,1.1c0.61,0 1.1,-0.49 1.1,-1.1s-0.49,-1.1 -1.1,-1.1z" +
                "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z" +
                "M14.19,14.19L6,18l3.81,-8.19L18,6l-3.81,8.19z",
        ).toNodes(), fill = SolidColor(Color.White))
    }.build()
}

/** The phone bottom bar's tabs (spec section 13): the top bar's five sections, "Live TV" shortened to "Live". */
enum class PhoneTab(val nav: NavTab, val label: String) {
    HOME(NavTab.HOME, "Home"), MOVIES(NavTab.MOVIES, "Movies"), SHOWS(NavTab.SHOWS, "Shows"), LIVE(NavTab.LIVE, "Live"), DISCOVER(NavTab.DISCOVER, "Discover"),
}

/** Height of [PhoneBottomBar] above the system navigation bar (80 px including its 18 px bottom inset). */
val PhoneBarHeight = 80.dp

/**
 * The phone bottom bar (spec section 13, #3 style): full width, five evenly spaced tabs with the label under each
 * icon; the active tab sits in a lit pill (cyan-to-violet, violet glow, thin ring). Draws its own gradient background;
 * add the navigation-bar inset below it with [bottomInset].
 */
@Composable
fun PhoneBottomBar(active: NavTab?, onTab: (NavTab) -> Unit, modifier: Modifier = Modifier, bottomInset: Dp = 0.dp) {
    Row(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color(0x0005060C), 0.4f to Color(0xF505060C), 1f to Color(0xF505060C)))
            .padding(bottom = 18.dp + bottomInset).height(PhoneBarHeight - 18.dp).testTag("phone-bar"),
        horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically,
    ) {
        for (t in PhoneTab.values()) PhoneTabItem(t, t.nav == active) { onTab(t.nav) }
    }
}

@Composable
private fun PhoneTabItem(t: PhoneTab, on: Boolean, onClick: () -> Unit) {
    val tint = if (on) Color.White else Color(0x80FFFFFF)
    val pill = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .then(if (on) Modifier.drawBehind {
                // glow 0 0 18px rgba(138,92,255,.5) without blur: a few wider, fainter rounded rects
                for ((g, a) in listOf(12f to 0.07f, 8f to 0.10f, 4f to 0.16f)) {
                    val d = g.dp.toPx()
                    drawRoundRect(Color(0xFF8A5CFF).copy(alpha = a), Offset(-d, -d), Size(size.width + 2 * d, size.height + 2 * d), CornerRadius(16.dp.toPx() + d))
                }
            }.background(Brush.horizontalGradient(listOf(Color(0x592FD3FF), Color(0x738A5CFF))), pill).border(1.dp, Color(0x73AA96FF), pill) else Modifier)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .defaultMinSize(minWidth = 62.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("phone-tab-${t.label}"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        val m = Modifier.size(23.dp)
        when (t) {
            PhoneTab.HOME -> Icon(Icons.Filled.Home, null, tint = tint, modifier = m)
            PhoneTab.MOVIES -> Icon(painterResource(R.drawable.ic_nav_movie), null, tint = tint, modifier = m)
            PhoneTab.SHOWS -> Icon(painterResource(R.drawable.ic_nav_shows), null, tint = tint, modifier = m)
            PhoneTab.LIVE -> Icon(painterResource(R.drawable.ic_nav_tv), null, tint = tint, modifier = m)
            PhoneTab.DISCOVER -> Icon(CompassIcon, null, tint = tint, modifier = m)
        }
        Text(t.label, style = PhoneType.Nav, color = tint, maxLines = 1)
    }
}

/**
 * Phone header (spec section 13): the planet logo (40 px) or a page title (28 px / 800) on the left, search (22 px)
 * and the avatar (32 px) on the right. 20 px side margins; touch targets at least 48 dp.
 */
@Composable
fun PhoneHeader(title: String?, profileName: String, onSearch: () -> Unit, onAvatar: () -> Unit, modifier: Modifier = Modifier, settingsActive: Boolean = false) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        if (title == null) Image(painterResource(R.drawable.orbit_planet), "Orbit", Modifier.width(40.dp).height(30.dp))
        else Text(title, style = PhoneType.H1, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(PhoneTouch).clickable(onClick = onSearch).testTag("phone-search"), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Search, "Search", tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Box(Modifier.size(PhoneTouch).clickable(onClick = onAvatar).testTag("phone-avatar"), contentAlignment = Alignment.Center) {
            Box(if (settingsActive) Modifier.drawBehind { drawCircle(Color.White, size.minDimension / 2 + 4.dp.toPx()); drawCircle(O5.Bg, size.minDimension / 2 + 2.dp.toPx()) } else Modifier) {
                Avatar(profileName, 32.dp)
            }
        }
    }
}

/** A phone row heading (17 px / 700) with 20 px left margin. */
@Composable
fun PhoneRowHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, style = PhoneType.RowHeading, maxLines = 1, modifier = modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp))
}

/**
 * Phone landscape card (Continue watching: 200 x 112, radius 8) with the caption under it (12.5 / 11 px) and a 3 px
 * progress bar inside. Tap opens, long press the menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhoneLandscapeCard(
    image: String?, title: String, meta: String?, modifier: Modifier = Modifier, progress: Float? = null,
    width: Dp = 200.dp, height: Dp = 112.dp, contentScale: ContentScale = ContentScale.Crop,
    below: (@Composable () -> Unit)? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
) {
    Column(modifier.width(width)) {
        Box(Modifier.size(width, height).clip(RoundedCornerShape(8.dp)).background(Color(0xFF111111)).combinedClickable(onLongClick = onLongClick, onClick = onClick)) {
            NetImage(image, ImgSize.LANDSCAPE, Modifier.fillMaxSize(), contentScale) { PhoneNoArt(title) }
            progress?.let { ProgressBar(it, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 7.dp), height = 3.dp) }
        }
        Text(title, style = PhoneType.CardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        meta?.let { Text(it, style = PhoneType.CardMeta, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        below?.invoke()
    }
}

/** Phone poster card (108 x 162, radius 8; the 3-column grid size) with a one-line caption. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhonePosterCard(
    image: String?, title: String, meta: String?, modifier: Modifier = Modifier, width: Dp = 108.dp, height: Dp = 162.dp,
    below: (@Composable () -> Unit)? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
) {
    Column(modifier.width(width)) {
        Box(Modifier.size(width, height).clip(RoundedCornerShape(8.dp)).background(Color(0xFF111111)).combinedClickable(onLongClick = onLongClick, onClick = onClick)) {
            NetImage(image, ImgSize.POSTER, Modifier.fillMaxSize(), ContentScale.Crop) { PhoneNoArt(title) }
        }
        Text(title, style = PhoneType.CardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        meta?.let { Text(it, style = PhoneType.CardMeta, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        below?.invoke()
    }
}

@Composable
private fun PhoneNoArt(title: String) {
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1B2133), Color(0xFF0B0E18)))), contentAlignment = Alignment.BottomCenter) {
        Text(title.uppercase(), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, maxLines = 3,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp))
    }
}