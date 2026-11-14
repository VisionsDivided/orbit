// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import io.github.kmmarin.tvbro.orbit.OrbitApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class OrbitApiTest {
    @Test fun serverAddresses() {
        assertEquals("http://192.0.2.10:8750", OrbitApi.normalizeServer("192.0.2.10:8750"))
        assertEquals("http://192.0.2.10:8750", OrbitApi.normalizeServer("  http://192.0.2.10:8750/ "))
        assertEquals("https://orbit.example.com", OrbitApi.normalizeServer("https://orbit.example.com"))
        // no port typed on plain http: Orbit's own port 8750
        assertEquals("http://orbit.example.com:8750", OrbitApi.normalizeServer("orbit.example.com"))
        assertEquals("http://192.0.2.10:8750", OrbitApi.normalizeServer("192.0.2.10"))
        assertEquals("http://192.0.2.10", OrbitApi.normalizeServer("http://192.0.2.10:80"))
        assertEquals("http://[2001:db8::1]:8750", OrbitApi.normalizeServer("http://[2001:db8::1]"))
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

    @Test fun sessionCookieOfASignIn() {
        assertEquals("abc", OrbitApi.sessionFromCookies(listOf("other=1; Path=/", "tvbro_s=abc; HttpOnly; SameSite=Strict; Path=/")))
        assertNull(OrbitApi.sessionFromCookies(listOf("tvbro_s=; Max-Age=0")))
        assertNull(OrbitApi.sessionFromCookies(emptyList()))
    }

    @Test fun desktopSignInSendsItsKindAndReadsTheCookie() {
        var sent: Pair<String, String?>? = null
        val client = okhttp3.OkHttpClient.Builder().addInterceptor(okhttp3.Interceptor { chain ->
            val r = chain.request()
            sent = r.url.encodedPath to r.body?.let { b -> okio.Buffer().also { b.writeTo(it) }.readUtf8() }
            assertEquals("1", r.header("X-TvBro"))
            assertEquals("Orbit-Desktop", r.header("User-Agent"))
            okhttp3.Response.Builder().request(r).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("ok")
                .header("Set-Cookie", "tvbro_s=sess1; HttpOnly; Path=/")
                .body("""{"account":{"id":"a1","username":"alex","displayName":"Alex"},"csrf":"c1",
                    "profiles":[{"id":"p1","name":"Alex","kids":false},{"id":"p2","name":"Kids","kids":true}],
                    "profile":{"id":"p1","name":"Alex","kids":false}}""".toResponseBody("application/json".toMediaType())).build()
        }).build()
        val me = OrbitApi(client, "Orbit-Desktop").login("http://192.0.2.10:8750", "alex", "pw", "Orbit on Windows")
        assertEquals("/v1/auth/login", sent!!.first)
        val body = Json.parseToJsonElement(sent!!.second!!).jsonObject
        assertEquals("desktop", body["kind"].toString().trim('"'))
        assertEquals(listOf("sess1", "c1", "Alex"), listOf(me.link.session, me.link.csrf, me.link.profile))
        assertEquals(listOf("p1", "p2"), me.profiles.map { it.id })
        assertEquals(true, me.profiles[1].kids)
        assertEquals("p1", me.profileId)
    }
}
