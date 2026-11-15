// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.discover

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kmmarin.tvbro.AppModel
import io.github.kmmarin.tvbro.Screen
import io.github.kmmarin.tvbro.orbit.Badge
import io.github.kmmarin.tvbro.orbit.BadgeKind
import io.github.kmmarin.tvbro.orbit.CastMember
import io.github.kmmarin.tvbro.orbit.DiscoverApi
import io.github.kmmarin.tvbro.orbit.DiscoverSeason
import io.github.kmmarin.tvbro.orbit.DiscoverTitle
import io.github.kmmarin.tvbro.orbit.DiscoverTitlePage
import io.github.kmmarin.tvbro.orbit.DownloadInfo
import io.github.kmmarin.tvbro.ui.QrCode
import io.github.kmmarin.tvbro.ui.SafeFocus
import io.github.kmmarin.tvbro.ui.orbit5.BackdropSpec
import io.github.kmmarin.tvbro.ui.orbit5.CinematicBackdrop
import io.github.kmmarin.tvbro.ui.orbit5.ColorKeyActions
import io.github.kmmarin.tvbro.ui.orbit5.FocusRoom
import io.github.kmmarin.tvbro.ui.orbit5.ImgSize
import io.github.kmmarin.tvbro.ui.orbit5.KeyAction
import io.github.kmmarin.tvbro.ui.orbit5.NetImage
import io.github.kmmarin.tvbro.ui.orbit5.O5
import io.github.kmmarin.tvbro.ui.orbit5.O5Button
import io.github.kmmarin.tvbro.ui.orbit5.O5Row
import io.github.kmmarin.tvbro.ui.orbit5.O5Shape
import io.github.kmmarin.tvbro.ui.orbit5.O5Space
import io.github.kmmarin.tvbro.ui.orbit5.O5Type
import io.github.kmmarin.tvbro.ui.orbit5.PhoneAction
import io.github.kmmarin.tvbro.ui.orbit5.PhoneBottomFade
import io.github.kmmarin.tvbro.ui.orbit5.PhoneRowHeading
import io.github.kmmarin.tvbro.ui.orbit5.PhoneTouch
import io.github.kmmarin.tvbro.ui.orbit5.PhoneType
import io.github.kmmarin.tvbro.ui.orbit5.PhoneWideButton
import io.github.kmmarin.tvbro.ui.orbit5.ProgressBar
import io.github.kmmarin.tvbro.ui.orbit5.StatusBadge
import io.github.kmmarin.tvbro.ui.orbit5.o5Focus
import io.github.kmmarin.tvbro.ui.shell.LocalIsPhone
import io.github.kmmarin.tvbro.ui.shell.LocalPhoneInsets
import kotlinx.coroutines.delay

/** What the title page does (bound to [DiscoverFeed] by [DiscoverTitleScreen]; the shots pass stand-ins). */
class TitleActions(
    val back: () -> Unit = {},
    val play: (DiscoverTitle) -> Unit = {},
    /** Request: a film at once, a show opens the season chooser. */
    val request: () -> Unit = {},
    val toggle: (Int) -> Unit = {},
    val toggleAll: () -> Unit = {},
    val send: () -> Unit = {},
    val cancel: () -> Unit = {},
    val trailer: (String) -> Unit = {},
    val seerr: (String) -> Unit = {},
    val retry: () -> Unit = {},
    val canRequest: Boolean = true,
    val busy: Boolean = false,
)

/** A link shown as a QR code on the TV (Open in Seerr; a trailer when no app plays YouTube). */
@Immutable
data class QrInfo(val title: String, val url: String)

/**
 * The Discover title page (GET /v1/discover/title/{movie|tv}/{tmdb}): backdrop, poster, title and meta, tagline,
 * overview, status, download bar, who requested it, then Play (in the catalogue) or Request (shows: a season chooser
 * with "All seasons"), Trailer and Open in Seerr, then the seasons and the cast. Colour keys: red Request, green
 * Trailer, blue Open in Seerr. It asks again every 30 s while a download runs. Back closes the chooser or the QR
 * code first, then returns to Discover on the same row and card.
 */
