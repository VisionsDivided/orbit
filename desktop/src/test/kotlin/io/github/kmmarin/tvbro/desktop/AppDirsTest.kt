// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Per-OS folders: macOS uses ~/Library; Windows and Linux stay where they always were. */
class AppDirsTest {
    private fun p(f: File) = f.path.replace('\\', '/')

    @Test fun mac() {
        val d = AppDirs.of(Os.MAC, "/Users/me") { error("macOS ignores env: $it") }
        assertEquals("/Users/me/Library/Application Support/tvBro", p(d.config))
        assertEquals("/Users/me/Library/Caches/tvBro", p(d.cache))
        assertEquals("/Users/me/Library/Logs/tvBro", p(d.logs))
        assertEquals("/Users/me/Movies/tvBro", p(d.recordings))
    }

    @Test fun windowsUnchanged() {
        val env = mapOf("APPDATA" to "C:\\Users\\<user>\\AppData\\Roaming", "LOCALAPPDATA" to "C:\\Users\\<user>\\AppData\\Local")
        val d = AppDirs.of(Os.WINDOWS, "C:\\Users\\<user>", env::get)
        assertEquals("C:/Users/<user>/AppData/Roaming/tvBro", p(d.config))
        assertEquals("C:/Users/<user>/AppData/Local/tvBro/cache", p(d.cache))
        assertEquals("C:/Users/<user>/Videos/tvBro", p(d.recordings))
        assertEquals("C:/Users/<user>/AppData/Roaming/tvBro", p(AppDirs.of(Os.WINDOWS, "C:\\Users\\<user>") { null }.config))
    }

    @Test fun linuxUnchanged() {
        val d = AppDirs.of(Os.LINUX, "/home/user") { null }
        assertEquals("/home/user/.config/tvbro", p(d.config))
        assertEquals("/home/user/.cache/tvbro", p(d.cache))
        assertEquals("/home/user/Videos/tvBro", p(d.recordings))
        assertEquals("/x/tvbro", p(AppDirs.of(Os.LINUX, "/home/user") { if (it == "XDG_CONFIG_HOME") "/x" else null }.config))
    }

    /** The app is called Orbit now, but its data stays in the tvBro folders and Keychain item so upgrades keep everything. */
    @Test fun renameToOrbitKeepsTheDataLocations() {
        assertEquals("tvBro", AppDirs.NAME)
        assertEquals("tvBro", MacKeychain.SERVICE)
        val d = AppDirs.of(Os.WINDOWS, "C:\\Users\\<user>") { null }
        assertTrue(listOf(d.config, d.cache, d.logs, d.recordings).none { p(it).contains("Orbit") })
    }

    @Test fun fileLogRotates() {
        val dir = Files.createTempDirectory("tvbro-log").toFile()
        val f = File(dir, "tvbro.log")
        val log = FileLog(f, max = 50)
        repeat(5) { log.line("line $it") }
        assertTrue(f.readText().contains("line 4"))
        FileLog(f, max = 50).line("after")
        assertTrue(File(dir, "tvbro.log.1").readText().contains("line 4"))
        assertTrue(f.readText().trim().endsWith("after"))
    }
}
