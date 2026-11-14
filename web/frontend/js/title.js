// Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
// The page of a catalogue title that is not an IPTV film or show (Plex / Jellyfin items from
// /v1/catalog): #title:<key>. IPTV titles open the full detail page (vod.js) instead.
// Films and episodes play in the web player through the home server (POST /v1/media/play: HLS,
// same origin, no media-server token in the page); the position goes back to Plex / Jellyfin.

import { api, toggleMyList, inMyList } from './api.js';
import { h, toast, spatialMove, menu, fmtDur } from './util.js';
import { heroEl, btn5, metaFor, srcBadge, emptyState, cachedTitle, legend, colorKey, fmtLeft, listEntry, landCard } from './ui5.js';
import { setScene, plainScene } from './backdrop.js';
import { sceneOf } from './feed.js';
import { openPlayer } from './player.js';
import { resumable, epCode, sortSeasons, sortEpisodes, mediaRef } from './mediautil.js';

/** Play a Plex / Jellyfin film or episode. fromStart: from the beginning, else where the media server says. */
export function playMedia(conn, id, o = {}) {
  openPlayer({ media: { conn, id }, title: o.title || '', subtitle: o.subtitle || '', mode: 'vod', startAt: o.fromStart ? 0 : null, onClose: o.onClose });
}

