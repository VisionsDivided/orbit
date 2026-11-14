// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * "Find my server" on the TV's link screen: which addresses to try (the TV's own /24 network) and how to recognise an
 * Orbit home server's `GET /v1/hello` answer. A TCP scan, not a UDP broadcast: the server runs in Docker behind a
 * TCP port mapping, which broadcasts do not reach. The scan itself is in the app (orbit/ServerFinder.kt).
 */
object ServerScan {
    const val PORT = 8750
    const val PATH = "/v1/hello"

    private fun octets(ip: String): List<Int>? {
        val p = ip.trim().split('.')
        if (p.size != 4) return null
        val n = p.map { s -> s.toIntOrNull()?.takeIf { s.length in 1..3 && it in 0..255 } ?: return null }
        return n
    }

    /** A private IPv4 address of a home network (10/8, 172.16/12, 192.168/16): the only networks worth scanning. */
    fun isPrivate(ip: String): Boolean {
        val o = octets(ip) ?: return false
        return o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168)
    }

    /** The TV's address to scan around: the first private IPv4 one of [candidates], else null. */
    fun pick(candidates: List<String>): String? = candidates.firstOrNull { isPrivate(it) }

    /** "a.b.c.1" … "a.b.c.254" without [own] itself; empty for anything but a private IPv4 address. */
    fun subnetHosts(own: String): List<String> {
        val o = octets(own)?.takeIf { isPrivate(own) } ?: return emptyList()
        val pre = "${o[0]}.${o[1]}.${o[2]}."
        return (1..254).filter { it != o[3] }.map { pre + it }
    }

    /** "a.b.c.x" for messages ("Searching 192.0.2.x …"). */
    fun subnetLabel(own: String): String = own.substringBeforeLast('.') + ".x"

    /** The answer of `GET /v1/hello`: null unless it is an Orbit server; else whether the address is allowed (hostOk). */
    fun parseHello(body: String): Boolean? {
        val o = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        if ((o["app"] as? JsonPrimitive)?.contentOrNull != "orbit") return null
        return (o["hostOk"] as? JsonPrimitive)?.booleanOrNull ?: true
    }

    /** What the address field gets for a found server. */
    fun fieldValue(host: String): String = "http://$host:$PORT"
}
