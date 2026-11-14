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
import io.github.kmmarin.tvbro.ui.shell.PhoneFrame
import io.github.kmmarin.tvbro.ui.home.HomeRow
import io.github.kmmarin.tvbro.ui.home.PhoneHomeContent
import io.github.kmmarin.tvbro.ui.home.Tile
import io.github.kmmarin.tvbro.ui.discover.DiscoverActions
import io.github.kmmarin.tvbro.ui.discover.DiscoverContent
import io.github.kmmarin.tvbro.ui.discover.DiscoverState
import io.github.kmmarin.tvbro.ui.SettingsFrame
import io.github.kmmarin.tvbro.ui.PickerPreview
import io.github.kmmarin.tvbro.ui.profileRows
import io.github.kmmarin.tvbro.orbit.DiscoverRequest
import io.github.kmmarin.tvbro.orbit.DiscoverRow
import io.github.kmmarin.tvbro.orbit.DiscoverTitle
import io.github.kmmarin.tvbro.orbit.DownloadInfo
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
            DiscoverRow("trending-movies", "Trending movies", listOf(t(1, "Starfall Ridge"), t(2, "The Last Cartographer"), t(3, "Mirror Lake", status = "available"),
                t(4, "Saffron Engine", req = "pending", status = "pending"), t(5, "Halcyon Drift"), t(6, "Paper Comets"), t(7, "Orchid Protocol"), t(8, "The Long Meridian"), t(9, "Quiet Machines"))),
            DiscoverRow("trending-shows", "Trending shows", listOf(t(11, "Tidewater", "tv"), t(12, "Undertow Club", "tv"))),
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
}
