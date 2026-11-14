# Orbit demo setup

A self-contained demo for trying Orbit and taking presentation screenshots, on the web app and on a TV, with no
real provider, titles or accounts. **Everything is fictional:** channel names, programmes, films, shows, plots,
cast names, artwork and video are all made up or generated here.

| File | What it is |
|---|---|
| `demo_provider.py` | A fake Xtream Codes provider (Python standard library only). |
| `run_demo.ps1` | Starts the provider and an Orbit server from this repo, then sets up a demo household through the server's API. |
| `demo_server.py` | The older tvBro demo provider (ffmpeg-drawn artwork with titles, M3U). Kept as it was; Orbit's demo uses `demo_provider.py`. |
| `../../web/tests/test_demo_provider.py` | Checks the provider's answers with the server's own parsers. |

## Quick start (Windows)

```powershell
powershell -ExecutionPolicy Bypass -File tools\demo\run_demo.ps1                      # generates a password and prints it
powershell -ExecutionPolicy Bypass -File tools\demo\run_demo.ps1 -Password "<choose one>" # or choose one
# ... use it ...
powershell -ExecutionPolicy Bypass -File tools\demo\run_demo.ps1 -Stop
```

Then open http://127.0.0.1:8760 and sign in as **demo** with the password the script printed (or the one you
passed). The profiles are **Demo**, **Kids** (a kids profile) and **Sam**.

What the script does:

1. Starts `demo_provider.py` on `127.0.0.1:8795` (the first start encodes the test video, a few seconds).
2. Starts the Orbit server (`web/server/run.py`) on `127.0.0.1:8760` with a scratch data folder,
   `%TEMP%\orbit-demo-<Port>` (`orbit-demo-8760` by default). The folder is **wiped on every start** (use `-Keep` to reuse it). The environment is
   `TVBRO_BIND=127.0.0.1`, `TVBRO_PORT`, `TVBRO_DATA`, `TVBRO_STATIC=web\frontend` (the server also serves the
   web app) and `TVBRO_ALLOW_LOOPBACK_SOURCES=1` (so the server accepts the loopback provider). The encryption key
   is a throwaway `key.bin` in the scratch folder.
3. Through the server's own HTTP API, as the tests do: `POST /v1/setup` with the one-time token from
   `orbit-setup-token` (creates the owner **demo**, with a profile named Demo), `POST /v1/profiles` for Kids and
   Sam, and `POST /api/signin` with the provider's Xtream address. Then it waits until `/v1/catalog/home` has rows.
4. Prints the address, the login, the log folder (`%TEMP%\orbit-demo-<Port>-logs`) and how to link a TV.

The processes run in the background (process IDs in `%TEMP%\orbit-demo-<Port>.pids`); `-Stop` ends them and their
children (`taskkill /T`). Everything is kept per server port, so two demos can run side by side: `-Stop -Port 8771`
stops only the one on 8771 (without `-Port`, the default 8760).

Options: `-Password`, `-Port` (default 8760), `-ProviderPort` (default 8795), `-DataDir`, `-Keep`, `-NoVideo`, `-Stop`.

**The demo password is never written to a file.** When the script generates one it only prints it in that
console. Use a throwaway password, as the household exists only for the demo.

### Web player libraries

The web player needs `web/frontend/vendor/hls.min.js` and `mpegts.min.js`. They are not committed (see
`web/README.md`). Copy them in from a server install or another checkout. The script warns when they are missing.
Without them the catalogue still works, but nothing plays in the browser.

## Linking a TV (ADB)

With the TV connected over ADB (USB or `adb connect`):

```powershell
adb reverse tcp:8760 tcp:8760    # the Orbit server
adb reverse tcp:8795 tcp:8795    # the provider: artwork is loaded from the provider's own address
```

On the TV, choose the server `http://127.0.0.1:8760`. The TV shows a code. Approve it in the web app while signed
in as **demo** and pick a profile. Hidden provider logins are on by default, so the TV plays through the server's
Xtream facade (`/xt/...`). Posters and backdrops are fetched from `http://127.0.0.1:8795`, so you need the second
`adb reverse` for artwork.

## The provider alone

```powershell
py -3 tools/demo/demo_provider.py [--port 8795] [--bind 127.0.0.1] [--no-video] [--video-dir DIR]
```

- **Xtream login:** `demo` / `demo`. This is a made-up fixture login for a fake provider, not a credential.
  Change it with `--user` and `--password`.
- **player_api.php:** user_info (no action), `get_live_categories`, `get_live_streams`, `get_vod_categories`,
  `get_vod_streams`, `get_vod_info`, `get_series_categories`, `get_series`, `get_series_info`, `get_short_epg` and
  `get_simple_data_table`. `category_id` filters work.
- **xmltv.php:** a guide from 6 hours ago to 24 hours ahead. The schedule is fixed for each channel and day, so it
  doesn't change between requests.
- **get.php:** an M3U of the live channels.
- **Catalogue:**
  - **Live:** 60 channels in News, Sports, Entertainment, Movies, Kids and Locals, plus about 200 groups named the way
    big providers do ("Sports | NHL Teams", "UK | News", "Ambiance | News"…) with two channels each (six in
    "Sports | NHL Teams", whose guide has overlapping entries on purpose). Every third channel has catch-up.
  - **Films:** 150, over eight categories. They include franchises that Orbit's collections pick up: *The
    Hollowmere Saga* (8 films), *Copper Sky* (3), *Fathom Station* (3), *Neon Orchard* (2) and *Starfall Ridge* (2).
  - **Shows:** 40, each with 1 to 4 seasons of 6 to 10 episodes.
  - **Details:** genres, years, ratings, plots, cast and release dates. `added` dates are spread over about eight
    months before today. TMDb ids are left empty.
- **Artwork:** `/art/{poster|backdrop|still|logo}/<slug>.png`. These are PNGs drawn with zlib and struct, with no
  text: a two-colour gradient, soft discs, a horizon band and a darker bottom edge. Each title gets its own palette
  (seeded from its name), so the colour-matched backgrounds have something to work with. Sizes are posters 400×600
  (2:3), backdrops 1280×720 (16:9), episode stills 480×270 and channel logos 256×256.
- **Video (needs ffmpeg on PATH):** a 30-second test pattern of SMPTE bars, a moving white bar and a quiet
  440 Hz tone, encoded once into `%TEMP%\orbit-demo-video` and kept for later starts.
  - Live `.ts`: the clip looped in real time with continuous timestamps (`ffmpeg -re -stream_loop -1 -c copy`, one
    light process for each open stream, which ends when the client goes away).
  - Live `.m3u8`: a sliding HLS window over the clip's 2-second segments, with a discontinuity at each wrap.
  - Films, episodes and catch-up: the MP4 or TS file, with byte ranges.
  - **Without ffmpeg,** every stream answers 404 and everything else still works.

Every channel and title plays the same test clip. Artwork URLs use the `Host` the client asked with, so they work
through `adb reverse` and from the server alike.

## Test

```powershell
py -3 -m unittest web.tests.test_demo_provider -v
```

This runs the provider in the test process and parses its answers with `tvbro_server.parsers`. It checks the
catalogue size, unique titles and ids, no TMDb ids, `added` dates spread over months, the 8-film saga, the guide
and the PNG shapes. When ffmpeg is on PATH, it also checks MP4 ranges, the HLS window and the live TS.
