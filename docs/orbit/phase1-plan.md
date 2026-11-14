# Orbit — Phase 1 plan (proposal) and test designs

**Goal (handoff v4 Phase 1 exit):** reproducible deployment; at least two separate accounts can sign in from the TV and from a browser, pick their own profiles, see independent favourites and history, and can't reach each other's data. Auth and session tests pass, no credentials are exposed, and rollback has been tested. **tvBro's IPTV playback keeps working unchanged.**

## Needs owner approval before starting
1. Create the private repository (host: GitHub private? self-hosted Gitea?) and push the pinned tvBro copy plus the `upstream` remote.
2. Orbit application ID (proposal `<your-domain-reversed>.orbit`; decide after the name check) and a new signing key kept outside the repo.
3. Installing debug builds on the BRAVIA over network ADB, as a separate app.

## Work, in order (each a small reviewable PR into `orbit/main`)
1. **Repo setup:** rebrand strings, README, NOTICE kept, new app ID, CI green (fix the Windows timestamp flake).
2. **`orbit.db` and migrations:** migration runner, upgrade tests, backup before migrating.
3. **Accounts, profiles, sessions:** `/v1/auth/*`, `/v1/accounts`, `/v1/profiles`, invitations by the owner. The existing household password becomes the owner account.
4. **Per-profile state:** My list, favourites, resume and series progress move to profile-scoped tables; the old `/api/state` serves the signed-in profile so the current web UI keeps working.
5. **Web:** sign-in and profile picker screens (dark theme, focus everywhere, remote-friendly).
6. **TV pairing:** the TV shows a code and QR → a signed-in user approves → the device session is stored encrypted on the TV.
7. **Android sync:** pull/push of favourites, My list and resume with versions; newest change wins (setting).
8. **Deploy:** Docker Compose for the Debian VM (volumes, health check, key outside the data volume), plus backup/restore and rollback docs.

## Phase 1 acceptance tests
- A1 Create accounts **Alex** and **Sam** (synthetic), each with two profiles. Sign in as Alex on web; Sam's favourites, history and profiles are invisible, and direct `/v1` calls with Sam's IDs return 404/403.
- A2 Pair the TV as Alex/Kids; favourite a channel on TV → it appears on web within one sync, under Alex/Kids only.
- A3 Resume a VOD at 12:31 on TV, open it on web → resume offered at 12:31 (±10 s).
- A4 Revoke the TV's device session on web → the TV's next call is rejected and it returns to pairing.
- A5 Restart the server and reboot the TV → sessions and state survive.
- A6 A migration from a tvBro-only `state.db` keeps the household's existing My list and resume under the owner account.
- A7 `check_private.py`, the full test suites and a log/traffic scan show no credentials or tokens.
- A8 Roll back to the previous image with the pre-migration backup → the old version runs.

## Phase 2 test designs (prepared now, run later)

**Buffering baseline (same channel, same time):**
- Pick 5 authorized channels: mixed HLS / MPEG-TS, SD/HD/HEVC, one known to be flaky.
- For each, run 30-minute sessions on the BRAVIA in this order, repeated at two times of day:
  1. tvBro, current buffer
  2. tvBro, larger buffer
  3. TiviMate (observable outcome only)
  4. web relay in a desktop browser
- Record time to first frame, stall count, total stall time, longest stall, reconnects, bitrate, and channel-change time (10 zaps).
- Classify each stall as upstream or client using the player error type and a parallel `ffprobe` on the same URL from the server.
- Only then tune the buffer.

**Stream-limit tests:**
- Set the provider cap to N=1 and N=2, then try each of these and check the cap holds:
  - live plus preview on the same TV
  - live on the TV plus VOD on web
  - a recording plus live
  - two TVs on the same channel
  - app killed mid-stream (lease expires within TTL)
  - player retry storm
  - a relay request without `sid`
- Check the ledger, and where possible the provider-reported active connections. Expect a clear "streams in use" message and no silent failure.
