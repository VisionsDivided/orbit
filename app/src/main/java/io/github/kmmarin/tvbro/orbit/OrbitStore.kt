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

    /** Forget the session (the last server address stays, to link again quickly). */
    fun clear() {
        try { prefs?.edit { listOf("server", "session", "csrf", "account", "username", "profile", "kids").forEach { remove(it) } } } catch (e: Exception) { }
    }

    var lastServer: String
        get() = try { prefs?.getString("lastServer", "") ?: "" } catch (e: Exception) { "" }
        set(v) { try { prefs?.edit { putString("lastServer", v) } } catch (e: Exception) { } }
}
