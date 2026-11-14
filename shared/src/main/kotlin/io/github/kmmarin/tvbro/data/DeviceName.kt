// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

/**
 * A name for this device ("Kitchen", "Bedroom"), shown in the stream-limit prompt and the device lists. Same rules as
 * the home server (orbit/accounts.py `device_name`): 1 to 32 letters (any language), digits, spaces and ' - . _ ( ) & + #;
 * runs of spaces become one.
 */
object DeviceName {
    const val MAX = 32
    private const val PUNCT = " '-._()&+#"

    /** The name as the server will store it, or null when it would refuse it. */
    fun clean(input: String): String? {
        val s = input.trim(' ').replace(Regex(" {2,}"), " ")
        if (s.isEmpty() || s.length > MAX) return null
        return if (s.all { it.isLetterOrDigit() || it in PUNCT }) s else null
    }

    /** The name offered before the person types one: the TV's model ("SHIELD Android TV"), tidied to fit the rules. */
    fun default(model: String?): String {
        val s = (model ?: "").filter { it.isLetterOrDigit() || it in PUNCT }.trim(' ').replace(Regex(" {2,}"), " ").take(MAX).trim(' ')
        return s.ifEmpty { "TV" }
    }

    const val HELP = "1 to 32 letters, digits or spaces (also ' - . _ ( ) & + #)"
}
