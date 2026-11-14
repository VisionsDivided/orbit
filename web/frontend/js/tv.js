// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Live TV (direction 5, like the TV app): a sub-menu row (Channels · Guide · Recordings · My list), an info block
// for the channel under the cursor (compact) with the live preview top right, quick-select tabs (★ Favourites, All,
// Recent, the profile's pinned groups, "All groups ☰" which opens a side menu with search, folders and pins), a
// time-proportional guide grid with a now line (virtualised rows, guide paged from the backend) and the colour-key
// legend. Channels shows the info block; Guide gives the grid the whole screen.
// Keys: Up from the guide's first row reaches the group tabs, then the sub-menu row, then the top bar.

import { api, cacheGet, cacheSet, myList, inMyList, toggleMyList } from './api.js';
import { layoutEdit, addToGroupMenu } from './editor.js';
import { h, esc, fmtTime, fmtDate, nowSec, toast, menu, debounce } from './util.js';
import * as G from './guide.js';
import { PreviewPlayer } from './engine.js';
import { prefs, setPref } from './prefs.js';
import { state as play, watchProg as watchProg0, playLive as playLive0, resumeFullscreen, setCurrent } from './playback.js';
import { setDock, playerIsOpen } from './player.js';
import { uiLoad, uiSave } from './uistate.js';
import { hasReminder, toggleReminder } from './reminders.js';
import { isUnlocked, setLock, unlock } from './parental.js';
import { guideMapDialog } from './epgpick.js';
import { openLiveExternally } from './external.js';
import { legend, colorKey, COLOR_KEY } from './ui5.js';
import * as LG from './livegroups.js';

const PAGE = 200;
let ROW_H = 52, LABEL_W = 240, narrow = false;     // both scale with the window on wide screens (layout())

// remembered across screen switches
// (group, focused row, scroll position and whether the guide or the group list had focus)
// (extra: the group last picked in the "All groups" side menu, shown as a tab while it is not pinned)
const mem = { group: null, row: 0, winStart: null, follow: true, scroll: 0, zone: 'grid', extra: null };
{ const u = uiLoad().tv; if (u) { mem.group = u.group != null ? u.group : null; mem.row = u.row | 0; mem.scroll = u.scroll | 0; mem.zone = u.zone === 'groups' || u.zone === 'label' ? u.zone : 'grid'; mem.extra = u.extra || null; } }
const saveMem = () => uiSave({ tv: { group: mem.group, row: mem.row, scroll: mem.scroll, zone: mem.zone, extra: mem.extra } });
/** The profile's pinned groups (full names, most recent first; server prefs, shared with the TV). */
const pinsOf = () => LG.normPins(prefs()[LG.PIN_PREF]);
const recentOf = () => { const r = prefs()[LG.RECENT_PREF]; return Array.isArray(r) ? r.filter((x) => x && x.key) : []; };
const guideCache = new Map();   // channel key -> { chunks: Map<chunk, progs[]>, progs: merged[] }

export function favKeys() { return myList().filter((x) => x.kind === 'live').map((x) => x.key); }
export function toggleFavChannel(c) {
  const added = toggleMyList({ key: c.key, kind: 'live', name: c.name, logo: c.logo, ch: c });
  toast(added ? '★ Added to Favourites' : 'Removed from Favourites');
  return added;
}

