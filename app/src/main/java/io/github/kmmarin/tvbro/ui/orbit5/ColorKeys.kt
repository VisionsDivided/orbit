// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.orbit5

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The remote's four colour keys, with their Android key codes and the spec's colours. */
enum class ColorKey(val code: Int, val color: Color) {
    RED(android.view.KeyEvent.KEYCODE_PROG_RED, O5.KeyRed),
    GREEN(android.view.KeyEvent.KEYCODE_PROG_GREEN, O5.KeyGreen),
    YELLOW(android.view.KeyEvent.KEYCODE_PROG_YELLOW, O5.KeyYellow),
    BLUE(android.view.KeyEvent.KEYCODE_PROG_BLUE, O5.KeyBlue);

    companion object { fun of(code: Int): ColorKey? = values().firstOrNull { it.code == code } }
}

/** What a colour key does on the current screen: the legend shows [label], the key runs [run]. */
class KeyAction(val label: String, val run: () -> Unit)

/**
 * The colour-key dispatcher. Screens register their actions while they are on screen ([ColorKeyActions]); the newest
 * registration wins (a page opened over another), and it goes when the screen leaves. MainActivity hands every
 * KEYCODE_PROG_RED / GREEN / YELLOW / BLUE press to [dispatch]; a key with no action falls through to the app's old
 * behaviour (yellow = My list).
 */
object ColorKeys {
    internal class Entry(val owner: Any) {
        var labels by mutableStateOf<Map<ColorKey, String>>(emptyMap())
        var runs: Map<ColorKey, () -> Unit> = emptyMap()
    }
    private val stack = mutableStateListOf<Entry>()

    /** Labels of the active registration (observable: the legend recomposes when they change). */
    val labels: Map<ColorKey, String> get() = stack.lastOrNull()?.labels ?: emptyMap()

    /** Registers or replaces [owner]'s actions; null actions are left out. Empty = the same as [clear]. */
    fun set(owner: Any, actions: Map<ColorKey, KeyAction?>) {
        val a = actions.filterValues { it != null }.mapValues { it.value!! }
        var e = stack.firstOrNull { it.owner === owner }
        if (e == null) { e = Entry(owner); stack.add(e) }
        val l = a.mapValues { it.value.label }
        if (e.labels != l) e.labels = l
        e.runs = a.mapValues { it.value.run }
    }

    fun clear(owner: Any) { stack.removeAll { it.owner === owner } }

    /** Runs the active action for [keyCode]; false when it is not a colour key or nothing is registered for it. */
    fun dispatch(keyCode: Int): Boolean {
        val k = ColorKey.of(keyCode) ?: return false
        val run = stack.lastOrNull()?.runs?.get(k) ?: return false
        run()
        return true
    }

    /** For tests. */
    internal fun reset() = stack.clear()
}

/**
 * Registers this screen's colour-key actions while it is composed; pass null for keys it does not use. The lambdas
 * may read the focused item: the newest ones are used.
 */
@Composable
fun ColorKeyActions(red: KeyAction? = null, green: KeyAction? = null, yellow: KeyAction? = null, blue: KeyAction? = null, enabled: Boolean = true) {
    val owner = remember { Any() }
    SideEffect {
        if (enabled) ColorKeys.set(owner, mapOf(ColorKey.RED to red, ColorKey.GREEN to green, ColorKey.YELLOW to yellow, ColorKey.BLUE to blue))
        else ColorKeys.clear(owner)
    }
    DisposableEffect(owner) { onDispose { ColorKeys.clear(owner) } }
}

/**
 * The colour-key legend pill (spec section 9): the active screen's keys, then button hints ("OK" to "Watch") after a
 * divider. Draws nothing when there are no keys and no hints. Place it at the content's left edge, 28 px from the
 * bottom.
 */
@Composable
fun ColorKeyLegend(modifier: Modifier = Modifier, hints: List<Pair<String, String>> = emptyList()) {
    val labels = ColorKeys.labels
    if (labels.isEmpty() && hints.isEmpty()) return
    Row(
        modifier.background(O5.LegendPanel, O5Shape.Pill).border(1.dp, Color(0x1AFFFFFF), O5Shape.Pill).padding(horizontal = 26.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(30.dp),
    ) {
        for (k in ColorKey.values()) {
            val l = labels[k] ?: continue
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                Box(Modifier.size(38.dp, 17.dp).background(k.color, RoundedCornerShape(9.dp)))
                Text(l, style = O5Type.Legend, maxLines = 1)
            }
        }
        if (hints.isNotEmpty()) {
            if (labels.isNotEmpty()) Box(Modifier.width(1.dp).height(28.dp).background(Color(0x2EFFFFFF)))
            for ((button, what) in hints) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.border(1.5.dp, Color(0x66FFFFFF), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp)) {
                        Text(button, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
                    }
                    Text(what, style = O5Type.Legend, color = Color(0x8CFFFFFF), maxLines = 1)
                }
            }
        }
    }
}
