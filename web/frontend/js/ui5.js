// Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
// Direction 5 building blocks shared by Home, Movies / Shows, collections, details and Discover:
// hero block, poster and landscape cards, rows, skeletons, the colour-key legend, status badges.
// Cards are <button class="focusable">, so spatialMove (util.js) and the app's key handling work
// unchanged. Text always goes in as text (never as HTML).

import { h, fmtDur } from './util.js';
import { api, getResume, isWatched, inMyList } from './api.js';
import { iptvRef } from './catalog.js';

const SVGNS = 'http://www.w3.org/2000/svg';
/** An icon from the sprite in index.html. */
export function ic(name, cls) {
  const s = document.createElementNS(SVGNS, 'svg');
  s.setAttribute('class', 'ic' + (cls ? ' ' + cls : '')); s.setAttribute('aria-hidden', 'true');
  const u = document.createElementNS(SVGNS, 'use'); u.setAttribute('href', '#i-' + name); s.append(u);
  return s;
}
/** Image URLs the page may load: the server's own proxies (signed /img, /v1/media/image) and data:.
 *  TMDB artwork (Discover) is passed through too; the page's CSP decides whether it loads. */
export function imgUrl(u) {
  if (typeof u !== 'string' || !u) return '';
  if (u.startsWith('/img?') || u.startsWith('/v1/') || u.startsWith('data:')) return u;
  if (u.startsWith('https://image.tmdb.org/')) return u;
  return api.img(u);
}
export const ratingText = (r) => { const n = parseFloat(r); return n ? (n > 10 ? (n / 10).toFixed(1) : n.toFixed(1)) : ''; };
const SRC = { iptv: ['IPTV', 'bI'], plex: ['Plex', 'bL'], jellyfin: ['Jellyfin', 'bL'], local: ['Local', 'bL'] };
/** Source badge: Local (cyan) / IPTV (violet). */
export function srcBadge(source) {
  const s = SRC[String(source || '').toLowerCase()]; if (!s) return null;
  return h('span', { class: 'bd ' + s[1] }, s[0]);
}
export function runtimeText(min) { if (!min) return ''; const hh = Math.floor(min / 60), mm = Math.round(min % 60); return hh ? `${hh}h ${String(mm).padStart(2, '0')}m` : `${mm} min`; }

// ---------------------------------------------------------------- items
/** A /api VOD list item (movie / series) in the catalogue Item shape. */
export function fromVod(kind, it) {
  const show = kind === 'series';
  const key = it.key || (show ? 's|' : 'm|') + it.id;
  const r = !show ? getResume('m|' + it.id) : null;
  return {
    key, kind: show ? 'show' : 'movie', title: it.name || '', year: +it.year || null, poster: it.poster || null, backdrop: it.backdrop || null,
    rating: parseFloat(it.rating) || null, genres: it.genre ? String(it.genre).split(/\s*[,/]\s*/).filter(Boolean) : [], source: 'iptv',
    colors: null, progress: r && !r.done && r.dur ? { secs: r.secs, dur: r.dur } : null, overview: it.plot || '', runtime: null,
    vod: { kind: show ? 'series' : 'movie', id: it.id }, raw: it, locked: !!it.locked,
  };
}
/** Where an item opens: the /api detail page for IPTV titles, else the catalogue title page. */
export function itemHref(it) {
  const ref = it.vod || iptvRef(it);
  if (ref) return `detail:${ref.kind}:${ref.id}`;
  return 'title:' + encodeURIComponent(it.key);
}
const titleCache = new Map();
export function rememberTitle(it) { titleCache.set(it.key, it); if (titleCache.size > 500) titleCache.delete(titleCache.keys().next().value); }
export function cachedTitle(key) { return titleCache.get(key) || null; }
const watched = (it) => !!(it.raw && it.raw.progress && it.raw.progress.watched) || (it.vod && it.vod.kind === 'movie' && isWatched('m|' + it.vod.id));
const frac = (p) => (p && p.dur ? Math.max(0, Math.min(1, p.secs / p.dur)) : 0);

