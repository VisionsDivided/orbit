# TS vs HLS channel survey, 14 November 2026

**Question (owner):** are some channels better over HLS and others over TS, and does HLS cause problems on any
channel that TS doesn't?

**Short answer:** HLS was smooth on 9 of the 10 channels where it was measured. TS had multi-second delivery gaps on
most channels. But HLS was clearly *worse* than TS on one channel (NFL Network), and HLS keeps the account's single
provider connection busy for about a minute after playback stops. Recommendation: keep **TS as the default, add a
per-channel HLS override, and suggest switching to HLS when a TS channel stalls** (details at the end).

Follow-up to [buffering-2026-11-14.md](buffering-2026-11-14.md) (Reelz).

## Method

- Run from the Orbit container on the wired VM, straight to the provider, between 13:47 and 14:34 UTC (afternoon,
  not prime time). The account allows **1** connection; it was idle (0 in use) before each probe, and probes ran
  strictly one at a time.
- Each channel: 75 s over MPEG-TS (`.ts`), then 75 s over HLS (`.m3u8`).
- **TS:** time to first byte, gaps with no data at all (over 1 s), average and minimum throughput in 5 s windows.
  The probe also reads the stream's PCR clock to know how many seconds of video actually arrived.
- **HLS:** joins 3 segments from the live edge like a player, reloads the playlist as the HLS spec says (once per
  target duration, half that when nothing changed), downloads every new segment, and records segment download time
  vs segment length, failures (404 etc.), playlist errors, discontinuities, playlist sequence jumps and stale
  playlists.
