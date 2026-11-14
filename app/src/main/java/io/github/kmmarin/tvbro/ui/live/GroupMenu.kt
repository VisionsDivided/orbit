// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.live

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kmmarin.tvbro.AppModel
import io.github.kmmarin.tvbro.GROUP_ALL
import io.github.kmmarin.tvbro.GROUP_FAV
import io.github.kmmarin.tvbro.data.Channel
import io.github.kmmarin.tvbro.data.LiveGroups
import io.github.kmmarin.tvbro.ui.SafeFocus
import io.github.kmmarin.tvbro.ui.orbit5.ColorKeyActions
import io.github.kmmarin.tvbro.ui.orbit5.ColorKeyLegend
import io.github.kmmarin.tvbro.ui.orbit5.KeyAction
import io.github.kmmarin.tvbro.ui.orbit5.O5
import io.github.kmmarin.tvbro.ui.orbit5.o5Focus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Live TV's side menu of groups (owner feedback 2026-11-14): a search field, then the groups folded into folders by
 * the text before " | " (shared/LiveGroups). One state per Live TV page; it survives closing, so folders stay open.
 */
@Stable
class GroupMenuState {
    var open by mutableStateOf(false); private set
    var query by mutableStateOf("")
    var expanded by mutableStateOf(emptySet<String>())
    /** Search results (null: no search, the folders show). */
    var results by mutableStateOf<List<LiveGroups.Row>?>(null)
    /** The group row with focus (Red pins it); null on folders, channels and the search field. */
    var focusedGroup by mutableStateOf<String?>(null)
    /** A folder row that should take focus once it is composed (Left from one of its groups). */
    var pendingFolder by mutableStateOf<String?>(null)
    val searchFocus = SafeFocus()
    val listState = LazyListState()

    fun show() { open = true }
    fun hide() { open = false; query = ""; results = null; focusedGroup = null }
    fun toggle(folder: String) { expanded = if (folder in expanded) expanded - folder else expanded + folder }
}

/** What the menu's rows need besides the rows themselves. */
class GroupMenuActions(
    val onFolder: (String) -> Unit,
    val onGroup: (String) -> Unit,
    val onChannel: (Channel) -> Unit,
    val onPin: (String) -> Unit,
    val onFocusGroup: (String?) -> Unit,
    /** Left on a group inside a folder: close that folder (focus goes to its row). */
    val onCollapse: (String) -> Unit,
    val onClose: () -> Unit,
)

/**
 * The side menu bound to the app: the provider's and custom groups (not Favourites / All), search over groups and
 * channels (off the main thread, 120 ms after the last letter), Red pins the focused group, Back closes. [onChosen]
 * runs after a group or channel was picked (the menu is already closed), [onClosed] after Back.
 */
@Composable
fun GroupSideMenu(m: AppModel, s: GroupMenuState, top: Dp, onChosen: () -> Unit, onClosed: () -> Unit, phone: Boolean = false, legend: Boolean = false) {
    val groups = remember(m.groupList) { m.groupList.filter { it != GROUP_FAV && it != GROUP_ALL } }
    val nodes = remember(groups) { LiveGroups.fold(groups) }
    val channels = m.shown
    val names = m.prefs.groupNames
    val label: (String) -> String = { g -> (names[g] ?: g) + if (g in m.prefs.locked) " (locked)" else "" }
    LaunchedEffect(s.query, groups, channels) {
        if (LiveGroups.norm(s.query).isEmpty()) { s.results = null; return@LaunchedEffect }
        delay(120)
        val q = s.query
        s.results = withContext(Dispatchers.Default) { LiveGroups.search(q, groups, channels.map { it.name }, { g -> names[g] ?: g }) }
    }
    val close = { s.hide(); onClosed() }
    BackHandler { close() }
    if (!phone) ColorKeyActions(red = KeyAction("Pin / unpin group") { s.focusedGroup?.let { m.togglePin(it) } })
    val rows = s.results ?: LiveGroups.rows(nodes, s.expanded)
    GroupMenuView(
        query = s.query, onQuery = { s.query = it }, rows = rows, channelOf = { channels.getOrNull(it) },
        pinned = m.groupPins().toSet(), selected = m.group, label = label, groupCount = groups.size, state = s, top = top, phone = phone, legend = legend,
        actions = GroupMenuActions(
            onFolder = { s.toggle(it) },
            onGroup = { g -> s.hide(); m.selectGroup(g); onChosen() },
            onChannel = { c ->
                s.hide()
                m.selectGroup(if (c.group in m.groupList) c.group else GROUP_ALL)
                m.focusCh = c; m.focusProg = null; m.groupFocus[m.group] = c.key
                onChosen()
            },
            onPin = { m.togglePin(it) },
            onFocusGroup = { s.focusedGroup = it },
            onCollapse = { f -> s.expanded = s.expanded - f; s.pendingFolder = f },
            onClose = close,
        ),
    )
}

