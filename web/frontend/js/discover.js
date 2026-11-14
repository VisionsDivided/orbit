// Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
// Discover (Seerr, docs/orbit/API-v1.md "Discover and requests"): a search field, the #1 trending title as
// the hero, then Your requests (landscape cards with a status badge), Trending movies, Trending shows,
// Upcoming, Popular movies and Popular shows. Sub-menu: For you · Movies · Shows · Your requests.
// - Rows scroll sideways and load Seerr's next page near their end (GET /v1/discover/rows/{id}?page=);
//   Trending movies / shows are filtered out of one mixed list, so they keep loading pages until they are full.
// - Card images are loaded ahead of the visible part of a row (ui5.deferImages), through /v1/discover/image.
// - A card (click, Enter / OK) opens the title page (#discover:title:<movie|tv>:<tmdb>, discovertitle.js).
//   Keyboard / remote focus updates the hero after a short settle; a mouse hover never does (it shows a small
//   info card next to the card instead).
// - Search (GET /v1/discover/search?q=&page=): people, then a poster grid that loads more near its end.
// Status badges: Request / Requested / Approved / Downloading N% · ~time / Available (ui5.seerrStatus).

import { apiReq } from './api.js';
import { getJson } from './catalog.js';
import { h, toast, spatialMove } from './util.js';
import { orbitMode, orbitMe } from './orbit.js';
import { heroEl, rowEl, posterCard, landCard, skHero, skRow, skGrid, emptyState, btn5, ratingText, seerrStatus, statusBadge, legend, colorKey, discoverImg, ic, deferImages, itemHref, rememberTitle } from './ui5.js';
import { setScene, settle, plainScene } from './backdrop.js';
import { discoverTitle, titleHash, rememberDiscover } from './discovertitle.js';
import { addToRow, wantsMore } from './discoverlogic.js';

const TABS = [['', 'For you'], ['movies', 'Movies'], ['shows', 'Shows'], ['requests', 'Your requests']];
const keyOf = (t) => `${t.type}:${t.tmdb}`;
const typeName = (t) => (t.type === 'tv' ? 'Show' : 'Film');
const NEAR_END = 6;            // a row loads its next page when the focus is this many cards from its end…
const NEAR_PX = 1400;          // …or when it is scrolled to within this many px of its end
const MIN_FILL = 14;           // a filtered row keeps loading pages until it has this many titles…
const MAX_FILL_PAGES = 4;      // …but asks for at most this many pages in a row to get there
const HOVER_MS = 450;
const MEMO_MS = 10 * 60 * 1000;
const kbd = () => document.documentElement.classList.contains('kbd');

// What the screen showed when a title page was opened, so Back comes to the same rows, pages, search,
// sideways scroll and focused card. One entry; older than MEMO_MS is dropped.
let memo = null;

