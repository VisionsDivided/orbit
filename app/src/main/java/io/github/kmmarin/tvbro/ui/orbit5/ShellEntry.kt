// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.ui.orbit5

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.kmmarin.tvbro.ui.SafeFocus

/**
 * Where focus goes when it comes down from the top bar into a page that has nothing remembered yet (first visit):
 * the sub-menu's active pill first, then the hero's primary button. Components register themselves
 * ([RegisterShellEntry]: SubMenuRow and primary [O5Button] do it); the shell provides one per page.
 */
class ShellEntry {
    private class E(val priority: Int, val focus: SafeFocus, val seq: Long)
    private val list = ArrayList<E>()
    private var seq = 0L

    fun add(focus: SafeFocus, priority: Int) { remove(focus); list += E(priority, focus, seq++) }
    fun remove(focus: SafeFocus) { list.removeAll { it.focus === focus } }

    /** Registered targets still on screen: lowest priority number first, the newest first among equals. */
    fun targets(): List<SafeFocus> = list.filter { it.focus.attachedNow }.sortedWith(compareBy<E>({ it.priority }, { -it.seq })).map { it.focus }

    companion object {
        const val SUB_MENU = 0
        const val HERO = 1
        const val OTHER = 2
    }
}

val LocalShellEntry = staticCompositionLocalOf<ShellEntry?> { null }

/** Offers [focus] as a way into the page from the top bar (see [ShellEntry]); no-op outside the shell. */
@Composable
fun RegisterShellEntry(focus: SafeFocus, priority: Int) {
    val e = LocalShellEntry.current ?: return
    DisposableEffect(e, focus, priority) { e.add(focus, priority); onDispose { e.remove(focus) } }
}
