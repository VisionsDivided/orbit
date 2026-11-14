# Orbit home server API — v1 (accounts and profiles)

Status: Phase 1, step 3. This is the contract; the Python server implements it in `web/server/orbit/`.
It is written so another implementation (for example Rust) could replace the server without changing clients.

## Conventions
- JSON over HTTP, same origin as the web app. Every response is `application/json` with `Cache-Control: no-store`.
- Errors: `{"error": "<short message>"}` with 400 (bad input), 401 (not signed in), 403 (not allowed / CSRF),
  404 (no such thing *for you*: other accounts' ids answer 404, never 403, so ids cannot be probed),
  409 (conflict), 429 (throttled, with `Retry-After`).
- Writes are `POST` with a JSON body. They need the session cookie and `X-CSRF-Token` (from `GET /api/session`
  or the sign-in answer), exactly like the rest of the server. The two sign-in calls (`/v1/setup`, `/v1/auth/login`)
  have no session yet and send `X-TvBro: 1` instead.
- The Host, Origin and `Sec-Fetch-Site` checks of the existing server apply to `/v1` unchanged.
- Ids are opaque random strings. Clients must not parse them.
- Times are Unix seconds (integers).

## Modes
- **tvBro mode** (no accounts yet): the server behaves exactly like tvBro. `GET /v1/status` says `{"accounts": false}`.
- **Orbit mode** (at least one account): every request needs a session that belongs to an account. tvBro's shared
  password login (`/api/auth/login`, `require`, `password`, `viewer`) answers 410. Sessions made before the
  first account was created stop working.

## Objects
- `Account`: `{"id", "username", "displayName", "role": "owner"|"admin"|"member", "disabled": bool, "created"}`
- `Profile`: `{"id", "name", "kids": bool, "created"}`
- `Session`: `{"id", "device", "created", "lastSeen", "current": bool}`
- `Me`: `{"account": Account, "profiles": [Profile], "profile": Profile|null, "csrf"}`

## Endpoints

| Method | Path | Who | Body → Answer |
|---|---|---|---|
| GET | `/v1/status` | anyone (public) | → `{"accounts": bool, "setup": bool}`. `setup` is true while the first owner can be created. |
| POST | `/v1/setup` | anyone, only while no account exists | `{"token", "username", "password", "displayName"?}` → `Me` + session cookie. `token` is the one-time setup token from the server's data folder (`orbit-setup-token`). |
| POST | `/v1/auth/login` | anyone | `{"username", "password", "device"?}` → `Me` + session cookie. Wrong username or password: 403, the same message either way. Throttled like tvBro's login. |
| POST | `/v1/auth/logout` | signed in | `{}` → `{"ok": true}`, session ends, cookie cleared. |
| GET | `/v1/me` | signed in | → `Me` |
| GET | `/v1/profiles` | signed in | → `{"profiles": [Profile]}` (own account only) |
| POST | `/v1/profiles` | signed in | `{"name", "kids"?}` → `Profile` (at most 8 per account) |
| POST | `/v1/profiles/select` | signed in | `{"id"}` → `Me` (the profile must be your own) |
| POST | `/v1/profiles/delete` | signed in | `{"id"}` → `{"ok": true}` (not your last profile) |
| GET | `/v1/sessions` | signed in | → `{"sessions": [Session]}` (own account only) |
| POST | `/v1/sessions/revoke` | signed in | `{"id"}` → `{"ok": true}` (own sessions only) |
| GET | `/v1/accounts` | owner, admin | → `{"accounts": [Account]}` |
| POST | `/v1/accounts` | owner, admin | `{"username", "password", "displayName"?, "role"?: "member"\|"admin"}` → `Account`. Only the owner may create admins. Each new account gets one profile named after it. |
| POST | `/v1/accounts/disable` | owner, admin | `{"id", "disabled": bool}` → `Account`. Ends all its sessions. Never the owner, never yourself; admins cannot disable admins. |

## Linking a TV (step 6)

| Method | Path | Who | Body → Answer |
|---|---|---|---|
| POST | `/v1/pair/start` | the TV (public, `X-TvBro: 1`) | `{"device"}` → `{"pairId", "code", "expires", "interval"}`. `code` is `XXXX-XXXX` (letters without vowels, digits 2–9). At most 5 waiting pairings per address, 50 in all (429). 409 while the server has no accounts. |
| POST | `/v1/pair/poll` | the TV (public, `X-TvBro: 1`) | `{"pairId"}` → `{"status": "pending"}` or, once, `{"status": "approved", "session", "csrf", "me": Me}`. 404 when expired, collected or cancelled. Poll every `interval` seconds. |
| POST | `/v1/pair/approve` | signed in | `{"code", "profileId"?}` → `{"ok": true, "device"}`. The profile must be your own (else 404); unknown or expired codes answer 404 and are throttled per address (429). |

The TV keeps `session` (encrypted) and sends it as the `tvbro_s` cookie with the `X-CSRF-Token`, like a browser. It
shows the code and a QR code of `<server>/#pair:<code>`, which opens the web app's "Link a TV" dialog with the code
filled in. Pairings live 10 minutes in memory; an approved session that is never collected is ended. The device
appears under "Your devices" and can be signed out there; the TV notices on its next request (401) and offers to
link again.

The server checks the `Host` header, so the address the TV uses (for example `192.0.2.10:8750`) must be listed in
`TVBRO_ALLOWED_HOSTS`.

## Playlists for linked TVs (step 7)

| Method | Path | Who | Body → Answer |
|---|---|---|---|
| GET | `/v1/device/playlists` | a **linked TV** session only (403 for browser sign-ins) | → `{"playlists": [{"id", "name", "kind": "xtream"\|"m3u", "server", "user", "pass", "url", "epgUrl"}]}`: the household's enabled playlists with their logins, so the TV plays directly from the provider. Ids are the server's (`p0`, `p1` …), so channel and VOD keys are the same on every device. |

Sessions have a kind (`orbit.db` migration 002): `web` for a browser sign-in, `tv` for a device linked with a pairing
code. The TV keeps the playlists encrypted and removes them again when it is unlinked or signed out. It syncs My list
(three-way merge, so offline changes on either side survive) and film resume points (newest wins) through the
existing `/api/state` routes of its profile, every minute and shortly after a change.

## What belongs to a profile (step 4)
In Orbit mode these existing `/api` routes read and write the **signed-in session's profile** instead of the
household: `GET /api/state`, `POST /api/state/<scope>` (My list, which also holds favourite channels; resume
points; reminders; search history; preferences), `GET /api/series/continue`, `GET /api/series/state`,
`POST /api/series/*` (show progress, watched marks, caption/audio choice, hidden shows) and the profile part of
`GET /api/backup` / `POST /api/restore`. Each profile's data is a separate SQLite file
(`profiles/<profile id>/state.db`). Still shared by the household: playlists and their sync, channel layout and
custom groups, the guide, recordings and recording rules, parental PIN and locks, and server settings.

At setup, the household's data so far (its `state.db`) is copied into the owner's first profile; the household
file is left as it was. Deleting a profile deletes its data; sessions on it move to the account's first profile.

## Rules
- Usernames: 3–32 characters, letters, digits, `.`, `_`, `-`; compared case-insensitively.
- Passwords: 8–200 characters, stored only as PBKDF2-SHA256 (600 000 iterations), as in tvBro.
- Every query is filtered by the signed-in account; a profile or session id of another account is "not found".
- Sessions last 180 days and are renewed while used (tvBro's session rules). Revoking a session, logging out
  or disabling an account ends it at once.
