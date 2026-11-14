// Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
// The Discover title page, #discover:title:<movie|tv>:<tmdb>[:request] (GET /v1/discover/title/{type}/{tmdb},
// docs/orbit/API-v1.md): backdrop, poster, title, year, rating, runtime or seasons, genres, overview, status,
// who requested it and when, download progress, the actions (Play when the title is in the Orbit catalogue,
// Request — for shows with a season choice like Seerr's —, Trailer, Open in Seerr), the seasons with their own
// status and the cast. ":request" opens the season choice at once (Request from a row on a show).
// While a download runs the page refreshes itself every 30 s.

import { apiReq } from './api.js';
import { getJson } from './catalog.js';
import { h, toast, spatialMove } from './util.js';
import { orbitMode } from './orbit.js';
import { btn5, emptyState, ratingText, runtimeText, seerrStatus, statusBadge, legend, colorKey, discoverImg, ic, itemHref, rememberTitle, etaSecs, etaText, deferImages } from './ui5.js';
import { setScene, plainScene } from './backdrop.js';
import { seasonsText as seasonsOf } from './discoverlogic.js';

const known = new Map();      // "type:tmdb" -> the last Title seen in Discover (shown at once, before the page loads)
export function rememberDiscover(t) { if (t && t.tmdb) { known.set(`${t.type}:${t.tmdb}`, t); if (known.size > 600) known.delete(known.keys().next().value); } }
export function knownDiscover(key) { return known.get(key) || null; }
export const titleHash = (t, extra) => `discover:title:${t.type}:${t.tmdb}${extra ? ':' + extra : ''}`;

const REQ_WORD = { pending: 'waiting for approval', approved: 'approved', declined: 'declined', failed: 'failed', completed: 'completed' };
const fmtDay = (iso) => { const d = new Date(iso); return isNaN(d) ? '' : d.toLocaleDateString([], { day: 'numeric', month: 'short', year: 'numeric' }); };
const fmtBytes = (n) => (n >= 1e9 ? (n / 1e9).toFixed(1) + ' GB' : Math.max(1, Math.round(n / 1e6)) + ' MB');
const POLL_MS = 30000;
/** An image loaded by deferImages when it comes near (no placeholder src). */
const lazyImg = (src) => { const im = Object.assign(document.createElement('img'), { alt: '', decoding: 'async' }); im.dataset.src = src; return im; };
/** Back to Discover: the previous page when there is one (keeps its rows and scroll), else Discover itself. */
const goBack = () => { if (history.length > 1) history.back(); else location.hash = 'discover'; };

