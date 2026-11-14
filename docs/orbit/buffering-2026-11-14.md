# Buffering investigation, 14 November 2026 (Reelz)

**Question:** Reelz kept buffering on the TV. Is that us (TV, Wi-Fi, home network, player) or the provider?

**Answer: the provider's MPEG-TS delivery.** The same channel over the provider's HLS was smooth.

## Measurements (all at ~05:30 UTC, nobody else streaming, provider limit 1 connection, 0 in use)

| Path | Result |
|---|---|
| Provider → VM (wired), Reelz, MPEG-TS, 120 s | avg 3.5 Mbps; slowest 5 s window 0.1 Mbps; **16 gaps over 1 s, longest 12.9 s** of no data |
| Provider → VM (wired), ABC (same group), MPEG-TS, 120 s | avg 5.9 Mbps; 19 gaps over 1 s, longest 5.7 s |
| Provider → VM (wired), Reelz, **HLS (.m3u8)**, 120 s | 30 segments of ~10 s, each downloaded in **2.25 s on average** (~14.6 Mbps), 0 failures, 1 segment slightly slower than real time (10.5 s for 10.1 s) |
| Internet → VM (public test file, Europe) | 142 Mbps, one 0.75 s blip |
| VM → TV over Wi-Fi (60 MB) | 1.81 s ≈ **278 Mbps** |

The wired VM sees the same pauses without any TV or Wi-Fi involved, and the home internet line and the TV's Wi-Fi
are far faster than the stream. A 12.9 s pause is longer than any reasonable live buffer (the TV's is ~2.5 s), so
the TV must stall. The provider's HLS path (a CDN-style segment server) did not show it.

## What changed

- **Settings ▸ Playback ▸ Live stream format** (TV): MPEG-TS (default, unchanged) or **HLS**. With HLS the player
  asks the provider for `.m3u8` instead of `.ts` for Xtream live channels. Recordings keep using TS.
- Playback stats (Settings ▸ About, and the second line in the player's stream info) show start time, stalls and
  longest stall per channel, so the two formats can be compared on the TV.

## Suggested next steps

1. On the TV, switch Live stream format to HLS and watch Reelz (and a channel that never buffered) for 30+ minutes
   each; compare the stall numbers in Settings ▸ About (Export saves them).
2. If HLS is clearly better on most channels, make HLS the default for this provider (a decision for you: it is a
   behaviour change).
3. The playlist also has "LBW: REELZ" (low bandwidth) and "REELZ" in another group: tvBro's alternate-stream
   fallback can use them when one copy keeps failing.
4. Re-run the probe at prime time (provider load differs by time of day):
   `ssh <user>@<vm> 'docker exec -i orbit python - reelz 120' < stream_probe.py` (script kept outside the repo
   because it reads the provider login inside the container; it prints names and numbers only).
