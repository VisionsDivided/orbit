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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
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
import io.github.kmmarin.tvbro.ui.movies.BrowseGridView
import io.github.kmmarin.tvbro.ui.movies.Coll
import io.github.kmmarin.tvbro.ui.movies.CollectionView
import io.github.kmmarin.tvbro.ui.movies.DetailButton
import io.github.kmmarin.tvbro.ui.movies.DetailData
import io.github.kmmarin.tvbro.ui.movies.DetailView
import io.github.kmmarin.tvbro.ui.movies.EpCard
import io.github.kmmarin.tvbro.ui.movies.RecommendedView
import io.github.kmmarin.tvbro.ui.movies.Title
import io.github.kmmarin.tvbro.ui.movies.TitleEvents
import io.github.kmmarin.tvbro.ui.movies.TitleRow
import io.github.kmmarin.tvbro.ui.movies.heroOf
import io.github.kmmarin.tvbro.ui.orbit5.ColorKeyLegend
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
import io.github.kmmarin.tvbro.ui.shell.PhoneFrame
import io.github.kmmarin.tvbro.ui.home.HomeRow
import io.github.kmmarin.tvbro.ui.home.PhoneHomeContent
import io.github.kmmarin.tvbro.ui.home.Tile
import io.github.kmmarin.tvbro.ui.discover.DiscoverActions
import io.github.kmmarin.tvbro.ui.discover.DiscoverContent
import io.github.kmmarin.tvbro.ui.discover.DiscoverState
import io.github.kmmarin.tvbro.ui.discover.DiscoverSearch
import io.github.kmmarin.tvbro.ui.discover.DiscoverTitleView
import io.github.kmmarin.tvbro.ui.discover.PagedRow
import io.github.kmmarin.tvbro.ui.discover.QrInfo
import io.github.kmmarin.tvbro.ui.discover.TitleActions
import io.github.kmmarin.tvbro.ui.discover.TitleView
import io.github.kmmarin.tvbro.orbit.CastMember
import io.github.kmmarin.tvbro.orbit.DiscoverPerson
import io.github.kmmarin.tvbro.orbit.DiscoverSeason
import io.github.kmmarin.tvbro.orbit.DiscoverTitlePage
import io.github.kmmarin.tvbro.orbit.TitleRequest
import io.github.kmmarin.tvbro.ui.SettingsFrame
import io.github.kmmarin.tvbro.ui.PickerPreview
import io.github.kmmarin.tvbro.ui.profileRows
import io.github.kmmarin.tvbro.orbit.DiscoverRequest
import io.github.kmmarin.tvbro.orbit.DiscoverRow
import io.github.kmmarin.tvbro.orbit.DiscoverTitle
import io.github.kmmarin.tvbro.orbit.DownloadInfo
import androidx.compose.material.icons.filled.Check
import io.github.kmmarin.tvbro.ui.movies.PhoneBrowseView
import io.github.kmmarin.tvbro.ui.movies.PhoneMoviesControls
import io.github.kmmarin.tvbro.ui.movies.PhoneTopBackdrop
import io.github.kmmarin.tvbro.ui.live.PhoneChannelRow
import io.github.kmmarin.tvbro.ui.live.PhoneListHeading
import io.github.kmmarin.tvbro.ui.live.PhoneLiveView
import io.github.kmmarin.tvbro.ui.live.PhonePlayerEvents
import io.github.kmmarin.tvbro.ui.live.PhonePlayerView
import io.github.kmmarin.tvbro.ui.live.PhoneStreamSheet
import io.github.kmmarin.tvbro.ui.shell.LocalIsPhone
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
                O5Row("Continue watching · films and shows", Modifier.padding(top = 630.dp)) {
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
    private fun guideGrid(modifier: Modifier, list: List<Channel> = channels, metrics: io.github.kmmarin.tvbro.ui.live.EpgMetrics = io.github.kmmarin.tvbro.ui.live.EpgMetrics(),
                          focus: Boolean = true) {
        keyboardMode()
        val win = remember { EpgWindow(now) }
        val nowFocus = remember { NowFocus().apply { if (focus) pending = "k1" } } // the mockup's focus: the programme on now
        EpgGrid(
            list, { c -> progs[c.key] ?: moreProgs[c.key] ?: emptyArray() }, now, win, rememberLazyListState(), { "k1" }, remember { SafeFocus() }, nowFocus,
            EpgActions({ _, _ -> }, { _, _ -> }, {}, { _, _ -> }, { false }, { c -> marks[c.key] ?: RowMarks() }, { c, pr -> c.key == "k4" && pr.title == "Meridian Debate" }),
            modifier, metrics,
        )
    }

    private val info = LiveInfoData("104 · Vista Cinema", null, "The Ninth Lantern", "21:30 – 23:35   113 min left", live = true, progress = 0.1f,
        next = "Next: 23:35 Late Feature", stream = "Stream: TS · default")

    private val groups = listOf("Favourites", "All channels", "Entertainment", "News", "Sports", "Movies", "Kids")

    private val guideKeys: @Composable () -> Unit = {
        ColorKeyActions(red = KeyAction("Favourite") {}, green = KeyAction("Record") {}, yellow = KeyAction("Format: TS / HLS") {}, blue = KeyAction("Channel options") {})
    }

    // ---- Live TV rework (owner feedback 2026-11-14): compact header, quick tabs, side menu of ~200 groups ----

    /** About 200 groups like the owner's provider (fictional): folders by the text before " | ". */
    private val manyGroups: List<String> = buildList {
        add("Ambiance | Channels"); add("Sports | NHL Teams"); add("Sports | NHL Hockey"); add("Kids"); add("UK | News")
        for (n in listOf("NFL Replays", "NBA Pass", "MLB Extra", "Soccer Europe", "Soccer Americas", "Tennis", "Golf", "Motorsport", "Boxing & MMA", "Cricket",
            "Rugby", "Cycling", "Darts", "Snooker", "Athletics", "Winter Sports", "Esports", "Horse Racing", "Sailing", "College", "PPV Events", "Fishing")) add("Sports | $n")
        for (i in 1..17) add("UK | Regional $i")
        for (i in 1..31) add("US | Network $i")
        for (c in listOf("FR", "DE", "ES", "IT", "NL", "PT", "PL", "SE", "NO", "DK")) for (i in 1..12) add("$c | Channels $i")
        add("Music"); add("24/7 Movies")
    }

    private val moreChannels = channels + listOf(
        ch("k8", "Arcadia Hockey Night", 120, "Sports | NHL Hockey"), ch("k9", "NHL Centre Ice 1", 121, "Sports | NHL Teams"),
        ch("k10", "Meridian Weather", 105, "UK | News"), ch("k11", "Harbor Classic", 130, "24/7 Movies"),
    )

    /** Guide data as providers send it: overlapping and duplicated entries, cleaned by the same rules as the app (EpgIndex.tidy). */
    private val moreProgs: Map<String, Array<Programme>> = mapOf(
        "k8" to arrayOf(p(-30, 150, "NHL Hockey on Arcadia"), p(-30, 150, "Next Game Today"), p(-30, 150, "NHL Hockey on Arcadia"), p(150, 210, "Post-game"), p(160, 180, "Post-game")),
        "k9" to arrayOf(p(-15, 45, "Centre Ice Tonight"), p(30, 120, "Harbor City v Northgate"), p(120, 180, "Highlights")),
        "k10" to arrayOf(p(-10, 20, "Forecast"), p(20, 50, "Forecast"), p(50, 110, "Weekend Outlook")),
        "k11" to arrayOf(p(-40, 80, "Copper Sky"), p(80, 200, "The Long Meridian")),
    ).mapValues { io.github.kmmarin.tvbro.data.EpgIndex.tidy(it.value) }

    private val quickTabs = io.github.kmmarin.tvbro.data.LiveGroups.quickTabs(listOf("Favourites", "All channels", "Recent"),
        listOf("Sports | NHL Teams", "UK | News"), manyGroups.toSet() + listOf("Favourites", "All channels"), "Favourites", "ALL")

    private fun quickLabel(g: String) = when (g) { "Favourites" -> "★ Favourites"; "ALL" -> "All groups ☰"; else -> g }

    @Composable
    private fun liveCompactPage(focusGrid: Boolean, menu: (@Composable () -> Unit)? = null) {
        OrbitCanvas {
            guideKeys()
            ShellFrame(active = NavTab.LIVE, onTab = {}, onSearch = {}, onAvatar = {}, profileName = "Dani") {
                val l = io.github.kmmarin.tvbro.ui.ChannelsLayout
                BackdropStill(null, MeshColors(Color(0xFFB8741A), Color(0xFFFFE2B0)), Modifier.fillMaxSize(), dim = true)
                SubMenuRow(listOf("Channels", "Guide", "Recordings", "My list"), 0, {}, Modifier.padding(top = 118.dp))
                io.github.kmmarin.tvbro.ui.live.LiveInfoBar(
                    info.copy(fav = true, desc = "A lighthouse keeper's daughter follows a ninth light out to sea, where the charts end and the old stories begin."),
                    onFav = {}, Modifier.padding(start = 96.dp, top = l.InfoTop, end = 96.dp + l.PreviewW + 40.dp))
                Box(Modifier.align(Alignment.TopEnd).padding(end = 96.dp, top = l.PreviewTop).size(l.PreviewW, l.PreviewH).background(Color.Black, RoundedCornerShape(12.dp))) {
                    Image(art().asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                GroupTabs(quickTabs, "Favourites", ::quickLabel, remember { SafeFocus() }, rememberLazyListState(),
                    {}, {}, {}, { false }, Modifier.padding(top = l.TabsTop, end = 96.dp + l.PreviewW + 24.dp))
                guideGrid(Modifier.padding(start = 96.dp, end = 96.dp, top = l.GridTop, bottom = 92.dp).fillMaxSize(), moreChannels.sortedBy { it.number }, l.metrics, focusGrid)
                menu?.invoke()
            }
        }
    }

    @Test fun liveCompact() = shot("live-compact") { liveCompactPage(focusGrid = true) }

    @Composable
    private fun sideMenu(query: String, expanded: Set<String>) {
        ColorKeyActions(red = KeyAction("Pin / unpin group") {})
        val state = remember { io.github.kmmarin.tvbro.ui.live.GroupMenuState().apply { this.expanded = expanded; this.query = query } }
        val names = moreChannels.map { it.name }
        val rows = if (query.isBlank()) io.github.kmmarin.tvbro.data.LiveGroups.rows(io.github.kmmarin.tvbro.data.LiveGroups.fold(manyGroups), expanded)
            else io.github.kmmarin.tvbro.data.LiveGroups.search(query, manyGroups, names)
        io.github.kmmarin.tvbro.ui.live.GroupMenuView(
            query, {}, rows, { moreChannels.getOrNull(it) }, setOf("Sports | NHL Teams", "UK | News"), "Favourites", { it }, manyGroups.size, state,
            io.github.kmmarin.tvbro.ui.live.GroupMenuActions({}, {}, {}, {}, {}, {}, {}), top = 120.dp,
        )
    }

    @Test fun liveSideMenu() = shot("live-sidemenu") { liveCompactPage(focusGrid = false) { sideMenu("", setOf("Sports")) } }

    @Test fun liveSideMenuSearch() = shot("live-sidemenu-search") { liveCompactPage(focusGrid = false) { sideMenu("hockey", emptySet()) } }

    @Config(sdk = [34], qualifiers = "w412dp-h915dp-port-xxhdpi")
    @Test fun phoneLiveGroups() = shot("phone-live-groups") {
        PhoneFrame(active = NavTab.LIVE, onTab = {}, onSearch = {}, onAvatar = {}, profileName = "Dani", title = "Live TV") {
            val ins = io.github.kmmarin.tvbro.ui.shell.LocalPhoneInsets.current
            val state = remember { io.github.kmmarin.tvbro.ui.live.GroupMenuState().apply { expanded = setOf("Sports") } }
            Box(Modifier.fillMaxSize().padding(top = ins.top, bottom = ins.bottom)) {
                io.github.kmmarin.tvbro.ui.live.GroupMenuView(
                    "", {}, io.github.kmmarin.tvbro.data.LiveGroups.rows(io.github.kmmarin.tvbro.data.LiveGroups.fold(manyGroups), state.expanded), { null },
                    setOf("Sports | NHL Teams"), "Sports | NHL Teams", { it }, manyGroups.size, state,
                    io.github.kmmarin.tvbro.ui.live.GroupMenuActions({}, {}, {}, {}, {}, {}, {}), phone = true,
                )
            }
        }
    }

    // ---- Live TV ▸ Recordings and Search in direction 5 (orbit/tv-live3) ----

    private fun rec(id: String, title: String, ch: String, startMin: Long, durMin: Long, status: String, size: String, note: String = "", list: Boolean = false, prog: Float? = null) =
        io.github.kmmarin.tvbro.ui.live.RecCardData(id, title, ch, null,
            io.github.kmmarin.tvbro.ui.live.recTime((t0 + startMin * 60) * 1000, (t0 + (startMin + durMin) * 60) * 1000), size, status, note, list, prog)

    @Test fun recordings() = shot("recordings") { recordingsPage(full = true) }

    /** The lower part: the scheduled list, a series rule and downloads (no recordings yet). */
    @Test fun recordingsScheduled() = shot("recordings-scheduled") { recordingsPage(full = false) }

    @Composable
    private fun recordingsPage(full: Boolean) {
        OrbitCanvas {
            ColorKeyActions(yellow = KeyAction("My list") {})
            ShellFrame(active = NavTab.LIVE, onTab = {}, onSearch = {}, onAvatar = {}, profileName = "Dani") {
                BackdropStill(null, MeshColors(Color(0xFFB8741A), Color(0xFFFFE2B0)), Modifier.fillMaxSize(), dim = true)
                keyboardMode()
                io.github.kmmarin.tvbro.ui.live.RecordingsView(
                    storage = "Saving to Internal storage · 41.2 GB free (minimum 2048 MB free) · change in Settings ▸ Recording",
                    recordNow = "Record Vista Cinema now",
                    recording = if (!full) emptyList() else listOf(rec("r1", "Harbor City v Northgate", "Arcadia Sports", -40, 120, "recording", "1.3 GB", prog = 0.33f)),
                    recorded = if (!full) emptyList() else listOf(
                        rec("r2", "The Quiet Bureau", "Lumen Drama", -1500, 60, "done", "2.1 GB", list = true), rec("r3", "Meridian Debate", "Meridian News 24", -2900, 60, "done", "1.8 GB"),
                        rec("r4", "Deep Ice", "Quarry Docs", -4300, 55, "done", "1.6 GB"), rec("r5", "Late Feature", "Vista Cinema", -5800, 115, "failed", "", "stream ended"),
                    ),
                    scheduled = listOf(rec("s1", "Night Ferry", "Lumen Drama", 150, 60, "scheduled", ""), rec("s2", "NHL Hockey on Arcadia", "Arcadia Hockey Night", 1440, 180, "scheduled", "", list = true)),
                    rules = listOf(io.github.kmmarin.tvbro.ui.live.RuleData("u1", "Fathom Station", "Nova One", null)),
                    downloads = listOf(
                        io.github.kmmarin.tvbro.ui.live.DlCardData("d1", "m|p0|1", "Paper Comets", null, "Downloaded · 2.4 GB", null),
                        io.github.kmmarin.tvbro.ui.live.DlCardData("d2", "s|p0|2", "Fathom Station S2 E5", null, "Downloading 42% · 610 MB", 0.42f),
                        io.github.kmmarin.tvbro.ui.live.DlCardData("d3", "m|p0|3", "Glass Harbor", null, "Paused 12% · 180 MB", null),
                    ),
                    actions = io.github.kmmarin.tvbro.ui.live.RecordingsActions(),
                )
                SubMenuRow(listOf("Channels", "Guide", "Recordings", "My list"), 2, {}, Modifier.padding(top = 118.dp))
            }
        }
    }

    @Composable
    private fun searchPage(query: String, groups: io.github.kmmarin.tvbro.ui.search.SearchGroups?, detail: String? = null) {
        OrbitCanvas {
            ColorKeyActions(yellow = KeyAction("My list") {})
            ShellFrame(active = null, onTab = {}, onSearch = {}, onAvatar = {}, profileName = "Dani", searchActive = true) {
                BackdropStill(null, MeshColors(Color(0xFF1B7FA3), Color(0xFFD8F3FF)), Modifier.fillMaxSize(), dim = true)
                keyboardMode()
                io.github.kmmarin.tvbro.ui.search.SearchView(query, listOf("hockey", "paper comets", "fathom station", "news", "the quiet bureau", "nfl"), groups, detail,
                    io.github.kmmarin.tvbro.ui.search.SearchActions())
            }
        }
    }

    @Test fun searchHistory() = shot("search") { searchPage("", null) }

    @Test fun searchResults() = shot("search-results") {
        fun ph(id: String, ch: String, t: String, meta: String, st: io.github.kmmarin.tvbro.data.ProgState, p: Float? = null) = io.github.kmmarin.tvbro.ui.search.ProgHit(id, ch, null, t, meta, p, st)
        searchPage("hockey", io.github.kmmarin.tvbro.ui.search.SearchGroups(
            "Results for “hockey”  ·  14 ms",
            channels = listOf(io.github.kmmarin.tvbro.ui.search.ChanHit("k8", "Arcadia Hockey Night", null, "NHL Hockey on Arcadia", 0.2f, true),
                io.github.kmmarin.tvbro.ui.search.ChanHit("k9", "Hockey Classics", null, "1987 Final, Game 7", 0.6f, false)),
            live = listOf(ph("l1", "Arcadia Hockey Night", "NHL Hockey on Arcadia", "Arcadia Hockey Night · 118 min left", io.github.kmmarin.tvbro.data.ProgState.NOW, 0.2f),
                ph("l2", "Nova One", "Hockey Tonight", "Nova One · 22 min left", io.github.kmmarin.tvbro.data.ProgState.NOW, 0.7f), ph("l3", "Meridian News 24", "Sports Desk: Hockey", "Meridian News 24 · 9 min left", io.github.kmmarin.tvbro.data.ProgState.NOW, 0.85f)),
            movies = listOf(io.github.kmmarin.tvbro.ui.search.VodHit("m1", "Ice Line", null, "★ 7.4", false), io.github.kmmarin.tvbro.ui.search.VodHit("m2", "The Long Shift", null, "★ 6.9", true),
                io.github.kmmarin.tvbro.ui.search.VodHit("m3", "Northbound Quiet", null, "★ 7.1", false)),
            shows = listOf(io.github.kmmarin.tvbro.ui.search.VodHit("s1", "Fathom Station", null, "★ 8.4", false), io.github.kmmarin.tvbro.ui.search.VodHit("s2", "Rink Rats", null, "★ 7.0", false)),
            later = listOf(ph("f1", "Arcadia Sports", "Hockey Night Preview", "Today 21:00 – 21:30 · Arcadia Sports", io.github.kmmarin.tvbro.data.ProgState.FUTURE),
                ph("c1", "Arcadia Hockey Night", "NHL Hockey on Arcadia", "Fri 13 Nov 19:00 – 22:00 · Arcadia Hockey Night", io.github.kmmarin.tvbro.data.ProgState.PAST_CATCHUP)),
        ))
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

    // ---- Movies / Shows / title page (ui/movies) ----

    private val names = listOf("Glass Harbor", "Paper Comets", "Northbound Quiet", "Saltwater Kings", "Velvet Static", "A Map of Tuesdays", "Ironwood",
        "The Ninth Lantern", "Low Tide Republic", "Neon Orchard", "Halcyon Drift", "Copper Sky", "Small Hours", "Ember Line", "Wildglass", "The Long Meridian",
        "Orchid Protocol", "Second Sunrise", "Driftwood Saints", "Quiet Machines", "Terminal Bloom", "Foxglove Hill", "Sable Coast", "The Kindling")
    private val genres = listOf("Drama", "Sci-fi", "Western", "Drama", "Music", "Comedy", "Fantasy", "Mystery")

    private fun title(i: Int) = Title(
        "m|p0|$i", names[i % names.size], false, year = "${2020 + i % 6}", rating = "7.${i % 10}", genres = listOf(genres[i % genres.size]),
        source = if (i % 3 == 0) "plex" else "iptv", meta = "${2020 + i % 6} · ${genres[i % genres.size]}",
    )

    @Composable private fun keys() =
        ColorKeyActions(red = KeyAction("My list") {}, green = KeyAction("Play trailer") {}, yellow = KeyAction("Mark watched") {}, blue = KeyAction("More") {})

    private val tabs = listOf("Recommended", "Browse", "Collections")

    @Test fun moviesRecommended() = shot("movies-recommended") {
        OrbitCanvas {
            keys()
            ShellFrame(active = NavTab.MOVIES, onTab = {}, onSearch = {}, onAvatar = {}, profileName = "Dani") {
                BackdropStill(art().asImageBitmap(), MeshColors(Color(0xFF6A3FD8), Color(0xFFFF9AD5)), Modifier.fillMaxSize())
                val cont = (0..4).map { title(it).copy(progress = 0.2f + it * 0.15f, meta = if (it % 2 == 0) "S2 E4 · Ballast" else "1h 12m left") }
                val rows = listOf(TitleRow("continue", "Continue watching", cont, landscape = true), TitleRow("recent", "Recently added", (5..14).map { title(it) }))
                RecommendedView(
                    heroOf(title(1), "#1 in Sci-fi this week", "A paper-route kid finds that the comets falling over her town are letters, and somebody up there is waiting for a reply.",
                        runtime = "1h 51m"),
                    rows, TitleEvents(), "Play", inList = false, onPlay = {}, onMyList = {},
                )
                SubMenuRow(tabs, 0, {}, Modifier.padding(top = 118.dp), browseTab = 1, browse = BrowseControls(BrowseSource.ALL, {}, count = "1,284 titles"))
            }
        }
    }

    @Test fun moviesBrowse() = shot("movies-browse") {
        OrbitCanvas {
            keys()
            ShellFrame(active = NavTab.MOVIES, onTab = {}, onSearch = {}, onAvatar = {}, profileName = "Dani") {
                BackdropStill(null, MeshColors(Color(0xFF1B7FA3), Color(0xFFD8F3FF)), Modifier.fillMaxSize())
                BrowseGridView((0..23).map { title(it) }, TitleEvents(watched = { it.key.endsWith("3") }), hasMore = false, onNearEnd = {})
                SubMenuRow(tabs, 1, {}, Modifier.padding(top = 118.dp), browseTab = 1, browse = BrowseControls(BrowseSource.ALL, {}, count = "1,284 titles"))
            }
        }
    }

    @Test fun collection() = shot("collection") {
        OrbitCanvas {
            keys()
            ShellFrame(active = NavTab.MOVIES, onTab = {}, onSearch = {}, onAvatar = {}, profileName = "Dani") {
                BackdropStill(art().asImageBitmap(), MeshColors(Color(0xFF16968A), Color(0xFFF2FFFB)), Modifier.fillMaxSize())
                val words = listOf("Key", "Orchard", "Winter", "Gate", "Lantern", "Prince", "Ashes I", "Ashes II")
                val films = (0..7).map { i ->
                    title(i).copy(key = "m|p0|c$i", title = "The Hollowmere ${words[i]}", year = "${2001 + i}", progress = if (i == 3) 0.4f else null,
                        meta = if (i == 3) "1h 12m left" else null, genres = listOf("Fantasy", "Adventure"))
                }
                CollectionView(Coll("c1", "The Hollowmere Saga", 8, null, null, null, "plex"), films, false, TitleEvents(watched = { it.key.last().digitToInt() < 3 }), onPlay = {})
                SubMenuRow(tabs, 2, {}, Modifier.padding(top = 118.dp), browseTab = 1, browse = BrowseControls(BrowseSource.ALL, {}))
            }
        }
    }

    @Test fun detail() = shot("detail") {
        OrbitCanvas {
            keys()
            ShellFrame(active = NavTab.SHOWS, onTab = {}, onSearch = {}, onAvatar = {}, profileName = "Dani", legend = false) {
                BackdropStill(art().asImageBitmap(), MeshColors(Color(0xFF1B7FA3), Color(0xFFD8F3FF)), Modifier.fillMaxSize())
                DetailView(
                    DetailData("Fathom Station", "Series", listOf("iptv"), "2023", "8.4", "3 Seasons", listOf("Thriller", "Sci-fi"),
                        "Eight researchers live two kilometres under the ocean on a station that was never meant to be found. When the surface goes silent, the crew has to decide who they can trust.",
                        status = "28 min left · Ballast · My list ✓"),
                    listOf(DetailButton("Resume S2 E4", Icons.Filled.PlayArrow, primary = true), DetailButton("Play from start"), DetailButton("My list", Icons.Filled.Add),
                        DetailButton("More", Icons.Filled.MoreVert)),
                    seasons = listOf("Season 1", "Season 2", "Season 3"), season = 1,
                    episodes = listOf(
                        EpCard("3", "E3 · Pressure Line", "Watched", null, 1f, true), EpCard("4", "E4 · Ballast", "28 min left", null, 0.46f),
                        EpCard("5", "E5 · Thermocline", "52 min", null), EpCard("6", "E6 · The Quiet Hatch", "49 min", null), EpCard("7", "E7 · Surface", "55 min", null),
                    ),
                )
                ColorKeyLegend(Modifier.align(Alignment.BottomStart).padding(start = 96.dp, bottom = 28.dp), hints = listOf("OK" to "Play"))
            }
        }
    }

    // ---- Discover, Settings > Profiles, phone (orbit/tv-discover) ----

    private fun iso(msFromNow: Long): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        return f.format(java.util.Date(System.currentTimeMillis() + msFromNow))
    }

    @Test fun discover() = shot("discover") {
        fun t(i: Long, n: String, type: String = "movie", status: String = "requestable", req: String? = null, dl: DownloadInfo? = null) =
            DiscoverTitle(type, i, n, "2025", rating = "8.0", status = status, requestStatus = req, download = dl)
        val rows = listOf(
            PagedRow("trending-movies", "Trending movies", "trending", "movie", listOf(t(1, "Starfall Ridge"), t(2, "The Last Cartographer"), t(3, "Mirror Lake", status = "available"),
                t(4, "Saffron Engine", req = "pending", status = "pending"), t(5, "Halcyon Drift"), t(6, "Paper Comets"), t(7, "Orchid Protocol"), t(8, "The Long Meridian"), t(9, "Quiet Machines")), 1, 9),
            PagedRow("trending-shows", "Trending shows", "trending", "tv", listOf(t(11, "Tidewater", "tv"), t(12, "Undertow Club", "tv")), 1, 9),
        )
        val dl = DownloadInfo(42, 100, 58, iso(18 * 60_000L + 20_000))
        fun r(id: Long, n: String, type: String, st: String, ms: String, by: String, d: DownloadInfo? = null, seasons: List<Int> = emptyList()) =
            DiscoverRequest(id, type, id, st, ms, "", seasons, by, d, DiscoverTitle(type, id, n, "2025", status = ms, requestStatus = st, download = d))
        val reqs = listOf(
            r(21, "Coldharbour", "movie", "pending", "pending", "Dani"),
            r(22, "Tidewater", "tv", "approved", "processing", "Maya", seasons = listOf(1)),
            r(23, "Lantern Season", "movie", "approved", "processing", "Dani", dl),
            r(24, "The Amber Hour", "movie", "completed", "available", "Dani"),
            r(25, "Undertow Club", "tv", "pending", "pending", "Dani", seasons = listOf(2)),
        )
        OrbitCanvas {
            ColorKeyActions(red = KeyAction("Request") {}, blue = KeyAction("More") {})
            ShellFrame(active = NavTab.DISCOVER, onTab = {}, onSearch = {}, onAvatar = {}, profileName = "Dani") {
                DiscoverContent(DiscoverState.READY, rows, reqs, DiscoverActions())
            }
        }
    }

    // ---- Discover search and title page (orbit/tv-discover2) ----

    private fun dt(i: Long, n: String, type: String = "movie", status: String = "requestable", req: String? = null, dl: DownloadInfo? = null) =
        DiscoverTitle(type, i, n, "2025", rating = "7.6", status = status, requestStatus = req, download = dl)

    private val found = DiscoverSearch(
        "star",
        results = listOf(
            dt(1, "Starfall Ridge"), dt(2, "Star Harbour", "tv", status = "available"), dt(3, "Starlit Avenue", req = "pending", status = "pending"),
            dt(4, "The Star Keeper"), dt(5, "Stars Over Ashford", "tv"), dt(6, "Starboard"), dt(7, "North Star Line", status = "processing", req = "approved"),
            dt(8, "Starling"), dt(9, "Lone Star Radio", "tv"), dt(10, "Starfield Nine"), dt(11, "Stardust Motel"), dt(12, "Seven Stars", "tv"),
        ),
        people = listOf(
            DiscoverPerson(31, "Ada Starling", null, listOf(dt(1, "Starfall Ridge"), dt(8, "Starling"))),
            DiscoverPerson(32, "Ben Example", null, listOf(dt(4, "The Star Keeper"))),
            DiscoverPerson(33, "Cleo Example", null, emptyList()),
        ),
        page = 1, pages = 6, total = 117, loading = false,
    )

    @Test fun discoverSearch() = shot("discover-search") {
        OrbitCanvas {
            ColorKeyActions(red = KeyAction("Request") {}, blue = KeyAction("More") {})
            ShellFrame(active = NavTab.DISCOVER, onTab = {}, onSearch = {}, onAvatar = {}, profileName = "Dani") {
                DiscoverContent(DiscoverState.READY, emptyList(), emptyList(), DiscoverActions(), query = "star", search = found)
            }
        }
    }

    private fun titlePage(choosing: Boolean): TitleView {
        val dl = DownloadInfo(65, 4_000_000_000, 1_400_000_000, iso(18 * 60_000L + 20_000), 3)
        val t = dt(1399, "Tidewater", "tv", status = "partial", req = "approved", dl = dl).copy(
            overview = "A harbour town's ferry captain finds a ledger in the hull of a wreck, and every name in it is still alive. Three seasons of a quiet, salt-stained mystery.",
        )
        val p = DiscoverTitlePage(
            t, listOf("Drama", "Mystery", "Crime"), "The sea keeps score.", "2024-03-01", 52,
            cast = listOf(CastMember(1, "Ada Example", "Mara Quinn", null), CastMember(2, "Ben Example", "Tom Hale", null), CastMember(3, "Cleo Example", "Iris", null),
                CastMember(4, "Dev Example", "The Harbourmaster", null), CastMember(5, "Eve Example", "June", null), CastMember(6, "Finn Example", "Rook", null)),
            trailer = "https://www.youtube.com/watch?v=abcdef12",
            requests = listOf(TitleRequest(4, "approved", iso(-13 * 86_400_000L), "Sam", listOf(2))),
            seerrUrl = "http://192.0.2.20:5055/tv/1399",
            seasons = listOf(DiscoverSeason(1, 8, "Season 1", "available"), DiscoverSeason(2, 8, "Season 2", "processing"),
                DiscoverSeason(3, 10, "Season 3", "requestable"), DiscoverSeason(4, 10, "Season 4", "requestable")),
            seasonCount = 4, episodeCount = 36, networks = listOf("Example TV"), showStatus = "Returning Series",
        )
        return TitleView(t, p, choosing = choosing, pick = if (choosing) setOf(3, 4) else emptySet())
    }

    @Test fun discoverTitle() = shot("discover-title") {
        OrbitCanvas {
            ShellFrame(active = NavTab.DISCOVER, onTab = {}, onSearch = {}, onAvatar = {}, profileName = "Dani") {
                DiscoverTitleView(titlePage(false), TitleActions())
            }
        }
    }

    @Test fun discoverTitleSeasons() = shot("discover-title-seasons") {
        OrbitCanvas {
            ShellFrame(active = NavTab.DISCOVER, onTab = {}, onSearch = {}, onAvatar = {}, profileName = "Dani") {
                DiscoverTitleView(titlePage(true), TitleActions())
            }
        }
    }

    @Test fun discoverTitleQr() = shot("discover-title-qr") {
        OrbitCanvas {
            ShellFrame(active = NavTab.DISCOVER, onTab = {}, onSearch = {}, onAvatar = {}, profileName = "Dani") {
                DiscoverTitleView(titlePage(false), TitleActions(), QrInfo("Open Tidewater in Seerr", "http://192.0.2.20:5055/tv/1399"))
            }
        }
    }

    @Config(sdk = [34], qualifiers = "w412dp-h915dp-port-xxhdpi")
    @Test fun phoneDiscoverSearch() = shot("phone-discover-search") {
        PhoneFrame(active = NavTab.DISCOVER, onTab = {}, onSearch = {}, onAvatar = {}, profileName = "Dani", title = "Discover") {
            DiscoverContent(DiscoverState.READY, emptyList(), emptyList(), DiscoverActions(), query = "star", search = found)
        }
    }

    @Config(sdk = [34], qualifiers = "w412dp-h915dp-port-xxhdpi")
    @Test fun phoneDiscoverTitle() = shot("phone-discover-title") {
        PhoneFrame(active = NavTab.DISCOVER, onTab = {}, onSearch = {}, onAvatar = {}, profileName = "Dani", title = "Discover") {
            DiscoverTitleView(titlePage(true), TitleActions())
        }
    }

    @Test fun discoverNotSetUp() = shot("discover-not-set-up") {
        OrbitCanvas {
            ShellFrame(active = NavTab.DISCOVER, onTab = {}, onSearch = {}, onAvatar = {}, profileName = "Dani", legend = false) {
                DiscoverContent(DiscoverState.NOT_CONFIGURED, emptyList(), emptyList(), DiscoverActions(), admin = true)
            }
        }
    }

    @Test fun settingsProfiles() = shot("settings-profiles") {
        OrbitCanvas {
            ShellFrame(active = null, onTab = {}, onSearch = {}, onAvatar = {}, profileName = "Dani", settingsActive = true, legend = false) {
                SettingsFrame(0, {}, preview = { PickerPreview(PickerStyle.ORBIT, listOf("Dani", "Maya", "Leo", "Guest")) }) {
                    profileRows(PickerStyle.ORBIT, {}, true, {}, linked = true, current = "Dani", onSwitch = {}, onLink = {})
                }
            }
        }
    }

    @Test fun settingsAbout() = shot("settings-about") {
        OrbitCanvas {
            ShellFrame(active = null, onTab = {}, onSearch = {}, onAvatar = {}, profileName = "Dani", settingsActive = true, legend = false) {
                SettingsFrame(6, {}) { io.github.kmmarin.tvbro.ui.aboutForTest(this) }
            }
        }
    }

    @Config(sdk = [34], qualifiers = "w412dp-h915dp-port-xxhdpi")
    @Test fun phoneHome() = shot("phone-home") {
        val cont = listOf(Tile("a", "Fathom Station", "S2 E4 · 28 min left", progress = 0.46f), Tile("b", "Wildglass", "1h 02m left", progress = 0.38f),
            Tile("c", "The Quiet Bureau", "S1 E6 · 9 min left", progress = 0.84f))
        val posters = (1..6).map { Tile("p$it", "Title $it", "2024 · Drama") }
        PhoneFrame(active = NavTab.HOME, onTab = {}, onSearch = {}, onAvatar = {}, profileName = "Dani") {
            PhoneHomeContent(
                hero = Tile("k", "Ember Line", year = "2025", rating = "7.9", genres = listOf("Thriller")), eyebrow = "Featured tonight",
                rows = listOf(HomeRow("continue", "Continue watching", cont, landscape = true), HomeRow("recent", "Recently added", posters)),
                backdrop = { BackdropStill(art().asImageBitmap(), MeshColors(Color(0xFFC3261B), Color.Black), Modifier.fillMaxSize()) },
            )
        }
    }

    // ---- phone 5b: Movies / Shows, title page, Live TV, player (orbit/phone-layouts) ----

    @Config(sdk = [34], qualifiers = "w412dp-h915dp-port-xxhdpi")
    @Test fun phoneMovies() = shot("phone-movies") {
        PhoneFrame(active = NavTab.MOVIES, onTab = {}, onSearch = {}, onAvatar = {}, profileName = "Dani", title = "Movies") {
            PhoneTopBackdrop { BackdropStill(art().asImageBitmap(), MeshColors(Color(0xFF1B7FA3), Color(0xFFD8F3FF)), Modifier.fillMaxSize()) }
            PhoneBrowseView(
                (0..17).map { title(it) }, TitleEvents(watched = { it.key.endsWith("3") }), hasMore = false, onNearEnd = {},
                header = { PhoneMoviesControls(tabs, 1, {}, BrowseControls(BrowseSource.ALL, {}, count = "1,284 titles")) },
            )
        }
    }

    @Config(sdk = [34], qualifiers = "w412dp-h915dp-port-xxhdpi")
    @Test fun phoneDetail() = shot("phone-detail") {
        androidx.compose.runtime.CompositionLocalProvider(LocalIsPhone provides true) {
            Box(Modifier.fillMaxSize().background(Color(0xFF05060C))) {
                PhoneTopBackdrop(1f) { BackdropStill(art().asImageBitmap(), MeshColors(Color(0xFF1B7FA3), Color(0xFFD8F3FF)), Modifier.fillMaxSize()) }
                DetailView(
                    DetailData("Fathom Station", "Series", listOf("iptv"), "2023", "8.4", "3 Seasons", listOf("Thriller", "Sci-fi"),
                        "Eight researchers live two kilometres under the ocean on a station that was never meant to be found.",
                        status = "28 min left · Ballast · My list ✓"),
                    listOf(DetailButton("Resume S2 E4", Icons.Filled.PlayArrow, primary = true), DetailButton("My list", Icons.Filled.Check),
                        DetailButton("Trailer", Icons.Filled.Star), DetailButton("More", Icons.Filled.MoreVert)),
                    seasons = listOf("Season 1", "Season 2", "Season 3"), season = 1,
                    episodes = listOf(
                        EpCard("3", "E3 · Pressure Line", "Watched · 47 min", null, 1f, true), EpCard("4", "E4 · Ballast", "28 min left", null, 0.46f),
                        EpCard("5", "E5 · Thermocline", "52 min", null), EpCard("6", "E6 · The Quiet Hatch", "49 min", null), EpCard("7", "E7 · Surface", "55 min", null),
                    ),
                    watched = DetailButton("Watched", Icons.Filled.Check),
                )
            }
        }
    }

    private val liveMarks = mapOf("k1" to RowMarks(fav = true, playing = true), "k2" to RowMarks(fav = true, format = "HLS"), "k4" to RowMarks(rec = true))

    @Composable
    private fun phoneLive(guide: Boolean) {
        PhoneFrame(active = NavTab.LIVE, onTab = {}, onSearch = {}, onAvatar = {}, profileName = "Dani", title = "Live TV") {
            BackdropStill(null, MeshColors(Color(0xFFB8741A), Color(0xFFFFE2B0)), Modifier.fillMaxSize(), dim = true)
            PhoneLiveView(
                tabs = listOf("Channels", "Guide", "Recordings", "My list"), selected = if (guide) 1 else 0, onTab = {},
                preview = { mod -> Box(mod.background(Color.Black, RoundedCornerShape(12.dp))) { Image(art().asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } },
                nowPlaying = "The Ninth Lantern" to "Vista Cinema",
                groups = groups, group = "Favourites", groupLabel = { if (it == "Favourites") "★ Favourites" else it }, onGroup = {}, onGroupLong = {},
                guide = guide, channels = channels, programmes = { c -> progs[c.key] ?: emptyArray() }, now = now, marks = { c -> liveMarks[c.key] ?: RowMarks() },
                onChannel = {}, onChannelLong = {}, guideChannel = channels.first(), onGuideChannel = {},
                catchup = { _, _ -> true }, recorded = { _, pr -> pr.title == "Late Feature" }, onProgramme = { _, _ -> }, onProgrammeLong = { _, _ -> },
            )
        }
    }

    @Config(sdk = [34], qualifiers = "w412dp-h915dp-port-xxhdpi")
    @Test fun phoneLiveChannels() = shot("phone-live") { phoneLive(guide = false) }

    @Config(sdk = [34], qualifiers = "w412dp-h915dp-port-xxhdpi")
    @Test fun phoneLiveGuide() = shot("phone-guide") { phoneLive(guide = true) }

    private val phoneQuick = listOf(
        QuickAction("Favourite", icon = Icons.Filled.Star, tint = Color(0xFFFFD166), on = true) {}, QuickAction("Record", glyph = "●", tint = Color(0xFFFF6B6B)) {},
        QuickAction("Format · TS", glyph = "TS") {}, QuickAction("Start over", icon = Icons.Filled.Refresh) {}, QuickAction("Guide", icon = Icons.Filled.DateRange) {},
        QuickAction("Info", icon = Icons.Filled.Info) {}, QuickAction("More", glyph = "•••") {},
    )

    @Composable
    private fun phonePlayer() {
        Box(Modifier.fillMaxSize().background(Color(0xFF05060C))) {
            PhonePlayerView(
                playerInfo, phoneQuick, controls = true, PhonePlayerEvents(), hint = true,
                video = { Image(art().asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) },
            ) {
                item { PhoneListHeading("Channels · Favourites") }
                items(channels.size) { i -> val c = channels[i]; PhoneChannelRow(c, progs[c.key] ?: emptyArray(), now, liveMarks[c.key] ?: RowMarks(), {}) {} }
            }
        }
    }

    @Config(sdk = [34], qualifiers = "w412dp-h915dp-port-xxhdpi")
    @Test fun phonePlayerShot() = shot("phone-player") { phonePlayer() }

    @Config(sdk = [34], qualifiers = "w412dp-h915dp-port-xxhdpi")
    @Test fun phoneStreamPrompt() = shot("phone-stream-prompt") {
        phonePlayer()
        PhoneStreamSheet(
            2, "Vista Cinema",
            listOf(
                StreamChoice.Row("a1", "Kitchen", "Arcadia Sports · Harbor City v Northgate", false, t0 - 900),
                StreamChoice.Row("b2", "Bedroom", "Meridian News 24 · Market Close", false, t0),
            ),
            oldestOnly = false, onStop = {}, onCancel = {},
        )
    }
}
