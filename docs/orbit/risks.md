# Orbit — risk register (Phase 0)

| # | Risk | Likelihood | Impact | Mitigation | Owner/phase |
|---|---|---|---|---|---|
| R1 | Upstream tvBro is rewritten in Rust, ending easy merges | Medium (reported, not public) | Medium | Pin `e5d1897`; Orbit code in new modules; re-check before Phase 1; treat upstream lane as optional | Owner / P1 |
| R2 | Multi-user retrofit leaks one person's data to another | Medium | High | `/v1` only, `ctx(account, profile)` on every query, default-deny, two-account test helper on every endpoint | P1 |
| R3 | Android has no server client; sync/pairing is bigger than it looks | High | Medium | Build pairing + sync + leases first as a thin vertical slice; keep `AppModel.kt` edits small | P1 |
| R4 | Provider credentials on the TV device (direct playback) | Certain today | Medium | Paired-device sessions only, encrypted at rest, revocable; measure relay option in P2 | P1–P2 |
| R5 | Stream limit gaps (no-`sid` bypass, `/rec/` uncounted, cap ≤3, not per provider, TV not counted by server) | Verified | High (provider bans/“max connections”) | Server lease ledger (ADR D5) + tests for preview/VOD/record/two devices | P2 |
| R6 | Buffering blamed on the wrong layer | High | High | Instrument first (TTFF, stalls, longest stall, bitrate, reconnects); same-channel, same-time comparisons; no blind buffer tuning | P2 |
| R7 | LAN access for Plex/Jellyfin weakens SSRF protection | Medium | High | Per-host allowlist in `net.py`, not the global `allowLanSources` switch | P3 |
| R8 | Schema changes without a migration framework corrupt existing installs | Medium | High | Numbered migrations + upgrade tests + pre-migration backup | P1 |
| R9 | 30k-series catalogue slows Home/search on the BRAVIA | Medium | Medium | Server-side paging, FTS5, lazy rows; fixture benchmark before full ingest | P2/P8 |
| R10 | Windows-only test flake hides real failures on the dev PC | Verified | Low | Fix tie-break (upstream PR candidate) | P1 |
| R11 | Licence mix-ups (Jellyfin SDK LGPL, AGPL Dispatcharr, GPL clients) | Low | High | REST via stdlib/OkHttp only; no copied client code; THIRD_PARTY.md review | All |
| R12 | Breaking the owner's TV setup (launcher, Plex, existing tvBro) | Low | High | Separate application ID, install via ADB only with approval, never disable packages | P1 |
| R13 | Single big `app.py` / `AppModel.kt` become merge-conflict hotspots | High | Medium | New packages, thin hooks into existing files | All |
| R14 | Claude desktop's AppData virtualization confuses local tooling | Verified (this PC) | Low | SDK at `C:\Android\Sdk`; per-user installs via the terminal panel | Dev env |
