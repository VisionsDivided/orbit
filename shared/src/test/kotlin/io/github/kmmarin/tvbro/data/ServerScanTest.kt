// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerScanTest {
    @Test fun scansTheOwnSlash24WithoutItself() {
        val h = ServerScan.subnetHosts("192.168.1.37")
        assertEquals(253, h.size)
        assertEquals("192.168.1.1", h.first())
        assertEquals("192.168.1.254", h.last())
        assertFalse("192.168.1.37" in h)
        assertFalse("192.168.1.0" in h || "192.168.1.255" in h)
        assertEquals("192.168.1.x", ServerScan.subnetLabel("192.168.1.37"))
    }

    @Test fun onlyPrivateHomeNetworks() {
        assertTrue(ServerScan.isPrivate("10.0.0.5"))
        assertTrue(ServerScan.isPrivate("172.20.1.2"))
        assertFalse(ServerScan.isPrivate("172.32.1.2"))
        assertFalse(ServerScan.isPrivate("192.0.2.10"))          // documentation range: not a home network
        assertFalse(ServerScan.isPrivate("100.64.0.1"))          // carrier NAT / Tailscale: not scanned
        for (bad in listOf("", "fe80::1", "1.2.3", "192.168.1.300", "192.168.01.x")) {
            assertFalse(bad, ServerScan.isPrivate(bad))
            assertTrue(bad, ServerScan.subnetHosts(bad).isEmpty())
        }
        assertEquals("192.168.0.9", ServerScan.pick(listOf("100.64.0.1", "192.168.0.9", "10.0.0.2")))
        assertNull(ServerScan.pick(listOf("100.64.0.1")))
    }

    @Test fun recognisesOrbitsHello() {
        assertEquals(true, ServerScan.parseHello("""{"app":"orbit","api":1,"hostOk":true}"""))
        assertEquals(false, ServerScan.parseHello("""{"app":"orbit","api":1,"hostOk":false}"""))
        assertNull(ServerScan.parseHello("""{"status":"ok"}"""))
        assertNull(ServerScan.parseHello("<html>router login</html>"))
        assertNull(ServerScan.parseHello("""["orbit"]"""))
        assertEquals("http://192.168.1.20:8750", ServerScan.fieldValue("192.168.1.20"))
    }
}