- **Simulated stalls:** a model player that starts once 2.5 s of video is buffered (the TV's live buffer), keeps
  everything it receives, and stalls when it runs dry. It is the same model for both formats, so it shows whether
  the gaps would be heard on a player that keeps the provider's start-up burst (see "Caveat" below).
- Scope was cut mid-run to US channels (requested); 12 channels were measured before that.

## Results

### TS (`.ts`)

| Channel | Group | First byte (s) | Gaps > 1 s | Longest gap (s) | Avg Mbps | Avg 5 s Mbps | Min 5 s Mbps | Video s received in 75 s | Sim. stalls |
|---|---|---|---|---|---|---|---|---|---|
| Reelz | US Entertainment | 1.10 | 5 | 2.0 | 3.63 | 3.04 | 1.99 | 87.3 | 0 |
| ABC | US Entertainment | 1.27 | 12 | 5.8 | 5.82 | 5.96 | 2.94 | 83.2 | 0 |
| TNT | US Entertainment | 1.27 | 11 | **11.6** | 6.39 | 6.48 | 1.57 | 87.2 | 0 |
| AMC | US Entertainment | 1.11 | 3 | 2.0 | 5.63 | 4.73 | 3.88 | 90.1 | 0 |
| HBO | US Movies | 1.28 | 0 | 1.0 | 6.49 | 5.69 | 4.40 | 84.9 | 0 |
| CNN | US News | 1.26 | 11 | **11.5** | 6.13 | 6.56 | 1.36 | 98.9 | 0 |
| FOX News | US News | 1.31 | 19 | 4.8 | 5.63 | 4.64 | 2.31 | 89.6 | 0 |
| ESPN (1080p) | US Sports | 1.26 | 13 | 9.7 | 11.68 | 10.25 | 4.19 | 87.0 | 0 |
| FOX Sports 1 | US Sports | 1.27 | 0 | 1.0 | 6.66 | 5.85 | 5.24 | 87.4 | 0 |
| NFL Network | US Sports | 1.14 | 14 | 8.7 | 9.75 | 9.65 | 3.77 | 87.0 | 0 |
| Disney Channel | US Kids | 1.11 | 13 | 6.0 | 4.85 | 4.99 | 2.52 | 89.5 | 0 |
| Cartoon Network | US Kids | 1.12 | 2 | 2.8 | 3.81 | 3.71 | 1.78 | 76.1 | 1 (3.3 s) |

### HLS (`.m3u8`)

| Channel | First segment byte (s) | Segments | Failed | Playlist errors | Seg. length (s) | Avg download (s) | Download / length avg (max) | Slower than real time | Longest wait for a new segment (s) | Seq. went back | Video Mbps | Sim. stalls |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Reelz | 1.52 | 10 | 0 | 0 | 9.96 | 1.13 | 0.11 (0.12) | 0 | 12.1 | 0 | 3.06 | 0 |
| ABC | 1.44 | 19 | 0 | 0 | 10.04 | 0.89 | 0.09 (0.15) | 0 | 12.4 | 1 | 5.15 | 0 |
| TNT | 1.43 | 20 | 0 | 0 | 9.90 | 1.00 | 0.10 (0.13) | 0 | 12.1 | 2 | 5.81 | 0 |
| AMC | 1.56 | 14 | 0 | 0 | 9.91 | 2.16 | 0.21 (**1.45**) | 1 | 16.6 | 0 | 4.81 | 0 |
| HBO | 1.43 | 21 | 0 | 0 | 10.01 | 0.93 | 0.09 (0.12) | 0 | 11.2 | 1 | 5.45 | 0 |
| CNN | 1.39 | 23 | 0 | 0 | 9.97 | 0.80 | 0.08 (0.13) | 0 | 13.0 | 0 | 4.23 | 0 |
| FOX News | 1.41 | 19 | 0 | 0 | 10.01 | 0.89 | 0.09 (0.16) | 0 | 11.4 | 0 | 5.28 | 0 |
| ESPN (1080p) | — | — | — | — | — | — | — | — | — | — | — | not recorded (see notes) |
| FOX Sports 1 | 1.42 | 14 | 0 | 0 | 10.01 | 1.14 | 0.11 (0.16) | 0 | 11.2 | 0 | 6.18 | 0 |
| **NFL Network** | 1.62 | 6 | 0 | 0 | 10.01 | **13.70** | **1.37 (4.22)** | **2** | **43.8** | 0 | 8.55 | **2 (39.4 s total, longest 21.8 s)** |
| Disney Channel | 1.49 | 11 | 0 | 0 | 10.01 | 1.15 | 0.12 (0.13) | 0 | 17.2 | 0 | 3.84 | 0 |
| Cartoon Network | 1.50 | 11 | 0 | 0 | 10.01 | 1.51 | 0.15 (0.52) | 0 | 22.3 | 0 | 3.81 | 0 |

Every HLS channel used a single-variant playlist (no master playlist) with ~10 s segments (target duration 11–12 s),
and the provider redirected the playlist to a separate segment server. No 404s, no failed segments, no playlist
errors, no discontinuity tags and no end-of-stream markers on any channel.

## Findings

1. **TS gaps are common, not a Reelz quirk.** 10 of 12 channels had data gaps over 1 s in 75 s; 8 had a gap longer
   than the TV's 2.5 s buffer; TNT (11.6 s), CNN (11.5 s), ESPN (9.7 s) and NFL Network (8.7 s) had the worst.
   Only HBO and FOX Sports 1 were clean. Reelz was mild this afternoon (longest 2.0 s vs 12.9 s in the morning), so
   this varies with time of day, not by channel. One 75 s sample can't tell a "good TS channel" from a lucky minute.
2. **HLS segments usually arrive 5–10x faster than real time.** On 9 of 10 channels each 10 s segment downloaded in
   about 1 s, so a single slow segment is easily covered.
3. **HLS was worse than TS on NFL Network.** Segments took up to 42 s for 10 s of video, 44 s passed without a new
   segment, and the model player stalled twice (39 s in total). Over TS the same channel had 14 gaps (longest 8.7 s)
   but kept up on average. AMC had one slow HLS segment (14.5 s for 10 s), which its buffer absorbed. So yes: HLS
   can fail on a channel where TS works, at least at times. A single sample can't tell whether that was the channel
   or one overloaded segment server.
4. **Smaller HLS issues, no visible effect:** the playlist's media sequence number went backwards between reloads
   on ABC (1x), TNT (2x) and HBO (1x), which suggests several out-of-sync playlist servers behind the provider. AMC,
   Disney Channel and Cartoon Network sometimes went 16–22 s (1.5–2x the target duration) without a new segment.
   Players handle both, but they eat into the buffer.
5. **HLS holds the provider connection for ~1 minute after stopping.** After every HLS probe the provider still
   counted the connection as in use for about 60–90 s (2 consecutive 30 s checks found it busy). After a TS probe it
   was free immediately in 9 of 11 cases (30 s in the other two). On a 1-connection account this means switching
   from an HLS channel to another channel may be refused for about a minute. This needs checking on the TV, since
   the provider may let the same device reuse its own session.
6. **Start and latency.** In the model, HLS started ~0.5 s later than TS (about 2.0–2.4 s vs 1.5 s). With 10 s
   segments and a 3-segment join, an HLS player also runs about 30 s behind live, versus a few seconds for TS. That
   matters for sports and news tickers.
7. **TS start-up burst.** On TS the provider sends 8–24 s of extra video in the first seconds (83–99 s of video in
   75 s). (Cartoon Network was the exception, with no burst and the only simulated TS stall.) A player that keeps that burst rides out most gaps, which is why the model shows almost no TS stalls. The
   TV still stalled on Reelz, so in practice it doesn't keep or doesn't get enough of that lead (see "Caveat").

### Caveat on the simulated stalls

The model keeps everything received. The TV player targets a ~2.5 s live buffer. If it drops or catches up past the
burst, every TS gap longer than ~2.5 s becomes a visible stall (8 of 12 channels here). The TV's own playback stats
(Settings ▸ About) are the real test, not the model.

## Recommendation

**TS default, per-channel HLS override, and an auto-suggest when a TS channel stalls.**

- Global HLS isn't safe yet: one of ten channels was far worse over HLS. On this 1-connection account the
  ~1-minute connection hold after HLS playback risks "connection limit" refusals on every channel change, and HLS
  adds ~30 s of live delay.
- A purely per-channel setting with no prompting puts all the work on the household, and TS gaps change with time
  of day, so a one-off choice per channel would go stale.
- Suggested behaviour: remember the format per channel. When a TS channel has, say, 2+ stalls or one stall over
  5 s within a few minutes, offer "Switch this channel to HLS?" (and the reverse if an HLS channel stalls).
  Recordings stay on TS.

Before deciding:

1. On the TV, switch an HLS channel straight to another channel and check whether the provider refuses the second
   one (finding 5). If it does, HLS needs a "release" delay or should stay opt-in.
2. Compare TS vs HLS stall counts from the TV's playback stats over a few evenings (prime time was not covered here).
3. If most channels stall on TS on the TV, revisit making HLS the default and keep TS as the per-channel fallback
   (e.g. for NFL Network).

## Notes

- ESPN (1080p) over HLS: the probe ran but its output was lost, and it was not re-run because the survey scope
  was cut. ESPN (4K), Sky Sports Main Event, local stations and more entertainment channels (TBS, USA, FX, Bravo,
  E!) were planned but not measured.
- Each probe is a single 75 s sample at one time of day; treat per-channel differences as indications, not verdicts.
- No provider address, login or URL appears in this document. The probe kept them in memory inside the container
  and printed names and numbers only.
