// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

/** The desktop systems the app knows. Anything that is neither Windows nor macOS is treated like Linux. */
enum class Os {
    WINDOWS, LINUX, MAC;

    companion object {
        /** From the JVM's `os.name` ("Windows 11", "Linux", "Mac OS X"). */
        fun of(osName: String): Os {
            val n = osName.lowercase()
            return when {
                n.contains("mac") || n.contains("darwin") -> MAC // first: "darwin" contains "win"
                n.contains("win") -> WINDOWS
                else -> LINUX
            }
        }
    }
}

/** The one place the running OS is decided; everything else asks here. */
object Platform {
    val os: Os = Os.of(System.getProperty("os.name"))
    val windows: Boolean = os == Os.WINDOWS
    val mac: Boolean = os == Os.MAC
    val linux: Boolean = os == Os.LINUX
}
