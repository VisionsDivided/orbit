// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.launch
import io.github.kmmarin.tvbro.data.TvNav
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import io.github.kmmarin.tvbro.data.Diag
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager

/** App colours: Orbit direction 5 (DESIGN-SPEC-5 §1; O5 in OrbitUi.kt has the rest). */
object Theme {
    val bg = Color(0xFF05060C)
    val surface = Color(0xFF0E101C)
    val surface2 = Color(0xFF1A1D2E)
    val accent = Color(0xFF2FD3FF)
    val text = Color(0xFFF4F6FF)
    val dim = Color(0xA3E4E9FF)
    val live = Color(0xFFFF3B5C)
    val ok = Color(0xFF7FF0BB)
}

/** The Orbit app icon (branding/orbit-app-icon.webp at 256 px): window, taskbar and the missing-library screen. */
val AppIcon: Painter by lazy {
    val bytes = Thread.currentThread().contextClassLoader?.getResourceAsStream("orbit-app-icon.png")?.use { it.readBytes() }
        ?: AppDirs::class.java.getResourceAsStream("/orbit-app-icon.png")?.use { it.readBytes() }
    if (bytes != null) androidx.compose.ui.graphics.painter.BitmapPainter(
        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
    else androidx.compose.ui.graphics.painter.ColorPainter(Theme.accent)
}

/**
 * Test options: --m3u <file|url> adds a temporary (not saved) playlist source; --play <n> plays its n-th channel;
 * --screenshot <png> --exit-after <s> saves a screen capture and quits (used for unattended smoke tests).
 */
fun main(args: Array<String>) {
    val fileLog = if (Platform.mac) try { FileLog(java.io.File(Paths.logs, "tvbro.log")) } catch (e: Exception) { null } else null // ~/Library/Logs/tvBro
    Diag.sink = { System.err.println("tvbro: $it"); fileLog?.line(it) }
    Thread({ Perms.tighten(Paths.cache) }, "tighten-cache").apply { isDaemon = true; start() } // 0700 / 0600 (Linux)
    fun arg(n: String) = args.indexOf(n).takeIf { it >= 0 && it + 1 < args.size }?.let { args[it + 1] }
    arg("--render-missing")?.let { png -> renderMissing(png); return }
    arg("--selftest")?.let { url -> selfTest(url, arg("--render"), "--no-play" !in args); return }
    application {
        val scope = rememberCoroutineScope()
        val app = remember {
            AppState(scope, arg("--m3u")?.let { listOf(io.github.kmmarin.tvbro.data.Source("test-m3u", "Test playlist", "m3u", url = it)) })
                .also { it.videoSetup = MpvLoader.lib == null } // tvBro does not ship libmpv: explain how to add it
        }
        LaunchedEffect(Unit) {
            arg("--play")?.toIntOrNull()?.let { n -> while (app.channels.isEmpty()) kotlinx.coroutines.delay(200); app.channels.getOrNull(n)?.let(app::playChannel) }
            arg("--exit-after")?.toLongOrNull()?.let { s ->
                kotlinx.coroutines.delay(s * 1000)
                arg("--screenshot")?.let { f ->
                    val img = java.awt.Robot().createScreenCapture(java.awt.Rectangle(java.awt.Toolkit.getDefaultToolkit().screenSize))
                    javax.imageio.ImageIO.write(img, "png", java.io.File(f))
                }
                app.shutdown(); exitApplication()
            }
        }
        val ws = rememberWindowState(size = androidx.compose.ui.unit.DpSize(1280.dp, 760.dp))
        LaunchedEffect(app.fullscreen) { ws.placement = if (app.fullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating }
        // An arrow or Enter that reached no focused item (the focused one went away): focus returns where it was.
        Window(onCloseRequest = { app.shutdown(); exitApplication() }, state = ws, title = "Orbit", icon = AppIcon, onKeyEvent = { e ->
            if (e.type != KeyEventType.KeyDown || app.videoSetup || app.homeHasFocus || app.full || app.overlayOpen) false
            else if (e.key in listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.Enter)) { app.refocus(); true }
            else false
        }) {
            // Full-screen player keys come straight from AWT: the native video surface never has Compose focus.
            DisposableEffect(Unit) {
                val d = KeyEventDispatcher { e ->
                    val mine = javax.swing.SwingUtilities.getWindowAncestor(e.component) === window || e.component === window
                    if (!app.full || app.playing == null || app.overlayOpen || !mine) false
                    else if (e.id == java.awt.event.KeyEvent.KEY_PRESSED) app.player.key(e)
                    else true // releases and typed characters belong to the player too
                }
                KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(d)
                onDispose { KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(d) }
            }
            MaterialTheme(colorScheme = darkColorScheme(primary = Theme.accent, background = Theme.bg, surface = Theme.surface, onSurface = Theme.text)) {
                if (app.videoSetup) VideoSetup(app) else {
                    Box(Modifier.fillMaxSize().background(Theme.bg)) {
                        if (app.orbit.picking) WhoScreen(app) else Home(app)
                        VideoHost(app) // last: drawn above Home, at a stable place in the tree
                    }
                    Overlays(app)
                    StreamAskDialog(app, window)
                }
            }
        }
    }
}

/** The "Video library missing" screen wired to the loader: pick the file, check again, the guide, or go on. */
@Composable
fun VideoSetup(app: AppState) {
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(MpvLoader.error) }
    MissingLibScreen(Platform.os, MpvLoader.appDir, error, note,
        onChoose = {
            val f = pickLibmpv()
            if (f != null) {
                val why = MpvLoader.choose(f)
                if (why == null) { app.videoSetup = false; app.toast("Video library loaded: ${f.name}") } else { note = why; error = MpvLoader.error }
            }
        },
        onRetry = {
            if (MpvLoader.reload() != null) { app.videoSetup = false; app.toast("Video library loaded") }
            else { error = MpvLoader.error; note = "Still not found. Check the steps above." }
        },
        onDocs = { openInstallDoc()?.let { note = it } },
        onSkip = { app.videoSetup = false },
    )
}

