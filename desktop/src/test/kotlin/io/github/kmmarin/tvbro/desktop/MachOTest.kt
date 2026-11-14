// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** macOS libmpv: Mach-O detection from synthetic headers (no real dylib needed) and the macOS search order. */
class MachOTest {
    @get:Rule val tmp = TemporaryFolder()

    private val x64 = 0x01000007
    private val arm64 = 0x0100000C

    private fun file(name: String, b: ByteArray): File = File(tmp.root, name).apply { parentFile.mkdirs(); writeBytes(b) }

    /** A thin 64-bit Mach-O header in [order] (little-endian on Intel and Apple Silicon). */
    private fun thin(cpu: Int, magic: Int = MachO.MH_MAGIC_64, order: ByteOrder = ByteOrder.LITTLE_ENDIAN): ByteArray =
        ByteBuffer.allocate(256).order(order).putInt(magic).putInt(cpu).putInt(3).putInt(6).array() // magic, cputype, subtype, MH_DYLIB

    /** A universal header (always big-endian) with one record per cpu. */
    private fun fat(vararg cpus: Int, wide: Boolean = false): ByteArray {
        val b = ByteBuffer.allocate(4096).order(ByteOrder.BIG_ENDIAN).putInt(if (wide) MachO.FAT_MAGIC_64 else MachO.FAT_MAGIC).putInt(cpus.size)
        cpus.forEachIndexed { i, c ->
            b.putInt(c).putInt(0)
            if (wide) b.putLong(4096L * (i + 1)).putLong(1024).putInt(12).putInt(0) else b.putInt(4096 * (i + 1)).putInt(1024).putInt(12)
        }
        return b.array()
    }

    @Test fun thinHeaders() {
        // bytes on disk for a 64-bit Apple Silicon dylib: CF FA ED FE 0C 00 00 01
        val raw = thin(arm64)
        assertEquals(listOf(0xCF, 0xFA, 0xED, 0xFE, 0x0C, 0x00, 0x00, 0x01), raw.take(8).map { it.toInt() and 0xff })
        assertEquals(BinArch("macho", "arm64"), BinArch.of(file("a.dylib", raw)))
        assertEquals(BinArch("macho", "x64"), BinArch.of(file("b.dylib", thin(x64))))
        assertEquals(BinArch("macho", "x86"), BinArch.of(file("c.dylib", thin(7, MachO.MH_MAGIC))))
        assertEquals(BinArch("macho", "x64"), BinArch.of(file("d.dylib", thin(x64, order = ByteOrder.BIG_ENDIAN))))
        assertEquals(BinArch("macho", "other"), BinArch.of(file("ppc.dylib", thin(18, MachO.MH_MAGIC, ByteOrder.BIG_ENDIAN))))
    }

    @Test fun universalHeaders() {
        val u = BinArch.of(file("u.dylib", fat(x64, arm64)))!!
        assertEquals("macho", u.format); assertEquals("universal", u.arch); assertEquals(listOf("x64", "arm64"), u.all)
        assertEquals(BinArch("macho", "universal", listOf("arm64", "x64")), BinArch.of(file("u64.dylib", fat(arm64, x64, wide = true))))
        assertEquals(BinArch("macho", "arm64"), BinArch.of(file("one.dylib", fat(arm64))))
    }

    @Test fun notMachO() {
        // a Java class file also starts with CAFEBABE, then minor / major version (here Java 17 = 61)
        val cls = ByteBuffer.allocate(128).putInt(MachO.FAT_MAGIC).putShort(0).putShort(61).array()
        assertNull(BinArch.of(file("A.class", cls)))
        assertNull(BinArch.of(file("zero.dylib", ByteBuffer.allocate(128).putInt(MachO.FAT_MAGIC).putInt(0).array())))
        // a fat header whose records run past the end of the file
        assertNull(BinArch.of(file("cut.dylib", ByteBuffer.allocate(64).putInt(MachO.FAT_MAGIC).putInt(5).array())))
        assertNull(BinArch.of(file("short.dylib", thin(arm64).copyOf(16))))
    }

