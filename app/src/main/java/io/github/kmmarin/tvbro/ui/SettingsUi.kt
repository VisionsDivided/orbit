// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kmmarin.tvbro.AppModel
import io.github.kmmarin.tvbro.Screen
import io.github.kmmarin.tvbro.Section
import io.github.kmmarin.tvbro.orbit.DiscoverApi
import io.github.kmmarin.tvbro.orbit.DiscoverStatus
import io.github.kmmarin.tvbro.orbit.PickerStyle
import io.github.kmmarin.tvbro.ui.orbit5.AvatarColors
import io.github.kmmarin.tvbro.ui.orbit5.Avatar
import io.github.kmmarin.tvbro.ui.orbit5.BackdropStill
import io.github.kmmarin.tvbro.ui.orbit5.ColorKeyLegend
import io.github.kmmarin.tvbro.ui.orbit5.LegacyDensity
import io.github.kmmarin.tvbro.ui.orbit5.LocalBaseDensity
import io.github.kmmarin.tvbro.ui.orbit5.MeshColors
import io.github.kmmarin.tvbro.ui.orbit5.O5
import io.github.kmmarin.tvbro.ui.orbit5.O5Space
import io.github.kmmarin.tvbro.ui.orbit5.O5Type
import io.github.kmmarin.tvbro.ui.orbit5.SubMenuRow
import io.github.kmmarin.tvbro.ui.shell.LocalIsPhone
import io.github.kmmarin.tvbro.ui.shell.LocalPhoneInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/*
 * Settings in direction 5 (spec section 12): a page under the top bar, reached from the avatar, with the categories
 * in the sub-menu row. Rows are 22 x 28 with radius 16 on a translucent panel, 25 px labels with 19 px muted
 * descriptions; the focused row has a 4 px white ring. Sizes are spec pixels (the page is inside OrbitCanvas; on a
 * phone it is the same page scaled down, see [SettingsScaled]).
 */

private val RowShape = RoundedCornerShape(16.dp)
private val RowBg = Color(0x800A0C16)                   // rgba(10,12,22,.5)

/** The Settings categories (spec section 12), in order. */
val SettingsCategories = listOf("Profiles", "Playback", "Live TV", "Sources", "Requests", "Appearance", "About")

/** A settings row's focus: the 4 px white ring (spec section 12), remembered in [FocusMemory] like every item. */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.settingFocus(onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    val me = remember { SafeFocus() }
    val was = remember { booleanArrayOf(false) }
    SideEffect { if (f != was[0]) { was[0] = f; if (f) { FocusMemory.gained(); FocusMemory.focused(me) } else FocusMemory.lost() } }
    DisposableEffect(Unit) { onDispose { if (was[0]) { was[0] = false; FocusMemory.lost() } } }
    this.safeFocus(me)
        .background(RowBg, RowShape)
        .border(if (f) 4.dp else 0.dp, if (f) Color.White else Color.Transparent, RowShape)
        .clip(RowShape)
        .combinedClickable(interactionSource = src, indication = null, onClick = onClick)
}

private fun Modifier.leftRight(onLeft: (() -> Unit)?, onRight: (() -> Unit)?): Modifier = onPreviewKeyEvent { e ->
    if (e.type != KeyEventType.KeyDown) false
    else when (e.key) {
        Key.DirectionLeft -> onLeft?.let { it(); true } ?: false
        Key.DirectionRight -> onRight?.let { it(); true } ?: false
        else -> false
    }
}

/**
 * A settings row: [title] (25 px / 600) with an optional [desc] (19 px muted) and its [value] on the right. Left /
 * Right run [onLeft] / [onRight] (cycling values), OK runs [onClick].
 */
@Composable
internal fun SettingRow(
    title: String, value: String, onLeft: (() -> Unit)? = null, onRight: (() -> Unit)? = null, mod: Modifier = Modifier,
    desc: String? = null, onClick: () -> Unit,
) {
    Row(
        mod.fillMaxWidth().padding(vertical = 6.dp).leftRight(onLeft, onRight).settingFocus(onClick).padding(horizontal = 28.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = O5.Text, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
            desc?.let { Text(it, color = O5.Muted, fontSize = 19.sp, modifier = Modifier.padding(top = 4.dp)) }
        }
        if (value.isNotEmpty()) Text(value, color = O5.MetaText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 24.dp).widthIn(max = 640.dp))
    }
}

