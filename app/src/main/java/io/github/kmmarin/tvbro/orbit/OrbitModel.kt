// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.orbit

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.kmmarin.tvbro.data.Diag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A pairing in progress: the code shown on screen while the TV waits for approval. */
data class Pairing(val server: String, val code: String, val expires: Long)

/**
 * Orbit state for the TV app: the home-server link and the pairing screen. Kept out of AppModel; AppModel only
 * holds one instance. Network calls run on IO, state changes on the main thread.
 */
class OrbitModel(app: Application, private val scope: CoroutineScope, private val api: OrbitApi = OrbitApi()) {
    private val store = OrbitStore(app)

    var link by mutableStateOf<OrbitLink?>(null); private set
    var pairing by mutableStateOf<Pairing?>(null); private set
    var message by mutableStateOf(""); private set
    /** The server ended this TV's session (signed out from another device, or the account was disabled). */
    var signedOut by mutableStateOf(false); private set
    /** Set when a pairing has just finished, so the pairing screen can close itself. */
    var justLinked by mutableStateOf(false)
    val lastServer: String get() = store.lastServer

    private var job: Job? = null
    private val device = "TV (" + (android.os.Build.MODEL ?: "Android").take(40) + ")"

    init {
        scope.launch(Dispatchers.IO) {
            val l = store.load()
            withContext(Dispatchers.Main) { link = l }
            if (l != null) check(l)
        }
    }

    /** Names up to date; a session the server no longer knows means this TV was signed out. */
    private suspend fun check(l: OrbitLink) {
        try {
            val now = api.me(l)
            if (now == null) {
                store.clear()
                withContext(Dispatchers.Main) { link = null; signedOut = true }
            } else if (now != l) {
                store.save(now)
                withContext(Dispatchers.Main) { link = now }
            }
        } catch (e: Exception) {
            Diag.log("Orbit: home server not reachable (${e.javaClass.simpleName})")    // offline: keep the link
        }
    }

    fun startPairing(input: String) {
        val server = OrbitApi.normalizeServer(input)
        if (server == null) { message = "Enter the home server's address, e.g. http://192.0.2.10:8750"; return }
        cancelPairing()
        message = "Asking the home server for a code…"
        store.lastServer = server
        job = scope.launch(Dispatchers.IO) {
            val p = try { api.start(server, device) } catch (e: Exception) {
                withContext(Dispatchers.Main) { message = describe(e) }
                return@launch
            }
            withContext(Dispatchers.Main) { pairing = Pairing(server, p.code, p.expires); message = "" }
            while (isActive) {
                delay(p.interval.coerceIn(2, 10) * 1000L)
                val r = try { api.poll(server, p.pairId) } catch (e: Exception) {
                    withContext(Dispatchers.Main) { message = describe(e) + " (still trying)" }
                    continue
                }
                when (r) {
                    PairPoll.Pending -> if (message.isNotEmpty()) withContext(Dispatchers.Main) { message = "" }
                    is PairPoll.Approved -> {
                        store.save(r.link)
                        withContext(Dispatchers.Main) {
                            link = r.link; signedOut = false; pairing = null; justLinked = true
                            message = "Linked: ${r.link.account}, profile ${r.link.profile}"
                        }
                        return@launch
                    }
                    is PairPoll.Gone -> {
                        withContext(Dispatchers.Main) { pairing = null; message = "The code expired. Get a new one." }
                        return@launch
                    }
                }
            }
        }
    }

    fun cancelPairing() {
        job?.cancel(); job = null
        pairing = null
    }

    fun unlink() {
        val l = link ?: return
        cancelPairing()
        link = null; signedOut = false; message = "This TV is unlinked"
        store.clear()
        scope.launch(Dispatchers.IO) { api.logout(l) }
    }

    fun clearMessage() { message = "" }

    private fun describe(e: Exception): String = when (e) {
        is OrbitError -> when (e.code) {
            409 -> "The home server has no accounts yet: set them up in Orbit on a computer first (Settings ▸ Accounts)."
            421 -> "The home server does not accept this address. Add it to TVBRO_ALLOWED_HOSTS on the server."
            429 -> "Too many codes at once. Try again in a minute."
            else -> e.message ?: "The home server refused (${e.code})"
        }
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException ->
            "The home server cannot be reached. Check the address and that the TV is on the same network."
        else -> "Could not reach the home server (${e.javaClass.simpleName})"
    }
}
