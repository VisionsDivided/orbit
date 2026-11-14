// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.CompositionLocalProvider
import io.github.kmmarin.tvbro.ui.orbit5.LocalShellEntry
import io.github.kmmarin.tvbro.ui.orbit5.ShellEntry
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.kmmarin.tvbro.AppModel
import io.github.kmmarin.tvbro.Screen
import io.github.kmmarin.tvbro.Section
import io.github.kmmarin.tvbro.ui.FocusMemory
import io.github.kmmarin.tvbro.ui.SafeFocus
import io.github.kmmarin.tvbro.ui.SearchScreen
import io.github.kmmarin.tvbro.ui.SettingsScreen
import io.github.kmmarin.tvbro.ui.discover.DiscoverSection
import io.github.kmmarin.tvbro.ui.home.HomeSection
import io.github.kmmarin.tvbro.ui.live.LiveTvSection
import io.github.kmmarin.tvbro.ui.movies.MoviesShowsSection
import io.github.kmmarin.tvbro.ui.orbit5.ColorKeyLegend
import io.github.kmmarin.tvbro.ui.orbit5.LegacyDensity
import io.github.kmmarin.tvbro.ui.orbit5.NavTab
import io.github.kmmarin.tvbro.ui.orbit5.O5
import io.github.kmmarin.tvbro.ui.orbit5.O5Space
import io.github.kmmarin.tvbro.ui.orbit5.OrbitCanvas
import io.github.kmmarin.tvbro.ui.orbit5.TopBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The top-bar item a section belongs to (null: Search and Settings, reached from the icons on the right). */
fun navTabOf(s: Section): NavTab? = when (s) {
    Section.HOME -> NavTab.HOME
    Section.MOVIES -> NavTab.MOVIES
    Section.SHOWS -> NavTab.SHOWS
    Section.TV, Section.RECORDINGS, Section.MYLIST -> NavTab.LIVE
    Section.DISCOVER -> NavTab.DISCOVER
    Section.SEARCH -> null
}

/** Opens the section of a top-bar item. */
fun openTab(m: AppModel, t: NavTab) {
    m.screen = Screen.LIST
    when (t) {
        NavTab.HOME -> m.section = Section.HOME
        NavTab.MOVIES -> m.openVod(false)
        NavTab.SHOWS -> m.openVod(true)
        NavTab.LIVE -> if (navTabOf(m.section) != NavTab.LIVE) m.section = Section.TV
        NavTab.DISCOVER -> m.section = Section.DISCOVER
    }
}

