// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Search in direction 5, like the TV's (ui/search/SearchSection.kt): under the top bar a mic button
// (Web Speech API where available) and a big field; before typing the recent searches as chips with
// "Clear history"; after, rows of cards: Channels (logo + what is on now), Live now, Movies, Shows,
// Later and catch-up. The focused or hovered item's details (title, channel, time, description) sit
// under the field. OK on a channel or a "Live now" programme plays it; OK on a later or catch-up
// programme opens its choices (Watch catch-up, Remind me, the channel in My list, Go to channel);
// films and shows open their page. Phones (5b) stack the results vertically.

import { api, inMyList, searchHistory, addSearchHistory, clearSearchHistory } from './api.js';
import { h, toast, fmtTime, fmtDate, nowSec, spatialMove, debounce, menu, onItemMenu } from './util.js';
import * as G from './guide.js';
import { playLive, watchProg } from './playback.js';
import { hasReminder, toggleReminder } from './reminders.js';
import { rememberItem } from './vod.js';
import { toggleFavChannel } from './tv.js';
import { ic, imgUrl, fromVod, posterCard, rowEl, ratingText } from './ui5.js';

let lastQuery = '';

/** "Today", "Tomorrow", "Yesterday" or the date of an epoch second. */
function dayText(sec, now) {
  const d = new Date(sec * 1000), n = new Date(now * 1000);
  const days = Math.round((new Date(d.getFullYear(), d.getMonth(), d.getDate()) - new Date(n.getFullYear(), n.getMonth(), n.getDate())) / 86400000);
  return days === 0 ? 'Today' : days === 1 ? 'Tomorrow' : days === -1 ? 'Yesterday' : fmtDate(sec);
}
const airTime = (p, now) => `${dayText(p.start, now)} ${fmtTime(p.start)} – ${fmtTime(p.stop)}`;
const pct = (f) => (Math.max(0, Math.min(1, f)) * 100).toFixed(1) + '%';