function pic(it, cls, wide) {
  const src = imgUrl(wide ? (it.still || it.backdrop || it.poster) : (it.poster || it.backdrop));
  const box = h('span', { class: 'pic ' + cls });
  box.append(h('span', { class: 'pic-t', 'aria-hidden': 'true' }, it.title || ''));
  if (src) {
    const im = h('img', { alt: '', loading: 'lazy', decoding: 'async', src });
    im.addEventListener('error', () => { im.remove(); box.classList.add('noart'); });
    box.append(im);
  } else box.classList.add('noart');
  return box;
}
/** Poster card (2:3) with source badges, watched mark, progress and a caption. */
export function posterCard(it, o = {}) {
  rememberTitle(it);
  const p = pic(it, 'po5');
  const b = o.noBadge ? null : srcBadge(it.source);
  if (b) p.append(h('span', { class: 'po-b' }, b));
  if (o.num != null) p.append(h('span', { class: 'num' }, String(o.num)));
  if (watched(it)) p.append(h('span', { class: 'wt', title: 'Watched' }, ic('check')));
  else if (frac(it.progress)) p.append(h('span', { class: 'pg' }, h('i', { style: { width: (frac(it.progress) * 100).toFixed(1) + '%' } })));
  if (it.locked) p.append(h('span', { class: 'lk', title: 'Locked (parental PIN)' }, '🔒'));
  if (inMyList(it.key) && !o.noFav) p.append(h('span', { class: 'fv', title: 'In My list' }, ic('star')));
  const sub = o.sub != null ? o.sub : [it.year, it.genres && it.genres[0]].filter(Boolean).join(' · ');
  const card = h('button', { class: 'card5 pc focusable', type: 'button', 'aria-label': it.title + (it.year ? `, ${it.year}` : ''), 'data-key': it.key },
    p, h('b', {}, it.title || ''), sub ? h('span', {}, sub) : null, o.after || null);
  wire(card, it, o);
  return card;
}
/** Landscape card (16:9): Continue watching, episodes, collections, requests. */
export function landCard(it, o = {}) {
  rememberTitle(it);
  const p = pic(it, 'land5', true);
  if (o.badge !== false && it.source && o.badges) p.append(h('span', { class: 'po-b' }, srcBadge(it.source)));
  const f = frac(it.progress);
  if (f) p.append(h('span', { class: 'pg' }, h('i', { style: { width: (f * 100).toFixed(1) + '%' } })));
  if (o.watched) p.append(h('span', { class: 'wt', title: 'Watched' }, ic('check')));
  const left = it.progress && it.progress.dur ? fmtLeft(it.progress.dur - it.progress.secs) : '';
  const sub = o.sub != null ? o.sub : [left, it.year].filter(Boolean).join(' · ');
  const card = h('button', { class: 'card5 cap5 focusable' + (o.small ? ' sm' : ''), type: 'button', 'aria-label': it.title, 'data-key': it.key },
    p, h('b', {}, it.title || ''), sub ? h('span', {}, sub) : null, o.after || null);
  wire(card, it, o);
  return card;
}
export function fmtLeft(s) { s = Math.max(0, Math.round(s || 0)); const hh = Math.floor(s / 3600), mm = Math.round((s % 3600) / 60); return hh ? `${hh}h ${String(mm).padStart(2, '0')}m left` : `${Math.max(1, mm)} min left`; }
function wire(card, it, o) {
  card.addEventListener('click', () => { if (o.onOpen) o.onOpen(it, card); else location.hash = itemHref(it); });
  if (o.onFocus) { card.addEventListener('focus', () => o.onFocus(it, card)); card.addEventListener('mouseenter', () => o.onFocus(it, card)); }
}

