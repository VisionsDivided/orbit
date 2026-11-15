-- Copyright 2026 The Orbit authors
-- SPDX-License-Identifier: Apache-2.0
-- LAN media servers. A saved connection may reach a private (LAN) address only when an owner or admin linked it
-- while it resolved to one (lan_ok = 1), and then only the addresses it resolved to at link time (lan_ips, a
-- comma-separated list). A member's link stays public-only, so a public name that later re-resolves to a LAN
-- address (DNS rebinding) is refused. Rows linked before this migration by an owner or admin keep working: they
-- get lan_ok = 1 with no addresses yet, and the server pins what they resolve to the next time it starts.
ALTER TABLE media_connections ADD COLUMN lan_ok INTEGER NOT NULL DEFAULT 0;
ALTER TABLE media_connections ADD COLUMN lan_ips TEXT NOT NULL DEFAULT '';
UPDATE media_connections SET lan_ok = 1
    WHERE account_id IN (SELECT id FROM accounts WHERE role IN ('owner', 'admin'));
