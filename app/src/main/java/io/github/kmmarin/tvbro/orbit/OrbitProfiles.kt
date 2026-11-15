// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.orbit

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

// OrbitProfile (a profile of the linked account) is in shared/: orbit/OrbitApi.kt

/** "Who's watching?" look: the orbit ring or one classic row (Settings ▸ Home server). */
enum class PickerStyle(val label: String) { ORBIT("Orbit"), CLASSIC("Classic") }

/**
 * Profiles of the linked account and the "Who's watching?" picker: shown at start (when asked for and the account
 * has two or more profiles) and from Settings. Choosing a profile switches this TV to it ([OrbitModel.switchProfile]).
 *
 * The picker style and "ask at start" are settings of this TV (plain preferences; no names are stored, only how many
 * profiles there were, to decide at start without waiting for the server).
 */
class OrbitProfiles(app: Application, private val scope: CoroutineScope, private val orbit: OrbitModel, private val api: OrbitApi = OrbitApi()) {
    private val prefs = app.getSharedPreferences("orbit_ui", Context.MODE_PRIVATE)

    var list by mutableStateOf<List<OrbitProfile>>(emptyList()); private set
    var currentId by mutableStateOf<String?>(null); private set
    /** The picker is on screen. */
    var showing by mutableStateOf(false); private set
    /** Shown at start: the app waits behind it (nothing plays until a profile is chosen). */
    var atStart by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set

    var style by mutableStateOf(PickerStyle.values().firstOrNull { it.name == prefs.getString("pickerStyle", null) } ?: PickerStyle.ORBIT); private set
    var askOnStart by mutableStateOf(prefs.getBoolean("askOnStart", true)); private set

    fun changeStyle(s: PickerStyle) { style = s; prefs.edit().putString("pickerStyle", s.name).apply() }
    fun changeAskOnStart(b: Boolean) { askOnStart = b; prefs.edit().putBoolean("askOnStart", b).apply() }

    init {
        // at start: shown straight away when the account had two or more profiles last time (no waiting for the
        // server); closed again if the TV turns out not to be linked or the server says otherwise
        if (askOnStart && prefs.getInt("lastCount", 0) >= 2) { showing = true; atStart = true }
        scope.launch {
            snapshotFlow { orbit.loaded }.filter { it }.first()
            if (orbit.link == null) { if (atStart) close(); return@launch }
            refresh()
            if (atStart && list.size < 2) close()    // one profile, or the server is not reachable: nothing to ask
        }
        // unlinked while open: nothing to choose from
        scope.launch { snapshotFlow { orbit.link }.collect { if (it == null && orbit.loaded) { list = emptyList(); currentId = null; if (showing) close() } } }
    }

    /** Reloads the profiles from the server (GET /v1/me). */
    suspend fun refresh() {
        val l = orbit.link ?: return
        try {
            val me = withContext(Dispatchers.IO) { api.getJson(l, "/v1/me") }
            list = parseProfiles(me)
            currentId = ((me["profile"] as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
            prefs.edit().putInt("lastCount", list.size).apply()
            message = ""
        } catch (e: Exception) {
            message = "The home server cannot be reached (${e.javaClass.simpleName})"
        }
    }

    /** Opens the picker (from Settings). */
    fun open() {
        if (orbit.link == null) return
        message = ""; showing = true; atStart = false
        scope.launch { refresh() }
    }

    fun close() { showing = false; atStart = false }

    /** Continue as [p]: switches the TV to it when it is another profile, then closes the picker. */
    fun choose(p: OrbitProfile) {
        if (busy) return
        if (p.id == currentId) { close(); return }
        busy = true; message = "Switching to ${p.name}…"
        scope.launch {
            val err = try { orbit.switchProfile(p.id) } finally { busy = false }
            if (err != null) { message = err; return@launch }
            currentId = p.id; message = ""
            close()
        }
    }

    /** New profile (at most 8 per account, the server checks). */
    fun add(name: String, kids: Boolean = false) {
        val l = orbit.link ?: return
        if (name.isBlank()) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) { api.postJson(l, "/v1/profiles", JsonObject(mapOf("name" to JsonPrimitive(name.trim()), "kids" to JsonPrimitive(kids)))) }
                refresh()
            } catch (e: OrbitError) { message = e.message ?: "Could not add the profile" } catch (e: Exception) { message = "Could not add the profile (${e.javaClass.simpleName})" }
        }
    }

    /** Deletes [p] and its data on the server (not the profile this TV uses, not the last one). */
    fun delete(p: OrbitProfile) {
        val l = orbit.link ?: return
        if (p.id == currentId) { message = "Switch to another profile before deleting this one"; return }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { api.postJson(l, "/v1/profiles/delete", JsonObject(mapOf("id" to JsonPrimitive(p.id)))) }
                refresh()
            } catch (e: OrbitError) { message = e.message ?: "Could not delete the profile" } catch (e: Exception) { message = "Could not delete the profile (${e.javaClass.simpleName})" }
        }
    }

    companion object {
        fun parseProfiles(me: JsonObject): List<OrbitProfile> = (me["profiles"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            OrbitProfile(id, (o["name"] as? JsonPrimitive)?.contentOrNull ?: "", (o["kids"] as? JsonPrimitive)?.booleanOrNull ?: false)
        }
    }
}
