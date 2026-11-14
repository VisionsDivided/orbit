# Orbit UI spec: TV direction 5 + phone 5b

Locked design. TV = direction 5 "Cinematic + top bar". Phone = 5b. Reference mockup: `orbit-mockups.html` (sections `#s5`, phone row "Phone · 5b"). All TV sizes are at 1920×1080. Scale them linearly for other resolutions. Phone sizes are CSS px on a 390×844 viewport.

## 1. Colour tokens

| Token | Value | Use |
|---|---|---|
| `bg` | `#05060c` | App background, mesh base fallback |
| `text` | `#f4f6ff` | Primary text |
| `muted` | `rgba(228,233,255,.64)` | Secondary text, inactive tabs |
| `accent` | `#2fd3ff` | Active underline, progress, toggles, selected source |
| `rating` | `#ffd166` | Star rating |
| `line` | `rgba(255,255,255,.07)` | Dividers, EPG grid lines |
| `live` | `#ff3b5c` | LIVE pill, now-line in guide |
| `brand-gradient` | `linear-gradient(90deg,#2fd3ff,#8a5cff)` | Phone primary button, avatar, profile focus |
| `scrim-panel` | `rgba(5,6,12,.78)` | Colour-key legend background |
| `dialog` | `rgba(10,12,22,.95)` with border `rgba(255,255,255,.12)` | Pop-ups (stream limit) |
| `chip-bg` | `rgba(10,12,22,.5–.6)` with inset 1px `rgba(255,255,255,.14)` | Sub-menu pills, dropdowns |

Remote colour keys (use these exact values everywhere): red `#e5484d`, green `#30a46c`, yellow `#f5d90a`, blue `#3e63dd`.

Discover status badges:

| Status | Background | Text | Ring (inset 2px) |
|---|---|---|---|
| Request (action) | `linear-gradient(90deg,#2fd3ff,#8a5cff)` | `#fff` | none |
| Requested | `rgba(138,92,255,.22)` | `#d2c2ff` | `rgba(138,92,255,.6)` |
| Approved | `rgba(80,160,255,.16)` | `#a9cfff` | `rgba(80,160,255,.55)` |
| Downloading N% · ~T | fill `rgba(47,211,255,.42)` to N%, rest `rgba(255,255,255,.07)` | `#fff` | `rgba(47,211,255,.6)` |
| Available | `rgba(70,220,150,.16)` | `#7ff0bb` | `rgba(70,220,150,.5)` |
| Pending | `rgba(255,190,80,.16)` | `#ffd27a` | `rgba(255,190,80,.5)` |

The Downloading label and percentage come from Seerr, which gets them from Sonarr or Radarr. Update them live.

## 2. Type

- Family: `"Segoe UI Variable Display","Segoe UI","SF Pro Display","Helvetica Neue",system-ui,sans-serif`. On Android TV, use the closest bundled display sans (e.g. Inter Display) at the same weights.
- TV scale:

| Role | Size / weight | Notes |
|---|---|---|
| Hero title | 112px / 800 | Letter-spacing −.035em, line-height .95. 84–96px on section pages |
| Eyebrow | 22px / 700 | Uppercase, +.14em, muted |
| Meta line | 26px / 600 | Gap 18px; pills have a 2px border at 50% white, radius 6 |
| Synopsis | 28px / 400 | Line-height 1.45, max 2 lines, max-width ~1000px |
| Buttons | 28px / 700 | Padding 20×40, radius 12 (26px with 18×36 on section pages) |
| Top bar nav | 27px / 700 | |
| Sub-menu pills | 22px / 600 | |
| Row heading | 28–30px / 700 | |
| Card title / meta | 24 / 20px | Grid captions 20 / 17px |
| Legend | 20px / 600 | |

- Phone: H1 28px/800; row heading 17px/700; buttons 15px/700, padding 11×18, radius 10; nav labels 10px/600.

## 3. Spacing and grid (TV)

