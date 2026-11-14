// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.profiles

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kmmarin.tvbro.AppModel
import io.github.kmmarin.tvbro.R
import io.github.kmmarin.tvbro.orbit.OrbitProfile
import io.github.kmmarin.tvbro.orbit.PickerStyle
import io.github.kmmarin.tvbro.ui.FocusMemory
import io.github.kmmarin.tvbro.ui.MenuState
import io.github.kmmarin.tvbro.ui.PromptState
import io.github.kmmarin.tvbro.ui.SafeFocus
import io.github.kmmarin.tvbro.ui.countFocus
import io.github.kmmarin.tvbro.ui.focusTrap
import io.github.kmmarin.tvbro.ui.orbit5.Avatar
import io.github.kmmarin.tvbro.ui.orbit5.AvatarColors
import io.github.kmmarin.tvbro.ui.orbit5.MeshColors
import io.github.kmmarin.tvbro.ui.orbit5.NetImage
import io.github.kmmarin.tvbro.ui.orbit5.O5
import io.github.kmmarin.tvbro.ui.orbit5.O5Button
import io.github.kmmarin.tvbro.ui.orbit5.O5Type
import io.github.kmmarin.tvbro.ui.orbit5.OrbitCanvas
import io.github.kmmarin.tvbro.ui.orbit5.mesh
import io.github.kmmarin.tvbro.ui.orbit5.o5Focus
import io.github.kmmarin.tvbro.ui.orbit5.vignette
import io.github.kmmarin.tvbro.ui.safeFocus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin

/**
 * Geometry of the orbit picker (spec section 8), in spec pixels: the ring is the ellipse at (300, 296), 1320 x 500;
 * the focused profile sits at 6 o'clock, the others 38° and 78° around it (the mockup's positions).
 */
object OrbitRing {
    const val CX = 960f; const val CY = 546f; const val RX = 660f; const val RY = 250f
    const val FOCUSED = 200f; const val OTHER = 150f

    /** Angle in degrees (0 = 3 o'clock, 90 = 6 o'clock) of an item [d] places after the focused one (fractional while turning). */
    fun angle(d: Float): Float {
        val a = abs(d)
        val off = if (a <= 1f) 38f * a else 38f + 40f * (a - 1f)
        return 90f - sign(d) * off
    }

    /** Centre of the avatar [d] places from the focused one. */
    fun center(d: Float): Offset {
        val t = Math.toRadians(angle(d).toDouble())
        return Offset(CX + RX * cos(t).toFloat(), CY + RY * sin(t).toFloat())
    }

    /** Avatar diameter: 200 at the bottom, 150 elsewhere. */
    fun size(d: Float): Float = FOCUSED + (OTHER - FOCUSED) * abs(d).coerceAtMost(1f)

    /** Items more than two places away fade out behind the planet. */
    fun alpha(d: Float): Float = (1f - (abs(d) - 2f) * 1.6f).coerceIn(0f, 1f)
}

/** One spot on the picker: a profile, or "Add profile" ([profile] null). */
private data class Spot(val profile: OrbitProfile?, val label: String)

/**
 * "Who's watching?" (spec section 8): the orbit picker or the classic row (Settings ▸ Home server ▸ Profile picker
 * style), with one "Manage profiles" button and no colour-key legend. Back keeps the current profile.
 */
@Composable
fun WhoIsWatching(m: AppModel) {
    val p = m.profiles
    BackHandler(enabled = m.menu == null && m.prompt == null) { p.close() }
    val spots = remember(p.list) { p.list.map { Spot(it, it.name) } + Spot(null, "Add profile") }
    var sel by remember(p.list, p.currentId) { mutableIntStateOf(p.list.indexOfFirst { it.id == p.currentId }.coerceAtLeast(0)) }
    val choose: (Spot) -> Unit = { s ->
        if (s.profile != null) p.choose(s.profile)
        else m.prompt = PromptState("Name of the new profile", "", false) { name -> p.add(name) }
    }
    val manage = { manageMenu(m) }
    OrbitCanvas {
        Box(Modifier.fillMaxSize().background(O5.Bg).focusTrap().testTag("whos-watching")) {
            PickerBackground(m)
            Column(Modifier.fillMaxWidth().padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Who's watching?", style = O5Type.PickerTitle)
                Text(
                    p.message.ifEmpty { "Profile picker style: ${p.style.label} · change it in Settings › Home server" },
                    style = O5Type.Body, modifier = Modifier.padding(top = 10.dp),
                )
            }
            val manageFr = remember { SafeFocus() }
            if (p.style == PickerStyle.ORBIT) OrbitPicker(spots, sel, { sel = it }, p.currentId, choose, manageFr)
            else ClassicPicker(spots, sel, { sel = it }, p.currentId, choose)
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 44.dp)) {
                ManageButton(manageFr, manage)
            }
        }
    }
}

