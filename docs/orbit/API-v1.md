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
- `Session`: `{"id", "device", "name", "kind": "web"|"tv", "created", "lastSeen", "current": bool}`. `device` is the label
  the client sent at sign-in ("Chrome on Windows", "TV (Model)"); `name` the name the household gave it ("Kitchen"),
  `""` while it has none.
- `Me`: `{"account": Account, "profiles": [Profile], "profile": Profile|null, "csrf", "deviceName"}`. `deviceName` is
  the calling device's own `name` (`""` when not named).
- `Device` (a linked TV): `{"id", "name", "device", "created", "lastSeen", "current": bool, "account": {"id",
  "displayName"}, "profile": "<profile name>", "canUnlink": bool}`. `id` is the same as the `Session` id.

## Endpoints

| Method | Path | Who | Body → Answer |
|---|---|---|---|
| GET | `/v1/hello` | anyone (public, **any Host**) | → `{"app": "orbit", "api": 1, "hostOk": bool}`. Recognises an Orbit server (the TV's "Find my server" scan). It is answered before the Host check, so a scan by IP address works; `hostOk` says whether the Host the client used is allowed (else the address must be added to `TVBRO_ALLOWED_HOSTS` before the TV can link). It names no version, account or setting. |
| GET | `/v1/status` | anyone (public) | → `{"accounts": bool, "setup": bool}`. `setup` is true while the first owner can be created. |
| POST | `/v1/setup` | anyone, only while no account exists | `{"token", "username", "password", "displayName"?}` → `Me` + session cookie. `token` is the one-time setup token from the server's data folder (`orbit-setup-token`). |
| POST | `/v1/auth/login` | anyone | `{"username", "password", "device"?, "kind"?}` → `Me` + session cookie. Wrong username or password: 403, the same message either way. Throttled like tvBro's login. `"kind": "desktop"` makes a desktop-app session (see "The desktop app" below); anything else is a browser session. |
| POST | `/v1/auth/logout` | signed in | `{}` → `{"ok": true}`, session ends, cookie cleared. The web app then removes what the browser cached for the account and profile (localStorage `tvbro.ui`, `tvbro.reminders`; sessionStorage `tvbro.parental`, `tvbro.sid`; no IndexedDB is used), and does the same whenever it shows the sign-in page because the server ended its session, so the next person starts clean. |
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

### Device names and linked TVs

| Method | Path | Who | Body → Answer |
|---|---|---|---|
| POST | `/v1/device/name` | signed in (the device itself: a TV after linking or from Settings ▸ Home server ▸ Name this TV) | `{"name"}` → `{"ok": true, "name"}` (the name as stored). |
| GET | `/v1/devices` | signed in | → `{"devices": [Device]}`: linked TVs (sessions of kind `tv`), newest first. Owner and admins get the household's; members their own account's. |
| POST | `/v1/devices/rename` | signed in | `{"id", "name"}` → `Device`. Owner and admins may rename any linked TV; members only their own account's (others answer 404). |
| POST | `/v1/devices/unlink` | signed in | `{"id"}` → `{"ok": true}`: the TV's session ends (it removes the account's data on its next request). Your own account's TVs; the owner any; admins also members' (never the owner's or another admin's: 403). Unknown ids, browser sessions and (for members) other accounts' TVs answer 404. |

Names (`orbit.db` migration 004, column `account_sessions.name`): 1 to 32 characters, letters (any language), digits,
spaces and `' - . _ ( ) & + #`; leading and trailing spaces are dropped and runs of spaces become one; anything else
(control characters, quotes, `<`, emoji) is 400. A name belongs to the session, so linking a TV again starts unnamed.
Right after linking, the TV offers to name itself (the field offers its model, e.g. "SHIELD Android TV"); the step can
be skipped. The web app lists linked TVs for the owner and admins under Settings ▸ Accounts ▸ Linked TVs, with Rename
and Unlink.

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
| GET | `/v1/device/playlists` | a **linked TV** or **desktop app** session of any account (owner, admin or member); 403 for browser sign-ins | → `{"playlists": [{"id", "name", "kind": "xtream"\|"m3u", "server", "user", "pass", "url", "epgUrl"}]}`: the household's enabled playlists. With **hidden provider logins** (the default, below) every address and login points at this server's Xtream facade; with it off, they are the provider's own, so the TV plays directly from the provider. Ids are the server's (`p0`, `p1` …), so channel and VOD keys are the same on every device. |

