// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.orbit

/**
 * What unlinking a TV removes (Settings ▸ Home server ▸ Unlink, or the server ending its session): everything the
 * account brought onto the TV, so the next account starts clean.
 *
 * Data from before the TV was first linked (tvBro mode) is removed too: at the first link it was merged into the
 * account (My list and resume points uploaded, the TV's own playlists replaced by the server's), so on the TV it can
 * no longer be told apart from the account's own data. It is not lost: it is in that account on the home server
 * and comes back when that account links the TV again. Device settings (player, captions, recordings folder) and
 * recordings / downloads made on this TV are not synced account data and stay.
 */
object OrbitUnlink {
    interface Steps {
        /** Kept across restarts: a wipe the app was closed during is finished at the next start. */
        fun setPending(on: Boolean)
        /** Session id, CSRF token, names, the My list sync base and this TV's stream id. */
        fun clearSession()
        /** Ids of the playlists that came from the server. */
        fun serverSources(): Set<String>
        /** The playlists (and their logins, channel lists, guide) by id. */
        fun removeSources(ids: Set<String>)
        /** Once they are removed: forget which playlists came from the server. */
        fun forgetServerSources()
        /** Film resume points, episode marks, show progress and next episodes. */
        fun clearWatchState()
        /** My list and favourites, recently watched and their snapshots, last channel, search history. */
        fun clearAccountData()
    }

    fun wipe(s: Steps) {
        s.setPending(true)
        s.clearSession()
        s.clearWatchState()
        s.clearAccountData()
        val ids = s.serverSources()
        if (ids.isNotEmpty()) s.removeSources(ids)
        s.forgetServerSources()
        s.setPending(false)                      // only once every step ran: a failure above is retried at the next start
    }
}
