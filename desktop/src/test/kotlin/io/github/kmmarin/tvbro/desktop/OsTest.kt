// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class OsTest {
    @Test fun osNames() {
        assertEquals(Os.WINDOWS, Os.of("Windows 11"))
        assertEquals(Os.WINDOWS, Os.of("Windows Server 2022"))
        assertEquals(Os.MAC, Os.of("Mac OS X"))
        assertEquals(Os.MAC, Os.of("Darwin")) // contains "win", must still be macOS
        assertEquals(Os.LINUX, Os.of("Linux"))
        assertEquals(Os.LINUX, Os.of("FreeBSD"))
    }
}
