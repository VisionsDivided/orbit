// Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
// Discover (Seerr, docs/orbit/API-v1.md "Discover and requests"): the #1 trending title as the hero
// with Request, then Your requests (landscape cards with a status badge), Trending movies, Trending
// shows, Upcoming and Popular. Sub-menu: For you · Movies · Shows · Your requests.
// Status badges: Request / Requested / Approved / Downloading N% · ~time / Available. The server does
// not send download progress yet, so "Downloading" shows without a percentage until it does
// (ui5.seerrStatus reads `download: {progress, eta}` on a request or title once it is there).

import { apiReq } from './api.js';
import { getJson } from './catalog.js';
import { h, toast, spatialMove, menu } from './util.js';
import { orbitMode, orbitMe } from './orbit.js';
import { heroEl, rowEl, posterCard, landCard, skHero, skRow, emptyState, btn5, ratingText, seerrStatus, statusBadge, legend, colorKey, imgUrl } from './ui5.js';
import { setScene, settle, plainScene } from './backdrop.js';

const TABS = [['', 'For you'], ['movies', 'Movies'], ['shows', 'Shows'], ['requests', 'Your requests']];
const keyOf = (t) => `${t.type}:${t.tmdb}`;
const typeName = (t) => (t.type === 'tv' ? 'Show' : 'Film');