- Safe side margin: 96px left and right. Content left edge is x=96 on every screen.
- Row gap between cards: 26–28px for landscape cards, 24–34px for posters.
- Vertical rhythm: row heading then 16–20px, then cards; 50–60px between rows.
- Card radius 10px everywhere.

## 4. Top bar (TV)

- Height 120px, full width, no background. A top scrim `linear-gradient(180deg,rgba(0,0,0,.5),transparent 16%)` keeps it legible.
- Left to right: logo (horizontal wordmark, height 46px), then a 72px gap, then nav `Home · Movies · Shows · Live TV · Discover` with a 54px gap. On the right (margin-left:auto, gap 36px): search icon 34px, clock (32px, weight 300, tabular numbers), avatar (56px circle, brand gradient).
- Inactive nav items are `rgba(255,255,255,.6)`. The active item is white with a 4px accent underline 8px below the text, plus a `0 0 12px accent` glow.
- Settings is reached from the avatar. While in Settings, no nav item is active and the avatar gets a double ring (`0 0 0 4px #05060c, 0 0 0 8px #fff`).
- Up from the sub-menu row focuses the top bar.

## 5. Sub-menu row (Movies and Shows)

- Sits at y=118, left 96px, gap 12px. Tabs: `Recommended · Browse · Collections`.
- Pill: padding 10×24, radius 999, background `rgba(10,12,22,.5)` with an inset 1px `rgba(255,255,255,.14)` ring. Active pill: white background, `#05060c` text, weight 700.
- **Browse selected:** the row spans the full width (left 96, right 96). Order:
  `Recommended · [Browse | All · Local · IPTV] · Collections ………… Any genre ▾ · Sort: Recently added ▾ · 1,284 titles`
  - The source toggle sits inside the Browse group, right next to the active Browse pill. It is a segmented control: 19px, padding 6×16; the selected segment has an accent background and `#02141c` text.
  - Genre and sort dropdowns (20px, padding 10×18) and the title count (20px, muted) are pushed right with margin-left:auto.
  - The poster grid starts directly under this row (y≈204). There is no separate filter line.
- **Recommended and Collections selected:** only the three tabs show. No source toggle, genre or sort.
- D-pad: Left/Right moves along the row, including into the toggle segments and dropdowns. Down goes to the grid.

## 6. Backdrop and hero

- Every page has a colour-matched backdrop made of three layers, bottom to top:
  1. **Mesh** in the focused title's colour pair `m1` (main), `m2` (secondary), base `mb`:
     ```
     radial-gradient(72% 82% at 100% 0%,   m1 @88%, transparent 70%),
     radial-gradient(55% 65% at 0% 100%,   m2 @22%, transparent 72%),
     radial-gradient(60% 55% at 0% 0%,     m1 @40%, transparent 70%),
     radial-gradient(60% 60% at 100% 100%, m2 @16%, transparent 70%),
     mb
     ```
     Example pairs from the mockup: red `#c3261b` / `#000` / `#110304`; teal `#16968a` / `#f2fffb` / `#03110f`; blue `#1b7fa3` / `#d8f3ff` / `#03101a`; violet `#6a3fd8` / `#ff9ad5` / `#07061a`. In the app, extract `m1` and `m2` from the backdrop image (dominant and secondary colour) and darken `mb` from `m1`.
  2. **Artwork**: top-right, 74% wide and 84% tall, masked with `radial-gradient(130% 120% at 100% 0%, #000 36%, transparent 76%)`. It fades into the mesh with no hard edge.
  3. **Vignette**: `linear-gradient(90deg,rgba(0,0,0,.5),rgba(0,0,0,.16) 45%,transparent 70%)`, plus a bottom `linear-gradient(0deg,rgba(0,0,0,.55),transparent 45%)`, plus the top bar scrim. Text-heavy pages (guide) use a stronger bottom scrim `rgba(4,5,10,.9)→.72 at 48%`.