/** On / off row with the spec's toggle (66 x 36 accent pill, 28 px white knob). */
@Composable
internal fun ToggleRow(title: String, on: Boolean, desc: String? = null, mod: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(
        mod.fillMaxWidth().padding(vertical = 6.dp).leftRight({ onChange(false) }, { onChange(true) }).settingFocus { onChange(!on) }
            .padding(horizontal = 28.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = O5.Text, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
            desc?.let { Text(it, color = O5.Muted, fontSize = 19.sp, modifier = Modifier.padding(top = 4.dp)) }
        }
        Toggle(on)
    }
}

@Composable
internal fun Toggle(on: Boolean) {
    Box(Modifier.size(66.dp, 36.dp).background(if (on) O5.Accent else Color(0x2EFFFFFF), RoundedCornerShape(18.dp)).padding(4.dp), contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.size(28.dp).background(Color.White, CircleShape))
    }
}

/** A row with a segmented control (19 px, 6 x 16; selected = accent): Left / Right change it, OK takes the next. */
@Composable
internal fun <T> SegmentRow(title: String, options: List<Pair<T, String>>, selected: T, desc: String? = null, mod: Modifier = Modifier, onChange: (T) -> Unit) {
    val i = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    Row(
        mod.fillMaxWidth().padding(vertical = 6.dp)
            .leftRight({ onChange(options[(i - 1).coerceAtLeast(0)].first) }, { onChange(options[(i + 1).coerceAtMost(options.size - 1)].first) })
            .settingFocus { onChange(options[(i + 1) % options.size].first) }.padding(horizontal = 28.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = O5.Text, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
            desc?.let { Text(it, color = O5.Muted, fontSize = 19.sp, modifier = Modifier.padding(top = 4.dp)) }
        }
        Row(Modifier.background(Color(0x14FFFFFF), RoundedCornerShape(999.dp)).padding(3.dp)) {
            for ((v, label) in options) {
                val on = v == selected
                Box(Modifier.background(if (on) O5.Accent else Color.Transparent, RoundedCornerShape(999.dp)).padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(label, fontSize = 19.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold, color = if (on) O5.OnAccent else O5.PillInactive)
                }
            }
        }
    }
}

/** A group heading inside a category (22 px eyebrow). */
internal fun LazyListScope.head(s: String) {
    item { Text(s.uppercase(), style = O5Type.Eyebrow, modifier = Modifier.padding(start = 4.dp, top = 26.dp, bottom = 8.dp)) }
}

/** A muted explanation line (19 px). */
internal fun LazyListScope.note(s: String) {
    item { Text(s, color = O5.Muted, fontSize = 19.sp, lineHeight = 27.sp, modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 6.dp)) }
}

/**
 * Rows still written for the device density (update check, playback stats, text fields) inside the page: every item
 * [block] adds is drawn in [LegacyDensity].
 */
internal fun LazyListScope.legacy(block: LazyListScope.() -> Unit) = LegacyScope(this).block()

@OptIn(ExperimentalFoundationApi::class)
private class LegacyScope(private val d: LazyListScope) : LazyListScope {
    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) =
        d.item(key, contentType) { LegacyDensity { content() } }

    override fun items(count: Int, key: ((index: Int) -> Any)?, contentType: (index: Int) -> Any?, itemContent: @Composable LazyItemScope.(index: Int) -> Unit) =
        d.items(count, key, contentType) { i -> LegacyDensity { itemContent(i) } }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun stickyHeader(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) =
        d.stickyHeader(key, contentType) { LegacyDensity { content() } }
}

/** On a phone: the page's spec pixels scaled to the phone (0.55: 25 px labels become about 14 dp). */
@Composable
private fun SettingsScaled(content: @Composable () -> Unit) {
    val base = LocalDensity.current
    CompositionLocalProvider(LocalBaseDensity provides base, LocalDensity provides Density(base.density * 0.55f, base.fontScale)) { content() }
}

