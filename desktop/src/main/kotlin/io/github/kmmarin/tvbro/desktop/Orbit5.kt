// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Shared direction-5 pieces of the desktop (DESIGN-SPEC-5): the colour keys and their legend (§9), Settings rows,
 * toggles and segmented controls (§12), the LIVE pill and the guide's tags.
 */

/** The remote's colour keys (§1, exact values). */
object KeyColors {
    val red = Color(0xFFE5484D)
    val green = Color(0xFF30A46C)
    val yellow = Color(0xFFF5D90A)
    val blue = Color(0xFF3E63DD)
}

/** One colour key: its label in the legend and what it does. */
class ColorKey(val label: String, val act: () -> Unit)

/**
 * A screen's colour keys. On the desktop they are F1 (red), F2 (green), F3 (yellow) and F4 (blue), or a click on the
 * legend. [hints] follow after a divider ("Enter" to "Open").
 */
class ColorKeys(val red: ColorKey? = null, val green: ColorKey? = null, val yellow: ColorKey? = null, val blue: ColorKey? = null,
                val hints: List<Pair<String, String>> = emptyList()) {
    val all: List<Triple<Color, String, ColorKey>> get() = listOfNotNull(red?.let { Triple(KeyColors.red, "F1", it) }, green?.let { Triple(KeyColors.green, "F2", it) },
        yellow?.let { Triple(KeyColors.yellow, "F3", it) }, blue?.let { Triple(KeyColors.blue, "F4", it) })

    /** The key's action for [k] (F1 to F4), or null. */
    fun forKey(k: Key): ColorKey? = when (k) { Key.F1 -> red; Key.F2 -> green; Key.F3 -> yellow; Key.F4 -> blue; else -> null }
}

/** A click (or press) with the primary button runs [f]; no focus is taken (the legend never steals focus). */
private fun Modifier.click(f: () -> Unit) = pointerInput(f) {
    awaitPointerEventScope { while (true) { val e = awaitPointerEvent(); if (e.type == PointerEventType.Press) f() } }
}

/** The colour-key legend (§9): a dark pill at the bottom left; each key a coloured pill (with its F key) and a label. */
@Composable
fun KeyLegend(keys: ColorKeys, modifier: Modifier = Modifier) {
    Row(modifier.background(Color(0xC705060C), CircleShape).border(1.dp, Color(0x1AFFFFFF), CircleShape).padding(17.dp, 7.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((c, _, k) in keys.all) Row(Modifier.click(k.act), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(25.dp, 11.dp).background(c, RoundedCornerShape(6.dp)))
            Text2(k.label, 13, Color(0xDBFFFFFF), FontWeight.SemiBold)
        }
        val hints = keys.hints + if (keys.all.isNotEmpty()) listOf("F1–F4" to "Colour keys") else emptyList()
        if (hints.isNotEmpty()) {
            if (keys.all.isNotEmpty()) Box(Modifier.width(1.dp).height(16.dp).background(Color(0x2EFFFFFF)))
            for ((b, l) in hints) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(4.dp)).padding(4.dp, 0.dp)) { Text2(b, 10, Color.White, FontWeight.Bold) }
                Text2(l, 13, Color(0x8CFFFFFF), FontWeight.SemiBold)
            }
        }
    }
}

/** The LIVE pill: red, a white dot, bold spaced letters. */
@Composable
fun LivePill(small: Boolean = false) = Row(Modifier.background(O5.live, RoundedCornerShape(4.dp)).padding(if (small) 5.dp else 7.dp, 2.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(if (small) 4.dp else 5.dp).background(Color.White, CircleShape))
    androidx.compose.material3.Text("LIVE", color = Color.White, fontSize = androidx.compose.ui.unit.TextUnit(if (small) 9f else 11f, androidx.compose.ui.unit.TextUnitType.Sp),
        fontWeight = FontWeight.ExtraBold, letterSpacing = androidx.compose.ui.unit.TextUnit(1f, androidx.compose.ui.unit.TextUnitType.Sp))
}