Sessions have a kind (`orbit.db` migration 002): `web` for a browser sign-in, `tv` for a device linked with a pairing
code. Every linked TV gets the playlists, whatever its account's role: every household TV can watch IPTV.
Browser sessions never get them (`session_kind` must be `tv`). With hidden logins off, any account can read the
provider logins by linking a device with its own approval, so only give accounts to people the household trusts with
them. The TV keeps the playlists encrypted.

### Hidden provider logins: the Xtream facade

On by default. `ORBIT_HIDE_PROVIDER_LOGIN=1` / `0` decides it on the server; otherwise the owner's setting
(Settings > Security, "Provider login on linked TVs"; `POST /api/settings {"hideProviderLogin": bool}`). The status
answer's `settings` carry `hideProviderLogin` (as it applies) and `hideProviderLoginForced` (the environment decides).
TVs pick up a change at their next playlist sync (every 30 minutes, and at start).

With it on, `/v1/device/playlists` answers, for each playlist:

- **Xtream**: `{"server": "<origin>/xt/<id>", "user": "tv<16 hex>", "pass": "<32 hex>", "url": "", "epgUrl": ""}`.
- **M3U**: `{"url": "<origin>/xt/<id>/get.php?username=…&password=…", "epgUrl": "<origin>/xt/<id>/xmltv.php?…&n=0,…"}`
  (one guide address per configured one; empty when none is set), `server` / `user` / `pass` empty.

