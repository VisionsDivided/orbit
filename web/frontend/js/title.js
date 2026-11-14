// Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
// The page of a catalogue title that is not an IPTV film or show (Plex / Jellyfin items from
// /v1/catalog): #title:<key>. IPTV titles open the full detail page (vod.js) instead.
// The browser cannot play Plex / Jellyfin titles yet (their direct-play URLs are on another origin,
// which the page's Content-Security-Policy does not allow), so this page shows the title, My list
// and where it plays.

import { toggleMyList, inMyList } from './api.js';
import { h, toast, spatialMove } from './util.js';
import { heroEl, btn5, metaFor, srcBadge, emptyState, cachedTitle, legend, colorKey, fmtLeft } from './ui5.js';
import { setScene, plainScene } from './backdrop.js';
import { sceneOf } from './feed.js';

export function titleScreen(main, nav, key) {
  main.innerHTML = '';
  const hero = heroEl('title');
  const scr = h('div', { class: 'screen5 title5' }, hero.el);
  main.append(scr);
  const it = cachedTitle(key);
  if (!it) {
    plainScene();
    scr.append(emptyState('This title is not loaded', 'Open it again from Home, Movies or Shows.', btn5('Home', 'home', () => { location.hash = 'home'; }, { primary: true })));
    return { onKey: (e) => { if (e.key.startsWith('Arrow')) { if (!spatialMove(scr, e.key)) nav.focusActive(); return true; } return false; }, focusIn() { const f = scr.querySelector('.focusable'); if (f) f.focus(); } };
  }
  setScene(sceneOf(it));
  const entry = () => ({ key: it.key, kind: it.kind === 'show' ? 'series' : 'movie', name: it.title, poster: it.poster, id: it.key, rating: it.rating, year: it.year, source: it.source });
  const toggle = () => { const a = toggleMyList(entry()); toast(a ? 'Added to My list' : 'Removed from My list'); draw(); };
  function draw() {
    const where = { plex: 'Plex', jellyfin: 'Jellyfin' }[it.source] || 'your media server';
    hero.set(it.key + inMyList(it.key), {
      eyebrow: [srcBadge(it.source), h('span', {}, it.kind === 'show' ? 'Series' : 'Film')].filter(Boolean),
      title: it.title,
      meta: metaFor(it, it.progress && it.progress.dur ? [fmtLeft(it.progress.dur - it.progress.secs)] : []),
      synopsis: it.overview,
      buttons: [btn5(inMyList(it.key) ? 'In My list' : 'My list', inMyList(it.key) ? 'check' : 'plus', toggle, { primary: true })],
      foot: `Plays on the Orbit TV and desktop apps from ${where}. Playing ${where} titles in the browser is not available yet.`,
    }, true);
  }
  draw();
  const keys = [{ color: 'red', label: 'My list', action: toggle }];
  scr.append(legend(keys, [['BACK', 'Close']]));
  return {
    onKey(e) {
      if (e.key.startsWith('Arrow')) { if (!spatialMove(scr, e.key) && (e.key === 'ArrowUp' || e.key === 'ArrowLeft')) nav.focusActive(); return true; }
      if (e.key === 'Enter' && document.activeElement && scr.contains(document.activeElement)) { document.activeElement.click(); return true; }
      if (colorKey(e, keys)) return true;
      if (e.key === 'Escape' || e.key === 'Backspace') { history.back(); return true; }
      return false;
    },
    focusIn() { const f = scr.querySelector('.hero-l.in .focusable'); if (f) f.focus(); },
    destroy() {},
  };
}
export { plainScene };
