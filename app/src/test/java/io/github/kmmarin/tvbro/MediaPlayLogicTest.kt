// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import io.github.kmmarin.tvbro.orbit.MediaApi
import io.github.kmmarin.tvbro.orbit.MediaPlay
import io.github.kmmarin.tvbro.orbit.MediaPlayAnswer
import io.github.kmmarin.tvbro.orbit.MediaPlayback
import io.github.kmmarin.tvbro.orbit.OrbitLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plex / Jellyfin playback on the TV: keys, the relay's cookie, what player errors say. */
class MediaPlayLogicTest {
    private val link = OrbitLink("http://192.0.2.10:8750", "sess1", "c", "Alex", "alex", "Alex", false)

    @Test fun keysLikeTheCatalog() {
        assertEquals("m|c1|i9", MediaPlayback.key("c1", "i9", episode = false))
        assertEquals("e|c1|e3", MediaPlayback.key("c1", "e3", episode = true))
    }

    @Test fun theCookieGoesOnlyToTheHomeServer() {
        var seen: Pair<String, String?>? = null
        val c = MediaPlayback.client(link).newBuilder().addInterceptor { ch ->
            seen = ch.request().url.host to ch.request().header("Cookie")
            okhttp3.Response.Builder().request(ch.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("ok")
                .body(okhttp3.ResponseBody.create(null, "")).build()
        }.build()
        c.newCall(okhttp3.Request.Builder().url("http://192.0.2.10:8750/v1/media/stream/ps1/file").build()).execute().close()
        assertEquals("192.0.2.10" to "tvbro_s=sess1", seen)
        c.newCall(okhttp3.Request.Builder().url("http://203.0.113.9/elsewhere").build()).execute().close()
        assertEquals("203.0.113.9" to null, seen)
        assertTrue(!c.followRedirects)
    }

    @Test fun playerErrorsInWords() {
        assertEquals("The Orbit home server stopped answering", MediaPlayback.playerError("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", "plex"))
        assertTrue(MediaPlayback.playerError("ERROR_CODE_IO_BAD_HTTP_STATUS", "jellyfin").contains("Jellyfin"))
        assertEquals("This title's format cannot be played on this TV", MediaPlayback.playerError("ERROR_CODE_DECODER_INIT_FAILED", "plex"))
    }

    @Test fun aMediaPlayCarriesTheAnswer() {
        val a = MediaPlayAnswer("ps1", "http://192.0.2.10:8750/v1/media/stream/ps1/master.m3u8", "hls", durationSecs = 60)
        val vp = VodPlay("e|c1|e3", a.url, "Show · S1 E3", MediaPlay("c1", "e3", "plex", true, a, MediaApi.startAt(a.resumeSecs, a.durationSecs, false)))
        assertEquals(0L, vp.media!!.startSecs)
        assertTrue(vp.media!!.p.isHls)
        assertNull(VodPlay("m|p0|1", "http://example.com/1.mkv", "IPTV film").media)
    }
}