`<origin>` is the scheme and `Host` the TV used for this request. The user is the TV session's public id, the
password an HMAC of the session with the server key, so both stay the same for as long as the TV stays linked (the
TV's playlist list does not change between syncs). They stop working at once when the session ends: unlinked,
signed out from another device (`/v1/sessions/revoke`), account disabled or password reset, or expiry. Rotating the
server key changes every password; TVs get the new one at their next playlist sync. A TV app that predates the
facade just sees an Xtream server and works unchanged.

Facade routes (Host check as everywhere; no cookie, the device login is in the address; wrong or dead logins get
401 `{"user_info": {"auth": 0}, "error"}`, unknown playlists 404):

| Path under `/xt/<id>/` | Does |
|---|---|
| `player_api.php?username&password[&action&…]` | the provider's answer for the same action and parameters (Xtream only). `user_info.username` / `password` become the device's, `server_info` names this server, and any value that carries the provider login in an address is replaced by a sealed `s/` address. Other answers pass through unchanged. gzip when the TV accepts it. |
| `xmltv.php?username&password[&n=i]` | the guide, streamed through: Xtream's own `xmltv.php`, or the M3U playlist's i-th guide address. |
| `get.php?username&password` | the M3U playlist (for Xtream: the provider's `get.php`), rewritten: every stream line, the `url-tvg` / `x-tvg-url` guide addresses and absolute `catchup-source` templates point here. Logos stay as they are. |
| `live\|movie\|series\|timeshift/<user>/<pass>/<rest>` | relayed from the provider's same path with the real login (Xtream only). |
| `s/<user>/<pass>/<token>[.ext]` | a sealed provider address (HLS segment, M3U stream, guide); a query string the TV adds (catch-up "append") is passed on. |
| `t/<user>/<pass>/<token>[.ext]?v0=…&v1=…` | a sealed catch-up template; its placeholders were handed to the TV in order as `v0`, `v1` …, so it fills them in as usual. |

Relaying: bytes are piped through 64 KiB at a time as they arrive (no buffering beyond one chunk; a TV that stops
reading stops the provider read too), `Range` / `Content-Range` / `Accept-Ranges` pass through for films, the
provider's redirects are followed by the server, the TV's `User-Agent` is passed on, and an answer without a length
(live) closes the connection when it ends. An HLS playlist is rewritten so every segment, key and variant comes
through `s/`. Tokens are AES-SIV sealed with the server key (`App.seal`), so the TV can replay them but never read
the provider address inside. A running relay stops within a few chunks once its TV's session ends. Images (logos,
posters) still load straight from wherever the catalogue names them, unless that address carries the login.

Nothing logs a provider address or a device login: request lines show `/xt/<id>/<kind>/-/-/…` (the container's nginx
log too), errors the exception type only. When it is unlinked, signed out from another device, or linked to
another account, it removes everything the account brought: session and CSRF token, the server's playlists, My
list and favourites, film resume points, episode marks and show progress, recently watched and search history, so
the next account starts clean (data from before the first link was merged into that account then and goes too; it
comes back when that account links again). It syncs My list
(three-way merge, so offline changes on either side survive) and film resume points (newest wins) through the
profile's state, every minute and shortly after a change: it downloads the state only when its revision changed and
sends its own changes in one batch (`/v1/sync/*` below; the `/api/state` routes with older servers).

## The desktop app (Windows, macOS)

The desktop app has a keyboard, so it signs in like the web app instead of showing a pairing code: server address,
username and password, then "Who's watching?".

1. `GET /v1/status`: `accounts` false means a tvBro-mode server; the app says so and stays as it is.
2. `POST /v1/auth/login` with `X-TvBro: 1` and `{"username", "password", "device": "Orbit on Windows", "kind": "desktop"}`.
   The session id comes in the `tvbro_s` cookie of the answer (`Set-Cookie`), the CSRF token in `Me.csrf`. The app
   stores both encrypted (Windows DPAPI, macOS Keychain; `Secrets` in desktop/Store.kt) and sends them like the TV.
3. `Me.profiles` is the "Who's watching?" list; `POST /v1/profiles/select` `{"id"}` picks one (also later, from the
   profile menu). An account with one profile skips the picker.
4. From then on the app is a device like a linked TV: `GET /v1/device/playlists` (the household's playlists, which
   replace any typed into the app, so channel and VOD keys match every other device), `/v1/sync/state?since=` and
   `/v1/sync/batch` every minute and shortly after a change, and the stream count below (one slot per app, the
   "Stop … and watch" choice when every stream is in use).
5. Home, Movies and Shows use the catalogue routes ("Catalogue" below, the shared `CatalogApi`): Home, Recommended,
   Browse (`source` from the All · Local · IPTV toggle, `genre`, `sort`, pages of 120) and Collections. Signed out,
   or on a 404 from a server without the catalogue, the app uses its own IPTV catalogue as before. Artwork on the home
   server (`/img`, `/v1/media/image`) is loaded with the session cookie and without following redirects.
6. The per-channel live stream format (TS / HLS) is the profile's `tvChannelFormats` pref, as on the TV ("What belongs
   to a profile" below), merged in each sync (the newer copy wins).
7. A recording made by the desktop app takes a slot of its own with `/api/stream/start` (its own `sid`, label
   "<device> · recording", `what` "Recording: <title>") and keeps it with heartbeats until it ends (`/api/stream/stop`).
   Started by hand, a full count asks which stream to stop; a scheduled one lets the server stop the oldest viewer.
   The server does not mark these slots as recordings (`recording` false; only its own recordings are pinned), so
   another device can still take the slot: the recording then goes on and the app says so.

Why a `desktop` kind and not `web`: a desktop session plays from the provider itself, so it needs the playlists,
which browser sessions never get. It has the `viewer` role whatever the account's role, like a linked TV (the app has
no admin screens; household settings stay in the web app). It is no new exposure: any account could already link a
"TV" with its own approval. Signing out (`/v1/auth/logout`), a revoked session or a disabled account (any 401) makes
the app remove everything the account brought, as on the TV: session, the server's playlists, My list, resume points
and show progress, search history and the last channel. Device settings and recordings stay.

## The provider's stream limit (Phase 2)

TVs, browsers and recordings share one count of provider streams (tvBro's `StreamRegistry`). Each device or tab
has a random stream session id (`sid`, 32+ characters) that it keeps to itself.

| Method | Path | Who | Body → Answer |
|---|---|---|---|
| GET | `/v1/streams` | signed in | → `{"max", "count", "active": [StreamUse], "relays": [Relay]}`, oldest first. Only streams active in the last 20 seconds count. |
| POST | `/api/stream/start` | signed in | `{"sid", "label", "what", "replace"?}` → `{"ok": true, "replaced": n}`. Takes a slot; when every slot is taken, another viewer is stopped: the stream whose `id` is `replace`, or else the oldest. `{"ok": false, "stopped": "recording"}` when every slot is held by a recording. `{"ok": false, "changed": true, "streams": {...}}` (the `/v1/streams` answer) when `replace` names a stream that has ended or is a recording and no slot is free: nothing was stopped; ask again with the new list. When the chosen stream has ended and a slot is free, the start simply goes ahead. |
| POST | `/api/stream/heartbeat` | signed in | `{"sid"}` every ~10 s → `{"ok": true}`, or `{"stopped": "replaced"\|"recording"}` once another device took the slot. |
| POST | `/api/stream/stop` | signed in | `{"sid"}` → `{"ok": true}`: the slot is free again. |

- `StreamUse`: `{"id", "sid", "label", "what", "profile", "device", "name", "since", "idleSec", "recording": bool}`. `id` is a public
  id of the stream (a hash of its `sid`, 12 hex characters) to pass as `replace`; knowing it does not let anyone send
  that device's heartbeats. `sid` is the first 6 characters of the session id (kept for older clients to recognise
  their own stream). `label` is the device as the client named it ("Chrome on Windows", "TV (…)"), `profile` the
  Orbit profile that started it (empty in tvBro mode and for recordings), `device` the name the household gave the
  device ("Kitchen", see "Device names" above; empty when not named), `what` what is playing. `name` is what to call
  the stream: `device`, else `profile`, else `label` ("Recording" for a recording). The device name is read when the
  stream starts, so a rename shows from the device's next start.