private val PanelBg = Color(0xFF0A0C14)
private val RowSel = Color(0x1F2FD3FF)
private val TitleStyle = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = O5.Text)
private val RowStyle = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = O5.Text)
private val CountStyle = TextStyle(fontSize = 20.sp, color = O5.Muted)
private val HeadStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp, color = O5.Muted)

/** Width of the panel at 1920 (spec: about 520 px). */
val GroupMenuWidth = 520.dp

/**
 * The side menu without the app model (the shot tests draw it): a dark panel at the left from [top] down, over a scrim
 * on the rest of the page; on a phone ([phone]) a full-screen sheet with a close button and tappable pins.
 *
 * D-pad: focus opens in the search field (OK types; letters from a remote keyboard go straight in), Down moves into the
 * list, Right opens a folder, Left closes it (or the folder of the focused group), OK picks. Focus stays in the panel.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GroupMenuView(
    query: String, onQuery: (String) -> Unit, rows: List<LiveGroups.Row>, channelOf: (Int) -> Channel?, pinned: Set<String>,
    selected: String, label: (String) -> String, groupCount: Int, state: GroupMenuState, actions: GroupMenuActions,
    top: Dp = 0.dp, phone: Boolean = false, legend: Boolean = false,
) {
    // the menu opens with focus in the search field (a few tries: the panel is composed in this frame)
    LaunchedEffect(Unit) { if (!phone) repeat(12) { if (state.searchFocus.request()) return@LaunchedEffect; delay(40) } }
    Box(Modifier.fillMaxSize().testTag("group-menu")) {
        // the rest of the page dims; a tap there closes the menu (touch)
        if (!phone) Box(Modifier.fillMaxSize().background(Color(0x99000000))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = actions.onClose))
        val panel = if (phone) Modifier.fillMaxSize().background(O5.Bg)
        else Modifier.padding(top = top).width(GroupMenuWidth).fillMaxHeight()
            .background(PanelBg, RoundedCornerShape(topEnd = 20.dp))
        Column(
            panel.focusProperties { exit = { FocusRequester.Cancel } }.focusGroup()
                .padding(top = if (phone) 12.dp else 28.dp),
        ) {
            Row(Modifier.padding(horizontal = if (phone) 16.dp else 40.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("All groups", style = if (phone) TitleStyle.copy(fontSize = 22.sp) else TitleStyle, maxLines = 1)
                Text("  $groupCount", style = if (phone) CountStyle.copy(fontSize = 14.sp) else CountStyle, maxLines = 1, modifier = Modifier.weight(1f))
                if (phone) Box(Modifier.size(48.dp).clickable(onClick = actions.onClose), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, "Close", tint = O5.Text, modifier = Modifier.size(26.dp))
                }
            }
            MenuSearchField(query, onQuery, state.searchFocus, phone,
                Modifier.padding(horizontal = if (phone) 16.dp else 40.dp).padding(top = if (phone) 8.dp else 22.dp, bottom = 10.dp).fillMaxWidth())
            if (rows.isEmpty()) Text(if (query.isBlank()) "No groups." else "Nothing matches “${query.trim()}”.", style = if (phone) CountStyle.copy(fontSize = 14.sp) else CountStyle,
                modifier = Modifier.padding(horizontal = if (phone) 16.dp else 40.dp, vertical = 16.dp))
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f), state = state.listState,
                contentPadding = if (phone) PaddingValues(start = 8.dp, end = 8.dp, bottom = 24.dp) else PaddingValues(start = 26.dp, end = 26.dp, top = 12.dp, bottom = 130.dp),
            ) {
                items(rows, key = { r -> rowKey(r) }) { r ->
                    when (r) {
                        is LiveGroups.Row.Heading -> Text(r.text.uppercase(), style = if (phone) HeadStyle.copy(fontSize = 12.sp) else HeadStyle,
                            modifier = Modifier.padding(start = 14.dp, top = if (phone) 12.dp else 18.dp, bottom = 6.dp))
                        is LiveGroups.Row.FolderRow -> FolderRowView(r, state, actions, phone)
                        is LiveGroups.Row.GroupRow -> GroupRowView(r, label(r.group.name).let { full -> if (r.inFolder && full.startsWith(r.group.name)) r.group.label + full.substring(r.group.name.length) else full },
                            r.group.name == selected, r.group.name in pinned, actions, phone)
                        is LiveGroups.Row.ChannelRow -> channelOf(r.index)?.let { c -> ChannelRowView(c, label(c.group), actions, phone) }
                    }
                }
            }
        }
        if (legend && !phone) ColorKeyLegend(Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 28.dp), hints = listOf("OK" to "Choose", "BACK" to "Close"))
    }
}

private fun rowKey(r: LiveGroups.Row): String = when (r) {
    is LiveGroups.Row.Heading -> "h:" + r.text
    is LiveGroups.Row.FolderRow -> "f:" + r.name
    is LiveGroups.Row.GroupRow -> "g:" + r.group.name
    is LiveGroups.Row.ChannelRow -> "c:" + r.index
}

private val RowShape = RoundedCornerShape(12.dp)

/** A row's own look: the selected group tinted, every row the same height. */
private fun Modifier.menuRow(phone: Boolean, sel: Boolean = false): Modifier =
    fillMaxWidth().height(if (phone) 52.dp else 62.dp).background(if (sel) RowSel else Color.Transparent, RowShape)
        .padding(horizontal = if (phone) 12.dp else 16.dp)