- When focus moves to another title, cross-fade the whole backdrop (mesh and art) by opacity over 900ms ease. The hero text fades over 600ms. Wait about 250ms after a focus move before swapping. No blur anywhere.
- Hero block: x=96, y≈196 on Home and ≈214 under the sub-menu. Order: eyebrow (source badges Local `#2fd3ff` / IPTV `#b49bff`), title, meta, synopsis, buttons. Primary button: white background, `#05060c` text. Secondary: `rgba(255,255,255,.16)`.

## 7. Cards and focus

| Card | Size (TV) |
|---|---|
| Landscape, Continue watching | 380×214 (Home), 340×191 (section pages) |
| Landscape, episodes | 330×186 |
| Poster, Browse grid | 180×270, 8 per row, gaps 34px vertical × 30px horizontal |
| Poster, collection | 184×276, 8 in one row |
| Poster, rows | 160–170 wide |

- Progress bar: 6px high, 16px inset from the card's bottom and sides, track `rgba(255,255,255,.22)`, fill accent.
- Watched mark: a 34px circle at the top-right, `rgba(0,0,0,.62)`, with a `#7ff0bb` check. Collection order number: a 38px rounded box at the top-left.
- **Focus state, all cards and buttons:** 5px white outline with a 5px offset, scale 1.07, shadow `0 24px 60px rgba(0,0,0,.7)`, raised above neighbours. Animate scale over 200–300ms. EPG cells don't scale; the focused cell turns white with dark text.

## 8. Who's watching? (orbit picker)

