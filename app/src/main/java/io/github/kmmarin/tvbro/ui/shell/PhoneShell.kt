// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.shell

import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.kmmarin.tvbro.AppModel
import io.github.kmmarin.tvbro.Screen
import io.github.kmmarin.tvbro.Section
import io.github.kmmarin.tvbro.ui.SearchScreen
import io.github.kmmarin.tvbro.ui.SettingsScreen
import io.github.kmmarin.tvbro.ui.discover.DiscoverSection
import io.github.kmmarin.tvbro.ui.home.PhoneHome
import io.github.kmmarin.tvbro.ui.live.LiveTvSection
import io.github.kmmarin.tvbro.ui.movies.MoviesShowsSection
import io.github.kmmarin.tvbro.ui.orbit5.NavTab
import io.github.kmmarin.tvbro.ui.orbit5.O5
import io.github.kmmarin.tvbro.ui.orbit5.PhoneBarHeight
import io.github.kmmarin.tvbro.ui.orbit5.PhoneBottomBar
import io.github.kmmarin.tvbro.ui.orbit5.PhoneHeader

/**
 * True when the shell runs the phone 5b layout (bottom bar, phone header, touch-sized rows): the same APK on a phone.
 * Sections read it and draw their phone variant; on phones there is no [io.github.kmmarin.tvbro.ui.orbit5.OrbitCanvas],
 * so 1 dp is the phone's own dp (the spec's phone CSS px).
 */
val LocalIsPhone = staticCompositionLocalOf { false }

/** Room the phone shell's header and bottom bar take (sections pad their scrolling content by these). */
@Immutable
data class PhoneInsets(val top: Dp, val bottom: Dp)

val LocalPhoneInsets = staticCompositionLocalOf { PhoneInsets(0.dp, 0.dp) }

/**
 * Phone or TV layout: a touch screen that is not a TV (no leanback) and is compact (smallest width under 600 dp) or
 * held upright. TVs, and tablets lying sideways, keep the TV layout.
 */
fun isPhoneLayout(tv: Boolean, touch: Boolean, smallestWidthDp: Int, widthDp: Int, heightDp: Int): Boolean =
    !tv && touch && (smallestWidthDp < 600 || heightDp > widthDp)

@Composable
fun rememberIsPhone(): Boolean {
    val ctx = LocalContext.current
    val conf = LocalConfiguration.current
    val (tv, touch) = remember {
        val pm = ctx.packageManager
        val ui = ctx.getSystemService(android.content.Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) || ui?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) to
            pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }
    return isPhoneLayout(tv, touch, conf.smallestScreenWidthDp, conf.screenWidthDp, conf.screenHeightDp)
}

/**
 * The app's main screen on a phone (spec section 13, "Phone 5b"): the header (logo or page title, search, avatar)
 * over the current section, and the bottom bar with Home, Movies, Shows, Live and Discover. Settings opens from the
 * avatar as a page. Back from a section goes to Home, as on the TV.
 */
@Composable
fun PhoneShell(m: AppModel) {
    val overlay = m.menu != null || m.prompt != null || m.progMenu != null
    val settings = m.screen == Screen.SETTINGS
    BackHandler(enabled = m.screen == Screen.LIST && !overlay && m.section != Section.HOME) { m.section = Section.HOME }
    val title = when {
        settings -> "Settings"
        m.section == Section.HOME -> null
        m.section == Section.SEARCH -> "Search"
        m.section == Section.MOVIES -> "Movies"
        m.section == Section.SHOWS -> "Shows"
        m.section == Section.DISCOVER -> "Discover"
        else -> "Live TV"
    }
    PhoneFrame(
        active = if (settings) null else navTabOf(m.section),
        onTab = { t -> openTab(m, t) },
        onSearch = { m.screen = Screen.LIST; m.section = Section.SEARCH },
        onAvatar = { if (settings) { m.screen = m.settingsBack } else { m.settingsBack = Screen.LIST; m.screen = Screen.SETTINGS } },
        profileName = m.orbit.link?.profile?.ifEmpty { null } ?: "Orbit",
        title = title, settingsActive = settings,
    ) {
        if (settings) SettingsScreen(m)
        else when (m.section) {
            Section.HOME -> PhoneHome(m)
            Section.MOVIES, Section.SHOWS -> MoviesShowsSection(m)
            Section.TV, Section.RECORDINGS, Section.MYLIST -> LiveTvSection(m)
            Section.DISCOVER -> DiscoverSection(m)
            Section.SEARCH -> LegacyPage(0.dp) { SearchScreen(m) { m.section = Section.HOME } }
        }
    }
}

/**
 * The phone shell without the app model (tests): [section] full screen under the header and above the bottom bar.
 * Sections get [LocalIsPhone] and [LocalPhoneInsets] (the header's and bar's height) and draw behind both, so
 * backdrops reach the top of the screen.
 */
@Composable
fun PhoneFrame(
    active: NavTab?, onTab: (NavTab) -> Unit, onSearch: () -> Unit, onAvatar: () -> Unit, profileName: String,
    title: String? = null, settingsActive: Boolean = false, section: @Composable BoxScope.() -> Unit,
) {
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val insets = PhoneInsets(top = status + 6.dp + 52.dp, bottom = PhoneBarHeight + nav)
    CompositionLocalProvider(LocalIsPhone provides true, LocalPhoneInsets provides insets) {
        Box(Modifier.fillMaxSize().background(O5.Bg).testTag("phone-shell")) {
            Box(Modifier.fillMaxSize().testTag("section"), content = section)
            PhoneHeader(title, profileName, onSearch, onAvatar, Modifier.padding(top = status + 6.dp), settingsActive = settingsActive)
            PhoneBottomBar(active, onTab, Modifier.align(Alignment.BottomCenter), bottomInset = nav)
        }
    }
}
