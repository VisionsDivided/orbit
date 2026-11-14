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

## Show progress for devices (step 7b)

| Method | Path | Who | Body → Answer |
|---|---|---|---|
| GET | `/v1/progress/shows` | signed in | → `{"episodes": [{"seriesId", "epId", "secs", "dur", "done", "at"}], "shows": [{"id", "key", "name", "poster", "lastEp", "next": {"id", "season", "num", "title", "secs", "dur", "resume"}\|null, "finished", "hidden", "at"}]}` for the session's profile. `at` is milliseconds. |
| POST | `/v1/progress/episodes` | signed in | `{"items": [{"seriesId", "epId", "secs", "dur", "done", "at", "name"?, "poster"?}]}` (at most 500) → `{"applied", "shows"}`. Per episode the newest change wins (an older `at` is skipped); a clock ahead of the server's counts as now. Changed shows get their next episode recomputed in the background. |

`seriesId` is the server's VOD id (`7` for the first playlist, `p1~7` for others); `key` is the show's My list key
(`s|p0|7`). Episode ids are the provider's.

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

## Discover and requests (Phase 6)

Browse what is new and ask for films and shows through the household's Seerr (the
successor of Overseerr / Jellyseerr). The server talks to Seerr; clients never see its address or API key.
Server code: `web/server/orbit/seerr.py` (settings, client, cache, normalization) and `web/server/orbit/discover.py`
(the routes below).

| Method | Path | Who | Body / query → Answer |
|---|---|---|---|
| GET | `/v1/discover/status` | signed in | → `{"configured", "reachable", "linked", "version"?}`; owners and admins also get `"baseUrl"` and `"keySet"` (never the key). `linked`: your account has a Seerr user. |
| GET | `/v1/discover/rows` | signed in | → `{"rows": [{"id": "trending"\|"movies"\|"tv"\|"upcoming", "title", "items": [Title]}], "stale"}`. `upcoming` mixes films and shows. |
| GET | `/v1/discover/search?q=` | signed in | `q`: 1–100 characters → `{"results": [Title], "stale"}` (people are left out). |
| GET | `/v1/discover/title?type=movie\|tv&tmdb=` | signed in | → `Title` with `seasons` for shows, plus `"stale"`. 404 when Seerr does not know it. |
| GET | `/v1/requests` | signed in | → `{"requests": [Request], "linked"}`: your own requests (the newest 50). Owners and admins may pass `all=1` for everyone's; then `requestedBy.accountId` names the Orbit account where one is linked. |
| POST | `/v1/requests` | signed in, linked | `{"type": "movie"\|"tv", "tmdb", "seasons"?: [n]}` → 201 `{"created": true, "request": Request, "title": Title}`, or 200 `{"created": false, "title": Title}` when it is already available, being processed or requested (nothing new is sent to Seerr). For shows without `seasons`, every season that is neither available nor requested is asked for. 409 for an account without a linked Seerr user ("ask the owner to link your Seerr user"); 403 with Seerr's message when Seerr refuses (permissions, quota); 503 while Seerr cannot be reached. |
| POST | `/v1/discover/settings` | owner, admin | `{"baseUrl", "apiKey"}` → `{"configured": true, "baseUrl", "keySet": true, "version"}`. The connection and key are tested (`/api/v1/status`, `/api/v1/auth/me`) before anything is saved (400 wrong key, 502 unreachable). Leave `apiKey` out to change only the address. `{"remove": true}` → `{"configured": false}`. |
| GET | `/v1/discover/users` | owner, admin | → `{"users": [{"id", "name", "email", "username", "plexUsername", "jellyfinUsername"}], "accounts": [{"id", "username", "displayName", "seerrUserId", "suggested"}]}`. `suggested`: the one Seerr user whose username, Plex / Jellyfin name, display name or e-mail name equals the account's username or display name (case-insensitive), else null. |
| POST | `/v1/discover/map` | owner, admin | `{"accountId", "seerrUserId"}` (null unlinks) → `{"accountId", "seerrUserId"}`; the Seerr user must exist (400). `{"auto": true}` links every unlinked account that has exactly one suggestion → `{"linked": [{"accountId", "seerrUserId"}]}`. |

Objects:
- `Title`: `{"type": "movie"|"tv", "tmdb", "title", "year", "overview", "poster", "backdrop", "rating",
  "status": "available"|"partial"|"processing"|"pending"|"requestable", "requestStatus"?: "pending"|"approved"|"declined"|"failed"|"completed", "seasons"?: [{"season", "episodes", "name", "status"}]}`.
  `status` comes from Seerr's media status (5 available, 4 partially available, 3 processing, 2 pending; anything
  else is requestable). `requestStatus` is the newest request's state. Specials (season 0) are left out.
- `Request`: `{"id", "type", "tmdb", "status", "mediaStatus", "created", "seasons"? , "requestedBy": {"seerrUserId", "name", "accountId"?}, "title": Title|null}`.

How it works:
- **Requests are made as the account's Seerr user.** The server uses Seerr's admin API key with `X-Api-User:
  <linked Seerr user id>`, so Seerr's own permissions, auto-approval and quotas apply to each person. Discovery
  (rows, search, title) uses the key alone and works for every account, linked or not.
- **Storage:** the Seerr address, key and the account → Seerr user links are one encrypted file, `seerr.enc` in the
  data folder, sealed with the server key like the provider logins (covered by `rotate_key` and the startup key
  check). Nothing is added to `orbit.db`, so no migration number is used.
- **LAN access:** the configured Seerr host and port (only those) may be on the local network without switching
  on "local network sources" (`net.set_private_hosts`). Loopback and link-local stay refused.
- **Cache:** rows 10 minutes, titles 1 hour, searches 10 minutes, at most 256 answers. When Seerr cannot be reached
  (or answers 5xx), the last answer is served with `"stale": true`; with nothing cached the call answers 503. After
  a failure the server does not try Seerr again for 15 seconds, so pages stay fast. `POST /v1/requests` always
  checks Seerr's current state first and answers 503 while Seerr is down.
- **Play instead of Request:** `discover.availability_override(tmdb, type)` is merged over every Title. It does
  nothing yet; Phase 5 (catalogue merge) will return local Plex / Jellyfin / IPTV matches by TMDb id there.
- **Privacy note:** `poster` and `backdrop` are full `https://image.tmdb.org/t/p/...` URLs, so clients load artwork
  straight from TMDB's public image CDN (no key, but TMDB sees the client's address and which artwork it loads). A
  later option can proxy them through the server's image cache.
