# Orbit docs

Orbit is a private media app built on tvBro (see the note at the top of the main README).

| File | What |
|---|---|
| [ADR-001-architecture.md](ADR-001-architecture.md) | How Orbit extends tvBro: home server, accounts, Android client, stream leases, connectors |
| [API-v1.md](API-v1.md) | The `/v1` home-server API contract (accounts and profiles so far) |
| [phase1-plan.md](phase1-plan.md) | Phase 1 work, acceptance tests, and the Phase 2 buffering / stream-limit test designs |
| [risks.md](risks.md) | Risk register |

## Progress

| Phase 1 step | Status |
|---|---|
| 1. Repo setup, own app ID, Windows test fix | done |
| 2. `orbit.db` with numbered migrations | done |
| 3. Accounts, profiles, sessions (`/v1`) | done (server; no UI yet) |
| 4. Per-profile state (My list, resume, favourites, series progress) | done (server) |
| 5. Web sign-in and profile picker | next |
| 6. TV pairing | to do |
| 7. Android sync | to do |
| 8. Docker Compose deploy | to do |

Note: once the first account is created, the current web UI's password screen no longer works (the server
answers 410 to the old shared-password login) until step 5 adds the account sign-in screen.
