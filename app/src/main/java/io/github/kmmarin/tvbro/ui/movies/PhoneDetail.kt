// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.movies

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kmmarin.tvbro.ui.orbit5.BackdropSpec
import io.github.kmmarin.tvbro.ui.orbit5.CinematicBackdrop
import io.github.kmmarin.tvbro.ui.orbit5.ImgSize
import io.github.kmmarin.tvbro.ui.orbit5.NetImage
import io.github.kmmarin.tvbro.ui.orbit5.O5
import io.github.kmmarin.tvbro.ui.orbit5.PhoneAction
import io.github.kmmarin.tvbro.ui.orbit5.PhoneChip
import io.github.kmmarin.tvbro.ui.orbit5.PhoneChipRow
import io.github.kmmarin.tvbro.ui.orbit5.PhoneRoundButton
import io.github.kmmarin.tvbro.ui.orbit5.PhoneSourceBadge
import io.github.kmmarin.tvbro.ui.orbit5.PhoneTouch
import io.github.kmmarin.tvbro.ui.orbit5.PhoneType
import io.github.kmmarin.tvbro.ui.orbit5.PhoneWideButton
import io.github.kmmarin.tvbro.ui.orbit5.ProgressBar
import io.github.kmmarin.tvbro.ui.shell.LocalIsPhone

/** The title page's backdrop: full screen on the TV, the top 360 px (faded into the page) on a phone. */
@Composable
fun DetailBackdrop(spec: BackdropSpec?) {
    if (LocalIsPhone.current) PhoneTopBackdrop(1f) { CinematicBackdrop(spec, Modifier.fillMaxSize(), settleMs = 0) }
    else CinematicBackdrop(spec, Modifier.fillMaxSize(), settleMs = 0)
}

/** "E3 · Pressure Line" -> "3. Pressure Line" (the phone episode list, mockup `.p-ep`). */
fun phoneEpTitle(t: String): String = Regex("^E(\\d+) · (.*)$").matchEntire(t)?.let { "${it.groupValues[1]}. ${it.groupValues[2]}" } ?: t

/** The round actions under Play on a phone: the page's other buttons, with Watched before More. */
fun phoneDetailActions(buttons: List<DetailButton>, watched: DetailButton?): List<DetailButton> {
    val rest = buttons.drop(1).map { b -> if (b.label == "Play from start") DetailButton("From start", b.icon ?: Icons.Filled.Refresh, b.primary, b.enabled, b.onClick) else b }
    if (watched == null) return rest
    val more = rest.indexOfLast { it.label == "More" }
    return if (more < 0) rest + watched else rest.take(more) + watched + rest.drop(more)
}

/**
 * The title page on a phone (spec section 13, mockup "Phone · 5b" Title detail): the backdrop at the top with a round
 * Back button, then title (32 px / 800), meta, synopsis, the gradient Play / Resume across the width, the round actions
 * (My list, Trailer, Watched, More: the phone's colour keys), and for shows the season chips and that season's
 * episodes as a list (thumbnail with progress, "4. Ballast", "28 min left"). Tap plays an episode; long press opens
 * its menu (long press on a season chip: the season's menu). Scrolls; works upright and on its side.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhoneDetailView(
    d: DetailData, buttons: List<DetailButton>, onBack: () -> Unit, modifier: Modifier = Modifier, watched: DetailButton? = null,
    seasons: List<String> = emptyList(), season: Int = 0, onSeason: (Int) -> Unit = {}, onSeasonLong: (Int) -> Unit = {},
    episodes: List<EpCard> = emptyList(), onEp: (EpCard) -> Unit = {}, onEpLong: (EpCard) -> Unit = {},
) {
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(modifier.fillMaxSize().testTag("phone-detail")) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = status + 170.dp, bottom = nav + 24.dp)) {
            item(key = "head") {
                Column(Modifier.padding(horizontal = 20.dp).widthIn(max = 640.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        d.sources.forEach { PhoneSourceBadge(it) }
                        Text(d.eyebrow.uppercase(), style = PhoneType.Eyebrow, maxLines = 1)
                    }
                    Text(d.title, fontSize = 32.sp, lineHeight = 35.sp, fontWeight = FontWeight.ExtraBold, color = O5.Text, letterSpacing = (-0.8).sp,
                        maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.padding(top = 5.dp, bottom = 10.dp)) { PhoneMeta(d.year, d.rating, d.length, d.genres.firstOrNull()) }
                    d.synopsis?.takeIf { it.isNotBlank() }?.let {
                        Text(it, fontSize = 13.sp, lineHeight = 19.sp, color = Color(0xD1EBEEFF), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    buttons.firstOrNull()?.let { b ->
                        PhoneWideButton(b.label, Modifier.padding(top = 12.dp).testTag("phone-detail-play"), icon = b.icon, primary = b.primary, enabled = b.enabled, onClick = b.onClick)
                    }
                    d.status?.takeIf { it.isNotEmpty() }?.let { Text(it, style = PhoneType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp)) }
                    d.note?.takeIf { it.isNotEmpty() }?.let { Text(it, style = PhoneType.Meta, color = O5.MetaText, maxLines = 2, modifier = Modifier.padding(top = 4.dp)) }
                    val actions = phoneDetailActions(buttons, watched)
                    if (actions.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        actions.forEachIndexed { i, a -> PhoneAction(a.label, icon = a.icon, enabled = a.enabled, modifier = Modifier.testTag("phone-detail-act-$i"), onClick = a.onClick) }
                    }
                    if (seasons.isEmpty() && episodes.isEmpty() && (d.cast != null || d.director != null)) {
                        Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            d.cast?.let { Text("Cast · $it", style = PhoneType.Meta, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                            d.director?.let { Text("Director · $it", style = PhoneType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
            if (seasons.size > 1 || (seasons.size == 1 && episodes.isNotEmpty())) item(key = "seasons") {
                PhoneChipRow(Modifier.padding(top = 10.dp)) {
                    seasons.forEachIndexed { i, s -> PhoneChip(s, i == season, onLongClick = { onSeasonLong(i) }) { onSeason(i) } }
                }
            }
            items(episodes, key = { "ep-" + it.key }) { e ->
                Row(
                    Modifier.padding(horizontal = 20.dp).widthIn(max = 640.dp).fillMaxWidth().heightIn(min = PhoneTouch)
                        .combinedClickable(onLongClick = { onEpLong(e) }) { onEp(e) }
                        .drawBehind { drawLine(O5.Line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.size(118.dp, 66.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF111111))) {
                        NetImage(e.image, ImgSize.LANDSCAPE, Modifier.fillMaxSize(), ContentScale.Crop)
                        e.progress?.let { ProgressBar(it, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 7.dp), height = 3.dp) }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(phoneEpTitle(e.title), fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = O5.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (e.meta.isNotEmpty()) Text(e.meta, fontSize = 11.5.sp, color = O5.Muted, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
        PhoneRoundButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", Modifier.padding(start = 10.dp, top = status + 2.dp).testTag("phone-detail-back"), onClick = onBack)
    }
}

