// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveFormatTest {
    @Test fun xtreamLiveTsBecomesHls() {
        assertEquals("http://tv.example.com:8080/live/u/p/123.m3u8", LiveFormat.toHls("http://tv.example.com:8080/live/u/p/123.ts"))
        assertEquals("https://tv.example.com/live/u/p/123.m3u8?x=1", LiveFormat.toHls("https://tv.example.com/live/u/p/123.ts?x=1"))
    }

    @Test fun everythingElseUnchanged() {
        for (u in listOf("http://tv.example.com/timeshift/u/p/60/2026-11-14:10-00/123.ts", "http://tv.example.com/live/u/p/123.m3u8",
            "http://cdn.example.com/playlist/ch1.ts", "http://tv.example.com/movie/u/p/7.mkv")) {
            assertEquals(u, LiveFormat.toHls(u))
            assertFalse(LiveFormat.canHls(u))
        }
        assertTrue(LiveFormat.canHls("http://tv.example.com/live/u/p/123.ts"))
    }

    private val ts = "http://tv.example.com/live/u/p/123.ts"

    @Test fun channelChoiceWinsOverTheGlobalSetting() {
        assertEquals(ts, LiveFormat.playUrl(ts, null, false))                         // default: TS
        assertEquals(LiveFormat.toHls(ts), LiveFormat.playUrl(ts, null, true))        // global HLS
        assertEquals(LiveFormat.toHls(ts), LiveFormat.playUrl(ts, ChannelFormats.HLS, false))
        assertEquals(ts, LiveFormat.playUrl(ts, ChannelFormats.TS, true))
        assertEquals(ts, LiveFormat.playUrl(ts, "nonsense", false))
    }

    @Test fun channelFormatsSetClearAndRoundTrip() {
        val f = ChannelFormats().with("p0|1", ChannelFormats.HLS, 10).with("p0|2", ChannelFormats.TS, 20)
        assertEquals(ChannelFormats.HLS, f["p0|1"])
        assertEquals(20L, f.at)
        val back = ChannelFormats.fromJson(f.toJson())
        assertEquals(f, back)
        val cleared = back.with("p0|1", null, 30)
        assertNull(cleared["p0|1"])
        assertEquals(setOf("p0|2"), cleared.channels.keys)
    }

    @Test fun channelFormatsIgnoreJunkAndStayBounded() {
        val junk = Json.parseToJsonElement("""{"at": 5, "channels": {"a": "hls", "b": "mp4", "": "ts", "c": 3}}""")
        assertEquals(mapOf("a" to "hls"), ChannelFormats.fromJson(junk).channels)
        assertEquals(ChannelFormats(), ChannelFormats.fromJson(null))
        var f = ChannelFormats()
        for (i in 0..ChannelFormats.MAX + 5) f = f.with("k$i", ChannelFormats.HLS, i.toLong())
        assertEquals(ChannelFormats.MAX, f.channels.size)
        assertNull(f["k0"])                                                           // the oldest choice went
        assertTrue(f.toJson().toString().length < 16 * 1024)
    }

    @Test fun newerCopyWins() {
        val local = ChannelFormats(100, mapOf("a" to "hls"))
        val server = ChannelFormats(200, mapOf("b" to "ts"))
        assertEquals(ChannelFormats.Merge(server, false), ChannelFormats.merge(local, server))
        assertEquals(ChannelFormats.Merge(local, true), ChannelFormats.merge(local, ChannelFormats(50)))
        assertEquals(ChannelFormats.Merge(local, true), ChannelFormats.merge(local, null))
        assertEquals(ChannelFormats.Merge(local, false), ChannelFormats.merge(local, local))
        assertFalse(ChannelFormats.merge(ChannelFormats(), null).toServer)          // nothing chosen yet: nothing to send
    }

    @Test fun stallHintOnlyForRepeatedEarlyStalls() {
        var t = 0L
        val s = PlaybackStats { t }
        s.begin(1, "live", "Ch")
        t = 2000; s.firstFrame()
        repeat(2) { t += 5000; s.buffering(true); t += 3000; s.buffering(false) }
        assertFalse(StallHint.suggest(s.snapshot(), t))                               // two stalls: not yet
        t += 5000; s.buffering(true); t += 3000; s.buffering(false)
        assertTrue(StallHint.suggest(s.snapshot(), t))                                // third within two minutes
        assertFalse(StallHint.suggest(s.snapshot(), StallHint.WINDOW_MS + 1))       // later on: never
        assertFalse(StallHint.suggest(null, t))
        val v = PlaybackStats { t }
        v.begin(2, "vod", "Film"); v.firstFrame()
        repeat(3) { v.buffering(true); v.buffering(false) }
        assertFalse(StallHint.suggest(v.snapshot(), t))                               // films: not a live format question
    }
}
