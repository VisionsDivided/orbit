// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.orbit

import io.github.kmmarin.tvbro.data.GroupPins
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Live TV's pinned groups of the TV's profile, one value in the profile's server prefs (key [GroupPins.PREF_KEY],
 * shared with the web app). Pins changed on this TV since the last sync win and are sent; otherwise the server's copy
 * is taken. Runs inside OrbitModel.syncState, like [syncChannelFormats].
 */
internal suspend fun syncGroupPins(api: OrbitApi, l: OrbitLink, h: OrbitHost, prefs: JsonObject?) {
    val server = GroupPins.fromJson(prefs?.get(GroupPins.PREF_KEY))
    val (local, dirty) = withContext(Dispatchers.Main) { h.groupPins to h.groupPinsDirty }
    val m = GroupPins.merge(local, dirty, server)
    if (m.toServer) api.statePut(l, "prefs", GroupPins.PREF_KEY, GroupPins.toJson(m.result))
    if (m.toServer || dirty || m.result != local) withContext(Dispatchers.Main) { h.setGroupPinsSynced(m.result) }
}