- `Relay`: `{"device", "playlist", "what", "since", "idleSec"}`, one per linked TV pulling media through the facade
  (open relay, or media bytes in the last 20 seconds). This is exact, since every byte passes the server, but it is
  informational: `count` and the slot rules above are unchanged (the TV still takes its slot with `/api/stream/start`).
  `what` is the last stream named in an address (`live 101`, `movie 7`); empty for M3U and HLS segments.
- The TV and the web player list every active stream when the limit is full and offer one "Stop <name> and watch"
  per viewer (recordings are listed but cannot be chosen), plus Cancel. Names: `device`, else `profile`, else `label`;
  two equal names get the label added ("Kitchen on TV (Model)"), then a number (shared/.../StreamChoice.kt and
  web/frontend/js/streamprompt.js). Cancel starts nothing (the web player closes). The small live preview never
  stops anyone: when every stream is in use it stays off. In tvBro mode (no `/v1/streams`) the web app still stops
  the oldest without asking.

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
  "seriesId", "season", "episode", "genres": [..], "added", "released", "colors", "lastViewed"}`. Times in seconds; missing values are `null`.
  `genres`, `added` (Unix seconds), `released` (`"YYYY-MM-DD"`), `colors` (Plex UltraBlur, see the catalogue) and
  `lastViewed` (Unix seconds) were added for the catalogue (Phase 5). `poster` / `backdrop` are
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
| GET | `/v1/media/image` | `?conn=&path=` → the image itself (`image/jpeg`, `png`, `webp` or `gif` only; at most 5 MB; `Cache-Control: private, max-age=86400`). The server fetches it with the token. `path` must be an artwork path of that server (as found in `poster` / `backdrop`), else 400. With `/v1/discover/image`, one of the two `/v1` answers that are not JSON. |
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
any public address). While an owner or admin signs in, the new address is allowed only for that sign-in's own
requests (start, each poll, the password sign-in: `net.private_hosts_scope`, per request thread), never in the
server-wide list, until the link is saved; a Plex server found during the sign-in is added the same way. Jellyfin uses one `DeviceId` per link (Jellyfin ends older tokens of a device that signs in
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
- `tvChannelFormats`: the per-channel live stream format of the TV and the desktop app, `{"at": <ms>, "channels": {"<channel key>": "ts"|"hls"}}`
  (at most 400 channels; a channel not listed follows the TV's Settings ▸ Live stream format). The copy with the newer
  `at` wins as a whole.
- `liveGroupsPinned`: the Live TV groups the profile pinned as quick-select tabs, shared by the TV and the web app: a
  JSON array of full group names (as `/api/groups` names them, e.g. `"Sports | NHL Teams"`), most recent first, at most
  8, no repeats. A name no longer in `/api/groups` is skipped (kept in the list). The TV sends its copy when it changed the pins since its last sync, otherwise it takes the server's.
- `liveRecent`: the web app's Live TV "Recent" tab, the last channels watched: a JSON array of
  `{key, name, logo, number, group}`, most recent first, at most 12.

Guide data (`/api/guide`, now/next, recordings, search) is normalised on the server (providers send overlapping
entries, and fillers or inner entries inside a live game). Per channel: entries with stop <= start are dropped; the
rest are sorted by start, longer first; of entries starting together only the longest is kept (for equal length the
first in input order); an entry lying fully inside an earlier kept one with the same title is dropped (duplicates
included). Then the timeline is overlaid: at any moment the programme shown is the kept entry covering it with the
latest start, so when an inner entry ends the enclosing one resumes (Block 1–5 with Match 2–3 gives Block 1–2,
Match 2–3, Block 3–5; A 1–3 with B 2–4 gives A 1–2, B 2–4). The result is sorted and never overlaps; a resumed piece
has the original's title, description and other fields with its own start and stop, and zero-length pieces are
dropped. The web app (`guide.js normalizeProgs`) and the TV and desktop apps (`EpgIndex.tidy`) apply the same rules.

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
successor of Overseerr / Jellyseerr). The server talks to Seerr; clients never see its API key, and see its address only as a title page's
`seerrUrl` ("Open in Seerr").
Server code: `web/server/orbit/seerr.py` (settings, client, cache, normalization) and `web/server/orbit/discover.py`
(the routes below).

| Method | Path | Who | Body / query → Answer |
|---|---|---|---|
| GET | `/v1/discover/status` | signed in | → `{"configured", "reachable", "linked", "version"?}`; owners and admins also get `"baseUrl"` and `"keySet"` (never the key). `linked`: your account has a Seerr user. |
| GET | `/v1/discover/rows` | signed in | → `{"rows": [{"id": "trending"\|"movies"\|"tv"\|"upcoming", "title", "items": [Title], "page": 1, "pages"}], "stale"}`: the first page of each list (Seerr sends 20 per page). `pages`: how many pages Seerr has (at most 500; 0 when the list could not be loaded). `upcoming` mixes films and shows. |
| GET | `/v1/discover/rows/{id}?page=` | signed in | `id` from the rows above, `page` 1–500 (default 1) → `{"id", "title", "items": [Title], "page", "pages", "stale"}`: one further page of a row, for clients that load more near the row's end. `upcoming` interleaves page N of the film and show lists. Cached like the rows (each page on its own). 404 for an unknown row, 400 for a bad page, 503 while Seerr cannot be reached and nothing is cached. |
| GET | `/v1/discover/search?q=&page=` | signed in | `q`: 1–100 characters, `page` 1–500 (default 1) → `{"results": [Title], "people": [Person], "page", "pages", "total", "stale"}` through Seerr's `/search` (films, shows and people). `total`: matches over all pages (films, shows and people), or null. Each page cached 10 minutes. |
| GET | `/v1/discover/title?type=movie\|tv&tmdb=` | signed in | → `Title` with `seasons` for shows, plus `"stale"`. 404 when Seerr does not know it. |
| GET | `/v1/discover/title/{movie\|tv}/{tmdb}` | signed in | → `TitlePage`, plus `"stale"`: everything a title page shows. Cached 1 minute (requests and download progress change while someone looks at it); a request through `POST /v1/requests` drops it. 404 when Seerr does not know the title (or the path is not `<movie\|tv>/<number>`), 400 for another type. Request chosen seasons with `POST /v1/requests` and `"seasons"`. |
| GET | `/v1/discover/image` | signed in | `?p=/<size>/<file>`: one TMDB artwork file through this server (for clients whose page may load images only from its own origin, the web app). `size` is one of `w92 w154 w185 w300 w342 w500 w780 w1280 original`; `file` is 1–100 letters, digits, `_` or `-` with `.jpg`, `.jpeg`, `.png` or `.webp` (exactly TMDB's `/t/p/<size>/<file>`; anything else is 400). Fetched only from `https://image.tmdb.org/t/p/`, at most 5 MB (413), served only when the bytes are a JPEG / PNG / GIF / WebP / AVIF (415 otherwise; never the upstream Content-Type), with `Cache-Control: private, max-age=604800` and a sandbox CSP. Kept in the server's image cache (`imgcache/`, shared with `/img`, 300 MB cap, oldest dropped). 404 when TMDB has no such file. Works whether or not Seerr is set up. This is the one other `/v1` answer that is not JSON. |
| GET | `/v1/requests` | signed in | → `{"requests": [Request], "linked"}`: your own requests (the newest 50). Owners and admins may pass `all=1` for everyone's; then `requestedBy.accountId` names the Orbit account where one is linked. |
| POST | `/v1/requests` | signed in, linked | `{"type": "movie"\|"tv", "tmdb", "seasons"?: [n]}` → 201 `{"created": true, "request": Request, "title": Title}`, or 200 `{"created": false, "title": Title}` when it is already available, being processed or requested (nothing new is sent to Seerr). For shows without `seasons`, every season that is neither available nor requested is asked for. 409 for an account without a linked Seerr user ("ask the owner to link your Seerr user"); 403 with Seerr's message when Seerr refuses (permissions, quota); 503 while Seerr cannot be reached. |
| POST | `/v1/discover/settings` | owner, admin | `{"baseUrl", "apiKey"}` → `{"configured": true, "baseUrl", "keySet": true, "version"}`. The connection and key are tested (`/api/v1/status`, `/api/v1/auth/me`) before anything is saved (400 wrong key, 502 unreachable). Leave `apiKey` out to change only the address. With `"test": true` the same checks run (same errors) but nothing is saved and the LAN allowlist is unchanged → `{"ok": true, "baseUrl", "version"}` (the web app's "Test connection"). `{"remove": true}` → `{"configured": false}`. |
| GET | `/v1/discover/users` | owner, admin | → `{"users": [{"id", "name", "email", "username", "plexUsername", "jellyfinUsername"}], "accounts": [{"id", "username", "displayName", "seerrUserId", "suggested"}]}`. `suggested`: the one Seerr user whose username, Plex / Jellyfin name, display name or e-mail name equals the account's username or display name (case-insensitive), else null. |
| POST | `/v1/discover/map` | owner, admin | `{"accountId", "seerrUserId"}` (null unlinks) → `{"accountId", "seerrUserId"}`; the Seerr user must exist (400). `{"auto": true}` links every unlinked account that has exactly one suggestion → `{"linked": [{"accountId", "seerrUserId"}]}`. |

Objects:
- `Title`: `{"type": "movie"|"tv", "tmdb", "title", "year", "overview", "poster", "backdrop", "rating",
  "status": "available"|"partial"|"processing"|"pending"|"requestable", "requestStatus"?: "pending"|"approved"|"declined"|"failed"|"completed", "download"?: Download, "play"?: Item, "seasons"?: [{"season", "episodes", "name", "status"}]}`.
  `status` comes from Seerr's media status (5 available, 4 partially available, 3 processing, 2 pending; anything
  else is requestable). `requestStatus` is the newest request's state. Specials (season 0) are left out.