@Composable
fun DiscoverTitleScreen(m: AppModel, feed: DiscoverFeed, v: TitleView) {
    val ctx = LocalContext.current
    val phone = LocalIsPhone.current
    var qr by remember { mutableStateOf<QrInfo?>(null) }
    val overlay = m.menu != null || m.prompt != null || m.progMenu != null
    BackHandler(enabled = !overlay && m.screen == Screen.LIST) {
        when {
            qr != null -> qr = null
            v.choosing -> feed.cancelChooser()
            else -> feed.closeTitle()
        }
    }
    val polling = DiscoverLogic.shouldPoll(v.title)
    LaunchedEffect(v.title.key, polling) {
        while (polling) { delay(DiscoverLogic.TITLE_POLL_MS); feed.loadTitle(quiet = true) }
    }
    val a = TitleActions(
        back = { feed.closeTitle() }, play = { playTitle(m, it) }, request = { feed.requestOpen() },
        toggle = { feed.toggleSeason(it) }, toggleAll = { feed.toggleAllSeasons() }, send = { feed.sendChosen() }, cancel = { feed.cancelChooser() },
        // a TV opens YouTube when it can, else shows the link as a QR code; a phone opens it
        trailer = { url -> if (!openLink(ctx, url)) qr = QrInfo("Trailer · ${v.title.title}", url) },
        // Seerr's own pages want a browser: a QR code for the phone on the TV, the browser on a phone
        seerr = { url -> if (!phone || !openLink(ctx, url)) qr = QrInfo("Open ${v.title.title} in Seerr", url) },
        retry = { feed.loadTitle() }, canRequest = feed.canRequest, busy = feed.busy == v.title.key,
    )
    DiscoverTitleView(v, a, qr) { qr = null }
}

private fun openLink(ctx: Context, url: String): Boolean = try {
    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
} catch (e: Exception) { false }

/** The title page without the app model (shots). */
@Composable
fun DiscoverTitleView(v: TitleView, a: TitleActions, qr: QrInfo? = null, closeQr: () -> Unit = {}) {
    val p = v.shown
    val req = a.canRequest && DiscoverLogic.canRequest(p)
    ColorKeyActions(
        red = if (req) KeyAction(DiscoverLogic.requestLabel(p)) { a.request() } else null,
        green = p.trailer?.let { u -> KeyAction("Trailer") { a.trailer(u) } },
        blue = p.seerrUrl?.let { u -> KeyAction("Open in Seerr") { a.seerr(u) } },
        enabled = qr == null,
    )
    if (LocalIsPhone.current) PhoneTitlePage(v, a, req) else TvTitlePage(v, a, req)
    if (qr != null) QrPanel(qr, closeQr)
}

/** The badge the page shows instead of Request (or "Not requested" when nothing is asked for yet). */
private fun pageBadge(t: DiscoverTitle): Badge = DiscoverApi.badge(t) ?: Badge(BadgeKind.PENDING, "Not requested")

private fun eyebrowStatus(t: DiscoverTitle) = if (t.play != null) "In your library" else DiscoverApi.badge(t)?.label ?: "Not in your library"

