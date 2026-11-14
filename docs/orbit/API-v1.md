# Orbit home server API — v1 (accounts and profiles)

Status: Phase 1, step 3. This is the contract; the Python server implements it in `web/server/orbit/`.
It is written so another implementation (for example Rust) could replace the server without changing clients.

## Conventions
- JSON over HTTP, same origin as the web app. Every response is `application/json` with `Cache-Control: no-store`
  (one exception: `GET /v1/media/image` answers with the image).
- Errors: `{"error": "<short message>"}` with 400 (bad input), 401 (not signed in), 403 (not allowed / CSRF),
  404 (no such thing *for you*: other accounts' ids answer 404, never 403, so ids cannot be probed),
  409 (conflict), 429 (throttled, with `Retry-After`).
- Writes are `POST` with a JSON body. They need the session cookie and `X-CSRF-Token` (from `GET /api/session`
  or the sign-in answer), exactly like the rest of the server. The two sign-in calls (`/v1/setup`, `/v1/auth/login`)
  have no session yet and send `X-TvBro: 1` instead.
- The Host, Origin and `Sec-Fetch-Site` checks of the existing server apply to `/v1` unchanged.
- "Client address" (throttles, the pairing `address`, logs) is the connection's address, or behind trusted reverse
  proxies (`ORBIT_TRUSTED_PROXIES`, default loopback only) the right-most untrusted `X-Forwarded-For` entry; the
  header is ignored from anyone else. See DEPLOY.md, "Client addresses and a TLS reverse proxy in front".
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
| GET | `/v1/hello` | anyone (public, **any Host**) | → `{"app": "orbit", "api": 1, "hostOk": bool}`. Recognises an Orbit server (the TV's "Find my server" scan). It is answered before the Host check, so a scan by IP address works; `hostOk` says whether the Host the client used is allowed (else the address must be added to `TVBRO_ALLOWED_HOSTS` before the TV can link). It names no version, account or setting. |
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
| POST | `/v1/pair/start` | the TV (public, `X-TvBro: 1`) | `{"device"}` → `{"pairId", "code", "expires", "interval"}`. `code` is `XXXX-XXXX` (letters without vowels, digits 2–9). At most 3 pairings per client address and 20 in all may wait at once (expired ones do not count); over that: 429 with a message saying which limit was hit and `Retry-After` (seconds until the oldest of those codes expires). 409 while the server has no accounts. |
| POST | `/v1/pair/poll` | the TV (public, `X-TvBro: 1`) | `{"pairId"}` → `{"status": "pending"}` or, once, `{"status": "approved", "session", "csrf", "me": Me}`. 404 when expired, collected or cancelled. Poll every `interval` seconds. |
| POST | `/v1/pair/peek` | signed in | `{"code"}` → `{"device", "address", "waitingSecs"}`: which TV is waiting behind a code (its name and client address), shown before approving. Unknown codes: 404, throttled like `approve`. |
| POST | `/v1/pair/approve` | signed in | `{"code", "profileId"?}` → `{"ok": true, "device"}`. The profile must be your own (else 404); unknown or expired codes answer 404 and are throttled per address (429). |

The TV keeps `session` (encrypted) and sends it as the `tvbro_s` cookie with the `X-CSRF-Token`, like a browser. It
shows the code and a QR code of `<server>/#pair:<code>`, which opens the web app's "Link a TV" dialog with the code
filled in. Pairings live 10 minutes in memory; an approved session that is never collected is ended. The device
appears under "Your devices" and can be signed out there; the TV notices on its next request (401) and offers to
link again.

The server checks the `Host` header, so the address the TV uses (for example `192.0.2.10:8750`) must be listed in
`TVBRO_ALLOWED_HOSTS`.

**Find my server.** Next to the address field the TV offers "Find my server": it asks `GET /v1/hello` on port 8750 of
every address of its own /24 network (only private home ranges: 10/8, 172.16/12, 192.168/16; TCP, 64 at a time, 0.5 s connect and 1.5 s overall timeout per address; a few seconds in all, at most 6) and
lists the servers that answer `"app": "orbit"`. Picking one fills the field. UDP broadcast is not used: the server
runs in Docker behind a TCP port mapping, which broadcasts do not reach. A server whose `hostOk` is false is listed
with a note that its address must be added to `TVBRO_ALLOWED_HOSTS`.

## Playlists for linked TVs (step 7)

| Method | Path | Who | Body → Answer |
|---|---|---|---|
| GET | `/v1/device/playlists` | a **linked TV** session of any account (owner, admin or member); 403 for browser sign-ins | → `{"playlists": [{"id", "name", "kind": "xtream"\|"m3u", "server", "user", "pass", "url", "epgUrl"}]}`: the household's enabled playlists with their logins, so the TV plays directly from the provider. Ids are the server's (`p0`, `p1` …), so channel and VOD keys are the same on every device. |

Sessions have a kind (`orbit.db` migration 002): `web` for a browser sign-in, `tv` for a device linked with a pairing
code. Every linked TV gets the playlists, whatever its account's role: every household TV can watch IPTV.
The flip side is that any account can read the provider logins by linking a device with its own approval, so only
give accounts to people the household trusts with them. Browser sessions never get them (`session_kind` must be
`tv`). The TV keeps the playlists encrypted. When it is unlinked, signed out from another device, or linked to
another account, it removes everything the account brought: session and CSRF token, the server's playlists, My
list and favourites, film resume points, episode marks and show progress, recently watched and search history, so
the next account starts clean (data from before the first link was merged into that account then and goes too; it
comes back when that account links again). It syncs My list
(three-way merge, so offline changes on either side survive) and film resume points (newest wins) through the
profile's state, every minute and shortly after a change: it downloads the state only when its revision changed and
sends its own changes in one batch (`/v1/sync/*` below; the `/api/state` routes with older servers).

## The provider's stream limit (Phase 2)

TVs, browsers and recordings share one count of provider streams (tvBro's `StreamRegistry`). Each device or tab
has a random stream session id (`sid`, 32+ characters) that it keeps to itself.

| Method | Path | Who | Body → Answer |
|---|---|---|---|
| GET | `/v1/streams` | signed in | → `{"max", "count", "active": [StreamUse]}`, oldest first. Only streams active in the last 20 seconds count. |
| POST | `/api/stream/start` | signed in | `{"sid", "label", "what", "replace"?}` → `{"ok": true, "replaced": n}`. Takes a slot; when every slot is taken, another viewer is stopped: the stream whose `id` is `replace`, or else the oldest. `{"ok": false, "stopped": "recording"}` when every slot is held by a recording. `{"ok": false, "changed": true, "streams": {...}}` (the `/v1/streams` answer) when `replace` names a stream that has ended or is a recording and no slot is free: nothing was stopped; ask again with the new list. When the chosen stream has ended and a slot is free, the start simply goes ahead. |
| POST | `/api/stream/heartbeat` | signed in | `{"sid"}` every ~10 s → `{"ok": true}`, or `{"stopped": "replaced"\|"recording"}` once another device took the slot. |
| POST | `/api/stream/stop` | signed in | `{"sid"}` → `{"ok": true}`: the slot is free again. |

- `StreamUse`: `{"id", "sid", "label", "what", "profile", "since", "idleSec", "recording": bool}`. `id` is a public
  id of the stream (a hash of its `sid`, 12 hex characters) to pass as `replace`; knowing it does not let anyone send
  that device's heartbeats. `sid` is the first 6 characters of the session id (kept for older clients to recognise
  their own stream). `label` is the device as the client named it ("Chrome on Windows", "TV (…)"), `profile` the
  Orbit profile that started it (empty in tvBro mode and for recordings), `what` what is playing.
- The TV lists every active stream when the limit is full and offers one "Stop <name> and watch" per viewer
  (recordings are listed but cannot be chosen), plus Cancel. The web app still stops the oldest without asking
  (it never had a prompt).

## Show progress for devices (step 7b)

| Method | Path | Who | Body → Answer |
|---|---|---|---|
| GET | `/v1/progress/shows` | signed in | → `{"episodes": [{"seriesId", "epId", "secs", "dur", "done", "at"}], "shows": [{"id", "key", "name", "poster", "lastEp", "next": {"id", "season", "num", "title", "secs", "dur", "resume"}\|null, "finished", "hidden", "at"}]}` for the session's profile. `at` is milliseconds. |
| POST | `/v1/progress/episodes` | signed in | `{"items": [{"seriesId", "epId", "secs", "dur", "done", "at", "name"?, "poster"?}]}` (at most 500) → `{"applied", "shows"}`. Per episode the newest change wins (an older `at` is skipped); a clock ahead of the server's counts as now. Changed shows get their next episode recomputed in the background. |

`seriesId` is the server's VOD id (`7` for the first playlist, `p1~7` for others); `key` is the show's My list key
(`s|p0|7`). Episode ids are the provider's.

## Sync for devices: change check and batches

| Method | Path | Who | Body / query → Answer |
|---|---|---|---|
| GET | `/v1/sync/state` | signed in | `?since=<rev>` (optional) → `{"rev", "changed": false}` when the profile's state is unchanged since `rev`; otherwise `{"rev", "changed": true, "state": <as GET /api/state>, "progress": <as GET /v1/progress/shows>}`. |
| POST | `/v1/sync/batch` | signed in | `{"state"?: [{"op": "put"\|"del", "scope", "key", "value"?}], "episodes"?: [<as /v1/progress/episodes items>]}` (at most 500 of each) → `{"state": n, "episodes": applied, "shows"}`. |

- **`rev`** is an opaque string for the session profile's whole state (My list, resume points, reminders, history,
  prefs, show progress). Any write through any route (web, TV, restore, the server's own show recompute) gives a
  new one, and so does a server restart; it can also change without a visible difference. Compare it only for
  equality. A device keeps the `rev` and the data of its last full answer and sends `since=` every minute: an
  unchanged profile costs one tiny answer instead of the whole state.
- **Batch:** every change is checked first; one bad change answers 400 (`"state[3]: ..."`) and nothing is applied.
  Then all changes are applied in order in **one transaction** (state changes and episode changes together), so a
  device never leaves half a batch behind. `op` defaults to `put`; scopes and limits are those of
  `POST /api/state/<scope>` (`clear` is not offered here). Episode changes follow `/v1/progress/episodes` (newest
  wins per episode). Request bodies are capped at 1 MB, so devices send at most 200 state changes per call.
- Older clients keep working: `GET /api/state`, `POST /api/state/<scope>`, `GET /v1/progress/shows` and
  `POST /v1/progress/episodes` are unchanged. A device that gets 404 for these routes (an older server) falls back
  to them.

## Media servers (Phase 3)

Each account links **its own** Plex or Jellyfin user (`orbit.db` migration 003, `media_connections`), so what a person
may see and their watch state stay theirs on the media server. One account may link several servers. Every route
looks a connection up by id *and* the signed-in account: another account's connection answers 404.

Objects:
- `Connection`: `{"id", "kind": "plex"|"jellyfin", "baseUrl", "serverName", "serverId", "userId", "userName", "created", "lastOk"}`. Never a token.
- `Library`: `{"id", "title", "kind": "movie"|"series"|"other", "conn", "source", "serverName"}`
- `Item`: `{"id", "conn", "source": "plex"|"jellyfin", "kind": "movie"|"series"|"season"|"episode", "title", "year", "overview",
  "poster", "backdrop", "runtime", "rating", "guids": {"tmdb", "imdb", "tvdb"}, "progress": {"secs", "dur", "watched"},
  "seriesId", "season", "episode"}`. Times in seconds; missing values are `null`. `poster` / `backdrop` are
  `/v1/media/image?...` URLs on this server (or `null`). Ids are the media server's, opaque; they are only meaningful
  together with `conn`.

| Method | Path | Body / query → Answer |
|---|---|---|
| GET | `/v1/media/connections` | `?check=1` also asks each server (adds `"ok"`, and `"error"` when it fails) → `{"connections": [Connection]}` |
| POST | `/v1/media/connect/start` | `{"kind": "jellyfin", "baseUrl"}` → `{"id", "kind", "method": "quickconnect", "code", "server": {"name", "version"}, "hint", "expires", "interval"}`: the user enters `code` in Jellyfin (profile ▸ Quick Connect). 409 when Quick Connect is off (use the password call). `{"kind": "plex"}` → `{"id", "kind", "method": "pin", "code", "linkUrl": "https://plex.tv/link", "hint", "expires", "interval"}`: the user opens plex.tv/link and enters the 4-character `code`. At most 5 waiting sign-ins per account (429); they live 10 minutes in memory. |
| POST | `/v1/media/connect/poll` | `{"id"}` → `{"status": "pending", "expires"}` or `{"status": "linked", "connections": [Connection]}`. Poll every `interval` seconds. 404 when expired or not yours. Plex links every Plex server of that user this server can reach (one connection each). Linking the same server and user again updates the existing connection. |
| POST | `/v1/media/connect/password` | `{"kind": "jellyfin", "baseUrl", "username", "password"}` → `{"connections": [Connection]}`. The password goes to Jellyfin once and is never stored. Wrong password: 403; throttled per account (429). |
| POST | `/v1/media/connections/delete` | `{"id"}` → `{"ok": true}`. Jellyfin is asked to end the token (best effort); the stored token is removed. |
| GET | `/v1/media/libraries` | `?conn=` (optional: without it, all your connections) → `{"libraries": [Library], "errors": [{"conn", "error"}]}` |
| GET | `/v1/media/items` | `?conn=&library=&cursor=&kind=movie\|series&limit=` (limit 1–200, default 50) → `{"items": [Item], "next": cursor\|null, "total"}`. `cursor` is opaque; pass `next` back. |
| GET | `/v1/media/item` | `?conn=&id=` → `Item` |
| GET | `/v1/media/children` | `?conn=&id=` → `{"items": [Item]}`: the seasons of a series, the episodes of a season |
| GET | `/v1/media/image` | `?conn=&path=` → the image itself (`image/jpeg`, `png`, `webp` or `gif` only; at most 5 MB; `Cache-Control: private, max-age=86400`). The server fetches it with the token. `path` must be an artwork path of that server (as found in `poster` / `backdrop`), else 400. This is the one `/v1` answer that is not JSON. |
| POST | `/v1/media/playback` | `{"conn", "id"}` → `{"url", "headers": {}, "direct": bool, "container", "resumeSecs", "conn", "source"}` for a film or episode (400 for a series / season). |
| POST | `/v1/media/progress` | `{"conn", "id", "secs", "dur", "state": "playing"\|"paused"\|"stopped"}` → `{"ok": true}`. Jellyfin: `Sessions/Playing/Progress` or `/Stopped`; Plex: `/:/timeline`. Send it every ~10 seconds while playing and once on stop. |
| POST | `/v1/media/watched` | `{"conn", "id", "watched": bool}` → `{"ok": true}` |

Errors from the media server: 409 with `"... link it again"` when its token no longer works (sign in again with
`connect/start`), 404 when the item is not on the server, 502 when it cannot be reached or answers oddly. Messages
never contain addresses with tokens.

**Tokens.** They are stored sealed with the server's encryption key in `media.enc` (not in `orbit.db`), so
`rotate_key` re-encrypts them with the other sealed files. They are never logged, never in list answers and never
in artwork URLs. The **only** place a token leaves the server is `playback.url`: a direct-play URL for the media
server that contains **the signed-in person's own** token (Jellyfin `api_key=`, Plex `X-Plex-Token=`), so the device
plays straight from Plex / Jellyfin. Clients must treat that URL as a secret (keep it out of logs, do not share or
cache it) and fetch a fresh one for each playback.

**Local network.** The server reaches each linked server's exact `host:port` even when "local network sources" is
off (`net.set_private_hosts`); loopback and link-local addresses stay refused. Only an owner or admin can link a
server at a new local address; a member can link their own user on a server the household already linked (or
any public address). Jellyfin uses one `DeviceId` per link (Jellyfin ends older tokens of a device that signs in
again); Plex gets the same per-link `X-Plex-Client-Identifier` and stores each server's own access token.

## What belongs to a profile (step 4)
In Orbit mode these existing `/api` routes read and write the **signed-in session's profile** instead of the
household: `GET /api/state`, `POST /api/state/<scope>` (My list, which also holds favourite channels; resume
points; reminders; search history; preferences), `GET /api/series/continue`, `GET /api/series/state`,
`POST /api/series/*` (show progress, watched marks, caption/audio choice, hidden shows) and the profile part of
`GET /api/backup` / `POST /api/restore`. Each profile's data is a separate SQLite file
(`profiles/<profile id>/state.db`). Still shared by the household: playlists and their sync, channel layout and
custom groups, the guide, recordings and recording rules, parental PIN and locks, and server settings.

Preference keys written by Orbit clients (scope `prefs`):
- `tvChannelFormats`: the TV's per-channel live stream format, `{"at": <ms>, "channels": {"<channel key>": "ts"|"hls"}}`
  (at most 400 channels; a channel not listed follows the TV's Settings ▸ Live stream format). The copy with the newer
  `at` wins as a whole.

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
  on "local network sources" (`net.set_private_hosts`). Loopback and link-local stay refused. While
  `POST /v1/discover/settings` tests a new address, that address is allowed only for the test's own calls (that
  request's thread, `net.private_host_here`); the household-wide allowlist changes only once the address is saved.
- **Cache:** rows 10 minutes, titles 1 hour, searches 10 minutes, at most 256 answers. `GET /v1/requests` looks up
  each distinct title once (cached ones not at all), at most 4 at a time; a title Seerr does not know (404) is
  not asked for again for 10 minutes and shows as `"title": null`. When Seerr cannot be reached
  (or answers 5xx), the last answer is served with `"stale": true`; with nothing cached the call answers 503. After
  a failure the server does not try Seerr again for 15 seconds, so pages stay fast. `POST /v1/requests` always
  checks Seerr's current state first and answers 503 while Seerr is down.
- **Play instead of Request:** `discover.availability_override(tmdb, type)` is merged over every Title. It does
  nothing yet; Phase 5 (catalogue merge) will return local Plex / Jellyfin / IPTV matches by TMDb id there.
- **Privacy note:** `poster` and `backdrop` are full `https://image.tmdb.org/t/p/...` URLs, so clients load artwork
  straight from TMDB's public image CDN (no key, but TMDB sees the client's address and which artwork it loads). A
  later option can proxy them through the server's image cache.
