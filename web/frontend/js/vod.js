// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Films and shows from the /api catalogue: show progress, the details page (direction 5) and
// playback. (The Movies / Shows screens themselves are section.js.)

import { api, inMyList, toggleMyList, getResume, clearResume, setResume, isWatched } from './api.js';
import { h, toast, fmtDur, spatialMove, menu, onItemMenu } from './util.js';
import { heroLayer, btn5, srcBadge, ic, landCard, legend, colorKey, emptyState, skHero, skRow, ratingText } from './ui5.js';
import { setScene, plainScene } from './backdrop.js';
import { openPlayer, playerReplace } from './player.js';
import { withUnlock } from './parental.js';
import { cleanEpisodeTitle, seasonsOf, fmtRuntime, episodeAfter, epCode, isWatchedPos } from './vodutil.js';

// ---------------------------------------------------------------- shows progress (server)
// The server keeps, per series: each episode's position / watched mark, the last episode, the
// next one, and the caption / audio choice. This is the browser's view of it.
const seriesState = new Map();      // series id -> {episodes:{epId:{secs,dur,done,at}}, next, finished, subs, audio}
let progressChain = Promise.resolve();
const seriesChanged = () => window.dispatchEvent(new Event('tvbro-series'));
export async function loadSeriesState(id) {
  await progressChain;
  const s = await api.seriesState(id); seriesState.set(String(id), s); return s;
}
/** Shows "Continue watching" rows (after any progress still being sent). */
export async function continueRows() { await progressChain; return (await api.seriesContinue()).items || []; }
export async function markSeries(sid, watched, scope = {}, what = 'Show') {
  try {
    const s = await api.seriesMark({ seriesId: String(sid), watched, ...scope });
    seriesState.set(String(sid), s); seriesChanged();
    toast(`${what} marked as ${watched ? 'watched' : 'unwatched'}`);
    return s;
  } catch (e) { toast('Could not save: ' + e.message); return null; }
}
function reportProgress(series, e, secs, dur, ended) {
  const sid = String(series.id);
  const st = seriesState.get(sid) || { id: sid, episodes: {} };
  st.episodes = st.episodes || {};
  const old = st.episodes[e.id];
  st.episodes[e.id] = { secs: Math.round(secs), dur: Math.round(dur || 0), at: Date.now() / 1000,
    done: isWatchedPos(secs, dur, ended) || !!(old && old.done && secs < 5) };
  seriesState.set(sid, st);
  const body = { seriesId: sid, epId: String(e.id), secs: Math.round(secs), dur: Math.round(dur || 0), ended: !!ended, name: series.name, poster: series.poster };
  progressChain = progressChain.then(() => api.seriesProgress(body).then((r) => { Object.assign(st, r, { episodes: st.episodes }); }).catch(() => {}));
}
function saveSeriesPrefs(sid, p) {
  const st = seriesState.get(String(sid)); if (st) Object.assign(st, p);
  api.seriesPrefs({ seriesId: String(sid), ...p }).catch(() => {});
}
/** A wide tile for a show in Continue watching: the next / in-progress episode with its progress. */
export function cwTile(row, onChange) {
  const n = row.next || {};
  const it = { id: row.id, name: row.name || '', poster: row.poster, key: row.key };
  rememberItem('series', it);
  const frac = n.dur && n.secs ? Math.min(1, n.secs / n.dur) : 0;
  const et = cleanEpisodeTitle(row.name, n.title);
  const t = h('button', { class: 'tile wide cw-tile focusable' },
    h('img', { class: 'poster wide', loading: 'lazy', alt: '', src: api.img(n.image || row.poster), onerror: function () { if (row.poster && n.image && !this._f) { this._f = 1; this.src = api.img(row.poster); } else this.style.opacity = '.15'; } }),
    frac ? h('div', { class: 'progress tile-prog' }, h('i', { style: { width: (frac * 100).toFixed(1) + '%' } })) : null,
    h('div', { class: 'cap' }, row.name || ''),
    h('div', { class: 'cap2' }, `${epCode(n)}${et ? ' · ' + et : ''}${n.resume && n.dur ? ' · ' + fmtDur(n.dur - n.secs) + ' left' : ''}`));
  const play = async () => {
    try { const d = await vodInfo('series', it.id, true); playEpisode(it, d, (d.episodes || []).find((x) => String(x.id) === String(n.id)) || n); }
    catch (e) { if (!e.cancelled) toast('Could not load: ' + e.message); }
  };
  onItemMenu(t, () => menu(row.name || 'Show', [
    { label: `▶ ${n.resume ? 'Resume' : 'Play'} ${epCode(n)}`, action: play },
    { label: 'Go to the show', action: () => { location.hash = `detail:series:${it.id}`; } },
    { label: '✓ Mark the show as watched', action: async () => { await markSeries(it.id, true); if (onChange) onChange(); } },
    { label: 'Remove from Continue watching', action: async () => { await api.seriesHide(it.id).catch(() => {}); seriesChanged(); if (onChange) onChange(); } },
  ]));
  t.onclick = play;
  t._remove = async () => { await api.seriesHide(it.id).catch(() => {}); seriesChanged(); };
  t._series = it;
  return t;
}

