-- Copyright 2026 The Orbit authors
-- SPDX-License-Identifier: Apache-2.0
-- Plex / Jellyfin connections. Each account links its own media-server user, so permissions and watch
-- state stay that person's. The access token is not in this table: it is sealed with the server key in
-- media.enc (orbit/media.py TokenVault), so a key rotation re-encrypts it with the other sealed files.
CREATE TABLE media_connections (
    id          TEXT PRIMARY KEY,
    account_id  TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    kind        TEXT NOT NULL CHECK (kind IN ('plex', 'jellyfin')),
    base_url    TEXT NOT NULL,                  -- scheme://host:port[/path], never with a token
    server_name TEXT NOT NULL DEFAULT '',
    server_id   TEXT NOT NULL DEFAULT '',       -- Jellyfin server Id / Plex machine identifier
    user_id     TEXT NOT NULL DEFAULT '',       -- the remote user id
    user_name   TEXT NOT NULL DEFAULT '',
    device_id   TEXT NOT NULL,                  -- this link's client id (Jellyfin DeviceId / X-Plex-Client-Identifier)
    created     INTEGER NOT NULL,
    last_ok     INTEGER,
    UNIQUE (account_id, kind, server_id, user_id)
);
CREATE INDEX media_connections_account ON media_connections(account_id);