@Composable
private fun rowFocus(phone: Boolean, focus: SafeFocus? = null, onFocus: (() -> Unit)? = null, onLong: (() -> Unit)? = null, onClick: () -> Unit): Modifier =
    if (phone) Modifier.clickable(onClick = onClick)
    else Modifier.o5Focus(RowShape, 12.dp, focus = focus, scaleTo = 1.02f, onFocus = onFocus, onLongClick = onLong, onClick = onClick)

@Composable
private fun FolderRowView(r: LiveGroups.Row.FolderRow, state: GroupMenuState, a: GroupMenuActions, phone: Boolean) {
    val fr = remember { SafeFocus() }
    LaunchedEffect(state.pendingFolder, fr.isAttached) { if (state.pendingFolder == r.name && fr.request()) state.pendingFolder = null }
    val expanded by rememberUpdatedState(r.expanded)
    Row(
        Modifier.padding(vertical = 3.dp)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) false
                else when (e.key) {
                    Key.DirectionRight -> { if (!expanded) a.onFolder(r.name); true }
                    Key.DirectionLeft -> { if (expanded) a.onFolder(r.name); true }
                    else -> false
                }
            }
            .then(rowFocus(phone, fr, onFocus = { a.onFocusGroup(null) }) { a.onFolder(r.name) })
            .menuRow(phone).testTag("menu-f-" + r.name),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (r.expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight, null, tint = O5.Muted,
            modifier = Modifier.size(if (phone) 22.dp else 30.dp))
        Spacer(Modifier.width(if (phone) 6.dp else 10.dp))
        Text(r.name, style = rowStyle(phone).copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Text("  (${r.count})", style = if (phone) CountStyle.copy(fontSize = 13.sp) else CountStyle, maxLines = 1)
    }
}

private fun rowStyle(phone: Boolean) = if (phone) RowStyle.copy(fontSize = 15.sp) else RowStyle

