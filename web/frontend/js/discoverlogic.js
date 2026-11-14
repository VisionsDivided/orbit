// Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
// Discover's pure logic (no DOM), shared by discover.js, discovertitle.js and ui5.js and tested in
// web/tests/discover.test.mjs.

import { tmdbProxy } from './catalog.js';

/**
 * The /v1/discover/image URL of a Seerr artwork URL, or '' when it is not TMDB artwork. The server signs every
 * poster / backdrop URL in its JSON answers into /img (sign_images in app.py), so a Seerr title's TMDB poster
 * arrives as /img?u=https://image.tmdb.org/...&s=...: the TMDB URL is taken back out of it. Only TMDB's allowlisted
 * sizes and plain file names pass (tmdbProxy).
 */
export function tmdbImage(u) {
  if (typeof u !== 'string' || !u) return '';
  let raw = u;
  if (u.startsWith('/img?')) { try { raw = new URLSearchParams(u.slice(5)).get('u') || ''; } catch { raw = ''; } }
  return raw.startsWith('https://image.tmdb.org/') ? tmdbProxy(raw) : '';
}

/** Seconds until a download's eta: the server sends an ISO time (Download.eta); older callers sent seconds. */
export function etaSecs(eta, now = Date.now()) {
  if (eta == null || eta === '') return 0;
  if (typeof eta === 'number') return eta;
  const t = Date.parse(eta); return Number.isFinite(t) ? Math.round((t - now) / 1000) : 0;
}
export function etaText(s) {
  s = Math.round(+s || 0);
  if (s >= 172800) return Math.round(s / 86400) + ' days';
  if (s >= 3600) return Math.round(s / 3600) + ' h';
  return Math.max(1, Math.round(s / 60)) + ' min';
}

/** "all seasons" / "season 2" / "seasons 1, 3" for the chosen season numbers (open: how many can be asked for). */
export function seasonsText(list, open) {
  if (list.length > 1 && list.length === open) return 'all seasons';
  return list.length === 1 ? 'season ' + list[0] : 'seasons ' + list.join(', ');
}

/**
 * Adds a row page's titles to a row: only its type (films or shows) when the row has one, each title once.
 * row: {type, items: [], seen: Set of "type:tmdb"}. Returns the titles that were new.
 */
export function addToRow(row, items, own = (t) => t) {
  const fresh = [];
  for (const raw of items || []) {
    if (!raw || !raw.tmdb || (row.type && raw.type !== row.type)) continue;
    const t = own(raw), k = `${t.type}:${t.tmdb}`;
    if (row.seen.has(k)) continue;
    row.seen.add(k); row.items.push(t); fresh.push(t);
  }
  return fresh;
}

/** Whether a row should load its next page now: more pages exist and the focus or scroll is near its end. */
export function wantsMore(row, { index = -1, count = 0, near = 6, scrollLeft = 0, width = 0, scrollWidth = 0, px = 1400 } = {}) {
  if (row.busy || row.page >= row.pages) return false;
  if (index >= 0) return index >= count - near;
  return scrollWidth > 0 && scrollLeft + width > scrollWidth - px;
}

/**
 * The status badge of a Seerr title or request (DESIGN-SPEC-5 §1): {cls, label, icon, pct}.
 * download (optional, when the server has it): {percent: 0..100, eta: ISO time} (Download in API-v1.md).
 */
export function seerrStatus(t, req) {
  const media = (req && req.mediaStatus) || (t && t.status) || 'requestable';
  const rs = (req && req.status) || (t && t.requestStatus) || null;
  const dl = (req && (req.download || req.downloadStatus)) || (t && (t.download || t.downloadStatus)) || null;
  if (media === 'available' || rs === 'completed') return { cls: 'st-avail', label: 'Available', icon: 'check' };
  if (media === 'partial') return { cls: 'st-avail', label: 'Partly available', icon: 'check' };
  if (rs === 'declined') return { cls: 'st-pend', label: 'Declined', icon: 'info' };
  if (rs === 'failed') return { cls: 'st-pend', label: 'Failed', icon: 'info' };
  const p = dl ? (dl.percent != null ? dl.percent : dl.progress) : null;
  if (dl && (p != null || dl.eta != null)) {
    const pct = p != null ? Math.max(0, Math.min(100, Math.round(+p))) : null;
    const secs = etaSecs(dl.eta);
    const eta = secs > 0 ? ' · ~' + etaText(secs) : '';
    return { cls: 'st-dl', label: 'Downloading' + (pct != null ? ` ${pct}%` : '') + eta, icon: 'req', pct };
  }
  if (media === 'processing') return rs === 'pending' ? { cls: 'st-reqd', label: 'Requested', icon: 'clock' } : { cls: 'st-dl', label: 'Downloading', icon: 'req', pct: null };
  if (rs === 'approved') return { cls: 'st-appr', label: 'Approved', icon: 'check' };
  if (rs === 'pending' || media === 'pending') return { cls: 'st-reqd', label: 'Requested', icon: 'clock' };
  return { cls: 'st-req', label: 'Request', icon: 'req', action: true };
}
