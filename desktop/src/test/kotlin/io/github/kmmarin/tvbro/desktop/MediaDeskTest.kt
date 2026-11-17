// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import io.github.kmmarin.tvbro.data.VodItem
import io.github.kmmarin.tvbro.orbit.CatalogItem
import io.github.kmmarin.tvbro.orbit.CatalogNext
import io.github.kmmarin.tvbro.orbit.MediaApi
import io.github.kmmarin.tvbro.orbit.OrbitApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Plex / Jellyfin playback on the desktop against a made-up home server. */
class MediaDeskTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun done() { scope.cancel() }

    private val answer = """{"session":"ps1","url":"/v1/media/stream/ps1/file","kind":"file","container":"mkv","durationSecs":5400,
        "resumeSecs":1200,"title":"A show","subtitle":"S1 E2 · Two","audio":[],"subtitles":[{"id":"7","label":"English","lang":"eng","url":"/v1/media/stream/ps1/sub/7.vtt"}],
        "next":{"id":"e3","title":"S1 E3 · Three"}}"""

    private fun linkedApp(client: OkHttpClient): AppState {
        val app = AppState(scope, emptyList())
        app.orbit.showForTest(OrbitSaved(server = "http://192.0.2.10:8750", session = "s", profile = "Dani", profileId = "1"))
        app.media.api = MediaApi(OrbitApi(client, "Orbit-Desktop-Test"))
        return app
    }

    private fun waitFor(what: String, f: () -> Boolean) {
        val until = System.currentTimeMillis() + 5000
        while (!f()) { if (System.currentTimeMillis() > until) fail("timed out: $what"); Thread.sleep(20) }
    }

    @Test fun playsThroughTheHomeServerWithoutAStreamSlot() {
        val fake = FakeOrbit(mapOf("/v1/media/play" to answer, "/v1/media/progress" to """{"ok":true}"""))
        val app = linkedApp(fake.client)
        val show = VodItem("s|c1|show9", "c1", "series", "show9", "A show", null, "", null, "")
        app.media.playTitle(show, CatalogItem("s|c1|show9", "show", "A show", source = "plex", id = "show9", conn = "c1", next = CatalogNext("e2", 1, 2, "Two")))
        waitFor("playing") { app.playing != null }
        val r = app.playing!!
        assertEquals("http://192.0.2.10:8750/v1/media/stream/ps1/file", r.url)
        assertEquals(1200L, r.startSec)                           // its resume point (as IPTV titles resume)
        assertEquals("A show · S1 E2 · Two", r.title)
        assertEquals("e2", r.media!!.id); assertTrue(r.media!!.episode)
        assertEquals("e3", r.media!!.answer.next!!.id)
        assertNull(r.resumeKey)                                   // the media server keeps the resume point, not this app
        assertTrue(app.full)
        // no /v1/streams: no IPTV stream slot (a speed test may go first; this fake has none, so "auto" stays the default)
        assertEquals(listOf("/v1/media/play"), fake.asked.filterNot { it.startsWith("/v1/speedtest") })
        // stopping reports "stopped" for the session
        app.runOnUi { app.stopPlayback() }
        waitFor("stopped") { fake.asked.contains("/v1/media/progress") }
    }

    @Test fun fromTheStartAndHlsFallback() {
        var body = ""
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { ch ->
            body = ch.request().body?.let { b -> okio.Buffer().also { b.writeTo(it) }.readUtf8() } ?: ""
            Response.Builder().request(ch.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(answer.replace("\"kind\":\"file\"", "\"kind\":\"hls\"").toResponseBody("application/json".toMediaType())).build()
        }).build()
        val app = linkedApp(client)
        app.media.play("c1", "m1", "jellyfin", false, fromStart = true, hlsOnly = true, atSecs = null)
        waitFor("playing") { app.playing != null }
        assertEquals(0L, app.playing!!.startSec)
        assertTrue(body.contains("\"directPlay\":false"))
        assertTrue(body.contains("\"client\":\"desktop\""))
    }

    @Test fun aSignInThatEndedSaysLinkAgain() {
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { ch ->
            Response.Builder().request(ch.request()).protocol(Protocol.HTTP_1_1).code(409).message("Conflict")
                .body("""{"error":"Plex: the sign-in is no longer valid; link it again"}""".toResponseBody("application/json".toMediaType())).build()
        }).build()
        val app = linkedApp(client)
        app.media.play("c1", "m1", "plex", false)
        waitFor("message") { app.media.message.isNotEmpty() }
        assertEquals("Plex: the sign-in is no longer valid; link it again", app.media.message)
        assertNull(app.playing)
    }

    @Test fun theCookieOnlyForTheHomeServer() {
        val app = linkedApp(OkHttpClient())
        assertEquals("Cookie: tvbro_s=s", DeskMediaPlay.headerFields(app.orbit.link, "http://192.0.2.10:8750/v1/media/stream/ps1/file"))
        assertEquals("", DeskMediaPlay.headerFields(app.orbit.link, "http://203.0.113.9/file"))
        assertEquals("", DeskMediaPlay.headerFields(null, "http://192.0.2.10:8750/v1/media/stream/ps1/file"))
    }
}