/** A titled horizontal row of cards. */
export function rowEl(title, cards, o = {}) {
  const strip = h('div', { class: 'strip' + (o.posters ? ' posters' : '') }, ...cards);
  return h('section', { class: 'row5', 'aria-label': title, 'data-row': o.id || '' }, h('h3', { class: 'rh' }, title, o.note ? h('small', {}, o.note) : null), strip);
}
// ---------------------------------------------------------------- skeletons
export const skCards = (n, cls) => Array.from({ length: n }, () => h('span', { class: 'sk ' + cls, 'aria-hidden': 'true' }));
export function skRow(n = 6, posters = false) {
  return h('section', { class: 'row5 skel', 'aria-hidden': 'true' }, h('span', { class: 'sk sk-h' }), h('div', { class: 'strip' + (posters ? ' posters' : '') }, ...skCards(n, posters ? 'sk-po' : 'sk-land')));
}
export function skHero() {
  return h('div', { class: 'hero5 skel', 'aria-hidden': 'true' }, h('span', { class: 'sk sk-eyebrow' }), h('span', { class: 'sk sk-title' }), h('span', { class: 'sk sk-meta' }), h('span', { class: 'sk sk-syn' }), h('span', { class: 'sk sk-btns' }));
}
export function skGrid(n = 16) { return skCards(n, 'sk-po'); }
/** Empty / error state: a short title, a sentence, optional buttons. */
export function emptyState(title, text, ...actions) {
  return h('div', { class: 'empty5', role: 'status' }, h('h3', {}, title), text ? h('p', {}, text) : null, actions.length ? h('div', { class: 'btns' }, ...actions) : null);
}

// ---------------------------------------------------------------- hero
/** A hero block whose content cross-fades (600 ms) when set() gets another title. */
export function heroEl(cls = '') {
  const el = h('div', { class: 'hero5 ' + cls });
  let curKey = null, layer = null;
  return {
    el,
    /** parts: {eyebrow: [nodes], title, meta: [text|node], synopsis, buttons: [nodes], foot} */
    set(key, parts, force) {
      if (key === curKey && !force) return;
      curKey = key;
      const keepFocus = layer && layer.contains(document.activeElement) ? [...layer.querySelectorAll('.focusable')].indexOf(document.activeElement) : -1;
      const n = heroLayer(parts);
      const old = layer; layer = n;
      el.append(n);
      requestAnimationFrame(() => n.classList.add('in'));
      if (old) { old.classList.remove('in'); old.classList.add('out'); old.setAttribute('aria-hidden', 'true'); old.querySelectorAll('.focusable').forEach((b) => { b.tabIndex = -1; b.classList.remove('focusable'); }); setTimeout(() => old.remove(), 650); }
      if (keepFocus >= 0) { const f = n.querySelectorAll('.focusable')[keepFocus] || n.querySelector('.focusable'); if (f) f.focus({ preventScroll: true }); }
    },
    current: () => layer,
  };
}
/** One hero text block (eyebrow, title, meta, synopsis, buttons, foot); `.in` makes it visible. */
export function heroLayer(parts, visible) {
  return h('div', { class: 'hero-l' + (visible ? ' in' : '') },
    parts.eyebrow && parts.eyebrow.length ? h('div', { class: 'eyebrow' }, ...parts.eyebrow) : null,
    h('h1', { class: 'ttl' }, parts.title || ''),
    parts.meta && parts.meta.length ? h('div', { class: 'meta' }, ...parts.meta.filter(Boolean).map((m) => (m.nodeType ? m : h('span', {}, String(m))))) : null,
    parts.extra || null,
    parts.synopsis ? h('p', { class: 'syn' }, parts.synopsis) : null,
    parts.buttons && parts.buttons.length ? h('div', { class: 'btns' }, ...parts.buttons) : null,
    parts.foot ? h('div', { class: 'hero-foot' }, parts.foot) : null);
}
/** Hero buttons: primary (white; filled gradient on phones) / secondary. */
export function btn5(label, icon, onclick, o = {}) {
  return h('button', { class: 'btn5 focusable ' + (o.primary ? 'pri' : 'sec') + (o.cls ? ' ' + o.cls : ''), type: 'button', onclick, 'data-fk': o.fk || null, 'aria-label': o.aria || null },
    icon ? ic(icon) : null, h('span', {}, label));
}
export function metaFor(it, extra = []) {
  const r = ratingText(it.rating);
  return [it.year ? String(it.year) : null, r ? h('span', { class: 'r' }, '★ ' + r) : null, runtimeText(it.runtime), (it.genres || []).slice(0, 2).join(' · '), ...extra].filter(Boolean);
}

