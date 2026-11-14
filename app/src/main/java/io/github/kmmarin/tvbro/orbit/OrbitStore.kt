// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.orbit

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.github.kmmarin.tvbro.data.Diag

/**
 * The home-server link (server address, session, CSRF token, names), encrypted with the Android Keystore in its
 * own file, apart from tvBro's playlists. If the encrypted file cannot be read, the TV is simply not linked.
 */
class OrbitStore(private val ctx: Context) {
    private val prefs: android.content.SharedPreferences? by lazy {
        try {
            EncryptedSharedPreferences.create(
                ctx, "secure_orbit",
                MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) { Diag.log("Orbit: encrypted storage unavailable (${e.javaClass.simpleName})"); null }
    }

    fun load(): OrbitLink? = try {
        val p = prefs ?: return null
        val server = p.getString("server", null) ?: return null
        val session = p.getString("session", null) ?: return null
        OrbitLink(server, session, p.getString("csrf", "") ?: "", p.getString("account", "") ?: "",
            p.getString("username", "") ?: "", p.getString("profile", "") ?: "", p.getBoolean("kids", false))
    } catch (e: Exception) { null }

    fun save(l: OrbitLink) {
        try {
            prefs?.edit {
                putString("server", l.server); putString("session", l.session); putString("csrf", l.csrf)
                putString("account", l.account); putString("username", l.username); putString("profile", l.profile)
                putBoolean("kids", l.kids); putString("lastServer", l.server)
            }
        } catch (e: Exception) { Diag.log("Orbit: encrypted write failed (${e.javaClass.simpleName})") }
    }

    /** Forget the session, the sync base and this TV's stream id (the last server address stays, to link again quickly). */
    fun clear() {
        try { prefs?.edit { listOf("server", "session", "csrf", "account", "username", "profile", "kids", "listBase", "streamSid").forEach { remove(it) } } } catch (e: Exception) { }
        sid = null
    }

    /**
     * An unlink has started and not finished (the app was closed half-way): finished at the next start. Plain
     * preferences, not the encrypted file, so it still works when the Keystore key is gone.
     */
    var wipePending: Boolean
        get() = plain.getBoolean("wipePending", false)
        set(v) { plain.edit(commit = true) { putBoolean("wipePending", v) } }
    private val plain by lazy { ctx.getSharedPreferences("orbit", Context.MODE_PRIVATE) }

    /** My list as it was after the last sync (the base of the three-way merge). */
    var listBase: Set<String>
        get() = try { prefs?.getStringSet("listBase", emptySet())?.toSet() ?: emptySet() } catch (e: Exception) { emptySet() }
        set(v) { try { prefs?.edit { putStringSet("listBase", v) } } catch (e: Exception) { } }

    /** Ids of the playlists that came from the home server (removed again when the TV is unlinked). */
    var serverSources: Set<String>
        get() = try { prefs?.getStringSet("serverSources", emptySet())?.toSet() ?: emptySet() } catch (e: Exception) { emptySet() }
        set(v) { try { prefs?.edit { putStringSet("serverSources", v) } } catch (e: Exception) { } }

    /** This TV's stream id for the shared stream count (made once per link, so zapping never takes a second slot). */
    @Volatile private var sid: String? = null
    val streamSid: String
        get() = sid ?: synchronized(this) {
            sid ?: try {
                prefs?.getString("streamSid", null) ?: ("tv" + java.util.UUID.randomUUID().toString().replace("-", "")).also { v ->
                    prefs?.edit { putString("streamSid", v) }
                }
            } catch (e: Exception) { "tv" + java.util.UUID.randomUUID().toString().replace("-", "") }
        }.also { sid = it }

    var lastServer: String
        get() = try { prefs?.getString("lastServer", "") ?: "" } catch (e: Exception) { "" }
        set(v) { try { prefs?.edit { putString("lastServer", v) } } catch (e: Exception) { } }
}
