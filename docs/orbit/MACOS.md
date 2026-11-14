# Orbit desktop on macOS

Status (2026-11-14): **in progress** on branch `orbit/macos`. Everything below was written and unit-tested on a
Windows PC; nothing has run on a Mac yet. The first MacBook session should go through "Steps on the MacBook" and the
test checklist, then the video spike.

## What is done (and how it was checked)

| Area | What | Checked on Windows |
|---|---|---|
| OS detection | `Os` / `Platform` (`Os.kt`): one place; "Darwin" is not taken for Windows | `OsTest` |
| libmpv loading | Mach-O headers (thin 32/64-bit, both byte orders; universal 32- and 64-bit fat records; Java class files are not mistaken for fat files). Search order: the file chosen in the app, `Orbit.app/Contents/Frameworks`, `Contents/MacOS/lib`, `Contents/MacOS`, `/opt/homebrew/lib` (Homebrew, Apple Silicon), `/usr/local/lib` (Homebrew, Intel), `/opt/local/lib` (MacPorts), each `libmpv.2.dylib` then `libmpv.dylib`, then the dyld search for `mpv`. A library for the wrong processor (an arm64 Homebrew lib under an Intel/Rosetta Java) is refused with a clear message. The missing-library screen says `brew install mpv` | `MachOTest` (synthetic headers, search order, fallbacks) |
| Secrets | The AES key of the encrypted secrets goes into the login Keychain (generic password, service `tvBro`, account `secrets-key`) through `/usr/bin/security`; fallback: `key.bin` (0600) as on Linux | `MacKeychainTest` with a fake `security` tool |
| Folders | `~/Library/Application Support/tvBro` (settings, secrets), `~/Library/Caches/tvBro`, `~/Library/Logs/tvBro/tvbro.log` (1 MB + one old file), recordings `~/Movies/tvBro`; 0700 / 0600 like Linux | `AppDirsTest` |
| Video | Sound only for now: macOS does not embed the video by default (see "Video" below). `TVBRO_MAC_VIDEO=wid` tries mpv's `wid` for the spike. Hardware decoding choices: auto-safe (VideoToolbox), videotoolbox, no | `MpvVideoTest` |
| Drives | Recording drives listed from `/Volumes` (start-up disk left out) | compiles only |
| Packaging | `packageDmg`: `Orbit.app` in a DMG, bundle id `io.github.visionsdivided.orbit.desktop`, icon `desktop/packaging/orbit.icns` (made with Pillow from `branding/orbit-app-icon.webp`), version 1.0.0 (jpackage refuses 0.x on macOS). Unsigned | Gradle task list only; Windows packaging unchanged |
| CI | `.github/workflows/desktop-macos.yml`: tests on macos-latest (arm64) and macos-15-intel (Intel) with a Homebrew libmpv, unsigned DMG, "no libmpv in the app" check | runs on every push to github.com/VisionsDivided/orbit; the arm64 DMG builds |

The folder name stays `tvBro` (as on Windows; `AppDirs.NAME` is the one place to change for a rename). The Mac app
itself is called Orbit.

### Why the Keychain goes through `/usr/bin/security`

- A Keychain item trusts the program that created it. Created by `security`, it is read back by the same
  Apple-signed binary that never changes, so the user is not asked again after an app update. Created through
  Security.framework from the app (JNA `SecItemAdd`), the trusted program would be Orbit's unsigned Java launcher,
  which changes with every build: macOS would ask for the login password (or refuse) after each update.
- No secret on a command line: `security add-generic-password -w <key>` would show the key in `ps` while it runs.
  The key is written as a command line on the **stdin** of `security -i` (interactive mode), so `ps` shows only
  `security -i`; it is read from the stdout of `find-generic-password -w`. After storing, the key is read back and
  compared; if that fails, the key file is used.
- Trade-off (the same as `secret-tool` on Linux): any program running as the same user can ask `security` for the
  item without a prompt. A signed app could later move to `SecItemAdd` with its own code signature as the trusted
  application (no prompts, other programs asked); that needs the Developer ID (see Signing).
