// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kmmarin.tvbro.AppModel
import io.github.kmmarin.tvbro.Screen
import io.github.kmmarin.tvbro.ui.orbit5.BackdropSpec
import io.github.kmmarin.tvbro.ui.orbit5.CinematicBackdrop
import io.github.kmmarin.tvbro.ui.orbit5.O5
import io.github.kmmarin.tvbro.ui.orbit5.PhoneButton
import io.github.kmmarin.tvbro.ui.orbit5.PhoneLandscapeCard
import io.github.kmmarin.tvbro.ui.orbit5.PhonePosterCard
import io.github.kmmarin.tvbro.ui.orbit5.PhoneRowHeading
import io.github.kmmarin.tvbro.ui.orbit5.PhoneType
import io.github.kmmarin.tvbro.ui.shell.LocalPhoneInsets
import io.github.kmmarin.tvbro.ui.vodMenu
import kotlinx.coroutines.delay

/** Top of the hero text on the phone Home (y = 296 in the 390 x 844 mockup). */
private val PhoneHeroTop = 296.dp

/**
 * Home on a phone (spec section 13 and the mockup's "Phone · 5b" Home): the featured title over its backdrop with
 * filled Play / Resume and My list, then the same rows as the TV (Continue watching as 200 x 112 cards, the others as
 * 108 x 162 posters). Everything scrolls; tap opens, long press opens the title's menu.
 */
@Composable
fun PhoneHome(m: AppModel) {
    val feed = m.home
    LaunchedEffect(m.orbit.link?.profile) { feed.refresh(force = true) }
    LaunchedEffect(Unit) { feed.refresh() }
    val rows = feed.rows
    val featured = remember(rows, feed.hero) { feed.hero.ifEmpty { rows.flatMap { it.tiles }.filter { it.channel == null }.distinctBy { it.key } }.take(6) }
    var idx by remember { mutableIntStateOf(0) }
    LaunchedEffect(featured) { if (featured.size > 1) while (true) { delay(9000); idx = (idx + 1) % featured.size } }
    val hero = featured.getOrNull(idx % featured.size.coerceAtLeast(1))
    PhoneHomeContent(
        hero = hero, eyebrow = if (feed.hero.isNotEmpty()) "Featured tonight" else rows.firstOrNull { r -> r.tiles.any { it.key == hero?.key } }?.title ?: "",
        rows = rows, empty = feed.loaded && rows.isEmpty(),
        backdrop = { CinematicBackdrop(hero?.let { BackdropSpec(it.backdrop ?: it.poster, it.colors) }, Modifier.fillMaxSize(), settleMs = 0) },
        watched = { t -> t.vod?.let { m.isWatched(it.key) } == true },
        onPlay = { t -> open(m, t, play = true) },
        onSecondary = { t -> if (t.vod != null && !t.show) { m.toggleFavKey(t.vod); feed.refresh(force = true) } else open(m, t, play = false) },
        onTile = { t -> open(m, t, play = t.channel != null) },
        onLong = { t -> t.vod?.let { vodMenu(m, it) } ?: t.channel?.let { m.openChannelMenu(it) } },
        onEmpty = { m.settingsBack = Screen.LIST; m.screen = Screen.SETTINGS },
    )
}

/** The phone Home without the app model (the design shot draws it with stand-in data). */
@Composable
fun PhoneHomeContent(
    hero: Tile?, eyebrow: String, rows: List<HomeRow>, empty: Boolean = false,
    backdrop: @Composable BoxScope.() -> Unit, watched: (Tile) -> Boolean = { false },
    onPlay: (Tile) -> Unit = {}, onSecondary: (Tile) -> Unit = {}, onTile: (Tile) -> Unit = {}, onLong: (Tile) -> Unit = {}, onEmpty: () -> Unit = {},
) {
    val insets = LocalPhoneInsets.current
    LazyColumn(Modifier.fillMaxSize().testTag("phone-home"), contentPadding = PaddingValues(bottom = insets.bottom + 20.dp)) {
        item(key = "hero") {
            Box(Modifier.fillMaxWidth().height(PhoneHeroTop + 210.dp)) {
                Box(Modifier.fillMaxWidth().height(540.dp), content = backdrop)
                // the backdrop fades into the page (no hard edge above the rows)
                Box(Modifier.padding(top = 380.dp).fillMaxWidth().height(160.dp).background(Brush.verticalGradient(listOf(Color.Transparent, O5.Bg))))
                if (hero != null) Column(Modifier.padding(start = 20.dp, end = 20.dp, top = PhoneHeroTop)) {
                    if (eyebrow.isNotEmpty()) Text(eyebrow.substringBefore(" · ").uppercase(), style = PhoneType.Eyebrow, maxLines = 1)
                    Text(hero.title, style = PhoneType.HeroTitle, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        hero.year?.let { Text(it, style = PhoneType.Meta) }
                        hero.rating?.let { Text("★ $it", style = PhoneType.Meta, color = O5.Rating) }
                        (if (hero.progress != null) hero.meta else null)?.let { Text(it, style = PhoneType.Meta, maxLines = 1) }
                        hero.genres.firstOrNull()?.let { Text(it, style = PhoneType.Meta, maxLines = 1) }
                    }
                    Row(Modifier.padding(top = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val primary = when {
                            hero.channel != null -> "Watch"
                            hero.show -> if (hero.seriesRow != null || hero.progress != null) "Resume" else "Episodes"
                            hero.progress != null -> "Resume"
                            else -> "Play"
                        }
                        PhoneButton(primary, Modifier.weight(1f).testTag("phone-play"), icon = Icons.Filled.PlayArrow, primary = true) { onPlay(hero) }
                        val film = hero.vod != null && !hero.show
                        PhoneButton(if (film) "My list" else "More info", Modifier.weight(1f), icon = if (film) Icons.Filled.Add else Icons.Filled.Info) { onSecondary(hero) }
                    }
                }
            }
        }
        if (empty) item(key = "empty") {
            Column(Modifier.padding(20.dp)) {
                Text("Nothing here yet. Watch something, add titles to My list, or link this phone to Orbit in Settings.", style = PhoneType.Body)
                PhoneButton("Open Settings", Modifier.padding(top = 14.dp)) { onEmpty() }
            }
        }
        items(rows, key = { it.id }) { row ->
            Column(Modifier.padding(bottom = 22.dp)) {
                PhoneRowHeading(row.title.substringBefore(" · "))
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(row.tiles, key = { it.key }) { t ->
                        if (row.landscape) PhoneLandscapeCard(
                            if (t.channel != null) t.poster else (t.backdrop ?: t.poster), t.title, t.meta, progress = t.progress,
                            contentScale = if (t.channel != null) ContentScale.Fit else ContentScale.Crop, onLongClick = { onLong(t) },
                        ) { onTile(t) }
                        else PhonePosterCard(t.poster, t.title, t.meta.takeIf { !watched(t) } ?: "Watched", onLongClick = { onLong(t) }) { onTile(t) }
                    }
                }
            }
        }
    }
}
