// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import io.github.kmmarin.tvbro.orbit.CatalogApi
import io.github.kmmarin.tvbro.orbit.OrbitApi
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** A made-up Orbit home server for tests: answers the catalog routes from [routes] (path prefix → JSON), else 404. */
class FakeOrbit(val routes: Map<String, String>) {
    val asked = ArrayList<String>()

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val u = chain.request().url
        val path = u.encodedPath + (u.encodedQuery?.let { "?$it" } ?: "")
        synchronized(asked) { asked += path }
        val body = routes.entries.firstOrNull { path.startsWith(it.key) }?.value
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(if (body != null) 200 else 404).message(if (body != null) "OK" else "Not Found")
            .body((body ?: """{"error": "not found"}""").toResponseBody("application/json".toMediaType())).build()
    }).build()

    val catalog = CatalogApi(OrbitApi(client, "Orbit-Desktop-Test"))

    companion object {
        fun item(key: String, title: String, kind: String = "movie", source: String = "iptv", poster: String? = null, backdrop: String? = null,
                 year: Int? = 2024, rating: Double? = 7.9, genres: List<String> = listOf("Drama"), colors: List<String>? = null, extra: String = ""): String =
            """{"key": "$key", "kind": "$kind", "title": "$title", "year": ${year ?: "null"}, "rating": ${rating ?: "null"}, "source": "$source",""" +
                """ "poster": ${poster?.let { "\"$it\"" } ?: "null"}, "backdrop": ${backdrop?.let { "\"$it\"" } ?: "null"},""" +
                """ "genres": [${genres.joinToString { "\"$it\"" }}], "colors": ${colors?.joinToString(prefix = "[", postfix = "]") { "\"$it\"" } ?: "null"}$extra}"""
    }
}