- Only exit code 44 (item not found) creates a new key. A locked Keychain, a denied prompt or anything else is an
  error, so old secrets are never orphaned by a new key.

## Steps on the MacBook

1. Tools:
   ```
   xcode-select --install            # git, clang (once)
   brew install mpv                  # libmpv.2.dylib in /opt/homebrew/lib (Apple Silicon) or /usr/local/lib (Intel)
   brew install --cask temurin@17    # JDK 17 (or any JDK 17; check: /usr/libexec/java_home -v 17)
   export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
   ```
2. Code: `git checkout orbit/macos`.
3. Tests: `./gradlew :desktop:test` (all should pass; `MpvLoaderTest.realLibmpv` runs against the real library with
   `TVBRO_TEST_LIBMPV=$(brew --prefix)/lib/libmpv.2.dylib ./gradlew :desktop:test`; the real Keychain test runs with
   `TVBRO_TEST_KEYCHAIN=1` and removes its test item afterwards; it skips itself when a real tvBro key exists).
4. Headless libmpv check: `./gradlew :desktop:run --args="--selftest https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"`
   (a public test stream from `desktop/testdata/test.m3u`; prints the resolution, codecs and `hwdec` it played; see
   desktop/README.md for the other test options, e.g. `--render <png>` for an off-screen picture of the TV screen).
5. Run the app: `./gradlew :desktop:run`. Expect the UI, sound, and **no picture** (by design until the video spike).
6. Package: `./gradlew :desktop:packageDmg` writes `desktop/build/compose/binaries/main/dmg/Orbit-1.0.0.dmg`. Open it
   and drag Orbit to Applications.
7. Gatekeeper (the app is not signed): the first open says Apple cannot check it.
   - macOS 15 (Sequoia) and later: try to open it once, then System Settings > Privacy & Security > "Open Anyway"
     (scroll down), confirm with the password.
   - macOS 14 and older: Control-click (right-click) Orbit in Applications > Open > Open.
   - Or in Terminal, for this app only: `xattr -dr com.apple.quarantine /Applications/Orbit.app`.
   A DMG built on the same Mac is not quarantined, so this applies to copies downloaded or sent from elsewhere.
8. Logs: `~/Library/Logs/tvBro/tvbro.log` (Console.app shows it too). mpv log for a session:
   `TVBRO_MPV_LOG=/tmp/mpv.log ./gradlew :desktop:run` (it can contain stream URLs; delete it afterwards).

### Test checklist (MacBook)

- [ ] `./gradlew :desktop:test` green, including `realLibmpv` with `TVBRO_TEST_LIBMPV` and `realKeychain` with
      `TVBRO_TEST_KEYCHAIN=1`
- [ ] `--selftest` plays a stream headless through Homebrew's libmpv
- [ ] App starts from `gradlew :desktop:run` and from `Orbit.app`; the menu bar and Dock say Orbit; the icon looks right
- [ ] Without mpv (`brew unlink mpv`): the "Video library missing" screen shows the brew steps; "Choose file…"
      opens a file dialog; picking `libmpv.2.dylib` from `/opt/homebrew/Cellar/mpv/<v>/lib` loads it; `brew link mpv` again
- [ ] Add a source with a login: first save makes a Keychain item "tvBro" (Keychain Access.app, login keychain) with
      no prompt; restart: playlists still there; Settings shows "macOS Keychain"; `~/Library/Application Support/tvBro`
      is 0700 and its files 0600 (`ls -la`)
- [ ] Keychain locked (`security lock-keychain`), start the app: playlists not readable, message shown, no new key
      made (unlock and restart: they are back)
- [ ] Sound plays for live, film, episode; no crash on channel change, full screen, back to preview
- [ ] Keys: arrows, Enter, Esc, Space; F11 / F (full screen) — on a MacBook F11 needs the fn key and may be taken by
      "Show Desktop", F works
- [ ] A recording to `~/Movies/tvBro` and to a USB stick (listed from `/Volumes`)
- [ ] External player: `/Applications/VLC.app/Contents/MacOS/VLC` (the plain `vlc` command is usually not on PATH)
- [ ] Intel Mac too, if one is around (or the macos-15-intel CI job)