// ---------------------------------------------------------------- TV

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TvTitlePage(v: TitleView, a: TitleActions, req: Boolean) {
    val p = v.shown
    val t = p.title
    val firstFr = remember { SafeFocus() }
    val allFr = remember { SafeFocus() }
    LaunchedEffect(t.key, v.choosing) {
        repeat(15) { delay(80); if ((if (v.choosing) allFr else firstFr).request()) return@LaunchedEffect }
    }
    Box(Modifier.fillMaxSize().testTag("discover-title")) {
        CinematicBackdrop(BackdropSpec(t.backdrop ?: t.poster), Modifier.fillMaxSize(), dim = true)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 150.dp, bottom = 150.dp)) {
            Row(Modifier.padding(start = O5Space.Side, end = O5Space.Side), horizontalArrangement = Arrangement.spacedBy(56.dp)) {
                Box(Modifier.padding(top = 10.dp).size(260.dp, 390.dp).clip(O5Shape.Card).background(Color(0xFF111111))) {
                    NetImage(t.poster, ImgSize.LANDSCAPE, Modifier.fillMaxSize(), ContentScale.Crop) {
                        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1B2133), Color(0xFF0B0E18)))), contentAlignment = Alignment.Center) {
                            Text(t.title.uppercase(), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, maxLines = 4, modifier = Modifier.padding(16.dp))
                        }
                    }
                }
                Column(Modifier.widthIn(max = 1180.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("DISCOVER", style = O5Type.Eyebrow, color = O5.Accent, maxLines = 1)
                        Text("· " + eyebrowStatus(t).uppercase(), style = O5Type.Eyebrow, maxLines = 1)
                    }
                    Text(
                        t.title, style = O5Type.HeroTitle.copy(fontSize = if (t.title.length > 24) 72.sp else 92.sp, lineHeight = if (t.title.length > 24) 72.sp else 90.sp),
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp, bottom = 10.dp),
                    )
                    TvMeta(p)
                    if (p.tagline.isNotBlank()) Text(p.tagline, style = O5Type.Synopsis.copy(fontSize = 24.sp, fontStyle = FontStyle.Italic), color = O5.Muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp))
                    val overview = t.overview.ifBlank { if (v.page == null && v.error == null) "Loading…" else "" }
                    if (overview.isNotEmpty()) Text(overview, style = O5Type.Synopsis.copy(fontSize = 25.sp, lineHeight = 36.sp), maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 12.dp).widthIn(max = 1100.dp))
                    v.error?.let { Text(it, style = O5Type.Body, color = O5.Rating, modifier = Modifier.padding(top = 12.dp)) }
                    t.download?.let { TvDownload(it, Modifier.padding(top = 20.dp)) }
                    val lines = DiscoverLogic.requestLines(p.requests)
                    if (lines.isNotEmpty()) Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        lines.forEach { Text(it, style = O5Type.Body.copy(fontSize = 21.sp), color = O5.MetaText, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    // the ends of the button row are edges (Right from the last button must not jump into the rows)
                    Row(
                        Modifier.padding(top = 26.dp).focusProperties {
                            enter = { firstFr.target() }
                            exit = { d -> if (d == FocusDirection.Left || d == FocusDirection.Right) FocusRequester.Cancel else FocusRequester.Default }
                        }.focusGroup(),
                        horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        var first = true
                        fun f(): SafeFocus? = if (first) { first = false; firstFr } else null
                        if (t.play != null) O5Button("Play", icon = Icons.Filled.PlayArrow, primary = true, compact = true, focus = f(), modifier = Modifier.testTag("title-play")) { a.play(t) }
                        if (req) O5Button(if (a.busy) "Requesting…" else DiscoverLogic.requestLabel(p), icon = Icons.Filled.Add, primary = t.play == null, compact = true, focus = f(),
                            modifier = Modifier.testTag("title-request")) { a.request() }
                        p.trailer?.let { u -> O5Button("Trailer", icon = Icons.Filled.PlayArrow, compact = true, focus = f(), modifier = Modifier.testTag("title-trailer")) { a.trailer(u) } }
                        p.seerrUrl?.let { u -> O5Button("Open in Seerr", icon = Icons.AutoMirrored.Filled.ArrowForward, compact = true, focus = f(), modifier = Modifier.testTag("title-seerr")) { a.seerr(u) } }
                        if (v.error != null && v.page == null) O5Button("Try again", icon = Icons.Filled.Refresh, compact = true, focus = f()) { a.retry() }
                        if (first) O5Button("Back", icon = Icons.AutoMirrored.Filled.ArrowBack, compact = true, focus = f()) { a.back() }
                        if (t.play == null && !req) StatusBadge(pageBadge(t))
                    }
                }
            }
            if (v.choosing && t.isShow) TvSeasonChooser(v, a, allFr)
            else {
                if (t.isShow && p.seasons.isNotEmpty()) TvSeasons(p.seasons, req, a)
                if (p.cast.isNotEmpty()) TvCast(p.cast)
            }
        }
    }
}

