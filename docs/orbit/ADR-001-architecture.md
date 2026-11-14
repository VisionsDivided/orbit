# ADR-001 — Orbit architecture on top of tvBro

**Status:** Proposed (needs owner sign-off) · **Date:** 2026-11-13 · **Base:** tvBro `e5d1897`

## Context
The owner's decisions (handoff v4 §8) are fixed: a private independent copy of tvBro with an `upstream` remote; the home server is mandatory; multiple accounts and profiles from the first functional release; Plex and Jellyfin connectors built in parallel; Android TV and web first. The Phase 0 audit (the Phase 0 audit (kept outside the repo)) found that the web server has no user model and the Android app has no server client.

## Decisions

### D1. Repository
A private copy of tvBro at `e5d1897`; `origin` = private Orbit repo, `upstream` = `kmmarin/tvBro`. Orbit work goes on an `orbit/main` integration branch. Upstream-bound fixes go on branches cut from `upstream/dev`.
*Rejected:* a public GitHub fork (owner decision); a rewrite from scratch (throws away tested IPTV code).

### D2. Home server = tvBro's Python server, extended in new packages
- New package `web/server/orbit/` with its own small router: a dict of `(method, path-pattern) → handler` under `/v1/`. It sits beside the existing `if/elif` routes, behind the same Host, Origin and CSRF gates.
- New database `orbit.db` (SQLite, WAL) with **numbered migrations** (`PRAGMA user_version` plus a `migrations/NNN_*.sql` folder) and a test that upgrades from every older version.
- tvBro's own stores keep working unchanged for IPTV catalogue, guide and search, which are household data. **Per-person** data moves to profile-scoped tables: My list, resume, favourites, series progress, reminders, prefs.
- *Rejected:* a new TypeScript/Postgres backend (handoff v4); rewriting `app.py` in place (high merge cost with upstream).

### D3. Identity model
`Account` (login, role: owner/admin/member) → `Profile` (one or more; optional kids flag) → `Session` (`account_id`, `profile_id`, `device_id`, expiry, revocable). Also `Device`, `SourceConnection`, `SourceGrant` (which accounts can use which IPTV/Plex/Jellyfin source), `Progress`, `Favorite`, `PlaybackLease`.
- Passwords: reuse tvBro's PBKDF2 and throttling code.
- **Every `/v1` handler gets an `ctx(account, profile)` object and every query filters by it.** Access to other accounts' data is denied by default, and there's a test helper that runs each endpoint as two accounts.
- **TV sign-in:** the TV shows a short code and QR; a signed-in user approves it on web or phone; the TV receives a device session. This builds on tvBro's existing phone-to-TV QR flow.
- The household's existing single password becomes the **owner account** at migration.

### D4. Android client
- A new `orbit` package in the Android app: an OkHttp client for `/v1`, the pairing screen, a sync engine (pull on start and resume, push changes with versions), and a lease client. **New code goes outside `AppModel.kt`**, behind small interfaces that AppModel calls.
- **Playback stays native (Media3) and direct from the provider by default**, so tvBro's tuning behaviour is unchanged. The server hands out provider credentials only to a paired device session, and the device stores them encrypted as today. Whether to relay through the server instead is decided in Phase 2 with measurements (open question Q2).
- An application ID for Orbit, separate signing key and update feed: chosen in Phase 1 after the name check.

### D5. Stream limits
A server-side **lease ledger** per IPTV provider: `acquire(provider, device, kind=live|preview|vod|record)` → lease with TTL; heartbeat every 15–30 s; release on stop; expired leases reclaimed. The default cap comes from the provider's `max_connections`, and the setting is no longer capped at 3. When full, the client gets "all N streams in use: <device/profile, what>" with an option to stop one. Plex and Jellyfin playback never counts. Fix the no-`sid` bypass on the relay paths.

### D6. Media-server connectors
- Python package `orbit/connectors/` with one contract: `connect, list_libraries, fetch_changes, search, get_title, get_seasons_episodes, get_playback_options, report_progress, mark_watched, get_artwork, health`.
- `plex.py` (PIN linking) and `jellyfin.py` (Quick Connect / user auth) are built in parallel against a shared contract test suite with recorded, synthetic fixtures.
- Calls to the REST APIs use the stdlib HTTP stack via `net.open_url`. **No Jellyfin SDK** (avoids the LGPL question).
- `net.py` gets a **per-host allowlist** (exact host:port of the configured Plex/Jellyfin server) so the household doesn't need to switch on LAN access for everything.

### D7. Storage scale
SQLite stays until the Phase 2/8 benchmarks (30k series, thousands of channels) show otherwise. tvBro already uses FTS5 for search.

### D8. Upstream lane
Generic fixes go upstream only with the owner's and maintainer's OK: the Windows timestamp tie-break, the no-`sid` limit bypass, playback statistics, and an all-IPTV stream limit on Android. Orbit-only work: accounts, connectors, Seerr, cinematic UI.

## Open questions
- **Q1 Rust rewrite:** if tvBro moves to Rust, upstream merges into Orbit stop being practical, and Orbit becomes a true independent codebase. Re-check before Phase 1 starts. It doesn't change D2–D6, but it changes how much the upstream lane is worth.
- **Q2 Direct or relayed IPTV on TV:** decided by the Phase 2 same-channel benchmark.
- **Q3 Account-to-Plex/Jellyfin mapping:** per-Orbit-account linking (each person links their own Plex user) or admin-granted shared libraries. Proposal: per-account linking, plus admin grants for managed users.
- **Q4 Progress conflict rule:** handoff defaults to newest change wins; confirm.
