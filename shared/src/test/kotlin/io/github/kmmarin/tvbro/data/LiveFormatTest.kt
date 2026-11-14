// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import org.junit.Assert.assertEquals
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
        }
    }
}