- `TitlePage`: a `Title` (with `seasons` for shows, each with its own `status`) plus `"genres": [name]`,
  `"tagline"`, `"date"` (`"YYYY-MM-DD"` release / first air date, or null), `"runtime"` (minutes, a show's episode
  length; or null), `"cast": [{"id", "name", "character", "photo"}]` (Seerr's order, the first 20; `photo` a TMDB
  `w185` URL or null), `"trailer": {"name", "url"}|null` (the first YouTube trailer, else teaser; the URL is always
  `https://www.youtube.com/watch?v=<key>`, built by the server from the video key), `"requests": [{"id", "status",
  "created", "by", "seasons"}]` (every request on the title, newest first; `by` is the Seerr user's display name,
  `created` an ISO time) and `"seerrUrl"` (the title on the household's Seerr, `<Seerr address>/<movie|tv>/<tmdb>`,
  for "Open in Seerr"). Shows also get `"seasonCount"`, `"episodeCount"`, `"networks": [name]` and `"showStatus"`
  (TMDB's, e.g. "Returning Series").
- `Person`: `{"id", "name", "photo", "knownFor": [Title]}` (a person in the search results; `knownFor` up to 10).
- `Request`: `{"id", "type", "tmdb", "status", "mediaStatus", "created", "seasons"? , "download"?: Download, "requestedBy": {"seerrUserId", "name", "accountId"?}, "title": Title|null}`.
  The request's `title` carries the request's own `download` and `mediaStatus` (the list is fetched fresh on every
  call; the title may come from the cache).
- `Download`: `{"percent": 0-100, "size", "sizeLeft", "eta": ISO time|null, "items"}`, only while Sonarr or Radarr
  is downloading the title. Seerr attaches the queue to its media objects (`mediaInfo.downloadStatus`, else
  `downloadStatus4k`: one entry per queue item, e.g. per episode); `size` and `sizeLeft` are bytes summed over the
  items, `eta` is the latest `estimatedCompletionTime`, `items` the number of queue entries. Clients show
  "Downloading N% · ~T" from it; poll `GET /v1/requests` (not cached) for live progress, since rows and titles are
  cached (10 minutes, 1 hour).
- `play`: the profile's catalogue `Item` (see "Catalogue") with the same TMDb id, when the title is in the catalogue
  (IPTV first, then the account's Plex / Jellyfin; titles a kids profile may not see are left out). `status` is then
  `"available"`. Clients show Play instead of Request.

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
- **Cache:** rows 10 minutes (each page), titles 1 hour, title pages 1 minute, searches 10 minutes (each page), at most 256 answers. `GET /v1/requests` looks up
  each distinct title once (cached ones not at all), at most 4 at a time; a title Seerr does not know (404) is
  not asked for again for 10 minutes and shows as `"title": null`. When Seerr cannot be reached
  (or answers 5xx), the last answer is served with `"stale": true`; with nothing cached the call answers 503. After
  a failure the server does not try Seerr again for 15 seconds, so pages stay fast. `POST /v1/requests` always
  checks Seerr's current state first and answers 503 while Seerr is down.
- **Play instead of Request:** every Title is looked up in the signed-in profile's catalogue by TMDb id
  (`Catalog.tmdb_finder`, set as `discover.local_finder` by the app); a match adds `play` and sets `status` to
  `"available"`. IPTV titles match when the provider sends a TMDb id; Plex and Jellyfin titles by their TMDb guid.
  `discover.availability_override(tmdb, type)` stays as a second, household-wide hook (no-op).
- **Privacy note:** `poster` and `backdrop` are full `https://image.tmdb.org/t/p/...` URLs, so clients load artwork
  straight from TMDB's public image CDN (no key, but TMDB sees the client's address and which artwork it loads), or
  through `GET /v1/discover/image?p=/<size>/<file>` (the part after `/t/p`), which the web app does: its CSP allows
  only images from its own origin. Then only the server talks to TMDB.
  Note: the home server's JSON envelope (`sign_images` in `tvbro_server/app.py`) rewrites every `poster` / `backdrop`
  URL in any answer, `/v1` included, into a signed `/img?u=<url>&s=<signature>` URL, so clients actually receive
  `/img?u=https%3A%2F%2Fimage.tmdb.org%2F...` for Discover titles. The web app takes the TMDB URL back out and loads it
  through `/v1/discover/image`; cast and people `photo` URLs are not rewritten.

## Catalogue: Home, Movies and Shows (Phase 5)

The redesign's Home, Movies and Shows screens. Server code: `web/server/orbit/catalog.py` (indexes, rows, routes)
and `web/server/orbit/franchise.py` (IPTV film collections). All routes are `GET`, signed in, and answer for the
**session's profile** (409 `"no profile selected"` without one).

Sources: the household's IPTV films and series (the playlists' VOD catalogues, as the web app already loads and
stores them), the signed-in **account's** Plex / Jellyfin libraries (`/v1/media` connections; every profile of the
account sees the same media-server user), and the profile's My list, film resume points and show progress.

