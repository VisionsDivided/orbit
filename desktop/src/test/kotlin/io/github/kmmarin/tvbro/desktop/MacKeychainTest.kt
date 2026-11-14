// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Base64

/** The macOS Keychain key logic with a fake `security` tool (the real one only exists on a Mac). */
class MacKeychainTest {
    /** A pretend Keychain: `security -i` reads add-generic-password from stdin, find-generic-password -w prints it. */
    private class FakeSecurity(var stored: String? = null, var lookupFails: ProcOut? = null, var addWorks: Boolean = true) {
        val calls = mutableListOf<List<String>>()
        val stdins = mutableListOf<String>()
        fun run(cmd: List<String>, stdin: ByteArray?): ProcOut {
            calls += cmd
            stdin?.let { stdins += String(it) }
            assertEquals(MacKeychain.TOOL, cmd[0])
            return when {
                cmd.getOrNull(1) == "find-generic-password" -> lookupFails ?: stored?.let { ProcOut(0, it + "\n", "") }
                    ?: ProcOut(44, "", "security: SecKeychainSearchCopyNext: The specified item could not be found in the keychain.\n")
                cmd == listOf(MacKeychain.TOOL, "-i") -> {
                    val words = String(stdin!!).trim().split(' ')
                    if (addWorks) stored = words[words.indexOf("-w") + 1]
                    ProcOut(0, "", "")
                }
                else -> error("unexpected $cmd")
            }
        }
    }

    private fun dir(): File = Files.createTempDirectory("tvbro-kc").toFile()

    @Test fun classify() {
        val k = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
        assertEquals(KeyringLookup.FOUND, MacKeychain.classify(ProcOut(0, "$k\n", "")))
        assertEquals(KeyringLookup.NOT_FOUND, MacKeychain.classify(ProcOut(44, "", "could not be found")))
        assertEquals(KeyringLookup.ERROR, MacKeychain.classify(ProcOut(0, "short\n", ""))) // not a 32-byte key
        assertEquals(KeyringLookup.ERROR, MacKeychain.classify(ProcOut(51, "", "User interaction is not allowed."))) // locked
        assertEquals(KeyringLookup.ERROR, MacKeychain.classify(ProcOut(128, "", ""))) // prompt cancelled
        assertEquals(KeyringLookup.ERROR, MacKeychain.classify(ProcOut(-1, "", "IOException"))) // tool missing
    }

    @Test fun newKeyIsStoredWithoutTheCommandLine() {
        val fake = FakeSecurity()
        val k = MacKeychain(fake::run).loadOrCreate(File(dir(), "key.bin"))!!
        assertEquals(32, k.size)
        val b64 = Base64.getEncoder().encodeToString(k)
        assertEquals(b64, fake.stored)
        assertTrue(fake.calls.none { c -> c.any { b64 in it } }) // never in argv (visible in ps)
        assertEquals(listOf(MacKeychain.TOOL, "-i"), fake.calls[1])
        assertTrue(fake.stdins.single().startsWith("add-generic-password -U -s tvBro -a secrets-key "))
        // the next start reads the same key
        assertArrayEquals(k, MacKeychain(fake::run).loadOrCreate(File(dir(), "key.bin")))
    }

    @Test fun lockedKeychainNeverMakesANewKey() {
        val fake = FakeSecurity(lookupFails = ProcOut(51, "", "User interaction is not allowed."))
        try { MacKeychain(fake::run).loadOrCreate(File(dir(), "key.bin")); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("51")) }
        assertEquals(1, fake.calls.size) // no add attempted
    }

    @Test fun existingKeyFileWinsWhenNothingIsStored() {
        val d = dir()
        val kf = File(d, "key.bin")
        val old = KeyFile.loadOrCreate(kf)
        val fake = FakeSecurity()
        assertArrayEquals(old, MacKeychain(fake::run).loadOrCreate(kf))
        assertNull(fake.stored)
    }

    @Test fun failedStoreReturnsNull() {
        val fake = FakeSecurity(addWorks = false)
        assertNull(MacKeychain(fake::run).loadOrCreate(File(dir(), "key.bin")))
    }

    @Test fun scriptRefusesNonBase64() {
        try { MacKeychain.addScript("abc def\nrm"); fail() } catch (e: IllegalArgumentException) { }
        assertEquals("add-generic-password -U -s tvBro -a secrets-key -l tvBro-secrets-key -w QUJD\n", String(MacKeychain.addScript("QUJD")))
    }

    /** Only on a Mac with an unlocked login Keychain and TVBRO_TEST_KEYCHAIN=1 (writes and deletes a test item). */
    @Test fun realKeychain() {
        assumeTrue(MacKeychain.available && System.getenv("TVBRO_TEST_KEYCHAIN") == "1")
        val key = ByteArray(32) { (it * 7).toByte() }
        val kc = MacKeychain()
        val before = kc.lookup()
        assumeTrue("a real tvBro key is already stored; not touching it", MacKeychain.classify(before) == KeyringLookup.NOT_FOUND)
        try {
            assertTrue(kc.store(key))
            assertArrayEquals(key, Keyring.decode(kc.lookup().out))
            assertTrue(ProcessBuilder("ps", "-axo", "args").start().inputStream.bufferedReader().readText().lines().none { "security" in it && "-w " in it })
        } finally {
            MacKeychain.runProc(listOf(MacKeychain.TOOL, "delete-generic-password", "-s", MacKeychain.SERVICE, "-a", MacKeychain.ACCOUNT), null)
        }
    }
}
