// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kmmarin.tvbro.orbit.OrbitProfile
import io.github.kmmarin.tvbro.orbit.PickerStyle
import io.github.kmmarin.tvbro.ui.orbit5.BackdropStill
import io.github.kmmarin.tvbro.ui.orbit5.BrowseControls
import io.github.kmmarin.tvbro.ui.orbit5.BrowseSource
import io.github.kmmarin.tvbro.ui.orbit5.CardInfo
import io.github.kmmarin.tvbro.ui.orbit5.CardSize
import io.github.kmmarin.tvbro.ui.orbit5.ColorKeyActions
import io.github.kmmarin.tvbro.ui.orbit5.HeroBlock
import io.github.kmmarin.tvbro.ui.orbit5.HeroData
import io.github.kmmarin.tvbro.ui.orbit5.KeyAction
import io.github.kmmarin.tvbro.ui.orbit5.LandscapeCard
import io.github.kmmarin.tvbro.ui.orbit5.MeshColors
import io.github.kmmarin.tvbro.ui.orbit5.NavTab
import io.github.kmmarin.tvbro.ui.orbit5.O5Button
import io.github.kmmarin.tvbro.ui.orbit5.O5Row
import io.github.kmmarin.tvbro.ui.orbit5.OrbitCanvas
import io.github.kmmarin.tvbro.ui.orbit5.PosterCard
import io.github.kmmarin.tvbro.ui.orbit5.SubMenuRow
import io.github.kmmarin.tvbro.ui.profiles.ProfilePicker
import io.github.kmmarin.tvbro.ui.shell.ShellFrame
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.ContentScale
import io.github.kmmarin.tvbro.data.Channel
import io.github.kmmarin.tvbro.data.Programme
import io.github.kmmarin.tvbro.data.StreamChoice
import io.github.kmmarin.tvbro.ui.GuideLayout
import io.github.kmmarin.tvbro.ui.SafeFocus
import io.github.kmmarin.tvbro.ui.live.EpgActions
import io.github.kmmarin.tvbro.ui.live.EpgGrid
import io.github.kmmarin.tvbro.ui.live.EpgWindow
import io.github.kmmarin.tvbro.ui.live.GroupTabs
import io.github.kmmarin.tvbro.ui.live.LiveInfo
import io.github.kmmarin.tvbro.ui.live.LiveInfoData
import io.github.kmmarin.tvbro.ui.live.NowFocus
import io.github.kmmarin.tvbro.ui.live.PlayerInfo
import io.github.kmmarin.tvbro.ui.live.PlayerOverlayStill
import io.github.kmmarin.tvbro.ui.live.QuickAction
import io.github.kmmarin.tvbro.ui.live.RowMarks
import io.github.kmmarin.tvbro.ui.live.StreamPromptCard
import io.github.kmmarin.tvbro.ui.orbit5.ColorKeyLegend
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the direction-5 pieces at 1920 x 1080 to PNG files in app/build/orbit-shots (a visual check without a TV
 * or emulator; Robolectric's native graphics). Not compared against anything: a look, not an assertion.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class DesignShotsTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun shot(name: String, content: @Composable () -> Unit) {
        rule.setContent(content)
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1500)
        // (captureToImage waits for a window redraw that Robolectric never signals: draw the view tree directly)
        val v = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { v.draw(Canvas(bmp)) }
        val dir = File("build/orbit-shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Stand-in artwork: a warm gradient with a bright band (no network in tests). */
    private fun art(): Bitmap {
        val b = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 960f, 540f, 0xFFE0602A.toInt(), 0xFF3A0A0A.toInt(), Shader.TileMode.CLAMP) })
        c.drawCircle(640f, 200f, 160f, Paint().apply { color = 0xFFFFD08A.toInt() })
        return b
    }

    @Test fun home() = shot("home") {
        OrbitCanvas {
            ColorKeyActions(red = KeyAction("My list") {}, green = KeyAction("Play trailer") {}, yellow = KeyAction("Mark watched") {}, blue = KeyAction("More") {})
            ShellFrame(active = NavTab.HOME, onTab = {}, onSearch = {}, onAvatar = {}, profileName = "Dani") {
                BackdropStill(art().asImageBitmap(), MeshColors(Color(0xFFC3261B), Color.Black), Modifier.fillMaxSize())
                HeroBlock(
                    HeroData("k", "Ember Line", "Featured tonight", "plex", "2025", "7.9", "1h 58m", listOf("Thriller", "Action"), listOf("4K", "HDR"),
                        "A night-shift signal engineer has one hour to reroute a burning freight train through a city that does not know it is coming."),
                    Modifier.padding(start = 96.dp, top = 196.dp),
                ) {
                    O5Button("Play", icon = Icons.Filled.PlayArrow, primary = true) {}
                    O5Button("More info", icon = Icons.Filled.Info) {}
                }
                O5Row("Continue watching · films and shows", Modifier.padding(top = 650.dp)) {
                    items(listOf("Fathom Station" to "S2 E4 · Ballast", "Wildglass" to "1h 02m left", "The Quiet Bureau" to "S1 E6", "Glass Harbor" to "41 min left", "Low Tide Republic" to "S1 E2")) { (t, m) ->
                        LandscapeCard(null, CardInfo(t, m, progress = 0.45f)) {}
                    }
                }
            }
        }
    }

    @Test fun browse() = shot("browse") {
        OrbitCanvas {
            ShellFrame(active = NavTab.MOVIES, onTab = {}, onSearch = {}, onAvatar = {}, profileName = "Dani") {
                BackdropStill(null, MeshColors(Color(0xFF1B7FA3), Color(0xFFD8F3FF)), Modifier.fillMaxSize())
                SubMenuRow(listOf("Recommended", "Browse", "Collections"), 1, {}, Modifier.padding(top = 118.dp), browseTab = 1,
                    browse = BrowseControls(BrowseSource.ALL, {}, count = "1,284 titles"))
                O5Row(null, Modifier.padding(top = 190.dp), gap = 30.dp) {
                    items((1..9).toList()) { i -> PosterCard(null, CardInfo("Title $i", "2024 · Drama", sources = listOf(if (i % 2 == 0) "iptv" else "plex"), watched = i == 3)) {} }
                }
            }
        }
    }

    private val people = listOf(OrbitProfile("g", "Guest", false), OrbitProfile("m", "Maya", false), OrbitProfile("d", "Dani", false), OrbitProfile("l", "Leo", true))

    @Test fun pickerOrbit() = shot("picker-orbit") { ProfilePicker(people, "d", PickerStyle.ORBIT, "", emptyList(), {}, {}) }

    @Test fun pickerClassic() = shot("picker-classic") { ProfilePicker(people, "d", PickerStyle.CLASSIC, "", emptyList(), {}, {}) }

    // ---- Live TV (ui/live): guide, player overlay, stream prompt ----

    private val now = System.currentTimeMillis() / 1000
    private val t0 = GuideLayout.initialStart(now)

    private fun ch(key: String, name: String, num: Int, group: String = "Movies") =
        Channel(key, "s", name, null, group, "http://192.0.2.10/live/u/p/$num.ts", null, num, null, 0)

    private val channels = listOf(
        ch("k1", "Vista Cinema", 104), ch("k2", "Arcadia Sports", 103, "Sports"), ch("k3", "Nova One", 101, "Entertainment"),
        ch("k4", "Meridian News 24", 102, "News"), ch("k5", "Lumen Drama", 109, "Entertainment"), ch("k6", "Harbor Kids", 110, "Kids"),
        ch("k7", "Quarry Docs", 112, "Entertainment"),
    )

    private fun p(s: Long, e: Long, t: String) = Programme("k", t0 + s * 60, t0 + e * 60, t, null)

    private val progs = mapOf(
        "k1" to arrayOf(p(-120, -10, "Copper Sky"), p(-10, 125, "The Ninth Lantern"), p(125, 240, "Late Feature")),
        "k2" to arrayOf(p(-60, -15, "Pre-match Live"), p(-15, 90, "Harbor City v Northgate"), p(90, 150, "Final Whistle")),
        "k3" to arrayOf(p(-30, 0, "Evening Edition"), p(0, 60, "Fathom Station"), p(60, 120, "The Late Room"), p(120, 200, "Night Shift")),
        "k4" to arrayOf(p(0, 30, "Market Close"), p(30, 90, "Meridian Debate"), p(90, 150, "World Late")),
        "k5" to arrayOf(p(0, 60, "Kestrel & Vane"), p(60, 120, "Night Ferry"), p(120, 180, "The Quiet Bureau")),
        "k7" to arrayOf(p(-20, 40, "Deep Ice"), p(40, 100, "Salt Roads"), p(100, 160, "Lantern Makers")),
    )

    private val marks = mapOf("k1" to RowMarks(fav = true, playing = true), "k2" to RowMarks(fav = true, format = "HLS"), "k3" to RowMarks(fav = true))

    /** Remote-control input (focus shows as on the TV; Robolectric starts in touch mode, where clickables take no focus). */
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Composable
    private fun keyboardMode() {
        val im = androidx.compose.ui.platform.LocalInputModeManager.current
        remember { im.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
    }

    @Composable
    private fun guideGrid(modifier: Modifier) {
        keyboardMode()
        val win = remember { EpgWindow(now) }
        val nowFocus = remember { NowFocus().apply { pending = "k1" } } // the mockup's focus: the programme on now
        EpgGrid(
            channels, { c -> progs[c.key] ?: emptyArray() }, now, win, rememberLazyListState(), { "k1" }, remember { SafeFocus() }, nowFocus,
            EpgActions({ _, _ -> }, { _, _ -> }, {}, { _, _ -> }, { false }, { c -> marks[c.key] ?: RowMarks() }, { c, pr -> c.key == "k4" && pr.title == "Meridian Debate" }),
            modifier,
        )
    }

    private val info = LiveInfoData("104 · Vista Cinema", null, "The Ninth Lantern", "21:30 – 23:35   113 min left", live = true, progress = 0.1f,
        next = "Next: 23:35 Late Feature", stream = "Stream: TS · default")

    private val groups = listOf("Favourites", "All channels", "Entertainment", "News", "Sports", "Movies", "Kids")

    private val guideKeys: @Composable () -> Unit = {
        ColorKeyActions(red = KeyAction("Favourite") {}, green = KeyAction("Record") {}, yellow = KeyAction("Format: TS / HLS") {}, blue = KeyAction("Channel options") {})
    }

    @Test fun liveChannels() = shot("live-channels") {
        OrbitCanvas {
            guideKeys()
            ShellFrame(active = NavTab.LIVE, onTab = {}, onSearch = {}, onAvatar = {}, profileName = "Dani") {
                BackdropStill(null, MeshColors(Color(0xFFB8741A), Color(0xFFFFE2B0)), Modifier.fillMaxSize(), dim = true)
                SubMenuRow(listOf("Channels", "Guide", "Recordings", "My list"), 0, {}, Modifier.padding(top = 118.dp))
                LiveInfo(info, Modifier.padding(start = 96.dp, top = 190.dp).width(1080.dp))
                Box(Modifier.align(Alignment.TopEnd).padding(end = 96.dp, top = 178.dp).size(500.dp, 281.dp).background(Color.Black, RoundedCornerShape(12.dp)))
                GroupTabs(groups, "Favourites", { if (it == "Favourites") "★ Favourites" else it }, remember { SafeFocus() }, rememberLazyListState(),
                    {}, {}, {}, { false }, Modifier.padding(top = 478.dp))
                guideGrid(Modifier.padding(start = 96.dp, end = 96.dp, top = 538.dp, bottom = 92.dp).fillMaxSize())
            }
        }
    }

    @Test fun guide() = shot("guide") {
        OrbitCanvas {
            guideKeys()
            Box(Modifier.fillMaxSize()) {
                BackdropStill(null, MeshColors(Color(0xFF6A3FD8), Color(0xFFFF9AD5)), Modifier.fillMaxSize(), dim = true)
                LiveInfo(info, Modifier.padding(start = 96.dp, top = 56.dp).width(1100.dp), compact = true)
                Box(Modifier.align(Alignment.TopEnd).padding(end = 96.dp, top = 48.dp).size(480.dp, 270.dp).background(Color.Black, RoundedCornerShape(12.dp)))
                GroupTabs(groups, "All channels", { if (it == "Favourites") "★ Favourites" else it }, remember { SafeFocus() }, rememberLazyListState(),
                    {}, {}, {}, { false }, Modifier.padding(top = 350.dp))
                guideGrid(Modifier.padding(start = 96.dp, end = 96.dp, top = 410.dp, bottom = 92.dp).fillMaxSize())
                ColorKeyLegend(Modifier.align(Alignment.BottomStart).padding(start = 96.dp, bottom = 28.dp), hints = listOf("OK" to "Watch", "BACK" to "Close"))
            }
        }
    }

    private val playerInfo = PlayerInfo("Vista Cinema", "Channel 104 · Movies", null, "21:42", "The Ninth Lantern", "21:30 – 23:35 · Film",
        progress = 0.1f, start = "21:30", end = "23:35", next = "Next · 23:35 Late Feature", tech = "1920x1080 · H.264 · 50 fps · 6.2 Mbit/s\nstart 1.4 s · 0 stalls")

    private val quick = listOf(
        QuickAction("Guide", icon = Icons.Filled.DateRange) {}, QuickAction("Favourite", icon = Icons.Filled.Star, tint = Color(0xFFFFD166), on = true, value = "in My list") {},
        QuickAction("Record", glyph = "●", tint = Color(0xFFFF6B6B)) {}, QuickAction("Audio", glyph = "♪", value = "English 5.1") {},
        QuickAction("Subtitles", glyph = "CC", value = "Off") {}, QuickAction("Start over", icon = Icons.Filled.Refresh) {},
        QuickAction("Format · TS", glyph = "TS", value = "default") {}, QuickAction("Channels", icon = Icons.AutoMirrored.Filled.List) {},
        QuickAction("Sleep timer", glyph = "Zz", value = "Off") {}, QuickAction("Info", icon = Icons.Filled.Info) {},
        QuickAction("Video", glyph = "HD", value = "1920x1080") {},
    )

    private val playerKeys: @Composable () -> Unit = {
        ColorKeyActions(red = KeyAction("Favourite") {}, green = KeyAction("Record") {}, yellow = KeyAction("Format: TS / HLS") {}, blue = KeyAction("Options") {})
    }

    @Test fun player() = shot("player") {
        OrbitCanvas {
            playerKeys()
            Box(Modifier.fillMaxSize()) {
                Image(art().asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                PlayerOverlayStill(playerInfo, quick, hint = true)
                ColorKeyLegend(Modifier.align(Alignment.BottomStart).padding(start = 96.dp, bottom = 28.dp), hints = listOf("OK" to "Select", "BACK" to "Close"))
            }
        }
    }

    @Test fun streamPrompt() = shot("stream-prompt") {
        OrbitCanvas {
            playerKeys()
            Box(Modifier.fillMaxSize()) {
                Image(art().asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                PlayerOverlayStill(playerInfo, quick, hint = true)
                ColorKeyLegend(Modifier.align(Alignment.BottomStart).padding(start = 96.dp, bottom = 28.dp), hints = listOf("OK" to "Select", "BACK" to "Close"))
                keyboardMode()
                val cancel = remember { SafeFocus() }
                LaunchedEffect(Unit) { kotlinx.coroutines.delay(50); cancel.request() }
                StreamPromptCard(
                    2, "Vista Cinema",
                    listOf(
                        StreamChoice.Row("a1", "Kitchen", "Arcadia Sports · Harbor City v Northgate", false, t0 - 900),
                        StreamChoice.Row("b2", "Bedroom", "Meridian News 24 · Market Close", false, t0),
                    ),
                    oldestOnly = false, cancelFocus = cancel, onStop = {}, onCancel = {},
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 132.dp, end = 96.dp),
                )
            }
        }
    }
}
