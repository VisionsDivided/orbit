# Android TV UI: direction 5 ("Cinematic + top bar")

The TV app's redesign. The spec is DESIGN-SPEC-5.md and the mockup is `orbit-mockups.html` (#s5), both kept with the
Phase 0 mockups. This page says where the code lives, so several people can work on it at once without editing the
same files.

## Units

Inside `OrbitCanvas { }` **1 dp is 1 pixel of the 1920 × 1080 spec**. The density is set from the screen width, so a
BRAVIA at 1920 × 1080 (density 2) and a 1280 × 720 stick both show the same layout. Write the spec's numbers as they
are: `96.dp` side margin, `112.sp` hero title. Legacy tvBro screens, written in real dp, go inside `LegacyDensity { }`
(or `LegacyPage(top)` in the shell) until they are restyled.

## Packages (app/src/main/java/io/github/kmmarin/tvbro/…)

| Package / file | What | Owner |
|---|---|---|
| `ui/orbit5/` | Design system. Add new shared components here in **new files**. | shared (shell agent) |
| `ui/orbit5/Tokens.kt` | `O5` colours, `O5Type`, `O5Space`, `O5Shape`, `OrbitCanvas`, `LegacyDensity` | |
| `ui/orbit5/Focus.kt` | `Modifier.o5Focus(...)`: white outline, 1.07 scale, shadow, raised. Use it for every card and button (it also feeds `FocusMemory`) | |
| `ui/orbit5/TopBar.kt` | `TopBar`, `NavTab`, `Clock` | |
| `ui/orbit5/SubMenu.kt` | `SubMenuRow` (tabs plus Browse controls: `BrowseControls`, `BrowseSource`), `SubPill`, `Segment`, `Dropdown` | |
| `ui/orbit5/Backdrop.kt` | `CinematicBackdrop(BackdropSpec)`: mesh, masked artwork, vignette, 250 ms settle then a 900 ms cross-fade. `BackdropStill` for fixed previews. `Modifier.mesh`, `Modifier.vignette(dim)` | |
| `ui/orbit5/TitleColors.kt` | `MeshColors`; `TitleColors.of(url, catalogColors)`: the catalog's `colors`, else Palette on a 160 px copy, cached | |
| `ui/orbit5/Hero.kt` | `HeroBlock(HeroData) { buttons }`: the text cross-fades (600 ms) and the buttons stay composed, so focus is kept | |
| `ui/orbit5/Cards.kt` | `PosterCard`, `LandscapeCard`, `CardInfo`, `CardSize` | |
| `ui/orbit5/Rows.kt` | `O5Row(title) { items }`: a lazy row with focus restore and `FocusRoom` padding so the outline is not clipped | |
| `ui/orbit5/Controls.kt` | `O5Button`, `SourceBadge`, `MetaPill`, `ProgressBar`, `WatchedMark`, `Avatar` | |
| `ui/orbit5/ColorKeys.kt` | Colour-key dispatcher (`ColorKeys`, `ColorKeyActions`, `KeyAction`) and `ColorKeyLegend` | |
| `ui/orbit5/Images.kt` | `NetImage(url, ImgSize.X)`: images decoded at about their drawn size | |
| `ui/shell/OrbitShell.kt` | `OrbitShell(m)`: top bar, section routing, Back, Settings panel. `ShellFrame` is the same without the model (tests) | shell agent |
| `ui/home/` | `HomeSection`, `HomeFeed` (catalog home or local fallback) | shell agent |
| `ui/profiles/WhoIsWatching.kt` | Orbit and classic picker, `ProfilePicker`, `OrbitRing` geometry | shell agent |
| `ui/movies/` | Movies and Shows (`MoviesShowsSection`, views in `MoviesViews`, state in `MoviesFeed`), the title page (`DetailScreen`, Screen.DETAIL), title actions (`TitleActions`) | Movies / Shows / Detail agent |
| `ui/live/LiveTvSection.kt` | Live TV: sub-menu Channels · Guide · Recordings · My list, dim backdrop, the guide's colour keys | **Live TV / player agent** |
| `ui/live/EpgGrid.kt` | The EPG grid shared by Live TV ▸ Channels and the full guide (`EpgWindow`, `EpgActions`, `RowMarks`) | Live TV / player |
| `ui/live/LiveParts.kt`, `LiveUi.kt` | Info block (`LiveInfo`), group tabs, channel tile, LIVE pill, HLS tag, ● REC, `flatFocus` | Live TV / player |
| `ui/live/PlayerOverlay.kt` | Player overlay pieces: scrims, top line, info block, scrubber, "Try HLS?" chip, quick actions, `O5Popup` | Live TV / player |
| `ui/live/StreamPrompt.kt` | Stream-limit dialog (`StreamPromptCard`), used by `OrbitStreamPrompt` | Live TV / player |
| `ui/discover/` | `DiscoverSection` (TV and phone), `DiscoverFeed` (rows, requests, live progress); client in `orbit/DiscoverApi.kt` | Discover / Settings / phone agent |
| `ui/SettingsUi.kt` | Settings page: categories in the sub-menu, `SettingRow` / `ToggleRow` / `SegmentRow`, Profiles, Requests | Discover / Settings / phone agent |
| `ui/shell/PhoneShell.kt` | Phone 5b: `LocalIsPhone`, `LocalPhoneInsets`, `PhoneShell`, `PhoneFrame` | Discover / Settings / phone agent |
| `ui/orbit5/Phone.kt`, `StatusBadge.kt` | Phone bottom bar, header, buttons, cards; Discover status pills | shared |
| `ui/home/PhoneHome.kt` | Home on a phone | Discover / Settings / phone agent |
| `orbit/CatalogApi.kt` | Client for every `/v1/catalog` route (`home`, `recommended`, `browse`, `collections`, `collection`) | shared |
| `orbit/OrbitProfiles.kt` | Profiles, the picker state, picker style and "ask at start" (settings of this TV) | shell agent |

