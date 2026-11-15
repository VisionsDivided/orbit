// Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
// Settings ▸ Requests (Seerr) and Settings ▸ Sources (Plex, Jellyfin): the logic without the page, so
// web/tests/setup.test.mjs can check it in Node. setupcards.js draws the cards.

/** An address as typed, with http:// added when no scheme was typed ("192.0.2.20:5055"). Empty stays empty. */
export function withScheme(s) {
  const v = String(s || '').trim();
  if (!v) return '';
  return /^[a-z][a-z0-9+.-]*:\/\//i.test(v) ? v : 'http://' + v;
}

/** The body for POST /v1/discover/settings from the form, or {error}. The key is sent only when typed: without
 *  it the server keeps the saved key (same server only). test: "Test connection" (nothing is saved). */
export function seerrBody(form, { keySet = false, test = false } = {}) {
  const baseUrl = withScheme(form.baseUrl);
  const apiKey = String(form.apiKey || '').trim();
  if (!baseUrl) return { error: 'Enter the Seerr address, for example http://192.0.2.20:5055' };
  if (!apiKey && !keySet) return { error: 'Enter the API key (Seerr ▸ Settings ▸ General)' };
  if (apiKey && /\s/.test(apiKey)) return { error: 'The API key has no spaces' };
  const body = { baseUrl };
  if (apiKey) body.apiKey = apiKey;
  if (test) body.test = true;
  return { body };
}

/** The status line of Seerr from GET /v1/discover/status: {text, tone: 'ok'|'warn'|'off'}. */
export function seerrState(st) {
  if (!st) return { text: 'Unknown: the server did not answer', tone: 'warn' };
  if (!st.configured) return { text: 'Not connected', tone: 'off' };
  if (st.reachable === false) return { text: 'Connected, but Seerr cannot be reached now', tone: 'warn' };
  return { text: 'Connected' + (st.version ? ' · Seerr ' + st.version : ''), tone: 'ok' };
}

/** A Seerr user's name for a list: their name, with the Plex / Jellyfin name or e-mail when it differs. */
export function seerrUserLabel(u) {
  const name = u.name || u.username || u.email || 'User ' + u.id;
  const alt = [u.plexUsername, u.jellyfinUsername, u.email].find((x) => x && x !== name);
  return alt ? `${name} (${alt})` : name;
}

/** The mapping list from GET /v1/discover/users: one row per Orbit account with the options of its select
 *  ([value, label], "" = not linked; the suggested user and Seerr admins are marked: only the owner may link an
 *  account to a Seerr admin, whose requests are approved automatically) and the value now. */
export function mappingRows(data) {
  const users = (data && data.users) || [];
  return ((data && data.accounts) || []).map((a) => ({
    id: a.id,
    name: a.displayName && a.displayName !== a.username ? `${a.displayName} (${a.username})` : a.username,
    value: a.seerrUserId ? String(a.seerrUserId) : '',
    suggested: a.suggested || null,
    options: [['', 'Not linked'], ...users.map((u) => [String(u.id), seerrUserLabel(u) + (u.admin ? ' · Seerr admin' : '')
      + (u.id === a.suggested && u.id !== a.seerrUserId ? ' · suggested' : '')])],
  }));
}

/** How many accounts "Link suggested" would link (unlinked, with one suggestion). */
export function suggestedCount(data) {
  return ((data && data.accounts) || []).filter((a) => !a.seerrUserId && a.suggested).length;
}

export const KIND_LABEL = { plex: 'Plex', jellyfin: 'Jellyfin' };

/** A linked media server for the list: {title, sub, state: {text, tone}}. c: a Connection, with "ok" / "error"
 *  when it was listed with ?check=1. */
export function connView(c, now = Date.now() / 1000) {
  const title = c.serverName || c.baseUrl || KIND_LABEL[c.kind] || 'Server';
  const sub = [KIND_LABEL[c.kind] || c.kind, c.userName ? 'as ' + c.userName : null, c.baseUrl && c.baseUrl !== title ? c.baseUrl : null].filter(Boolean).join(' · ');
  let state;
  if (c.ok === true) state = { text: 'Online', tone: 'ok' };
  else if (c.ok === false) state = { text: /link it again/i.test(c.error || '') ? 'Sign-in expired: link it again' : 'Cannot be reached' + (c.error ? ': ' + c.error : ''), tone: 'warn' };
  else state = { text: c.lastOk ? 'Last reached ' + agoText(c.lastOk, now) : 'Checking…', tone: 'off' };
  return { title, sub, state };
}

export function agoText(ts, now = Date.now() / 1000) {
  const s = Math.max(0, Math.floor(now - ts));
  if (s < 90) return 'just now';
  if (s < 5400) return Math.round(s / 60) + ' min ago';
  if (s < 172800) return Math.round(s / 3600) + ' h ago';
  return Math.round(s / 86400) + ' days ago';
}

/** What the person does with a sign-in from POST /v1/media/connect/start: {code, steps, link?}. */
export function signInSteps(start) {
  if (start.method === 'pin') {
    return { code: start.code, link: start.linkUrl || 'https://plex.tv/link',
      steps: `On a phone or computer, open ${(start.linkUrl || 'https://plex.tv/link').replace(/^https?:\/\//, '')}, sign in to Plex and enter this code.` };
  }
  const name = start.server && start.server.name ? start.server.name : 'Jellyfin';
  return { code: start.code, steps: `In ${name}, open your profile ▸ Quick Connect and enter this code.` };
}

/** Seconds left of a sign-in ("expires" is Unix seconds); never below 0. */
export function secsLeft(expires, now = Date.now() / 1000) {
  return Math.max(0, Math.round((expires || 0) - now));
}
export function mmss(s) { return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0'); }

/** The poll delay in ms: the server's interval (seconds), kept between 2 and 10 seconds. */
export function pollDelay(interval) {
  const n = Number(interval);
  return Math.min(10, Math.max(2, Number.isFinite(n) && n > 0 ? n : 3)) * 1000;
}
