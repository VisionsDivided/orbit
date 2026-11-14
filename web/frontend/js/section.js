// Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
// Movies and Shows (direction 5): one sub-menu row under the top bar,
//   Recommended · Browse [All | Local | IPTV] · Collections …… Any genre ▾ · Sort ▾ · N titles
// The source toggle opens inside the Browse group only while Browse is selected; genre, sort and
// the count show on Browse. Feeds: /v1/catalog/{movies|shows}/recommended | browse | collections and
// /v1/catalog/collections/{id}. Without them (older server, 404) Recommended and Browse are built
// from the /api catalogue (categories stand in for genres) and Collections says it needs the update.
// Routes: #movies[:browse|:collections|:collection:<id>], the same with #series for shows.

import { catalog, SORTS, SOURCES, iptvRef } from './catalog.js';
import { api, cacheGet, cacheSet, toggleMyList, inMyList } from './api.js';
import { h, toast, spatialMove, menu, onItemMenu } from './util.js';
import { uiLoad, uiSave } from './uistate.js';
import { heroEl, rowEl, posterCard, landCard, skHero, skRow, skGrid, emptyState, btn5, ic, srcBadge, imgUrl, legend, colorKey, itemHref, fromVod, listEntry } from './ui5.js';
import { featured, heroParts, continueItems, myListItems, latestItems, cwCard, playItem, enrich, sceneOf } from './feed.js';
import { setScene, settle, plainScene } from './backdrop.js';
import { markSeries, rememberItem } from './vod.js';

const TABS = [['rec', 'Recommended'], ['browse', 'Browse'], ['coll', 'Collections']];
const PAGE = 60;
const ARG = { '': 'rec', recommended: 'rec', browse: 'browse', collections: 'coll' };
const ARG_OF = { rec: '', browse: 'browse', coll: 'collections' };

function memOf(kind) {
  const all = uiLoad().sect || {};
  return Object.assign({ tab: 'rec', source: 'all', genre: '', sort: 'added' }, all[kind] || {});
}
function saveMem(kind, m) { const all = Object.assign({}, uiLoad().sect || {}); all[kind] = m; uiSave({ sect: all }); }

