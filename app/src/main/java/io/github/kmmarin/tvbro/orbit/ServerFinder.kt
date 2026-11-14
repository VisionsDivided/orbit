// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.orbit

import io.github.kmmarin.tvbro.data.ServerScan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/**
 * "Find my server": asks `GET /v1/hello` on port 8750 of every address of the TV's own /24 network, many at once with
 * short timeouts (about 3 s in all), and returns the Orbit servers that answered. TCP only: the server sits behind a
 * Docker TCP port mapping, so a UDP broadcast would not reach it. No cookie or login is ever sent.
 */
object ServerFinder {
    data class Found(val host: String, val hostOk: Boolean)
    /** [network]: "192.168.1.x" (null when the TV has no private IPv4 address); [servers] in address order. */
    data class Result(val network: String?, val servers: List<Found>)

    private const val PARALLEL = 64

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(500, TimeUnit.MILLISECONDS).readTimeout(1000, TimeUnit.MILLISECONDS)
        .callTimeout(1500, TimeUnit.MILLISECONDS).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()

    /** The TV's private IPv4 addresses (Wi-Fi or Ethernet). */
    private fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { i -> i.inetAddresses.toList().filterIsInstance<Inet4Address>().map { it.hostAddress.orEmpty() } }
    }.getOrDefault(emptyList())

    private fun probe(host: String): Found? = try {
        val req = Request.Builder().url("http://$host:${ServerScan.PORT}${ServerScan.PATH}").header("User-Agent", "Orbit-TV").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) null
            else ServerScan.parseHello(r.body?.string()?.take(512).orEmpty())?.let { Found(host, it) }
        }
    } catch (e: Exception) {
        null
    }

    suspend fun scan(): Result = withContext(Dispatchers.IO) {
        val own = ServerScan.pick(localAddresses()) ?: return@withContext Result(null, emptyList())
        val hosts = ServerScan.subnetHosts(own)
        val gate = Semaphore(PARALLEL)
        val found = java.util.concurrent.ConcurrentLinkedQueue<Found>()
        // every probe ends within its 1.5 s call timeout; the overall cap keeps what was found so far
        withTimeoutOrNull(6000) {
            coroutineScope { hosts.map { h -> async { gate.withPermit { probe(h)?.let(found::add) } } }.awaitAll() }
        }
        Result(ServerScan.subnetLabel(own), found.toList().sortedBy { it.host.substringAfterLast('.').toIntOrNull() ?: 0 })
    }
}