/** Nothing further that way inside a screen: the top bar (never a dead end). */
private fun inScreenEdge(app: AppState, scope: kotlinx.coroutines.CoroutineScope): Boolean { scope.launch { app.railFr.settle() }; return true }

/**
 * The main window in Orbit direction 5 (Cinema.kt): a colour-matched backdrop, the top bar (Home, Movies, Shows,
 * Live TV, Discover; search, clock, profile) and the section below it. Keyboard: Up / Esc reach the top bar, Left and
 * Right move along it, Down goes back into the section; the mouse works everywhere.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Home(app: AppState) {
    val fm = LocalFocusManager.current
    var inRail by remember { mutableStateOf(false) }
    // Leaving a section for another: a preview that is not on screen stops.
    LaunchedEffect(app.tab, app.detail) { if (!app.full && app.playing != null && (app.tab != Tab.TV || app.detail != null)) app.stopPlayback() }
    val scope = rememberCoroutineScope()
    val hero = remember { HeroState() }
    var discoverArt by remember { mutableStateOf<String?>(null) }
    val d = app.detail
    val artItem = when {
        d != null -> d
        app.tab == Tab.HOME || app.tab == Tab.MOVIES || app.tab == Tab.SHOWS -> hero.item
        else -> null
    }
    val art: String? = if (app.tab == Tab.DISCOVER && d == null) discoverArt else artItem?.let { app.catalog.art(it) }
    val colors = artItem?.let { CatalogMap.mesh(app.catalog.info[it.key]?.colors) }
    // the colour keys belong to the screen shown: a new screen starts without any until something takes the focus
    LaunchedEffect(app.tab, d?.key) { app.colorKeys = null }
    Box(Modifier.fillMaxSize().background(Theme.bg).onFocusChanged { app.homeHasFocus = it.hasFocus }.onKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
        when {
            // Back closes the topmost layer; menus and questions are their own windows and close themselves first,
            // full screen takes its keys from AWT. Here: a page (details, a collection, Manage profiles), else the top bar.
            e.key == Key.Escape || (e.key == Key.Backspace && !typing) -> when (TvNav.top(false, false, false, app.detail != null || app.pageBack != null)) {
                TvNav.Layer.PAGE -> { if (app.detail != null) app.detail = null else app.pageBack?.invoke(); true }
                else -> if (inRail) false else { typing = false; scope.launch { app.railFr.settle() }; true }
            }
            e.key in listOf(Key.F1, Key.F2, Key.F3, Key.F4) -> app.colorKeys?.forKey(e.key)?.let { it.act(); true } ?: false
            typing -> false
            e.key == Key.DirectionUp -> if (inRail) true else fm.moveFocus(FocusDirection.Up) || inScreenEdge(app, scope)
            e.key == Key.DirectionDown -> {
                val t = app.railReturn
                if (inRail && t != null && app.detail == null && app.tab == Tab.TV) t() else fm.moveFocus(FocusDirection.Down)
            }
            e.key == Key.DirectionLeft -> if (inRail) { fm.moveFocus(FocusDirection.Left); true } else fm.moveFocus(FocusDirection.Left) || inScreenEdge(app, scope)
            e.key == Key.DirectionRight -> if (inRail) { fm.moveFocus(FocusDirection.Right); true } else fm.moveFocus(FocusDirection.Right)
            e.key == Key.F11 -> { app.fullscreen = !app.fullscreen; true }
            e.key == Key.Y || e.key == Key.F || e.key == Key.Bookmark -> app.favAction?.let { it(); true } ?: false
            else -> false
        }
    }) {
        val cinematic = d != null || app.tab in listOf(Tab.HOME, Tab.MOVIES, Tab.SHOWS, Tab.DISCOVER)
        // text-heavy pages (guide, search, settings): the orbit mesh with the stronger bottom scrim (§6)
        Backdrop(if (cinematic) art else null, strongBottom = !cinematic, colors = if (cinematic) colors else null)
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.onFocusChanged { inRail = it.hasFocus }) { TopBar(app) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // each screen is a focus group that restores its last focused item when focus comes back from the top bar
                if (d != null) Box(Modifier.fillMaxSize()) { DetailScreen(app, d) }
                else key(app.settings.timeFmt, app.tab) { Box(Modifier.fillMaxSize().focusRestorer().focusGroup()) { when (app.tab) {
                    Tab.HOME -> HomeScreen(app, hero)
                    Tab.MOVIES -> VodSection(app, app.movies, false, hero)
                    Tab.SHOWS -> VodSection(app, app.series, true, hero)
                    Tab.DISCOVER -> DiscoverScreen(app) { discoverArt = it }
                    Tab.TV -> Box(Modifier.padding(start = 56.dp, end = 56.dp, bottom = 52.dp)) { TvScreen(app) }
                    Tab.MYLIST -> Box(Modifier.padding(horizontal = 56.dp, vertical = 8.dp)) { MyListScreen(app) }
                    Tab.RECORDINGS -> Box(Modifier.padding(horizontal = 56.dp, vertical = 8.dp)) { RecordingsScreen(app) }
                    Tab.SEARCH -> Box(Modifier.padding(start = 56.dp, end = 56.dp, top = 4.dp)) { SearchScreen(app) }
                    Tab.SETTINGS -> SettingsScreen(app)
                } } }
            }
        }
        // the colour-key legend at the bottom left (DESIGN-SPEC-5 §9), notices just above it
        val keys = app.colorKeys
        if (keys != null && !app.full) KeyLegend(keys, Modifier.align(Alignment.BottomStart).padding(start = 56.dp, bottom = 18.dp))
        if (app.toastMsg.isNotEmpty() || (app.busy && app.sources.isNotEmpty())) Box(Modifier.align(Alignment.BottomStart).padding(start = 56.dp, bottom = if (keys != null) 58.dp else 18.dp)
            .background(Color(0xC705060C), RoundedCornerShape(50)).border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(50)).padding(18.dp, 8.dp)) {
            T(app.toastMsg.ifEmpty { app.status }, 13, if (app.toastMsg.isNotEmpty()) Theme.text else Theme.dim, maxLines = 3)
        }
    }
}