`Section` (AppModel.kt) gained `HOME` (now the default) and `DISCOVER`. Live TV is `TV`, and `RECORDINGS` and `MYLIST`
are its sub-pages. `SEARCH` opens from the search icon. MainActivity routes `Screen.LIST / SETTINGS / VOD` to
`OrbitShell` and `Screen.DETAIL` to `ui/movies/DetailScreen` (under the same top bar, via `ShellFrame`). The guide
and the live player are full-screen direction-5 screens (see "Live TV and the player"); link and add source are
still full-screen legacy screens.

## Focus rules (shell)

- **Up** from the top of a section lands on the top bar's **current** item, not the nearest one. This is the section
  group's `exit`. Sections don't have to do anything, but must not swallow Up at their top edge.
- **Down** from the bar returns to the item last focused in the page (`FocusMemory.restoreContent()`). Any item
  using `o5Focus` or the legacy `tvFocus` takes part. Items inside the bar don't count (`LocalInRail`).
- **OK** on a tab opens its section (`openTab`), then focus moves down into it after 300 ms.
- **Back**: from any section to Home. On Home with focus in the rows, Back goes up to the hero; on the hero it
  leaves the app. Screens that open on top (detail, guide, player) still handle Back themselves.
- Use `SafeFocus` + `o5Focus(focus = …)` for items the screen requests focus on, never a bare `FocusRequester` on a
  lazy item.

## Colour keys

```kotlin
ColorKeyActions(
    red = KeyAction("My list") { … }, green = KeyAction("Play trailer") { … },
    yellow = KeyAction("Mark watched") { … }, blue = KeyAction("More") { … },
)
```

Call it in the screen's composition; the lambdas may read the focused item. The newest registration wins, and it
is removed when the screen leaves. MainActivity hands `KEYCODE_PROG_RED / GREEN / YELLOW / BLUE` to
`ColorKeys.dispatch` (not while a menu, dialog or stream prompt is open). A key with no action keeps its old meaning
(yellow toggles My list for the focused item). Inside the shell the legend is drawn at bottom left automatically.
Full-screen screens (guide, player) place `ColorKeyLegend(hints = listOf("OK" to "Watch"))` themselves. Don't show
a legend on Who's watching.

## Home

`HomeFeed.refresh()` runs `GET /v1/catalog/home` when linked. It uses the catalog's `hero` and rows; `continue`
rows use landscape cards. The live row "Live now on your favourites" is added after Continue watching and My list.
On 404 (no catalog on the server; not asked again for that session), an error, or when unlinked, it uses local
data:
- Continue watching (films with a resume point, shows with a next episode)
- My list
- live favourites
- recently added films and shows from `search.db`. This is the highest provider ids, because the local index
  stores no "added" date.