/** A channel's own stream format (§9 guide indicators): a small yellow tag. */
@Composable
fun FormatTag(fmt: String) = Box(Modifier.background(Color(0x2EF5D90A), RoundedCornerShape(3.dp)).border(1.dp, Color(0x80F5D90A), RoundedCornerShape(3.dp)).padding(4.dp, 1.dp)) {
    Text2(fmt.uppercase(), 9, Color(0xFFFFE55C), FontWeight.ExtraBold)
}

/** The toggle (§12): an accent pill with a white knob on the right when on, dim with the knob left when off. */
@Composable
fun Toggle(on: Boolean) = Box(Modifier.size(44.dp, 24.dp).background(if (on) O5.accent else Color(0x33FFFFFF), CircleShape).padding(3.dp),
    contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) { Box(Modifier.size(18.dp).background(Color.White, CircleShape)) }

/**
 * A Settings row (§12): label and a muted description on the left, [trailing] on the right. Rounded, translucent; the
 * focused row gets a white ring. Enter or a click runs [onClick]; [onLeftRight] gets −1 / +1 for Left / Right.
 */
@Composable
fun SRow(label: String, desc: String? = null, requester: FocusRequester? = null, onLeftRight: ((Int) -> Unit)? = null, onMenu: (() -> Unit)? = null,
         onClick: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().onFocusChanged { focused = it.hasFocus }
        .then(if (onLeftRight != null) Modifier.onLeftRight(onLeftRight) else Modifier)
        .tvItem(requester = requester, onMenu = onMenu, onClick = onClick)
        .background(if (focused) Color(0x1FFFFFFF) else O5.chip, RoundedCornerShape(11.dp))
        .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else Color(0x14FFFFFF), RoundedCornerShape(11.dp))
        .padding(18.dp, 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f)) {
            Text2(label, 16, O5.text, FontWeight.SemiBold, maxLines = 2)
            if (!desc.isNullOrBlank()) Text2(desc, 12, O5.muted, maxLines = 3)
        }
        trailing()
    }
}

/** Left / Right on a row (Settings' segmented controls): handled here, so focus stays on the row. */
private fun Modifier.onLeftRight(f: (Int) -> Unit) = onPreviewKeyEvent { e ->
    if (e.type != KeyEventType.KeyDown) false
    else when (e.key) { Key.DirectionLeft -> { f(-1); true }; Key.DirectionRight -> { f(1); true }; else -> false }
}

/** A value at the end of a row: muted text and a chevron. */
@Composable
fun RowValue(text: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Text2(text, 14, O5.muted, maxLines = 1)
    Text2("›", 18, O5.muted, FontWeight.Bold)
}

/** A static segmented control (the row handles keys and clicks): the selected segment has the accent background. */
@Composable
fun SegmentView(options: List<String>, selected: Int, onPick: (Int) -> Unit) =
    Row(Modifier.background(Color(0x14FFFFFF), CircleShape).padding(3.dp)) {
        options.forEachIndexed { i, s ->
            Box(Modifier.click { onPick(i) }.background(if (i == selected) O5.accent else Color.Transparent, CircleShape).padding(14.dp, 5.dp)) {
                Text2(s, 13, if (i == selected) Color(0xFF02141C) else O5.muted, if (i == selected) FontWeight.Bold else FontWeight.SemiBold)
            }
        }
    }

/** A page heading under the sub-menu (§12): title with a muted line after it. */
@Composable
fun PageHead(title: String, sub: String? = null) = Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp),
    modifier = Modifier.padding(bottom = 12.dp)) {
    Text2(title, 36, Color.White, FontWeight.ExtraBold)
    if (sub != null) Text2(sub, 14, O5.muted, modifier = Modifier.padding(bottom = 6.dp))
}

/** A muted section label inside a Settings page. */
@Composable
fun SHead(s: String) = androidx.compose.material3.Text(s.uppercase(), color = O5.muted, fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp),
    fontWeight = FontWeight.Bold, letterSpacing = androidx.compose.ui.unit.TextUnit(1.5f, androidx.compose.ui.unit.TextUnitType.Sp),
    modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
