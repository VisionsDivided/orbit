// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.orbit5

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The text of a hero block (spec section 6): eyebrow, title, meta line, synopsis. */
@Immutable
data class HeroData(
    val key: String,
    val title: String,
    val eyebrow: String = "",
    /** "iptv", "plex", "jellyfin" or "" (no badge). */
    val source: String = "",
    val year: String? = null,
    val rating: String? = null,
    /** "1h 58m" or "1h 02m left". */
    val runtime: String? = null,
    val genres: List<String> = emptyList(),
    val pills: List<String> = emptyList(),
    val synopsis: String? = null,
)

private val titleShadow = Shadow(Color(0x73000000), Offset(0f, 4f), 30f)

/**
 * Hero text; a new [data] cross-fades over 600 ms. The buttons are kept out of the cross-fade ([buttons] stays
 * composed, so focus on them survives a title change). [section]: the smaller title of section pages.
 */
@Composable
fun HeroBlock(data: HeroData?, modifier: Modifier = Modifier, section: Boolean = false, buttons: (@Composable RowScope.() -> Unit)? = null) {
    Column(modifier.widthIn(max = 1150.dp)) {
        Crossfade(data, animationSpec = tween(600), label = "hero") { d ->
            if (d != null) HeroText(d, section)
        }
        if (buttons != null) Row(Modifier.padding(top = 38.dp), horizontalArrangement = Arrangement.spacedBy(22.dp), content = buttons)
    }
}

@Composable
private fun HeroText(d: HeroData, section: Boolean) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (d.source.isNotEmpty()) SourceBadge(d.source)
            if (d.eyebrow.isNotEmpty()) Text(d.eyebrow.uppercase(), style = O5Type.Eyebrow, maxLines = 1)
        }
        Text(
            d.title, style = (if (section) O5Type.SectionHeroTitle else O5Type.HeroTitle).copy(shadow = titleShadow),
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 22.dp),
        )
        val parts = listOfNotNull(d.year, d.runtime, d.genres.take(2).joinToString(" · ").ifEmpty { null })
        if (parts.isNotEmpty() || d.rating != null || d.pills.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                d.year?.let { Text(it, style = O5Type.Meta, maxLines = 1) }
                d.rating?.let { Text("★ $it", style = O5Type.Meta, color = O5.Rating, maxLines = 1) }
                d.runtime?.let { Text(it, style = O5Type.Meta, maxLines = 1) }
                d.genres.take(2).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { Text(it, style = O5Type.Meta, maxLines = 1) }
                d.pills.forEach { MetaPill(it) }
            }
        }
        d.synopsis?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = O5Type.Synopsis, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 20.dp).widthIn(max = 1000.dp))
        }
    }
}