- Background: poster wall at 16% opacity, rotated −6° and scaled 1.15, plus the orbit mesh (`#6b45e6` / `#2fd3ff` / `#06061a`), plus a radial darkening `rgba(5,6,12,.55)` at centre to `.92` at 72%.
- Title "Who's watching?" 72px/800, centred, y=64. Subline 24px muted: "Profile picker style: Orbit · change it in Settings › Profiles".
- Orbit ring: an ellipse at x=300, y=296, 1320×500. Border 3px `rgba(255,255,255,.26)`, glow `0 0 60px rgba(138,92,255,.25)`. Planet logo centred at x=730, y=331, width 460.
- Profiles on the ellipse. The focused profile sits at the bottom (6 o'clock): 200px, name 32px/800 white. The others are 150px, names 26px/600 muted. Positions (top-left x, y): Guest (204, 523), Maya (444, 668), focused (850, 696), Leo (1256, 668), Add profile (1496, 523, dashed 4px ring).
- Left/Right rotates the orbit so the next profile moves to the bottom (animate along the ellipse, about 400ms).
- One "Manage profiles" button, centred, 44px from the bottom: secondary style, 24px, gear icon. There is **no colour-key legend on this screen**.
- Picker style is a setting (see §12). "Classic" is a single centred row of avatars.

## 9. Colour-key legend

- A pill at bottom-left: x=96 (or the content's left edge), 28px from the bottom. Padding 11×26, background `rgba(5,6,12,.78)`, inset 1px `rgba(255,255,255,.1)` ring.
- Each key: a 38×17 pill in the key colour with radius 9 and the label after it (20px/600), 30px between keys. Button hints (`OK Watch`, `BACK Close`) follow after a 1px divider, with the button name in a 1.5px outlined box.
- Mapping:

| Screen | Red | Green | Yellow | Blue |
|---|---|---|---|---|
| Live TV guide | Favourite | Record | Format: TS / HLS (this channel) | Channel options |
| Title detail, posters, Recommended, Collections | My list | Play trailer | Mark watched | More |
| Discover | Request | Play trailer | Not interested | More |
| Player overlay | Favourite | Record | Format: TS / HLS | Options |
| Settings | (none, hints only) | | | |
| Who's watching | (no legend) | | | |

- Guide indicators: a channel with a format override shows a yellow tag (`HLS`, 14px/800, background `rgba(245,217,10,.18)`, text `#ffe55c`). A recording shows `● REC` in `#ff6b6b`. Favourites show a `#ffd166` star.

## 10. Live player overlay and stream limit

- Video full-bleed. Bottom scrim `linear-gradient(0deg,rgba(0,0,0,.94),rgba(0,0,0,.66) 40%,transparent 66%)`, plus a top scrim to 24%.
- Top-left: channel logo (72px) with name and number. Top-right: clock, 40px/300.
- Info block at x=96, y≈612: LIVE pill and time line, title 72px/800, a scrubber (8px bar with a 22px white knob), and "Next · …".
- Quick actions at y≈846: 84px circles (`rgba(255,255,255,.14)`) with 20px labels, gap 20px. Order: Guide, Favourite, Record, Audio, Subtitles, Start over, Format · TS, Channels, Sleep timer, Info.
- **Buffering hint:** when a channel rebuffers repeatedly, show a chip at the right of the info block: "This channel keeps buffering. Try HLS?". Background `rgba(40,34,4,.82)`, ring `rgba(245,217,10,.6)`, text `#fff3a8`, with a yellow key pill (yellow switches the format for this channel only).
- **Stream-limit prompt:** a dialog at the top-right (right 96, y=132, width 900, radius 20, padding 28×30).
  - Title "Both streams are in use" 30px/800. Explanation 21px muted.
  - One row per active stream: logo 60px, device name 24px/800, "channel · programme" and "Watching since hh:mm" muted. Each row has its own button, "Stop Kitchen and watch" / "Stop Bedroom and watch".
  - Cancel at the bottom-right. **Default focus is Cancel; never pre-select a stream.**

## 11. Discover

- Hero for the #1 trending title (Request as primary). Below it, in order: Your requests (landscape cards with a status badge under the caption), Trending movies, Trending shows, Upcoming movies, Upcoming shows, Popular.
- Sub-menu: For you · Movies · Shows · Your requests.

## 12. Settings › Profiles

- Reached from the avatar. The sub-menu row lists categories: Profiles · Playback · Live TV · Sources · Requests · Appearance · About.
- Rows: padding 22×28, radius 16, background `rgba(10,12,22,.5)`, label 25px/600 with a 19px muted description. The focused row has a 4px white ring.
- First row: **Profile picker style** with a segmented control `Orbit | Classic` (default Orbit). Left/Right changes it, and a live preview card on the right shows each style. Other rows: Ask who's watching on start (toggle), Default profile, PIN to leave a Kids profile (toggle), Manage profiles.
- Toggle: 66×36 accent pill with a 28px white knob.

## 13. Phone 5b

- Status bar 44px. Header at y=50: logo (40px) or page title (28px/800) on the left, search (22px) and avatar (32px) on the right.
- Movies and Shows: tabs `Recommended · Browse · Collections`, then a source toggle `All | Local | IPTV`, then genre/sort chips, then a 3-column poster grid (108×162, gap 13px).
- **Bottom bar:**
  - Layout: full width, height 80px including an 18px bottom inset, background `linear-gradient(0deg,rgba(5,6,12,.96) 60%,rgba(5,6,12,0))`. Five tabs spaced evenly: Home, Movies, Shows, Live, Discover.
  - Tabs: icon 23px with the label underneath (10px/600, gap 3px) on every tab. Inactive: `rgba(255,255,255,.5)`.
  - Active tab: a pill (padding 6×10, radius 16, min-width 62) with background `linear-gradient(90deg,rgba(47,211,255,.35),rgba(138,92,255,.45))`, glow `0 0 18px rgba(138,92,255,.5)`, inset 1px `rgba(170,150,255,.45)` ring, white icon and label.
- **Primary buttons** (Play, Resume): background `linear-gradient(90deg,#2fd3ff,#8a5cff)`, white text, shadow `0 6px 20px rgba(138,92,255,.45)`. Secondary: `rgba(255,255,255,.14)`.
- Title detail action row (the phone equivalent of the colour keys): My list, Trailer, Watched, More, as 44px circles with 10.5px labels.
- Stream limit on phone: a bottom sheet (radius 22 on top) with the same content as on TV: one row and button per stream, then Cancel. The buffering hint appears as a small yellow chip under the programme info.