@Composable
private fun GroupRowView(r: LiveGroups.Row.GroupRow, text: String, sel: Boolean, pinned: Boolean, a: GroupMenuActions, phone: Boolean) {
    val folder = if (r.inFolder) LiveGroups.split(r.group.name)?.first else null
    Row(
        Modifier.padding(vertical = 3.dp).padding(start = if (r.inFolder) (if (phone) 26.dp else 40.dp) else 0.dp)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) false
                else when (e.key) {
                    Key.DirectionLeft -> { folder?.let { a.onCollapse(it) }; true }
                    Key.DirectionRight -> true
                    else -> false
                }
            }
            .then(rowFocus(phone, onFocus = { a.onFocusGroup(r.group.name) }) { a.onGroup(r.group.name) })
            .menuRow(phone, sel).testTag("menu-g-" + r.group.name),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = rowStyle(phone).copy(color = if (sel) O5.Accent else O5.Text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        // the pin: Red on the TV; a tap on a phone
        val pin = if (pinned) Icons.Filled.Place else Icons.Outlined.Place
        val tint = if (pinned) O5.Accent else Color(0x59FFFFFF)
        if (phone) Box(Modifier.size(44.dp).clickable { a.onPin(r.group.name) }, contentAlignment = Alignment.Center) {
            Icon(pin, if (pinned) "Unpin" else "Pin", tint = tint, modifier = Modifier.size(22.dp))
        } else Icon(pin, if (pinned) "Pinned" else null, tint = tint, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun ChannelRowView(c: Channel, group: String, a: GroupMenuActions, phone: Boolean) {
    Row(
        Modifier.padding(vertical = 3.dp)
            .onPreviewKeyEvent { e -> e.type == KeyEventType.KeyDown && (e.key == Key.DirectionLeft || e.key == Key.DirectionRight) }
            .then(rowFocus(phone, onFocus = { a.onFocusGroup(null) }) { a.onChannel(c) })
            .menuRow(phone).testTag("menu-c-" + c.key),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (phone) 10.dp else 14.dp),
    ) {
        ChannelTile(c.logo, c.name, if (phone) 34.dp else 42.dp)
        Column(Modifier.weight(1f)) {
            Text(c.name, style = rowStyle(phone), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(group, style = if (phone) CountStyle.copy(fontSize = 12.sp) else CountStyle.copy(fontSize = 17.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The search field. TV: a focusable box; OK starts typing (the on-screen keyboard opens), letters from a remote's
 * keyboard go straight in, Back or Down ends typing. Phone: a plain text field.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MenuSearchField(query: String, onQuery: (String) -> Unit, focus: SafeFocus, phone: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(if (phone) 22.dp else 30.dp)
    val h = if (phone) 46.dp else 60.dp
    val text = TextStyle(fontSize = if (phone) 15.sp else 23.sp, color = O5.Text)
    var editing by remember { mutableStateOf(phone) }
    val fr = remember { FocusRequester() }
    val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val onQ by rememberUpdatedState(onQuery)
    val q by rememberUpdatedState(query)
    // after Back or the keyboard's Search key focus returns to the box (Down moves on into the list instead)
    var refocus by remember { mutableStateOf(false) }
    LaunchedEffect(editing) {
        if (editing && !phone) { delay(50); runCatching { fr.requestFocus() }; kb?.show() }
        else if (!editing && refocus) { refocus = false; delay(50); repeat(6) { if (focus.request()) return@LaunchedEffect; delay(40) } }
    }
    val stop = { if (!phone) { editing = false; kb?.hide() } }
    val stopHere = { refocus = true; stop() }
    val frame: @Composable (Boolean, @Composable BoxScope.() -> Unit) -> Unit = { active, inner ->
        Row(Modifier.fillMaxWidth().height(h).background(Color(0x14FFFFFF), shape).border(if (active) 2.dp else 1.dp, if (active) O5.Accent else Color(0x2EFFFFFF), shape)
            .padding(horizontal = if (phone) 14.dp else 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, tint = O5.Muted, modifier = Modifier.size(if (phone) 20.dp else 28.dp))
            Spacer(Modifier.width(if (phone) 8.dp else 14.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text("Search groups and channels", style = text.copy(color = O5.Muted), maxLines = 1)
                inner()
            }
        }
    }
    if (editing) {
        if (!phone) BackHandler { stopHere() }
        BasicTextField(
            value = query, onValueChange = onQuery, singleLine = true, textStyle = text, cursorBrush = SolidColor(O5.Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { kb?.hide(); stopHere() }, onDone = { kb?.hide(); stopHere() }),
            modifier = modifier.focusRequester(fr).testTag("group-menu-search")
                .onPreviewKeyEvent { e -> if (!phone && e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) { stop(); false } else false },
            decorationBox = { inner -> frame(true) { inner() } },
        )
    } else {
        Box(
            modifier
                // letters from a remote with a keyboard go straight into the search (no on-screen keyboard needed)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        e.key == Key.Backspace || e.key == Key.Delete -> { if (q.isNotEmpty()) { onQ(q.dropLast(1)); true } else false }
                        else -> {
                            val ch = e.nativeKeyEvent.unicodeChar
                            if (ch >= 32 && !Character.isISOControl(ch)) { onQ(q + ch.toChar()); true } else false
                        }
                    }
                }
                .o5Focus(shape, if (phone) 22.dp else 30.dp, focus = focus, scaleTo = 1.02f) { editing = true }
                .testTag("group-menu-search"),
        ) {
            frame(false) { if (query.isNotEmpty()) Text(query, style = text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}