// ---------------------------------------------------------------- colour keys
const KEYS = { red: 'kr', green: 'kg', yellow: 'ky', blue: 'kb' };
/** Remote colour keys as they arrive in browsers on TVs. */
export const COLOR_KEY = { ColorF0Red: 'red', ColorF1Green: 'green', ColorF2Yellow: 'yellow', ColorF3Blue: 'blue' };
/** The colour-key legend (bottom-left pill). keys: [{color, label, action}], hints: [[button, label]].
 *  Each key is also a button for mouse / touch users. */
export function legend(keys, hints = []) {
  const el = h('div', { class: 'ck', role: 'toolbar', 'aria-label': 'Colour keys' },
    ...keys.filter(Boolean).map((k) => h('button', { type: 'button', class: 'ck-k', tabindex: '-1', onclick: k.action, title: k.label }, h('i', { class: KEYS[k.color] }), k.label)),
    hints.length ? h('span', { class: 'hn' }, ...hints.flatMap(([b, l], i) => [i ? '   ' : '', h('b', {}, b), ' ' + l])) : null);
  return el;
}
/** Runs the legend action for a remote colour key; true when handled. */
export function colorKey(e, keys) {
  const c = COLOR_KEY[e.key]; if (!c) return false;
  const k = keys.find((x) => x && x.color === c); if (!k) return false;
  k.action(); return true;
}

// ---------------------------------------------------------------- Discover status badges
/**
 * The status badge of a Seerr title or request (DESIGN-SPEC-5 §1): {cls, label, icon, pct}.
 * download (optional, when the server has it): {progress: 0..100, eta: seconds}.
 */
export function seerrStatus(t, req) {
  const media = (req && req.mediaStatus) || (t && t.status) || 'requestable';
  const rs = (req && req.status) || (t && t.requestStatus) || null;
  const dl = (req && (req.download || req.downloadStatus)) || (t && (t.download || t.downloadStatus)) || null;
  if (media === 'available' || rs === 'completed') return { cls: 'st-avail', label: 'Available', icon: 'check' };
  if (media === 'partial') return { cls: 'st-avail', label: 'Partly available', icon: 'check' };
  if (rs === 'declined') return { cls: 'st-pend', label: 'Declined', icon: 'info' };
  if (rs === 'failed') return { cls: 'st-pend', label: 'Failed', icon: 'info' };
  if (dl && (dl.progress != null || dl.eta != null)) {
    const pct = dl.progress != null ? Math.max(0, Math.min(100, Math.round(+dl.progress))) : null;
    const eta = dl.eta ? ' · ~' + etaText(dl.eta) : '';
    return { cls: 'st-dl', label: 'Downloading' + (pct != null ? ` ${pct}%` : '') + eta, icon: 'req', pct };
  }
  if (media === 'processing') return rs === 'pending' ? { cls: 'st-reqd', label: 'Requested', icon: 'clock' } : { cls: 'st-dl', label: 'Downloading', icon: 'req', pct: null };
  if (rs === 'approved') return { cls: 'st-appr', label: 'Approved', icon: 'check' };
  if (rs === 'pending' || media === 'pending') return { cls: 'st-reqd', label: 'Requested', icon: 'clock' };
  return { cls: 'st-req', label: 'Request', icon: 'req', action: true };
}
function etaText(s) { s = Math.round(+s || 0); if (s >= 3600) return Math.round(s / 3600) + ' h'; return Math.max(1, Math.round(s / 60)) + ' min'; }
export function statusBadge(st) {
  const b = h('span', { class: 'stb ' + st.cls }, ic(st.icon), st.label);
  if (st.pct != null) b.style.setProperty('--p', st.pct + '%');
  return b;
}
export { fmtDur };