export function titleScreen(main, nav, key) {
  main.innerHTML = '';
  const hero = heroEl('title');
  const below = h('div', { class: 'dt5-below' });
  const scr = h('div', { class: 'screen5 title5' }, hero.el, below);
  main.append(scr);
  const ref = mediaRef(key);
  let it = cachedTitle(key) || (ref ? { key, kind: ref.kind, conn: ref.conn, id: ref.id, title: '', source: null } : null);
  const keys = [{ color: 'red', label: 'My list', action: () => toggle() }];
  const keyHandler = {
    onKey(e) {
      if (e.key.startsWith('Arrow')) { if (!spatialMove(scr, e.key) && (e.key === 'ArrowUp' || e.key === 'ArrowLeft')) nav.focusActive(); return true; }
      if (e.key === 'Enter' && document.activeElement && scr.contains(document.activeElement)) { document.activeElement.click(); return true; }
      if (colorKey(e, keys)) return true;
      if (e.key === 'Escape' || e.key === 'Backspace') { history.back(); return true; }
      return false;
    },
    focusIn() { const f = scr.querySelector('.hero-l.in .focusable'); if (f) f.focus(); },
    destroy() { alive = false; },
  };
  let alive = true;
  if (!it || !it.conn || !it.id) {
    plainScene();
    keys.length = 0;
    scr.append(emptyState('This title is not loaded', 'Open it again from Home, Movies or Shows.', btn5('Home', 'home', () => { location.hash = 'home'; }, { primary: true })));
    return { ...keyHandler, focusIn() { const f = scr.querySelector('.focusable'); if (f) f.focus(); } };
  }
  const show = it.kind === 'show' || it.kind === 'series';
  let seasons = null, season = null, eps = null, epErr = '';
  setScene(sceneOf(it));
  const entry = () => listEntry(it);
  const toggle = () => { const a = toggleMyList(entry()); toast(a ? 'Added to My list' : 'Removed from My list'); draw(); };
  const refresh = () => { if (alive) load(); };

  async function load() {
    try {
      const m = await api.mediaItem(it.conn, it.id);
      if (!alive) return;
      it = { ...it, title: m.title || it.title, year: m.year || it.year, overview: m.overview || it.overview, runtime: m.runtime ? m.runtime / 60 : it.runtime,
        rating: m.rating ?? it.rating, genres: (m.genres && m.genres.length) ? m.genres : it.genres, source: m.source || it.source,
        poster: it.poster || m.poster, backdrop: it.backdrop || m.backdrop, progress: m.progress || it.progress };
      if (!cachedTitle(key)) setScene(sceneOf(it));
    } catch (e) { if (!alive) return; if (!it.title) { toast('Could not load: ' + e.message); } }
    draw();
    if (show) loadSeasons();
  }
  async function loadSeasons() {
    try {
      const r = await api.mediaChildren(it.conn, it.id);
      if (!alive) return;
      seasons = sortSeasons((r.items || []).filter((x) => x.kind === 'season'));
      if (season == null || !seasons.some((s) => s.id === season)) {
        const n = it.next, hit = n && seasons.find((s) => s.season === n.season);
        season = (hit || seasons[0] || {}).id || null;
      }
      await loadEpisodes();
    } catch (e) { if (alive) { epErr = e.message; drawBelow(); } }
  }
  async function loadEpisodes() {
    if (!season) { eps = []; drawBelow(); draw(); return; }
    const want = season;
    try {
      const r = await api.mediaChildren(it.conn, want);
      if (!alive || want !== season) return;
      eps = sortEpisodes((r.items || []).filter((x) => x.kind === 'episode'));
      epErr = '';
    } catch (e) { if (alive) epErr = e.message; }
    drawBelow(); draw();
  }
  /** The episode to offer first: the show's next one (catalogue), else the first unwatched one shown. */
  function nextEpisode() {
    const n = it.next;
    if (n && n.id) return { id: n.id, season: n.season, episode: n.num, title: n.title, progress: eps && (eps.find((e) => e.id === n.id) || {}).progress };
    if (!eps || !eps.length) return null;
    return eps.find((e) => !(e.progress && e.progress.watched)) || eps[0];
  }
  function playEp(e, fromStart) {
    playMedia(it.conn, e.id, { title: it.title, subtitle: [epCode(e), e.title].filter(Boolean).join(' · '), fromStart, onClose: refresh });
  }
  function epMenu(e) {
    if (!resumable(e.progress)) { playEp(e, true); return; }
    menu([epCode(e), e.title].filter(Boolean).join(' · '), [
      { label: `▶ Resume (${fmtDur(e.progress.secs)})`, action: () => playEp(e, false) },
      { label: '⟲ Play from start', action: () => playEp(e, true) },
    ]);
  }
  function buttons() {
    const out = [];
    if (!show) {
      const p = it.progress, res = resumable(p);
      out.push(btn5(res ? `Resume · ${fmtLeft(p.dur - p.secs)}` : 'Play', 'play', () => playMedia(it.conn, it.id, { title: it.title, fromStart: !res, onClose: refresh }), { primary: true, fk: 'main' }));
      if (res) out.push(btn5('From start', 'restart', () => playMedia(it.conn, it.id, { title: it.title, fromStart: true, onClose: refresh }), { fk: 'start' }));
    } else {
      const e = nextEpisode();
      if (e) {
        const res = resumable(e.progress) || !!(it.next && it.next.id === e.id && it.progress && it.progress.secs > 30);
        out.push(btn5(`${res ? 'Resume' : 'Play'} ${epCode(e)}`.trim(), 'play', () => playEp(e, !res), { primary: true, fk: 'main' }));
        if (res) out.push(btn5('Play from start', 'restart', () => playEp(e, true), { fk: 'start' }));
      }
    }
    out.push(btn5(inMyList(it.key) ? 'In My list' : 'My list', inMyList(it.key) ? 'check' : 'plus', toggle, { primary: !out.length, fk: 'fav' }));
    return out;
  }
  function draw() {
    const p = !show && it.progress;
    const e = show ? nextEpisode() : null;
    hero.set([it.key, inMyList(it.key), JSON.stringify(p || null), it.title, (it.overview || '').length, e ? e.id + JSON.stringify(e.progress || null) : ''].join('|'), {
      eyebrow: [srcBadge(it.source), h('span', {}, show ? 'Series' : 'Film')].filter(Boolean),
      title: it.title,
      meta: metaFor(it, p && p.dur && resumable(p) ? [fmtLeft(p.dur - p.secs)] : []),
      synopsis: it.overview,
      buttons: buttons(),
    });
  }
  function drawBelow() {
    if (!show) return;
    const ae = document.activeElement, fk = ae && below.contains(ae) ? ae.dataset.fk : null;
    below.replaceChildren();
    if (epErr) { below.append(emptyState('Could not load the episodes', epErr)); return; }
    if (!seasons) return;
    if (seasons.length > 1) {
      below.append(h('div', { class: 'tabs5', role: 'tablist', 'aria-label': 'Seasons' },
        ...seasons.map((s) => h('button', { class: 'tab focusable' + (s.id === season ? ' on' : ''), type: 'button', role: 'tab', 'aria-selected': s.id === season ? 'true' : 'false', 'data-fk': 's' + s.id,
          onclick: () => { if (season === s.id) return; season = s.id; eps = null; drawBelow(); loadEpisodes(); } }, s.title || ('Season ' + s.season)))));
    }
    const strip = h('div', { class: 'strip eps5' });
    for (const e of eps || []) {
      const pr = e.progress || {};
      const left = resumable(pr) ? `${Math.max(1, Math.round((pr.dur - pr.secs) / 60))} min left` : '';
      const card = landCard({ key: 'e|' + it.conn + '|' + e.id, title: `${e.episode != null ? 'E' + e.episode + ' · ' : ''}${e.title}`, still: e.poster || it.backdrop, poster: it.poster,
        progress: resumable(pr) ? { secs: pr.secs, dur: pr.dur } : null, genres: [] },
      { sub: pr.watched ? 'Watched' : left || (e.runtime ? Math.round(e.runtime / 60) + ' min' : ''), watched: !!pr.watched, onOpen: () => epMenu(e) });
      card.dataset.fk = 'e' + e.id;
      if (pr.watched) card.classList.add('watched');
      strip.append(card);
    }
    if (eps && !eps.length) below.append(emptyState('No episodes', 'This season has no episodes on the server.'));
    else below.append(strip);
    const back = fk && below.querySelector(`[data-fk="${CSS.escape(fk)}"]`);
    if (back) back.focus({ preventScroll: true });
  }
  draw();
  load();
  scr.append(legend(keys, [['OK', 'Play'], ['BACK', 'Close']]));
  return keyHandler;
}
export { plainScene };