    @Test fun problemOnMac() {
        val arm = file("libmpv.2.dylib", thin(arm64))
        assertNull(MpvLocate.problem(arm, Os.MAC, "arm64"))
        assertTrue(MpvLocate.problem(arm, Os.MAC, "x64")!!.contains("aarch64")) // Homebrew arm64 lib under an Intel (Rosetta) JVM
        val uni = file("uni/libmpv.2.dylib", fat(x64, arm64))
        assertNull(MpvLocate.problem(uni, Os.MAC, "x64"))
        assertNull(MpvLocate.problem(uni, Os.MAC, "arm64"))
        assertEquals("libmpv.2.dylib is not a macOS library (.dylib)", MpvLocate.problem(file("elf/libmpv.2.dylib", ByteArray(64).also {
            it[0] = 0x7f; it[1] = 'E'.code.toByte(); it[2] = 'L'.code.toByte(); it[3] = 'F'.code.toByte(); it[18] = 0xB7.toByte()
        }), Os.MAC, "arm64"))
        // and a Mach-O file is not accepted where a DLL or a .so is expected
        assertEquals("libmpv.2.dylib is not a Windows DLL", MpvLocate.problem(arm, Os.WINDOWS, "arm64"))
        assertEquals("libmpv.2.dylib is not a Linux shared library", MpvLocate.problem(arm, Os.LINUX, "arm64"))
    }

    @Test fun macSearchOrder() {
        val app = "/Applications/Orbit.app/Contents/MacOS"
        val c = MpvLocate.candidates(Os.MAC, "/Users/me/mpv/libmpv.2.dylib", app)
        assertEquals(MpvCandidate("/Users/me/mpv/libmpv.2.dylib", file = true, chosen = true), c.first())
        val files = c.filter { it.file && !it.chosen }.map { it.path.replace('\\', '/') }
        assertEquals(listOf(
            "/Applications/Orbit.app/Contents/Frameworks/libmpv.2.dylib", "/Applications/Orbit.app/Contents/Frameworks/libmpv.dylib",
            "$app/lib/libmpv.2.dylib", "$app/lib/libmpv.dylib", "$app/libmpv.2.dylib", "$app/libmpv.dylib",
            "/opt/homebrew/lib/libmpv.2.dylib", "/opt/homebrew/lib/libmpv.dylib",
            "/usr/local/lib/libmpv.2.dylib", "/usr/local/lib/libmpv.dylib",
            "/opt/local/lib/libmpv.2.dylib", "/opt/local/lib/libmpv.dylib",
        ), files)
        assertEquals(listOf("mpv", "libmpv.2.dylib"), c.filter { !it.file }.map { it.path })
        // without an app folder (gradle run): straight to Homebrew / MacPorts
        assertEquals("/opt/homebrew/lib/libmpv.2.dylib", MpvLocate.candidates(Os.MAC, null, null).first().path.replace('\\', '/'))
    }

    @Test fun macLoadFallbacks() {
        val tried = mutableListOf<String>()
        val fake = Proxy.newProxyInstance(MpvLib::class.java.classLoader, arrayOf(MpvLib::class.java)) { _, _, _ -> null } as MpvLib
        // an Intel-only dylib bundled in the app on an Apple Silicon JVM is skipped; a universal one is loaded
        val app = File(tmp.root, "Orbit.app/Contents/MacOS").apply { mkdirs() }
        file("Orbit.app/Contents/Frameworks/libmpv.2.dylib", thin(x64))
        file("Orbit.app/Contents/MacOS/lib/libmpv.2.dylib", fat(x64, arm64))
        val r = MpvLocate.load(MpvLocate.candidates(Os.MAC, null, app.path), Os.MAC, "arm64") { tried += it; fake }
        assertSame(fake, r.lib)
        assertEquals(File(app, "lib/libmpv.2.dylib").path, r.from)
        // nothing loads anywhere: the bundled file's problem is the reason, else the brew hint
        val r2 = MpvLocate.load(MpvLocate.candidates(Os.MAC, null, app.path), Os.MAC, "x64") { throw UnsatisfiedLinkError("dlopen failed") }
        assertTrue(r2.error!!, r2.error!!.startsWith("libmpv.2.dylib could not be loaded: dlopen failed"))
        val none = MpvLocate.load(listOf(MpvCandidate("mpv", file = false)), Os.MAC, "arm64") { throw UnsatisfiedLinkError("no") }
        assertEquals("libmpv was not found (macOS: brew install mpv)", none.error)
    }

    @Test fun missingLibStepsOnMac() {
        val s = MissingLibText.steps(Os.MAC, "/Applications/Orbit.app/Contents/MacOS")
        assertTrue(s.first().contains("brew install mpv"))
        assertTrue(s.none { "libmpv-2.dll" in it || "apt install" in it })
        assertEquals("libmpv.2.dylib", MpvLocate.fileName(Os.MAC))
    }
}
