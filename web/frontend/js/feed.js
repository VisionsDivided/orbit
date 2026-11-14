// Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
// What Home and Recommended share: the featured title (hero + backdrop that follow focus), playing
// an item, and the rows built from the /api data when the server has no /v1/catalog yet
// (Continue watching, My list, latest films / shows, favourite channels on now).

import { api, myList, resumeMap, clearResume, getResume } from './api.js';
import { h, toast, nowSec, menu, onItemMenu } from './util.js';
import { vodInfo, playMovie, playEpisode, continueRows, rememberItem, loadSeriesState } from './vod.js';
import { playLive } from './playback.js';
import { setScene, settle } from './backdrop.js';
import { fromVod, itemHref, imgUrl, landCard, posterCard, btn5, metaFor, srcBadge, fmtLeft, rememberTitle } from './ui5.js';
import { epCode, cleanEpisodeTitle } from './vodutil.js';
import * as G from './guide.js';
import { iptvRef } from './catalog.js';

// ---------------------------------------------------------------- details for the hero
/** Fill in a /api item's backdrop, plot, runtime and genres from its detail info (cached). */
export async function enrich(it) {
  if (!it || !it.vod || it._info) return it;
  try {
    const d = await vodInfo(it.vod.kind, it.vod.id);
    it._info = d;
    it.backdrop = it.backdrop || d.backdrop || null;
    it.overview = it.overview || d.plot || '';
    it.runtime = it.runtime || (d.runtime ? +d.runtime : null);
    if (!it.genres.length && d.genre) it.genres = String(d.genre).split(/\s*[,/]\s*/).filter(Boolean);
    if (!it.year && d.year) it.year = +d.year || null;
    if (d.seasons && it.kind === 'show') it.seasons = d.seasons;
  } catch { it._info = {}; }
  return it;
}
/** The backdrop spec of an item (art = its backdrop, else its poster). */
export const sceneOf = (it, o = {}) => ({ art: imgUrl(it.backdrop || it.poster) || null, colors: it.colors, ...o });

/**
 * The featured title: the hero shows it and the backdrop takes its colours. feature(it) waits
 * about 250 ms (focus moves quickly over cards) unless now = true.
 * partsFor(it) -> hero parts (ui5.heroEl).
 */
export function featured(hero, partsFor, o = {}) {
  let tok = 0, current = null;
  const show = async (it) => {
    const my = ++tok; current = it;
    if (it.vod && !it._info) { hero.set(it.key, partsFor(it)); await enrich(it); if (my !== tok) return; hero.set(it.key, partsFor(it), true); }
    else hero.set(it.key, partsFor(it));
    setScene(sceneOf(it, o.scene || {}));
  };
  const soon = settle(show, 250);
  return { feature(it, now) { if (!it) return; if (now) { soon.cancel(); show(it); } else soon(it); }, current: () => current, cancel: () => soon.cancel() };
}

/** Standard hero parts: eyebrow (source badge + label), title, meta, synopsis, Play / Resume + More info. */
export function heroParts(it, label) {
  const p = it.progress && it.progress.dur ? it.progress : null;
  const isShow = it.kind === 'show';
  const extra = p ? [fmtLeft(p.dur - p.secs)] : [];
  return {
    eyebrow: [srcBadge(it.source), label ? h('span', {}, label) : null].filter(Boolean),
    title: it.title,
    meta: metaFor(it, extra),
    synopsis: it.overview,
    buttons: [
      btn5(p ? 'Resume' : isShow ? 'Episodes' : 'Play', 'play', () => playItem(it), { primary: true, fk: 'hero-play' }),
      btn5('More info', 'info', () => { location.hash = itemHref(it); }, { fk: 'hero-info' }),
    ],
  };
}

// ---------------------------------------------------------------- playing
/** Play / resume an item: films play, a show in Continue watching plays its next episode, anything
 *  else opens its page. */
export async function playItem(it) {
  try {
    if (it.cw) { await playContinue(it.cw); return; }
    const ref = it.vod || iptvRef(it);
    if (ref && ref.kind === 'movie') {
      const d = await vodInfo('movie', ref.id, true);
      const m = { id: ref.id, name: it.title, poster: it.poster, ext: (it.raw && it.raw.ext) || d.ext, key: it.key, rating: it.rating, year: it.year };
      rememberItem('movie', m);
      playMovie(m, d);
      return;
    }
    // a catalogue show with its next episode: play that episode (from where it stopped)
    if (ref && ref.kind === 'series' && it.next && it.next.id != null) {
      const s = { id: ref.id, name: it.title, poster: it.poster, key: it.key };
      rememberItem('series', s);
      const d = await vodInfo('series', ref.id, true);
      try { await loadSeriesState(ref.id); } catch {}
      const ep = (d.episodes || []).find((x) => String(x.id) === String(it.next.id));
      if (ep) { playEpisode(s, d, ep); return; }
    }
  } catch (e) { if (!e.cancelled) toast('Could not play: ' + e.message); return; }
  location.hash = itemHref(it);
}
/** A show's Continue watching row (/api/series/continue): plays its next / unfinished episode. */
export async function playContinue(row) {
  const n = row.next || {};
  const it = { id: row.id, name: row.name || '', poster: row.poster, key: row.key };
  rememberItem('series', it);
  const d = await vodInfo('series', it.id, true);
  try { await loadSeriesState(it.id); } catch {}
  playEpisode(it, d, (d.episodes || []).find((x) => String(x.id) === String(n.id)) || n);
}