@Composable
private fun ManageButton(fr: SafeFocus, onClick: () -> Unit) {
    Row(
        Modifier.o5Focus(io.github.kmmarin.tvbro.ui.orbit5.O5Shape.Button, 12.dp, focus = fr, onClick = onClick)
            .background(O5.SecondaryButton, io.github.kmmarin.tvbro.ui.orbit5.O5Shape.Button).padding(horizontal = 32.dp, vertical = 14.dp)
            .testTag("manage-profiles"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Settings, null, tint = Color.White, modifier = Modifier.size(24.dp))
        Text("Manage profiles", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

/** Poster wall (16 %, turned -6°), the orbit mesh and the radial darkening. */
@Composable
private fun PickerBackground(m: AppModel) {
    val posters by produceState(emptyList<String>()) { value = withContext(Dispatchers.IO) { wallPosters(m) } }
    Box(Modifier.fillMaxSize().mesh(MeshColors.Orbit))
    Box(Modifier.fillMaxSize().vignette())
    if (posters.isNotEmpty()) {
        Column(
            Modifier.offset((-40).dp, (-40).dp).requiredSize(2000.dp, 1160.dp).graphicsLayer { rotationZ = -6f; scaleX = 1.15f; scaleY = 1.15f; alpha = 0.16f },
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            posters.chunked(8).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    row.forEach { NetImage(it, 120, Modifier.size(230.dp, 345.dp).background(Color(0xFF111111), io.github.kmmarin.tvbro.ui.orbit5.O5Shape.Card), ContentScale.Crop) }
                }
            }
        }
    }
    Box(Modifier.fillMaxSize().drawWithCache {
        val b = Brush.radialGradient(0f to Color(0x8C05060C), 0.72f to Color(0xEB05060C), center = Offset(size.width / 2, size.height * 0.58f), radius = size.width * 0.6f)
        onDrawBehind { drawRect(b) }
    })
}

/** Up to 24 posters of the stored catalogue for the wall, leaving out locked categories. */
private fun wallPosters(m: AppModel): List<String> = try {
    io.github.kmmarin.tvbro.data.SearchStore.use(m.getApplication<android.app.Application>()) { db ->
        val out = ArrayList<String>()
        db.query("SELECT img,cat FROM item WHERE kind IN (1,2) AND img IS NOT NULL AND img != '' ORDER BY CAST(xid AS INTEGER) DESC LIMIT 80", emptyArray()) { r ->
            val cat = r.str(1) ?: ""
            if (cat !in m.prefs.locked || cat in m.unlocked) r.str(0)?.let { out += it }
        }
        out.distinct().take(24)
    } ?: emptyList()
} catch (e: Exception) { emptyList() }

@Composable
private fun OrbitPicker(spots: List<Spot>, sel: Int, onSel: (Int) -> Unit, currentId: String?, choose: (Spot) -> Unit, manageFr: SafeFocus) {
    // the ring and the planet
    Box(Modifier.fillMaxSize().drawBehind {
        val tl = Offset(300.dp.toPx(), 296.dp.toPx()); val sz = Size(1320.dp.toPx(), 500.dp.toPx())
        for ((grow, a) in listOf(30f to 0.05f, 18f to 0.08f, 8f to 0.12f)) {
            val g = grow.dp.toPx()
            drawOval(Color(0xFF8A5CFF).copy(alpha = a), Offset(tl.x - g / 2, tl.y - g / 2), Size(sz.width + g, sz.height + g), style = Stroke(g))
        }
        drawOval(Color(0x1F2FD3FF), Offset(tl.x + 12.dp.toPx(), tl.y + 12.dp.toPx()), Size(sz.width - 24.dp.toPx(), sz.height - 24.dp.toPx()), style = Stroke(24.dp.toPx()))
        drawOval(Color(0x42FFFFFF), tl, sz, style = Stroke(3.dp.toPx()))
    })
    Image(painterResource(R.drawable.orbit_planet), null, Modifier.offset(730.dp, 331.dp).width(460.dp))
    val rot by animateFloatAsState(sel.toFloat(), tween(400), label = "orbit")
    var focused by remember { mutableStateOf(false) }
    val me = remember { SafeFocus() }
    // one focus target for the whole ring: Left / Right turn it, OK chooses, Down goes to Manage profiles
    Box(
        Modifier.fillMaxSize()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { if (sel > 0) onSel(sel - 1); true }
                    Key.DirectionRight -> { if (sel < spots.size - 1) onSel(sel + 1); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { spots.getOrNull(sel)?.let(choose); true }
                    Key.DirectionUp -> true
                    else -> false
                }
            }
            .focusProperties { down = manageFr.target() }
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) FocusMemory.focused(me) }
            .countFocus().safeFocus(me).focusable().testTag("orbit-ring"),
    ) {
        spots.forEachIndexed { i, s ->
            val d = i - rot
            if (abs(d) < 2.7f) Box(
                Modifier.offset {
                    val c = OrbitRing.center(i - rot)
                    IntOffset((c.x - 110f).dp.roundToPx(), (c.y - OrbitRing.FOCUSED / 2).dp.roundToPx())
                }.width(220.dp).graphicsLayer { alpha = OrbitRing.alpha(i - rot) },
            ) {
                val near = abs(d) < 0.5f
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.size(200.dp).graphicsLayer {
                        val k = OrbitRing.size(i - rot) / OrbitRing.FOCUSED
                        scaleX = k; scaleY = k
                    }.drawBehind {
                        if (focused && i == sel) {
                            val w = 5.dp.toPx() / (OrbitRing.size(i - rot) / OrbitRing.FOCUSED)
                            drawCircle(Color.White, size.minDimension / 2 + 5.dp.toPx() + w / 2, style = Stroke(w))
                        }
                    }, contentAlignment = Alignment.Center) { SpotAvatar(s, 200.dp, i, currentId) }
                    SpotName(s, near, Modifier.offset(y = (-25).dp * abs(d).coerceAtMost(1f)))
                }
            }
        }
    }
    LaunchedEffect(Unit) { repeat(10) { delay(100); if (me.request() && FocusMemory.anyFocus) return@LaunchedEffect } }
}

