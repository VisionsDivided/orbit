// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import io.github.kmmarin.tvbro.orbit.MediaApi
import io.github.kmmarin.tvbro.orbit.MediaProblemKind
import io.github.kmmarin.tvbro.orbit.MediaProgressClock
import io.github.kmmarin.tvbro.orbit.OrbitApi
import io.github.kmmarin.tvbro.orbit.OrbitError
import io.github.kmmarin.tvbro.orbit.OrbitLink
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The media playback client against a fake home server (the contract in docs/orbit/API-v1.md "Playback"). */
class MediaApiTest {
    private val server = "http://192.0.2.10:8750"
    private val link = OrbitLink(server, "sess1", "csrf1", "Alex", "alex", "Alex", false)

    /** A fake home server: [answer] gets the path and the JSON body and returns (code, body). */
    private class Fake(val answer: (String, JsonObject?) -> Pair<Int, String>) {
        val calls = ArrayList<Triple<String, JsonObject?, okhttp3.Request>>()
        val client: okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder().addInterceptor(okhttp3.Interceptor { chain ->
            val r = chain.request()
            val body = r.body?.let { b -> okio.Buffer().also { b.writeTo(it) }.readUtf8() }?.let { Json.parseToJsonElement(it).jsonObject }
            calls += Triple(r.url.encodedPath, body, r)
            val (code, text) = answer(r.url.encodedPath, body)
            okhttp3.Response.Builder().request(r).protocol(okhttp3.Protocol.HTTP_1_1).code(code).message("x")
                .body(text.toResponseBody("application/json".toMediaType())).build()
        }).build()
    }

    private val playAnswer = """{"session":"ps1","url":"/v1/media/stream/ps1/file","kind":"file","container":"mkv","mime":"video/x-matroska",
        "durationSecs":5400,"resumeSecs":1200,"title":"The Film","subtitle":null,
        "audio":[{"id":"1","label":"English 5.1","lang":"eng","default":true},{"id":"2","label":"Deutsch","lang":"ger","default":false}],
        "subtitles":[{"id":"7","label":"English","lang":"eng","url":"/v1/media/stream/ps1/sub/7.vtt"},
                     {"id":"8","label":"Elsewhere","lang":"fre","url":"http://203.0.113.9/sub.vtt"}],
        "next":{"id":"e3","title":"S1 E3 · Next one"}}"""

    @Test fun playSendsTheContractAndReadsTheAnswer() {
        val f = Fake { _, _ -> 200 to playAnswer }
        val p = MediaApi(OrbitApi(f.client)).play(link, "c1", "i9", "tv")
        val (path, body, req) = f.calls.single()
        assertEquals("/v1/media/play", path)
        assertEquals(listOf("c1", "i9", "tv"), listOf("conn", "id", "client").map { body!![it]!!.jsonPrimitive.content })
        assertNull(body!!["caps"])
        assertEquals("tvbro_s=sess1", req.header("Cookie"))
        assertEquals("csrf1", req.header("X-CSRF-Token"))
        assertEquals("ps1", p.session)
        assertEquals("$server/v1/media/stream/ps1/file", p.url)
        assertFalse(p.isHls)
        assertEquals(5400L, p.durationSecs); assertEquals(1200L, p.resumeSecs)
        assertEquals(listOf("1", "2"), p.audio.map { it.id }); assertTrue(p.audio[0].default)
        // a subtitle somewhere else than the home server is dropped (the cookie never leaves it)
        assertEquals(listOf("$server/v1/media/stream/ps1/sub/7.vtt"), p.subtitles.map { it.url })
        assertEquals("e3", p.next!!.id)
    }