const items = new Map();        // key "movie:id" -> list item (for details deep links)
const infoCache = new Map();    // "movie:id" -> detail json

/** ask: a locked film / show asks for the parental PIN (the detail page, playing); a focus preview never does. */
export async function vodInfo(kind, id, ask = false) {
  const k = kind + ':' + id;
  if (infoCache.has(k)) return infoCache.get(k);
  const p = (ask ? withUnlock(() => api.vodInfo(kind, id)) : api.vodInfo(kind, id)).then((d) => { infoCache.set(k, d); if (infoCache.size > 300) infoCache.delete(infoCache.keys().next().value); return d; });
  infoCache.set(k, p); p.catch(() => infoCache.delete(k));
  return p;
}
export function rememberItem(kind, it) { items.set(kind + ':' + it.id, it); }

// ---------------------------------------------------------------- details (direction 5)
// Hero over the title's backdrop (eyebrow, title, meta, synopsis, actions), then for shows the
// seasons as tabs with the selected season's episodes as landscape cards. Colour keys: My list,
// Play trailer, Mark watched, More; on phones the same four as round buttons under the hero.
export function vodDetail(main, kind, id, nav) {
  main.innerHTML = '';
  const it = items.get(kind + ':' + id) || { id, key: (kind === 'movie' ? 'm|p0|' : 's|p0|') + id, name: '', poster: null };
  const heroBox = h('div', { class: 'hero5 detail' }, skHero());
  const below = h('div', { class: 'dt5-below' }, skRow(5));
  const scr = h('div', { class: 'screen5 dt5' }, heroBox, below);
  main.append(scr);
  plainScene();
  let alive = true, d = null, ss = null, season = null;     // ss: this show's progress (server)
  const refreshSeries = async () => { if (kind === 'series') { try { ss = await loadSeriesState(id); } catch {} } };
  const onClosed = async () => { if (!alive || !d) return; await refreshSeries(); if (alive) render(true); };
  const onSeries = () => { if (alive && d) { ss = seriesState.get(String(id)) || ss; render(true); } };
  window.addEventListener('tvbro-vod-closed', onClosed);
  window.addEventListener('tvbro-series', onSeries);
  Promise.all([vodInfo(kind, id, true), refreshSeries()]).then(([x]) => { if (alive) { d = x; render(); } })
    .catch((e) => { heroBox.replaceChildren(); below.replaceChildren(emptyState('Could not load', e.message, btn5('Back', 'back', () => history.back(), { primary: true }))); });
  const epProg = (e) => (ss && ss.episodes && ss.episodes[e.id]) || null;
  const epName = (e) => `${epCode(e)} · ${cleanEpisodeTitle(it.name, e.title)}`;
  const listEntry = () => ({ key: it.key, kind, name: it.name, poster: it.poster, id, ext: it.ext || (d && d.ext), rating: it.rating || (d && d.rating), year: it.year || (d && d.year) });
  const toggleList = () => { const a = toggleMyList(listEntry()); toast(a ? 'Added to My list' : 'Removed from My list'); render(true); };
  const trailer = () => { if (d && d.trailer) window.open(d.trailer, '_blank', 'noopener'); else toast('No trailer for this title'); };
  const markAll = () => {
    if (kind === 'series') { markSeries(id, !(ss && ss.finished)); return; }
    if (isWatched('m|' + id)) { clearResume('m|' + id); toast('Marked as unwatched'); }
    else { const dur = durSecs(d && (d.duration || (d.runtime && d.runtime * 60))) || 1; setResume('m|' + id, dur, dur, { kind: 'movie', id, ext: listEntry().ext, title: it.name, poster: it.poster }); toast('Marked as watched'); }
    render(true);
  };

  function render(keepFocus) {
    const ae = document.activeElement, fk = keepFocus && ae && scr.contains(ae) ? ae.dataset.fk : null;
    const sy = main.scrollTop;
    const name = it.name || d.name || '';
    it.name = name; it.poster = it.poster || d.cover;
    setScene({ art: api.img(d.backdrop || it.poster) || null, colors: d.colors || null });
    const rt = ratingText(it.rating || d.rating);
    const year = d.year || it.year;
    const run = kind === 'movie' ? fmtRuntime(d.runtime) : (d.seasons ? `${d.seasons} season${d.seasons > 1 ? 's' : ''}` : '');
    const people = [d.director ? 'Director: ' + d.director : null, d.cast ? 'Cast: ' + d.cast : null].filter(Boolean).join(' · ');
    heroBox.replaceChildren(heroLayer({
      eyebrow: [srcBadge('iptv'), h('span', {}, kind === 'movie' ? 'Film' : 'Series')],
      title: name,
      meta: [year ? String(year) : null, rt ? h('span', { class: 'r' }, '★ ' + rt) : null, run, d.genre || it.genre || null].filter(Boolean),
      synopsis: d.plot || '',
      buttons: actionButtons(),
      foot: people || null,
    }, true));
    heroBox.append(phoneActs());
    below.replaceChildren();
    if (kind === 'series') {
      const all = seasonsOf(d.episodes);
      if (all.length) {
        if (season == null || !all.some((s) => s.season === season)) {
          const n = ss && ss.next; const hit = n && all.find((s) => s.eps.some((e) => String(e.id) === String(n.id)));
          season = (hit || all[0]).season;
        }
        const cur = all.find((s) => s.season === season);
        const tabs = h('div', { class: 'tabs5', role: 'tablist', 'aria-label': 'Seasons' },
          ...all.map((s) => h('button', { class: 'tab focusable' + (s.season === season ? ' on' : ''), type: 'button', role: 'tab', 'aria-selected': s.season === season ? 'true' : 'false', 'data-fk': 's' + s.season,
            onclick: () => { season = s.season; render(true); const t = scr.querySelector(`[data-fk="s${s.season}"]`); if (t) t.focus({ preventScroll: true }); } }, 'Season ' + s.season)),
          h('button', { class: 'tab more focusable', type: 'button', 'data-fk': 'smenu', title: 'Season menu', 'aria-label': 'Season ' + season + ' menu', onclick: () => seasonMenu(cur) }, ic('dots')));
        below.append(tabs, episodeStrip(cur));
      }
    }
    scr.querySelectorAll('.ck').forEach((x) => x.remove());
    scr.append(legend(colorKeys(), [['OK', 'Play'], ['BACK', 'Close']]));
    const back = fk && scr.querySelector(`[data-fk="${CSS.escape(fk)}"]`);
    if (back) { back.focus({ preventScroll: true }); main.scrollTop = sy; }
    else if (!keepFocus) { const first = heroBox.querySelector('.btns .focusable'); if (first) first.focus({ preventScroll: true }); }
  }
  const colorKeys = () => [
    { color: 'red', label: 'My list', action: toggleList },
    { color: 'green', label: 'Play trailer', action: trailer },
    { color: 'yellow', label: 'Mark watched', action: markAll },
    { color: 'blue', label: 'More', action: moreMenu },
  ];
  function moreMenu() {
    const list = [{ label: inMyList(it.key) ? '★ Remove from My list' : '☆ Add to My list', action: toggleList }];
    if (d && d.trailer) list.push({ label: '▷ Play trailer', action: trailer });
    if (kind === 'movie') { const r = getResume('m|' + id); if (r && !r.done) list.push({ label: '⟲ Play from start', action: () => playMovie(it, d, true) }); }
    list.push({ label: kind === 'series' ? (ss && ss.finished ? '↺ Mark the show as unwatched' : '✓ Mark the show as watched') : (isWatched('m|' + id) ? '↺ Mark as unwatched' : '✓ Mark as watched'), action: markAll });
    menu(it.name || 'Title', list);
  }
  function phoneActs() {
    const a = (label, icon, fn, on) => h('button', { class: 'act5' + (on ? ' on' : ''), type: 'button', onclick: fn }, h('span', {}, ic(icon)), label);
    const watched = kind === 'series' ? !!(ss && ss.finished) : isWatched('m|' + id);
    return h('div', { class: 'acts5' }, a('My list', inMyList(it.key) ? 'check' : 'plus', toggleList, inMyList(it.key)), a('Trailer', 'play', trailer),
      a('Watched', watched ? 'check' : 'starO', markAll, watched), a('More', 'dots', moreMenu));
  }
  function epMenu(e) {
    const p = epProg(e);
    menu(epName(e), [
      { label: p && !p.done && p.secs > 10 ? `▶ Resume (${fmtDur(p.secs)})` : '▶ Play', action: () => playEpisode(it, d, e) },
      ...(p && !p.done && p.secs > 10 ? [{ label: '⟲ Play from start', action: () => playEpisode(it, d, e, { fromStart: true }) }] : []),
      p && p.done ? { label: '↺ Mark as unwatched', action: () => markSeries(id, false, { epId: String(e.id) }, epCode(e)) }
        : { label: '✓ Mark as watched', action: () => markSeries(id, true, { epId: String(e.id) }, epCode(e)) },
    ]);
  }
  function seasonMenu(s) {
    if (!s) return;
    menu('Season ' + s.season, [
      { label: '✓ Mark season as watched', action: () => markSeries(id, true, { season: s.season }, 'Season ' + s.season) },
      { label: '↺ Mark season as unwatched', action: () => markSeries(id, false, { season: s.season }, 'Season ' + s.season) },
    ]);
  }
  function actionButtons() {
    const out = [];
    if (kind === 'movie') {
      const r = getResume('m|' + id);
      const resume = r && !r.done && r.dur;
      out.push(btn5(resume ? `Resume · ${fmtDur(r.dur - r.secs)} left` : 'Play', 'play', () => playMovie(it, d), { primary: true, fk: 'main' }));
      if (resume) out.push(btn5('From start', 'restart', () => playMovie(it, d, true), { fk: 'start' }));
    } else {
      const eps = seasonsOf(d.episodes).flatMap((s) => s.eps);
      const n = ss && ss.next, find = (x) => eps.find((e) => String(e.id) === String(x.id)) || x;
      if (ss && ss.finished && eps[0]) out.push(btn5(`Watch again ${epCode(eps[0])}`, 'restart', () => playEpisode(it, d, eps[0], { fromStart: true }), { primary: true, fk: 'main' }));
      else if (n && n.resume && n.secs) {
        out.push(btn5(`Resume ${epCode(n)} (${fmtDur(n.secs)})`, 'play', () => playEpisode(it, d, find(n)), { primary: true, fk: 'main' }));
        out.push(btn5('Play from start', 'restart', () => playEpisode(it, d, find(n), { fromStart: true }), { fk: 'start' }));
      } else {
        const e = n ? find(n) : eps[0];
        if (e) out.push(btn5(`Play ${epCode(e)}`, 'play', () => playEpisode(it, d, e, { fromStart: true }), { primary: true, fk: 'main' }));
      }
      const done = !!(ss && ss.finished);
      out.push(btn5(done ? 'Mark as unwatched' : 'Mark as watched', done ? 'restart' : 'check', () => markSeries(id, !done), { fk: 'markall', cls: 'opt' }));
    }
    if (d.trailer) out.push(btn5('Trailer', 'play', trailer, { fk: 'trailer', cls: 'opt' }));
    out.push(btn5(inMyList(it.key) ? 'In My list' : 'My list', inMyList(it.key) ? 'check' : 'plus', toggleList, { fk: 'fav', cls: 'opt' }));
    return out;
  }
  function episodeStrip(s) {
    const strip = h('div', { class: 'strip eps5' });
    s.eps.forEach((e) => {
      const r = epProg(e);
      const left = r && !r.done && r.dur && r.secs ? `${Math.max(1, Math.round((r.dur - r.secs) / 60))} min left` : '';
      const card = landCard({ key: 'e' + e.id, title: `E${e.num} · ${cleanEpisodeTitle(it.name, e.title)}`, still: e.image || d.backdrop || it.poster, poster: it.poster,
        progress: r && !r.done && r.dur && r.secs ? { secs: r.secs, dur: r.dur } : null, genres: [] },
      { sub: r && r.done ? 'Watched' : left || e.duration || '', watched: !!(r && r.done), onOpen: () => playEpisode(it, d, e) });
      card.dataset.fk = 'e' + e.id;
      if (r && r.done) card.classList.add('watched');
      onItemMenu(card, () => epMenu(e));
      strip.append(card);
    });
    return strip;
  }
  return {
    onKey(e) {
      if (e.key.startsWith('Arrow')) { if (!spatialMove(scr, e.key) && (e.key === 'ArrowUp' || e.key === 'ArrowLeft')) nav.focusActive(); return true; }
      if (e.key === 'Enter' && document.activeElement && scr.contains(document.activeElement)) { document.activeElement.click(); return true; }
      if ((e.key === 'm' || e.key === 'M' || e.key === 'ContextMenu') && document.activeElement && document.activeElement.classList.contains('has-menu')) {
        document.activeElement.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true })); return true; }
      if (d && colorKey(e, colorKeys())) return true;
      if (e.key === 'Escape' || e.key === 'Backspace') { history.back(); return true; }
      return false;
    },
    focusIn() { const f = scr.querySelector('.btns .focusable') || scr.querySelector('.focusable'); if (f) f.focus(); },
    destroy() { alive = false; window.removeEventListener('tvbro-vod-closed', onClosed); window.removeEventListener('tvbro-series', onSeries); },
  };
}