export function discoverScreen(main, nav, arg = '') {
  const tab = TABS.some(([v]) => v === arg) ? arg : '';
  main.innerHTML = '';
  const sub = h('div', { class: 'sub5', role: 'navigation', 'aria-label': 'Discover sections' },
    ...TABS.map(([v, t]) => h('button', { class: 'pill focusable' + (v === tab ? ' on' : ''), type: 'button', 'aria-current': v === tab ? 'page' : null,
      onclick: () => { location.hash = 'discover' + (v ? ':' + v : ''); } }, t)));
  const hero = heroEl('disc');
  const rows = h('div', { class: 'rows5' });
  const scr = h('div', { class: 'screen5 disc5' }, sub, hero.el, rows);
  main.append(scr);
  plainScene();
  let alive = true, linked = false, focused = null;
  const titles = new Map();     // "type:tmdb" -> Title (statuses update in place after a request)
  const reqs = new Map();       // "type:tmdb" -> the newest Request of this account

  if (!orbitMode()) {
    rows.append(emptyState('Discover needs Orbit accounts', 'Discover and requests go through the household\'s Seerr, per account. Set up accounts in Settings ▸ Profiles first; the owner then connects Seerr.',
      btn5('Open Settings', 'gear', () => { location.hash = 'settings'; }, { primary: true })));
    return controller();
  }
  if (tab !== 'requests') hero.el.append(skHero());
  rows.append(skRow(5), skRow(8, true), skRow(8, true));

  const statusOf = (t) => seerrStatus(t, reqs.get(keyOf(t)));
  async function request(t) {
    if (!linked) { toast('Your account is not linked to a Seerr user yet: ask the owner to link it'); return; }
    try {
      const r = await apiReq('/v1/requests', { method: 'POST', body: JSON.stringify({ type: t.type, tmdb: t.tmdb }) });
      if (r.title) Object.assign(t, r.title);
      if (r.request) reqs.set(keyOf(t), r.request);
      toast(r.created ? `Requested: ${t.title}` : `${t.title} is already ${statusOf(t).label.toLowerCase()}`);
      refreshBadges(t);
    } catch (e) { toast('Not requested: ' + e.message); }
  }
  function refreshBadges(t) {
    const k = keyOf(t);
    scr.querySelectorAll(`[data-tk="${CSS.escape(k)}"]`).forEach((el) => el.replaceChildren(statusBadge(statusOf(t))));
    if (focused && keyOf(focused) === k) feature(t, true);
  }
  function details(t) {
    const st = statusOf(t);
    const lines = [{ label: st.action ? 'Request' : st.label, action: () => { if (st.action) request(t); } }];
    getJson(`/v1/discover/title?type=${t.type}&tmdb=${encodeURIComponent(t.tmdb)}`, null).then((r) => {
      const d = r.ok ? r.data : null;
      const seasons = d && d.seasons ? d.seasons.map((s) => ({ label: `${s.name || 'Season ' + s.season} · ${s.episodes} episodes · ${s.status}`, action: () => {} })) : [];
      menu(`${t.title}${t.year ? ' (' + t.year + ')' : ''} · ${typeName(t)}`, [...lines, ...seasons]);
    }).catch(() => menu(t.title, lines));
  }

  // ---------------------------------------------------------------- hero
  const heroParts = (t, label) => {
    const st = statusOf(t);
    return {
      eyebrow: [label ? h('span', { class: 'acc' }, label) : null, h('span', {}, st.action ? '· Not in your library' : '· ' + st.label)].filter(Boolean),
      title: t.title,
      meta: [t.year ? String(t.year) : null, ratingText(t.rating) ? h('span', { class: 'r' }, '★ ' + ratingText(t.rating)) : null, typeName(t)].filter(Boolean),
      synopsis: t.overview,
      buttons: [
        st.action ? btn5('Request', 'req', () => request(t), { primary: true, fk: 'req' }) : h('span', { class: 'hero-st', 'data-tk': keyOf(t) }, statusBadge(st)),
        btn5('Details', 'info', () => details(t), { fk: 'details' }),
      ],
    };
  };
  let tok = 0;
  function feature(t, now, label) {
    focused = t;
    const go = () => { const my = ++tok; hero.set(keyOf(t) + statusOf(t).label, heroParts(t, label || t._label || '')); if (my === tok) setScene({ art: imgUrl(t.backdrop) || null, colors: t.colors || null }); };
    if (now) { soon.cancel(); go(); } else soon(go);
  }
  const soon = settle((fn) => fn(), 250);
  const onFocus = (t) => feature(t, false);

  // ---------------------------------------------------------------- cards
  const asItem = (t) => ({ key: keyOf(t), kind: t.type === 'tv' ? 'show' : 'movie', title: t.title, year: t.year, poster: t.poster, backdrop: t.backdrop, rating: t.rating, genres: [], source: null, progress: null });
  function titleCard(t) {
    const tag = h('div', { class: 'stb-row', 'data-tk': keyOf(t) }, statusBadge(statusOf(t)));
    return posterCard(asItem(t), { noBadge: true, noFav: true, sub: '', after: tag, onFocus: () => onFocus(t),
      onOpen: () => { feature(t, true); main.scrollTo({ top: 0, behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' }); setTimeout(() => { const b = hero.el.querySelector('.hero-l.in .focusable'); if (b) b.focus({ preventScroll: true }); }, 60); } });
  }
  function requestCard(r) {
    const t = r.title || { type: r.type, tmdb: r.tmdb, title: `${r.type === 'tv' ? 'Show' : 'Film'} ${r.tmdb}`, poster: null, backdrop: null };
    titles.set(keyOf(t), titles.get(keyOf(t)) || t);
    const who = r.requestedBy && r.requestedBy.name ? ' · by ' + r.requestedBy.name : '';
    const seasons = r.seasons && r.seasons.length ? ' · S' + r.seasons.join(', S') : '';
    const tag = h('div', { class: 'stb-row', 'data-tk': keyOf(t) }, statusBadge(seerrStatus(t, r)));
    return landCard(asItem(t), { sub: typeName(t) + seasons + who, after: tag, onFocus: () => onFocus(t), onOpen: () => feature(t, true) });
  }

  // ---------------------------------------------------------------- load
  (async () => {
    let st = null;
    try { const r = await getJson('/v1/discover/status', null); st = r.ok ? r.data : null; } catch { st = null; }
    if (!alive) return;
    if (!st || !st.configured) {
      hero.el.replaceChildren(); rows.replaceChildren();
      const me = orbitMe(), admin = me && me.account && me.account.role !== 'member';
      rows.append(emptyState('Seerr is not connected', admin ? 'Connect the household\'s Seerr (Overseerr / Jellyseerr) in Settings ▸ Requests to browse what is new and ask for films and shows.' : 'Ask the owner of this server to connect Seerr.',
        admin ? btn5('Open Settings ▸ Requests', 'gear', () => { location.hash = 'settings:requests'; }, { primary: true }) : null));
      return;
    }
    linked = !!st.linked;
    const [rowsR, reqR] = await Promise.all([
      tab === 'requests' ? Promise.resolve(null) : getJson('/v1/discover/rows', null).catch((e) => ({ error: e })),
      getJson('/v1/requests', null).catch((e) => ({ error: e })),
    ]);
    if (!alive) return;
    const myReqs = reqR && reqR.ok ? (reqR.data.requests || []) : [];
    if (reqR && reqR.ok && reqR.data.linked === false) linked = false;
    for (const r of myReqs) if (r.title || r.tmdb) { const k = `${r.type}:${r.tmdb}`; if (!reqs.has(k)) reqs.set(k, r); }
    rows.replaceChildren(); hero.el.querySelectorAll('.skel').forEach((x) => x.remove());
    if (!linked) rows.append(h('p', { class: 'note5' }, 'Your account is not linked to a Seerr user yet, so you can browse but not request. Ask the owner to link it.'));
    if (st.reachable === false || (rowsR && rowsR.ok && rowsR.data.stale)) rows.append(h('p', { class: 'note5' }, 'Seerr cannot be reached right now; this is the last list the server saw.'));
    const wantType = tab === 'movies' ? 'movie' : tab === 'shows' ? 'tv' : null;
    const reqList = myReqs.filter((r) => !wantType || r.type === wantType);
    if (reqList.length || tab === 'requests') {
      rows.append(reqList.length ? rowEl('Your requests', reqList.map(requestCard), { id: 'requests' })
        : emptyState('No requests yet', 'Request a film or show from For you, and it shows here with its status.'));
    }
    if (tab === 'requests') { const f = reqList[0] && (reqList[0].title); if (f) feature(f, true, 'Your request'); return; }
    if (rowsR && rowsR.error) { rows.append(emptyState('Could not load Discover', rowsR.error.message)); return; }
    const raw = rowsR && rowsR.ok ? rowsR.data.rows || [] : [];
    const by = Object.fromEntries(raw.map((r) => [r.id, r.items || []]));
    const keep = (list) => list.filter((t) => t && t.tmdb && (!wantType || t.type === wantType)).map((t) => { const k = keyOf(t); if (!titles.has(k)) titles.set(k, t); return titles.get(k); });
    const trending = by.trending || [];
    const list = [
      ['trending-movies', 'Trending movies', keep(trending.filter((t) => t.type === 'movie'))],
      ['trending-shows', 'Trending shows', keep(trending.filter((t) => t.type === 'tv'))],
      ['upcoming', 'Upcoming', keep(by.upcoming || [])],
      ['popular-movies', 'Popular movies', keep(by.movies || [])],
      ['popular-shows', 'Popular shows', keep(by.tv || [])],
    ].filter(([, , items]) => items.length);
    for (const [id, title, items] of list) rows.append(rowEl(title, items.map(titleCard), { posters: true, id }));
    const top = list[0] && list[0][2][0];
    if (top) { top._label = '#1 ' + list[0][1].replace(/s$/, '').replace('Trending', 'Trending'); feature(top, true); }
    else if (!reqList.length) rows.append(emptyState('Nothing to discover', 'Seerr returned no titles.'));
  })();

  return controller();

  function controller() {
    const keys = () => [
      { color: 'red', label: 'Request', action: () => { if (focused && statusOf(focused).action) request(focused); } },
      { color: 'blue', label: 'More', action: () => { if (focused) details(focused); } },
    ];
    if (orbitMode()) scr.append(legend(keys(), [['OK', 'Details']]));
    return {
      onKey(e) {
        if (e.key.startsWith('Arrow')) { if (!spatialMove(scr, e.key) && (e.key === 'ArrowUp' || e.key === 'ArrowLeft')) nav.focusActive(); return true; }
        if (e.key === 'Enter' && document.activeElement && scr.contains(document.activeElement)) { document.activeElement.click(); return true; }
        if (colorKey(e, keys())) return true;
        if (e.key === 'Escape' || e.key === 'Backspace') { nav.focusActive(); return true; }
        return false;
      },
      focusIn() { const f = scr.querySelector('.hero-l.in .focusable') || scr.querySelector('.card5') || sub.querySelector('.pill.on'); if (f) f.focus(); },
      destroy() { alive = false; soon.cancel(); },
    };
  }
}