    @Test fun hlsFallbackAsksWithoutDirectPlay() {
        val f = Fake { _, _ -> 200 to """{"session":"ps2","url":"/v1/media/stream/ps2/master.m3u8","kind":"hls","durationSecs":60}""" }
        val p = MediaApi(OrbitApi(f.client)).play(link, "c1", "i9", "desktop", MediaApi.HLS_ONLY)
        assertTrue(p.isHls)
        assertEquals("false", f.calls.single().second!!["caps"]!!.jsonObject["directPlay"]!!.jsonPrimitive.content)
        assertNull(p.next); assertNull(p.resumeSecs); assertTrue(p.subtitles.isEmpty())
    }

    @Test fun anAnswerThatPointsElsewhereIsRefused() {
        for (bad in listOf(""""url":"http://203.0.113.9/file"""", """"url":"//203.0.113.9/file"""", """"url":"""""")) {
            val f = Fake { _, _ -> 200 to """{"session":"ps3",$bad,"kind":"file"}""" }
            try { MediaApi(OrbitApi(f.client)).play(link, "c1", "i9", "tv"); fail(bad) } catch (e: OrbitError) { assertEquals(502, e.code) }
        }
        val f = Fake { _, _ -> 200 to """{"url":"/v1/media/stream/x/file"}""" }
        try { MediaApi(OrbitApi(f.client)).play(link, "c1", "i9", "tv"); fail() } catch (e: OrbitError) { assertEquals(502, e.code) }
        // an absolute address on the home server itself is fine
        assertEquals("$server/v1/media/stream/x/file", MediaApi.onServer(server, "$server/v1/media/stream/x/file"))
    }

    @Test fun progressSendsSessionPositionAndState() {
        val f = Fake { _, _ -> 200 to """{"ok":true}""" }
        MediaApi(OrbitApi(f.client)).progress(link, "ps1", 1234, MediaApi.PAUSED)
        val (path, body, _) = f.calls.single()
        assertEquals("/v1/media/progress", path)
        assertEquals("ps1", body!!["session"]!!.jsonPrimitive.content)
        assertEquals("1234", body["positionSecs"]!!.jsonPrimitive.content)
        assertEquals("paused", body["state"]!!.jsonPrimitive.content)
    }

    @Test fun errorsAreToldClearly() {
        fun kind(code: Int, msg: String) = try {
            MediaApi(OrbitApi(Fake { _, _ -> code to """{"error":"$msg"}""" }.client)).play(link, "c1", "i9", "tv"); null
        } catch (e: OrbitError) { MediaApi.problem(e, "Plex") }
        assertEquals(MediaProblemKind.RELINK, kind(409, "Plex: the sign-in is no longer valid; link it again")!!.kind)
        assertEquals("Plex: the sign-in is no longer valid; link it again", kind(409, "Plex: the sign-in is no longer valid; link it again")!!.message)
        assertEquals(MediaProblemKind.UNREACHABLE, kind(502, "Plex could not be reached")!!.kind)
        assertEquals(MediaProblemKind.NOT_PLAYABLE, kind(404, "not found")!!.kind)
        assertEquals(MediaProblemKind.SIGNED_OUT, kind(401, "sign in")!!.kind)
        assertEquals(MediaProblemKind.UNREACHABLE, MediaApi.problem(java.net.ConnectException("refused"), "Plex").kind)
        // no text from the server: our own words, naming the source
        assertTrue(MediaApi.problem(OrbitError(409, "HTTP 409"), "Jellyfin").message.contains("Link Jellyfin again"))
    }

    @Test fun resumeLikeIptv() {
        assertEquals(1200L, MediaApi.startAt(1200, 5400, false))
        assertEquals(0L, MediaApi.startAt(1200, 5400, true))       // Play from start
        assertEquals(0L, MediaApi.startAt(4, 5400, false))         // the first seconds: from the start
        assertEquals(0L, MediaApi.startAt(5300, 5400, false))      // at the credits it counts as watched
        assertEquals(0L, MediaApi.startAt(null, 5400, false))
        assertEquals(300L, MediaApi.startAt(300, 0, false))        // unknown length: resume
    }

    @Test fun progressEveryTenSecondsAndOnPauseAndStop() {
        val c = MediaProgressClock()
        assertNull(c.tick(0, false))                                // still opening
        assertEquals("playing", c.tick(1_000, true))
        assertNull(c.tick(5_000, true))
        assertEquals("playing", c.tick(11_000, true))
        assertEquals("paused", c.tick(12_000, false))
        assertNull(c.tick(20_000, false))
        assertEquals("paused", c.tick(22_000, false))               // paused: every 10 s too (the server's idle stop)
        assertEquals("playing", c.tick(23_000, true))
        assertEquals("stopped", c.stop())
        assertNull(c.stop()); assertNull(c.tick(60_000, true))
        assertEquals("stopped", MediaProgressClock().stop())        // closed before it played: the server still ends the session
    }

    @Test fun titlePageParsing() {
        val ep = Json.parseToJsonElement("""{"items":[{"id":"e1","kind":"episode","season":1,"episode":2,"title":"Two","runtime":1800,
            "progress":{"secs":600,"dur":1800,"watched":false},"backdrop":"/v1/media/image?conn=c1&path=x"},
            {"id":"e2","kind":"episode","season":1,"episode":3,"title":"Three","runtime":1800,"progress":{"secs":0,"dur":1800,"watched":true}}]}""").jsonObject
        val eps = MediaApi.episodes(ep["items"] as kotlinx.serialization.json.JsonArray, server)
        assertEquals(listOf("S1 E2", "S1 E3"), eps.map { it.code })
        assertTrue(eps[0].inProgress); assertFalse(eps[1].inProgress); assertTrue(eps[1].watched)
        assertEquals("$server/v1/media/image?conn=c1&path=x", eps[0].image)
    }

    /** A fake server whose /v1/speedtest answers [size] bytes with X-Orbit-Away: [away]. */
    private fun speedServer(away: String, size: Int, hits: IntArray): okhttp3.OkHttpClient =
        okhttp3.OkHttpClient.Builder().addInterceptor(okhttp3.Interceptor { chain ->
            val r = chain.request()
            hits[0]++
            assertEquals("/v1/speedtest", r.url.encodedPath)
            assertEquals("tvbro_s=sess1", r.header("Cookie"))
            okhttp3.Response.Builder().request(r).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .header("X-Orbit-Away", away).body(ByteArray(size).toResponseBody("application/octet-stream".toMediaType())).build()
        }).build()

    @Test fun speedTestMeasuresAwayFromHomeAndIsReused() {
        val hits = IntArray(1)
        val l = link.copy(server = "http://192.0.2.11:8750")
        val api = OrbitApi(speedServer("1", 4 * 1024 * 1024, hits))
        val v = api.speedKbps(l, now = 1_000L)
        assertTrue("measured: $v", v != null && v > 0)
        assertEquals(v, api.speedKbps(l, now = 60_000L))                  // within 5 minutes: no new test
        assertEquals(1, hits[0])
        api.speedKbps(l, now = 1_000L + 6 * 60_000L)
        assertEquals(2, hits[0])
    }

    @Test fun speedTestAtHomeSendsNothing() {
        val hits = IntArray(1)
        val l = link.copy(server = "http://192.0.2.12:8750")
        assertNull(OrbitApi(speedServer("0", 1024 * 1024, hits)).speedKbps(l, now = 1_000L))
        assertNull(OrbitApi.speedFrom(0, 10.0)); assertNull(OrbitApi.speedFrom(100, 0.0))
        assertEquals(8000, OrbitApi.speedFrom(1_000_000, 1000.0))
    }

    @Test fun playSendsTheMeasuredSpeed() {
        val f = Fake { _, _ -> 200 to playAnswer }
        MediaApi(OrbitApi(f.client)).play(link, "c1", "i9", "tv", speedKbps = 12000)
        assertEquals("12000", f.calls.single().second!!["speedKbps"]!!.jsonPrimitive.content)
        MediaApi(OrbitApi(f.client)).play(link, "c1", "i9", "tv")
        assertNull(f.calls.last().second!!["speedKbps"])
    }
}