/**
 * Settings (spec section 12): the categories in the sub-menu row (OK opens one), the category's rows below. Back
 * returns where Settings was opened from. The category is remembered across starts ([AppModel.settingsSec]).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(m: AppModel) {
    BackHandler { m.screen = m.settingsBack; m.settingsBack = Screen.LIST }
    var sec by m::settingsSec
    val cat = sec.coerceIn(0, SettingsCategories.size - 1)
    // Seerr state for Settings > Requests (asked when that category opens)
    var seerr by remember { mutableStateOf<DiscoverStatus?>(null) }
    var seerrErr by remember { mutableStateOf("") }
    LaunchedEffect(cat, m.orbit.link) {
        if (cat != 4) return@LaunchedEffect
        val l = m.orbit.link ?: return@LaunchedEffect
        try { seerr = withContext(Dispatchers.IO) { DiscoverApi().status(l) }; seerrErr = "" } catch (e: Exception) { seerrErr = e.message ?: "no answer" }
    }
    SettingsFrame(cat, { sec = it }, preview = if (cat == 0) ({ PickerPreview(m.profiles.style, m.profiles.list.map { it.name }.ifEmpty { listOf(m.orbit.link?.profile ?: "You") }) }) else null) {
        when (cat) {
            0 -> profilesPage(m)
            1 -> playback(m)
            2 -> { general(m); epgSection(m); recording(m); other(m) }
            3 -> { orbitSection(m); playlists(m); syncSection(m) }
            4 -> requestsPage(m, seerr, seerrErr)
            5 -> { appearance(m) }
            else -> { about(); remote() }
        }
    }
}

/**
 * The Settings page without the app model (the design shot uses it): the sub-menu row at y = 118 with the
 * categories, [rows] below from y = 204 (96 px margin), [preview] on the right, and the hints legend (Settings has no
 * colour keys). Focus stays on the avatar it was opened from; Down reaches the categories, then the rows.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SettingsFrame(cat: Int, onCat: (Int) -> Unit, preview: (@Composable BoxScope.() -> Unit)? = null, rows: LazyListScope.() -> Unit) {
    val phone = LocalIsPhone.current
    val listState = rememberLazyListState()
    LaunchedEffect(cat) { runCatching { listState.scrollToItem(0) } }
    if (phone) {
        val insets = LocalPhoneInsets.current
        Column(Modifier.fillMaxSize().background(O5.Bg).padding(top = insets.top, bottom = insets.bottom).testTag("settings")) {
            SubMenuRow(SettingsCategories, cat, onCat, Modifier.padding(bottom = 6.dp))
            SettingsScaled {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 36.dp), state = listState, contentPadding = PaddingValues(bottom = 60.dp), content = rows)
            }
        }
        return
    }
    Box(Modifier.fillMaxSize().testTag("settings")) {
        BackdropStill(null, MeshColors.Orbit, Modifier.fillMaxSize(), dim = true)
        SubMenuRow(SettingsCategories, cat, onCat, Modifier.padding(top = O5Space.SubMenuTop))
        LazyColumn(
            Modifier.padding(start = O5Space.Side, top = O5Space.ContentUnderSubMenu - 10.dp).width(if (preview != null) 1060.dp else 1300.dp).fillMaxSize().focusGroup(),
            state = listState, contentPadding = PaddingValues(top = 10.dp, bottom = 160.dp), content = rows,
        )
        if (preview != null) Box(Modifier.padding(start = 1210.dp, top = O5Space.ContentUnderSubMenu + 6.dp).size(614.dp, 400.dp), content = preview)
        ColorKeyLegend(Modifier.align(Alignment.BottomStart).padding(start = O5Space.Side, bottom = 28.dp), hints = listOf("OK" to "Select", "BACK" to "Close"))
    }
}

/** Settings ▸ Profiles (spec section 12): picker style, ask at start, switching profile, the PIN, and this TV's name. */
internal fun LazyListScope.profilesPage(m: AppModel) {
    val pp = m.profiles
    profileRows(pp.style, { pp.changeStyle(it) }, pp.askOnStart, { pp.changeAskOnStart(it) }, linked = m.orbit.link != null,
        current = m.orbit.link?.profile ?: "", onSwitch = { pp.open() }, onLink = { m.orbit.clearMessage(); m.screen = Screen.ORBIT_LINK })
    parental(m)
    head("This TV")
    if (m.orbit.link != null) legacy { item { NameThisTv(m) } }
    else note("Link this TV to Orbit (Settings ▸ Sources) to give it a name other devices see.")
}

