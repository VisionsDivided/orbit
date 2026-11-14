// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.use
import io.github.kmmarin.tvbro.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Off-screen renders of the direction-5 screens at 1280 × 760 (the default window) into desktop/build/orbit-shots, with
 * made-up titles, channels and pictures (no network). A visual check only: nothing compares the images. The player
 * overlay is drawn by mpv (ASS) and is not in here; PlayerOsdTest checks its layout.
 */
class DesignShotsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir = File(System.getProperty("user.dir"), "build/orbit-shots").apply { mkdirs() }

    @After fun done() { scope.cancel() }

    /** A made-up picture: a diagonal gradient in two colours with a darker band (posters, backdrops). */
    private fun picture(url: String, w: Int, h: Int, c1: Int, c2: Int) {
        val s = org.jetbrains.skia.Surface.makeRasterN32Premul(w, h)
        val p = org.jetbrains.skia.Paint().apply {
            shader = org.jetbrains.skia.Shader.makeLinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(c1, c2))
        }
        s.canvas.drawRect(org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()), p)
        s.canvas.drawCircle(w * .7f, h * .35f, minOf(w, h) * .22f, org.jetbrains.skia.Paint().apply { color = 0x55FFFFFF })
        Images.prime(url, s.makeImageSnapshot().toComposeImageBitmap())
    }

    private val hues = listOf(0xFFC3261B.toInt() to 0xFF2A0606.toInt(), 0xFF16968A.toInt() to 0xFF04211E.toInt(), 0xFF1B7FA3.toInt() to 0xFF041826.toInt(),
        0xFF6A3FD8.toInt() to 0xFF150A33.toInt(), 0xFFD8902A.toInt() to 0xFF2B1604.toInt(), 0xFF3FA34A.toInt() to 0xFF071F0A.toInt())

    private fun app(): AppState {
        File(Paths.config, "settings.json").delete()
        val app = AppState(scope, emptyList())
        val t0 = System.currentTimeMillis()
        while ((!app.status.startsWith("Add a source") || !app.uiRestoreDone) && System.currentTimeMillis() - t0 < 10_000) Thread.sleep(50)
        val names = listOf("Paper Comets", "Glass Harbor", "The Long Meridian", "Northbound", "Quiet Saltwater", "Velvet Static", "Ironwood", "Ember Line",
            "A Map of Tuesdays", "Kestrel & Vane", "Night Ferry", "Copper Sky", "The Ninth Lantern", "Wildglass", "Lumen", "Harbor Lights")
        val cats = listOf("Drama", "Sci-fi", "Thriller", "Comedy")
        val movies = names.mapIndexed { i, n -> VodItem("m|p0|$i", "p0", "movie", "$i", n, "http://shots.example/p$i.jpg", cats[i % 4], "%.1f".format(java.util.Locale.ROOT, 6.5 + (i % 7) * .4), "mp4") }
        val series = names.reversed().mapIndexed { i, n -> VodItem("s|p0|$i", "p0", "series", "$i", "$n Station", "http://shots.example/s$i.jpg", cats[(i + 1) % 4], "8.${i % 9}", "") }
        names.indices.forEach { i -> val (a, b) = hues[i % hues.size]; picture("http://shots.example/p$i.jpg", 240, 360, a, b); picture("http://shots.example/s$i.jpg", 240, 360, b, a) }
        picture("http://shots.example/b0.jpg", 1280, 720, hues[2].first, hues[2].second)
        val now = System.currentTimeMillis() / 1000
        val chs = listOf("Vista Cinema", "Arcadia Sports", "Nova One", "Meridian News 24", "Lumen Drama", "Harbor Kids", "Atlas Docs", "Pulse Music")
            .mapIndexed { i, n -> Channel("p0|c$i", "p0", n, null, if (i % 2 == 0) "Entertainment" else "News", "http://shots.example:8080/live/u/p/${100 + i}.ts", "c$i", 101 + i, if (i < 3) Catchup("xc", null) else null, 3) }
        val epg = chs.associate { c ->
            val off = (c.number % 3) * 900L
            c.idKey!! to arrayOf(
                Programme(c.idKey!!, now - 2400 - off, now + 1500 - off, listOf("The Ninth Lantern", "Harbor City v Northgate", "Fathom Station", "Market Close")[c.number % 4], "A night-shift signal engineer has one hour to reroute a burning freight train.", null, "S2 E4".takeIf { c.number % 3 == 0 }),
                Programme(c.idKey!!, now + 1500 - off, now + 5100 - off, listOf("Late Feature", "Post-match", "The Late Room", "Meridian Debate")[c.number % 4], null),
                Programme(c.idKey!!, now + 5100 - off, now + 9000, "Night Ferry", null),
            )
        }
        val eps = (1..8).map { Episode("e$it", 2, it, listOf("Pressure Line", "Ballast", "Thermocline", "The Quiet Hatch", "Surface", "Undertow", "Brine", "Daylight")[it - 1], "mkv", "Plot $it", "52:00") } +
            (1..6).map { Episode("f$it", 1, it, "Episode $it", "mkv", null, "50:00") }
        app.detailCache[series[0].key] = VodDetail("Eight researchers live two kilometres under the ocean on a station that was never meant to be found. When the surface goes silent, the crew has to decide who they can trust.",
            "2023", "8.4", "A. Example, B. Example", "Thriller, Sci-fi", null, null, eps, "C. Example", backdrop = "http://shots.example/b0.jpg")
        val ok = CompletableDeferred<Unit>()
        app.runOnUi {
            app.movies = movies; app.series = series; app.channels = chs; app.epg = EpgIndex(epg)
            app.update { it.copy(myList = listOf(movies[2].key, series[1].key, chs[0].key), searchHistory = listOf("lantern", "harbor", "news"),
                channelFormats = ChannelFormats().with(chs[1].key, ChannelFormats.HLS, 1).toJson().toString()) }
            app.watch.snap(movies[3]); app.watch.setPos(movies[3].key, 40 * 60_000L, 110 * 60_000L, null, false)
            ok.complete(Unit)
        }
        kotlinx.coroutines.runBlocking { ok.await() }
        return app
    }

    private fun shot(name: String, w: Int = 1280, h: Int = 760, content: @Composable () -> Unit) {
        val f = File(dir, "$name.png")
        ImageComposeScene(w, h) { MaterialTheme(colorScheme = darkColorScheme()) { content() } }.use { s ->
            var t = 0L
            repeat(14) { s.render(t); Thread.sleep(120); t += 250_000_000L }
            f.writeBytes(s.render(t).encodeToData()!!.bytes)
        }
        assertTrue(f.length() > 10_000)
    }

    private fun onUi(app: AppState, f: () -> Unit) { val d = CompletableDeferred<Unit>(); app.runOnUi { f(); d.complete(Unit) }; kotlinx.coroutines.runBlocking { d.await() } }

    @Test fun screens() {
        val app = app()
        shot("home") { Home(app) }
        onUi(app) { app.tab = Tab.MOVIES }
        shot("movies-recommended") { Home(app) }
        onUi(app) { app.vodPages[false] = VodPage.BROWSE }
        shot("movies-browse") { Home(app) }
        onUi(app) { app.vodPages[false] = VodPage.COLLECTIONS }
        shot("movies-collections") { Home(app) }
        onUi(app) { app.tab = Tab.SHOWS; app.detail = app.series[0] }
        shot("title-show") { Home(app) }
        onUi(app) { app.detail = null; app.tab = Tab.SEARCH }
        shot("search") { Home(app) }
        onUi(app) { app.tab = Tab.TV; app.update { it.copy(previewOn = false) } }
        shot("guide") { Home(app) }
        // signed in: Home, Browse and Collections from a made-up home server's catalogue
        val it = (0 until 12).map { i -> FakeOrbit.item("m|p0|$i", app.movies[i].name, genres = listOf(listOf("Drama", "Sci-fi", "Thriller")[i % 3])) }
        val plex = FakeOrbit.item("m|plex1|77", "Starfall Ridge", source = "plex", poster = "http://shots.example/s5.jpg", backdrop = "http://shots.example/b0.jpg",
            colors = listOf("#1b7fa3", "#d8f3ff"), extra = """, "progress": {"secs": 1500, "dur": 6000}""")
        val fake = FakeOrbit(mapOf(
            "/v1/catalog/home" to """{"hero": [$plex], "rows": [{"id": "continue", "title": "Continue watching", "items": [$plex, ${it[3]}]},
                {"id": "mylist", "title": "My list", "items": [${it.take(6).joinToString()}]}, {"id": "recent", "title": "Recently added", "items": [${it.drop(4).joinToString()}]}]}""",
            "/v1/catalog/movies/browse" to """{"total": 1284, "genres": ["Drama", "Sci-fi", "Thriller"], "items": [$plex, ${it.joinToString()}]}""",
            "/v1/catalog/movies/collections" to """{"collections": [{"id": "c1", "name": "The Hollowmere Saga", "count": 8, "poster": "http://shots.example/p3.jpg", "backdrop": "http://shots.example/b0.jpg"},
                {"id": "c2", "name": "Harbor Lights", "count": 3, "poster": "http://shots.example/p5.jpg"}, {"id": "c3", "name": "Copper Sky", "count": 4, "poster": "http://shots.example/p7.jpg"}]}""",
        ))
        onUi(app) {
            app.orbit.showForTest(OrbitSaved(server = "http://192.0.2.10:8750", session = "x", account = "Example household", username = "dani", profile = "Dani", profileId = "1",
                profiles = listOf(SavedProfile("1", "Dani", false), SavedProfile("2", "Maya", false), SavedProfile("3", "Leo", true), SavedProfile("4", "Guest", false))))
            app.catalog.api = fake.catalog
            app.tab = Tab.HOME
        }
        shot("home-signed-in") { Home(app) }
        onUi(app) { app.tab = Tab.MOVIES; app.vodPages[false] = VodPage.BROWSE }
        shot("movies-browse-signed-in") { Home(app) }
        onUi(app) { app.vodPages[false] = VodPage.COLLECTIONS }
        shot("movies-collections-signed-in") { Home(app) }
        onUi(app) { app.tab = Tab.SETTINGS; app.settingsPage = SetCat.PROFILES.ordinal }
        shot("settings-profiles") { Home(app) }
        onUi(app) { app.settingsPage = SetCat.LIVE_TV.ordinal }
        shot("settings-livetv") { Home(app) }
        shot("who-orbit") { WhoScreen(app) }
        onUi(app) { app.update { it.copy(pickerStyle = "classic") } }
        shot("who-classic") { WhoScreen(app) }
        val q = StreamAsk(2, StreamChoice.rows(listOf(
            StreamChoice.Use("a1", "TV (Model)", "Maya", "Arcadia Sports · Harbor City v Northgate", false, "Kitchen", System.currentTimeMillis() / 1000 - 1800),
            StreamChoice.Use("b2", "Chrome on Windows", "Leo", "Meridian News 24 · Market Close", false, "Bedroom", System.currentTimeMillis() / 1000 - 700))), CompletableDeferred(), "Vista Cinema")
        val sz = streamAskSize(q.rows.size)
        shot("stream-prompt", sz.width.value.toInt(), sz.height.value.toInt()) { StreamAskContent(app, q) {} }
    }
}