/**
 * The app's main screen in direction 5 (docs/orbit/TV-UI.md): the top bar over the current section (or the Settings
 * page), and the colour-key legend. Replaces tvBro's side menu. On a phone it is [PhoneShell] instead.
 *
 * Focus: Up from the top of a section reaches the top bar at the current section's item; Down from the bar returns
 * to the item the user was on. Back from a section goes to Home; Back on Home leaves the app (Home itself first
 * takes focus back up to its hero).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun OrbitShell(m: AppModel) {
    // the same APK on a phone: the 5b layout (PhoneShell.kt)
    if (rememberIsPhone()) { PhoneShell(m); return }
    OrbitCanvas {
        val overlay = m.menu != null || m.prompt != null || m.progMenu != null
        BackHandler(enabled = m.screen == Screen.LIST && !overlay && m.section != Section.HOME) { m.section = Section.HOME }
        ShellFrame(
            active = if (m.screen == Screen.SETTINGS) null else navTabOf(m.section),
            onTab = { t -> openTab(m, t) },
            onSearch = { m.screen = Screen.LIST; m.section = Section.SEARCH },
            onAvatar = { m.settingsBack = Screen.LIST; m.screen = Screen.SETTINGS },
            profileName = m.orbit.link?.profile?.ifEmpty { null } ?: "Orbit",
            searchActive = m.section == Section.SEARCH && m.screen != Screen.SETTINGS,
            settingsActive = m.screen == Screen.SETTINGS,
            legend = m.screen == Screen.LIST,
        ) {
            // Settings is a page under the bar (spec section 12), reached from the avatar
            if (m.screen == Screen.SETTINGS) SettingsScreen(m)
            else when (m.section) {
                Section.HOME -> HomeSection(m)
                Section.MOVIES, Section.SHOWS -> MoviesShowsSection(m)
                Section.TV, Section.RECORDINGS, Section.MYLIST -> LiveTvSection(m)
                Section.DISCOVER -> DiscoverSection(m)
                Section.SEARCH -> LegacyPage(O5Space.TopBar) { SearchScreen(m) { m.section = Section.HOME } }
            }
        }
    }
}

/**
 * The shell without the app model (the JVM UI tests drive it with stand-in sections): [section] under the top bar,
 * the focus wiring between them, and the colour-key legend. Call inside an [OrbitCanvas].
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ShellFrame(
    active: NavTab?, onTab: (NavTab) -> Unit, onSearch: () -> Unit, onAvatar: () -> Unit, profileName: String,
    searchActive: Boolean = false, settingsActive: Boolean = false, legend: Boolean = true,
    section: @Composable BoxScope.() -> Unit,
) {
    val barFocus = remember { SafeFocus() }
    val scope = rememberCoroutineScope()
    val entry = remember { ShellEntry() }
    val sectionFr = remember { FocusRequester() }
    val sectionFocused = remember { booleanArrayOf(false) }
    Box(Modifier.fillMaxSize().background(O5.Bg)) {
        // the section; leaving it upwards always lands on the top bar's current item (not the nearest one)
        CompositionLocalProvider(LocalShellEntry provides entry) {
            Box(
                Modifier.fillMaxSize()
                    .onFocusChanged { sectionFocused[0] = it.hasFocus }
                    .focusRequester(sectionFr)
                    .focusProperties { exit = { d -> if (d == FocusDirection.Up) barFocus.target() else FocusRequester.Default } }
                    .focusGroup().testTag("section"),
                content = section,
            )
        }
        // the focus rescue (MainActivity) moves into the page this way instead of to the first item, the top bar
        androidx.compose.runtime.DisposableEffect(entry) {
            val f = { enterSection(entry, sectionFr) { !sectionFocused[0] } }
            ShellFocus.enter = f
            onDispose { if (ShellFocus.enter === f) ShellFocus.enter = null }
        }
        // OK on an item: its section opens and focus moves into it once it is there (pages load their rows a moment
        // later, so a few tries)
        val enter: () -> Unit = {
            scope.launch {
                repeat(12) { delay(if (it == 0) 300 else 150); if (sectionFocused[0] || enterSection(entry, sectionFr) { !sectionFocused[0] }) return@launch }
            }
        }
        TopBar(
            active = active, onTab = { t -> onTab(t); enter() }, onSearch = { onSearch(); enter() }, onAvatar = onAvatar,
            profileName = profileName, currentFocus = barFocus, searchActive = searchActive, settingsActive = settingsActive,
            onDown = { stillInBar -> enterSection(entry, sectionFr, stillInBar) },
        )
        if (legend && io.github.kmmarin.tvbro.ui.orbit5.ColorKeys.labels.isNotEmpty()) {
            // a footer the page scrolls under: the next row's heading fades out instead of showing through the legend
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(LegendFooter)
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(0f to O5.Bg.copy(alpha = 0f), 0.2f to O5.Bg.copy(alpha = 0.95f), 1f to O5.Bg.copy(alpha = 0.97f)))
                    .testTag("legend-footer"),
            )
            ColorKeyLegend(Modifier.align(Alignment.BottomStart).padding(start = O5Space.Side, bottom = 28.dp))
        }
    }
}

/**
 * Moves focus from the top bar into the page: the item last focused there, else the page's registered way in (the
 * sub-menu's active pill, then the hero's primary button: [ShellEntry]), else its first focusable item. A focus
 * request cannot report failure, so each try is checked with [notThereYet]. True when focus is in the page.
 */
/** Height of the footer band behind the colour-key legend; pages keep their last focusable content above it. */
val LegendFooter = 108.dp

/** The shell on screen: moves focus into its page (true when it got there); null when no shell is shown. */
object ShellFocus { var enter: (() -> Boolean)? = null }

internal fun enterSection(entry: ShellEntry, sectionFr: FocusRequester, notThereYet: () -> Boolean): Boolean {
    if (FocusMemory.restoreContent() && !notThereYet()) return true
    for (t in entry.targets()) if (t.request() && !notThereYet()) return true
    runCatching { sectionFr.requestFocus() }
    return !notThereYet()
}

/**
 * A legacy tvBro screen inside the shell, below [top] (spec pixels): drawn at the device's own density until it is
 * restyled.
 */
@Composable
fun LegacyPage(top: Dp, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    if (LocalIsPhone.current) {
        // phone: below the header (or [top] when the page sits under a sub-menu) and above the bottom bar
        val i = LocalPhoneInsets.current
        Box(modifier.fillMaxSize().padding(top = if (top > O5Space.TopBar) top else i.top, bottom = i.bottom), content = content)
        return
    }
    Box(modifier.fillMaxSize().padding(top = top)) { LegacyDensity { Box(Modifier.fillMaxSize(), content = content) } }
}