/** The Profiles rows that need no app model (the design shot draws them). */
fun LazyListScope.profileRows(
    style: PickerStyle, onStyle: (PickerStyle) -> Unit, askOnStart: Boolean, onAsk: (Boolean) -> Unit,
    linked: Boolean, current: String, onSwitch: () -> Unit, onLink: () -> Unit,
) {
    head("Who's watching?")
    item(key = "style") {
        SegmentRow("Profile picker style", listOf(PickerStyle.ORBIT to "Orbit", PickerStyle.CLASSIC to "Classic"), style,
            desc = "How \"Who's watching?\" looks: profiles around the orbit, or one row", mod = Modifier.testTag("set-picker-style"), onChange = onStyle)
    }
    item(key = "ask") {
        ToggleRow("Ask who's watching on start", askOnStart, desc = "When the account has two or more profiles; nothing plays until one is chosen",
            mod = Modifier.testTag("set-ask-start"), onChange = onAsk)
    }
    if (linked) item(key = "switch") { SettingRow("Switch profile", current, desc = "Opens \"Who's watching?\": choose, add or remove profiles", onClick = onSwitch) }
    else item(key = "link") { SettingRow("Link this TV to Orbit", "", desc = "Profiles come from your Orbit account on the home server", onClick = onLink) }
}

/** The live preview beside the picker style (spec section 12): the orbit ring or the classic row, with initials. */
@Composable
fun PickerPreview(style: PickerStyle, names: List<String>) {
    Column(Modifier.fillMaxSize().background(RowBg, RowShape).border(1.dp, O5.ChipRing, RowShape).padding(28.dp)) {
        Text("PREVIEW · ${style.label.uppercase()}", style = O5Type.Eyebrow)
        Text("Who's watching?", color = O5.Text, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 10.dp))
        val people = (names.take(4) + "+").distinct()
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (style == PickerStyle.ORBIT) {
                Box(Modifier.size(500.dp, 220.dp).drawBehind {
                    drawOval(Color(0x42FFFFFF), Offset(0f, 20.dp.toPx()), Size(size.width, size.height - 70.dp.toPx()), style = Stroke(3.dp.toPx()))
                }) {
                    // the focused profile at 6 o'clock, the others up the ring
                    val spots = listOf(Offset(206f, 132f), Offset(70f, 90f), Offset(342f, 90f), Offset(0f, 30f), Offset(420f, 30f))
                    people.forEachIndexed { i, n ->
                        val sz = if (i == 0) 84.dp else 60.dp
                        val o = spots.getOrElse(i) { Offset(0f, 0f) }
                        Box(Modifier.offset(o.x.dp, o.y.dp)) {
                            if (n == "+") Box(Modifier.size(sz).border(3.dp, Color(0x4DFFFFFF), CircleShape))
                            else Avatar(n, sz, brush = AvatarColors.brush(n, i))
                        }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.CenterVertically) {
                    people.forEachIndexed { i, n ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (n == "+") Box(Modifier.size(72.dp).border(3.dp, Color(0x4DFFFFFF), CircleShape))
                            else Avatar(n, if (i == 0) 92.dp else 72.dp, brush = AvatarColors.brush(n, i))
                            Spacer(Modifier.height(10.dp))
                            Text(if (n == "+") "Add" else n, color = if (i == 0) O5.Text else O5.Muted, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** Settings ▸ Requests: whether Seerr is set up and this account may request, and where that is changed. */
internal fun LazyListScope.requestsPage(m: AppModel, st: DiscoverStatus?, err: String) {
    head("Seerr")
    if (m.orbit.link == null) {
        note("Requests go through Seerr on your Orbit home server. Link this TV to Orbit first (Settings ▸ Sources).")
        return
    }
    if (st == null) { note(if (err.isNotEmpty()) "The home server did not answer: $err" else "Asking the home server…"); return }
    item { SettingRow("Seerr on the home server", if (!st.configured) "Not set up" else if (st.reachable) "Connected" else "Not answering", desc = "Trending titles and requests in Discover") { } }
    if (st.configured) item {
        SettingRow("Your requests", if (st.linked) "Allowed" else "Not linked", desc = if (st.linked) "Requests are made as your own Seerr user" else "Ask the owner to link your account to a Seerr user") { }
    }
    note(if (st.admin) "Set up or change Seerr, and link accounts to Seerr users, in Orbit on a phone or computer: Settings ▸ Discover (the Seerr address and its API key)."
        else "The owner sets up Seerr in Orbit on a phone or computer (Settings ▸ Discover).")
    item { SettingRow("Open Discover", "") { m.screen = Screen.LIST; m.section = Section.DISCOVER } }
}

