// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.orbit

import io.github.kmmarin.tvbro.data.ChannelFormats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Per-channel stream formats of the TV's profile, kept as one value in the profile's server prefs
 * (`/api/state` "prefs", key [ChannelFormats.PREF_KEY]); the newer copy wins as a whole. [st] is the `/api/state`
 * answer the sync just fetched. Runs inside OrbitModel.syncState.
 */
internal suspend fun syncChannelFormats(api: OrbitApi, l: OrbitLink, h: OrbitHost, prefs: JsonObject?) {
    val server = prefs?.get(ChannelFormats.PREF_KEY)?.let { ChannelFormats.fromJson(it) }
    val local = withContext(Dispatchers.Main) { h.channelFormats }
    val m = ChannelFormats.merge(local, server)
    if (m.toServer) api.statePut(l, "prefs", ChannelFormats.PREF_KEY, m.result.toJson())
    else if (m.result != local) withContext(Dispatchers.Main) { h.setChannelFormats(m.result) }
}
