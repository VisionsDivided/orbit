// Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
// Plex / Jellyfin titles on the web (title.js): pure helpers, unit-tested with node.

/** A position worth resuming: past the first 30 s and not in the last 30 s (else the title starts over). */
export function resumable(p) {
  if (!p || !(p.secs > 30)) return false;
  if (p.watched) return false;
  return !p.dur || p.secs < p.dur - 30;
}

/** "S1 E2" (or "E2", or "") of a media-server episode {season, episode}. */
export function epCode(e) {
  if (!e) return '';
  const s = e.season != null ? 'S' + e.season : '', n = e.episode != null ? 'E' + e.episode : (e.num != null ? 'E' + e.num : '');
  return [s, n].filter(Boolean).join(' ');
}

/** Seasons in order (specials, season 0, last), episodes in order. */
export function sortSeasons(list) {
  const key = (s) => (s.season == null ? 1e6 : s.season === 0 ? 1e5 : s.season);
  return [...(list || [])].sort((a, b) => key(a) - key(b));
}
export function sortEpisodes(list) {
  return [...(list || [])].sort((a, b) => (a.episode ?? 1e6) - (b.episode ?? 1e6));
}

/** The catalogue key of a Plex / Jellyfin item ("m|<conn>|<id>" / "s|<conn>|<id>") as {kind, conn, id}, or null. */
export function mediaRef(key) {
  const m = /^([ms])\|([0-9a-f]{16})\|([A-Za-z0-9_-]{1,64})$/.exec(String(key || ''));
  return m ? { kind: m[1] === 'm' ? 'movie' : 'show', conn: m[2], id: m[3] } : null;
}
