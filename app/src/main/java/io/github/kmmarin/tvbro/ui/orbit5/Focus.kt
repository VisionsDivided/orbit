// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.orbit5

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.kmmarin.tvbro.ui.FocusMemory
import io.github.kmmarin.tvbro.ui.LocalInRail
import io.github.kmmarin.tvbro.ui.SafeFocus
import io.github.kmmarin.tvbro.ui.safeFocus

/**
 * The direction-5 focus state for cards and buttons (spec section 7): a 5 px white outline 5 px outside the item,
 * scale 1.07 over 250 ms, a deep shadow, drawn above its neighbours.
 *
 * Scale and outline are applied in the draw phase from an animated value, so moving focus redraws two items and
 * recomposes nothing else. Like the legacy `tvFocus`, the item is remembered in [FocusMemory] (focus comes back to
 * it after a menu closes, and Down from the top bar returns to it). Give [focus] to steer it from outside
 * (a [SafeFocus] the screen requests); [onFocus] runs when it gains focus.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.o5Focus(
    shape: Shape = O5Shape.Card,
    radius: Dp = 10.dp,
    focus: SafeFocus? = null,
    scaleTo: Float = 1.07f,
    outline: Boolean = true,
    onFocus: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    val me = remember { SafeFocus() }
    val was = remember { booleanArrayOf(false) }
    val inRail = LocalInRail.current
    SideEffect {
        if (f != was[0]) {
            was[0] = f
            if (f) { FocusMemory.gained(); FocusMemory.focused(focus ?: me, inRail); onFocus?.invoke() } else FocusMemory.lost()
        }
    }
    DisposableEffect(Unit) { onDispose { if (was[0]) { was[0] = false; FocusMemory.lost() } } }
    val p by animateFloatAsState(if (f) 1f else 0f, tween(250), label = "o5focus")
    this
        .zIndex(if (f) 2f else 0f)
        .graphicsLayer {
            val s = 1f + (scaleTo - 1f) * p
            scaleX = s; scaleY = s
            shadowElevation = 30.dp.toPx() * p
            this.shape = shape
            clip = false
            spotShadowColor = Color.Black
            ambientShadowColor = Color.Black
        }
        .drawWithContent {
            drawContent()
            if (outline && p > 0.01f) {
                val w = 5.dp.toPx(); val gap = 5.dp.toPx()
                val o = gap + w / 2
                drawRoundRect(
                    Color.White.copy(alpha = p), topLeft = Offset(-o, -o), size = Size(size.width + 2 * o, size.height + 2 * o),
                    cornerRadius = CornerRadius(radius.toPx() + o), style = Stroke(w),
                )
            }
        }
        .safeFocus(focus ?: me)
        // the remote's Menu key opens the same menu as a long press of OK
        .onPreviewKeyEvent { e -> if (onLongClick != null && e.type == KeyEventType.KeyDown && e.key == Key.Menu) { onLongClick(); true } else false }
        .combinedClickable(interactionSource = src, indication = null, onLongClick = onLongClick, onClick = onClick)
}
