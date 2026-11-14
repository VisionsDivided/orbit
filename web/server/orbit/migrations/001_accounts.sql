-- Copyright 2026 The Orbit authors
-- SPDX-License-Identifier: Apache-2.0
-- Accounts, profiles and the link from a server session to an account / profile.

CREATE TABLE accounts (
    id           TEXT PRIMARY KEY,
    username     TEXT NOT NULL,
    username_key TEXT NOT NULL UNIQUE,          -- lower-case username, for case-insensitive lookups
    display_name TEXT NOT NULL,
    role         TEXT NOT NULL CHECK (role IN ('owner', 'admin', 'member')),
    pw_hash      TEXT NOT NULL,
    disabled     INTEGER NOT NULL DEFAULT 0,
    created      INTEGER NOT NULL
);

CREATE TABLE profiles (
    id         TEXT PRIMARY KEY,
    account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    name       TEXT NOT NULL,
    kids       INTEGER NOT NULL DEFAULT 0,
    created    INTEGER NOT NULL
);
CREATE INDEX profiles_account ON profiles(account_id);

-- sess_hash: the SHA-256 the server already keys its sessions by (auth.py); the id shown to clients is separate.
CREATE TABLE account_sessions (
    sess_hash  TEXT PRIMARY KEY,
    id         TEXT NOT NULL UNIQUE,
    account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    profile_id TEXT REFERENCES profiles(id) ON DELETE SET NULL,
    device     TEXT NOT NULL DEFAULT '',
    created    INTEGER NOT NULL,
    last_seen  INTEGER NOT NULL
);
CREATE INDEX account_sessions_account ON account_sessions(account_id);