## Video (the biggest risk) — spike plan

Today the video is drawn by mpv into the native window of an AWT `Canvas` (`wid`): the HWND on Windows, the X11
window on Linux. That does not carry over to macOS:

- Since Java 7, AWT on macOS draws with Core Animation layers; a heavyweight `Canvas` has no `NSView` of its own.
  JAWT on macOS offers a `CALayer` to attach a sublayer to (`JAWT_SurfaceLayers`), not a view.
- mpv's `wid` on macOS expects an `NSView*`. What JNA's `Native.getComponentID` returns there is not one, and a wrong
  pointer as `wid` can crash the whole process. So `MpvVideo.windowId` does not ask for it by default.

### Options

**A. `wid` with an NSView.** Already wired for a quick check: `TVBRO_MAC_VIDEO=wid ./gradlew :desktop:run` (run it
from Terminal; a crash or a black box are the likely results). Making it work would need native glue (JNI /
Objective-C) to create an `NSView`, put it in the window's view hierarchy above the Compose layer and keep its frame
in step with the `SwingPanel`, plus a `gpu-context` that supports embedding on macOS (`macvk`, MoltenVK). Fragile
across macOS and mpv releases. `vo=libmpv` is not an option here: it means "the host renders", that is option B.

**B. mpv render API (recommended).** mpv draws frames on request into memory or a GL target; the app shows them as a
Compose image. Variants:
- **B1, software render into a Compose image (recommended first):** `vo=libmpv`, `mpv_render_context_create` with
  `MPV_RENDER_PARAM_API_TYPE = "sw"`, render into a direct `ByteBuffer` (`SW_SIZE`, `SW_FORMAT` "bgr0", `SW_STRIDE`,
  `SW_POINTER`), wrap it as a Skia image and draw it in the video box. Pure JNA, no native code, no GL. Compose menus
  and the OSD draw over the video (today they are separate windows because the heavyweight `Canvas` covers
  everything), and the shrink-into-preview animation becomes an ordinary Compose animation. Cost: one CPU copy per
  frame (1080p at 50 fps is about 400 MB/s, fine on Apple Silicon) and hardware decoding with copy-back
  (`videotoolbox-copy` / `auto-copy-safe`).
- B2, OpenGL render into an offscreen FBO (CGL context via JNA), then read the pixels back: more code for about the
  same copy cost, since Compose on macOS draws with Metal and cannot share a GL texture; OpenGL is deprecated on macOS.
- B3, `CAOpenGLLayer` / `CAMetalLayer` attached through JAWT surface layers: zero copy, but Objective-C glue and a
  layer that sits above Compose again (same overlay problem as today). Only if B1 is too slow.

**C. mpv as a separate borderless window** kept on top of the app window and moved with it. Simple to start, but the
window lags behind moves and resizes, has trouble with Spaces / full screen and focus, Compose menus cannot appear
above it, the preview animation cannot be smooth, and syncing it needs mpv's IPC, which the hardening switches off.
Not recommended.

### Recommendation: B1, after a 10-minute look at A

Concrete steps:

1. MacBook, 10 minutes: run with `TVBRO_MAC_VIDEO=wid`; note crash / black / picture. Picture would be a surprise;
   stop here and harden it only if it is a clean success.
2. JNA bindings for `render.h` in a new `MpvRender.kt`: `mpv_render_context_create(ctx**, mpv*, params*)`,
   `mpv_render_context_set_update_callback`, `mpv_render_context_update` (returns flags; bit 0 =
   `MPV_RENDER_UPDATE_FRAME`), `mpv_render_context_render`, `mpv_render_context_free`; `mpv_render_param` as a JNA
   `Structure` array `{int type; void* data}` ending with type 0. Check the param numbers against the `render.h` of the
   installed mpv (API_TYPE 1; SW_SIZE 17, SW_FORMAT 18, SW_STRIDE 19, SW_POINTER 20 in current mpv).
