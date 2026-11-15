// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The app may not ask to install packages unless the in-app update is built in (Orbit builds have it off). */
class InstallPermissionTest {
    private fun module(path: String): File =
        listOf(File(path), File("app", path)).firstOrNull { it.exists() } ?: error("not found: $path")

    @Test fun mainManifestHasNoInstallPermission() {
        val main = module("src/main/AndroidManifest.xml").readText()
        assertFalse("REQUEST_INSTALL_PACKAGES must not be in the main manifest",
            Regex("""<uses-permission[^>]+REQUEST_INSTALL_PACKAGES""").containsMatchIn(main))
        assertTrue(module("src/selfupdate/AndroidManifest.xml").readText().contains("REQUEST_INSTALL_PACKAGES"))
    }

    @Test fun selfUpdateIsOffInThisBuild() {
        assertFalse(BuildConfig.UPDATE_CHECK)
        assertEquals(false, io.github.kmmarin.tvbro.update.Updater.supported)
    }
}