@Composable
private fun ClassicPicker(spots: List<Spot>, sel: Int, onSel: (Int) -> Unit, currentId: String?, choose: (Spot) -> Unit) {
    val first = remember { SafeFocus() }
    Row(
        Modifier.fillMaxSize().padding(top = 380.dp), horizontalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Top,
    ) {
        spots.forEachIndexed { i, s ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(200.dp)) {
                Box(Modifier.size(170.dp).o5Focus(CircleShape, 85.dp, focus = if (i == sel) first else null, onFocus = { onSel(i) }) { choose(s) }) {
                    SpotAvatar(s, 170.dp, i, currentId)
                }
                SpotName(s, i == sel, Modifier.padding(top = 4.dp))
            }
        }
    }
    LaunchedEffect(Unit) { repeat(10) { delay(100); if (first.request() && FocusMemory.anyFocus) return@LaunchedEffect } }
}

@Composable
private fun SpotAvatar(s: Spot, size: androidx.compose.ui.unit.Dp, i: Int, currentId: String?) {
    if (s.profile == null) {
        Box(Modifier.size(size).drawBehind {
            drawCircle(Color(0x4DFFFFFF), this.size.minDimension / 2 - 2.dp.toPx(), style = Stroke(4.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
        }, contentAlignment = Alignment.Center) { Icon(Icons.Filled.Add, "Add profile", tint = O5.Muted, modifier = Modifier.size(size * 0.37f)) }
    } else {
        Avatar(s.label, size, brush = if (s.profile.id == currentId) O5.AvatarGradient else AvatarColors.brush(s.label, i + 1))
    }
}

@Composable
private fun SpotName(s: Spot, near: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            s.label, maxLines = 1, textAlign = TextAlign.Center, color = if (near) Color.White else O5.Muted,
            fontSize = if (near) 32.sp else 26.sp, fontWeight = if (near) FontWeight.ExtraBold else FontWeight.SemiBold,
        )
        if (s.profile?.kids == true) {
            Box(Modifier.background(O5.Accent, androidx.compose.foundation.shape.RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 4.dp)) {
                Text("KIDS", style = O5Type.Badge, color = O5.OnAccent)
            }
        }
    }
}

/** "Manage profiles": add, delete, picker style and the start-up question, as a menu. */
private fun manageMenu(m: AppModel) {
    val p = m.profiles
    val items = ArrayList<Pair<String, () -> Unit>>()
    items += "Add a profile…" to { m.prompt = PromptState("Name of the new profile", "", false) { p.add(it) } }
    items += "Add a Kids profile…" to { m.prompt = PromptState("Name of the new Kids profile", "", false) { p.add(it, kids = true) } }
    items += "Picker style: ${p.style.label} (change to ${if (p.style == PickerStyle.ORBIT) "Classic" else "Orbit"})" to {
        p.changeStyle(if (p.style == PickerStyle.ORBIT) PickerStyle.CLASSIC else PickerStyle.ORBIT)
    }
    items += "Ask who's watching when Orbit starts: ${if (p.askOnStart) "On" else "Off"}" to { p.changeAskOnStart(!p.askOnStart) }
    for (pr in p.list.filter { it.id != p.currentId }) {
        items += "Delete ${pr.name}…" to {
            m.menu = MenuState("Delete ${pr.name}? Their My list, resume points and progress are deleted too.", listOf("Delete ${pr.name}" to { p.delete(pr) }))
        }
    }
    m.menu = MenuState("Manage profiles", items)
}
