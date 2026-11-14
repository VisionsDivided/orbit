// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.orbit5

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A title's colour pair for the colour-matched backdrop (spec section 6): [m1] main, [m2] secondary, [mb] the base
 * (darkened from m1).
 */
@Immutable
data class MeshColors(val m1: Color, val m2: Color, val mb: Color = darkBase(m1)) {
    companion object {
        /** The Orbit brand pair, used where there is no title (Who's watching?, empty pages). */
        val Orbit = MeshColors(Color(0xFF6B45E6), Color(0xFF2FD3FF), Color(0xFF06061A))
        /** Neutral pair while a title's colours are still unknown. */
        val Neutral = MeshColors(Color(0xFF2C3F86), Color(0xFFD8F3FF), Color(0xFF05060C))

        /** "#c3261b" / "c3261b" -> Color, null when unreadable. */
        fun parse(hex: String?): Color? {
            val h = hex?.trim()?.removePrefix("#") ?: return null
            if (h.length != 6 && h.length != 8) return null
            val v = h.toLongOrNull(16) ?: return null
            return if (h.length == 6) Color(0xFF000000 or v) else Color(v)
        }

        /** From the catalog's `colors: [c1, c2]`; null when absent or unreadable. */
        fun fromCatalog(colors: List<String>?): MeshColors? {
            val l = colors ?: return null
            val c1 = parse(l.getOrNull(0)) ?: return null
            val c2 = parse(l.getOrNull(1)) ?: c1
            return MeshColors(c1, c2)
        }

        /** The base under the mesh: m1 at about a tenth of its brightness (#c3261b -> #110304 in the mockup). */
        fun darkBase(m1: Color): Color = Color(red = m1.red * 0.09f, green = m1.green * 0.09f, blue = m1.blue * 0.09f, alpha = 1f)
    }
}

/**
 * Colours of a title: the catalog's when it sends them, else two colours worked out from the backdrop with
 * androidx.palette (dominant / vibrant and a secondary), cached by image address for the app's lifetime.
 */
object TitleColors {
    private val cache = LruCache<String, MeshColors>(400)

    fun cached(url: String?): MeshColors? = url?.let { cache.get(it) }

    /** The pair for [url] (loads a small copy of the image when needed); null when the image cannot be loaded. */
    suspend fun of(url: String?, catalog: List<String>? = null): MeshColors? {
        MeshColors.fromCatalog(catalog)?.let { return it }
        if (url.isNullOrEmpty()) return null
        cache.get(url)?.let { return it }
        val bmp = io.github.kmmarin.tvbro.ui.Logos.load(url, 160) ?: return null
        val c = withContext(Dispatchers.Default) { fromBitmap(bmp) } ?: return null
        cache.put(url, c)
        return c
    }

    /** Main and secondary colour of [bmp]: the most vibrant swatch (else the dominant one), then a contrasting one. */
    fun fromBitmap(bmp: Bitmap): MeshColors? = try {
        val p = androidx.palette.graphics.Palette.from(bmp).maximumColorCount(16).resizeBitmapArea(160 * 90).generate()
        val main = p.vibrantSwatch ?: p.darkVibrantSwatch ?: p.dominantSwatch
        val second = listOfNotNull(p.lightVibrantSwatch, p.lightMutedSwatch, p.mutedSwatch, p.darkMutedSwatch, p.dominantSwatch)
            .firstOrNull { it.rgb != main?.rgb }
        if (main == null) null else MeshColors(tame(Color(main.rgb)), Color(second?.rgb ?: main.rgb))
    } catch (e: Exception) { null }

    /** Very bright main colours wash out the white text over them: cap the brightness a little. */
    private fun tame(c: Color): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(c.toArgb(), hsv)
        if (hsv[2] <= 0.8f) return c
        hsv[2] = 0.8f
        return Color(android.graphics.Color.HSVToColor(hsv))
    }
}