@Composable
private fun TvMeta(p: DiscoverTitlePage) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        val parts = DiscoverLogic.metaParts(p)
        p.title.year?.let { Text(it, style = O5Type.Meta, maxLines = 1) }
        p.title.rating?.let { Text("★ $it", style = O5Type.Meta, color = O5.Rating, maxLines = 1) }
        parts.drop(if (p.title.year != null) 1 else 0).forEach { Text(it, style = O5Type.Meta, maxLines = 1) }
        Text(if (p.title.isShow) "Series" else "Film", style = O5Type.Meta, color = O5.Muted, maxLines = 1)
    }
}

/** "Downloading 65% · ~18 min", the size line and the bar. */
@Composable
private fun TvDownload(d: DownloadInfo, modifier: Modifier = Modifier) {
    Column(modifier.width(720.dp).testTag("title-download")) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(DiscoverApi.downloadLabel(d), style = O5Type.CardTitle.copy(fontSize = 24.sp), color = O5.Accent, maxLines = 1)
            DiscoverLogic.downloadDetail(d).takeIf { it.isNotEmpty() }?.let { Text(it, style = O5Type.CardMeta, maxLines = 1) }
        }
        ProgressBar(d.percent / 100f, Modifier.fillMaxWidth().padding(top = 10.dp), height = 8.dp)
    }
}

