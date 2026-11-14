// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.orbit5

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Phone 5b building blocks shared by the phone pages (Movies / Shows, the title page, Live TV, the player). Every
 * control has a touch target of at least [PhoneTouch]; the drawn shape can be smaller (mockup sizes).
 */

/** The smallest touch target on a phone (Material: 48 dp). */
val PhoneTouch = 48.dp

/** Phone colours of the mockup's `.phs` pieces. */
object PhoneColors {
    val Chip = Color(0x1AFFFFFF)          // .p-chip rgba(255,255,255,.1)
    val Segments = Color(0x14FFFFFF)      // .p-src rgba(255,255,255,.08)
    val Action = Color(0x1AFFFFFF)        // .p-act circle rgba(255,255,255,.1)
    val Sheet = Color(0xFF0D0F1B)         // .p-sheet
    val SheetScrim = Color(0x8C000000)    // rgba(0,0,0,.55)
    val SheetRow = Color(0x0FFFFFFF)      // rgba(255,255,255,.06)
    val Secondary = Color(0x24FFFFFF)     // .p-sec rgba(255,255,255,.14)
    val Round = Color(0x73000000)         // round icon buttons over artwork rgba(0,0,0,.45)
}

/**
 * A phone chip (mockup `.p-chip`: 13 px / 600, 7 x 13, pill; white with dark text when [on]). The chip itself is
 * about 34 dp high; the touch target around it is [PhoneTouch].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhoneChip(
    text: String, on: Boolean, modifier: Modifier = Modifier, icon: ImageVector? = null, trailing: ImageVector? = null,
    onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
) {
    Box(
        modifier.heightIn(min = PhoneTouch).clip(O5Shape.Pill).combinedClickable(onLongClick = onLongClick, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.background(if (on) Color.White else PhoneColors.Chip, O5Shape.Pill).padding(horizontal = 13.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            val fg = if (on) O5.Bg else Color.White
            if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(15.dp))
            Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
            if (trailing != null) Icon(trailing, null, tint = fg.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
        }
    }
}

/** A row of chips that scrolls sideways, 20 px from the screen edges, 8 px apart. */
@Composable
fun PhoneChipRow(modifier: Modifier = Modifier, start: Dp = 20.dp, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = start),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = content,
    )
}

/**
 * The phone segmented control (mockup `.p-src`: `All | Local | IPTV`): full width, 8 % white, radius 10, 3 px inside;
 * the selected segment white with dark text. Each segment is [PhoneTouch] high including the frame.
 */
@Composable
fun PhoneSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().background(PhoneColors.Segments, RoundedCornerShape(10.dp)).padding(3.dp)) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Box(
                Modifier.weight(1f).heightIn(min = PhoneTouch - 6.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (on) Color.White else Color.Transparent).clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { Text(o, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (on) O5.Bg else O5.Muted, maxLines = 1) }
        }
    }
}

/**
 * A round action with its label under it (mockup `.p-act`, the phone's colour keys: My list, Trailer, Watched,
 * More): a 44 px circle at 10 % white, 10.5 px muted label, 64 px wide. [on] rings the circle in the accent.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhoneAction(
    label: String, modifier: Modifier = Modifier, icon: ImageVector? = null, glyph: String? = null, tint: Color = Color.White,
    on: Boolean = false, enabled: Boolean = true, onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
) {
    Column(
        modifier.width(64.dp).clip(RoundedCornerShape(12.dp)).combinedClickable(enabled = enabled, onLongClick = onLongClick, onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier.size(44.dp).background(PhoneColors.Action, CircleShape).then(if (on) Modifier.border(1.5.dp, O5.Accent, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            val fg = if (enabled) tint else tint.copy(alpha = 0.4f)
            if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(22.dp))
            else Text(glyph ?: label.take(1), color = fg, fontWeight = FontWeight.ExtraBold, fontSize = if ((glyph?.length ?: 1) > 2) 11.sp else 15.sp, maxLines = 1)
        }
        Text(label, fontSize = 10.5.sp, color = O5.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** A round icon button over artwork or video (36 px, 45 % black) with a [PhoneTouch] target. */
@Composable
fun PhoneRoundButton(icon: ImageVector, description: String, modifier: Modifier = Modifier, size: Dp = 36.dp, onClick: () -> Unit) {
    Box(modifier.size(PhoneTouch).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size).background(PhoneColors.Round, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, description, tint = Color.White, modifier = Modifier.size(size * 0.6f))
        }
    }
}

/** The small source badge inside a phone poster ("Local" cyan, "IPTV" violet; mockup `.bd`). */
@Composable
fun PhoneSourceBadge(source: String, modifier: Modifier = Modifier) {
    val iptv = source == "iptv"
    Text(
        if (iptv) "IPTV" else "Local", modifier.background(Color(0xB805060C), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
        color = if (iptv) O5.SourceIptv else O5.SourceLocal, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1,
    )
}

/** A full-width phone button row; [primary] is the brand gradient (Play, Resume). Disabled draws it muted. */
@Composable
fun PhoneWideButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    if (enabled) PhoneButton(text, modifier.fillMaxWidth(), icon = icon, primary = primary, onClick = onClick)
    else Row(
        modifier.fillMaxWidth().heightIn(min = PhoneTouch).background(Color(0x1FFFFFFF), RoundedCornerShape(10.dp)).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, tint = Color(0x8CFFFFFF), modifier = Modifier.size(18.dp))
        Text(text, style = PhoneType.Button, color = Color(0x8CFFFFFF), maxLines = 1)
    }
}

/**
 * A bottom sheet (spec section 13, mockup `.p-sheet`): over a 55 % black scrim, #0d0f1b with 22 px top corners, a
 * drag handle, 18 px sides. A tap on the scrim calls [onDismiss]; the sheet keeps its own taps. Draws above the
 * system navigation bar.
 */
@Composable
fun PhoneSheet(onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val none = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxSize().background(PhoneColors.SheetScrim).clickable(interactionSource = none, indication = null, onClick = onDismiss)) {
        val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
        Column(
            modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp).fillMaxWidth()
                .background(PhoneColors.Sheet, shape).border(1.dp, Color(0x1AFFFFFF), shape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 26.dp + nav),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp).size(40.dp, 4.dp).background(Color(0x40FFFFFF), RoundedCornerShape(2.dp)))
            content()
        }
    }
}

/** A plain choice row in a [PhoneSheet] (6 % white, radius 12, [PhoneTouch] high at least). */
@Composable
fun PhoneSheetRow(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.fillMaxWidth().heightIn(min = PhoneTouch + 4.dp).clip(RoundedCornerShape(12.dp)).background(PhoneColors.SheetRow).clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text(text, color = O5.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/**
 * Lets an item of a padded lazy list / grid span [bleed] past both sides (a chip row inside a grid with 20 px
 * content padding still scrolls from the screen edge).
 */
fun Modifier.bleed(bleed: Dp): Modifier = layout { measurable, constraints ->
    val extra = bleed.roundToPx()
    val p = measurable.measure(constraints.copy(minWidth = constraints.minWidth + 2 * extra, maxWidth = constraints.maxWidth + 2 * extra))
    layout(constraints.maxWidth, p.height) { p.place(-extra, 0) }
}

/** Fades the bottom of a box into the page background (backdrop edges on phone pages). */
@Composable
fun BoxScope.PhoneBottomFade(height: Dp = 160.dp) {
    Box(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(height)
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, O5.Bg))),
    )
}
