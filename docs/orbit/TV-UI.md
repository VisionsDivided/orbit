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
| `ui/movies/MoviesShowsSection.kt` | Movies and Shows (now the legacy `BrowseScreen` below the bar) | **Movies / Shows / Detail agent** |
| `ui/live/LiveTvSection.kt` | Live TV: sub-menu Channels · Guide · Recordings · My list over the legacy screens | **Live TV / player agent** |
| `ui/discover/DiscoverSection.kt` | Discover placeholder | **Discover / Settings / phone agent** |
| `orbit/CatalogApi.kt` | Client for every `/v1/catalog` route (`home`, `recommended`, `browse`, `collections`, `collection`) | shared |
| `orbit/OrbitProfiles.kt` | Profiles, the picker state, picker style and "ask at start" (settings of this TV) | shell agent |

`Section` (AppModel.kt) gained `HOME` (now the default) and `DISCOVER`. Live TV is `TV`, and `RECORDINGS` and `MYLIST`
are its sub-pages. `SEARCH` opens from the search icon. MainActivity routes `Screen.LIST / SETTINGS / VOD` to
`OrbitShell`. The other screens (guide, player, detail, link, add source) are still full-screen legacy screens.

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
- `DesignShotsTest` (Robolectric native graphics) renders Home, Browse and both pickers at 1920 × 1080 to
  `app/build/orbit-shots/*.png`. It is a visual check only; nothing compares the images.

## What each follow-up builds where

- **Movies / Shows / Detail**: replace `ui/movies/MoviesShowsSection.kt`. Use `SubMenuRow` (Recommended · Browse ·
  Collections, with `BrowseControls` on Browse), `CatalogApi.recommended / browse / collections`, `PosterCard` grids
  (180 × 270, 8 per row), and `CinematicBackdrop` + `HeroBlock(section = true)`. Restyle `DetailPage`, for example
  in a new `ui/movies/DetailScreen.kt`, and route `Screen.DETAIL` to it in MainActivity. Colour keys: My list, Play
  trailer, Mark watched, More. The legacy browse and detail code in `ui/BrowseScreens.kt` can then go.
- **Live TV / player**: replace `ui/live/LiveTvSection.kt` and restyle `TvSection`, `GuideScreen` and `PlayerScreen`.
  Use `CinematicBackdrop(dim = true)` for the guide. Colour keys: Favourite, Record, Format TS / HLS, Channel
  options. Draw the legend yourself on full-screen pages. Restyle the stream prompt (`OrbitStreamPrompt`) per spec
  section 10, keeping Cancel as the default focus.
- **Discover / Settings / phone**: replace `ui/discover/DiscoverSection.kt` (Seerr: `/v1/discover/*`,
  `/v1/requests`). Restyle `SettingsScreen` as a page under the bar with the spec's sub-menu, including Settings ▸
  Profiles with `OrbitProfiles.style / changeStyle` and `askOnStart / changeAskOnStart`. Remove the three simple
  rows in `orbitSection` once that exists.
