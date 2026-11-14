// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import io.github.kmmarin.tvbro.orbit.OrbitApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrbitApiTest {
    @Test fun serverAddresses() {
        assertEquals("http://192.0.2.10:8750", OrbitApi.normalizeServer("192.0.2.10:8750"))
        assertEquals("http://192.0.2.10:8750", OrbitApi.normalizeServer("  http://192.0.2.10:8750/ "))
        assertEquals("https://orbit.example.com", OrbitApi.normalizeServer("https://orbit.example.com"))
        assertEquals("http://orbit.example.com", OrbitApi.normalizeServer("orbit.example.com"))
        for (bad in listOf("", "http://", "ftp://orbit.example.com", "http://user:pw@orbit.example.com",
            "http://orbit.example.com/some/path", "http://orbit.example.com/?x=1", "orbit example com", "file:///etc/hosts")) {
            assertNull(bad, OrbitApi.normalizeServer(bad))
        }
    }

    @Test fun approveLinkOpensTheWebDialog() {
        assertEquals("http://192.0.2.10:8750/#pair:BCDF-2345", OrbitApi.approveUrl("http://192.0.2.10:8750", "BCDF-2345"))
    }

    @Test fun linkFromTheServersAnswer() {
        val me = Json.parseToJsonElement(
            """{"account":{"id":"a1","username":"alex","displayName":"Alex","role":"owner"},
               "profiles":[],"profile":{"id":"p1","name":"Kids","kids":true,"created":1},"csrf":"c"}""",
        ).jsonObject
        val l = OrbitApi.linkFrom("http://192.0.2.10:8750", "sid", "csrf", me)
        assertEquals(listOf("Alex", "alex", "Kids"), listOf(l.account, l.username, l.profile))
        assertEquals(true, l.kids)
    }
}