3. `MpvVideo.options(Os.MAC, …)`: `vo=libmpv`, `hwdec=auto-copy-safe` (set before `mpv_initialize`); create the render
   context after `mpv_initialize`, free it **before** `mpv_terminate_destroy`.
4. A render thread: the update callback (mpv's thread; it must not call mpv) only signals; the thread calls
   `mpv_render_context_update`, and on a new frame renders into one of two direct buffers sized to the video box in
   pixels (capped at the video's own size), then publishes it to Compose (`ImageBitmap` from a Skia `Bitmap` with
   `installPixels`, or `Image.makeRaster`), drawn with `Canvas`/`Image` in `VideoHost` instead of the `SwingPanel`.
5. `VideoHost` on macOS: the image composable at the same animated bounds; mouse clicks as before.
6. Measure on the MacBook: CPU of Orbit (Activity Monitor) for 720p/1080p live and a 4K film; dropped frames
   (`mpv` property `frame-drop-count`); A/V sync (`avsync`). Target: under ~40% of one core for 1080p50.
7. If B1 works well it can later replace `wid` on Windows and Linux too (Compose overlays everywhere); out of scope
   for the port.

Spike test checklist:
- [ ] Picture in the preview and full screen, correct aspect, no tearing in a pan
- [ ] Shrink / grow animation smooth; menus and the PIN question visible above the video
- [ ] Channel change, pause / seek, subtitles (ASS drawn by mpv), audio delay
- [ ] Retina: sharp picture at 2x (render at the box's pixel size, not points)
- [ ] CPU and frame drops measured as in step 6; window resize while playing; closing the app while playing (no crash
      in `mpv_render_context_free`)

## Signing and notarization (documentation only)

Needs a paid Apple Developer account (Developer ID Application certificate). Then in `desktop/build.gradle.kts`,
`macOS { signing { sign.set(true); identity.set("…") }; notarization { appleID.set(…); password.set(…); teamID.set(…) } }`
with the values from environment variables or a gitignored `local.properties`, never committed, and
`./gradlew :desktop:notarizeDmg`. With the hardened runtime that signing turns on, the app may no longer load a
Homebrew `libmpv.2.dylib` (library validation): it needs the entitlements
`com.apple.security.cs.disable-library-validation` and, for the JVM, `com.apple.security.cs.allow-jit` /
`allow-unsigned-executable-memory` (Compose's `entitlementsFile`). Test that loading again after signing.

## Risks

| Risk | Impact | Note / mitigation |
|---|---|---|
| Video embedding (`wid`) does not work on macOS | No picture | Default is sound only; spike plan above, B1 recommended |
| Render API CPU cost (B1) too high for 4K | Dropped frames | Cap the render size to the box; B3 (Metal layer) as the later zero-copy path |
| `security -i` behaves differently than expected (quoting, exit codes, prompts) | Secrets fall back to `key.bin` | Read-back check after storing; the real-Keychain test on the Mac shows it |
| Exit codes of `security` other than 44 for "not found" on some macOS version | Secrets unreadable until fixed | Error is shown, no key is replaced; check `realKeychain` and the locked-keychain item |
| Gatekeeper / quarantine on an unsigned app | Users cannot open it | Steps above; signing needs the paid account |
| Hardened runtime after signing blocks Homebrew libmpv | No video library | Entitlements above |
| Homebrew libmpv for the other processor (Rosetta Java, or Intel brew on Apple Silicon) | "built for …" message | Use an arm64 JDK on Apple Silicon; the loader explains it |
| Keys on a MacBook keyboard (no Menu key, F11 taken by the system, Cmd vs Ctrl) | Some shortcuts unreachable | F works for full screen; hold Enter / right click for menus; review on the Mac |
| jpackage needs version ≥ 1.0 on macOS | DMG version differs from 0.1.0 | `packageVersion = "1.0.0"` in the macOS block |
| AWT / Compose quirks on macOS (menu bar, full screen as its own Space, Retina scaling of the old Canvas) | Visual glitches | Checklist items above |
| libmpv licence | Not bundled, as on Windows | Homebrew's mpv is the user's own install; CI checks the app has no libmpv |
