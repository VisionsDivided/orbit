// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.orbit5

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale

/**
 * Decode widths for images, in real pixels. Images are decoded at about the size they are drawn (Logos.load samples
 * down by powers of two), so a row of posters stays a few MB on a 1 GB TV.
 */
object ImgSize {
    const val POSTER = 200
    const val LANDSCAPE = 400
    const val BACKDROP = 960
    const val AVATAR = 160
}

/** A remote image loaded off the main thread at about [target] px wide; [placeholder] until it is there. */
@Composable
fun NetImage(
    url: String?, target: Int, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center, placeholder: @Composable () -> Unit = {},
) {
    val img = rememberNetImage(url, target)
    Box(modifier) {
        val b = img
        if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = contentScale, alignment = alignment)
        else placeholder()
    }
}

/** The image at [url] decoded at about [target] px wide, or null while loading / when it fails. */
@Composable
fun rememberNetImage(url: String?, target: Int): ImageBitmap? {
    val img by produceState<ImageBitmap?>(null, url, target) {
        value = if (url.isNullOrEmpty()) null else io.github.kmmarin.tvbro.ui.Logos.load(url, target)?.asImageBitmap()
    }
    return img
}
