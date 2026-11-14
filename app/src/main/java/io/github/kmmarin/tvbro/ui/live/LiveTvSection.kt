// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.live

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import io.github.kmmarin.tvbro.AppModel
import io.github.kmmarin.tvbro.Screen
import io.github.kmmarin.tvbro.Section
import io.github.kmmarin.tvbro.data.Channel
import io.github.kmmarin.tvbro.data.LiveFormat
import io.github.kmmarin.tvbro.data.RecRepo
import io.github.kmmarin.tvbro.ui.MyListScreen
import io.github.kmmarin.tvbro.ui.RecordingsScreen
import io.github.kmmarin.tvbro.ui.TvSection
import io.github.kmmarin.tvbro.ui.orbit5.BackdropSpec
import io.github.kmmarin.tvbro.ui.orbit5.CinematicBackdrop
import io.github.kmmarin.tvbro.ui.orbit5.ColorKeyActions
import io.github.kmmarin.tvbro.ui.orbit5.KeyAction
import io.github.kmmarin.tvbro.ui.orbit5.O5Space
import io.github.kmmarin.tvbro.ui.orbit5.SubMenuRow
import io.github.kmmarin.tvbro.ui.orbit5.TitleColors
import io.github.kmmarin.tvbro.ui.shell.LegacyPage
import kotlinx.coroutines.delay

/**
 * Live TV (top bar), direction 5: the sub-menu row Channels · Guide · Recordings · My list over a dim colour-matched
 * backdrop. Channels is the TV page (programme info, live preview, group tabs, guide); Guide opens the full-screen
 * guide; Recordings and My list are tvBro's own screens for now.
 */
@Composable
fun LiveTvSection(m: AppModel) {
    // the same APK on a phone: channel list and a vertical guide (PhoneLive.kt)
    if (io.github.kmmarin.tvbro.ui.shell.LocalIsPhone.current) {
        Box(Modifier.fillMaxSize()) { LiveBackdrop(m, Modifier.fillMaxSize()); PhoneLiveSection(m) }
        return
    }
    val tabs = listOf("Channels", "Guide", "Recordings", "My list")
    val sel = when (m.section) { Section.RECORDINGS -> 2; Section.MYLIST -> 3; else -> 0 }
    Box(Modifier.fillMaxSize()) {
        LiveBackdrop(m, Modifier.fillMaxSize())
        when (m.section) {
            Section.RECORDINGS -> LegacyPage(190.dp) { RecordingsScreen(m) }
            Section.MYLIST -> LegacyPage(190.dp) { MyListScreen(m) }
            else -> TvSection(m)
        }
        SubMenuRow(tabs, sel, onSelect = { i ->
            when (i) {
                0 -> m.section = Section.TV
                1 -> { io.github.kmmarin.tvbro.ui.GuideReturn.toList = true; m.screen = Screen.GUIDE }
                2 -> m.section = Section.RECORDINGS
                else -> { m.refreshLists(); m.section = Section.MYLIST }
            }
        }, Modifier.padding(top = O5Space.SubMenuTop))
    }
}

/**
 * The guide's dim backdrop (spec section 6, `dim`): the Orbit mesh, tinted with the focused channel's logo colours
 * once focus rests on it for a moment. Channels have no artwork, so no image is drawn.
 */
@Composable
fun LiveBackdrop(m: AppModel, modifier: Modifier = Modifier) {
    val logo = (m.focusCh ?: m.current)?.logo
    var spec by remember { mutableStateOf<BackdropSpec?>(null) }
    LaunchedEffect(logo) {
        delay(250)
        val c = TitleColors.of(logo) ?: return@LaunchedEffect
        spec = BackdropSpec(null, listOf(hex(c.m1.toArgb()), hex(c.m2.toArgb())))
    }
    CinematicBackdrop(spec, modifier, dim = true)
}

private fun hex(argb: Int) = "#%06x".format(argb and 0xFFFFFF)

/**
 * The guide's colour keys (spec section 9): red Favourite, green Record, yellow the channel's stream format
 * (Default -> TS -> HLS), blue Channel options. They act on the focused channel and programme, or on the focused
 * group ([group]) for red and blue.
 */
@Composable
fun LiveColorKeys(m: AppModel, group: () -> String?) {
    ColorKeyActions(
        red = KeyAction("Favourite") { group()?.let { m.toggleFavGroup(it) } ?: focused(m)?.let { m.toggleFav(it) } },
        green = KeyAction("Record") { focused(m)?.let { record(m, it) } },
        yellow = KeyAction("Format: TS / HLS") { focused(m)?.let { cycleFormat(m, it) } },
        blue = KeyAction("Channel options") { group()?.let { m.openGroupMenu(it) } ?: focused(m)?.let { m.openChannelMenu(it) } },
    )
}

private fun focused(m: AppModel): Channel? = m.focusCh ?: m.current

/** Green: record the focused programme, or cancel / stop its recording; on a channel, the record menu (or stop). */
fun record(m: AppModel, ch: Channel) {
    val now = System.currentTimeMillis() / 1000
    val p = m.focusProg?.takeIf { m.focusCh?.key == ch.key }
    val rec = p?.let { LiveLogic.recordingOf(RecRepo.items, ch.key, ch.name, it) }
    when {
        rec != null -> { m.stopRec(rec); m.toast(if (rec.status == "recording") "Recording stopped" else "Recording cancelled: ${rec.title}") }
        p != null && p.start > now -> m.recordProgramme(ch, p)
        p != null && p.stop <= now -> m.toast("That programme has ended")
        else -> m.activeRec(ch)?.let { m.stopRec(it); m.toast("Recording stopped") } ?: m.recordMenu(ch)
    }
}

/** Yellow: the channel's own stream format, Default -> TS -> HLS -> Default (stored per channel and synced). */
fun cycleFormat(m: AppModel, ch: Channel) {
    if (!LiveFormat.canHls(ch.url)) { m.toast("${ch.name} has one stream format"); return }
    m.setChannelFormat(ch, LiveLogic.nextFormat(m.channelFormat(ch.key)))
}
