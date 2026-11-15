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

/** The body of a "stopped" report (POST /v1/media/progress). */
export function stopBody(session, positionSecs) {
  return JSON.stringify({ session, positionSecs: Math.max(0, Math.round((positionSecs || 0) * 10) / 10), state: 'stopped' });
}

/** The last "stopped" of a play session while the page goes away (pagehide, beforeunload). navigator.sendBeacon
 *  first: browsers deliver it after the page is gone, but it cannot carry the CSRF header, so it is sent as
 *  text/plain and the server accepts exactly this form (a same-origin page, the cookie, the play session id,
 *  state "stopped"; docs/orbit/API-v1.md). Else a keepalive fetch with JSON and the CSRF token.
 *  env: {navigator, fetch, csrf} (the browser's, injected for the tests). Returns 'beacon' | 'fetch' | null. */
export function sendStop(session, positionSecs, env) {
  const body = stopBody(session, positionSecs);
  const nav = env && env.navigator;
  try {
    if (nav && typeof nav.sendBeacon === 'function' && nav.sendBeacon('/v1/media/progress', new Blob([body], { type: 'text/plain' }))) return 'beacon';
  } catch {}
  try {
    env.fetch('/v1/media/progress', { method: 'POST', keepalive: true, credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': env.csrf || '' }, body }).catch(() => {});
    return 'fetch';
  } catch {}
  return null;
}

/** The server's error text of a failed relay request ({"error": "..."}), from an hls.js fatal error's data
 *  (networkDetails: the XMLHttpRequest it used; response.code: the HTTP status). */
export function hlsErrorText(d) {
  const code = d && d.response && d.response.code;
  const det = d && d.networkDetails;
  let text = '';
  try {
    if (det && typeof det.responseText === 'string' && (det.responseType === '' || det.responseType === 'text')) text = det.responseText;
    else if (det && det.response instanceof ArrayBuffer) text = new TextDecoder().decode(det.response);
  } catch {}
  const msg = errorText(text);
  if (msg) return msg;
  if (code) return `The server could not send this title (HTTP ${code}).`;
  if (d && d.type === 'mediaError') return 'This browser could not play the title.';
  return 'The home server did not answer.';
}

/** "..." of a JSON {"error": "..."} body, else ''. */
export function errorText(text) {
  try { const j = JSON.parse(text || ''); return j && typeof j.error === 'string' ? j.error.slice(0, 300) : ''; } catch { return ''; }
}