export function discoverScreen(main, nav, arg = '') {
  if (arg.startsWith('title:')) return discoverTitle(main, nav, arg.slice(6));
  const tab = TABS.some(([v]) => v === arg) ? arg : '';
  const back = memo && memo.tab === tab && Date.now() - memo.at < MEMO_MS ? memo : null;
  memo = null;
  main.innerHTML = '';
  const sub = h('div', { class: 'sub5', role: 'navigation', 'aria-label': 'Discover sections' },
    ...TABS.map(([v, t]) => h('button', { class: 'pill focusable' + (v === tab ? ' on' : ''), type: 'button', 'aria-current': v === tab ? 'page' : null,
      onclick: () => { location.hash = 'discover' + (v ? ':' + v : ''); } }, t)));
  const qIn = h('input', { class: 'dsq focusable', type: 'search', placeholder: 'Search films, shows and people', 'aria-label': 'Search Seerr', enterkeyhint: 'search', autocomplete: 'off', spellcheck: 'false' });
  const qX = h('button', { class: 'dsq-x', type: 'button', 'aria-label': 'Clear the search', tabindex: '-1', hidden: true, onclick: () => { clearSearch(); qIn.focus(); } }, '×');
  const qBox = h('label', { class: 'dsq-box' }, ic('search'), qIn, qX);
  sub.append(h('div', { class: 'rt dsq-rt' }, qBox));
  const hero = heroEl('disc');
  const rows = h('div', { class: 'rows5' });
  const found = h('div', { class: 'dsr5', hidden: true });
  const hover = h('div', { class: 'dhov', hidden: true, 'aria-hidden': 'true' });
  const scr = h('div', { class: 'screen5 disc5' }, sub, hero.el, rows, found, hover);
  main.append(scr);
  plainScene();
  let alive = true, linked = false, focused = null;
  const imgs = deferImages(scr);
  const titles = new Map();     // "type:tmdb" -> Title (statuses update in place after a request)
  const reqs = new Map();       // "type:tmdb" -> the newest Request of this account
  const lists = [];             // the title rows: {id, title, src, type, items, page, pages, busy, strip, seen}
  const pageCache = new Map();  // "src:page" -> Promise of the row page (Trending movies and shows share one list)

  const statusOf = (t) => seerrStatus(t, reqs.get(keyOf(t)));
  const own = (t) => { const k = keyOf(t); if (!titles.has(k)) titles.set(k, t); rememberDiscover(titles.get(k)); return titles.get(k); };
  function open(t, extra) {
    memo = snapshot();
    location.hash = titleHash(t, extra);
  }
  async function request(t) {
    if (!linked) { toast('Your account is not linked to a Seerr user yet: ask the owner to link it'); return; }
    if (t.type === 'tv') { open(t, 'request'); return; }      // shows: choose the seasons on the title page
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

  // ---------------------------------------------------------------- hero
  const heroParts = (t, label) => {
    const st = statusOf(t);
    return {
      eyebrow: [label ? h('span', { class: 'acc' }, label) : null, h('span', {}, st.action ? '· Not in your library' : '· ' + st.label)].filter(Boolean),
      title: t.title,
      meta: [t.year ? String(t.year) : null, ratingText(t.rating) ? h('span', { class: 'r' }, '★ ' + ratingText(t.rating)) : null, typeName(t)].filter(Boolean),
      synopsis: t.overview,
      buttons: [
        t.play ? btn5('Play', 'play', () => { rememberTitle(t.play); location.hash = itemHref(t.play); }, { primary: true, fk: 'play' })
          : st.action ? btn5('Request', 'req', () => request(t), { primary: true, fk: 'req' }) : h('span', { class: 'hero-st', 'data-tk': keyOf(t) }, statusBadge(st)),
        btn5('Details', 'info', () => open(t), { fk: 'details' }),
      ],
    };
  };
  let tok = 0;
  function feature(t, now, label) {
    focused = t;
    const go = () => { const my = ++tok; hero.set(keyOf(t) + statusOf(t).label, heroParts(t, label || t._label || '')); if (my === tok) setScene({ art: discoverImg(t.backdrop) || null, colors: t.colors || null }); };
    if (now) { soon.cancel(); go(); } else soon(go);
  }
  const soon = settle((fn) => fn(), 400);
  // only keyboard / remote focus features a title (a click focuses the card too, but it opens the title page)
  const onFocus = (t, card) => { hideHover(); if (kbd()) feature(t, false); nearEnd(card); };

  // ---------------------------------------------------------------- hover info card (mouse only)
  let hovT = 0;
  function showHover(t, card) {
    clearTimeout(hovT);
    if (!matchMedia('(hover: hover)').matches || kbd()) return;
    hovT = setTimeout(() => {
      if (!card.isConnected || !card.matches(':hover')) return;
      const st = statusOf(t);
      hover.replaceChildren(h('b', {}, t.title), h('span', { class: 'dhov-m' }, [t.year, typeName(t), ratingText(t.rating) ? '★ ' + ratingText(t.rating) : null].filter(Boolean).join(' · ')),
        t.overview ? h('p', {}, t.overview) : null, h('div', {}, statusBadge(st)));
      hover.hidden = false;
      const r = card.getBoundingClientRect(), w = hover.offsetWidth, hh = hover.offsetHeight;
      const right = r.right + 12 + w < innerWidth;
      hover.style.left = Math.max(8, right ? r.right + 12 : r.left - 12 - w) + 'px';
      hover.style.top = Math.max(8, Math.min(innerHeight - hh - 8, r.top)) + 'px';
    }, HOVER_MS);
  }
  function hideHover() { clearTimeout(hovT); hover.hidden = true; }
  main.addEventListener('scroll', hideHover, { passive: true });

  // ---------------------------------------------------------------- cards
  const asItem = (t) => ({ key: keyOf(t), kind: t.type === 'tv' ? 'show' : 'movie', title: t.title, year: t.year, poster: discoverImg(t.poster), backdrop: discoverImg(t.backdrop), rating: t.rating, genres: [], source: null, progress: null });
  function hoverable(card, t) {
    card.addEventListener('mouseenter', () => showHover(t, card));
    card.addEventListener('mouseleave', hideHover);
    card.dataset.tkey = keyOf(t);
    return card;
  }
  function titleCard(t, o = {}) {
    const st = statusOf(t);
    const tag = h('div', { class: 'stb-row', 'data-tk': keyOf(t) }, statusBadge(o.search && st.action ? { ...st, label: 'Not requested' } : st));
    return hoverable(posterCard(asItem(t), { noBadge: true, noFav: true, sub: '', after: tag, defer: true, hoverFocus: false,
      onFocus: (_it, card) => onFocus(t, card), onOpen: () => open(t) }), t);
  }
  function requestCard(r) {
    const t = own(r.title || { type: r.type, tmdb: r.tmdb, title: `${r.type === 'tv' ? 'Show' : 'Film'} ${r.tmdb}`, poster: null, backdrop: null });
    const who = r.requestedBy && r.requestedBy.name ? ' · by ' + r.requestedBy.name : '';
    const seasons = r.seasons && r.seasons.length ? ' · S' + r.seasons.join(', S') : '';
    const tag = h('div', { class: 'stb-row', 'data-tk': keyOf(t) }, statusBadge(seerrStatus(t, r)));
    return hoverable(landCard(asItem(t), { sub: typeName(t) + seasons + who, after: tag, defer: true, hoverFocus: false,
      onFocus: (_it, card) => onFocus(t, card), onOpen: () => open(t) }), t);
  }

  // ---------------------------------------------------------------- rows that load more
  function srcPage(src, page) {
    const k = src + ':' + page;
    if (!pageCache.has(k)) {
      const p = getJson(`/v1/discover/rows/${encodeURIComponent(src)}?page=${page}`, null)
        .then((r) => { if (!r.ok) throw new Error((r.data && r.data.error) || 'could not load'); return r.data; });
      p.catch(() => pageCache.delete(k));
      pageCache.set(k, p);
    }
    return pageCache.get(k);
  }
  const addItems = (row, items) => addToRow(row, items, own);
  async function more(row) {
    if (row.busy || row.page >= row.pages || !alive) return;
    row.busy = true;
    let asked = 0;
    try {
      while (alive && row.page < row.pages && asked < MAX_FILL_PAGES) {
        const d = await srcPage(row.src, row.page + 1); asked++;
        if (!alive) return;
        row.page = d.page || row.page + 1; row.pages = d.pages != null ? d.pages : row.pages;
        const cards = addItems(row, d.items || []).map((t) => titleCard(t));
        if (row.strip && cards.length) { row.strip.append(...cards); cards.forEach((c) => imgs.watch(c)); }
        // a filtered row (only the films or only the shows of a mixed list) keeps going until it has enough
        if (!row.type || row.items.length >= MIN_FILL || cards.length >= NEAR_END) break;
      }
    } catch (e) { if (alive) toast('Could not load more: ' + e.message); } finally { row.busy = false; }
  }
  function nearEnd(card) {
    const strip = card && card.closest('.strip'); if (!strip) return;
    const row = lists.find((r) => r.strip === strip); if (!row) return;
    const cards = strip.children;
    if (wantsMore(row, { index: [...cards].indexOf(card), count: cards.length, near: NEAR_END })) more(row);
  }
  function rowFor(def) {
    const row = { ...def, items: [], seen: new Set(), busy: false, strip: null };
    addItems(row, def.items0 || []);
    if (def.saved) { row.items = []; row.seen.clear(); addItems(row, def.saved); }
    const el = rowEl(def.title, row.items.map((t) => titleCard(t)), { posters: true, id: def.id });
    row.strip = el.querySelector('.strip');
    row.strip.addEventListener('scroll', () => {
      hideHover();
      const s = row.strip; if (wantsMore(row, { scrollLeft: s.scrollLeft, width: s.clientWidth, scrollWidth: s.scrollWidth, px: NEAR_PX })) more(row);
    }, { passive: true });
    lists.push(row);
    return { row, el };
  }

  // ---------------------------------------------------------------- search
  let q = '', sTok = 0, sPage = 0, sPages = 0, sBusy = false, sGrid = null, sSeen = new Set(), sPeople = [];
  const sentinel = h('div', { class: 'dsr-end', 'aria-hidden': 'true' });
  const sIO = typeof IntersectionObserver === 'function' ? new IntersectionObserver((es) => { if (es.some((e) => e.isIntersecting)) searchMore(); }, { root: main, rootMargin: '0px 0px 900px 0px' }) : null;
  const runSoon = settle((v) => runSearch(v), 400);
  qIn.addEventListener('input', () => { const v = qIn.value.trim(); qX.hidden = !qIn.value; if (!v) clearSearch(true); else runSoon(v); });
  qIn.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); runSoon.cancel(); const v = qIn.value.trim(); if (v) runSearch(v, true); } });
  function setMode(searching) {
    found.hidden = !searching; rows.hidden = searching; hero.el.hidden = searching;
    scr.classList.toggle('searching', searching);
    if (searching) { hideHover(); plainScene(); } else if (focused) feature(focused, true);
  }
  function clearSearch(keepText) {
    runSoon.cancel(); sTok++; q = '';
    if (!keepText) qIn.value = '';
    qX.hidden = !qIn.value;
    if (sIO) sIO.disconnect();
    found.replaceChildren(); setMode(false);
  }
  function personCard(p) {
    const ph = discoverImg(p.photo);
    const face = h('span', { class: 'pfc' }, ph ? h('img', { alt: '', loading: 'lazy', decoding: 'async', src: ph }) : h('span', {}, (p.name || '?').slice(0, 1)));
    const sub = (p.knownFor || []).slice(0, 2).map((t) => t.title).join(', ');
    return h('button', { class: 'card5 pcard focusable', type: 'button', 'aria-label': p.name + (sub ? ', known for ' + sub : ''),
      onclick: () => showPerson(p) }, face, h('b', {}, p.name), sub ? h('span', {}, sub) : null);
  }
  function showPerson(p) {
    const list = (p.knownFor || []).map(own);
    const backBtn = btn5('All results', 'back', () => runSearch(q, true), { fk: 'sback' });
    found.replaceChildren(h('div', { class: 'dsr-h' }, backBtn, h('h3', { class: 'rh' }, 'Known for: ' + p.name)),
      list.length ? h('div', { class: 'grid5' }, ...list.map((t) => titleCard(t, { search: true }))) : emptyState('Nothing listed', 'Seerr lists no films or shows for ' + p.name + '.'));
    imgs.watch(found);
    if (sIO) sIO.disconnect();
    backBtn.focus();
  }
  async function runSearch(v, force) {
    if (!force && v === q) return;
    q = v; const my = ++sTok;
    sPage = 0; sPages = 1; sSeen = new Set(); sPeople = [];
    setMode(true);
    main.scrollTop = 0;
    sGrid = h('div', { class: 'grid5' }, ...skGrid(12));
    found.replaceChildren(h('h3', { class: 'rh dsr-t' }, `Results for “${v}”`), sGrid);
    if (sIO) sIO.disconnect();
    await searchMore(my);
  }
  async function searchMore(my = sTok) {
    if (sBusy || !q || sPage >= sPages || my !== sTok) return;
    sBusy = true;
    try {
      const r = await getJson(`/v1/discover/search?q=${encodeURIComponent(q)}&page=${sPage + 1}`, null);
      if (my !== sTok || !alive) return;
      if (!r.ok) throw new Error((r.data && r.data.error) || 'search failed');
      const d = r.data;
      sPage = d.page || sPage + 1; sPages = d.pages || 0;
      sGrid.querySelectorAll('.sk').forEach((x) => x.remove());
      const fresh = (d.results || []).map(own).filter((t) => !sSeen.has(keyOf(t)) && sSeen.add(keyOf(t)));
      const cards = fresh.map((t) => titleCard(t, { search: true }));
      sGrid.append(...cards); cards.forEach((c) => imgs.watch(c));
      if ((d.people || []).length) {
        sPeople.push(...d.people);
        let pr = found.querySelector('.ppl5');
        if (!pr) { pr = rowEl('People', [], { id: 'people' }); pr.classList.add('ppl5'); found.insertBefore(pr, sGrid); }
        pr.querySelector('.strip').append(...d.people.map(personCard));
      }
      if (sPage === 1) {
        const t = found.querySelector('.dsr-t');
        if (t && d.total != null) t.append(h('small', {}, `${d.total} found`));
        if (!fresh.length && !sPeople.length) sGrid.replaceChildren(emptyState('Nothing found', `Seerr found nothing for “${q}”.`));
      }
      if (d.stale) toast('Seerr cannot be reached right now; these are earlier results');
      if (sPage < sPages && sIO) { sGrid.after(sentinel); sIO.observe(sentinel); } else if (sIO) { sIO.disconnect(); sentinel.remove(); }
    } catch (e) {
      if (my === sTok) { sGrid.querySelectorAll('.sk').forEach((x) => x.remove()); if (!sGrid.children.length) sGrid.replaceChildren(emptyState('Search failed', e.message)); else toast('Could not load more: ' + e.message); sPages = sPage; }
    } finally { sBusy = false; }
  }

  // ---------------------------------------------------------------- state for Back from a title page
  function snapshot() {
    const ae = document.activeElement, card = ae && ae.closest && ae.closest('[data-tkey]');
    return {
      tab, at: Date.now(), focusKey: card ? card.dataset.tkey : null, focusRow: card && card.closest('[data-row]') ? card.closest('[data-row]').dataset.row : null,
      rows: lists.map((r) => ({ id: r.id, items: r.items.slice(), page: r.page, pages: r.pages, left: r.strip ? r.strip.scrollLeft : 0 })),
      q, people: sPeople.slice(), results: q ? [...sSeen].map((k) => titles.get(k)).filter(Boolean) : [], sPage, sPages,
      top: main.scrollTop, featured: focused,
    };
  }

  if (!orbitMode()) {
    rows.append(emptyState('Discover needs Orbit accounts', 'Discover and requests go through the household\'s Seerr, per account. Set up accounts in Settings ▸ Profiles first; the owner then connects Seerr.',
      btn5('Open Settings', 'gear', () => { location.hash = 'settings'; }, { primary: true })));
    qBox.hidden = true;
    return controller();
  }
  if (tab !== 'requests') hero.el.append(skHero());
  rows.append(skRow(5), skRow(8, true), skRow(8, true));

  // ---------------------------------------------------------------- load
  (async () => {
    let st = null;
    try { const r = await getJson('/v1/discover/status', null); st = r.ok ? r.data : null; } catch { st = null; }
    if (!alive) return;
    if (!st || !st.configured) {
      hero.el.replaceChildren(); rows.replaceChildren(); qBox.hidden = true;
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
    if (tab === 'requests') { const f = reqList[0] && reqList[0].title; if (f) feature(own(f), true, 'Your request'); restore(); return; }
    if (rowsR && rowsR.error) { rows.append(emptyState('Could not load Discover', rowsR.error.message)); return; }
    const raw = rowsR && rowsR.ok ? rowsR.data.rows || [] : [];
    const by = Object.fromEntries(raw.map((r) => [r.id, r]));
    const src = (id) => by[id] || { items: [], pages: 0 };
    // [id, title, Seerr row, type filter]
    const defs = [
      ['trending-movies', 'Trending movies', 'trending', 'movie'],
      ['trending-shows', 'Trending shows', 'trending', 'tv'],
      ['upcoming', 'Upcoming', 'upcoming', wantType],
      ['popular-movies', 'Popular movies', 'movies', 'movie'],
      ['popular-shows', 'Popular shows', 'tv', 'tv'],
    ].filter(([, , , type]) => !wantType || type === wantType);
    const saved = back ? Object.fromEntries(back.rows.map((r) => [r.id, r])) : {};
    for (const [id, title, s, type] of defs) {
      const sv = saved[id];
      const def = { id, title, src: s, type: type || null, items0: src(s).items || [], page: sv ? sv.page : (src(s).page || 1), pages: sv ? sv.pages : (src(s).pages != null ? src(s).pages : 1), saved: sv ? sv.items : null };
      const { row, el } = rowFor(def);
      if (!row.items.length && row.page >= row.pages) continue;
      rows.append(el);
      if (row.type && row.items.length < MIN_FILL) more(row);     // e.g. Trending shows out of a mostly-film list
    }
    imgs.watch(rows);
    const first = lists.find((r) => r.items.length);
    const top = back && back.featured ? own(back.featured) : first && first.items[0];
    if (top) { if (!back && first) top._label = '#1 ' + first.title.replace(/s$/, ''); feature(top, true); }
    else if (!reqList.length) rows.append(emptyState('Nothing to discover', 'Seerr returned no titles.'));
    restore();
  })();

  /** Back from a title page: the same search, sideways scroll, page scroll and focused card. */
  function restore() {
    if (!back) return;
    for (const r of back.rows) { const row = lists.find((x) => x.id === r.id); if (row && row.strip) row.strip.scrollLeft = r.left; }
    if (back.q) {
      q = back.q; qIn.value = back.q; qX.hidden = false; sTok++; setMode(true);
      sPage = back.sPage; sPages = back.sPages; sSeen = new Set(back.results.map(keyOf)); sPeople = back.people;
      sGrid = h('div', { class: 'grid5' }, ...back.results.map((t) => titleCard(own(t), { search: true })));
      found.replaceChildren(h('h3', { class: 'rh dsr-t' }, `Results for “${q}”`), sGrid);
      if (sPeople.length) { const pr = rowEl('People', sPeople.map(personCard), { id: 'people' }); pr.classList.add('ppl5'); found.insertBefore(pr, sGrid); }
      imgs.watch(found);
      if (sPage < sPages && sIO) { sGrid.after(sentinel); sIO.observe(sentinel); }
    }
    requestAnimationFrame(() => {
      main.scrollTop = back.top || 0;
      const sel = back.focusKey ? `[data-tkey="${CSS.escape(back.focusKey)}"]` : null;
      const scope = back.focusRow ? scr.querySelector(`[data-row="${CSS.escape(back.focusRow)}"]`) : scr;
      const el = sel && ((scope && scope.querySelector(sel)) || scr.querySelector(sel));
      if (el && kbd()) el.focus({ preventScroll: true });
    });
  }

  return controller();

  function controller() {
    const keys = () => [
      { color: 'red', label: 'Request', action: () => { if (focused && statusOf(focused).action) request(focused); } },
      { color: 'blue', label: 'More', action: () => { if (focused) open(focused); } },
    ];
    if (orbitMode()) scr.append(legend(keys(), [['OK', 'Details']]));
    return {
      onKey(e) {
        const ae = document.activeElement;
        if (ae === qIn) {
          if (e.key === 'Escape') { if (qIn.value) { clearSearch(); return true; } qIn.blur(); nav.focusActive(); return true; }
          if (e.key === 'ArrowDown') { const f = (q ? found : scr).querySelector(q ? '.focusable' : '.hero-l.in .focusable, .card5'); if (f) { f.focus(); f.scrollIntoView({ block: 'nearest' }); } return true; }
          if (e.key === 'ArrowUp') { nav.focusActive(); return true; }
          if (e.key === 'ArrowLeft') { if (!spatialMove(scr, e.key)) nav.focusActive(); return true; }
          return false;
        }
        if (e.key.startsWith('Arrow')) { hideHover(); if (!spatialMove(scr, e.key) && (e.key === 'ArrowUp' || e.key === 'ArrowLeft')) nav.focusActive(); return true; }
        if (e.key === 'Enter' && ae && scr.contains(ae)) { ae.click(); return true; }
        if (colorKey(e, keys())) return true;
        if (e.key === 'Escape' || e.key === 'Backspace') { if (q) { clearSearch(); qIn.focus(); return true; } nav.focusActive(); return true; }
        return false;
      },
      focusIn() { const f = scr.querySelector('.hero-l.in .focusable') || scr.querySelector('.card5') || sub.querySelector('.pill.on'); if (f) f.focus(); },
      destroy() { alive = false; soon.cancel(); runSoon.cancel(); hideHover(); imgs.stop(); if (sIO) sIO.disconnect(); main.removeEventListener('scroll', hideHover); },
    };
  }
}
