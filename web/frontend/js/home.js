// Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
// Home (direction 5): the featured title over its cinematic backdrop, then rows. The rows come from
// GET /v1/catalog/home; an older server without it gets rows built from the /api data (Continue
// watching, My list, the newest films and shows, favourite channels on now). Focus on a card makes
// it the featured title (hero text and backdrop cross-fade).

import { catalog } from './catalog.js';
import { h, spatialMove } from './util.js';
import { heroEl, rowEl, posterCard, skHero, skRow, emptyState, btn5, colorKey } from './ui5.js';
import { featured, heroParts, continueItems, myListItems, latestItems, cwCard, favChannelCards, playItem } from './feed.js';
import { plainScene } from './backdrop.js';
import { toggleMyList, inMyList } from './api.js';
import { toast } from './util.js';

export function homeScreen(main, nav) {
  main.innerHTML = '';
  const hero = heroEl('home');
  const rows = h('div', { class: 'rows5' });
  const scr = h('div', { class: 'screen5 home5' }, hero.el, rows);
  main.append(scr);
  hero.el.append(skHero());
  rows.append(skRow(5), skRow(8, true));
  plainScene();
  let alive = true, first = null;
  const feat = featured(hero, (it) => heroParts(it, it._label || ''));
  const onFocus = (it) => feat.feature(it);
  const isCw = (id, title) => /continue|resume/i.test(id + ' ' + title);

  function build(list) {
    if (!alive) return;
    rows.replaceChildren();
    hero.el.querySelectorAll('.skel').forEach((x) => x.remove());
    for (const r of list) {
      if (!r.items.length && !r.cards) continue;
      const cw = isCw(r.id, r.title);
      const cards = r.cards || r.items.map((it) => (cw ? cwCard(it, { onFocus }) : posterCard(it, { onFocus })));
      rows.append(rowEl(r.title, cards, { posters: !cw && !r.cards, id: r.id }));
      if (!first && r.items && r.items.length) { first = r.items.find((x) => x.backdrop) || r.items[0]; first._label = cw ? 'Continue watching' : 'Featured tonight'; }
    }
    if (first) feat.feature(first, true);
    else {
      hero.el.replaceChildren();
      rows.append(emptyState('Nothing to show yet', 'Add a playlist or link a Plex or Jellyfin server, and your films, shows and channels appear here.',
        btn5('Open Settings', 'gear', () => { location.hash = 'settings'; }, { primary: true })));
    }
  }

  (async () => {
    let data = null;
    try { data = await catalog.home(); } catch (e) { data = null; }
    if (!alive) return;
    if (data && data.rows.length) { build(data.rows); return; }
    // older server: the rows from /api
    const [cw, films, shows] = await Promise.all([
      continueItems().catch(() => []),
      latestItems('movie').catch(() => ({ items: [] })),
      latestItems('series').catch(() => ({ items: [] })),
    ]);
    if (!alive) return;
    const list = [
      { id: 'continue', title: 'Continue watching · films and shows', items: cw },
      { id: 'mylist', title: 'My list', items: myListItems() },
      { id: 'movies', title: 'New films', items: films.items },
      { id: 'shows', title: 'New shows', items: shows.items },
    ];
    build(list);
    const ch = await favChannelCards().catch(() => null);
    if (alive && ch && ch.length) rows.append(rowEl('Live now on your favourites', ch, { id: 'live' }));
  })();

  return {
    onKey(e) {
      if (e.key.startsWith('Arrow')) { if (!spatialMove(scr, e.key) && (e.key === 'ArrowUp' || e.key === 'ArrowLeft')) nav.focusActive(); return true; }
      if (e.key === 'Enter' && document.activeElement && scr.contains(document.activeElement)) { document.activeElement.click(); return true; }
      if (e.key === 'Escape' || e.key === 'Backspace') { nav.focusActive(); return true; }
      if ((e.key === 'm' || e.key === 'M' || e.key === 'ContextMenu') && document.activeElement && document.activeElement.classList.contains('has-menu')) {
        document.activeElement.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true })); return true; }
      const it = feat.current();
      if (it && colorKey(e, [{ color: 'red', action: () => { const a = toggleMyList({ key: it.key, kind: it.vod ? it.vod.kind : it.kind, name: it.title, poster: it.poster, id: it.vod ? it.vod.id : it.key, rating: it.rating, year: it.year }); toast(a ? 'Added to My list' : 'Removed from My list'); } },
        { color: 'green', action: () => playItem(it) }])) return true;
      return false;
    },
    focusIn() { const f = scr.querySelector('.hero-l.in .focusable') || scr.querySelector('.focusable'); if (f) f.focus(); },
    destroy() { alive = false; feat.cancel(); },
  };
}
export { inMyList };
