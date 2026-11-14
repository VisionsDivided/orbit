# Changelog

All notable changes to Orbit are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions will follow
[Semantic Versioning](https://semver.org/) once Orbit has its first release.

Orbit is based on [tvBro](https://github.com/kmmarin/tvBro) (Apache License 2.0, Copyright 2026 The tvBro
authors). Orbit keeps tvBro's LICENSE and NOTICE; Orbit's own changes are Copyright 2026 The Orbit authors under
the same licence. This file lists only what Orbit added or changed on top of tvBro (fork point: tvBro
release v0.1.0, commit `e5d1897`). For tvBro's own features see its README and release notes.

## [Unreleased] - 0.1.0-dev

### Orbit identity
- Orbit has its own app ID (`io.github.visionsdivided.orbit`) and name, so it installs beside tvBro. Every build is
  called "Orbit"; development builds keep their own app ID so they can install beside a release build.
- Orbit logos everywhere: app icons, the Android TV banner, the side menu, the sign-in page, the favicon.
- tvBro's licence, notices and credits are kept; the README says Orbit is built on tvBro.

### Home server
- The home server keeps its own database (`orbit.db`) with numbered, automatic upgrades.
- New `/v1` API for Orbit's apps, documented in `docs/orbit/API-v1.md`.
- Linked TVs fetch the household's playlists from the home server, so a provider login is typed once, on the
  server, instead of on every TV.
- "Who is streaming now": the server can show which devices are using the provider's streams
  (`GET /v1/streams`), so the household's stream limit is easier to keep.

### Accounts and profiles
- Household accounts (owner, admin, member) with sign-in and sessions.
- Profiles inside an account with a "Who's watching?" picker in the web app.
- Each profile has its own My list, favourites, film resume points and show progress.
- Settings ▸ Accounts in the web app: set up the owner account with a one-time code from the server, add accounts
  and profiles, see your devices, change your password; owners and admins can reset a forgotten password.

### TV pairing and sync
- Link a TV to the home server with a short code shown on the TV and typed into the web app.
- The web app shows which TV is asking (name, address, how long ago) before you approve it.
- The TV link screen uses Orbit's port 8750 when no port is typed.
- My list, film resume points and show progress (episodes and Continue watching) sync between the TV, the web app
  and other devices.
- Show progress is saved in one go per device, and an episode never jumps to another show by mistake.

### Playback on the TV (Phase 2)
- Playback stats on the TV: start-up time, stalls, bitrate and reconnects.
- One stream count across TVs, browsers and recordings, so Orbit warns before going over the provider's limit.
- Settings ▸ Playback ▸ Live stream format: MPEG-TS (default) or HLS. HLS was much steadier with one provider
  that kept buffering (see `docs/orbit/buffering-2026-11-14.md`).
- Fixed: TV Settings opens the page you were last on and focuses it correctly.

### Media servers (Phase 3)
- The home server can connect to Plex and Jellyfin servers through one common connector (`/v1/media` routes).
  Server side only for now: there is no screen for it yet, and it has been tested against mock servers.
- Media server logins are stored in an encrypted token vault on the server.
- Media servers on your home network are allowed one by one (a per-host allowlist) instead of opening the whole
  local network.

### Discover and requests through Seerr (Phase 6)
- The home server can talk to Seerr (Overseerr / Jellyseerr): browse and search titles and request films and
  shows (`/v1/discover/*`, `/v1/requests`). Server side only for now: no screen yet.
- Seerr settings are stored encrypted; results are cached and titles tidied up for display.

### Desktop app on macOS (in progress)
- The desktop app now knows about macOS: finds libmpv from Homebrew, MacPorts or inside the app, keeps its secret
  key in the macOS Keychain, uses the usual `~/Library` folders, writes a log file, and lists recording drives
  from `/Volumes`.
- `Orbit.app` in an unsigned DMG, with its own bundle ID and icon.
- A GitHub Actions job builds and tests the macOS app on Apple Silicon and Intel and produces the DMG.
- Known limitation: on macOS the video picture is not shown yet (sound only); embedding the video is the next
  step. Nothing has been run on a real Mac yet.

### Security review fixes
- Provider logins are only handed to TVs linked to an owner or admin account; a TV linked to a member account is
  asked to have the owner or an admin link it (behaviour change).
- Linked TVs get viewer rights, never admin rights.
- Sign-in attempts are limited per username as well as per address; each account keeps at most 20 sessions (extra
  sign-ins end that account's own oldest session).
- Setting up accounts signs out the old tvBro-era admin and viewer sessions.
- The Seerr API key must be typed again when the Seerr address changes.
- Login headers never follow a redirect to another host, port or scheme, on the server and in the TV app.
- A deleted profile's data folder can't be brought back by a late request.
- Web pages send a Permissions-Policy header on the main page too.
- Pending TV links that expired are tidied up on every request.

### Infrastructure and Docker
- Docker image and a Docker Compose example for running the home server (see `docs/orbit/DEPLOY.md`).
- Provider host names are fetched in lower case, which fixes TLS failures with some providers.
- Web app logos live in `brand/` so they don't clash with the server's image proxy.

### Fixed
- My list keeps its order when two changes have the same timestamp.
- An intermittent desktop test failure (TV screen resume) is fixed.