The hero follows the focused card (250 ms settle). While focus is on its buttons it rotates through the hero titles
every 9 s. For IPTV titles the synopsis, year and backdrop come from the provider details, via the same disk-cached
lookup Movies uses (`peekVod`). Catalog artwork is on the home server (`/img?…`, `/v1/media/image?…`). `Logos.auth`
adds the session cookie only for that server's own addresses, and never follows a redirect for them.

## Who's watching?

`OrbitProfiles` reads `/v1/me`, switches with `POST /v1/profiles/select` (`OrbitModel.switchProfile`), and adds or
deletes with `/v1/profiles`. It shows at start when "Ask who's watching" is on and the account had two or more
profiles last time (only the count is stored, no names). The app waits behind it, so nothing plays until a profile
is chosen. Settings ▸ Home server has three simple rows: switch profile, picker style (Orbit / Classic), and ask at
start. The Settings restyle replaces them with the spec's segmented control and toggle.

Switching profile sends the current profile's changes first. It then clears what the old profile brought onto the
TV (My list, favourites, recently watched, resume points, show progress, as on unlink; the playlists stay) and
syncs the new profile in.

## Movies and Shows

`MoviesShowsSection` draws the sub-menu row `Recommended · Browse [All | Local | IPTV] · Collections … Any genre ▾ ·
Sort ▾ · N titles` over the page; the source toggle, genre, sort and count show only on Browse. `MoviesFeed`
(`m.movies`, one per app model) keeps each kind's state while a title page is open: the sub-tab, the Recommended
hero and rows, Browse's filters and loaded pages, the collections and the open collection, and the card focus
returns to.

- **Recommended**: `CatalogApi.recommended`. The hero shows the first hero title, then the focused card (250 ms
  settle); IPTV synopsis and backdrop come from `peekVod`. Rows scroll so the focused row sits at y = 634.
- **Browse**: `CatalogApi.browse`, 96 titles a page (12 rows of 8); the next page loads when focus is within three
  rows of the end. Genre and sort open a menu; the source toggle reloads.
- **Collections**: `CatalogApi.collections`; OK opens `CatalogApi.collection` as a page (hero, watched bar,
  Continue / Play from film 1, numbered posters). Back closes it.
- **Local data** (not linked, catalog 404, or the server out of reach): Recommended = Continue watching, My list,
  Recently added (highest provider ids in `search.db`), History. Browse = tvBro's own category list (`openVod`,
  `selectVodCat`, `vodMore`; PIN-locked categories ask for the PIN): the genre menu lists the categories (My list,
  History and all titles too); sort is the provider's order. Collections lists the categories with their size
  (OK opens the category in Browse, long press locks / unlocks it).
- OK on a card opens the title page; IPTV catalog titles are looked up in `search.db` first (real category for the
  parental lock, file type for direct play). Colour keys: My list, Play trailer (when the provider has one), Mark
  watched, More. Bookmark / gamepad Y still toggle My list.