export function discoverTitle(main, nav, arg) {
  const [type, id, extra] = String(arg || '').split(':');
  main.innerHTML = '';
  const scr = h('div', { class: 'screen5 dtl5' });
  main.append(scr);
  const tmdb = /^\d{1,9}$/.test(id || '') ? +id : 0;
  if (!orbitMode() || !['movie', 'tv'].includes(type) || !tmdb) {
    plainScene();
    scr.append(h('div', { class: 'dtl-pad' }, emptyState('Title not found', 'Open it again from Discover.', btn5('Discover', 'compass', () => { location.hash = 'discover'; }, { primary: true }))));
    return { onKey: (e) => { if (e.key.startsWith('Arrow')) { if (!spatialMove(scr, e.key)) nav.focusActive(); return true; } if (e.key === 'Escape' || e.key === 'Backspace') { goBack(); return true; } return false; }, focusIn() { const f = scr.querySelector('.focusable'); if (f) f.focus(); } };
  }
  const key = `${type}:${tmdb}`;
  let t = knownDiscover(key), full = false, linked = true, choosing = extra === 'request', pick = new Set(), alive = true, poll = 0, busy = false;
  const imgs = deferImages(scr);

  const statusOf = () => seerrStatus(t);
  const requestable = () => (t.seasons || []).filter((s) => s.status === 'requestable');
  const canRequest = () => (type === 'tv' ? requestable().length > 0 : !!statusOf().action) && !t.play;

  async function load(quiet) {
    try {
      const r = await getJson(`/v1/discover/title/${type}/${tmdb}`, null);
      if (!alive) return;
      if (!r.ok) { if (!quiet) failed('Seerr does not know this title', ''); return; }
      t = r.data; full = true; rememberDiscover(t);
      if (choosing && type === 'tv' && !pick.size) requestable().forEach((s) => pick.add(s.season));
      draw();
    } catch (e) { if (!quiet && alive) failed('Could not load this title', e.message); }
    clearTimeout(poll);
    if (alive && t && (t.download || t.status === 'processing')) poll = setTimeout(() => load(true), POLL_MS);
  }

  function failed(title, why) {
    if (t) { toast(title + (why ? ': ' + why : '')); return; }
    plainScene();
    scr.replaceChildren(h('div', { class: 'dtl-pad' }, emptyState(title, why, btn5('Back', 'back', goBack, { primary: true }))));
    const f = scr.querySelector('.focusable'); if (f) f.focus();
  }

  // ---------------------------------------------------------------- actions
  const play = () => { if (!t.play) return; rememberTitle(t.play); location.hash = itemHref(t.play); };
  const trailer = () => { if (t.trailer) window.open(t.trailer.url, '_blank', 'noopener'); else toast('Seerr has no trailer for this title'); };
  const openSeerr = () => { if (t.seerrUrl) window.open(t.seerrUrl, '_blank', 'noopener'); };
  async function send(seasons) {
    if (!linked) { toast('Your account is not linked to a Seerr user yet: ask the owner to link it'); return; }
    if (busy) return;
    busy = true;
    try {
      const body = { type, tmdb };
      if (type === 'tv') body.seasons = seasons;
      const r = await apiReq('/v1/requests', { method: 'POST', body: JSON.stringify(body) });
      toast(r.created ? `Requested: ${t.title}${type === 'tv' ? ' · ' + seasonsText(seasons) : ''}` : `${t.title} is already ${seerrStatus(r.title || t).label.toLowerCase()}`);
      choosing = false; pick.clear();
      if (r.title) { t = { ...t, ...r.title }; draw(); }
      await load(true);
      const f = scr.querySelector('.dtl-btns .focusable'); if (f) f.focus();
    } catch (e) { toast('Not requested: ' + e.message); } finally { busy = false; }
  }
  function request() {
    if (!canRequest()) return;
    if (type === 'movie') { send(); return; }
    choosing = true;
    if (!pick.size) requestable().forEach((s) => pick.add(s.season));
    draw();
    const f = scr.querySelector('.seas5 .focusable'); if (f) f.focus();
  }
  const seasonsText = (list) => seasonsOf(list, requestable().length);

  // ---------------------------------------------------------------- page
  function draw() {
    const fk = document.activeElement && scr.contains(document.activeElement) ? document.activeElement.dataset.fk : null;
    setScene({ art: discoverImg(t.backdrop) || null, colors: t.colors || null, dim: true });
    const st = statusOf();
    const poster = discoverImg(t.poster);
    const meta = [t.year ? String(t.year) : null, ratingText(t.rating) ? h('span', { class: 'r' }, '★ ' + ratingText(t.rating)) : null,
      type === 'movie' ? runtimeText(t.runtime) : (t.seasonCount ? `${t.seasonCount} season${t.seasonCount === 1 ? '' : 's'}${t.episodeCount ? ' · ' + t.episodeCount + ' episodes' : ''}` : null),
      (t.genres || []).slice(0, 3).join(' · ') || null, type === 'tv' ? 'Series' : 'Film'].filter(Boolean);
    const buttons = [
      t.play ? btn5('Play', 'play', play, { primary: true, fk: 'play' }) : null,
      canRequest() ? btn5(type === 'tv' && (t.seasons || []).some((s) => s.status !== 'requestable') ? 'Request more' : 'Request', 'req', request, { primary: !t.play, fk: 'req' }) : null,
      !t.play && !canRequest() ? h('span', { class: 'hero-st' }, statusBadge(st)) : null,
      t.trailer ? btn5('Trailer', 'play', trailer, { fk: 'trailer' }) : null,
      t.seerrUrl ? btn5('Open in Seerr', 'right', openSeerr, { fk: 'seerr', cls: 'opt' }) : null,
    ].filter(Boolean);
    const info = h('div', { class: 'dtl-info' },
      h('div', { class: 'eyebrow' }, h('span', { class: 'acc' }, 'Discover'), h('span', {}, '· ' + (t.play ? 'In your library' : st.label))),
      h('h1', { class: 'ttl' }, t.title || ''),
      h('div', { class: 'meta' }, ...meta.map((m) => (m.nodeType ? m : h('span', {}, m)))),
      t.tagline ? h('p', { class: 'dtl-tag' }, t.tagline) : null,
      t.overview ? h('p', { class: 'dtl-syn' }, t.overview) : (full ? null : h('p', { class: 'dtl-syn mut' }, 'Loading…')),
      progress(), requestLines(),
      h('div', { class: 'btns dtl-btns' }, ...buttons),
      choosing && type === 'tv' ? chooser() : null);
    const top = h('div', { class: 'dtl-top' },
      h('button', { class: 'pill focusable dtl-back', type: 'button', onclick: goBack, 'data-fk': 'back' }, ic('back'), 'Discover'),
      h('div', { class: 'dtl-po' }, poster ? h('img', { alt: '', src: poster, decoding: 'async' }) : h('span', { class: 'pic-t' }, t.title || '')),
      info);
    const below = h('div', { class: 'dtl-below' }, type === 'tv' && (t.seasons || []).length && !choosing ? seasonsRow() : null, castRow());
    scr.replaceChildren(top, below);
    imgs.watch(scr);
    if (orbitMode()) scr.append(legend(legendKeys(), [['BACK', 'Close']]));
    const back = fk && scr.querySelector(`[data-fk="${CSS.escape(fk)}"]`);
    if (back) back.focus({ preventScroll: true });
  }
  function progress() {
    const dl = t.download; if (!dl) return null;
    const secs = etaSecs(dl.eta);
    return h('div', { class: 'dtl-dl', role: 'status' },
      h('div', { class: 'dtl-dl-t' }, h('b', {}, `Downloading ${dl.percent}%`), h('span', {}, [dl.size ? `${fmtBytes(dl.size - dl.sizeLeft)} of ${fmtBytes(dl.size)}` : null, secs > 0 ? `about ${etaText(secs)} left` : null, dl.items > 1 ? `${dl.items} files` : null].filter(Boolean).join(' · '))),
      h('div', { class: 'pg dtl-pg' }, h('i', { style: { width: dl.percent + '%' } })));
  }
  function requestLines() {
    const list = (t.requests || []).filter((r) => r.status !== 'completed').slice(0, 3);
    if (!list.length) return null;
    return h('div', { class: 'dtl-req' }, ...list.map((r) => h('p', {},
      'Requested by ', h('b', {}, r.by || 'someone'), r.created ? ' on ' + fmtDay(r.created) : '',
      r.seasons && r.seasons.length ? ` · ${r.seasons.length === 1 ? 'season ' + r.seasons[0] : 'seasons ' + r.seasons.join(', ')}` : '',
      ' · ' + (REQ_WORD[r.status] || r.status))));
  }
  function chooser() {
    const open = requestable();
    const all = open.length > 0 && open.every((s) => pick.has(s.season));
    const toggle = (n) => { if (pick.has(n)) pick.delete(n); else pick.add(n); draw(); };
    const allBtn = h('button', { class: 'sea5 all focusable' + (all ? ' on' : ''), type: 'button', 'aria-pressed': String(all), 'data-fk': 'sea-all',
      onclick: () => { if (all) pick.clear(); else open.forEach((s) => pick.add(s.season)); draw(); } }, h('i', { class: 'box' }), h('b', {}, 'All seasons'), h('span', {}, `${open.length} not requested yet`));
    const items = (t.seasons || []).map((s) => {
      const can = s.status === 'requestable', on = can && pick.has(s.season);
      return h('button', { class: 'sea5' + (can ? ' focusable' : ' off') + (on ? ' on' : ''), type: 'button', disabled: !can, 'aria-pressed': can ? String(on) : null, 'data-fk': 'sea-' + s.season,
        onclick: () => { if (can) toggle(s.season); } }, h('i', { class: 'box' }), h('b', {}, s.name || 'Season ' + s.season),
        h('span', {}, `${s.episodes} episode${s.episodes === 1 ? '' : 's'}`), can ? null : statusBadge(seerrStatus({ status: s.status })));
    });
    const chosen = [...pick].filter((n) => open.some((s) => s.season === n)).sort((a, b) => a - b);
    return h('div', { class: 'seas5', role: 'group', 'aria-label': 'Choose seasons to request' },
      h('h3', { class: 'rh' }, 'Request seasons'), allBtn, h('div', { class: 'seas5-l' }, ...items),
      h('div', { class: 'btns' },
        btn5(chosen.length ? `Request ${seasonsText(chosen)}` : 'Choose seasons', 'req', () => { if (chosen.length) send(chosen); else toast('Choose at least one season'); }, { primary: true, fk: 'sea-send' }),
        btn5('Cancel', null, () => { choosing = false; pick.clear(); draw(); const f = scr.querySelector('[data-fk="req"]'); if (f) f.focus(); }, { fk: 'sea-cancel' })));
  }
  function seasonsRow() {
    const cards = t.seasons.map((s) => h('div', { class: 'seac5' }, h('b', {}, s.name || 'Season ' + s.season), h('span', {}, `${s.episodes} episodes`), statusBadge(seerrStatus({ status: s.status }))));
    return h('section', { class: 'row5' }, h('h3', { class: 'rh' }, 'Seasons'), h('div', { class: 'strip seas-strip' }, ...cards));
  }
  function castRow() {
    const cast = t.cast || []; if (!cast.length) return null;
    const cards = cast.map((p) => {
      const ph = discoverImg(p.photo);
      return h('div', { class: 'cast5' }, h('span', { class: 'pfc' }, ph ? lazyImg(ph) : h('span', {}, (p.name || '?').slice(0, 1))),
        h('b', {}, p.name), p.character ? h('span', {}, p.character) : null);
    });
    return h('section', { class: 'row5' }, h('h3', { class: 'rh' }, 'Cast'), h('div', { class: 'strip cast-strip' }, ...cards));
  }
  const legendKeys = () => [
    canRequest() ? { color: 'red', label: 'Request', action: request } : null,
    t.trailer ? { color: 'green', label: 'Trailer', action: trailer } : null,
    t.seerrUrl ? { color: 'blue', label: 'Open in Seerr', action: openSeerr } : null,
  ].filter(Boolean);

  if (t) draw(); else { plainScene(); scr.append(h('div', { class: 'dtl-pad' }, h('div', { class: 'hero5 skel' }, h('span', { class: 'sk sk-title' }), h('span', { class: 'sk sk-meta' }), h('span', { class: 'sk sk-syn' })))); }
  getJson('/v1/discover/status', null).then((r) => { if (r.ok) linked = !!r.data.linked; }).catch(() => {});
  load();

  return {
    onKey(e) {
      if (e.key.startsWith('Arrow')) { if (!spatialMove(scr, e.key) && (e.key === 'ArrowUp' || e.key === 'ArrowLeft')) nav.focusActive(); return true; }
      if (e.key === 'Enter' && document.activeElement && scr.contains(document.activeElement)) { document.activeElement.click(); return true; }
      if (t && colorKey(e, legendKeys())) return true;
      if (e.key === 'Escape' || e.key === 'Backspace') {
        if (choosing) { choosing = false; pick.clear(); draw(); const f = scr.querySelector('[data-fk="req"]'); if (f) f.focus(); return true; }
        goBack(); return true;
      }
      return false;
    },
    focusIn() { const f = scr.querySelector(choosing ? '.seas5 .focusable' : '.dtl-btns .focusable'); if (f) f.focus(); },
    destroy() { alive = false; clearTimeout(poll); imgs.stop(); },
  };
}
