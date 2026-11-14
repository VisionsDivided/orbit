-- Copyright 2026 The Orbit authors
-- SPDX-License-Identifier: Apache-2.0
-- What kind of device a session is: 'web' (browser sign-in) or 'tv' (linked with a pairing code).
-- Only 'tv' sessions may fetch the household's playlists (provider logins) for direct playback.
ALTER TABLE account_sessions ADD COLUMN kind TEXT NOT NULL DEFAULT 'web';
