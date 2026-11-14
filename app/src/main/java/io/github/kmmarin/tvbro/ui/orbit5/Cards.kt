// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.orbit5

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kmmarin.tvbro.ui.SafeFocus

/** Card sizes (spec section 7), in spec pixels. */
object CardSize {
    val LandscapeHomeW = 380.dp; val LandscapeHomeH = 214.dp
    val LandscapeSectionW = 340.dp; val LandscapeSectionH = 191.dp
    val EpisodeW = 330.dp; val EpisodeH = 186.dp
    val GridPosterW = 180.dp; val GridPosterH = 270.dp
    val CollectionPosterW = 184.dp; val CollectionPosterH = 276.dp
    val RowPosterW = 166.dp; val RowPosterH = 249.dp
}

/** What a card shows besides its picture; every field optional. */
data class CardInfo(
    val title: String,
    val meta: String? = null,
    /** Badges top left ("iptv", "plex" ...). */
    val sources: List<String> = emptyList(),
    val watched: Boolean = false,
    /** 0..1, null = no bar. */
    val progress: Float? = null,
    /** Collection order number, top left. */
    val number: Int? = null,
)

/** Placeholder art: the title on a dark gradient (no artwork, or still loading). */
@Composable
private fun BoxScope.NoArt(title: String, poster: Boolean) {
    Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(Color(0xFF1B2133), Color(0xFF0B0E18)))), contentAlignment = Alignment.BottomCenter) {
        Text(
            title.uppercase(), color = Color.White, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, maxLines = 3,
            overflow = TextOverflow.Ellipsis, fontSize = if (poster) 16.sp else 22.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 14.dp),
        )
    }
}

/**
 * Poster card (2:3). With [caption] the title and meta sit under it (grid captions 20 / 17 px). Focus: the direction-5
 * outline, scale and shadow ([o5Focus]); [onFocus] runs when it gains focus (hero / backdrop follow focus there).
 */
@Composable
fun PosterCard(
    image: String?, info: CardInfo, modifier: Modifier = Modifier,
    width: Dp = CardSize.GridPosterW, height: Dp = CardSize.GridPosterH, caption: Boolean = true,
    focus: SafeFocus? = null, onFocus: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
) {
    Column(modifier.width(width)) {
        Box(Modifier.size(width, height).o5Focus(focus = focus, onFocus = onFocus, onLongClick = onLongClick, onClick = onClick).clip(O5Shape.Card).background(Color(0xFF111111))) {
            NetImage(image, ImgSize.POSTER, Modifier.fillMaxSize(), ContentScale.Crop) { Box(Modifier.fillMaxSize()) { NoArt(info.title, true) } }
            CardOverlays(info, poster = true)
        }
        if (caption) {
            Text(info.title, style = O5Type.GridTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
            info.meta?.let { Text(it, style = O5Type.GridMeta, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
        }
    }
}

/** Landscape card (16:9): Continue watching, episodes, channels. Caption 24 / 20 px under it. */
@Composable
fun LandscapeCard(
    image: String?, info: CardInfo, modifier: Modifier = Modifier,
    width: Dp = CardSize.LandscapeHomeW, height: Dp = CardSize.LandscapeHomeH, caption: Boolean = true,
    contentScale: ContentScale = ContentScale.Crop, focus: SafeFocus? = null, onFocus: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
) {
    Column(modifier.width(width)) {
        Box(Modifier.size(width, height).o5Focus(focus = focus, onFocus = onFocus, onLongClick = onLongClick, onClick = onClick).clip(O5Shape.Card).background(Color(0xFF111111))) {
            NetImage(image, ImgSize.LANDSCAPE, Modifier.fillMaxSize(), contentScale) { Box(Modifier.fillMaxSize()) { NoArt(info.title, false) } }
            CardOverlays(info, poster = false)
        }
        if (caption) {
            Text(info.title, style = O5Type.CardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp))
            info.meta?.let { Text(it, style = O5Type.CardMeta, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}

@Composable
private fun BoxScope.CardOverlays(info: CardInfo, poster: Boolean) {
    if (info.sources.isNotEmpty() || info.number != null) {
        Row(Modifier.align(Alignment.TopStart).padding(if (poster) 7.dp else 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            info.number?.let {
                Box(Modifier.size(38.dp).background(Color(0x99000000), O5Shape.Button), contentAlignment = Alignment.Center) {
                    Text(it.toString(), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                }
            }
            info.sources.forEach { SourceBadge(it) }
        }
    }
    if (info.watched) WatchedMark()
    info.progress?.let { ProgressBar(it, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = if (poster) 10.dp else 16.dp, vertical = if (poster) 10.dp else 14.dp), height = if (poster) 5.dp else 6.dp) }
}