@Composable
private fun TvSeasons(seasons: List<DiscoverSeason>, req: Boolean, a: TitleActions) {
    O5Row("Seasons", Modifier.padding(top = 44.dp - FocusRoom), gap = 22.dp) {
        items(seasons, key = { "season-" + it.season }) { s ->
            Column(
                Modifier.width(230.dp).o5Focus(O5Shape.Card, 10.dp, scaleTo = 1.04f) { if (req && s.status == "requestable") a.request() }
                    .background(Color(0x1FFFFFFF), O5Shape.Card).padding(18.dp),
            ) {
                Text(s.name, style = O5Type.CardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${s.episodes} episode${if (s.episodes == 1) "" else "s"}", style = O5Type.CardMeta, maxLines = 1, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
                StatusBadge(seasonBadge(s), small = true)
            }
        }
    }
}

private fun seasonBadge(s: DiscoverSeason): Badge = DiscoverApi.badge(DiscoverTitle("tv", 0, s.name, status = s.status)) ?: Badge(BadgeKind.PENDING, "Not requested")

@Composable
private fun TvCast(cast: List<CastMember>) {
    val st = rememberLazyListState()
    PrefetchImages(st, cast.map { it.photo }, ImgSize.AVATAR)
    O5Row("Cast", Modifier.padding(top = 40.dp - FocusRoom), gap = 28.dp, state = st) {
        items(cast, key = { "cast-" + it.id + it.name }) { c ->
            Column(Modifier.width(150.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(130.dp).o5Focus(CircleShape, 65.dp, scaleTo = 1.05f) {}.clip(CircleShape).background(Color(0xFF1B2133))) {
                    Face(c.name, c.photo, 130.dp, 46.sp)
                }
                Text(c.name, style = O5Type.GridTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
                if (c.character.isNotBlank()) Text(c.character, style = O5Type.GridMeta, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The season chooser (TV): "All seasons", each season (closed ones show their status), then Request / Cancel. */
@Composable
private fun TvSeasonChooser(v: TitleView, a: TitleActions, allFr: SafeFocus) {
    val seasons = v.shown.seasons
    val open = DiscoverLogic.requestable(seasons)
    val chosen = v.pick.filter { n -> open.any { it.season == n } }.sorted()
    val all = open.isNotEmpty() && open.all { it.season in v.pick }
    Column(Modifier.padding(top = 44.dp).testTag("season-chooser")) {
        Text("Request seasons", style = O5Type.RowHeading, modifier = Modifier.padding(start = O5Space.Side))
        if (v.page == null) Text("Loading the seasons…", style = O5Type.Body, modifier = Modifier.padding(start = O5Space.Side, top = 16.dp))
        else O5Row(null, gap = 20.dp) {
            item(key = "all") {
                ChoiceCard("All seasons", "${open.size} not requested yet", all, enabled = open.isNotEmpty(), focus = allFr, badge = null, tag = "season-all") { a.toggleAll() }
            }
            items(seasons, key = { "pick-" + it.season }) { s ->
                val can = s.status == "requestable"
                ChoiceCard(s.name, "${s.episodes} episode${if (s.episodes == 1) "" else "s"}", can && s.season in v.pick, enabled = can,
                    badge = if (can) null else seasonBadge(s), tag = "season-${s.season}") { a.toggle(s.season) }
            }
        }
        Row(Modifier.padding(start = O5Space.Side, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            O5Button(
                if (a.busy) "Requesting…" else if (chosen.isEmpty()) "Choose seasons" else "Request " + DiscoverLogic.seasonsText(chosen, open.size),
                icon = Icons.Filled.Add, primary = true, compact = true, modifier = Modifier.testTag("season-send"),
            ) { a.send() }
            O5Button("Cancel", compact = true) { a.cancel() }
        }
    }
}

@Composable
private fun ChoiceCard(title: String, sub: String, on: Boolean, enabled: Boolean, badge: Badge?, tag: String, focus: SafeFocus? = null, onClick: () -> Unit) {
    Row(
        Modifier.width(300.dp).height(132.dp).o5Focus(O5Shape.Card, 10.dp, focus = focus, scaleTo = 1.04f) { if (enabled) onClick() }
            .background(if (on) Color(0x332FD3FF) else Color(0x1AFFFFFF), O5Shape.Card)
            .border(if (on) 2.dp else 1.dp, if (on) O5.Accent else Color(0x24FFFFFF), O5Shape.Card).padding(20.dp).testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CheckBox(on, enabled, 30)
        Column(Modifier.weight(1f)) {
            Text(title, style = O5Type.CardTitle, color = if (enabled) O5.Text else O5.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = O5Type.CardMeta, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
            if (badge != null) StatusBadge(badge, Modifier.padding(top = 10.dp), small = true)
        }
    }
}

@Composable
private fun CheckBox(on: Boolean, enabled: Boolean, size: Int) {
    val shape = RoundedCornerShape((size / 5).dp)
    Box(
        Modifier.size(size.dp).background(if (on) O5.Accent else Color.Transparent, shape)
            .border(2.dp, if (on) O5.Accent else if (enabled) Color(0x99FFFFFF) else Color(0x33FFFFFF), shape),
        contentAlignment = Alignment.Center,
    ) { if (on) Icon(Icons.Filled.Check, null, tint = O5.OnAccent, modifier = Modifier.size((size * 0.75f).dp)) }
}

/** A link as a QR code over the page (TV): scan it with a phone. Back or Close closes it. */
@Composable
private fun QrPanel(q: QrInfo, onClose: () -> Unit) {
    val phone = LocalIsPhone.current
    val fr = remember { SafeFocus() }
    LaunchedEffect(q) { repeat(10) { delay(60); if (fr.request()) return@LaunchedEffect } }
    if (phone) BackHandler { onClose() }
    Box(Modifier.fillMaxSize().background(Color(0xD905060C)).testTag("title-qr"), contentAlignment = Alignment.Center) {
        Column(
            Modifier.background(O5.Dialog, O5Shape.Dialog).border(1.dp, O5.DialogBorder, O5Shape.Dialog).padding(if (phone) 22.dp else 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(q.title, style = if (phone) PhoneType.RowHeading else O5Type.RowHeading, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Scan with your phone to open it there.", style = if (phone) PhoneType.Body else O5Type.Body, modifier = Modifier.padding(top = 8.dp, bottom = 22.dp))
            QrCode(q.url, Modifier.size(if (phone) 220.dp else 340.dp))
            Text(q.url, style = if (phone) PhoneType.Meta else O5Type.CardMeta, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 18.dp).widthIn(max = if (phone) 300.dp else 640.dp))
            Spacer(Modifier.height(22.dp))
            if (phone) PhoneWideButton("Close", Modifier.width(200.dp)) { onClose() }
            else O5Button("Close", compact = true, focus = fr) { onClose() }
        }
    }
}

// ---------------------------------------------------------------- phone

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhoneTitlePage(v: TitleView, a: TitleActions, req: Boolean) {
    val p = v.shown
    val t = p.title
    val insets = LocalPhoneInsets.current
    LazyColumn(Modifier.fillMaxSize().testTag("discover-title"), contentPadding = PaddingValues(top = insets.top, bottom = insets.bottom + 24.dp)) {
        item(key = "art") {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
                NetImage(t.backdrop ?: t.poster, ImgSize.BACKDROP, Modifier.fillMaxSize(), ContentScale.Crop)
                PhoneBottomFade(120.dp)
                Box(Modifier.padding(8.dp).size(PhoneTouch).clip(CircleShape).background(Color(0x8005060C)).clickable { a.back() }.testTag("title-back"), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
        }
        item(key = "head") {
            Column(Modifier.padding(horizontal = 20.dp).widthIn(max = 640.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(84.dp, 126.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF111111))) {
                        NetImage(t.poster, ImgSize.POSTER, Modifier.fillMaxSize(), ContentScale.Crop)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("DISCOVER · " + eyebrowStatus(t).uppercase(), style = PhoneType.Eyebrow, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t.title, fontSize = 26.sp, lineHeight = 29.sp, fontWeight = FontWeight.ExtraBold, color = O5.Text, letterSpacing = (-0.6).sp,
                            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        val meta = listOfNotNull(t.rating?.let { "★ $it" }) + DiscoverLogic.metaParts(p)
                        Text(meta.joinToString(" · "), style = PhoneType.Meta, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                when {
                    t.play != null -> PhoneWideButton("Play", Modifier.padding(top = 16.dp), icon = Icons.Filled.PlayArrow, primary = true) { a.play(t) }
                    req -> PhoneWideButton(if (a.busy) "Requesting…" else DiscoverLogic.requestLabel(p), Modifier.padding(top = 16.dp).testTag("title-request"), icon = Icons.Filled.Add, primary = true) { a.request() }
                    else -> StatusBadge(pageBadge(t), Modifier.padding(top = 16.dp))
                }
                if (t.play != null && req) PhoneWideButton(DiscoverLogic.requestLabel(p), Modifier.padding(top = 8.dp), icon = Icons.Filled.Add) { a.request() }
                if (p.trailer != null || p.seerrUrl != null) Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    p.trailer?.let { u -> PhoneAction("Trailer", icon = Icons.Filled.PlayArrow) { a.trailer(u) } }
                    p.seerrUrl?.let { u -> PhoneAction("Seerr", icon = Icons.AutoMirrored.Filled.ArrowForward) { a.seerr(u) } }
                }
                t.download?.let { d ->
                    Column(Modifier.padding(top = 14.dp)) {
                        Text(DiscoverApi.downloadLabel(d), style = PhoneType.CardTitle, color = O5.Accent)
                        DiscoverLogic.downloadDetail(d).takeIf { it.isNotEmpty() }?.let { Text(it, style = PhoneType.CardMeta) }
                        ProgressBar(d.percent / 100f, Modifier.fillMaxWidth().padding(top = 6.dp), height = 4.dp)
                    }
                }
                DiscoverLogic.requestLines(p.requests).forEach { Text(it, style = PhoneType.Meta, color = O5.MetaText, modifier = Modifier.padding(top = 8.dp)) }
                if (p.tagline.isNotBlank()) Text(p.tagline, style = PhoneType.Body.copy(fontStyle = FontStyle.Italic), modifier = Modifier.padding(top = 14.dp))
                val overview = t.overview.ifBlank { if (v.page == null && v.error == null) "Loading…" else "" }
                if (overview.isNotEmpty()) Text(overview, fontSize = 13.5.sp, lineHeight = 20.sp, color = Color(0xD1EBEEFF), modifier = Modifier.padding(top = 10.dp))
                v.error?.let { Text(it, style = PhoneType.Body, color = O5.Rating, modifier = Modifier.padding(top = 10.dp)) }
            }
        }
        if (v.choosing && t.isShow) {
            val open = DiscoverLogic.requestable(p.seasons)
            val chosen = v.pick.filter { n -> open.any { it.season == n } }.sorted()
            item(key = "choose-head") { PhoneRowHeading("Request seasons", Modifier.padding(top = 20.dp)) }
            item(key = "choose-all") {
                PhoneChoice("All seasons", "${open.size} not requested yet", open.isNotEmpty() && open.all { it.season in v.pick }, open.isNotEmpty(), null) { a.toggleAll() }
            }
            items(p.seasons, key = { "choose-" + it.season }) { s ->
                val can = s.status == "requestable"
                PhoneChoice(s.name, "${s.episodes} episode${if (s.episodes == 1) "" else "s"}", can && s.season in v.pick, can, if (can) null else seasonBadge(s)) { a.toggle(s.season) }
            }
            item(key = "choose-send") {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp).widthIn(max = 640.dp)) {
                    PhoneWideButton(if (a.busy) "Requesting…" else if (chosen.isEmpty()) "Choose seasons" else "Request " + DiscoverLogic.seasonsText(chosen, open.size),
                        icon = Icons.Filled.Add, primary = true, enabled = chosen.isNotEmpty()) { a.send() }
                    PhoneWideButton("Cancel", Modifier.padding(top = 8.dp)) { a.cancel() }
                }
            }
        } else {
            if (t.isShow && p.seasons.isNotEmpty()) item(key = "seasons") {
                Column(Modifier.padding(top = 20.dp)) {
                    PhoneRowHeading("Seasons")
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(p.seasons, key = { "s" + it.season }) { s ->
                            Column(Modifier.width(132.dp).background(Color(0x1AFFFFFF), RoundedCornerShape(10.dp)).padding(12.dp)) {
                                Text(s.name, style = PhoneType.CardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${s.episodes} episodes", style = PhoneType.CardMeta, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
                                StatusBadge(seasonBadge(s), small = true)
                            }
                        }
                    }
                }
            }
            if (p.cast.isNotEmpty()) item(key = "cast") {
                Column(Modifier.padding(top = 20.dp)) {
                    PhoneRowHeading("Cast")
                    val st = rememberLazyListState()
                    PrefetchImages(st, p.cast.map { it.photo }, ImgSize.AVATAR)
                    LazyRow(state = st, contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(p.cast, key = { "c" + it.id + it.name }) { c ->
                            Column(Modifier.width(80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(68.dp).clip(CircleShape).background(Color(0xFF1B2133))) { Face(c.name, c.photo, 68.dp, 24.sp) }
                                Text(c.name, style = PhoneType.CardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                                if (c.character.isNotBlank()) Text(c.character, style = PhoneType.CardMeta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhoneChoice(title: String, sub: String, on: Boolean, enabled: Boolean, badge: Badge?, onClick: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 20.dp, vertical = 4.dp).widthIn(max = 640.dp).fillMaxWidth().heightIn(min = PhoneTouch)
            .clip(RoundedCornerShape(10.dp)).background(if (on) Color(0x262FD3FF) else Color(0x14FFFFFF))
            .combinedClickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CheckBox(on, enabled, 22)
        Column(Modifier.weight(1f)) {
            Text(title, style = PhoneType.CardTitle.copy(fontSize = 14.sp), color = if (enabled) O5.Text else O5.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = PhoneType.CardMeta, maxLines = 1)
        }
        if (badge != null) StatusBadge(badge, small = true)
    }
}