export function tvScreen(main, nav) {
  main.innerHTML = '';
  const root = h('div', { class: 'tv2' + (uiLoad().tvGuide ? ' guide-only' : '') });
  // the sub-menu row: Channels (info block + guide) · Guide (the guide alone) · Recordings · My list
  const SUB = [['channels', 'Channels'], ['guide', 'Guide'], ['recordings', 'Recordings'], ['mylist', 'My list']];
  const sub = h('div', { class: 'sub5 tv-sub', role: 'navigation', 'aria-label': 'Live TV sections' },
    ...SUB.map(([id, t], i) => h('button', { class: 'pill', type: 'button', tabindex: '-1', 'data-i': i, onclick: () => subAct(i) }, t)));
  const gcol = h('div', { class: 'gcol' }, h('div', { class: 'gcol-h' }, 'TV'));
  const glist = h('div', { class: 'glist' }); gcol.append(glist);

  const pvVideo = h('video', { muted: true, playsinline: true, class: 'pv-video' });
  const pvNote = h('div', { class: 'pv-note', hidden: true, role: 'status' });
  const pvBox = h('div', { class: 'pv', title: 'Click: full screen' }, pvVideo, h('div', { class: 'pv-off' }, ''), pvNote);
  const info = h('div', { class: 'hd-info' });
  const side = h('div', { class: 'hd-side' });
  const header = h('div', { class: 'hd' }, pvBox, info, side);

  const rulerLabel = h('div', { class: 'ruler-label' });
  const rulerTrack = h('div', { class: 'ruler-track' });
  const ruler = h('div', { class: 'ruler' }, rulerLabel, rulerTrack,
    h('div', { class: 'ruler-btns' },
      h('button', { class: 'rb', title: 'Earlier (30 min)', onclick: () => shift(-G.STEP) }, '‹'),
      h('button', { class: 'rb', title: 'Now', onclick: () => { mem.winStart = G.initialStart(nowSec()); mem.follow = true; renderAll(); } }, 'Now'),
      h('button', { class: 'rb', title: 'Later (30 min)', onclick: () => shift(G.STEP) }, '›')));
  const body = h('div', { class: 'gbody', tabindex: '-1' });
  const spacer = h('div', { class: 'gspacer' });
  const nowLine = h('div', { class: 'now-line' });
  const empty = h('div', { class: 'gempty', hidden: true });
  body.append(spacer, empty);
  const bodyWrap = h('div', { class: 'gbody-wrap' }, body, nowLine);
  const nowPill = h('div', { class: 'now-pill', hidden: true });
  ruler.append(nowPill);
  const right = h('div', { class: 'tvr' }, header, gcol, ruler, bodyWrap);
  // the "All groups" side menu: a search field (groups and channels), the groups folded by the text before " | ",
  // a pin on each group (the pinned ones become tabs)
  const gmInput = h('input', { class: 'gm-q', type: 'search', placeholder: 'Search groups and channels', 'aria-label': 'Search groups and channels', autocomplete: 'off', spellcheck: 'false', enterkeyhint: 'search' });
  const gmList = h('div', { class: 'gm-list', role: 'listbox', 'aria-label': 'Groups' });
  const gmPanel = h('div', { class: 'gm-panel', role: 'dialog', 'aria-label': 'All groups' },
    h('div', { class: 'gm-h' }, h('span', {}, 'All groups'), h('button', { class: 'gm-x', type: 'button', title: 'Close (Esc)', onclick: () => closeMenu(true) }, '✕')),
    h('div', { class: 'gm-search' }, gmInput), gmList,
    h('div', { class: 'gm-keys' }, h('button', { type: 'button', class: 'ck-k', onclick: () => pinFocused() }, h('i', { class: 'kr' }), 'Pin / unpin'),
      h('span', { class: 'hn' }, h('b', {}, 'OK'), ' Choose   ', h('b', {}, 'Esc'), ' Close')));
  const gmenu = h('div', { class: 'gmenu', hidden: true }, h('div', { class: 'gm-scrim', onclick: () => closeMenu(true) }), gmPanel);
  root.append(sub, right, gmenu); main.append(root);

  const preview = new PreviewPlayer(pvVideo, () => { pvBox.querySelector('.pv-off').textContent = 'Playing in another window · click to play here'; },
    (msg) => { pvNote.textContent = msg || ''; pvNote.hidden = !msg; });
  let groups = [], chans = [], total = 0, loadingPages = new Set(), zone = mem.zone, gi = 0, si = 0, focusKey = null;
  // what plays from here remembers its group, so the guide can come back to it
  const playLive = (c, ctx = {}) => playLive0(c, { ...ctx, group: mem.group, row: mem.row });
  const watchProg = (c, p, ctx = {}) => watchProg0(c, p, { ...ctx, group: mem.group, row: mem.row });
  let focusProg = null, alive = true, pxPerMin = 4, groupToken = 0, playerOpen = false;
  const rows = new Map(); // idx -> row element
  // a device that never showed the guide (new / cleared browser) opens on the resumed channel
  let focusResumed = !uiLoad().tv && !!play.playing;
  if (mem.group == null) mem.group = prefs().lastGroup || '__all';
  if (mem.winStart == null || mem.follow) mem.winStart = G.initialStart(nowSec());
  mem.winStart = G.clampWin(mem.winStart, nowSec());   // a window left behind now comes back to now

  // ---------- groups ----------
  (async () => {
    const cached = await cacheGet('groups');
    if (cached) setGroups(cached);
    try { const g = (await api.groups()).groups; cacheSet('groups', g); setGroups(g); }
    catch (e) { if (!cached) glist.append(h('div', { class: 'empty' }, 'Could not load: ' + e.message)); }
  })();
  // the household's layout changed (rename / hide / own groups): reload the lists
  async function onLayout() {
    if (!alive) return;
    try { const g = (await api.groups()).groups; cacheSet('groups', g); groups = g; if (!groupExists(mem.group)) mem.group = '__all'; renderGroups(); loadGroup(mem.group); if (menuOpen) renderMenu(); } catch {}
  }
  window.addEventListener('tvbro-layout', onLayout);
  // the profile's state came back from the server (every minute, or the tab shown again): pins set on the TV
  // show up as tabs
  const onState = () => { if (!alive) return; renderGroups(); if (menuOpen) renderMenu(); };
  window.addEventListener('tvbro-state', onState);
  const onUnlocked = () => { if (alive) { renderRows(); renderHeader(); } };
  window.addEventListener('tvbro-unlocked', onUnlocked);
  // a channel got another guide channel (manual mapping): its rows load again (and again while the
  // server fetches the guide for it)
  const epgTimers = [];
  const onEpgMap = (e) => {
    const k = e.detail && e.detail.key; if (!k || !alive) return;
    const again = () => { if (!alive) return; guideCache.delete(k); renderRows(); renderHeader(); };
    again();
    if (e.detail.syncing) for (const ms of [2000, 5000, 10000, 20000, 40000]) epgTimers.push(setTimeout(again, ms));
  };
  window.addEventListener('tvbro-epgmap', onEpgMap);
  const SPECIAL = ['__fav', '__all', '__recent'];
  const groupExists = (id) => SPECIAL.includes(id) || groups.some((g) => g.id === id);
  function setGroups(gs) {
    const first = !groups.length;
    groups = gs;
    if (!groupExists(mem.group)) mem.group = '__all';
    // a group opened from the side menu (or on another visit) stays reachable as a tab
    if (!SPECIAL.includes(mem.group) && !pinsOf().includes((groups.find((g) => g.id === mem.group) || {}).name)) mem.extra = mem.group;
    renderGroups();
    if (first) loadGroup(mem.group);
    if (menuOpen) renderMenu();
  }
  /** The quick-select tabs: ★ Favourites · All · Recent · the pinned groups · (the group picked in the menu) · All groups ☰ */
  let tabs = [];
  function computeTabs() { tabs = LG.quickTabs(groups, pinsOf(), mem.extra && groupExists(mem.extra) ? mem.extra : null); return tabs; }
  function tabIndexOf(id) { const i = tabs.findIndex((t) => t.id === id); return i; }
  function renderGroups() {
    computeTabs();
    if (gi >= tabs.length) gi = tabs.length - 1;
    glist.innerHTML = '';
    tabs.forEach((t, i) => {
      const g = t.group || {};
      const n = t.id === '__fav' ? favKeys().length : t.id === '__recent' ? recentOf().length : g.count;
      const sp = t.kind === 'pin' || t.kind === 'sel' ? LG.splitGroup(t.name) : null;
      const label = t.kind === 'menu' ? [h('span', { class: 'gname' }, t.name), h('span', { class: 'gm-ic', 'aria-hidden': 'true' }, '☰')]
        : [h('span', { class: 'gname' }, (g.locked ? '🔒 ' : ''), sp ? h('span', { class: 'gpre' }, sp.folder + ' · ') : null, sp ? sp.short : t.name),
          t.kind === 'pin' ? h('span', { class: 'gpin', title: 'Pinned' }, '📌') : null];
      const b = h('button', { class: 'gitem' + (t.kind === 'menu' ? ' gmenu-tab' : '') + (t.id === mem.group ? ' sel' : '') + (zone === 'groups' && i === gi ? ' kf' : ''), 'data-i': i, tabindex: '-1', title: t.kind === 'menu' ? 'All groups: search, folders, pins' : t.name },
        ...label, n != null && t.kind !== 'menu' ? h('span', { class: 'count' }, String(n)) : null);
      b.onclick = () => { gi = i; zone = 'groups'; if (t.kind === 'menu') openMenu(); else selectGroup(t.id); };
      glist.append(b);
    });
    const kf = glist.querySelector('.kf') || glist.querySelector('.sel'); if (kf) kf.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    renderSub();
  }
  function selectGroup(id) {
    if (id === '__menu') { openMenu(); return; }
    if (id !== mem.group) { mem.group = id; mem.row = 0; mem.scroll = 0; setPref('lastGroup', id); loadGroup(id); }
    computeTabs(); gi = Math.max(0, tabIndexOf(mem.group));
    renderGroups();
  }
  const selectGroupSoon = debounce((id) => selectGroup(id), 300);

  // ---------- the "All groups" side menu ----------
  // Rows: the search field (mi = -1), then folders / groups (a folder opens in place) and, while searching, the
  // channels whose name matches. The field keeps the DOM focus on keyboards (typing goes there); the rows have
  // a drawn focus (mi). Red (or the pin button) pins / unpins the focused group.
  let menuOpen = false, mi = -1, mrows = [], chanHits = null, chanTotal = 0, searchTok = 0;
  const openFolders = new Set();
  const coarse = () => !!(window.matchMedia && window.matchMedia('(pointer: coarse)').matches);
  function openMenu() {
    if (menuOpen) return;
    menuOpen = true; gmenu.hidden = false; root.classList.add('gm-on');
    mi = -1; gmInput.value = ''; chanHits = null;
    // open on the folder of the group shown now, with that group focused
    const cur = groups.find((g) => g.id === mem.group), sp = cur && LG.splitGroup(cur.name);
    if (sp) openFolders.add(sp.folder.toLowerCase());
    mrows = menuRows();
    const at = mrows.findIndex((r) => r.kind === 'group' && r.group.id === mem.group);
    if (at >= 0) mi = at;
    renderMenu();
    if (!coarse()) gmInput.focus({ preventScroll: true });
  }
  function closeMenu(back) {
    if (!menuOpen) return;
    menuOpen = false; gmenu.hidden = true; root.classList.remove('gm-on');
    if (document.activeElement === gmInput) gmInput.blur();
    if (back) { zone = 'groups'; computeTabs(); gi = Math.max(0, tabs.findIndex((t) => t.kind === 'menu')); renderGroups(); renderRows(); }
  }
  function menuRows() {
    const q = gmInput.value.trim();
    const tree = LG.foldGroups(groups);
    const out = [];
    const groupRow = (g, label, depth) => ({ kind: 'group', group: g, label, depth });
    if (!q) {
      for (const n of tree) {
        if (n.type === 'group') { out.push(groupRow(n.group, n.label, 0)); continue; }
        const open = openFolders.has(n.name.toLowerCase());
        out.push({ kind: 'folder', node: n, open });
        if (open) for (const x of n.groups) out.push(groupRow(x.group, x.label, 1));
      }
      return out;
    }
    const hits = LG.filterTree(tree, q, (chanHits || []).map((c) => c.group).filter(Boolean));
    if (hits.length) out.push({ kind: 'head', text: 'Groups' });
    for (const n of hits) {
      if (n.type === 'group') { out.push(groupRow(n.group, n.label, 0)); continue; }
      out.push({ kind: 'folder', node: n, open: true });
      for (const x of n.groups) out.push(groupRow(x.group, x.label, 1));
    }
    if (chanHits && chanHits.length) {
      out.push({ kind: 'head', text: 'Channels' + (chanTotal > chanHits.length ? ` (first ${chanHits.length} of ${chanTotal})` : '') });
      for (const c of chanHits) out.push({ kind: 'chan', ch: c });
    }
    if (!out.length) out.push({ kind: 'head', text: chanHits ? 'Nothing found' : 'Searching…' });
    return out;
  }
  function renderMenu() {
    mrows = menuRows();
    if (mi >= mrows.length) mi = mrows.length - 1;
    if (mi >= 0 && mrows[mi].kind === 'head') mi = nextRow(mi, 1);
    if (mi >= 0 && mrows[mi].kind === 'head') mi = -1;
    const pins = pinsOf();
    gmList.innerHTML = '';
    gmInput.classList.toggle('kf', mi < 0);
    mrows.forEach((r, i) => {
      if (r.kind === 'head') { gmList.append(h('div', { class: 'gm-head' }, r.text)); return; }
      const cls = 'gm-row' + (i === mi ? ' kf' : '');
      let el;
      if (r.kind === 'folder') {
        el = h('button', { class: cls + ' gm-folder' + (r.open ? ' open' : ''), type: 'button', tabindex: '-1', 'aria-expanded': String(!!r.open) },
          h('span', { class: 'gm-chev', 'aria-hidden': 'true' }, '›'), h('span', { class: 'gm-name' }, r.node.name), h('span', { class: 'gm-n' }, String(r.node.count)));
      } else if (r.kind === 'group') {
        const g = r.group, pinned = pins.includes(g.name), cur = g.id === mem.group;
        el = h('button', { class: cls + ' gm-group' + (r.depth ? ' sub' : '') + (cur ? ' cur' : ''), type: 'button', tabindex: '-1', title: g.name },
          h('span', { class: 'gm-name' }, (g.locked ? '🔒 ' : '') + r.label), g.count != null ? h('span', { class: 'gm-n' }, String(g.count)) : null,
          h('span', { class: 'gm-pin' + (pinned ? ' on' : ''), role: 'button', 'data-pin': '1', title: pinned ? 'Unpin (remove the tab)' : 'Pin as a tab', 'aria-label': (pinned ? 'Unpin ' : 'Pin ') + g.name, 'aria-pressed': String(pinned) }, pinned ? '📌' : '＋'));
      } else {
        const c = r.ch;
        el = h('button', { class: cls + ' gm-chan', type: 'button', tabindex: '-1', title: c.name },
          c.logo ? h('img', { class: 'gm-logo', alt: '', loading: 'lazy', src: api.img(c.logo) }) : h('span', { class: 'gm-logo' }),
          h('span', { class: 'gm-name' }, c.name), h('span', { class: 'gm-grp' }, c.group || ''));
      }
      el.dataset.i = i;
      el.onclick = (ev) => { mi = i; if (ev.target.closest('[data-pin]')) { pinFocused(); return; } menuAct(); };
      gmList.append(el);
    });
    const kf = gmList.querySelector('.kf'); if (kf) kf.scrollIntoView({ block: 'nearest' });
    else if (mi < 0) gmList.scrollTop = 0;
  }
  function nextRow(from, d) {
    for (let i = from + d; i >= 0 && i < mrows.length; i += d) if (mrows[i].kind !== 'head') return i;
    return d < 0 ? -1 : from;
  }
  function menuAct() {
    const r = mrows[mi];
    if (!r || r.kind === 'head') { const first = nextRow(-1, 1); if (first >= 0 && mrows[first] && mrows[first].kind !== 'head') { mi = first; menuAct(); } return; }
    if (r.kind === 'folder') {
      if (gmInput.value.trim()) return;      // while searching, folders stay open
      const k = r.node.name.toLowerCase();
      if (openFolders.has(k)) openFolders.delete(k); else openFolders.add(k);
      renderMenu(); return;
    }
    if (r.kind === 'group') { chooseGroup(r.group.id); return; }
    if (r.kind === 'chan') chooseChannel(r.ch);
  }
  /** A group from the menu: its channels in the guide, the panel closes, the focus goes to the guide. */
  function chooseGroup(id) {
    const g = groups.find((x) => x.id === id);
    if (g && !pinsOf().includes(g.name)) mem.extra = id;
    closeMenu(false);
    selectGroup(id); toLabel();
  }
  /** A channel found by the search: shown in its own group (All when it has none), focused. */
  function chooseChannel(c) {
    const g = groups.find((x) => x.name === c.group);
    const id = g ? g.id : '__all';
    if (g && !pinsOf().includes(g.name)) mem.extra = id;
    closeMenu(false);
    zone = 'label'; focusProg = null;
    if (id !== mem.group) { focusKey = c.key; mem.group = id; mem.row = 0; mem.scroll = -1; setPref('lastGroup', id); computeTabs(); gi = Math.max(0, tabIndexOf(id)); renderGroups(); loadGroup(id); return; }
    const i = chans.findIndex((x) => x && x.key === c.key);
    if (i >= 0) { mem.row = i; scrollRowIntoView(); }
    renderGroupsKf(); renderRows(); renderHeader();
  }
  function pinFocused() {
    const r = mrows[mi];
    if (!r || r.kind !== 'group') { toast('Move to a group to pin it'); return; }
    const name = r.group.name, was = pinsOf().includes(name), next = LG.togglePin(pinsOf(), name);
    setPref(LG.PIN_PREF, next);
    toast(was ? 'Unpinned: ' + name : `Pinned as a tab: ${name}` + (next.length >= LG.MAX_PINS ? ` (at most ${LG.MAX_PINS})` : ''));
    if (!was && mem.extra === r.group.id) mem.extra = null;
    renderGroups(); renderMenu();
  }
  const searchSoon = debounce(async () => {
    const q = gmInput.value.trim(), tok = ++searchTok;
    if (!q) { chanHits = null; renderMenu(); return; }
    try {
      const r = await api.channels('__all', 1, q, 40);
      if (tok !== searchTok || !menuOpen) return;
      chanHits = r.items || []; chanTotal = r.total || chanHits.length;
    } catch { if (tok !== searchTok) return; chanHits = []; chanTotal = 0; }
    renderMenu();
  }, 250);
  gmInput.addEventListener('input', () => { mi = -1; if (!gmInput.value.trim()) chanHits = null; renderMenu(); searchSoon(); });
  /** Keys while the menu is open (from the field or from the screen). True when handled. */
  function menuKey(e) {
    const k = e.key;
    if (k === 'Escape' || k === 'GoBack' || k === 'BrowserBack') { closeMenu(true); return true; }
    if (COLOR_KEY[k] === 'red') { pinFocused(); return true; }
    if (k === 'ArrowDown') { mi = nextRow(mi, 1); renderMenu(); return true; }
    if (k === 'ArrowUp') { mi = mi <= 0 ? -1 : nextRow(mi, -1); renderMenu(); if (mi < 0 && !coarse()) gmInput.focus({ preventScroll: true }); return true; }
    if (k === 'PageDown' || k === 'PageUp') { for (let n = 0; n < 8; n++) mi = k === 'PageDown' ? nextRow(mi, 1) : nextRow(mi, -1); renderMenu(); return true; }
    if (k === 'Enter') { if (mi < 0 && !gmInput.value.trim()) { mi = nextRow(-1, 1); renderMenu(); return true; } menuAct(); return true; }
    if (mi >= 0 && (k === 'ArrowRight' || k === 'ArrowLeft')) {
      const r = mrows[mi];
      if (r.kind === 'folder' && !gmInput.value.trim()) {
        const key = r.node.name.toLowerCase();
        if (k === 'ArrowRight') { if (!openFolders.has(key)) openFolders.add(key); else mi = nextRow(mi, 1); }
        else openFolders.delete(key);
        renderMenu(); return true;
      }
      if (k === 'ArrowLeft' && r.kind === 'group' && r.depth) {     // back to its folder
        for (let i = mi - 1; i >= 0; i--) if (mrows[i].kind === 'folder') { mi = i; break; }
        renderMenu();
      }
      return true;
    }
    // a letter typed while a row has the focus goes to the search field
    if (k.length === 1 && !e.ctrlKey && !e.metaKey && !e.altKey && document.activeElement !== gmInput) { mi = -1; gmInput.focus({ preventScroll: true }); renderMenu(); return false; }
    return false;
  }
  gmInput.addEventListener('keydown', (e) => {
    if ((e.key === 'ArrowLeft' || e.key === 'ArrowRight') && mi < 0) return;     // the caret moves in the text
    if (menuKey(e)) { e.preventDefault(); e.stopPropagation(); }
  });

  async function loadGroup(id) {
    const tok = ++groupToken;
    chans = []; total = 0; loadingPages.clear(); rows.forEach((r) => r.remove()); rows.clear();
    empty.hidden = true;
    if (id === '__fav' || id === '__recent') {
      // show stored copies immediately, then refresh from the backend (Recent: the last channels watched)
      const stored = id === '__fav' ? myList().filter((x) => x.kind === 'live').map((x) => x.ch || { key: x.key, name: x.name, logo: x.logo }) : recentOf();
      const keys = stored.map((c) => c.key);
      chans = stored.slice(); total = chans.length; layout();
      if (keys.length) try {
        const r = await api.channelsByKeys(keys);
        if (tok === groupToken) { const by = new Map(r.items.map((c) => [c.key, c])); chans = keys.map((k) => by.get(k)).filter(Boolean); total = chans.length; layout(); }
      } catch {}
    } else {
      const ck = 'chan:' + id + ':1';
      const cached = await cacheGet(ck);
      if (tok !== groupToken) return;
      if (cached) { applyPage(1, cached); }
      try { const r = await api.channels(id, 1, '', PAGE); if (tok !== groupToken) return; cacheSet(ck, r); applyPage(1, r); }
      catch (e) { if (!cached) { empty.hidden = false; empty.textContent = 'Could not load channels: ' + e.message; } }
    }
    if (!total) { empty.hidden = false; empty.textContent = id === '__fav' ? 'No favourite channels yet. Press F (or the ☆ next to a channel) to add one.' : id === '__recent' ? 'No channels watched yet.' : 'No channels in this group.'; }
    if (focusKey) { const i = chans.findIndex((c) => c && c.key === focusKey); if (i >= 0) mem.row = i; focusKey = null; mem.scroll = -1; }
    mem.row = Math.min(mem.row, Math.max(0, total - 1));
    // back to the same place: the remembered scroll position if the focused row is in it
    layout();
    const vh = body.clientHeight || 600, rowTop = mem.row * ROW_H;
    body.scrollTop = mem.scroll >= 0 && rowTop >= mem.scroll && rowTop + ROW_H <= mem.scroll + vh ? mem.scroll : Math.max(0, rowTop - Math.floor(vh / 2 - ROW_H / 2));
    mem.scroll = body.scrollTop;
    renderAll();
    if (focusResumed && total) { focusResumed = false; focusPlaying(); }
  }
  /** Focus the channel that is playing, in its group (the guide coming back from the player). */
  function focusPlaying() {
    const pc = play.playing; if (!pc || !alive) return;
    const i = chans.findIndex((c) => c && c.key === pc.key);
    if (i >= 0) {
      mem.row = i; zone = 'grid'; focusProg = G.progAt(progsOf(chans[i]), nowSec());
      const vh = body.clientHeight || 600, top = i * ROW_H;
      if (top < body.scrollTop || top + ROW_H > body.scrollTop + vh) body.scrollTop = Math.max(0, top - Math.floor(vh / 2 - ROW_H / 2));
      renderGroupsKf(); renderRows(); renderHeader();
      return;
    }
    const gid = (play.groupId && groupExists(play.groupId) && play.groupId) || (groups.find((x) => x.name === pc.group) || {}).id || '__all';
    if (gid === mem.group) return;
    mem.group = gid; mem.row = play.groupId === gid && play.row != null ? play.row : 0; mem.scroll = -1; zone = 'grid';
    if (!SPECIAL.includes(gid) && !pinsOf().includes((groups.find((x) => x.id === gid) || {}).name)) mem.extra = gid;
    focusKey = pc.key;
    renderGroups(); gi = Math.max(0, tabIndexOf(gid)); loadGroup(gid);
  }
  function applyPage(page, r) {
    total = r.total;
    for (let i = 0; i < r.items.length; i++) {
      const c = chans[(page - 1) * r.size + i] = r.items[i];
      if (play.playing && c.key === play.playing.key && !!c.locked !== !!play.playing.locked) play.playing.locked = !!c.locked;   // locked / unlocked since
    }
    layout();
  }
  function ensurePages(first, last) {
    if (mem.group === '__fav' || mem.group === '__recent') return;
    for (let i = first; i <= last; i++) {
      if (i >= total || chans[i]) continue;
      const page = Math.floor(i / PAGE) + 1;
      if (loadingPages.has(page)) continue;
      loadingPages.add(page);
      const tok = groupToken;
      api.channels(mem.group, page, '', PAGE).then((r) => { if (tok === groupToken) { applyPage(page, r); renderRows(); } })
        .catch(() => {}).finally(() => loadingPages.delete(page));
    }
  }

  // ---------- guide paging ----------
  let guideTimer = null;
  function progsOf(c) { const g = c && guideCache.get(c.key); return g ? g.progs : null; }
  function haveWindow(c) {
    const g = guideCache.get(c.key); if (!g) return false;
    return G.chunksFor(mem.winStart, mem.winStart + G.WINDOW_SEC).every((k) => g.chunks.has(k));
  }
  function requestGuide() { clearTimeout(guideTimer); guideTimer = setTimeout(fetchGuide, 60); }
  function fetchGuide() {
    const [first, last] = visibleRange(2);
    const need = new Map(); // chunk -> keys
    const ws = mem.winStart, we = ws + G.WINDOW_SEC;
    for (let i = first; i <= last; i++) {
      const c = chans[i]; if (!c) continue;
      const g = guideCache.get(c.key) || { chunks: new Map(), progs: [], pending: new Set() };
      guideCache.set(c.key, g); if (!g.pending) g.pending = new Set();
      for (const k of G.chunksFor(ws, we)) if (!g.chunks.has(k) && !g.pending.has(k)) {
        g.pending.add(k); if (!need.has(k)) need.set(k, []); need.get(k).push(c.key);
      }
    }
    const shift = (prefs().epgShiftMin || 0) * 60;
    for (const [k, keys] of need) {
      for (let i = 0; i < keys.length; i += 40) {
        const part = keys.slice(i, i + 40);
        api.guide(part, k * G.CHUNK - shift, (k + 1) * G.CHUNK - shift).then((r) => {
          for (const key of part) {
            const g = guideCache.get(key); if (!g) continue;
            const list = (r.guide[key] || []).map((p) => shift ? { ...p, start: p.start + shift, stop: p.stop + shift } : p);
            g.chunks.set(k, list); g.pending.delete(k);
            g.progs = G.mergeProgs([...g.chunks.values()]);
          }
          if (alive) { renderRows(); renderHeader(); }
        }).catch(() => { for (const key of part) { const g = guideCache.get(key); if (g) g.pending.delete(k); } });
      }
    }
  }

  // ---------- layout / rendering ----------
  function layout() {
    narrow = window.innerWidth < 900;
    root.classList.toggle('narrow', narrow);
    // wide screens: the TV's proportions (a row is 80 px and the channel column 390 px of 1920)
    const rem = parseFloat(getComputedStyle(document.documentElement).fontSize) || 10;
    // Channels (info block above): 64 px rows of 1080, so at least 8 fit; Guide alone keeps 80 px
    const rowH = narrow ? 52 : Math.max(44, Math.round((root.classList.contains('guide-only') ? 8 : 6.4) * rem));
    if (rowH !== ROW_H) { ROW_H = rowH; rows.forEach((r) => r.remove()); rows.clear(); }
    root.style.setProperty('--rowh', ROW_H + 'px');
    LABEL_W = narrow ? 118 : Math.max(240, Math.round(39 * rem)); root.style.setProperty('--lw', LABEL_W + 'px');
    spacer.style.height = (total * ROW_H) + 'px';
    // phones: a fixed scale and the grid scrolls sideways (channel names stay put)
    pxPerMin = narrow ? 4.4 : Math.max(200, body.clientWidth - LABEL_W) / G.WINDOW_MIN;
    const rowW = narrow ? (LABEL_W + G.WINDOW_MIN * pxPerMin) + 'px' : '';
    spacer.style.width = rowW || '1px';
    root.style.setProperty('--roww', rowW || 'auto');
  }
  function visibleRange(pad = 3) {
    const top = body.scrollTop, hgt = body.clientHeight || 600;
    const first = Math.max(0, Math.floor(top / ROW_H) - pad);
    const last = Math.min(total - 1, Math.ceil((top + hgt) / ROW_H) + pad);
    return [first, last];
  }
  function renderAll() { layout(); renderRuler(); renderRows(); renderHeader(); renderGroupsKf(); requestGuide(); }
  function renderGroupsKf() { if (zone !== 'nav' && zone !== 'sub') mem.zone = zone; glist.querySelectorAll('.gitem').forEach((b, i) => b.classList.toggle('kf', zone === 'groups' && i === gi)); renderSub(); }
  function renderSub() {
    const guideOnly = root.classList.contains('guide-only');
    sub.querySelectorAll('.pill').forEach((b, i) => {
      b.classList.toggle('on', (i === 0 && !guideOnly) || (i === 1 && guideOnly));
      b.classList.toggle('kf', zone === 'sub' && i === si);
    });
  }
  /** A sub-menu item: Channels / Guide switch the layout here; Recordings and My list are their own screens. */
  function subAct(i) {
    const id = SUB[i][0];
    if (id === 'recordings' || id === 'mylist') { location.hash = id; return; }
    root.classList.toggle('guide-only', id === 'guide');
    uiSave({ tvGuide: id === 'guide' });
    si = i; layout(); scrollRowIntoView(); renderRuler(); renderRows(); renderHeader(); renderSub();
  }

  function renderRuler() {
    const now = nowSec(), ws = mem.winStart, we = ws + G.WINDOW_SEC;
    rulerLabel.innerHTML = `<div class="rl-date">${esc(fmtDate(ws < G.floor30(now) ? ws : now))}</div><div class="rl-clock">${esc(fmtTime(now))}</div>`;
    rulerTrack.innerHTML = G.ticks(ws, we).filter((t) => t < we).map((t) =>
      `<div class="tick" style="left:${G.tickX(t, ws, pxPerMin).toFixed(1)}px"><i></i>${esc(fmtTime(t))}</div>`).join('');
    rulerTrack.style.left = LABEL_W + 'px';
    syncHScroll();
    if (now >= ws && now < we) { nowLine.hidden = false; nowLine.style.left = (LABEL_W + G.tickX(now, ws, pxPerMin) - body.scrollLeft) + 'px'; }
    else nowLine.hidden = true;
    nowPill.hidden = nowLine.hidden;
    nowPill.textContent = fmtTime(now).replace(/\s?[AP]M$/i, '');
    nowPill.style.left = nowLine.style.left;
  }

  function syncHScroll() {
    const x = body.scrollLeft || 0;
    rulerTrack.firstChild && (rulerTrack.style.transform = `translateX(${-x}px)`);
    if (!nowLine.hidden) { const now = nowSec(); nowLine.style.left = (LABEL_W + G.tickX(now, mem.winStart, pxPerMin) - x) + 'px'; nowLine.style.visibility = G.tickX(now, mem.winStart, pxPerMin) - x < 0 ? 'hidden' : ''; }
    if (nowPill) { nowPill.style.left = nowLine.style.left; nowPill.style.visibility = nowLine.style.visibility; }
  }
  function chanNum(c, i) { return prefs().numbering === 'group' ? i + 1 : (c.number || ''); }
  function labelHtml(c, i) {
    if (!c) return `<div class="g-label"><span class="n"></span><span class="gname dim">…</span></div>`;
    const logo = c.logo ? `<img class="lg" loading="lazy" src="${esc(api.img(c.logo))}">` : '<span class="lg"></span>';
    const fav = inMyList(c.key);
    return `<div class="g-label"><span class="n">${esc(chanNum(c, i))}</span>${logo}<span class="gname">${esc(c.name)}</span>` +
      `${c.catchup ? '<span class="ic-cu" title="Catch-up">⟲</span>' : ''}` +
      `${c.locked ? '<span class="ic-lock" title="Locked (parental PIN)">🔒</span>' : ''}` +
      `<span class="g-fav${fav ? ' on' : ''}" role="button" title="${fav ? 'Remove from Favourites' : 'Add to Favourites'}">${fav ? '★' : '☆'}</span>` +
      `<span class="g-more" role="button" title="Menu">⋯</span></div>`;
  }
  function trackHtml(c) {
    const ws = mem.winStart, we = ws + G.WINDOW_SEC, now = nowSec();
    if (!c) return '';
    const progs = progsOf(c);
    if (!progs && !haveWindow(c)) return `<div class="g-blk gap loading" style="left:0;width:${(G.WINDOW_MIN * pxPerMin - 2).toFixed(1)}px"></div>`;
    let s = '';
    for (const seg of G.rowSegments(progs || [], ws, we)) {
      const b = G.block(seg.start, seg.stop, ws, we, pxPerMin); if (!b) continue;
      const st = `left:${b.x.toFixed(1)}px;width:${b.w.toFixed(1)}px`;
      if (seg.gap) { s += `<div class="g-blk gap" style="${st}">${b.w > 70 ? 'No guide data' : ''}</div>`; continue; }
      const p = seg.prog, state = G.progState(p, now, c);
      const foc = zone === 'grid' && focusProg && focusProg.start === p.start && chans[mem.row] === c;
      const rem = hasReminder(c.key, p.start) ? '<span class="bell">⏰</span>' : '';
      const done = state === 'now' ? G.progress(p, now).frac : 0;     // how far the programme on now is (bar under it)
      s += `<div class="g-blk ${state}${foc ? ' foc' : ''}" data-s="${p.start}" style="${st}">` +
        `<div class="t">${b.clippedLeft ? '‹ ' : ''}${rem}${esc(p.title)}</div>` +
        (b.w > 90 ? `<div class="tm">${esc(fmtTime(p.start))}${b.w > 170 ? ' – ' + esc(fmtTime(p.stop)) : ''}${state === 'catchup' ? ' ⟲' : ''}</div>` : '') +
        (done ? `<i class="g-pg" style="width:${(done * 100).toFixed(1)}%"></i>` : '') + '</div>';
    }
    return s;
  }
  function renderRows() {
    if (!alive) return;
    if (zone !== 'nav' && zone !== 'sub') mem.zone = zone;
    saveMem();
    const [first, last] = visibleRange();
    ensurePages(first, last);
    for (const [i, el] of rows) if (i < first || i > last) { el.remove(); rows.delete(i); }
    for (let i = first; i <= last; i++) {
      const c = chans[i];
      let el = rows.get(i);
      if (!el) {
        el = h('div', { class: 'g-row', 'data-i': i }); el.style.top = (i * ROW_H) + 'px';
        el.innerHTML = labelHtml(c, i) + '<div class="g-track"></div>'; el._key = c && c.key; el._fav = !!(c && inMyList(c.key));
        rows.set(i, el); body.append(el);
      } else if ((c && c.key) !== el._key || !!(c && inMyList(c.key)) !== el._fav) {
        el.firstChild.outerHTML = labelHtml(c, i); el._key = c && c.key; el._fav = !!(c && inMyList(c.key));
      }
      const here = i === mem.row && (zone === 'grid' || zone === 'label');
      el.classList.toggle('rf', here);
      // the channel name has the focus (label zone, or a row without a focused programme yet)
      el.classList.toggle('lf', here && (zone === 'label' || !focusProg));
      el.classList.toggle('playing', !!(c && play.playing && play.playing.key === c.key));
      el.classList.toggle('fav', !!(c && inMyList(c.key)));
      el.lastChild.style.width = (G.WINDOW_MIN * pxPerMin) + 'px';
      el.lastChild.innerHTML = trackHtml(c);
    }
    requestGuide();
  }

  function renderHeader() {
    const c = chans[mem.row], now = nowSec();
    info.innerHTML = ''; side.innerHTML = '';
    if (!c) { info.append(h('div', { class: 'hd-title' }, total ? 'Loading…' : 'No channel')); renderPreview(); return; }
    // nothing played yet on this device: the first channel the guide shows becomes the current one
    if (!play.playing) setCurrent(c, { list: () => chans, group: mem.group, row: mem.row });
    const progs = progsOf(c);
    const p = focusProg || G.progAt(progs, now);
    const st = p ? G.progState(p, now, c) : null;
    const isCur = !!(play.playing && play.playing.key === c.key);
    const num = chanNum(c, mem.row);
    // compact (so more guide rows fit): channel line with the Favourite button, a one-line title, time + progress
    // + time left + next on one line, a one-line description
    const fav = inMyList(c.key);
    const flags = [c.catchup ? `⟲ Catch-up ${c.catchupDays || ''}d` : null, isCur ? (playerOpen ? '▶ Playing' : '▶ In the preview') : null].filter(Boolean);
    info.append(h('div', { class: 'hd-ch' },
      c.logo ? h('img', { class: 'hd-logo', alt: '', src: api.img(c.logo) }) : h('span', { class: 'hd-logo' }),
      h('span', { class: 'hd-chname' }, (num ? num + ' · ' : '') + c.name),
      st === 'now' ? h('span', { class: 'live-pill' }, 'LIVE') : null,
      c.locked ? h('span', { class: 'hd-flag', title: 'Locked (parental PIN)' }, '🔒') : null,
      h('button', { class: 'hd-fav' + (fav ? ' on' : ''), type: 'button', tabindex: '-1', 'aria-pressed': String(fav), title: fav ? 'Remove from Favourites (F)' : 'Add to Favourites (F)',
        onclick: () => { toggleFavChannel(c); renderRows(); renderHeader(); renderGroups(); } }, fav ? '★ Favourited' : '☆ Favourite'),
      flags.length ? h('span', { class: 'hd-flags' }, flags.join('   ')) : null));
    info.append(h('div', { class: 'hd-title' }, p ? p.title : (progs ? 'No guide data' : 'Loading guide…')));
    if (p) {
      const pr = G.progress(p, now);
      const when = st === 'now' ? `${pr.minLeft} min left` : st === 'future' ? (fmtDate(p.start) + ' · starts in ' + Math.ceil((p.start - now) / 60) + ' min')
        : st === 'catchup' ? 'finished · catch-up available' : 'finished';
      const nx = G.nextProg(progs || [], p);
      const bar = st === 'now' ? h('div', { class: 'progress' }, h('i')) : null;
      if (bar) bar.firstChild.style.width = (pr.frac * 100).toFixed(1) + '%';
      info.append(h('div', { class: 'hd-time' }, h('span', {}, `${fmtTime(p.start)} – ${fmtTime(p.stop)}`), bar, h('span', { class: 'hd-left' }, when),
        nx ? h('span', { class: 'hd-next' }, `Next: ${fmtTime(nx.start)} ${nx.title}`) : null));
      const epl = [p.ep, p.sub].filter(Boolean).join(' ');
      if (epl || p.desc) info.append(h('div', { class: 'hd-desc' }, epl ? h('b', { class: 'hd-ep' }, epl + (p.desc ? ' · ' : '')) : null, p.desc || ''));
    }
    side.append(h('div', { class: 'hd-group' }, c.group || ''));
    renderPreview();
  }
  /** The preview plays the current channel, never the focused one: moving through the guide
   *  leaves it alone; Enter on a channel switches it (see enter()). */
  function renderPreview() {
    const P = prefs(), cur = play.playing;
    const off = pvBox.querySelector('.pv-off');
    if (!P.previewOn && !preview.docked) off.textContent = 'Preview off';
    else if (!off.textContent.startsWith('Playing in')) off.textContent = '';
    if (playerOpen) return;
    // a locked channel waits for the PIN (Enter): no stream is asked for until then
    const lockedCur = cur && cur.locked && !isUnlocked() && !(preview.docked && preview.key === cur.key);
    if (lockedCur) { if (preview.key) preview.show(null); pvNote.textContent = '🔒 Locked · Enter to unlock'; pvNote.hidden = false; return; }
    if (pvNote.textContent.startsWith('🔒')) { pvNote.textContent = ''; pvNote.hidden = true; }
    if (P.previewOn && cur) { preview.setMuted(P.previewMuted); preview.show(cur, (k) => api.streamUrl(k || cur.key), preview.key ? 300 : 0); }
    else if (!(preview.docked && cur && preview.key === cur.key)) preview.show(null);
  }

  // ---------- focus / navigation ----------
  function scrollRowIntoView() {
    const top = mem.row * ROW_H, vh = body.clientHeight;
    if (top < body.scrollTop) body.scrollTop = top;
    else if (top + ROW_H > body.scrollTop + vh) body.scrollTop = top + ROW_H - vh;
  }
  function anchorTime() {
    const now = nowSec(), ws = mem.winStart;
    // never before now: Up / Down keep the time column but do not land on a programme that has
    // ended (the window starts at the half hour, so its first minutes are already over)
    if (focusProg) return Math.max(focusProg.start, ws, now);
    return now >= ws && now < ws + G.WINDOW_SEC ? now : ws;
  }
  function moveRow(d) {
    if (!total) return;
    const t = anchorTime();
    mem.row = Math.max(0, Math.min(total - 1, mem.row + d));
    // the channel name keeps the focus going up / down; in the grid the programme at the same time
    focusProg = zone === 'label' ? null : G.progAt(progsOf(chans[mem.row]), t);
    scrollRowIntoView(); renderRows(); renderHeader();
  }
  function setWin(ws) { ws = G.clampWin(ws, nowSec()); if (ws !== mem.winStart) { mem.winStart = ws; mem.follow = ws === G.initialStart(nowSec()); renderRuler(); renderRows(); } }
  function shift(d) { setWin(G.shifted(mem.winStart, d, nowSec())); }
  function moveProg(d) {
    const c = chans[mem.row]; const progs = progsOf(c);
    const ws = mem.winStart, we = ws + G.WINDOW_SEC;
    if (d < 0) {
      // Left: the previous programme, the window back towards now, else the channel name
      const r = G.leftOf(progs, focusProg, ws, nowSec());
      if (r.label) { toLabel(); return; }
      focusProg = r.prog; setWin(r.win);
    } else {
      if (!progs || !progs.length) { shift(G.STEP); return; }
      if (!focusProg) { focusProg = G.progAt(progs, anchorTime()) || G.firstVisible(progs, ws, we); renderRows(); renderHeader(); return; }
      const nx = G.nextProg(progs, focusProg);
      if (nx && nx.start >= we) shift(G.STEP);
      else if (nx) { focusProg = nx; setWin(G.reveal(nx.start, nx.stop, ws)); }
      else shift(G.STEP);
    }
    renderRows(); renderHeader();
  }
  /** Zones, left to right: nav rail < groups < channel name (label) < programmes (grid). */
  function toGroups() {
    zone = 'groups'; focusProg = null;
    computeTabs(); gi = Math.max(0, tabIndexOf(mem.group));   // the selected group gets the focus
    renderGroups(); renderRows(); renderHeader();
  }
  function toLabel() { zone = 'label'; focusProg = null; renderGroupsKf(); renderRows(); renderHeader(); }
  function toGrid() {
    const r = G.rightOfLabel(progsOf(chans[mem.row]), nowSec());
    zone = 'grid'; focusProg = r.prog; setWin(r.win);
    renderGroupsKf(); renderRows(); renderHeader();
  }
  function toNav() { zone = 'nav'; renderGroupsKf(); renderRows(); nav.focusActive(); }
  function toSub() { zone = 'sub'; si = root.classList.contains('guide-only') ? 1 : 0; renderGroups(); renderRows(); }

  /** Enter / OK. On a channel name or its programme on now: the current channel goes full
   *  screen; another channel becomes the current one (the preview switches to it; full screen
   *  when the preview is off). Catch-up plays; a future programme opens its menu. */
  function enter() {
    const c = chans[mem.row]; if (!c) return;
    const now = nowSec(), p = zone === 'grid' ? focusProg : null;
    if (p && p.start > now) { progMenu(); return; }
    if (p && p.stop <= now) { watchProg(c, p, { list: () => chans }); return; }
    const isCur = play.playing && play.playing.key === c.key;
    if (isCur && c.locked && !isUnlocked() && prefs().previewOn) { unlock(`${c.name} is locked: enter the parental PIN`).then((ok) => { if (ok) { renderRows(); renderHeader(); } }); return; }
    if (isCur || !prefs().previewOn) { playLive(c, { list: () => chans }); return; }
    setCurrent(c, { list: () => chans, group: mem.group, row: mem.row });
    renderRows(); renderHeader();
  }
  const watch = enter;
  function progMenu() {
    const c = chans[mem.row]; if (!c) return;
    const p = focusProg || G.progAt(progsOf(c), nowSec());
    const items = [];
    if (p) {
      const st = G.progState(p, nowSec(), c);
      if (st === 'now') items.push({ label: '▶ Watch live', action: () => playLive(c, { list: () => chans }) });
      if (st === 'catchup') items.push({ label: '⟲ Watch catch-up', action: () => watchProg(c, p, { list: () => chans }) });
      if (st === 'future') items.push({ label: hasReminder(c.key, p.start) ? '⏰ Cancel reminder' : '⏰ Remind me',
        action: () => { const on = toggleReminder({ key: c.key, channel: c.name, title: p.title, start: p.start, stop: p.stop, logo: c.logo }); toast(on ? 'Reminder set for ' + fmtTime(p.start) : 'Reminder removed'); renderRows(); } });
      if (st === 'now' || st === 'future') items.push({ label: st === 'now' ? '⏺ Record (rest of this programme)' : '⏺ Record',
        action: async () => { try { const r = await api.record({ key: c.key, title: p.title, start: p.start, stop: p.stop }); toast(st === 'now' ? 'Recording on the server until ' + fmtTime(r.item.stop) : 'Recording scheduled ' + fmtTime(r.item.start) + '–' + fmtTime(r.item.stop)); } catch (e) { toast('Cannot record: ' + e.message); } } });
      if (p.title && st !== 'catchup') items.push({ label: '⟳ Record series (every airing of this title on this channel)',
        action: async () => { try { await api.recordSeries(c.key, p.title); toast('Series recording on: "' + p.title + '"'); } catch (e) { toast('Cannot record: ' + e.message); } } });
    } else items.push({ label: '▶ Watch live', action: () => playLive(c, { list: () => chans }) });
    // the grid never shows the past: earlier programmes are reached from here
    if (c.catchup) items.push({ label: '⟲ Catch-up: earlier programmes…', action: () => catchupMenu(c) });
    items.push({ label: inMyList(c.key) ? '★ Remove from Favourites' : '☆ Add to Favourites', action: () => { toggleFavChannel(c); renderRows(); renderHeader(); renderGroups(); } });
    items.push({ label: '+ Add channel to one of my groups…', action: () => addToGroupMenu(c) });
    items.push({ label: '✎ Rename channel', action: () => { const n = window.prompt('Channel name (empty = the provider\'s name)', c.name); if (n !== null) layoutEdit('channel.rename', { key: c.key, name: n }).then(() => toast('Renamed')).catch((e) => toast(e.message)); } });
    if (!String(mem.group).startsWith('c:')) items.push({ label: 'Hide channel', action: () => layoutEdit('channel.hide', { key: c.key, hidden: true }).then(() => toast('Hidden: show it again in Channels & groups')).catch((e) => toast(e.message)) });
    else items.push({ label: '− Remove from this group', action: () => layoutEdit('channel.remove', { group: mem.group, key: c.key }).then(() => toast('Removed')).catch((e) => toast(e.message)) });
    items.push({ label: '↗ Open in a player app (VLC, link, .m3u)…', action: () => openLiveExternally(c) });
    items.push({ label: '📺 Guide channel… (match it to the guide by hand)', action: () => guideMapDialog(c) });
    // parental PIN: lock / unlock this channel or the whole group (asks for the PIN)
    items.push({ label: c.locked ? '🔓 Unlock channel (parental PIN)' : '🔒 Lock channel (parental PIN)', action: () => setLock('channel', c.key, !c.locked, c.name) });
    const g = groups.find((x) => x.id === mem.group);
    if (g && !String(g.id).startsWith('__')) items.push({ label: g.locked ? `🔓 Unlock group "${g.name}"` : `🔒 Lock group "${g.name}"`, action: () => setLock('group', g.id, !g.locked, g.name) });
    items.push({ label: 'Edit channels & groups…', action: () => { location.hash = 'editor:' + encodeURIComponent(mem.group); } });
    menu(p ? `${c.name} · ${p.title}` : c.name, items);
  }
  /** The channel's past programmes still in its archive, newest first (the grid shows none). */
  async function catchupMenu(c) {
    const now = nowSec(), from = now - Math.max(1, c.catchupDays || 1) * 86400, shift = (prefs().epgShiftMin || 0) * 60;
    let list = [];
    try {
      const r = await api.guide([c.key], from - shift, now - shift);
      list = (r.guide[c.key] || []).map((p) => shift ? { ...p, start: p.start + shift, stop: p.stop + shift } : p);
    } catch (e) { toast('Could not load the guide: ' + e.message); return; }
    const past = list.filter((p) => G.progState(p, now, c) === 'catchup').sort((a, b) => b.start - a.start).slice(0, 80);
    if (!past.length) { toast('No earlier programmes in the archive'); return; }
    menu(c.name + ' · catch-up', past.map((p) => ({
      label: `${fmtDate(p.start)} ${fmtTime(p.start)} · ${p.title}`,
      action: () => watchProg(c, p, { list: () => chans }),
    })));
  }

  // ---------- colour keys: Favourite · Record · Channel options (the legend's items are buttons too) ----------
  // (the TV's yellow "Format: TS / HLS" key has no web counterpart: the browser picks the format itself)
  function colorKeys() {
    return [
      { color: 'red', label: 'Favourite', action: () => { const c = chans[mem.row]; if (c) { toggleFavChannel(c); renderRows(); renderHeader(); renderGroups(); } } },
      { color: 'green', label: 'Record', action: recordFocused },
      { color: 'blue', label: 'Channel options', action: progMenu },
    ];
  }
  async function recordFocused() {
    const c = chans[mem.row]; if (!c) return;
    const now = nowSec(), p = focusProg || G.progAt(progsOf(c), now), st = p ? G.progState(p, now, c) : null;
    if (p && st !== 'now' && st !== 'future') { toast('This programme has ended'); return; }
    try {
      const r = await api.record(p ? { key: c.key, title: p.title, start: p.start, stop: p.stop } : { key: c.key });
      toast(st === 'future' ? 'Recording scheduled ' + fmtTime(r.item.start) + '–' + fmtTime(r.item.stop) : 'Recording on the server until ' + fmtTime(r.item.stop));
    } catch (e) { toast('Cannot record: ' + e.message); }
  }
  root.append(legend(colorKeys(), [['OK', 'Watch'], ['M', 'Menu']]));

  // ---------- input ----------
  let escDown = 0, escFired = false;
  function onKey(e) {
    if (playerOpen || document.querySelector('.modal-back')) return false;
    if (menuOpen) return menuKey(e) || e.key.startsWith('Arrow');
    const k = e.key;
    if (k === 'Escape') {
      if (e.type === 'keydown') {
        if (!escDown) { escDown = Date.now(); escFired = false; }
        else if (!escFired && Date.now() - escDown > 450) { escFired = true; resumeFullscreen(); }
        return true;
      }
      return true;
    }
    if (k === 'p' || k === 'P') { resumeFullscreen(); return true; }
    // the keys reach the guide but the nav rail lost the DOM focus (a click elsewhere): no trap,
    // the guide takes the focus back where it was
    if (zone === 'nav') { zone = mem.zone && mem.zone !== 'nav' && mem.zone !== 'sub' ? mem.zone : 'label'; renderGroupsKf(); renderRows(); renderHeader(); if (k.startsWith('Arrow') || k === 'Enter') return true; }
    if (zone === 'sub') {        // Channels · Guide · Recordings · My list
      if (k === 'ArrowLeft' || k === 'ArrowRight') { si = Math.max(0, Math.min(SUB.length - 1, si + (k === 'ArrowRight' ? 1 : -1))); renderSub(); return true; }
      if (k === 'Enter') { subAct(si); return true; }
      if (k === 'ArrowDown') { toGroups(); return true; }
      if (k === 'ArrowUp') { toNav(); return true; }
      return k.startsWith('Arrow');
    }
    if (zone === 'groups') {     // the group tabs: Left / Right pick one, Down goes into the guide
      if (k === 'ArrowLeft' || k === 'ArrowRight') {
        if (k === 'ArrowLeft' && gi === 0) { toNav(); return true; }
        gi = Math.max(0, Math.min(tabs.length - 1, gi + (k === 'ArrowRight' ? 1 : -1))); renderGroupsKf();
        const t = tabs[gi]; if (t && t.kind !== 'menu') selectGroupSoon(t.id);    // "All groups" opens on OK / Down only
        return true;
      }
      if (k === 'ArrowDown' || k === 'Enter') { const t = tabs[gi]; if (t && t.kind === 'menu') { openMenu(); return true; } if (t) selectGroup(t.id); toLabel(); return true; }
      if (k === 'ArrowUp') { toSub(); return true; }
      return k === 'PageUp' || k === 'PageDown';
    }
    if (k === 'ArrowUp' && mem.row === 0 && (zone === 'label' || zone === 'grid')) { toGroups(); return true; }   // the tabs are above the guide
    const keys = colorKeys();
    if (colorKey(e, keys)) return true;
    switch (k) {
      case 'ArrowUp': moveRow(-1); return true;
      case 'ArrowDown': moveRow(1); return true;
      case 'PageUp': moveRow(-Math.max(1, Math.floor(body.clientHeight / ROW_H) - 1)); return true;
      case 'PageDown': moveRow(Math.max(1, Math.floor(body.clientHeight / ROW_H) - 1)); return true;
      case 'ArrowLeft': if (zone === 'label') toGroups(); else moveProg(-1); return true;
      case 'ArrowRight': if (zone === 'label') toGrid(); else moveProg(1); return true;
      case 'Enter': enter(); return true;
      case 'm': case 'M': case 'ContextMenu': case 'i': case 'I': progMenu(); return true;
      case 'f': case 'F': { const c = chans[mem.row]; if (c) { toggleFavChannel(c); renderRows(); renderHeader(); renderGroups(); } return true; }
      case 'Backspace': toGroups(); return true;
      case 'Home': mem.winStart = G.initialStart(nowSec()); mem.follow = true; focusProg = null; renderAll(); return true;
    }
    return false;
  }
  function onKeyUp(e) {
    if (e.key !== 'Escape') return;
    const short = escDown && !escFired && Date.now() - escDown <= 450;
    escDown = 0;
    if (short && !playerOpen && !document.querySelector('.modal-back')) { if (zone === 'grid' || zone === 'label') toGroups(); else if (zone === 'groups' || zone === 'sub') toNav(); }
  }

  function rowOf(ev) { const r = ev.target.closest('.g-row'); return r ? +r.dataset.i : -1; }
  function focusFromEvent(ev) {
    const i = rowOf(ev); if (i < 0 || !chans[i]) return false;
    mem.row = i;
    const blk = ev.target.closest('.g-blk[data-s]');
    focusProg = blk ? (progsOf(chans[i]) || []).find((p) => p.start === +blk.dataset.s) || null : null;
    zone = focusProg || ev.target.closest('.g-track') ? 'grid' : 'label';
    renderGroupsKf(); renderRows(); renderHeader(); return true;
  }
  let pressTimer = 0, longFired = false, lastPointer = 'mouse';
  body.addEventListener('pointerdown', (ev) => {
    lastPointer = ev.pointerType; longFired = false; clearTimeout(pressTimer);
    if (ev.pointerType === 'mouse') return;
    pressTimer = setTimeout(() => { longFired = true; if (focusFromEvent(ev)) progMenu(); }, 550);
  });
  ['pointerup', 'pointercancel', 'pointerleave'].forEach((n) => body.addEventListener(n, () => clearTimeout(pressTimer)));
  body.addEventListener('scroll', () => clearTimeout(pressTimer), { passive: true });
  body.addEventListener('click', (ev) => {
    if (longFired) { longFired = false; return; }
    if (ev.target.closest('.g-fav')) { if (focusFromEvent(ev)) { toggleFavChannel(chans[mem.row]); renderRows(); renderHeader(); renderGroups(); } return; }
    if (ev.target.closest('.g-more')) { if (focusFromEvent(ev)) progMenu(); return; }
    if (lastPointer !== 'mouse') {
      const i = rowOf(ev), blk = ev.target.closest('.g-blk[data-s]');
      const same = i === mem.row && (zone === 'grid' || zone === 'label') && (blk ? focusProg && focusProg.start === +blk.dataset.s : !focusProg);
      if (same) { watch(); return; }
    }
    focusFromEvent(ev);
  });
  body.addEventListener('dblclick', (ev) => { if (focusFromEvent(ev)) watch(); });
  body.addEventListener('contextmenu', (ev) => { if (focusFromEvent(ev)) { ev.preventDefault(); progMenu(); } });
  let wheelAt = 0;
  body.addEventListener('wheel', (ev) => {
    const horiz = Math.abs(ev.deltaX) > Math.abs(ev.deltaY) || ev.shiftKey;
    if (!horiz) return;
    ev.preventDefault();
    if (Date.now() - wheelAt < 180) return; wheelAt = Date.now();
    const d = (ev.deltaX || ev.deltaY) > 0 ? 1 : -1; shift(d * G.STEP);
  }, { passive: false });
  let raf = 0;
  body.addEventListener('scroll', () => { mem.scroll = body.scrollTop; syncHScroll(); if (!raf) raf = requestAnimationFrame(() => { raf = 0; renderRows(); }); });
  pvBox.addEventListener('click', () => { const c = play.playing || chans[mem.row]; if (c) playLive(c, { list: play.list || (() => chans) }); });
  const ro = new ResizeObserver(() => { layout(); renderRuler(); renderRows(); }); ro.observe(body);

  const onPlayer = (e) => { playerOpen = e.detail.open; if (playerOpen) { preview.stop(false); pvBox.querySelector('.pv-off').textContent = ''; } else { focusPlaying(); renderRows(); renderHeader(); } };
  window.addEventListener('tvbro-player', onPlayer);
  // leaving full screen: the live picture shrinks into the preview box and keeps playing there
  setDock((ch) => {
    // also with the preview off: what was watched keeps playing in the box (never paused / reloaded)
    if (!alive || !root.isConnected || pvBox.offsetParent === null) return null;
    return { rect: () => pvBox.getBoundingClientRect(), accept: (video, eng, srcKey) => { preview.adopt(video, eng, ch.key, ch, srcKey); pvBox.querySelector('.pv-off').textContent = ''; } };
  });
  if (playerIsOpen() && play.playing) setTimeout(focusPlaying, 0);
  const tick = setInterval(() => {
    // the guide follows the clock: a window that time has left behind snaps to now, and a focused
    // programme that has ended moves to the one on now
    const now = nowSec(), ws = mem.follow ? G.initialStart(now) : G.clampWin(mem.winStart, now);
    if (ws !== mem.winStart) { mem.winStart = ws; mem.follow = ws === G.initialStart(now); }
    if (focusProg && focusProg.stop <= now) focusProg = G.progAt(progsOf(chans[mem.row]), now);
    renderRuler(); renderRows(); renderHeader();
  }, 30000);

  layout();
  // read-only snapshot for the browser tests (web/tests/e2e): where the guide's own focus is
  root.tvState = () => ({ zone, row: mem.row, group: mem.group, gi, winStart: mem.winStart, total,
    tabs: tabs.map((t) => t.id), menuOpen, menuFocus: mi, pins: pinsOf(),
    focus: focusProg ? { start: focusProg.start, stop: focusProg.stop, title: focusProg.title } : null,
    current: play.playing ? play.playing.key : null, rowKey: chans[mem.row] ? chans[mem.row].key : null,
    previewKey: preview.key, previewPending: preview.pending || null, docked: !!preview.docked });
  return {
    onKey, onKeyUp,
    virtualFocus: true,          // the guide draws its own focus; app.js must not move the DOM focus into it
    focusIn() { const z = mem.zone && mem.zone !== 'nav' && mem.zone !== 'sub' ? mem.zone : 'label'; if (z === 'groups') toGroups(); else { zone = z; renderGroupsKf(); renderRows(); renderHeader(); } },
    destroy() { alive = false; setDock(null); preview.dispose(); clearInterval(tick); ro.disconnect(); window.removeEventListener('tvbro-player', onPlayer); window.removeEventListener('tvbro-layout', onLayout); window.removeEventListener('tvbro-state', onState); window.removeEventListener('tvbro-unlocked', onUnlocked); window.removeEventListener('tvbro-epgmap', onEpgMap); epgTimers.forEach(clearTimeout); },
  };
}