**Title page** (`DetailScreen`): backdrop, eyebrow, title, meta, synopsis, then Play / Resume (primary), Play from
start, My list, Trailer, More; a status line; for shows the season tabs (focus selects) and that season's episodes,
for films cast and director. Colour keys: red My list, green trailer, yellow mark watched (the focused episode, else
the film or the whole show), blue More (the episode's menu on an episode). Long press on an episode or a season tab
opens its menu (play from start, marks, downloads). Everything tvBro's page did is kept: resume, play from start,
episode lists and marks, downloads, external player, category lock.

**Plex / Jellyfin titles** open the title page with details, seasons and episodes from `/v1/media/item` and
`/v1/media/children`, My list and Mark watched (`/v1/media/watched`), and a disabled **Play (coming soon)**: playback
through those servers is not built on the TV yet.

## Performance (BRAVIA)

- Focus animation runs in the draw phase (`graphicsLayer` / `drawWithContent` read an animated float). Moving focus
  redraws two cards and recomposes nothing else.
- Backdrops: four radial gradients built once per size and colour pair (`drawWithCache`), one offscreen layer for the
  artwork mask, an opacity cross-fade, and no blur. A new backdrop image loads before the fade starts.
- Images are decoded near their drawn size (`ImgSize`): posters 200 px, landscape 400, backdrops 960, and 120 for the
  picker's poster wall.
- Hero and backdrop changes wait 250 ms, so scrolling quickly through a row does no work.

## Tests and visual check

- `Orbit5LogicTest`: catalog parsing, key mapping, the image-cookie rule, colour keys, ring geometry, mesh colours.
- `ShellFocusTest` (Robolectric): Up lands on the current tab, Down returns to the last item, OK opens a tab.
- `MoviesLogicTest`: catalog items to titles (IPTV and Plex keys), captions, counts, collection summaries.
- `LiveLogicTest`: the yellow key's format cycle, which programme a recording marks, the stream prompt's words.
- `DesignShotsTest` (Robolectric native graphics) renders Home, Browse, both pickers, Movies Recommended and Browse,
  a collection, a title page, Live TV ▸ Channels (`live-channels`), the full guide (`guide`), the player overlay
  (`player`) and the stream prompt (`stream-prompt`) at 1920 × 1080 to
  `app/build/orbit-shots/*.png`. It is a visual check only; nothing compares the images.

## Live TV and the player

- **Channels** (`TvSection`, under the bar): the focused programme (`GuideInfo`) at x 96, the live preview top right
  (500 × 281; `LiveVideo` draws the video over it), the channel groups as tabs (focusing one shows its channels), then
  the grid. OK on a channel or its programme on now previews it; OK on the channel in the preview opens full screen.
- **Guide** (`GuideScreen`, full screen): the same pieces with more rows, a "Search guide" pill and its own legend.
  It opens on the playing channel's programme on now. The preview spot shows what already plays (picture and sound);
  it starts nothing. Back returns to Live TV when it was opened from there, else to full screen.
- **Grid** (`EpgGrid`): 150-minute window that follows the clock and never shows the past (`GuideLayout`), 390 px
  channel column, 80 px rows. Rows are not keyed (the old guide wasn't either). A row reads only its own channel's
  programmes and marks; each cell holds its own focus state; moving focus recomposes the info block and two cells.
- **Colour keys** (`LiveColorKeys`): red Favourite (the focused group when a tab has focus), green Record (the focused
  future programme is scheduled or cancelled; on a channel or the programme on now: stop the running recording or the
  record menu), yellow cycles the channel's stream format Default → TS → HLS (`AppModel.setChannelFormat`, synced),
  blue the channel (or group) menu. A format set by hand shows a yellow `HLS` / `TS` tag; a recording shows ● REC.
- **Player** (`PlayerScreen`): zapping and the info block show for 5 s; Down / Menu opens the quick actions
  (`QuickBar`): Guide, Favourite, Record, Audio, Subtitles, Start over (catch-up from the programme's start, when the
  channel has catch-up) or Back to live, Format, Channels, Sleep timer, Info (programme details), then Video, Audio
  delay, Aspect, Settings. Colour keys: Favourite, Record, Format (or yes to "Try HLS?"), Options (channel menu).
  Films and episodes use the same `QuickBar` with their seek bar and playback controls.
- **Stream limit** (`OrbitStreamPrompt`): top-right dialog; each stream with its device name, what it plays, "Watching
  since" (the server's `since`), and its own "Stop X and watch". Cancel holds the first focus.

## What each follow-up builds where

- **Live TV / player** (done, see above): replace `ui/live/LiveTvSection.kt` and restyle `TvSection`, `GuideScreen` and `PlayerScreen`.
  Use `CinematicBackdrop(dim = true)` for the guide. Colour keys: Favourite, Record, Format TS / HLS, Channel
  options. Draw the legend yourself on full-screen pages. Restyle the stream prompt (`OrbitStreamPrompt`) per spec
  section 10, keeping Cancel as the default focus.
- **Discover / Settings / phone**: replace `ui/discover/DiscoverSection.kt` (Seerr: `/v1/discover/*`,
  `/v1/requests`). Restyle `SettingsScreen` as a page under the bar with the spec's sub-menu, including Settings ▸
  Profiles with `OrbitProfiles.style / changeStyle` and `askOnStart / changeAskOnStart`. Remove the three simple
  rows in `orbitSection` once that exists.

## Discover

`DiscoverFeed` asks `/v1/discover/rows` (cached by the server) and `/v1/requests` (fresh; download progress comes from
there, again every 20 s while something downloads, 2 min otherwise). Rows: Your requests (landscape cards with a
status badge), Trending movies, Trending shows, Upcoming movies, Upcoming shows, Popular; the sub-menu filters them
(For you · Movies · Shows · Your requests). Badges: `DiscoverApi.badge` (Requested, Approved, Downloading N% · ~T,
Available, Pending). A title with `play` (in the profile's catalogue) offers Play and opens like a Home card; the others
offer Request (OK on a poster, the hero button, or red). Blue opens a menu. Green (trailer) and yellow (not
interested) have no action yet. Not set up (409), not linked to Orbit and Seerr down each get a calm page.

## Settings

A page under the bar (the avatar gets the double ring). Categories: Profiles (picker style, ask at start, switch
profile, PIN, Name this TV) · Playback · Live TV (general, guide, reminders, recording, channels and groups) · Sources
(home server link, playlists, sync) · Requests (Seerr state) · Appearance · About (version, updates, stats,
diagnostics, remote help). Rows not restyled yet (update check, playback stats, text fields, reminders) are drawn in
the device density through `legacy { }`. On a phone it is the same page scaled down.

## Phone 5b

`OrbitShell` switches to `PhoneShell` when `isPhoneLayout` (touch, not leanback, smallest width < 600 dp or
portrait). There is no `OrbitCanvas` on phones: 1 dp is the spec's phone px. Sections read `LocalIsPhone` and pad by
`LocalPhoneInsets` (header, bottom bar). `SubMenuRow` becomes scrolling chips; `LegacyPage` sits between header and
bar. The activity no longer forces landscape (`screenOrientation="unspecified"`). Every phone page is touch-first:
targets at least 48 dp (`PhoneTouch`), plain scrolling, no D-pad focus or colour keys (long press opens the menus).

- **Shared parts** (`ui/orbit5/PhoneParts.kt`): `PhoneChip` / `PhoneChipRow`, `PhoneSegmented`, `PhoneAction` (the
  round actions), `PhoneRoundButton`, `PhoneWideButton`, `PhoneSheet` / `PhoneSheetRow` (bottom sheets), `bleed`.
- **Movies / Shows** (`ui/movies/PhoneMovies.kt` views, `PhoneMoviesPage.kt` bound to the feed): sub-menu chips;
  Browse adds `All | Local | IPTV` and genre / sort chips, then a 3-column poster grid (more columns on its side,
  `phoneGridColumns`); Recommended = hero with Play / My list, then rows; Collections grid and a collection page.
- **Title page** (`ui/movies/PhoneDetail.kt`): `DetailScreen` draws full screen without the top bar; backdrop header
  with Back, gradient Play / Resume, round actions (My list, Trailer, Watched, More; `phoneDetailActions`), season
  chips and a vertical episode list.
- **Live TV** (`ui/live/PhoneLive.kt`): chips Channels · Guide · Recordings · My list, the live preview (the video is
  drawn over it as on the TV), group chips, then the channel list (now with progress, next, marks) or the vertical
  guide (channel tiles, then that channel's programmes; tap = watch / catch-up / remind-record menu). On its side the
  preview is left and the list right.
- **Player** (`ui/live/PhonePlayer.kt`, `PlayerScreen`): upright the video is a 16:9 spot at the top (`PhoneVideo.rect`
  tells `LiveVideo` where), tap shows its controls (Back, LIVE, full screen, progress) for 5 s; below: channel with up /
  down, programme, the "Try HLS?" chip, round actions and the group's channels. On its side the video is full screen
  with the controls over it. Full screen turns the phone sideways (`requestedOrientation`, reset when the player
  closes). More opens the quick bar as a sheet (`QuickBar(phone = true)`); Record's choices are a sheet.
- **Stream limit** (`PhoneStreamSheet`): a bottom sheet with the same rows; Cancel (or a tap outside) keeps everything.
- Shots: `phone-home`, `phone-movies`, `phone-detail`, `phone-live`, `phone-guide`, `phone-player`,
  `phone-stream-prompt` at 412 × 915 dp. `PhoneLogicTest` covers the grid columns, episode titles, title-page actions
  and the guide's rows.