`Item`:
`{"key", "kind": "movie"|"show", "title", "year", "poster", "backdrop", "rating", "genres": [..], "source": "iptv"|"plex"|"jellyfin",
"added", "released", "colors": [c1, c2]|null, "progress": {"secs", "dur"}|null, "id", "conn", "locked", "next"?}`

- `key`: the My list / resume key. IPTV: `m|p0|7` (film), `s|p0|7` (show), as the TV and web already use.
  Plex / Jellyfin: `m|<connection id>|<item id>`, `s|<connection id>|<item id>` (the middle part is a `/v1/media`
  connection id instead of a playlist id `p<N>`). Put an Item in My list with exactly this key.
- `id`: the item's own id: the IPTV VOD id of `/api/vod/*` and `/v1/progress` (`7`, or `p1~7` for other playlists),
  or the media server's item id. `conn`: the media connection id (Plex / Jellyfin), else `null`. Play IPTV with `id`,
  Plex / Jellyfin with `POST /v1/media/playback {"conn", "id"}`.
- `title`: a trailing `(YYYY)` is dropped. `year`: integer or null. `rating`: 0–10 (one decimal) or null.
  `added`: Unix seconds (the provider's `added` / series `last_modified`, Plex `addedAt`, Jellyfin `DateCreated`)
  or null. `released`: `"YYYY-MM-DD"` when known, else null (sorting falls back to `year`).
- `poster` / `backdrop`: IPTV artwork comes as the server's signed `/img?...` URLs (like the rest of the web API);
  Plex / Jellyfin as `/v1/media/image?...`. IPTV films have no backdrop; IPTV series often do.
- `colors`: the two backdrop colours (`"#rrggbb"`) for the colour-matched background, from Plex's
  `UltraBlurColors` (top-left, bottom-right) when the server sends them; otherwise `null` and clients compute
  them from the image (Palette on Android, canvas on web).
- `progress`: a film's resume point (the profile's resume scope, or Plex / Jellyfin's own); a show's next episode
  (`secs` is where that episode stopped, 0 if not started). `null` otherwise.
- `next` (shows only): `{"id", "season", "num", "title"}` of the next episode to watch, or null.
- `locked`: true for an IPTV item in a VOD category locked with the parental PIN (playing still asks for it).

| Path | Query → Answer |
|---|---|
| `/v1/catalog/home` | → `{"hero": [Item], "rows": [Row]}`: rows `continue` (films with a resume point and shows with a next episode, newest first), `mylist`, `recent`, `released`, up to two `genre:<name>`, `unwatched`. Films and shows mixed. |
| `/v1/catalog/movies/recommended`, `/v1/catalog/shows/recommended` | → `{"hero": [Item], "rows": [Row]}`: rows `continue`, `recent` (Recently added), `released` (Recently released), up to three `genre:<name>` ("Top in Drama"), `unwatched` (Top unwatched). |
| `/v1/catalog/movies/browse`, `/v1/catalog/shows/browse` | `?source=all\|local\|iptv` (also `plex`, `jellyfin`; default `all`) `&genre=` (a name from `genres`, case-insensitive) `&sort=added\|released\|title\|rating` (default `added`) `&offset=` (default 0) `&limit=` (1–200, default 60) → `{"total", "genres": [..], "items": [Item]}`. `total` counts every match; `genres` lists the genres of the kind and source (before the genre filter), A to Z. 400 for an unknown source or sort, or limit / offset out of range. |
| `/v1/catalog/movies/collections`, `/v1/catalog/shows/collections` | → `{"collections": [Collection]}`, A to Z |
| `/v1/catalog/collections/{id}` | → `{"collection": Collection, "items": [Item]}` in release order. 404 when the id is unknown *for you* (another account's Plex collection, or one a kids profile may not see). |

- `Row`: `{"id", "title", "items": [Item]}`, at most 24 items. Rows without items are left out. Ids: `continue`,
  `mylist`, `recent`, `released`, `genre:<genre in lower case>`, `unwatched`.
- `hero`: up to 6 Items for the cinematic header: recently added ones with a backdrop, best rated first.
- `Collection`: `{"id", "name", "count", "poster", "backdrop", "colors", "source", "kind": "movie"|"show"}`. Artwork
  is the collection's own (Plex / Jellyfin, TMDb) or that of its first item. Ids are opaque (`[A-Za-z0-9._-]`).

How rows are chosen:
- **Recently added**: newest `added` first (items without one are left out). **Recently released**: newest `released`
  / `year` first.
- **Top in <genre>**: best rated items of a genre. The genres are the profile's most watched ones (genres of films it
  has watched or started and shows it has watched episodes of), filled up with the largest genres of the catalogue;
  a genre needs at least 3 rated items.
- **Top unwatched**: best rated items the profile has not watched or started.
- **Collections**: Plex collections and Jellyfin box sets as the server has them. IPTV films are grouped by a shared
  TMDb collection when the provider sends TMDb ids and Seerr is set up (Seerr's movie details carry the
  collection; looked up in the background, one film at a time, and kept in `catalog-tmdb.json` in the data folder:
  ids and names only). Other IPTV films are grouped by title, conservatively: `<Base> and the …` (3+ titles),
  `<Base>: …`, numbered sequels (`<Base> 2`, `II`, `Part 2`, `Vol. 2`) and the film titled `<Base>` itself; the base
  needs two significant words (one is enough only for a numbered series with both a 2 and a 3), and a group needs
  2+ different titles. IPTV shows are not grouped.

Kids profiles: IPTV items in VOD categories locked with the parental PIN are **left out** of every answer (rows,
hero, browse totals and genres, collections) while a PIN is set. Other profiles get them with `"locked": true`.
Plex and Jellyfin apply their own user's restrictions; the catalogue adds no filter for them.

Speed: the server keeps an index of the IPTV catalogue (every sort order, per-genre top lists, collections) and
rebuilds it in the background within a minute of a playlist refresh. An account's Plex / Jellyfin libraries are
fetched in the background (again after 10 minutes; "continue watching" after 1 minute); the first request of an
account waits up to 3 seconds for them, later ones never wait. Answers are cached per profile for 30 seconds,
keyed by the profile state's revision, so a change to My list or a resume point shows at once. With 20 000 titles
a warm request takes a few milliseconds (`test_orbit_catalog.Benchmark`).