// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.orbit5

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.delay

/** What a page's backdrop shows: the focused title's artwork and, when the catalog has them, its two colours. */
@Immutable
data class BackdropSpec(val image: String?, val colors: List<String>? = null)

/** One resolved backdrop layer (image decoded, colours known), so a cross-fade never shows a half-loaded layer. */
@Immutable
private data class Layer(val image: ImageBitmap?, val colors: MeshColors)

/**
 * The colour-matched backdrop of every direction-5 page (spec section 6): mesh in the title's colours, the artwork
 * top right fading into it, then the vignette. A new [spec] waits [settleMs] (focus moving quickly through a row
 * changes nothing), loads, then cross-fades the whole layer over 900 ms. No blur.
 *
 * [spec] null shows the Orbit mesh alone. [dim] is the stronger bottom scrim of text-heavy pages (the guide).
 */
@Composable
fun CinematicBackdrop(spec: BackdropSpec?, modifier: Modifier = Modifier, dim: Boolean = false, settleMs: Long = 250, empty: MeshColors = MeshColors.Orbit) {
    var shown by remember { mutableStateOf<Layer?>(null) }
    LaunchedEffect(spec) {
        if (shown != null) delay(settleMs)
        val img = spec?.image?.takeIf { it.isNotEmpty() }?.let { io.github.kmmarin.tvbro.ui.Logos.load(it, ImgSize.BACKDROP)?.asImageBitmap() }
        val colors = TitleColors.of(spec?.image, spec?.colors) ?: if (spec?.image == null) empty else MeshColors.Neutral
        shown = Layer(img, colors)
    }
    Box(modifier.background(O5.Bg)) {
        Crossfade(shown, animationSpec = tween(900, easing = FastOutSlowInEasing), label = "backdrop") { l ->
            if (l != null) BackdropLayer(l.image, l.colors)
        }
        Box(Modifier.fillMaxSize().vignette(dim))
    }
}

/** A resolved backdrop without loading or cross-fade (previews, tests): mesh, artwork and vignette. */
@Composable
fun BackdropStill(image: ImageBitmap?, colors: MeshColors, modifier: Modifier = Modifier, dim: Boolean = false) {
    Box(modifier.background(O5.Bg)) {
        BackdropLayer(image, colors)
        Box(Modifier.fillMaxSize().vignette(dim))
    }
}

@Composable
private fun BackdropLayer(image: ImageBitmap?, colors: MeshColors) {
    Box(Modifier.fillMaxSize().mesh(colors)) {
        if (image != null) {
            Image(
                image, null, contentScale = ContentScale.Crop, alignment = Alignment.TopEnd,
                modifier = Modifier.align(Alignment.TopEnd).fillMaxWidth(0.74f).fillMaxHeight(0.84f).artMask(),
            )
        }
    }
}

/** Draws an elliptical radial gradient (CSS `radial-gradient(rx ry at cx cy, color, transparent stop)`). */
private fun DrawScope.ellipse(rx: Float, ry: Float, cx: Float, cy: Float, brush: Brush) {
    withTransform({ scale(1f, ry / rx, Offset(cx, cy)) }) {
        drawRect(brush, topLeft = Offset(cx - rx, cy - rx), size = Size(2 * rx, 2 * rx))
    }
}

private fun radial(c: Color, alpha: Float, cx: Float, cy: Float, r: Float, stop: Float) =
    Brush.radialGradient(0f to c.copy(alpha = alpha), stop to c.copy(alpha = 0f), center = Offset(cx, cy), radius = r)

/** The four-gradient mesh of spec section 6, bottom layer first. Brushes are built once per size and colour pair. */
fun Modifier.mesh(c: MeshColors): Modifier = drawWithCache {
    val w = size.width; val h = size.height
    val b4 = radial(c.m2, 0.16f, w, h, 0.60f * w, 0.70f)
    val b3 = radial(c.m1, 0.40f, 0f, 0f, 0.60f * w, 0.70f)
    val b2 = radial(c.m2, 0.22f, 0f, h, 0.55f * w, 0.72f)
    val b1 = radial(c.m1, 0.88f, w, 0f, 0.72f * w, 0.70f)
    onDrawBehind {
        drawRect(c.mb)
        ellipse(0.60f * w, 0.60f * h, w, h, b4)
        ellipse(0.60f * w, 0.55f * h, 0f, 0f, b3)
        ellipse(0.55f * w, 0.65f * h, 0f, h, b2)
        ellipse(0.72f * w, 0.82f * h, w, 0f, b1)
    }
}

/**
 * The artwork's mask: two linear fades, no ellipse and no rounded edge (owner feedback 2026-11-14: the curved cut
 * looked hard). The left edge fades in over 40% of the width, the bottom fades out over the last 35% of the height.
 * One saved layer holds the artwork while the fades cut its alpha (a canvas layer, not a graphics-layer node).
 */
private fun Modifier.artMask(): Modifier = drawWithCache {
    val left = Brush.horizontalGradient(0f to Color.Transparent, 0.40f to Color.Black, startX = 0f, endX = size.width)
    val bottom = Brush.verticalGradient(0.65f to Color.Black, 1f to Color.Transparent, startY = 0f, endY = size.height)
    val bounds = androidx.compose.ui.geometry.Rect(Offset.Zero, size)
    onDrawWithContent {
        drawIntoCanvas { it.saveLayer(bounds, androidx.compose.ui.graphics.Paint()) }
        drawContent()
        drawRect(left, blendMode = BlendMode.DstIn)
        drawRect(bottom, blendMode = BlendMode.DstIn)
        drawIntoCanvas { it.restore() }
    }
}

private val vigLeft = Brush.horizontalGradient(0f to Color(0x80000000), 0.45f to Color(0x29000000), 0.70f to Color.Transparent)
private val vigLeftDim = Brush.horizontalGradient(0f to Color(0x80000000), 0.60f to Color.Transparent)

/** Left and bottom vignette plus the top-bar scrim (spec section 6, layer 3). */
fun Modifier.vignette(dim: Boolean = false): Modifier = drawWithCache {
    val h = size.height
    val bottom = if (dim) Brush.verticalGradient(0.28f to Color.Transparent, 0.52f to Color(0xB804050A), 1f to Color(0xE604050A), startY = 0f, endY = h)
    else Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0x8C000000), startY = 0f, endY = h)
    val top = topScrim(h)
    onDrawBehind {
        drawRect(if (dim) vigLeftDim else vigLeft)
        drawRect(bottom)
        drawRect(top)
    }
}

/** `linear-gradient(180deg, rgba(0,0,0,.5), transparent 16%)`: keeps the top bar legible on any artwork. */
fun topScrim(h: Float): Brush = Brush.verticalGradient(0f to Color(0x80000000), 0.16f to Color.Transparent, startY = 0f, endY = h)
