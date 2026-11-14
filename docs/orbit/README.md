# Orbit docs

Orbit is a private media app built on tvBro (see the note at the top of the main README).

| File | What |
|---|---|
| [ADR-001-architecture.md](ADR-001-architecture.md) | How Orbit extends tvBro: home server, accounts, Android client, stream leases, connectors |
| [API-v1.md](API-v1.md) | The `/v1` home-server API contract (accounts, profiles, TV linking, sync, media servers) |
| [phase1-plan.md](phase1-plan.md) | Phase 1 work, acceptance tests, and the Phase 2 buffering / stream-limit test designs |
| [risks.md](risks.md) | Risk register |

## Progress

| Phase 1 step | Status |
|---|---|
| 1. Repo setup, own app ID, Windows test fix | done |
| 2. `orbit.db` with numbered migrations | done |
| 3. Accounts, profiles, sessions (`/v1`) | done (server; no UI yet) |
| 4. Per-profile state (My list, resume, favourites, series progress) | done (server) |
| 5. Web sign-in and profile picker | done |
| 6. TV pairing | done (server, web, Android) |
| 7. Android sync (playlists, My list, film resume, show progress) | done |
| 8. Docker Compose deploy | next |
| Phase 3: Plex + Jellyfin connectors (server) | done (server and `/v1/media` API; no UI yet; tested against mock servers only) |

Web app: Settings ▸ Accounts sets up accounts (owner, with the one-time code from the server's data folder),
adds profiles and accounts, lists your devices; the side menu shows the current profile ("Who's watching?").