export function searchScreen(main, nav, initial = '') {
  main.innerHTML = '';
  if (initial) lastQuery = initial;
  const box = h('input', { class: 'srch5-in focusable', type: 'text', placeholder: 'Search channels, programmes, movies and shows', value: lastQuery,
    enterkeyhint: 'search', autocomplete: 'off', spellcheck: 'false', 'aria-label': 'Search' });
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  const mic = SR ? h('button', { class: 'srch5-mic focusable', type: 'button', title: 'Search by voice', 'aria-label': 'Search by voice' }, ic('mic')) : null;
  const field = h('label', { class: 'srch5-field' }, ic('search'), box);
  const info = h('div', { class: 'srch5-info', 'aria-live': 'polite' });
  const body = h('div', { class: 'srch5-body' });
  const scr = h('div', { class: 'screen5 srch5' }, h('div', { class: 'srch5-top' + (mic ? '' : ' nomic') }, mic, field), info, body);
  main.append(scr);
  let alive = true, runTok = 0, status = '';

  // ---------------------------------------------------------------- the line under the field
  function setStatus(text) { status = text; info.replaceChildren(h('div', { class: 'srch5-st' }, text)); }
  function setDetail(title, meta, desc) {
    const d = h('p', { class: 'd' }, desc || '');
    info.replaceChildren(h('div', { class: 'srch5-d' }, h('b', { class: 't' }, title || ''), h('div', { class: 'm' }, ...meta.filter(Boolean).flatMap((x, i) => (i ? [h('i', {}, ' · '), x] : [x]))), d));
    return d;
  }

  // ---------------------------------------------------------------- history (before typing)
  function renderHistory() {
    const l = searchHistory();
    scr.classList.add('idle');
    body.replaceChildren(h('section', { class: 'srch5-hist' }, h('h3', { class: 'rh' }, 'Recent searches'),
      l.length ? h('div', { class: 'srch5-chips' },
        ...l.map((q) => h('button', { class: 'pill focusable', type: 'button', onclick: () => { box.value = q; run(q, true); } }, q)),
        h('button', { class: 'pill srch5-clear focusable', type: 'button', onclick: () => { clearSearchHistory(); renderHistory(); box.focus(); } }, 'Clear history'))
        : h('p', { class: 'mut' }, 'Your searches will appear here. Search channels, programmes, movies and shows.')));
  }
  function addHist(q) { if (q.length < 2) return; addSearchHistory(q); }

  const runSoon = debounce((q) => run(q), 200);
  box.addEventListener('input', () => runSoon(box.value.trim()));
  box.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); run(box.value.trim(), true); } });
  box.addEventListener('focus', () => { if (status) setStatus(status); });
  if (mic) mic.onclick = () => {
    const rec = new SR(); rec.lang = navigator.language || 'en-US'; rec.interimResults = false; rec.maxAlternatives = 1;
    mic.classList.add('on'); toast('Listening…');
    rec.onresult = (ev) => { const q = ev.results[0][0].transcript.trim(); box.value = q; run(q, true); };
    rec.onend = () => mic.classList.remove('on');
    rec.onerror = () => { mic.classList.remove('on'); toast('Voice search unavailable'); };
    try { rec.start(); } catch { mic.classList.remove('on'); }
  };
  let ctl = null, shown = '';               // request in flight; query whose results are on screen
  if (lastQuery.length >= 2) run(lastQuery); else { renderHistory(); setStatus(''); }

  async function run(q, remember) {
    lastQuery = q;
    if (remember) addHist(q);
    const my = ++runTok;
    if (ctl) ctl.abort();                     // a newer query cancels the one in flight
    ctl = null;
    if (q.length < 2) { body.classList.remove('busy'); shown = ''; renderHistory(); setStatus(''); return; }
    if (q === shown) { body.classList.remove('busy'); return; }
    // keep the previous results on screen (dimmed) instead of flashing a placeholder
    if (body.querySelector('.row5')) body.classList.add('busy');
    else { body.replaceChildren(); setStatus('Searching…'); }
    const c = ctl = new AbortController();
    let r;
    try { r = await api.search(q, c.signal); }
    catch (e) {
      if (my === runTok && e.name !== 'AbortError') { body.classList.remove('busy'); body.replaceChildren(); setStatus(e.message); }
      return;
    }
    if (my !== runTok || !alive) return;
    ctl = null; shown = q;
    body.classList.remove('busy');
    if (r.indexing) {
      shown = '';
      body.replaceChildren(); setStatus('Preparing search… (first run after an update, a few seconds)');
      setTimeout(() => { if (alive && my === runTok) run(q); }, 3000);
      return;
    }
    render(q, r);
  }

  // ---------------------------------------------------------------- results
  function render(q, r) {
    const now = nowSec();
    // programmes come grouped by channel ([{ch, items}]): on now -> Live now; later / catch-up -> by start time
    const live = [], later = [];
    for (const { ch, items } of r.programs || []) for (const p of items) {
      const si = G.searchInfo(p, now, ch); if (!si) continue;
      (si.kind === 'now' ? live : later).push({ ch, p, si });
    }
    later.sort((a, b) => a.p.start - b.p.start);
    const rows = [];
    const row = (id, title, cards, posters) => { if (cards.length) rows.push(rowEl(title, cards, { id, note: String(cards.length), posters })); };
    row('channels', 'Channels', (r.channels || []).map((c) => channelCard(c, now)));
    row('live', 'Live now', live.map((x) => progCard(x, now)));
    row('movies', 'Movies', (r.movies || []).map((m) => vodCard('movie', m)), true);
    row('shows', 'Shows', (r.series || []).map((s) => vodCard('series', s)), true);
    row('later', 'Later and catch-up', later.map((x) => progCard(x, now)));
    scr.classList.remove('idle');
    const n = (r.channels || []).length + live.length + later.length + (r.movies || []).length + (r.series || []).length;
    body.replaceChildren(...(rows.length ? [h('div', { class: 'rows5' }, ...rows)] : []));
    setStatus((n ? `Results for “${q}” · ${n}` : `No matches for “${q}”`) + (r.ms != null ? ` · ${Math.round(r.ms)} ms` : ''));
  }
  /** Details under the field when an item is focused (keys) or hovered (mouse). */
  function onShow(el, fn) { el.addEventListener('focus', fn); el.addEventListener('mouseenter', fn); }

  function channelCard(c, now) {
    const p = c.now, pr = p ? G.progress(p, now) : null;
    const pic = h('span', { class: 'pic land5 ch' }, c.logo ? h('img', { alt: '', loading: 'lazy', decoding: 'async', src: imgUrl(c.logo) }) : h('span', { class: 'ch-name' }, c.name),
      pr ? h('span', { class: 'pg' }, h('i', { style: { width: pct(pr.frac) } })) : null,
      inMyList(c.key) ? h('span', { class: 'fv', title: 'In My list' }, ic('star')) : null);
    const card = h('button', { class: 'card5 cap5 chcard srch5-c focusable', type: 'button', 'aria-label': c.name + (p ? ', ' + p.title : '') },
      pic, h('b', {}, c.name), h('span', {}, p ? `${p.title} · ${pr.minLeft} min left` : 'No guide data'));
    card.addEventListener('click', () => { addHist(lastQuery); playLive(c); });
    onShow(card, () => setDetail(c.name, p ? [h('span', { class: 'ch' }, 'On now'), p.title, `${fmtTime(p.start)} – ${fmtTime(p.stop)}`, `${pr.minLeft} min left`] : ['No guide data'], ''));
    return card;
  }

  function progCard({ ch, p, si }, now) {
    const pill = si.kind === 'now' ? h('span', { class: 'srch5-pill live' }, '● LIVE')
      : si.kind === 'catchup' ? h('span', { class: 'srch5-pill cu' }, 'Catch-up') : h('span', { class: 'srch5-pill up' }, 'Upcoming');
    const rem = si.kind === 'future' && hasReminder(ch.key, p.start);
    const pic = h('span', { class: 'pic land5 ch' }, ch.logo ? h('img', { alt: '', loading: 'lazy', decoding: 'async', src: imgUrl(ch.logo) }) : h('span', { class: 'ch-name' }, ch.name),
      pill, si.kind === 'now' ? h('span', { class: 'pg' }, h('i', { style: { width: pct(si.frac) } })) : null);
    const sub = si.kind === 'now' ? `${ch.name} · ${si.minLeft} min left` : `${airTime(p, now)} · ${ch.name}` + (rem ? ' · ⏰' : '');
    const card = h('button', { class: 'card5 cap5 chcard srch5-p focusable', type: 'button', 'aria-label': `${p.title}, ${ch.name}` }, pic, h('b', {}, p.title), h('span', {}, sub));
    card.addEventListener('click', () => { addHist(lastQuery); if (si.kind === 'now') watchProg(ch, p, {}); else progMenu(ch, p); });
    onItemMenu(card, () => progMenu(ch, p));
    onShow(card, () => {
      const d = setDetail(p.title, [h('span', { class: 'ch' }, ch.name), airTime(p, nowSec()), p.ep, p.sub], p.desc || '');
      // the result list carries no descriptions: ask for this programme's own
      if (p.desc === undefined) {
        api.prog(ch.key, p.start).then((full) => { p.desc = full.desc || ''; if (d.isConnected) d.textContent = p.desc; })
          .catch(() => { p.desc = ''; });
      }
    });
    return card;
  }
  /** A programme's choices: Watch (live or catch-up), Remind me, the channel in My list, Go to channel. */
  function progMenu(ch, p) {
    const si = G.searchInfo(p, nowSec(), ch);
    menu(`${p.title} · ${ch.name}`, [
      si && si.watch ? { label: si.watch === 'live' ? '▶ Watch live' : '⟲ Watch catch-up', action: () => watchProg(ch, p, {}) } : null,
      si && si.kind === 'future' ? { label: hasReminder(ch.key, p.start) ? '⏰ Cancel reminder' : '⏰ Remind me', action: () => {
        const on = toggleReminder({ key: ch.key, channel: ch.name, title: p.title, start: p.start, stop: p.stop, logo: ch.logo });
        toast(on ? 'Reminder set for ' + fmtTime(p.start) : 'Reminder removed'); rerender();
      } } : null,
      { label: inMyList(ch.key) ? '★ Remove channel from My list' : '☆ Add channel to My list', action: () => { toggleFavChannel(ch); rerender(); } },
      { label: 'Go to channel', action: () => playLive(ch) },
    ].filter(Boolean));
  }
  /** Draws the shown results again (a reminder or a favourite changed), keeping the focused card. */
  function rerender() {
    const q = shown; if (!q) return;
    const ae = document.activeElement, idx = [...body.querySelectorAll('.focusable')].indexOf(ae);
    shown = ''; run(q).then(() => { const f = body.querySelectorAll('.focusable')[idx]; if (idx >= 0 && f) f.focus({ preventScroll: true }); });
  }

  function vodCard(kind, raw) {
    rememberItem(kind, raw);
    const it = fromVod(kind, raw);
    const card = posterCard(it, { sub: [it.year, ratingText(it.rating) ? '★ ' + ratingText(it.rating) : null].filter(Boolean).join(' · ') });
    card.addEventListener('click', () => addHist(lastQuery), true);
    onShow(card, () => setDetail(it.title, [h('span', { class: 'ch' }, kind === 'movie' ? 'Movie' : 'Show'), it.year ? String(it.year) : null,
      ratingText(it.rating) ? '★ ' + ratingText(it.rating) : null, (it.genres || []).slice(0, 2).join(', ')], it.overview || ''));
    return card;
  }

  // ---------------------------------------------------------------- keys
  const rowsOf = () => [...body.querySelectorAll('.row5, .srch5-hist')];
  const firstItem = () => { const r = rowsOf()[0]; return r ? r.querySelector('.focusable') : null; };
  return {
    onKey(e) {
      const ae = document.activeElement;
      if (e.key.startsWith('Arrow')) {
        if (ae === box) {
          if (e.key === 'ArrowDown') { const f = firstItem(); if (f) { f.focus(); f.scrollIntoView({ block: 'nearest', inline: 'nearest' }); } return true; }
          if (e.key === 'ArrowUp') { nav.focusActive(); return true; }
          if (e.key === 'ArrowLeft') { if (mic) mic.focus(); else nav.focusActive(); return true; }
          return false;
        }
        if (ae === mic) {
          if (e.key === 'ArrowRight') box.focus();
          else if (e.key === 'ArrowDown') { const f = firstItem(); if (f) f.focus(); }
          else nav.focusActive();
          return true;
        }
        // Up from the first row (or the history chips' first line) goes to the field, never the mic
        if (e.key === 'ArrowUp' && ae && body.contains(ae)) {
          const row = ae.closest('.row5, .srch5-hist');
          if (row === rowsOf()[0] && !spatialMove(row, 'ArrowUp')) { box.focus(); return true; }
        }
        if (!spatialMove(body, e.key)) { if (e.key === 'ArrowLeft') nav.focusActive(); else if (e.key === 'ArrowUp') box.focus(); }
        return true;
      }
      if (e.key === 'Enter' && ae && scr.contains(ae) && ae !== box) { ae.click(); return true; }
      // the menu key only (letters type into the field)
      if (e.key === 'ContextMenu' && ae && ae.classList.contains('has-menu')) {
        ae.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true })); return true; }
      if (e.key === 'Escape' || (e.key === 'Backspace' && ae !== box)) { if (ae === box) box.blur(); nav.focusActive(); return true; }
      if (e.key.length === 1 && /\S/.test(e.key) && ae !== box) { box.focus(); return false; }   // typing jumps into the field
      return false;
    },
    focusIn() { box.focus(); },
    destroy() { alive = false; if (ctl) ctl.abort(); },
  };
}