// ---------------------------------------------------------------- fallback rows (/api data)
/** Continue watching from this profile's resume points (films) and show progress (shows), newest first. */
export async function continueItems() {
  let shows = [];
  try { shows = await continueRows(); } catch {}
  const res = resumeMap();
  const films = Object.entries(res).filter(([, r]) => !r.done && r.meta && r.dur && r.meta.kind === 'movie')
    .map(([k, r]) => {
      const m = r.meta;
      const it = fromVod('movie', { id: m.id, name: m.title, poster: m.poster, ext: m.ext, key: 'm|' + m.id });
      it.progress = { secs: r.secs, dur: r.dur }; it.at = (r.at || 0) / 1000; it.resumeKey = k;
      return it;
    });
  const eps = shows.map((row) => {
    const n = row.next || {};
    const it = fromVod('series', { id: row.id, name: row.name, poster: row.poster, key: row.key });
    it.still = n.image || null;
    it.progress = n.resume && n.dur ? { secs: n.secs, dur: n.dur } : null;
    const et = cleanEpisodeTitle(row.name, n.title);
    it.sub = [epCode(n), et, it.progress ? fmtLeft(n.dur - n.secs) : ''].filter(Boolean).join(' · ');
    it.cw = row; it.at = row.at || 0;
    return it;
  });
  return films.concat(eps).sort((a, b) => b.at - a.at).slice(0, 20);
}
/** My list (films and shows), optionally one kind ('movie' | 'series'). */
export function myListItems(kind) {
  return myList().filter((x) => (kind ? x.kind === kind : x.kind === 'movie' || x.kind === 'series')).map((x) => {
    const it = fromVod(x.kind, x);
    // a Plex / Jellyfin title (saved from the catalogue): no /api detail page; it opens #title:<key>
    if (x.conn || (x.source && x.source !== 'iptv')) { it.vod = null; it.source = x.source || null; it.conn = x.conn || null; it.id = x.id; }
    return it;
  });
}
/** The newest page of a VOD kind ('movie' | 'series') from the server, as items. */
export async function latestItems(kind, cat = '__all', n = 24) {
  const r = await api.vodList(kind, cat, 1);
  return { total: r.total || 0, items: (r.items || []).slice(0, n).map((x) => { rememberItem(kind, x); return fromVod(kind, x); }) };
}
/** A Continue watching card (landscape) with Resume as OK and a remove menu. */
export function cwCard(it, o = {}) {
  const n = it.next;
  const sub = it.sub || (n ? [n.season != null && n.num != null ? `S${n.season} E${n.num}` : null, n.title, it.progress && it.progress.dur && it.progress.secs ? fmtLeft(it.progress.dur - it.progress.secs) : null].filter(Boolean).join(' · ') : undefined);
  const c = landCard(it, { sub, onFocus: o.onFocus, onOpen: () => playItem(it) });
  onItemMenu(c, () => menu(it.title, [
    { label: '▶ Resume', action: () => playItem(it) },
    { label: 'Open the page', action: () => { location.hash = itemHref(it); } },
    it.resumeKey ? { label: 'Remove from Continue watching', action: () => { clearResume(it.resumeKey); c.remove(); } }
      : it.cw ? { label: 'Remove from Continue watching', action: async () => { await api.seriesHide(it.cw.id).catch(() => {}); c.remove(); } } : null,
  ].filter(Boolean)));
  return c;
}
/** Live channels with what is on now (cards play the channel): the favourites, or else the first channels
 *  of the guide. {title, cards}, or null when there are no channels. */
export async function favChannelCards() {
  const favs = myList().filter((x) => x.kind === 'live');
  let chans = favs.map((x) => x.ch || { key: x.key, name: x.name, logo: x.logo });
  let title = 'Live now on your favourites';
  if (!chans.length) {
    try { chans = ((await api.channels('__all', 1, '', 24)).items || []); } catch { chans = []; }
    title = 'On now';
  }
  if (!chans.length) return null;
  let nn = {};
  try { nn = (await api.nownext(chans.map((c) => c.key).slice(0, 60))).nownext || {}; } catch {}
  const n = nowSec();
  const cards = chans.slice(0, 24).map((c) => {
    const now = nn[c.key] && nn[c.key].now;
    const pr = now ? G.progress(now, n) : null;
    const card = h('button', { class: 'card5 cap5 chcard focusable', type: 'button', 'aria-label': c.name + (now ? ', ' + now.title : '') },
      h('span', { class: 'pic land5 ch' }, c.logo ? h('img', { alt: '', loading: 'lazy', src: api.img(c.logo) }) : h('span', { class: 'ch-name' }, c.name),
        pr ? h('span', { class: 'pg' }, h('i', { style: { width: (pr.frac * 100).toFixed(1) + '%' } })) : null),
      h('b', {}, now ? now.title : c.name), h('span', {}, now ? `${c.name} · ${pr.minLeft} min left` : (c.number ? 'Channel ' + c.number : '')));
    card.addEventListener('click', () => playLive(c, { list: () => chans, group: favs.length ? '__fav' : '__all' }));
    return card;
  });
  return { title, cards };
}export { posterCard, rememberTitle, getResume };