// ---------------------------------------------------------------- playback
const vodClosed = () => window.dispatchEvent(new Event('tvbro-vod-closed'));
/** '01:19:13' / '45:10' / seconds -> seconds (0 when unknown). */
const durSecs = (v) => { if (!v) return 0; if (typeof v === 'number') return v; const p = String(v).split(':').map(Number); return p.some(isNaN) ? 0 : p.reduce((a, b) => a * 60 + b, 0); };
export function playMovie(it, d, fromStart) {
  if (fromStart) clearResume('m|' + it.id);
  const ext = (d && d.ext) || it.ext;
  openPlayer({ vod: { kind: 'movie', id: it.id, ext }, duration: durSecs(d && (d.duration || (d.runtime && d.runtime * 60))), title: it.name, mode: 'vod', resumeKey: 'm|' + it.id, onClose: vodClosed,
    resumeMeta: { kind: 'movie', id: it.id, ext, title: it.name, poster: it.poster } });
}
/**
 * Play an episode of a show: resumes its own position (unless fromStart), reports progress to the
 * server per series, re-applies the show's captions / audio language, and offers "Up next"
 * (the next episode in order, into the next season). An open player switches episodes in place.
 */
export function playEpisode(series, d, e, o = {}) {
  const sid = String(series.id);
  const st = seriesState.get(sid);
  if (!st) {     // (normally loaded with the show's page / Continue watching)
    loadSeriesState(sid).catch(() => seriesState.set(sid, { id: sid, episodes: {} })).then(() => playEpisode(series, d, e, o));
    return;
  }
  const p = st.episodes && st.episodes[e.id];
  const startAt = o.fromStart ? 0 : (p && !p.done && p.secs > 10 && (!p.dur || p.secs < p.dur - 30) ? p.secs : 0);
  const popts = {
    vod: { kind: 'episode', id: e.id, ext: e.ext, series: sid }, duration: durSecs(e.duration), title: series.name,
    subtitle: `${epCode(e)} · ${cleanEpisodeTitle(series.name, e.title)}`, mode: 'vod', onClose: vodClosed, startAt,
    progress: (secs, dur, ended) => reportProgress(series, e, secs, dur, ended),
    subsPref: st.subs || null, onSubsChange: (v) => saveSeriesPrefs(sid, { subs: v }),
    audioPref: st.audio || null, onAudioChange: (v) => saveSeriesPrefs(sid, { audio: v }),
    upNext: () => {
      const n = episodeAfter(d.episodes, e.id);
      return n ? { label: `${epCode(n)} – ${cleanEpisodeTitle(series.name, n.title)}`, go: () => playEpisode(series, d, n) } : null;
    },
    hasEpisode: (dir) => !!episodeAfter(d.episodes, e.id, dir),
    episodeNav: (dir, auto) => { const n = episodeAfter(d.episodes, e.id, dir); if (n) playEpisode(series, d, n); else if (!auto) toast(dir > 0 ? 'Last episode' : 'First episode'); },
  };
  if (!playerReplace(popts)) openPlayer(popts);
}
