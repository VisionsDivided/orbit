// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

/**
 * Every stream of the provider is in use: the prompt lists each one with its own "Stop <name> and watch". The name is
 * the device's own name ("Kitchen", "Bedroom": Settings ▸ Home server ▸ Name this TV, or the web app's device list),
 * else the profile that started it, else the device label; two streams with the same name are told apart by their
 * device label, then by a number. Recordings are listed but cannot be stopped from here.
 */
object StreamChoice {
    /** One active stream as the home server describes it (/v1/streams); [device]: its name, "" when not named. */
    data class Use(val id: String, val label: String, val profile: String, val what: String, val recording: Boolean,
                   val device: String = "")

    /** A line of the prompt: [stoppable] streams get a button. */
    data class Row(val id: String, val name: String, val what: String, val recording: Boolean) {
        val stoppable: Boolean get() = !recording && id.isNotEmpty()
        val button: String get() = "Stop ${name.take(40)} and watch"
    }

    fun rows(list: List<Use>): List<Row> {
        fun base(u: Use) = when {
            u.recording -> "Recording"
            u.device.isNotBlank() -> u.device.trim()
            u.profile.isNotBlank() -> u.profile.trim()
            u.label.isNotBlank() -> u.label.trim()
            else -> "Another device"
        }
        val bases = list.map(::base)
        // same name twice: add the device ("Kitchen on TV (Model)")
        val names = list.mapIndexed { i, u ->
            val b = bases[i]
            val dup = !u.recording && bases.count { it == b } > 1
            val named = u.device.isNotBlank() || u.profile.isNotBlank()
            if (dup && named && u.label.isNotBlank() && u.label.trim() != b) "$b on ${u.label.trim()}" else b
        }
        // still the same: number them
        val seen = HashMap<String, Int>()
        return list.mapIndexed { i, u ->
            val n = names[i]
            val name = if (!u.recording && names.count { it == n } > 1) "$n (${seen.merge(n, 1, Int::plus)})" else n
            Row(u.id, name, u.what.trim(), u.recording)
        }
    }
}
