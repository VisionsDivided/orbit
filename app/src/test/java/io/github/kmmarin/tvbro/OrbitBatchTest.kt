// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import io.github.kmmarin.tvbro.data.WatchState
import io.github.kmmarin.tvbro.orbit.EpisodePush
import io.github.kmmarin.tvbro.orbit.OrbitApi
import io.github.kmmarin.tvbro.orbit.OrbitBatch
import io.github.kmmarin.tvbro.orbit.OrbitLink
import io.github.kmmarin.tvbro.orbit.Outbox
import io.github.kmmarin.tvbro.orbit.SyncSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbitBatchTest {
    private val link = OrbitLink("http://192.0.2.10:8750", "sid", "csrf", "Alex", "alex", "Main", false)

    /** A home server in memory: answers by path, records every request. */
    private class Fake(val answer: (path: String, query: String?, body: String?) -> Pair<Int, String>) {
        val calls = ArrayList<Triple<String, String?, String?>>()
        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val r = chain.request()
            val body = r.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }
            calls += Triple(r.url.encodedPath, r.url.query, body)
            val (code, text) = answer(r.url.encodedPath, r.url.query, body)
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(text.toResponseBody("application/json".toMediaType())).build()
        }).build()
    }

    private val full = """{"rev":"r1","changed":true,
        "state":{"mylist":[{"key":"p0|1"},{"key":"m|p0|9"}],"resume":{"m|p0|9":{"secs":300,"dur":5400,"at":1000},"m|p0|8":{"secs":1}}},
        "progress":{"episodes":[{"seriesId":"7","epId":"101","secs":600,"dur":2400,"done":false,"at":2000}],
                    "shows":[{"id":"7","key":"s|p0|7","lastEp":"101","next":{"id":"102","season":1,"num":2,"title":"Two"},"finished":false,"hidden":false,"at":2000}]}}"""

    @Test fun fullAnswerBecomesASnapshot() {
        val s = OrbitBatch.fromSyncAnswer(Json.parseToJsonElement(full).jsonObject, null)!!
        assertEquals("r1", s.rev)
        assertEquals(setOf("p0|1", "m|p0|9"), s.mylist)
        assertEquals(setOf("m|p0|9"), s.resume.keys)          // no "at": skipped
        assertEquals(300L, s.resume["m|p0|9"]!!.secs)
        assertEquals("101", s.episodes.single().epId)
        assertEquals("102", s.shows.single().next!!.id)
    }

    @Test fun unchangedAnswerReusesTheCachedSnapshotOnlyForTheSameRev() {
        val cached = SyncSnapshot("r1", setOf("p0|1"), emptyMap(), emptyList(), emptyList())
        val same = Json.parseToJsonElement("""{"rev":"r1","changed":false}""").jsonObject
        assertSame(cached, OrbitBatch.fromSyncAnswer(same, cached))
        assertNull(OrbitBatch.fromSyncAnswer(same, null))
        assertNull(OrbitBatch.fromSyncAnswer(Json.parseToJsonElement("""{"rev":"r2","changed":false}""").jsonObject, cached))
        assertNull(OrbitBatch.fromSyncAnswer(Json.parseToJsonElement("""{"changed":true}""").jsonObject, cached))
    }

    @Test fun syncStateSendsSinceAndDownloadsOnlyWhenChanged() {
        var rev = "r1"
        val f = Fake { path, query, _ ->
            assertEquals("/v1/sync/state", path)
            if (query == "since=$rev") 200 to """{"rev":"$rev","changed":false}""" else 200 to full.replace("\"r1\"", "\"$rev\"")
        }
        val api = OrbitApi(f.client)
        val first = api.syncState(link, null)!!
        assertNull(f.calls.last().second)
        val again = api.syncState(link, first)!!
        assertSame("unchanged: the cached copy, nothing downloaded", first, again)
        assertEquals("since=r1", f.calls.last().second)
        rev = "r2"
        val changed = api.syncState(link, first)!!
        assertEquals("r2", changed.rev)
        assertEquals(3, f.calls.size)
    }

    @Test fun olderServerWithoutTheRoutes() {
        val f = Fake { _, _, _ -> 404 to """{"error":"not found"}""" }
        val api = OrbitApi(f.client)
        assertNull(api.syncState(link, null))
        val out = Outbox().apply { put("mylist", "p0|1", JsonPrimitive("x")) }
        assertFalse(api.batch(link, out))
        assertEquals(2, f.calls.size)
    }

    @Test fun batchSendsTheQueueInOneRequest() {
        val f = Fake { _, _, _ -> 200 to """{"state":3,"episodes":1,"shows":1}""" }
        val out = Outbox()
        out.put("mylist", "p0|1", JsonObject(mapOf("key" to JsonPrimitive("p0|1"))))
        out.del("mylist", "p0|2")
        out.put("resume", "m|p0|9", OrbitBatch.resumeValue(WatchState(600_000, 5_400_000, false, 1234), null))
        out.episodes += OrbitBatch.episodeItem(EpisodePush("e|p0|101", "s|p0|7", "7", "101", WatchState(0, 2_400_000, true, 99)), null)
        assertTrue(OrbitApi(f.client).batch(link, out))
        assertEquals(1, f.calls.size)
        val (path, _, body) = f.calls.single()
        assertEquals("/v1/sync/batch", path)
        val j = Json.parseToJsonElement(body!!).jsonObject
        val ops = j["state"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("put", "del", "put"), ops.map { it["op"]!!.jsonPrimitive.content })
        assertEquals(listOf("mylist", "mylist", "resume"), ops.map { it["scope"]!!.jsonPrimitive.content })
        assertEquals("600", ops[2]["value"]!!.jsonObject["secs"]!!.jsonPrimitive.content)
        val ep = j["episodes"]!!.jsonArray.single().jsonObject
        assertEquals(listOf("7", "101", "2400", "true"), listOf("seriesId", "epId", "secs", "done").map { ep[it]!!.jsonPrimitive.content })
    }

    @Test fun largeQueuesAreSplit() {
        val ops = (1..450).map { JsonObject(mapOf("k" to JsonPrimitive(it))) }
        val eps = (1..600).map { JsonObject(mapOf("e" to JsonPrimitive(it))) }
        val c = OrbitBatch.chunks(ops, eps)
        assertEquals(listOf(200, 200, 50), c.map { it.ops.size })
        assertEquals(listOf(500, 100, 0), c.map { it.episodes.size })
        assertTrue(OrbitBatch.chunks(emptyList(), emptyList()).isEmpty())
        val body = OrbitBatch.body(c[2])
        assertEquals(0, (body["episodes"] as JsonArray).size)
    }

    @Test fun resumeValueFormat() {
        val done = OrbitBatch.resumeValue(WatchState(0, 5_400_000, true, 77),
            JsonObject(mapOf("name" to JsonPrimitive("Film"), "poster" to JsonPrimitive("http://img.example.com/p.jpg"), "id" to JsonPrimitive("9"))))
        assertEquals("5400", done["secs"]!!.jsonPrimitive.content)
        assertEquals("true", done["done"]!!.jsonPrimitive.content)
        assertEquals("Film", done["meta"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        assertNull(OrbitBatch.resumeValue(WatchState(60_000, 5_400_000, false, 77), null)["done"])
    }
}