/** kind: 'movie' | 'series' (the screen names movies / series). */
export function sectionScreen(main, kind, nav, arg = '') {
  const screen = kind === 'movie' ? 'movies' : 'series';
  const noun = kind === 'movie' ? 'films' : 'shows';
  const label = kind === 'movie' ? 'Movies' : 'Shows';
  const m = memOf(kind);
  const parts = String(arg || '').split(':');
  let collId = null;
  if (parts[0] === 'collection') { collId = decodeURIComponent(parts.slice(1).join(':')); m.tab = 'coll'; }
  else if (parts[0] in ARG) m.tab = ARG[parts[0]];
  saveMem(kind, m);

  main.innerHTML = '';
  const sub = h('div', { class: 'sub5', role: 'navigation', 'aria-label': label + ' sections' });
  const body = h('div', { class: 'sect-body' });
  const scr = h('div', { class: 'screen5 sect5 tab-' + m.tab }, sub, body);
  main.append(scr);
  let alive = true, ctl = null;      // ctl: the current tab's {onKey?, destroy?, keys?}

  // ---------------------------------------------------------------- the sub-menu row
  const tabBtn = (id, text) => h('button', { class: 'pill focusable' + (m.tab === id && !(id === 'coll' && collId && false) ? ' on' : ''), type: 'button', 'aria-current': m.tab === id ? 'page' : null,
    onclick: () => { location.hash = screen + (ARG_OF[id] ? ':' + ARG_OF[id] : ''); } }, text);
  const genreSel = h('select', { class: 'dd focusable', 'aria-label': 'Genre' }, h('option', { value: '' }, 'Any genre'));
  const sortSel = h('select', { class: 'dd focusable', 'aria-label': 'Sort' }, ...SORTS.map(([v, t]) => h('option', { value: v }, 'Sort: ' + t)));
  sortSel.value = m.sort;
  const count = h('em', { class: 'count5', 'aria-live': 'polite' });
  const seg = h('div', { class: 'seg5', role: 'radiogroup', 'aria-label': 'Source' },
    ...SOURCES.map(([v, t]) => h('button', { class: 'focusable' + (m.source === v ? ' on' : ''), type: 'button', role: 'radio', 'aria-checked': m.source === v ? 'true' : 'false', 'data-src': v,
      onclick: () => setSource(v) }, t)));
  const browseGrp = h('div', { class: 'grp' + (m.tab === 'browse' ? ' open' : '') }, tabBtn('browse', 'Browse'), m.tab === 'browse' ? seg : null);
  const rt = h('div', { class: 'rt' }, h('span', { class: 'dd-wrap' }, ic('filter'), genreSel), h('span', { class: 'dd-wrap' }, sortSel), count);
  sub.append(tabBtn('rec', 'Recommended'), browseGrp, tabBtn('coll', 'Collections'));
  if (m.tab === 'browse') sub.append(rt);
  sub.classList.toggle('wide', m.tab === 'browse');

  function setSource(v) {
    m.source = v; saveMem(kind, m);
    seg.querySelectorAll('button').forEach((b) => { const on = b.dataset.src === v; b.classList.toggle('on', on); b.setAttribute('aria-checked', on ? 'true' : 'false'); });
    if (ctl && ctl.reload) ctl.reload();
  }
  genreSel.addEventListener('change', () => { m.genre = genreSel.value; saveMem(kind, m); if (ctl && ctl.reload) ctl.reload(); });
  sortSel.addEventListener('change', () => { m.sort = sortSel.value; saveMem(kind, m); if (ctl && ctl.reload) ctl.reload(); });

  if (collId) ctl = collectionView(collId);
  else if (m.tab === 'browse') ctl = browseView();
  else if (m.tab === 'coll') ctl = collectionsView();
  else ctl = recommendedView();

  // ---------------------------------------------------------------- Recommended
  function recommendedView() {
    const hero = heroEl('sect');
    const dots = h('div', { class: 'dots5', role: 'tablist', 'aria-label': 'Featured' });
    const rows = h('div', { class: 'rows5' });
    body.append(hero.el, dots, rows);
    hero.el.append(skHero()); rows.append(skRow(5), skRow(8, true), skRow(8, true));
    const feat = featured(hero, (it) => heroParts(it, it._label || ''));
    const onFocus = (it) => feat.feature(it);
    let heroes = [];
    function setDots(cur) {
      dots.replaceChildren(...heroes.map((it, i) => h('button', { class: 'dot' + (it === cur ? ' on' : ''), type: 'button', role: 'tab', 'aria-selected': it === cur ? 'true' : 'false', 'aria-label': it.title, tabindex: '-1',
        onclick: () => { feat.feature(it, true); setDots(it); } })));
    }
    function build(list, hs) {
      if (!alive) return;
      rows.replaceChildren(); hero.el.querySelectorAll('.skel').forEach((x) => x.remove());
      heroes = hs.slice(0, 5);
      heroes.forEach((x) => { x._label = x._label || 'Featured'; });
      for (const r of list) {
        if (!r.items.length) continue;
        const cw = /continue|resume/i.test(r.id + ' ' + r.title);
        rows.append(rowEl(r.title, r.items.map((it) => (cw ? cwCard(it, { onFocus }) : cardWithMenu(it, onFocus))), { posters: !cw, id: r.id }));
      }
      const first = heroes[0] || (list[0] && list[0].items[0]);
      if (first) { feat.feature(first, true); setDots(first); }
      else rows.append(emptyState(`No ${noun} yet`, `Your ${noun} from IPTV, Plex and Jellyfin show up here once a source is set up.`,
        btn5('Open Settings', 'gear', () => { location.hash = 'settings'; }, { primary: true })));
    }
    (async () => {
      let d = null;
      try { d = await catalog.recommended(kind); } catch { d = null; }
      if (!alive) return;
      if (d && (d.rows.length || d.hero.length)) { build(d.rows, d.hero); return; }
      const [cw, latest] = await Promise.all([continueItems().catch(() => []), latestItems(kind).catch(() => ({ items: [] }))]);
      const want = kind === 'movie' ? 'movie' : 'show';
      const list = [
        { id: 'continue', title: 'Continue watching', items: cw.filter((x) => x.kind === want) },
        { id: 'mylist', title: 'My list', items: myListItems(kind) },
        { id: 'latest', title: kind === 'movie' ? 'New films' : 'New shows', items: latest.items },
      ];
      build(list, latest.items.slice(0, 5));
      // a few categories as "Top in <category>" rows
      let cats = await cacheGet('vcat:' + kind);
      if (!cats) { try { cats = (await api.vodCategories(kind)).categories; cacheSet('vcat:' + kind, cats); } catch { cats = []; } }
      for (const c of (cats || []).filter((x) => !String(x.id).startsWith('__') && x.count !== 0).slice(0, 4)) {
        if (!alive) return;
        const r = await latestItems(kind, c.id, 20).catch(() => ({ items: [] }));
        if (alive && r.items.length) rows.append(rowEl(c.name, r.items.map((it) => cardWithMenu(it, onFocus)), { posters: true, id: 'cat-' + c.id }));
      }
    })();
    return {
      keys: () => feat.current(),
      destroy() { feat.cancel(); },
    };
  }

  // ---------------------------------------------------------------- Browse
  function browseView() {
    const grid = h('div', { class: 'grid5', role: 'list' });
    const more = h('div', { class: 'more5', hidden: true }, ...skGrid(8));
    body.append(grid, more);
    let tok = 0, offset = 0, total = 0, loading = false, fallback = null, focusedItem = null;   // fallback: {page}
    const sceneSoon = settle(async (it) => { if (!alive) return; await enrich(it); if (alive && focusedItem === it) setScene(sceneOf(it, { artOpacity: 0.5 })); }, 250);
    plainScene();
    const onFocus = (it, card) => { focusedItem = it; sceneSoon(it); const i = [...grid.children].indexOf(card); if (i > grid.children.length - 24) loadMore(); };
    function setGenres(list, cur) {
      const have = new Set([...genreSel.options].map((o) => o.value));
      const want = [''].concat(list.map((g) => (typeof g === 'string' ? g : g.id)));
      if (want.length === have.size && want.every((v) => have.has(v))) return;
      genreSel.replaceChildren(h('option', { value: '' }, 'Any genre'), ...list.map((g) => (typeof g === 'string' ? h('option', { value: g }, g) : h('option', { value: g.id }, g.name + (g.count ? ` (${g.count})` : '')))));
      genreSel.value = cur || '';
      if (genreSel.value !== (cur || '')) { m.genre = ''; genreSel.value = ''; }
    }
    function setCount(n) { count.textContent = `${n.toLocaleString()} ${n === 1 ? 'title' : 'titles'}`; }
    async function reload() {
      tok++; offset = 0; total = 0; loading = false;
      grid.replaceChildren(...skGrid(16));
      await loadMore(true);
    }
    async function loadMore(first) {
      if (loading || (!first && offset >= total)) return;
      loading = true; const my = tok;
      if (!first) more.hidden = false;
      try {
        let items = [], gotTotal = 0;
        if (fallback === null) {
          const d = await catalog.browse(kind, { source: m.source, genre: m.genre, sort: m.sort, offset, limit: PAGE });
          if (d === null) fallback = { page: 0 };
          else { items = d.items; gotTotal = d.total; if (first) setGenres(d.genres, m.genre); }
        }
        if (fallback) {
          sortSel.parentElement.hidden = true;      // the provider's order only
          if (first) {
            fallback.page = 0;
            let cats = await cacheGet('vcat:' + kind);
            if (!cats) { try { cats = (await api.vodCategories(kind)).categories; cacheSet('vcat:' + kind, cats); } catch { cats = []; } }
            setGenres((cats || []).filter((c) => c.id !== '__all').map((c) => ({ id: c.id, name: c.name, count: c.count })), m.genre);
          }
          if (m.source === 'local') { items = []; gotTotal = 0; }
          else {
            const r = await api.vodList(kind, m.genre || '__all', fallback.page + 1);
            fallback.page = r.page; gotTotal = r.total || 0;
            items = (r.items || []).map((x) => { rememberItem(kind, x); return fromVod(kind, x); });
          }
        }
        if (my !== tok || !alive) return;
        if (first) { grid.replaceChildren(); total = gotTotal; setCount(total); }
        total = gotTotal;
        for (const it of items) grid.append(cardWithMenu(it, onFocus));
        offset += items.length;
        if (!items.length && first) {
          grid.append(m.source === 'local' && fallback
            ? emptyState('No local library yet', 'Films and shows from Plex and Jellyfin appear here once the server has the catalogue update and a media server is linked (Settings ▸ Sources).')
            : emptyState('Nothing here', m.genre ? 'Nothing in this genre. Try another genre or All sources.' : `No ${noun} found.`));
        }
        if (items.length < 1) total = offset;
      } catch (e) {
        if (my === tok && first) grid.replaceChildren(emptyState('Could not load', e.message, btn5('Try again', 'restart', reload, { primary: true })));
      } finally { if (my === tok) { loading = false; more.hidden = true; } }
    }
    const onScroll = () => { if (main.scrollTop + main.clientHeight > main.scrollHeight - 900) loadMore(); };
    main.addEventListener('scroll', onScroll, { passive: true });
    reload();
    return {
      reload,
      keys: () => focusedItem,
      destroy() { main.removeEventListener('scroll', onScroll); sceneSoon.cancel(); },
    };
  }

  // ---------------------------------------------------------------- Collections
  function collectionsView() {
    const grid = h('div', { class: 'cgrid5' }, ...Array.from({ length: 8 }, () => h('span', { class: 'sk sk-land' })));
    body.append(h('h2', { class: 'sect-h' }, 'Collections'), grid);
    plainScene();
    const sceneSoon = settle((c) => { if (alive) setScene({ art: imgUrl(c.backdrop || c.poster) || null, colors: c.colors }); }, 250);
    (async () => {
      let d = null;
      try { d = await catalog.collections(kind); } catch (e) { if (alive) grid.replaceChildren(emptyState('Could not load', e.message)); return; }
      if (!alive) return;
      if (d === null) { grid.replaceChildren(emptyState('Collections need a server update', `Film series and box sets are grouped here once the Orbit server has the new catalogue. Until then, use Browse.`, btn5('Browse ' + noun, 'film', () => { location.hash = screen + ':browse'; }, { primary: true }))); return; }
      if (!d.collections.length) { grid.replaceChildren(emptyState('No collections yet', `Collections of ${noun} show up here when your libraries have them.`)); return; }
      grid.replaceChildren(...d.collections.map((c) => {
        const it = { key: 'coll:' + c.id, title: c.name, backdrop: c.backdrop, poster: c.poster, kind: 'collection', genres: [], source: null };
        return landCard(it, { sub: `${c.count} ${c.count === 1 ? noun.replace(/s$/, '') : noun}`, onFocus: () => sceneSoon(c),
          onOpen: () => { location.hash = `${screen}:collection:${encodeURIComponent(c.id)}`; } });
      }));
    })();
    return { destroy() { sceneSoon.cancel(); } };
  }

  // ---------------------------------------------------------------- one collection
  function collectionView(id) {
    const hero = heroEl('coll');
    const strip = h('div', { class: 'strip posters coll-strip' }, ...skGrid(8));
    body.append(hero.el, h('div', { class: 'rows5' }, h('section', { class: 'row5' }, strip)));
    hero.el.append(skHero());
    let list = [];
    (async () => {
      let d = null;
      try { d = await catalog.collection(id); } catch (e) { if (alive) { hero.el.replaceChildren(); strip.replaceChildren(emptyState('Could not load', e.message)); } return; }
      if (!alive) return;
      if (!d || d.notFound) { hero.el.replaceChildren(); strip.replaceChildren(emptyState('Collection not found', 'It may have been removed from the library.', btn5('All collections', 'list', () => { location.hash = screen + ':collections'; }, { primary: true }))); return; }
      const c = d.collection; list = d.items;
      setScene({ art: imgUrl(c.backdrop || (list[0] && list[0].backdrop)) || null, colors: c.colors || (list[0] && list[0].colors) });
      const years = list.map((x) => x.year).filter(Boolean);
      const done = list.filter((x) => x.raw && x.raw.progress && x.raw.progress.watched).length;
      const next = list.find((x) => !(x.raw && x.raw.progress && x.raw.progress.watched)) || list[0];
      const sources = [...new Set(list.map((x) => x.source).filter(Boolean))];
      const genres = [...new Set(list.flatMap((x) => x.genres || []))].slice(0, 2);
      const nextI = list.indexOf(next);
      hero.set('c:' + id, {
        eyebrow: [h('span', {}, 'Collections ›'), ...sources.map(srcBadge).filter(Boolean)],
        title: c.name,
        meta: [`${list.length || c.count} ${noun}`, years.length ? `${Math.min(...years)} – ${Math.max(...years)}` : null, genres.join(' · ')],
        extra: list.length ? h('div', { class: 'coll-prog' }, h('span', { class: 'pg' }, h('i', { style: { width: ((done / list.length) * 100).toFixed(1) + '%' } })), h('span', { class: 'mut' }, `${done} of ${list.length} watched${next && done ? ' · next up: ' + (kind === 'movie' ? 'film ' : '') + (nextI + 1) : ''}`)) : null,
        synopsis: c.overview,
        buttons: [
          next ? btn5(done ? 'Continue · ' + next.title : 'Play ' + next.title, 'play', () => playItem(next), { primary: true }) : null,
          list[0] && done ? btn5('Play from the first', 'restart', () => playItem(list[0])) : null,
        ].filter(Boolean),
      });
      strip.replaceChildren(...list.map((it, i) => cardWithMenu(it, (x) => setScene(sceneOf(x)), { num: i + 1, noBadge: true })));
    })();
    return { keys: () => null, destroy() {} };
  }

  // ---------------------------------------------------------------- cards and keys
  function toggleList(it) { const a = toggleMyList(listEntry(it)); toast(a ? '★ Added to My list' : 'Removed from My list'); }
  function itemMenu(it) {
    const items = [
      { label: 'Open', action: () => { location.hash = itemHref(it); } },
      { label: '▶ Play', action: () => playItem(it) },
      { label: inMyList(it.key) ? '★ Remove from My list' : '☆ Add to My list', action: () => toggleList(it) },
    ];
    const ref = it.vod || iptvRef(it);
    if (ref && ref.kind === 'series') {
      items.push({ label: '✓ Mark the show as watched', action: () => markSeries(ref.id, true) });
      items.push({ label: '↺ Mark the show as unwatched', action: () => markSeries(ref.id, false) });
    }
    menu(it.title || '', items);
  }
  function cardWithMenu(it, onFocus, o = {}) {
    const c = posterCard(it, { onFocus, ...o });
    onItemMenu(c, () => itemMenu(it));
    return c;
  }
  const keysFor = (it) => [
    { color: 'red', label: 'My list', action: () => { const x = it || (ctl && ctl.keys && ctl.keys()); if (x) toggleList(x); } },
    { color: 'green', label: 'Play', action: () => { const x = it || (ctl && ctl.keys && ctl.keys()); if (x) playItem(x); } },
    { color: 'blue', label: 'More', action: () => { const x = it || (ctl && ctl.keys && ctl.keys()); if (x) itemMenu(x); } },
  ];
  if (m.tab !== 'coll' || collId) scr.append(legend(keysFor(null), [['OK', 'Open']]));

  return {
    onKey(e) {
      if (e.key.startsWith('Arrow')) {
        if (!spatialMove(scr, e.key) && (e.key === 'ArrowUp' || e.key === 'ArrowLeft')) nav.focusActive();
        return true;
      }
      if (e.key === 'Enter' && document.activeElement && scr.contains(document.activeElement) && document.activeElement.tagName !== 'SELECT') { document.activeElement.click(); return true; }
      if ((e.key === 'm' || e.key === 'M' || e.key === 'ContextMenu') && document.activeElement && document.activeElement.classList.contains('has-menu')) {
        document.activeElement.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true })); return true; }
      if ((e.key === 'f' || e.key === 'F') && ctl && ctl.keys && ctl.keys()) { toggleList(ctl.keys()); return true; }
      if (colorKey(e, keysFor(null))) return true;
      if (e.key === 'Escape' || e.key === 'Backspace') { if (collId) { location.hash = screen + ':collections'; return true; } nav.focusActive(); return true; }
      return false;
    },
    focusIn() {
      const f = body.querySelector('.hero-l.in .focusable') || body.querySelector('.card5') || sub.querySelector('.pill.on');
      if (f) f.focus();
    },
    destroy() { alive = false; if (ctl && ctl.destroy) ctl.destroy(); },
  };
}
